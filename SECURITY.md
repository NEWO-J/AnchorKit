# Security Policy

## Supported versions

The latest release is the only version receiving security fixes.

## Reporting a vulnerability

Please report security vulnerabilities by emailing **security@anchorkit.net**.

Do **not** open a public GitHub issue for security reports — responsible
disclosure gives us time to investigate and patch before any public exposure.

### What to include

- Description of the vulnerability and its potential impact
- Reproduction steps or a minimal proof-of-concept
- Suggested mitigations (optional)

### What to expect

- Acknowledgement within 48 hours
- A status update within 7 days
- Credit in release notes (if desired) once the issue is resolved

We ask that you allow reasonable time for investigation and remediation before
any public disclosure.

## Security model

AnchorKit's full threat model — including trust assumptions, verified
properties, and acknowledged residual risks — is documented in:

- **[ARCHITECTURE.md](ARCHITECTURE.md)** — system design and trust boundaries
- **[SECURITY_REVIEW.md](SECURITY_REVIEW.md)** — independent security review
  covering the complete attack surface, chain-of-custody guarantees, and
  defence-in-depth measures

A security audit has been conducted. Findings were remediated prior to public
release. If you believe you have found a bypass of the documented controls,
please disclose privately via the email above.
