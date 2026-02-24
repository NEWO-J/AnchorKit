# AnchorKit Architecture Diagrams

Three diagrams covering the full lifecycle of a photo in the AnchorKit system.

---

## 1 · Photo Submission Pipeline

```
╔══════════════════════════════════════════════════════════════════════════════════╗
║                      ANCHORKIT · PHOTO SUBMISSION PIPELINE                      ║
╚══════════════════════════════════════════════════════════════════════════════════╝

  ANDROID DEVICE                            ANCHORKIT API            SOLANA BLOCKCHAIN
  ══════════════                            ═════════════            ═════════════════

  [User taps shutter]
          │
          ▼  AnchorKit.captureAndSubmit()
  ┌───────────────────────────────────────────────────────────────────────────────┐
  │  ① DeviceIntegrity.check()                                                    │
  │     ├─ build tag = "test-keys"?  (unlocked bootloader)                        │
  │     ├─ HARDWARE = goldfish / ranchu? MODEL/MANUFACTURER emulator strings?     │
  │     ├─ /system/xbin/su  ·  /data/adb/magisk.db  ·  /sbin/magisk present?     │
  │     └─ /system partition writable?                                             │
  │         ✗ → throw AnchorKitError.DeviceIntegrityError                         │
  └───────────────────────────────────┬───────────────────────────────────────────┘
                                      │ ✓ PASS
                                      ▼
  ┌───────────────────────────────────────────────────────────────────────────────┐
  │  ② PhotoCapture.capturePhoto()                                                │
  │     ├─ CameraX  ImageCapture  CAPTURE_MODE_MAXIMIZE_QUALITY                   │
  │     ├─ raw JPEG bytes pulled from image.planes[0].buffer  (no re-encode)      │
  │     ├─ EXIF scan  → width × height  (no full decode)                          │
  │     └─ timestamp = System.currentTimeMillis()                                  │
  │                                                                                │
  │  ③ HashUtils.hashPhoto()                                                      │
  │     └─ SHA-256(raw JPEG bytes)  →  64-char lowercase hex  "hash"              │
  │                                                                                │
  │  ④ Build metadata map                                                         │
  │     { "timestamp": "…", "dimensions": "WxH", "platform": "android" }         │
  └───────────────────────────────────┬───────────────────────────────────────────┘
                                      │
                      ┌───────────────┴──────────────────────┐
                      │  GET /api/attestation-challenge       │
                      ├──────────────────────────────────────►│
                      │◄─────────────────────────────────────-┤
                      │  { nonce, expires_at }                │
                      └───────────────┬──────────────────────┘
                                      │
                                      ▼
  ┌───────────────────────────────────────────────────────────────────────────────┐
  │  ⑤ EnclaveAttestation.sign(hash, nonce, metadata)                            │
  │                                                                                │
  │     Key provisioning (AndroidKeyStore, alias "anchorkit_attestation_key"):    │
  │       ┌─ StrongBox  (dedicated security chip, API 28+)  ← tried first        │
  │       └─ TEE        (Trusted Execution Environment)      ← fallback           │
  │       Key: EC secp256r1 (P-256)  ·  re-generated every 30 days               │
  │                                                                                │
  │     metaHash = SHA-256( "dimensions=WxH,platform=android,timestamp=…" )      │
  │                          ↑ keys sorted lexicographically                       │
  │                                                                                │
  │     payload   = "hash:nonce:metaHash"  (UTF-8 bytes)                         │
  │     signature = SHA256withECDSA( payload, private key )                       │
  │     certChain = [ deviceLeaf · intermediateCA · GoogleCA ]  (Base64 DER)     │
  │                   ↑ private key NEVER leaves the secure element               │
  └───────────────────────────────────┬───────────────────────────────────────────┘
                                      │
                      ┌───────────────┴──────────────────────────────────────────┐
                      │  POST /api/submit-hash                                   │
                      │  { hash, nonce, enclave_signature, device_attestation,   │
                      │    timestamp, metadata, api_key }                        │
                      ├─────────────────────────────────────────────────────────►│
                      │                                                           │
                      │                  API validates:                           │
                      │                  ✓ nonce single-use (replay prevention)  │
                      │                  ✓ enclave_sig vs pubkey in certChain    │
                      │                  ✓ certChain rooted at Google CA         │
                      │                  ✓ verifiedBootState = Verified          │
                      │                    (hardware-enforced, unforgeable)      │
                      │                  ✓ metaHash matches submitted metadata   │
                      │                  → hash stored in day's queue            │
                      │                                                           │
                      │◄─────────────────────────────────────────────────────────┤
                      │  VerificationReceipt                                     │
                      │  { hash, day, hash_id, table, timestamp,                │
                      │    attestation_verified, cert_fingerprint,               │
                      │    cert_valid_from, cert_valid_until }                   │
                      └───────────────┬──────────────────────────────────────────┘
                                      │
  ┌───────────────────────────────────┴───────────────────────────────────────────┐
  │  ⑥ CaptureResult { photo: PhotoResult, receipt: VerificationReceipt }        │
  │     returned to the calling application                                       │
  └───────────────────────────────────────────────────────────────────────────────┘


  ╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌ NIGHTLY BATCH JOB (AnchorKit backend) ╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌

  ┌─────────────────────────────────┐
  │  Collect all hashes for the day │
  │  [ h0, h1, h2, h3, … hN ]      │
  └──────────────┬──────────────────┘
                 │
                 ▼
  ┌─────────────────────────────────┐   RFC 6962 domain separation:
  │        Build Merkle Tree        │   leaf  node = SHA-256( 0x00 ‖ hash )
  │                                 │   inner node = SHA-256( 0x01 ‖ left ‖ right )
  │            [ Root ]             │
  │            /      \             │
  │          N01      N23           │
  │          / \      / \           │
  │         h0  h1  h2  h3  …      │
  └──────────────┬──────────────────┘
                 │  Merkle root R
                 ▼
  ┌─────────────────────────────────┐       write root to registry PDA
  │  Anchor root to Solana          │──────────────────────────────────────────────►
  │  (Anchor program, registry PDA) │                                 TX on-chain
  └──────────────┬──────────────────┘◄──────────────────────────────────────────────
                 │  solana_tx signature                              (immutable)
                 ▼
  ┌─────────────────────────────────┐
  │  Generate PortableProof per     │
  │  hash; available at             │
  │  GET /api/proof/{hash}          │
  └─────────────────────────────────┘
```

