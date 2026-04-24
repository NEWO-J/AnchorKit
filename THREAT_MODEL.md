# AnchorKit Threat Model

**Scope:** Android SDK · Backend API · Frontend  
**Last updated:** 2026-04-24

---

## What AnchorKit Protects

AnchorKit provides hardware-rooted photo provenance. The trust chain is:

```
Android TEE/StrongBox  →  ECDSA attestation cert  →  API verification  →  Merkle root on Solana
```

A valid AnchorKit proof asserts:

1. **Device authenticity** — the photo was captured on a real, unmodified Android device with a verified boot state (`verifiedBootState=Verified`).
2. **Hardware-generated key** — the signing key was generated inside a hardware security module (`origin=GENERATED`, `attestationSecurityLevel` ∈ {TrustedEnvironment, StrongBox}); it was never exportable.
3. **Single-use binding** — the hash was bound to a single-use server nonce (5-minute TTL), preventing replay of prior attestations.
4. **Metadata integrity** — the ECDSA signature covers `hash:nonce:metadataHash`, so dimensions, timestamp, and platform cannot be swapped after signing.
5. **Closed capture loop** — the SDK is the origin of image bytes; no externally-supplied hash can enter the signing path.
6. **Blockchain immutability** — the daily Merkle root is posted to Solana mainnet; on-chain state is append-only and verifiable by anyone with a public RPC endpoint.

---

## Attacker Capabilities Considered

| Capability | In scope |
|------------|----------|
| Valid API key, unrooted Android device | Yes |
| Valid API key, rooted Android device | Yes — hardware attestation is refused |
| Network attacker (MITM) | Yes — TLS + cert pinning |
| Compromised AnchorKit server | Partial — Solana anchors survive; live proofs require server |
| Compromised Merkle-posting keypair | Yes — arbitrary roots could be posted |
| Solana chain fork or reorg | Finality handled by `confirmed` commitment level |
| Photo editing before capture | Out of scope — camera hardware bound, not photo content |

---

## Security Properties Enforced

### Android SDK

- **StrongBox-first key generation** — falls back to TEE only when StrongBox is unavailable; Software attestation is always rejected server-side.
- **30-day key rotation** — limits the window during which a stale boot-state certificate can be used.
- **Challenge freshness** — a new nonce is fetched per attestation; the nonce expires in 5 minutes.
- **Chunk index bounds checking** — Solana chunk index validated to `[0, 65535]` before PDA derivation.
- **Merkle proof depth cap** — maximum 64 steps to prevent unbounded computation on crafted proofs.
- **Ed25519 curve validation via BouncyCastle** — PDA derivation uses `Ed25519.validatePublicKeyFull` from `bcprov-jdk18on`; no custom curve arithmetic.

### Backend API

- **Attestation strict mode** — `ATTESTATION_STRICT_MODE=false` is rejected at startup unless an explicit override env var is set; not reachable in production.
- **Nonce store** — single-use DynamoDB entries with 5-minute TTL; atomic `ConditionalExpression` delete prevents double-use.
- **Full certificate chain validation** — validates cryptographic chain from leaf to root; pins against Google attestation root CA fingerprints.
- **Android Keystore extension enforcement** — parses OID `1.3.6.1.4.1.11129.2.1.17`; rejects Software-level attestation, imported keys, and non-Verified boot states.
- **Rate limiting** — DynamoDB-backed fixed-window rate limiter; fails closed (HTTP 429) when DynamoDB is unavailable.
- **API key protection** — per-user keys only; keys transmitted in headers, not request bodies.
- **Keypair file permission check** — refuses to load the Solana keypair if the file is group- or world-readable; auto-corrects if possible.
- **Email-change atomicity** — DynamoDB conditional write prevents TOCTOU race on email uniqueness.
- **Auth session security** — JWT in HttpOnly, Secure, SameSite=Strict cookie; 24-hour lifetime; CSRF token required.
- **Startup safety gates** — refuses to start with a short secret key or misconfigured attestation mode.

### Frontend

- **Content Security Policy** — `default-src 'self'`; `connect-src` limited to `api.anchorkit.net`; `script-src 'self' 'wasm-unsafe-eval'`; `object-src 'none'`.
- **SPA path restoration** — decoded URL path validated against an allowlist regex (`^\/[A-Za-z0-9\-._~!$&'()*+,;=:@/%?]*$`) before `history.replaceState`; blocks `javascript:` and `//`-prefixed redirect payloads.
- **Session storage** — only the user's email (not the JWT) is stored in `sessionStorage` as a client-side nav hint; the actual session is in the HttpOnly cookie.

---

## Known Limitations

| Limitation | Notes |
|------------|-------|
| Android-only | iOS support is roadmapped; no hardware-equivalent on iOS currently. |
| Solana RPC availability | Local verification requires a public Solana RPC; offline verification is not possible. |
| No 2FA on account operations | API key regeneration and email change require only the session JWT. |
| JWT has no revocation | 24-hour JWTs cannot be invalidated before expiry (logout clears cookie only). |
| Rate limiting per process on DynamoDB failure | Fallback in-process limiter is per-process; N workers = N × limit during outage. |

---

## Vulnerability Disclosure

Report security issues to **security@anchorkit.net** with the subject line `[Security]`. See `SECURITY.md` for the full responsible disclosure policy.
