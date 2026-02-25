<p align="center">
  <img src="assets/anchorkit_logo.png" alt="AnchorKit" width="600"/>
</p>

<p align="center">
  <img alt="Version" src="https://img.shields.io/badge/version-1.0.1-blue"/>
  <img alt="API Level" src="https://img.shields.io/badge/API-24%2B-brightgreen"/>
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-2.1.0-purple?logo=kotlin"/>
  <img alt="License" src="https://img.shields.io/badge/license-MIT-green"/>
  <img alt="Platform" src="https://img.shields.io/badge/platform-Android-3DDC84?logo=android"/>
</p>

Ahoy! **AnchorKit** is a hardware-attested photo provenance SDK for Android (*With iOS to come*).
It lets you prove, cryptographically, that a real photo was taken by a specific physical device, at a specific time.


## ⚓ How It Works
- All media is hardware attested using **secure hardware enclave** (TEE).
- Each night, submissions are aggregated daily into a Merkle tree, and the root hash is anchored to the Solana blockchain.
- Proof bundles are **fully** self-contained. Media remains independently verifiable without relying on AnchorKit, AWS, or any third party.
- Verification requires only the proof bundle and a single Solana RPC call.
- Drop-in integration - integrates within your app's existing camera pipeline with minimal code changes.

## 🏴‍☠️ Capturing Truth at The Source

C2PA embeds credentials **inside the file** — a metadata layer appended to the JPEG/MP4.
Every major social platform (Instagram, Twitter/X, WhatsApp) strips or recompresses that layer on upload. The moment the file is shared, the credential is gone.

AnchorKit takes the opposite approach: **the proof never lives in the file at all.**
It lives on the Solana blockchain. No matter how many times a photo is shared, reposted, screenshotted, or re-compressed, the original can always be verified against an immutable public ledger that no one — not AnchorKit, not AWS, not any certificate authority — controls.

