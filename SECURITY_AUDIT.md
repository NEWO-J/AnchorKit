# AnchorKit Security Audit Report
**Scope:** NEWO-J/AnchorKit · NEWO-J/AnchorKit_API · NEWO-J/anchorkit.net
**Date:** 2026-03-09
**Objective:** Identify how an attacker can upload an AI-generated or falsified image through the pipeline and have it accepted as hardware-attested authentic media.

---

## Executive Summary

AnchorKit's security model depends on an unbroken chain of custody from camera hardware → hash → hardware-signed attestation → server verification → blockchain anchor. The system enforces strong individual controls (Android Keystore TEE/StrongBox, root CA pinning, nonce consumption, verified boot enforcement), but contains a **fundamental architectural break** in that chain: the SDK exposes a `submitPhoto()` method that accepts an **arbitrary, externally-supplied hash** and signs it with the hardware key. An attacker with any legitimate, unrooted Android device can submit the SHA-256 of any image — including AI-generated content — and receive a fully-valid hardware-attested proof bundle anchored on Solana.

---

## FINDING 1 — CRITICAL: `submitPhoto()` Accepts Arbitrary Hashes — Core Chain-of-Custody Break

**Repository:** AnchorKit (Android SDK)
**File:** `anchorkit-android-sdk/src/main/java/io/anchorkit/sdk/AnchorKit.kt:208-228`

### The Vulnerable API

```kotlin
suspend fun submitPhoto(
    hash: String,      // ← CALLER-SUPPLIED. NOT verified to originate from the camera.
    timestamp: Long,
    width: Int,
    height: Int
): VerificationReceipt {
    val challenge = client.fetchChallenge()
    val metadata = mapOf(
        "timestamp" to timestamp.toString(),
        "dimensions" to "${width}x${height}",
        "platform" to "android"
    )
    val attestation = EnclaveAttestation.sign(hash, challenge.nonce, metadata, context)
    return client.submitHash(
        hash = hash,
        nonce = challenge.nonce,
        enclaveSignature = attestation.enclaveSignature,
        deviceAttestation = attestation.deviceAttestation,
        metadata = metadata
    )
}
```

### Why This Breaks the Security Model

The Android Keystore attestation proves exactly one thing: **a hardware-backed signing key on a verified, unmodified Android device signed the payload `hash:nonce:metadataHash`**. It does **not** and **cannot** prove that `hash` was derived from bytes produced by the device's camera.

The server-side `enforce_keystore_attestation()` correctly validates:
- `attestationSecurityLevel` ∈ {TrustedEnvironment, StrongBox}
- `keyOrigin == GENERATED`
- `verifiedBootState == Verified`

All of these checks pass even when the `hash` parameter is `SHA-256("ai_generated_deepfake.jpg")`.

### Complete Attack Walkthrough

**Requirements:** One unrooted production Android device (any model) + a valid AnchorKit API key.

**Step 1 — Obtain an API key (legitimate signup):**
```
POST /api/auth/signup  {"email": "attacker@example.com", "password": "..."}
→ verify email → receive API key ak_...
```

**Step 2 — Compute the hash of any target image:**
```python
import hashlib
with open("ai_generated_face.jpg", "rb") as f:
    fake_hash = hashlib.sha256(f.read()).hexdigest()
# fake_hash = "e3b0c44298fc1c149afb..."
```

**Step 3 — Call `submitPhoto()` from a legitimate app on a legitimate device:**
```kotlin
// On any production (non-rooted) Android device:
val anchorKit = AnchorKit(context, apiKey = "ak_...")
val receipt = anchorKit.submitPhoto(
    hash = "e3b0c44298fc1c149afb...",  // hash of AI-generated image
    timestamp = System.currentTimeMillis(),
    width = 1920,
    height = 1080
)
// receipt.attestation_verified == true
```

**Step 4 — SDK execution (internals):**
- `EnclaveAttestation.sign("e3b0c44298...", nonce, metadata, context)` is called
- The hardware key (TEE/StrongBox) signs the payload
- The full Google-rooted certificate chain is assembled and sent

