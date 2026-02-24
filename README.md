<p align="center">
  <img src="assets/anchorkit_logo.png" alt="AnchorKit" width="600"/>
</p>

<p align="center">
  <img alt="Version" src="https://img.shields.io/badge/version-1.0.0-blue"/>
  <img alt="API Level" src="https://img.shields.io/badge/API-24%2B-brightgreen"/>
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-2.1.0-purple?logo=kotlin"/>
  <img alt="License" src="https://img.shields.io/badge/license-MIT-green"/>
  <img alt="Platform" src="https://img.shields.io/badge/platform-Android-3DDC84?logo=android"/>
</p>

AnchorKit proves that a photo or video was captured by a specific physical device at a specific moment in time — and has not been altered since.

The cryptographic proof is generated inside the device’s secure hardware enclave before the media ever reaches application code, eliminating the possibility of software-layer tampering.
Submissions are aggregated daily into a Merkle tree, and the root hash is anchored to the Solana blockchain. This creates a permanent, tamper-evident public record.
Proofs are fully self-contained. Media remains independently verifiable without relying on AnchorKit, AWS, or any third party. Verification requires only the proof bundle and a single Solana RPC call.
