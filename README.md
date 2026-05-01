<p align="center">
  <img src="assets/anchorkit_logo.png" alt="AnchorKit" width="600"/>
</p>

<p align="center">
  <a href="https://central.sonatype.com/artifact/net.anchorkit/anchorkit-sdk"><img alt="Maven Central" src="https://img.shields.io/maven-central/v/net.anchorkit/anchorkit-sdk?label=Maven%20Central"/></a>
  <img alt="API Level" src="https://img.shields.io/badge/API-24%2B-brightgreen"/>
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-2.1.0-purple?logo=kotlin"/>
  <img alt="License" src="https://img.shields.io/badge/license-MIT-green"/>
  <img alt="Platform" src="https://img.shields.io/badge/platform-Android-3DDC84?logo=android"/>
</p>

**AnchorKit** is a photo provenance SDK for Android (*with iOS support coming soon*). It enables applications to distinguish real camera captures from AI-generated or manipulated media using hardware-backed cryptographic proofs.
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
<img src="assets/dogverify.png" alt="Verification" width="600"/>

- AnchorKit integrates with your existing CameraX pipeline via a single API call — no camera rewrites required.
- All media captured is hardware attested using **secure hardware enclave** (TEE), then sent to the backend.
- Each night, submissions are aggregated into a daily Merkle tree, and the root hash is anchored to the Solana blockchain.
- Proof bundles are **fully** self-contained. Media remains independently verifiable without relying on AnchorKit, AWS, or any other third party.
- Verification requires only the proof bundle and a single Solana RPC call.
> [!NOTE]
> None of your photos or videos are stored within, or sent to AnchorKit, only 32 byte hash representations.

## How It Compares
| Property | C2PA | Truepic Lens | IPTC Metadata | Numbers Protocol | AnchorKit |
| --- | --- | --- | --- | --- | --- |
| **Signing environment** | Any software or HSM with C2PA credential | TEE (Qualcomm) | None | Any software | TEE or StrongBox on capture device |
| **Hardware key generation enforced** | No | Yes | No | No | Yes — origin = GENERATED enforced |
| **Verified boot enforced** | No | Not independently verifiable | No | No | Yes — verifiedBootState = Verified enforced |
| **Post-capture signing possible** | Yes | No | Yes | Yes | No — nonce expires in 5 min |
| **Attestation CA** | C2PA-accredited CA (operator-controlled) | Truepic CA | None | None | Google attestation root CA |
| **Blockchain anchoring** | No | No | No | Yes (Ethereum-compatible) | Yes (Solana mainnet) |
| **Offline verification** | No — requires centralised service | No — requires Truepic service | No | Partial | Yes — SHA-256 + Solana RPC only |
| **Strippable by image processing** | Yes — manifest embedded in file | Yes — C2PA manifest | Yes | No — hash-based | No — hash-based |
| **Retroactive forgery after key compromise** | Yes — can backdate manifests | Yes | N/A | Yes | No — past blockchain records are immutable |

## Anchor Demo
- Insert GIF here - 
> [!NOTE]
> The demo app is rate-limited, for full usage of AnchorKit, you can register for a free API key and integrate it into your application.


## ProofBundles - Zero Trust Verification
<img src="assets/proofbundle.png" alt="AnchorKit" width="600"/>

> [!CAUTION]
> AnchorKit makes no verification that the **subject matter** of a photo is real,
> anyone can simply take a photo of another photo, and this is technically valid.
> Videos are much more resillient to this attack, this is where AnchorKit excels.
> In this situation, supplmentary tools can be used to analyze the images for parallax, moire pattern, etc.

## How It Works
<img src="assets/howitworks1.png" alt="How It Works" width="800"/>
<img src="assets/howitworks2.png" alt="How It Works" width="800"/>