**Step 5 — Server-side verification in `hash_submission.py`:**
```python
# verify_chain() → passes (real Google root CA chain)
# enforce_keystore_attestation() → passes (real hardware key, verified boot)
# ECDSA signature check → passes (real hardware signed hash:nonce:metadataHash)
# _store_leaf(attestation_verified=True, ...)
```

**Step 6 — Result:**
The AI-generated image's hash is stored with `attestation_verified=True`, entered into the daily Merkle tree, and anchored to the Solana blockchain. The public verification endpoint returns:
```json
{
  "hash": "e3b0c44298fc1c...",
  "verified": true,
  "attestation_verified": true,
  "cert_fingerprint": "<real Google CA chain fingerprint>",
  "merkle_proof": [...],
  "solana_tx": "..."
}
```

The AI-generated image now has an undeniable "hardware-verified authentic capture" proof bundle.

### Why the Existing Controls Do Not Stop This

| Control | Status | Reason it Doesn't Help |
|---|---|---|
| Android Keystore TEE/StrongBox | ✅ Enforced | Proves the key is in hardware — not that the hash came from camera |
| `verifiedBootState == Verified` | ✅ Enforced | Proves OS is unmodified — not that `submitPhoto()` wasn't called directly |
| `keyOrigin == GENERATED` | ✅ Enforced | Proves key was generated in hardware — orthogonal to hash source |
| Root CA pinning (Google certs) | ✅ Enforced | Proves the attestation certificate is genuine — same issue |
| Nonce/replay protection | ✅ Enforced | Prevents reuse — doesn't prevent fresh forged submissions |
| TLS certificate pinning | ✅ Enforced | Prevents MITM — attacker is the legitimate client |
| Client-side `DeviceIntegrity` checks | ✅ Present | Not called by `submitPhoto()` at all (only called by `captureAndSubmit()` and `capturePhoto()`) |
| Rate limiting (60/hr) | ✅ Present | 60 forged images per hour per IP is sufficient for most attack scenarios |

---

## FINDING 2 — HIGH: Demo App Split Capture/Submit Flow Creates a TOCTOU Window and Hash Injection Point

**Repository:** AnchorKit (demo app)
**File:** `demo-app/src/main/java/io/anchorkit/demo/CameraActivity.kt:290-335`

### The Flaw

In the demo app's photo capture flow, the camera captures an image and computes the hash in `CameraActivity`, but then passes the hash as an Android Intent extra back to `MainActivity` for deferred submission:

```kotlin
// CameraActivity.kt:290-323
override fun onCaptureSuccess(image: ImageProxy) {
    val buffer = image.planes[0].buffer
    val photoData = ByteArray(buffer.remaining())
    buffer.get(photoData)
    val hash = sha256Hex(photoData)           // hash computed here
    val timestamp = System.currentTimeMillis()

    lifecycleScope.launch {
        val saved = withContext(Dispatchers.IO) { savePhotoToGallery(photoData, timestamp) }
        // ...
        val intent = Intent().apply {
            putExtra(EXTRA_HASH, hash)         // hash travels via IPC
            putExtra(EXTRA_SUBMISSION_PENDING, true)
        }
        setResult(Activity.RESULT_OK, intent)  // sent to MainActivity
        finish()
    }
}
```

`MainActivity` then calls `anchorkit.submitPhoto(hash, ...)` with this externally-received hash.

### Problems

1. **The hash travels through Android's IPC layer.** On a rooted device, Intent extras can be intercepted and modified by other apps with root access or by Xposed/LSPosed hooks. The nonce is fetched **after** this handoff, so the signed payload is over the potentially-modified hash.

2. **The split reinforces the `submitPhoto()` attack surface.** The app uses `submitPhoto()` (which accepts any hash) rather than the safer `captureAndSubmit()` flow. This means even a user of the demo app can replace the hash before it is submitted.

3. **The gallery save happens before submission.** `savePhotoToGallery()` saves image bytes to the device gallery with `IS_PENDING=1` before submission. The image on disk could be swapped before submission is attempted (though the hash was already computed, so a hash mismatch would be produced — this is a less critical sub-issue).

---

## FINDING 3 — HIGH: `captureAndSubmit()` Is Safe But Optional — SDK Design Allows Bypassing the Secure Path