The capture-time proof is also stronger at the source. AnchorKit requires that the signing key was generated inside a **hardware secure element (StrongBox or TEE)** with a verified, locked bootloader. C2PA has no such requirement — a software certificate on any machine is sufficient to produce a "valid" C2PA credential. Independent security researcher Dr. Neal Krawetz [demonstrated in December 2023](https://hackerfactor.com/blog/index.php?/archives/1018-C2PAs-Worst-Case-Scenario.html) that C2PA metadata can be trivially forged using Adobe's own open-source `c2patool`, with **cryptographic signatures that pass every C2PA validator**, because the standard places no cryptographic verification on what hardware or software did the signing.

AI-based image detection adds a third approach, but it is fundamentally probabilistic: a classifier that guesses whether an image looks AI-generated, looks altered, or looks authentic. It has no chain of custody, cannot be audited, produces false positives that harm legitimate photographers, and is easily defeated by adversarial fine-tuning. It establishes no legal record.

### How the Three Approaches Compare

| | AnchorKit | C2PA | AI Image Detection |
|---|---|---|---|
| **Where proof lives** | Solana blockchain (external, permanent) | Inside the file (JUMBF metadata) | Nowhere — result is ephemeral |
| **Survives social media sharing?** | ✅ Proof is independent of the file | ❌ Stripped on recompression by every major platform | ✅ (Re-runs on any copy, but result changes with compression) |
| **Hardware attestation required?** | ✅ StrongBox / TEE mandatory; verified boot state enforced | ❌ Any software certificate is valid | ❌ Not applicable |
| **Private key ever leaves device?** | ❌ Never — hardware-enforced | ✅ Must be distributed to device; user has API access | ❌ Not applicable |
| **Can be forged with cheap tools?** | ❌ Requires bypassing TEE + Solana blockchain | ✅ Yes — `c2patool` produces valid signatures on any laptop ([demonstrated publicly](https://hackerfactor.com/blog/index.php?/archives/1018-C2PAs-Worst-Case-Scenario.html)) | ✅ Adversarial fine-tuning defeats classifiers |
| **Trust model** | Zero-trust / hardware-rooted | Honor system ("we trust the signer") | Probabilistic guess |
| **Certificate authority dependency?** | ❌ None | ✅ Yes — CA compromise enables retroactive forgery of any historical image | ❌ Not applicable |
| **Historical records safe if CA breached?** | ✅ Blockchain entries are immutable | ❌ Revoked/forged CA certs can be backdated; C2PA spec explicitly permits revoked certificates | ❌ Not applicable |
| **Cert cost to produce "valid" credential** | N/A (hardware-bound, not cert-bound) | $0 (self-signed, flagged "unknown") to $289/year (trusted CA) | $0 |
| **Verify without any third party?** | ✅ Any public Solana RPC node | ❌ Requires CA infrastructure + Content Credentials service | ❌ Requires running the model |
| **Tamper detection** | ✅ Cryptographic — hash mismatch is definitive | ⚠️ "Tamper-evident" in name only — forged signatures pass all validators; valid signature proves nothing about signer honesty | ❌ Probabilistic — altered images may still pass |
| **Certifies device identity?** | ✅ Yes — TEE + verified boot state, cryptographically bound | ❌ No — certifies signer identity only (who, not what hardware) | ❌ No |
| **False positives harm authentic users?** | ❌ No — proof is binary pass/fail | ⚠️ Invalid sig doesn't mean content was altered (e.g. Windows Photo Gallery auto-updates metadata, breaking the signature innocently) | ✅ Yes — authentic photos regularly flagged as AI-generated |
| **Legal strength** | ✅ Blockchain record is auditable by any party with a Solana node | ⚠️ Risky — valid forged C2PA signatures are accepted as authentic by courts ([forensics expert analysis](https://hackerfactor.com/blog/index.php?/archives/1018-C2PAs-Worst-Case-Scenario.html)); invalid sig doesn't prove content was altered | ❌ Expert testimony on classifier output is weak evidence |
| **Works after cert expiry?** | ✅ Blockchain record is permanent | ❌ Certificates expire; backdating lets forgers sign with expired certs undetected | ❌ Not applicable |
| **Controlled by a single company?** | ❌ Open Solana blockchain | ⚠️ Adobe controls the trust list and Content Credentials service; changes made unilaterally without community announcement | Depends on vendor |
| **Works for AI-generated image labeling?** | ✅ Absence of AnchorKit proof means no hardware-attested origin | ⚠️ Can label as "AI-generated" but label is as forgeable as any other C2PA claim | ⚠️ Probabilistic only; adversarially defeatable |

### The Forgery Problem in Plain Terms

Every C2PA validator — including Adobe's own `c2patool` and the Content Credentials website — checks whether the cryptographic signature is valid. What it **cannot** check is whether the person who signed the content is who they claim to be, whether the signing device was a real camera, or whether the content matches the claim. Dr. Krawetz fabricated a convincing forgery attributing illegal content to a named individual using a copied Leica certificate. Every C2PA tool reported the signatures as valid. The only defense against the forgery was to demonstrate, to a non-technical court, that "valid signatures" do not mean "authentic content" — arguing against the explicit marketing claims of Adobe, Microsoft, Intel, Sony, and other C2PA backers.

With AnchorKit, the forgery path is closed at the hardware level. A valid AnchorKit submission requires the signing key to have been generated inside a certified secure element on a device that passed Android Verified Boot at the time of key generation. That condition is cryptographically attested by Google's certificate authority chain and verified server-side. There is no `c2patool` equivalent — no off-the-shelf CLI that produces a passing attestation from a laptop.

## Anchor Demo

The demo app (`demo-app/`) shows a minimal integration. Three calls cover the full capture-to-proof lifecycle:

```kotlin
// 1. Initialize (once, e.g. in Application.onCreate)
val anchorKit = AnchorKit(context, apiKey = BuildConfig.ANCHORKIT_API_KEY)

// 2. Capture — hardware-attested photo; blocks until submission receipt arrives
val result: CaptureResult = anchorKit.captureAndSubmit()
// result.receipt.attestation_verified == true  →  TEE/StrongBox confirmed server-side
// result.receipt.hash                          →  SHA-256 of the raw JPEG bytes

// 3. Verify — after nightly batch anchors to Solana (next midnight UTC)
val verification: VerificationResult = anchorKit.verify(result.receipt.hash)
// verification.verified == true               →  hash is on-chain; merkle_proof included
```

To go fully trustless after anchoring, fetch the `PortableProof` bundle once and store it alongside the photo:

```kotlin
// Save this JSON sidecar with your photo — it verifies against Solana forever,
// with no AnchorKit servers involved.
val proof: PortableProof = anchorKit.getProof(result.receipt.hash)

// Later, on any device, using any public Solana RPC:
val localResult: LocalVerificationResult = anchorKit.verifyLocally(proof)
// localResult.valid == true  →  verified against Solana directly; AnchorKit never contacted
```


## Architecture

### Photo Submission Pipeline

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

### Online Verification

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
  │  • merkle_proof is included so the caller CAN re-verify math locally │
  │  • Fastest method; no local crypto work                               │
  └──────────────────────────────────────────────────────────────────────┘
```

---

### Offline Proof Bundle Verification

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

---

## Installation