> [!IMPORTANT]
> Its important that AnchorKit is **not** treated as an arbiter of truth.
> It does make it exceedingly difficult for AI images to pose as legitimate media,
> but its only supplmentary information, everything you see should be taken with a healthy amount of skepticism.

## Installation

AnchorKit is published to Maven Central. Add the dependency to your app-level `build.gradle`:

```groovy
dependencies {
    implementation 'net.anchorkit:anchorkit-sdk:1.0.2'
}
```

Or with Kotlin DSL (`build.gradle.kts`):

```kotlin
dependencies {
    implementation("net.anchorkit:anchorkit-sdk:1.0.2")
}
```

No extra repository block is needed — `mavenCentral()` is already included in the default Android project template.

> [!TIP]
> Check [Maven Central](https://central.sonatype.com/artifact/net.anchorkit/anchorkit-sdk) for the latest version.

### Permissions

Add to your `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.INTERNET" />
```

## Quick Start

Create an `AnchorKit` instance once — typically in your `Application` class or injected via DI:

```kotlin
val anchorKit = AnchorKit(
    context = applicationContext,
    apiKey = "YOUR_API_KEY"
)
```

### Photo capture

Replace your existing shutter handler with a single `captureAndSubmit` call. AnchorKit runs the full pipeline — device integrity check, photo capture, hardware attestation, and API submission — in one atomic step:

```kotlin
binding.btnShutter.setOnClickListener {
    lifecycleScope.launch {
        try {
            val result = anchorKit.captureAndSubmit(lifecycleOwner = this@CameraActivity)

            val hash = result.photo.hash       // SHA-256 of the captured image
            val receipt = result.receipt       // server confirmation
            showSuccess(hash)
        } catch (e: AnchorKitError.DeviceIntegrityError) {
            showError("Device integrity check failed")
        } catch (e: AnchorKitError.AttestationError) {
            showError("Hardware attestation failed")
        } catch (e: AnchorKitError.NetworkError) {
            showError("Network error: ${e.message}")
        } catch (e: AnchorKitError.ApiError) {
            showError("API error ${e.statusCode}")
        }
    }
}
```

### Video capture

Call `startVideoRecording` when the user taps record, then `stopVideoAndSubmit` when they tap stop. The SDK owns the recording session and submits atomically on stop — you never hold raw video data between capture and submission:

```kotlin
var recordingSession: VideoRecordingSession? = null

// User taps record
binding.btnRecord.setOnClickListener {
    lifecycleScope.launch {
        try {
            recordingSession = anchorKit.startVideoRecording(
                lifecycleOwner = this@CameraActivity,
                previewSurfaceProvider = binding.previewView.surfaceProvider
            )
            binding.btnRecord.isEnabled = false
            binding.btnStop.isEnabled = true
        } catch (e: AnchorKitError.DeviceIntegrityError) {
            showError("Device integrity check failed")
        }
    }
}

// User taps stop
binding.btnStop.setOnClickListener {
    lifecycleScope.launch {
        try {
            val result = anchorKit.stopVideoAndSubmit(recordingSession!!)

            val hash = result.video.hash       // SHA-256 of the video file
            val receipt = result.receipt       // server confirmation
            showSuccess(hash)
        } catch (e: AnchorKitError.AttestationError) {
            showError("Hardware attestation failed")
        } catch (e: AnchorKitError.NetworkError) {
            showError("Network error: ${e.message}")
        } catch (e: AnchorKitError.ApiError) {
            showError("API error ${e.statusCode}")
        } finally {
            recordingSession = null
            binding.btnRecord.isEnabled = true
            binding.btnStop.isEnabled = false
        }
    }
}
```

### Verify a hash

```kotlin
val result = anchorKit.verify(hash)
if (result.verified) {
    println("Anchored on Solana: ${result.solana_tx}")
} else {
    println(result.message)
}
```

For the full API surface including offline proof verification and notification subscriptions, see the [SDK Reference](https://anchorkit.net/docs/sdk-reference).