---

## 2 · Online Verification

```
╔══════════════════════════════════════════════════════════════════════════════════╗
║              ANCHORKIT · ONLINE VERIFICATION  (AnchorKit.verify)                ║
╚══════════════════════════════════════════════════════════════════════════════════╝

  ANDROID DEVICE                       ANCHORKIT API              SOLANA BLOCKCHAIN
  ══════════════                       ═════════════              ═════════════════

  ┌────────────────────────────┐
  │  AnchorKit.verify(hash)    │
  │  AnchorKitClient           │
  │   .verifyHash(hash)        │
  └──────────────┬─────────────┘
                 │
                 │  GET /api/verify-hash/{hash}  (TLS + cert-pinned)
                 ├────────────────────────────────────────────────►
                 │                   │
                 │                   │  lookup hash in database
                 │                   │  retrieve stored merkle_proof,
                 │                   │  merkle_root, solana_tx, hash_id
                 │                   │
                 │◄───────────────────┘
                 │
                 │  VerificationResult {
                 │    verified:             Boolean
                 │    day:                  "YYYY-MM-DD"
                 │    hash_id:              Int        ← position in daily batch
                 │    merkle_proof:         [[sibling, "left"|"right"], …]
                 │    merkle_root:          "0x…"
                 │    solana_tx:            "<base-58 tx signature>"
                 │    solana_program:       "<program ID>"
                 │    chain:               "devnet" | "mainnet"
                 │    attestation_verified: Boolean
                 │    cert_fingerprint:     String
                 │  }
                 │
  ┌──────────────┴──────────────────────────────────────────────────────┐
  │  Possible outcomes:                                                  │
  │                                                                      │
  │  verified = false, merkle_proof = null                               │
  │    → Hash not found / not yet submitted                              │
  │                                                                      │
  │  verified = false, merkle_proof present                              │
  │    → Batched but tonight's blockchain anchor hasn't run yet          │
  │                                                                      │
  │  verified = true, solana_tx present                                  │
  │    → Hash is anchored on Solana  ✓                                  │
  └──────────────────────────────────────────────────────────────────────┘


  ┌──────────────────────────────────────────────────────────────────────┐
  │  TRUST MODEL                                                          │
  │  ─────────────────────────────────────────────────────────────────── │
  │  • Requires active internet connection                                │
  │  • Caller trusts the AnchorKit API server to report accurately       │
  │  • API performs Solana lookups on your behalf                         │
  │  • merkle_proof is included in the response so the caller CAN        │
  │    independently re-verify the math if desired                       │
  │  • Fastest method; no local crypto work                               │
  └──────────────────────────────────────────────────────────────────────┘
```

---

## 3 · Offline Proof Bundle Verification

