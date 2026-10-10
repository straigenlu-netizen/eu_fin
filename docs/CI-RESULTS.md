# Java 21 - CI fixes and mandate lifecycle

Commit: `c35a91e08c9ebb4388937ada1971a5c9bff18761`. [GitHub Actions](https://github.com/straigenlu-netizen/eu_fin/actions/runs/38051899042), 10 October 2026: **SUCCESS**.

| Check | Result |
|---|---|
| Maven/Java 21 | 89 discovered, 88 executed, one conditional ExternalKafkaTest skipped; zero failures/errors |
| PostgreSQL/Kafka | Passed, including duplicate OOFF rejection, revocation after CLEAR, no debit and REJECTED projection through outbox/Kafka |
| Migrations | V3 ledger and V4-to-V5 existing payments; collection dates/types, last activity, no fabricated activity for unused mandates |
| Playwright | 24 checks passed |
| Load/mTLS/security HTTP e2e | Passed; SDD: 120 requests, 8 workers, p95 46 ms/p99 59 ms on H2 |
| Semgrep Community | Six rules and six fixture groups passed; 28 source files, zero findings, SARIF saved |
| Trivy HIGH/CRITICAL | Passed |
| CodeQL | Explicitly skipped in this run because private repository Code Scanning was unavailable; not successful CodeQL evidence |

The previous Actions failure was CodeQL's SARIF upload configuration error in the private repository. Semgrep provides focused blocking source checks, not equivalent CodeQL coverage. Test/scanner errors are not hidden with continue-on-error. The current workflow automatically enables CodeQL for public repositories; private repositories need supported Code Security and `CODEQL_ENABLED=true`.

New tests cover concurrent OOFF instructions, idempotent retry, correction after Reject, reuse prevention after settlement/refund, mandate ownership, revocation before SETTLE, saved FRST/RCUR and XML dates, and 36 calendar months after latest instruction acceptance. Details: [SDD-MANDATES.md](SDD-MANDATES.md).

**Full SEPA/ISO conformance and production readiness remain unconfirmed.** Metrics are short integrity/latency regressions, not bank SLAs or production capacity. [Assessment](SEPA-ISO-CONFORMANCE.md).

## Previous run: SDD refunds, disputes and recovery

Commit: `ddbe24ae0fa2a2b104eca6d77826b6692b683d10`. [Actions 38048465852](https://github.com/straigenlu-netizen/eu_fin/actions/runs/38048465852), 10 October 2026.

| Check | Result |
|---|---|
| Maven/JDK 21/compiler release 21 | 82 discovered, 81 executed, one conditional ExternalKafkaTest skipped; zero failures/errors |
| PostgreSQL/Kafka Testcontainers | Passed: refund, partial recovery, migrations and outbox/consumer in real containers |
| Playwright/HTTP demo smoke | 24 browser checks passed: payer refund, unauthorised SDD, independent investigation and recovery |
| SDD load regression | 120 requests, 8 workers, 12 collections, 10 attempts per payment; p95 40 ms/p99 51 ms on H2 |
| Existing HTTP load/mTLS/security e2e | Passed |
| Trivy HIGH/CRITICAL vuln/secret/misconfig | Passed |
| CodeQL | Configuration failure: Code scanning is not enabled for this repository; not successful evidence |

Checks included empty/reserved creditor funds, eight-week/13-month calendar boundaries, leap-year February, value dates, one credit under concurrent retries, separate partial recovery, self-review/MFA, full rollback on audit failure and existing V3 ledger migration to V4.

[sdd-performance-results.json](sdd-performance-results.json) records financial integrity/latency regression, not production capacity/SLA. CI artifacts contain both load reports, JUnit/JaCoCo and browser screenshots including payment operations.

**Full SEPA conformance and production readiness are not confirmed.** [Refund implementation](SDD-REFUNDS.md); [limitations](SEPA-ISO-CONFORMANCE.md).

## Previous run: SEPA/ISO assessment fixes

Commit: `6f8bc26900769cdedec3f2333a9a4a0d953eabbd`. [Actions 38046761126](https://github.com/straigenlu-netizen/eu_fin/actions/runs/38046761126), 10 October 2026.

| Check | Result |
|---|---|
| Maven/JDK 21/compiler release 21 | 64 discovered, 63 executed, one conditional ExternalKafkaTest skipped; zero failures/errors |
| PostgreSQL/Kafka Testcontainers | Passed in real containers despite the conditional external Kafka skip |
| Playwright/HTTP demo smoke | 16 checks passed, including five demo customers |
| Load/mTLS/security e2e | Passed; not production capacity assessment or independent pentest |
| Trivy HIGH/CRITICAL vuln/secret/misconfig | Passed |
| CodeQL | Configuration failure: Code scanning is not enabled for this repository; not successful evidence |

New checks covered saved mandate metadata, refusal to export missing metadata, incomplete/future metadata rejection, immutable millisecond SCT Inst acceptance timestamps, slash rules, the 70-character name limit and seven ISO XSD checksums.

The first assessment run found CRLF/LF checksum differences introduced during publication. Original `sha256` and `storedSha256` are now distinct and stored XSDs use LF. The integrity rerun passed.

**Full SEPA conformance is unconfirmed.** [Requirements/evidence/gaps](SEPA-ISO-CONFORMANCE.md); [machine-readable status](sepa-iso-assessment.json).

## Earlier Java 21 verification

Commit: `0102a73a6ee6d5b91227df846e19d3278c3cb033`. [Actions 38043507242](https://github.com/straigenlu-netizen/eu_fin/actions/runs/38043507242), 10 October 2026.

| Check | Result |
|---|---|
| Maven/compiler release 21/JDK 21 | 53 discovered, 52 executed, one conditional ExternalKafkaTest skipped; zero failures/errors |
| PostgreSQL 16.6/Kafka 3.9 Testcontainers | Passed: migrations, ledger, outbox, broker, consumer and JPA projection |
| Playwright | 16 checks passed against real UI/backend with mocked IdP endpoints |
| Demo HTTP smoke | Five VERIFIED customers, one 1000 EUR account each |
| Trivy HIGH/CRITICAL vuln/secret/misconfig | Passed |
| CodeQL | Blocked: Code scanning is not enabled for this repository; no successful CodeQL claim |

Java 21 is the only supported version: compiler, CI and Docker build/runtime. Historical Java 17 reports concern earlier revisions.

Initial failures were fixed: Trivy action tag, Kafka 3.9 effective controller listener, PostgreSQL JDBC Instant parameters, vulnerable dependencies, pod security context and writable root filesystem.

Versions: Spring Boot 3.5.16, PostgreSQL JDBC 42.7.14, Spring Kafka 3.3.16 and Trivy action v0.36.0, checked against primary repository refs/releases. The HIGH/CRITICAL gate remained enabled without hiding findings through allowlists or disabling scanners.

Full Compose with live Keycloak, Schema Registry and Kafka Streams, deployment and real payment networks remain unverified. A mocked browser IdP does not establish real SCA/MFA. No independent pentest or complete applicable SEPA/ISO rule acceptance exists; [PRODUCTION-READINESS.md](PRODUCTION-READINESS.md) criteria remain open.

Initial local reports and load metrics are preserved separately; they do not prove production capacity.
