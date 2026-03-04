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

**AnchorKit** is a photo provenance SDK for Android (*With iOS to come*). It enables applications to distinguish real camera captures from AI-generated or manipulated media using hardware-backed cryptographic proofs.
<p align="center">
  <a href="#how-it-works">How It Works</a> •
  <a href="#anchor-demo">Demo</a> •
  <a href="#quick-start">Quick Start</a> •
  <a href="#installation">Installation</a> •
  <a href="#credits">Credits</a> •
  <a href="#license">License</a> •
  <a href="https://anchorkit.net">AnchorKit.net</a>
</p>

## What It Does
- AnchorKit integrates with your existing CameraX pipeline via a single API call — no camera rewrites required.
- All media captured is hardware attested using **secure hardware enclave** (TEE), then sent to the backend.
- Each night, submissions are aggregated into a daily Merkle tree, and the root hash is anchored to the Solana blockchain.
- Proof bundles are **fully** self-contained. Media remains independently verifiable without relying on AnchorKit, AWS, or any other third party.
- Verification requires only the proof bundle and a single Solana RPC call.
> [!NOTE]
> None of your photos or videos are stored within, or sent to AnchorKit, only 32 byte hash representations.

=======
## How It Compares
| | AnchorKit | C2PA Standard | AI Detection |
| --- | --- | --- | --- |
| **What it proves** | "This  is a real photograph taken on real hardware at this time" | "This content was created or endorsed by the holder of this certificate at this time" | "This image may or may not have been AI-generated" |
| **Verification** | Stored on public solana node | Certificate in metadata | Post-hoc property analysis |
| **Hardware Attestation** | StrongBox / TEE | Optional, not required | None |
| **Trust Model** | Trustless once anchored | Relies on certificate authority | Trust the estimation of the model provider —  not definitive |
| **Proof is self-contained** | Yes — proof bundle + any public Solana RPC node | No — depends on CA infrastructure and lookup services remaining operational | N/A |
| **Historical record is immutable** | Yes — blockchain entries cannot be altered retroactively | No — certificates expire and CAs can be revoked | N/A |
| **Decentralized** | Yes — anchored on public blockchain | No — centralized certificate infrastructure | No — model vendor dependency |
| **If Compromised** | Only future pictures can be faked | All pictures can be faked | Detection degrades as generation models improve (arms race) |

## Anchor Demo
- Insert GIF here - 
> [!NOTE]
> The demo app is rate-limited, for full usage of AnchorKit, you can register for a free API key and integrate it into your application.



## AnchorBadges
Optional scannable badge add-on to that links directly to your photo or video's verification.
```Kotlin
val badge: Bitmap = AnchorBadge.create(context, photo)
```


## SiteBadges - Build Trust in Your Brand
Display a verifiable badge on your site containing your site's AnchorKit statistics.

> [!CAUTION]
> AnchorKit makes no verification that the **subject matter** of a photo is real,
> anyone can simply take a photo of another photo, and this is technically valid.
> In this situation, supplmentary tools can be used to analyze the images for parallax, moire pattern, etc.

## How It Works

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

> [!IMPORTANT]
> Its important that AnchorKit is **not** treated as an arbiter of truth.
> It does make it exceedingly difficult for AI images to pose as legitimate media,
> but its only supplmentary information, everything you see should be taken with a healthy amount of skepticism.
> 
## Installation
