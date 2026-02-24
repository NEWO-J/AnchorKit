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

**AnchorKit** is a hardware-attested photo provenance SDK for Android (*With iOS to come*).
It lets you prove, cryptographically, that a real photo was taken by a specific physical device, at a specific time.


## ⚓ How It Works
- All media is hardware attested using **secure hardware enclave** (TEE).
- Each night, submissions are aggregated daily into a Merkle tree, and the root hash is anchored to the Solana blockchain.
- Proof bundles are **fully** self-contained. Media remains independently verifiable without relying on AnchorKit, AWS, or any third party.
- Verification requires only the proof bundle and a single Solana RPC call.
- Drop-in integration - integrates within your app's existing camera pipeline with minimal code changes.

## 🏴‍☠️ Capturing Truth at The Source

## Anchor Demo 


## Installation