```
╔══════════════════════════════════════════════════════════════════════════════════╗
║          ANCHORKIT · OFFLINE PROOF BUNDLE VERIFICATION (verifyLocally)          ║
╚══════════════════════════════════════════════════════════════════════════════════╝

  ┌──────────────────── Stored PortableProof (schema_version = 1) ─────────────────┐
  │  hash                 "abcd…ef"            SHA-256 of original JPEG            │
  │  day                  "2025-11-14"         calendar date of submission         │
  │  hash_id              42                   position in that day's batch        │
  │  timestamp            1731542400           Unix epoch seconds                  │
  │  merkle_proof         [ ["a1b2…", "left"],  ← sibling hash + its position     │
  │                         ["c3d4…", "right"], …]                                 │
  │  merkle_root          "0xe5f6…"            root of that day's tree            │
  │  solana_program       "<base-58 program ID>"                                   │
  │  solana_registry_pda  "<base-58 PDA address>"                                  │
  │  solana_chunk_index   0                                                         │
  │  solana_tx            "<base-58 tx signature>"   (audit trail)                │
  │  chain                "mainnet"                                                 │
  └──────────────────────────────────────────┬─────────────────────────────────────┘
                                             │
                    ┌────────────────────────┴─────────────────────────┐
                    │                                                   │
                    ▼                                                   │
  ════════════════════════════════════════════════════════════          │
  STEP 1 · Merkle proof math              [LOCAL — no network]          │
  ════════════════════════════════════════════════════════════          │
                                                                        │
  SolanaVerifier.verifyMerkleProof(hash, merkle_proof, merkle_root)    │
                                                                        │
  current = SHA-256( 0x00 ‖ hash_utf8 )      ← RFC 6962 leaf node     │
                                                                        │
  for each [ sibling, position ] in merkle_proof:                      │
    position == "left"  → current = SHA-256( 0x01 ‖ sibling ‖ current )│
    position == "right" → current = SHA-256( 0x01 ‖ current ‖ sibling )│
                              ↑ 0x01 prefix = inner-node domain sep.   │
                                                                        │
  current == proof.merkle_root  (strip "0x", lowercase)                │
    ✗ → LocalVerificationResult(valid=false,                           │
            reason="Merkle proof math failed")                          │
    ✓ → continue ──────────────────────────────────────────────────────┘
                    │
                    ▼
  ════════════════════════════════════════════════════════════
  STEP 2 · PDA integrity check            [LOCAL — no network]
  ════════════════════════════════════════════════════════════

  SolanaVerifier.derivePda(solana_program, solana_chunk_index)

  seeds = [ "merkle_registry", chunk_index as little-endian u16 ]

  for bump in 255 → 0:                        (Solana PDA derivation)
    candidate = SHA-256( seeds ‖ bump_byte ‖ program_id_bytes )
    if candidate is NOT a valid Ed25519 curve point:
      → derived_pda = base58(candidate)
      break

  derived_pda == proof.solana_registry_pda ?
    ✗ → LocalVerificationResult(valid=false,
            reason="PDA mismatch — proof may have been tampered")
    ✓ → continue
                    │
                    ▼
  ════════════════════════════════════════════════════════════
  STEP 3 · Read on-chain registry         [ONE Solana RPC call]
  ════════════════════════════════════════════════════════════

  SolanaVerifier.fetchRootForDate(pda, day, rpcUrl)

  POST https://api.mainnet-beta.solana.com          SOLANA BLOCKCHAIN
  {                                           ──────────────────────────►
    "method": "getAccountInfo",               getAccountInfo(pda)
    "params": [ pda, {"encoding":"base64"} ]  ◄──────────────────────────
  }                                             base64-encoded account data

  Parse Borsh binary layout:
  ┌───────────────────────────────────────────────────────────────┐
  │  offset   type     field                                       │
  │  0–7      [u8;8]   Anchor discriminator           → skip      │
  │  8–9      u16      chunk_index                    → skip      │
  │  10–11    u16      entries_count                              │
  │  12–43    [u8;32]  authority pubkey               → skip      │
  │  44       u8       has_next_chunk flag                        │
  │  45–76    [u8;32]  next_chunk ptr (if flag == 1)  → skip     │
  │  ─ ─ ─   repeat entries_count times ─ ─ ─                    │
  │           [u8;32]  merkle_root                               │
  │           u32      date string length                        │
  │           [u8;N]   date string  "YYYY-MM-DD"  (UTF-8)       │
  │           i64      timestamp                                  │
  │  → return root where date == proof.day                       │
  └───────────────────────────────────────────────────────────────┘
                    │
                    ▼
  ════════════════════════════════════════════════════════════
  STEP 4 · Root comparison                [LOCAL — no network]
  ════════════════════════════════════════════════════════════

  proof.merkle_root.removePrefix("0x").lowercase()
        ==
  on_chain_root.removePrefix("0x").lowercase()

    ✗ → LocalVerificationResult(valid=false,
            reason="Root mismatch — on-chain differs from proof")
    ✓ → LocalVerificationResult(valid=true)


  ┌──────────────────────────────────────────────────────────────────────┐
  │  TRUST MODEL                                                          │
  │  ─────────────────────────────────────────────────────────────────── │
  │  • Zero trust in AnchorKit servers                                    │
  │  • Zero trust in AWS, CDN, or any intermediary                       │
  │  • Only trusts the Solana blockchain (public, immutable, any RPC)    │
  │  • Proof bundle can be stored once and re-verified forever            │
  │  • Only 1 network call (Solana JSON-RPC); can use any public node    │
  │  • Steps 1, 2, 4 are pure cryptographic math — cannot be forged     │
  └──────────────────────────────────────────────────────────────────────┘
```
