# ⚓ AnchorKit - Your camera, under oath.
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

**AnchorKit** is a hardware-attested photo provenance SDK for Android (*With iOS to come*). It enables applications to distinguish real camera captures from AI-generated or manipulated media using hardware-backed cryptographic proofs.


## ⚓ How It Works
- All media is hardware attested using **secure hardware enclave** (TEE).
- Each night, submissions are aggregated daily into a Merkle tree, and the root hash is anchored to the Solana blockchain.
- Proof bundles are **fully** self-contained. Media remains independently verifiable without relying on AnchorKit, AWS, or any third party.
- Verification requires only the proof bundle and a single Solana RPC call.
- Drop-in integration - integrates within your app's existing camera pipeline with minimal code changes.

## 🏴‍☠️ Capturing Truth at The Source

## Anchor Demo


## Architecture

### Photo Submission Pipeline

```
     ANDROID DEVICE                ANCHORKIT API                SOLANA
     ───────────────               ─────────────                ───────

   [ User takes photo ]
              │
              ▼
   ┌──────────────────┐
   │  Device Check    │  (boot state, root, emulator)
   └──────────────────┘
              │
              ▼
   ┌──────────────────┐
   │  Hash Photo      │  SHA-256(photo)
   └──────────────────┘
              │
              ▼
   ┌──────────────────┐
   │  Hardware Sign   │  (StrongBox / TEE)
   └──────────────────┘
              │
              ├─────────────── POST ────────────────►

                              ┌──────────────────┐
                              │ Verify Signature │
                              │ Verify Attest.   │
                              │ Check Nonce      │
                              └──────────────────┘
                                        │
                                        ▼
                              [ Hash stored for day ]
                                        │
                              ───────── Nightly ─────────
                                        │
                                        ▼
                              ┌──────────────────┐
                              │ Build Merkle     │
                              │ Anchor Root      │──────────►  On-Chain Record
                              └──────────────────┘
```

### Online Verification

```

     ANDROID DEVICE                 ANCHORKIT API                 SOLANA
     ───────────────                ─────────────                 ───────

   AnchorKit.verify(hash)
              │
              │  GET /verify/{hash}  (TLS + cert pinned)
              ├──────────────────────────────────────────────►

                              ┌──────────────────┐
                              │ Lookup Hash      │
                              │ Retrieve Proof   │
                              │ Retrieve Root    │
                              │ Retrieve TX      │
                              └──────────────────┘
              ◄──────────────────────────────────────────────┤
              │
              ▼
   ┌──────────────────────────────┐
   │ Verification Result Outcomes:│
   │                              │
   │ • Not Found                  │
   │ • Batched (Not Anchored Yet) │
   │ • Anchored On-Chain ✓        │
   └──────────────────────────────┘
```

### Offline Proof Bundle Verification
```
 STORED PROOF BUNDLE                    SOLANA
 ───────────────────                    ───────

   [ ProofBundle File ]
   • photo hash
   • merkle proof
   • merkle root
   • solana program + PDA
   • solana tx reference
              │
              ▼

   ┌──────────────────────────┐
   │ 1. Verify Merkle Proof   │
   │   (local cryptography)   │
   └──────────────────────────┘
              │
              ▼
   ┌──────────────────────────┐
   │ 2. Re-derive PDA         │
   │   (prevent substitution) │
   └──────────────────────────┘
              │
              │  1 RPC Call
              ├──────────────────────────────►  Fetch On-Chain Root
              │
              ▼
   ┌──────────────────────────┐
   │ 3. Compare Roots         │
   │   proof root == chain?   │
   └──────────────────────────┘
              │
              ▼
        MATCH = VALID
        ELSE = INVALID
```

---

## Installation
