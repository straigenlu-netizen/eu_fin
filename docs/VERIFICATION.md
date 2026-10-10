# Initial local verification - 10 October 2026

Current remote verification: [CI-RESULTS.md](CI-RESULTS.md). The restrictions below describe the initial local environment. PostgreSQL/Kafka and browser suites subsequently passed on GitHub.

This historical run used JDK 17. Maven `-Pperformance verify` passed with **51 executed tests, no errors or failures**. Conditional ExternalKafkaTest was skipped in that run and separately passed against a real Kafka 3.9.0 broker: **52 distinct executed JUnit tests** in total. The final identity-correction queue addition separately passed CustomerSecurityE2ETest (7 tests); the application was repackaged afterwards. The current project supports Java 21 only; these results do not verify its current revision.

| Suite | Executed | Coverage |
|---|---:|---|
| RulesTest | 13 | IBAN, amounts, routes and banking rules |
| BankIntegrationTest | 21 | Postings, holds, returns, mandates, concurrency, ISO XSD, XXE, inbox/outbox, payee and worker |
| CustomerSecurityE2ETest | 7 | HTTP roles, encrypted PII, masked staff views, identity corrections, mass assignment, versions, MFA and headers |
| HttpE2ETest | 2 | Real HTTP/JWT requests and rejection scenarios |
| MtlsE2ETest | 1 | Real HTTPS, certificates, rejection without client certificate and additional JWT |
| ProfileCipherTest | 2 | GCM integrity and subject AAD |
| RateLimitE2ETest | 1 | HTTP rate limits and Retry-After |
| TopologyTest | 1 | Kafka Streams state/statistics and duplicate events |
| CircuitBreakerTest | 1 | Breaker opening and transitions |
| ApiContractTest | 1 | OpenAPI method/path coverage against controllers |
| PerformanceTest | 1 | HTTP load and idempotency preservation |
| ExternalKafkaTest, separate | 1 | Real Kafka broker, committed outbox and JPA projection |

An HTTP smoke test of the packaged application with demo data also passed: exactly five customers, expected names, VERIFIED status and one 1000 EUR account each. JavaScript passed `node --check`; Python scripts compiled. This was API verification, not a browser smoke test.

## Historical coverage

JaCoCo for the last full local verify: 994/1166 lines (**85.25%**) and 271/417 branches (**64.99%**). Coverage does not establish security, complete banking rules or production readiness. CI has no mandatory coverage threshold.

## Historical load test

`performance-results.json`: 200 requests, 8 concurrent workers, 80% encrypted profile reads and 20% repeated payment commands. In-memory H2, one JVM and a short local run. p50 **7 ms**, p95 **15 ms**, p99 **33 ms**, approximately **895 requests/second** over **0.223 seconds**.

The regression threshold is p95 < 2000 ms; the test also verifies one created payment and one hold under retries. This is not a sustained/soak benchmark, SEPA network latency test or capacity forecast. Capacity decisions require PostgreSQL, real adapters, multiple instances, sustained load, larger datasets and CPU/GC/lock/lag observations.

## Prepared but not executed in the initial local environment

- PostgreSQL/Kafka Testcontainers: Docker was unavailable; separate PostgreSQL startup was also limited by the environment's UID model.
- Playwright: Chromium was absent and CDN download blocked. Scenarios and a separate CI job were prepared; no local browser result was claimed.
- Full Compose with Keycloak, PostgreSQL, Registry and Kafka Streams; Kubernetes deployment.
- GitHub Actions, JDK 21 build, CodeQL and Trivy: the remote repository had not yet been created at that time.
- Independent pentest, real PKI, KYC/AML/VOP and payment scheme certification.

Reproduce current suites with `./mvnw -Pperformance verify`, or `./mvnw -Pcontainers verify` with Docker; use the e2e instructions for the browser. For the optional external Kafka test, start Kafka on `127.0.0.1:19092` and run `EUFIN_EXTERNAL_KAFKA_TEST=true ./mvnw -Dtest=ExternalKafkaTest test`. Override the address with `EUFIN_TEST_KAFKA`.
