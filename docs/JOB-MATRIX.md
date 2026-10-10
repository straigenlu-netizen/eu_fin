# Job requirements and project mapping

| Requirement | Demonstrable implementation | Remaining gaps |
|---|---|---|
| Java 21, Spring Boot 3 | Compiler release 21, CI JDK 21, REST and DI | Java 21 is the only supported version; prior JDK 21 verification passed |
| Spring Data/Hibernate/JPA | JPA read model; SQL commands and Spring transactions | Other database vendors are unverified |
| OAuth2/OIDC/JWT/mTLS | Resource server, PKCE UI, Keycloak configuration, real JWT/HTTPS tests | Full real Keycloak login has not been verified |
| Microservices, gateway, circuit breaker | Module boundaries, nginx template and circuit breaker | No independent services or deployed gateway |
| Saga/outbox/CQRS/event sourcing | Payment lifecycle, compensation, durable outbox/inbox and projection | No full event sourcing or distributed saga orchestrator |
| Kafka/partitioning/Streams/registry | Real broker test, keyed partitions, Streams topology and schema registration | Registry and Streams cluster Compose have not been run |
| Contract-first OpenAPI | API specification and route coverage test | No Pact/Spring Cloud Contract consumer suites |
| ISO 20022 pain/pacs/camt | Official XSDs, restricted import and XML export tests | Not all messages or network usage guidelines |
| SWIFT MT/MX | Restricted MT103 preview | Not CBPR+/FIN migration |
| SEPA SCT/Inst/SDD, TIPS/RT1 | Internal EUR ledger rules, deadlines, mandates, independent SDD refunds, 13-month claims, investigation and recovery | No real connectivity or certification |
| EU regulation | Concepts explained in BANKING and SECURITY | No legal compliance confirmation |
| AI/spec-driven development | Documented invariants, OpenAPI, tests and AI restrictions | No separate AI generation/governance platform |
| Databases/locking/migrations | SQL, transactions, ordered locks, Flyway and PostgreSQL container test | PostgreSQL/Kafka passed in CI; Oracle/MS SQL are absent |
| Docker/Kubernetes/CI | Compose, Dockerfile, Kubernetes template and GitHub Actions | Infrastructure has not been deployed |
| Quality/testing/security | JUnit, Mockito, HTTP e2e, TLS, load regression and JaCoCo | Container/browser suites passed in CI; no independent pentest |
| Scheduling | Spring scheduler, expiry worker and shell/Python lab tools | No UC4/AutoSys/Control-M integration |

In an interview, explaining invariants, failure modes and boundaries is more valuable than claiming that every technology in the vacancy is fully implemented. This is a preparation project, not a replacement for hands-on production payments experience.