**Repository:** AnchorKit (Android SDK)
**File:** `anchorkit-android-sdk/src/main/java/io/anchorkit/sdk/AnchorKit.kt:39-83`

`captureAndSubmit()` correctly enforces a closed loop:
```
DeviceIntegrity.check() → capturePhoto() → fetchChallenge() → sign() → submitHash()
```
The hash is derived from camera bytes and never leaves the SDK's control before signing. However, the SDK also publishes `capturePhoto()` (returns hash + bytes) and `submitPhoto()` (accepts any hash) as separate public methods.

The Kotlin Docstring for `captureAndSubmit()` says:
> *"This is the **only** public method that produces an attested photo submission. Accepting an externally-supplied file or pre-computed hash is intentionally not supported."*

But this is a documentation claim, not an enforcement mechanism. The `submitPhoto()` method is in the same public class and accepts any hash. The API key gating (server-side) does not distinguish between hashes produced by `captureAndSubmit()` vs `submitPhoto()`. This creates a **documentation vs implementation security mismatch**.

---

## FINDING 4 — HIGH: `EnclaveAttestation.sign()` Is Called with a User-Controlled `hash` Parameter

**Repository:** AnchorKit (Android SDK)
**File:** `anchorkit-android-sdk/src/main/java/io/anchorkit/sdk/EnclaveAttestation.kt:88`

```kotlin
fun sign(hash: String, nonce: String, metadata: Map<String, String>, context: Context): AttestationResult {
    val metadataHash = hashMetadata(metadata)
    val data = "$hash:$nonce:$metadataHash".toByteArray(Charsets.UTF_8)
    // ... signs `data` with the hardware key
}
```

`EnclaveAttestation.sign()` is an `object` member (effectively a static/singleton method) with no validation that `hash` is a SHA-256 of any specific type of byte source. Any code with access to the `io.anchorkit.sdk` package can call this directly with an arbitrary hash string. There is no enforcement (or even checking) that `hash` was produced by `PhotoCapture` or `VideoRecorder`.

---

## FINDING 5 — MEDIUM: `ATTESTATION_STRICT_MODE=false` Eliminates All Hardware Verification

**Repository:** AnchorKit_API
**Files:** `src/attestation/chain_verifier.py:59`, `src/attestation/keystore_attestation.py:290`

```python
_STRICT_MODE = os.environ.get("ATTESTATION_STRICT_MODE", "true").strip().lower() != "false"
```

When `ATTESTATION_STRICT_MODE=false`:
- Root CA pinning is **completely skipped** — any self-signed certificate chain is accepted
- The Android Keystore extension is **not required** — its absence is silently accepted
- A self-signed cert chain with a crafted `KeyDescription` extension (or no extension at all) can submit any hash

**Risk:** If this variable is set in a production environment (accidentally through a copied `.env.example`, CI/CD misconfiguration, or infrastructure-as-code error), the entire hardware attestation mechanism is bypassed silently. There is no monitoring or alerting configured in the codebase when strict mode is disabled.

**Exploitation in `ATTESTATION_STRICT_MODE=false` mode:**
An attacker can submit any hash with a self-generated certificate chain (no Android device required):
```python
import requests, base64, json
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives import hashes
from cryptography import x509
# ... generate self-signed cert chain, sign hash, submit
```

---

## FINDING 6 — MEDIUM: Attestation Challenge Endpoint Requires No Authentication — Enables Pre-Harvesting

**Repository:** AnchorKit_API
**File:** `src/api/routes/challenge.py`

```python
@router.get("/api/attestation-challenge", response_model=ChallengeResponse)
async def get_attestation_challenge(request: Request):
    client_ip = request.client.host if request.client else "unknown"
    check_rate_limit("challenge", client_ip, _CHALLENGE_RATE_MAX, _CHALLENGE_RATE_WINDOW)
    result = issue_nonce()
    return ChallengeResponse(nonce=result["nonce"], expires_at=result["expires_at"])
```

The challenge endpoint has no authentication. Any unauthenticated client can:
1. Obtain up to 30 nonces per 10 minutes per IP
2. Use rotating IPs (VPN, proxy, IPv6 rotation) to obtain nonces in bulk
3. Nonces have a 5-minute TTL — with IP rotation an attacker can pre-harvest and cache a large pool

