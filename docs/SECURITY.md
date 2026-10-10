# Security controls and their limits

A project or test suite cannot establish a promise of maximum security. This implementation has layered controls and explicit remaining gaps.

## Implemented controls

- JWT signature, issuer, audience, expiry and subject validation. The `exp` claim is mandatory: a correctly signed token without an expiration is rejected. Default Spring timestamp validation checks expiry and `nbf` with its clock-skew allowance. JwtValidationTest exercises real signatures and missing/expired/future validity claims, issuer, audience and subject boundaries. OIDC mode uses JWKS; HMAC is reserved for the separate local lab scenario.
- Backend ownership checks for accounts, payments and profiles. Hiding UI controls is not authorisation. Staff and operators have separate roles.
- Staff/ops require `amr=mfa`. Keycloak requires password plus OTP; its AMR mapper reports performed methods. A trusted issuer must supply the signed claim; the text `mfa` alone does not establish IdP quality.
- Profiles and proposed corrections use AES-256-GCM with random nonces, integrity checks and subject AAD. Moving ciphertext between customers fails. Missing or weak keys block startup.
- Staff can edit risk tier and suspension only. List contacts are masked. Identity corrections require a request, independent decision and audit; self-review is forbidden.
- Unknown JSON fields are rejected, preventing self-registration from assigning roles, balances or KYC status. DTO validation, versions and parameterised SQL protect input boundaries.
- CSRF protection is enabled. Spring's resource-server defaults exempt requests with an explicit bearer token in the Authorization header; authentication still validates the signature, issuer, audience, expiry and scopes. Cookies, query parameters and form bodies are not accepted as bearer-token sources. Anonymous and cookie-only unsafe requests require a valid CSRF token. The API remains stateless; deployments must not add a proxy that converts browser cookies into bearer headers without its own CSRF protection.
- CSP, nosniff, framing restrictions and no-referrer. The UI creates DOM text with `textContent`. Access tokens live in memory only. One-use PKCE verifier/state values use sessionStorage; tokens are not stored in localStorage.
- Per-subject rate limits, Retry-After and a bounded limit map. This is a process-local limiter; multiple instances need a shared gateway/Redis and pre-auth limits.
- XML parsing prohibits external entities, DTDs and schemas and limits XML size. Official XSDs are local. The nginx template includes a general body limit, but the gateway is not part of Compose.
- The mTLS profile requires a trusted client certificate and still requires JWT. Certificates do not replace role authorisation.
- Payment idempotency, ordered locks and a balanced ledger. State checks prevent duplicate settlement/refund.

## Threat model

| Threat | Control | Verification |
|---|---|---|
| Customer submits another account ID | Ownership check | HTTP/domain integration |
| Mass assignment of permissions/status | Strict DTOs and unknown-field rejection | CustomerSecurityE2ETest |
| Staff obtains operator access | Separate scopes and backend checks | HTTP role tests |
| Forged JWT, wrong issuer/audience, missing MFA | Token validation and ApiGuard | HTTP security tests |
| Modified or reassigned encrypted profile | GCM tag and subject AAD | ProfileCipherTest |
| XSS through names or addresses | textContent and CSP | Browser suite; see current CI evidence |
| XXE/external schema loading | Hardened XML factory | ISO integration tests |
| Retries and concurrent spending | Idempotency and row locks | Concurrent integration/load tests |
| Duplicate or stale Kafka events | Inbox and sequence | Consumer/Streams tests |
| Connection without a client certificate | mTLS truststore | Real HTTPS e2e |

## Required before production

KMS/HSM, key rotation and versioning, a secret manager, TLS on every channel, Kafka ACL/SASL/TLS, a secured database, backup restore drills, shared rate limits, current dependency scans, an independent pentest, SCA with dynamic linking and real VOP/KYC/AML/sanctions sources. Risky actions need maker-checker controls, approved staff access procedures, tamper-resistant audit, anomaly monitoring, retention/GDPR processes, incident response and DORA recovery tests.

Lab audit is an SQL table without cryptographic immutability. Authorised responses and banking documents still contain full customer data. AES keys are environment variables without rotation. Contact changes do not verify the new phone or email. MFA does not prevent abuse by an already compromised customer session. KYC approval is a simulated decision without documentary verification evidence. These limits should be explained during interviews.

## AI and specifications

Architecture and API documents describe testable invariants. Use fictional fixtures with AI; never supply real documents, tokens, secrets, statements or production logs. Changes need review, tests and requirement traceability.

Semgrep and Trivy run in CI; current evidence is in [CI-RESULTS.md](CI-RESULTS.md). Semgrep's six local rules are a focused baseline, not equivalent to CodeQL. CodeQL runs automatically for a public repository; private repositories require enabled Code Security and `CODEQL_ENABLED=true`. Historical skipped or failed CodeQL runs are not successful security evidence.