While nonces are single-use and the rate limit prevents large-scale abuse from a single IP, combining this with Finding 1 means the attacker has all the ingredients with minimal friction.

---

## FINDING 7 — MEDIUM: Rate Limiting is Fail-Open on DynamoDB Failure

**Repository:** AnchorKit_API
**File:** `src/storage/rate_limiter.py:114-119`

```python
except Exception as exc:
    # Fail-open: don't block all traffic on a DynamoDB outage.
    logger.error(
        "Rate-limit check failed for %s:%s — failing open: %s", endpoint, ip, exc
    )
    return  # ← All rate limiting bypassed
```

During any DynamoDB degradation or outage, all rate limits across all endpoints (challenge, submit-hash, signup, login, forgot-password) are completely bypassed. A DDoS against DynamoDB or a misconfigured VPC security group causing DynamoDB unreachability would silently open the submission endpoint to unlimited traffic.

**Exploitation:** An attacker who can cause a DynamoDB outage (or who times their attack during an infrastructure event) can submit unlimited hashes in a short window and saturate the Merkle tree with forged entries.

---

## FINDING 8 — MEDIUM: Metadata Values Are Caller-Controlled with No Semantic Validation

**Repository:** AnchorKit_API
**File:** `src/api/routes/hash_submission.py:80-90`

The metadata map (timestamp, dimensions, platform) is caller-supplied and the signature binds these values to the hash — preventing in-transit modification. However, the server never validates:

- **`timestamp`** — can be any value. An attacker can claim the image was captured years ago or in the future.
- **`dimensions`** — can be any `WxH` string. An attacker can claim the image is 12MP when it's actually a thumbnail.
- **`platform`** — can be any string (e.g., `"ios"` even though iOS isn't supported, or `"android"` from a non-Android client).

The verified proof bundle (returned by `/api/proof/{hash}`) includes this metadata, creating misleading provenance claims even if the hash itself is legitimate.

---

## FINDING 9 — LOW: Attestation Key Age Limit Is Time-Based, Not Submission-Based

**Repository:** AnchorKit (Android SDK)
**File:** `anchorkit-android-sdk/src/main/java/io/anchorkit/sdk/EnclaveAttestation.kt:52`

```kotlin
private const val KEY_MAX_AGE_MS: Long = 30L * 24 * 60 * 60 * 1000  // 30 days
```

A key is generated with the device's `verifiedBootState` captured at generation time. If a device was clean when the key was generated and the bootloader is subsequently unlocked/rooted, the existing key continues to carry `verifiedBootState = Verified` for up to 30 more days. The 30-day window is a trade-off the codebase acknowledges, but no server-side mechanism enforces a shorter window or allows revoking specific device certificates.

---

## FINDING 10 — LOW: IP-Based Rate Limiting Does Not Account for Reverse Proxy Headers

**Repository:** AnchorKit_API
**File:** `src/api/routes/hash_submission.py:67`, `src/api/routes/challenge.py:36`

```python
client_ip = http_request.client.host if http_request.client else "unknown"
```

FastAPI's `request.client.host` returns the IP of the immediate TCP connection. Behind a load balancer or reverse proxy (the typical deployment via the Docker Compose setup), this will be the proxy's internal IP, not the actual client IP. This means:

1. **Rate limits are shared** across all real clients behind the proxy — a single legitimate user's requests could exhaust the limit for everyone.
2. **Rate limits can be trivially bypassed** by connecting directly to the API if the proxy isn't the only ingress point.

The `X-Forwarded-For` or `X-Real-IP` headers (set by nginx/Caddy/etc.) are not trusted or read.

---

## Attack Scenarios Summary

### Scenario A — AI-Generated Image Certified as Authentic (Primary Attack)

**Difficulty:** Low (requires any unrooted Android device + AnchorKit account)

1. Compute `SHA-256("deepfake.jpg")` = `e3b0c4...`
2. Call `anchorKit.submitPhoto("e3b0c4...", timestamp, 4032, 3024)` on a legitimate device
3. Server returns `{ attestation_verified: true }` and anchors the hash on Solana
4. Present the proof bundle as evidence of authentic capture

**No device modification required. No exploit of any cryptographic primitive. Pure SDK-level abuse.**

### Scenario B — Bypassing All Attestation (ATTESTATION_STRICT_MODE=false)

**Difficulty:** Low IF `ATTESTATION_STRICT_MODE=false` is set in production

1. Generate any RSA/EC keypair and a self-signed certificate with a crafted Android Keystore extension (`attestationSecurityLevel=1`, `origin=0`, `verifiedBootState=0`)
2. Sign any hash with the private key
3. Submit to `/api/submit-hash` — all verification passes

**Requires no Android device at all.**

### Scenario C — Photo-to-Intent Hash Substitution on Rooted Device (Demo App)

**Difficulty:** Medium (requires rooted device OR Xposed framework)

1. Root the test device (this passes client-side checks but would fail server-side `verifiedBootState` check)

**Note:** This scenario is blocked by the `verifiedBootState=Verified` enforcement — a rooted device will fail attestation. However, an attacker can use Scenario A instead, which requires no rooting.

---

## Recommended Fixes

### Fix 1 (Critical) — Remove `submitPhoto()` or Bind It to In-SDK Capture

**Option A (Preferred):** Remove `submitPhoto()` entirely. All submissions must go through `captureAndSubmit()`, which maintains the closed loop.

**Option B:** If the split flow is architecturally necessary (e.g., to avoid holding a camera lifecycle across Activity transitions), use an in-memory `PhotoResult` opaque token that cannot be constructed outside the SDK. The hash must never be exposed as a plain `String` that can be passed back in. Use a sealed class or sealed interface wrapping the hash so it can only originate from `capturePhoto()`:

```kotlin
// Opaque token — cannot be constructed outside the SDK package
class CapturedPhotoToken internal constructor(internal val hash: String, ...)

// submitPhoto now only accepts a token, not a raw String
suspend fun submitPhoto(token: CapturedPhotoToken): VerificationReceipt { ... }
```

### Fix 2 (High) — Demo App Should Use `captureAndSubmit()` or CapturedPhotoToken

Replace the CameraActivity split flow with `captureAndSubmit()`, or if the split is needed for UX reasons, use the opaque token approach from Fix 1.

### Fix 3 (Medium) — Alert and Refuse to Start If `ATTESTATION_STRICT_MODE=false` in Production

Add a startup check that refuses to start the API if `ATTESTATION_STRICT_MODE=false` when detected as a production environment (e.g., based on `ENVIRONMENT=production` env var). Log an `ERROR`-level alert and emit a metric when strict mode is disabled.

### Fix 4 (Medium) — Validate Metadata Semantics Server-Side

- `timestamp`: Reject submissions where the timestamp is more than 5 minutes in the past or any time in the future.
- `dimensions`: Validate format (`\d+x\d+`) and reject unrealistic values (e.g., 0x0, or values inconsistent with known camera hardware).
- `platform`: Enforce an allowlist (`android`).

### Fix 5 (Medium) — Require API Key Authentication for Challenge Endpoint

Require a valid API key to obtain a nonce. This couples challenge issuance to a registered user and makes the attacker's requests attributable.

### Fix 6 (Medium) — Rate Limiter Should Fail Closed (or Use In-Memory Fallback)

Replace the fail-open behavior with a fail-closed policy (return `HTTP 503`) or maintain an in-process counter as a fallback when DynamoDB is unavailable.

---

## Files Implicated

| File | Finding |
|---|---|
| `AnchorKit/anchorkit-android-sdk/.../AnchorKit.kt:208-228` | F1, F3 |
| `AnchorKit/anchorkit-android-sdk/.../EnclaveAttestation.kt:88` | F4 |
| `AnchorKit/demo-app/.../CameraActivity.kt:290-335` | F2 |
| `AnchorKit_API/src/attestation/chain_verifier.py:57-59,256-266` | F5 |
| `AnchorKit_API/src/attestation/keystore_attestation.py:290` | F5 |
| `AnchorKit_API/src/api/routes/challenge.py:28-40` | F6 |
| `AnchorKit_API/src/storage/rate_limiter.py:114-119` | F7 |
| `AnchorKit_API/src/api/routes/hash_submission.py:67,80-90` | F8, F10 |
| `AnchorKit_API/src/api/routes/challenge.py:36` | F10 |
