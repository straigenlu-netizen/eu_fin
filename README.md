# eu_fin - European payments learning bank

A Java backend application for studying the banking domain covered by the target job description. It includes customer and staff portals, five fictional customers, EUR accounts, credit transfers, mandate-based direct debits, refunds, a posting journal, ISO 20022 XML and Kafka events.

This is a learning implementation with virtual money. It has no connections to SWIFT, TIPS, RT1 or real banks. Validation against official ISO XSDs does not establish compliance with all SEPA rules or banking certification. A production bank requires substantially more than this project implements.

Assessment and evidence boundaries: [SEPA-ISO-CONFORMANCE.md](docs/SEPA-ISO-CONFORMANCE.md).

## Quick start with the portals

Requires Docker Compose and Python 3. Run from the repository root:

```sh
python3 scripts/init-lab.py
docker compose -f compose.yml -f compose.oidc.yml up --build
```

Open http://localhost:8080/bank/. The identity provider is at http://localhost:8082. Random passwords are generated in `.env`: use `DEMO_ALICE_PASSWORD` for `alice` and `DEMO_AGENT_PASSWORD` for staff user `agent`. Other logins: `bob`, `carla`, `daniel`, `eva`, `operator`. The first login requires OTP setup in an authenticator app. `.env` contains secrets: do not publish or forward it. Keycloak `start-dev`, local HTTP and a single Kafka broker are for the local lab only. Using another address requires consistent issuer, redirect URI and CSP changes.

The first start imports the realm and creates five profiles with 1000 EUR each. Subsequent starts do not create duplicates. A new user registers on the login page and completes the profile in the portal. Staff must approve the simulated identity check before the customer can open an account or make payments. This step does not perform real document or sanctions checks.

New account IBANs are entered manually in the lab portal. This supports experiments; it is not a production IBAN allocation process. For an Alice-to-Bob payment, enter `NL91ABNA0417164300`, payee name `Bob Example`, an amount and a unique reference. The local worker simulates screening, clearing and settlement. Standard and instant transfers use a single internal learning ledger.

The full Compose stack is prepared but has not been started in the development environment because Docker is unavailable. PostgreSQL/Kafka, JDK 21 and browser tests subsequently passed in GitHub Actions. Current evidence: [CI-RESULTS.md](docs/CI-RESULTS.md); initial local report: [VERIFICATION.md](docs/VERIFICATION.md).

## Build and tests

Requires JDK 21, Maven Wrapper and access to Maven Central:

```sh
./mvnw verify
./mvnw -Pperformance verify
./mvnw -Pcontainers verify
```

On Windows, use `mvnw.cmd`. The default run excludes performance and container suites. `performance` adds the H2 load suite; `containers` requires Docker and includes PostgreSQL/Kafka and load tests. Browser scenarios: [e2e/README.md](e2e/README.md).

For API experiments without Keycloak, generate `.env`, load its variables into your shell, build with `./mvnw package`, then run `java -jar target/eu-fin-0.1.0.jar`. POSIX shell example: `set -a; . ./.env; set +a`. The default database is in-memory H2; stopping the process loses its data. Generate a lab JWT with `python3 scripts/lab-token.py alice bank.pay`. This script simulates the issuer and MFA and is not real authentication. Do not use it in a deployed bank. The portal requires an OIDC provider configured as in Compose.

## Customers and permissions

| Role | Allowed operations | Restrictions |
|---|---|---|
| Customer | Own profile, contacts, accounts, transfers, mandates and consents | Other customers' accounts are inaccessible; name and date of birth changes require a request |
| Staff `bank.agent` | Customer list, risk, suspension, KYC decisions and correction review | List contacts are masked; no direct identity or balance changes; self-review is prohibited |
| Operator `bank.ops` | Lab funding, payment transitions, recovery and reconciliation | Separate permissions and MFA are required; this is the payment operations role |
| TPP `bank.read` | Statements under valid customer consent | TPP subject, account, expiry and revocation are checked |

The five customers are Alice Demo, Bob Example, Carla Sample, Daniel Demo and Eva Example. All data and money are fictional. Personal profiles and correction requests use AES-256-GCM encryption. Owner names also appear in operational account tables and XML statements; this is not whole-database encryption.

SDD refunds, disputes and independent recovery: [SDD-REFUNDS.md](docs/SDD-REFUNDS.md). The portal includes **Refunds & disputes** and **Payment operations** (`bank.ops`) tabs.

## Learning path

1. [Architecture and transactions](docs/ARCHITECTURE.md).
2. [Banking operations and messages](docs/BANKING.md).
3. [Security controls and limits](docs/SECURITY.md).
4. [Mapping to the job requirements](docs/JOB-MATRIX.md).
5. [Detailed walkthrough plan](docs/LEARNING.md).
6. [Verification evidence](docs/VERIFICATION.md).

API: [OpenAPI](api/openapi.json). Sources: `src/main/java/dev/eufin`; migrations: `src/main/resources/db/migration`; official XSDs and checksums: `src/main/resources/iso/xsd/sources.json`.

The current project uses **Java 21 only**: compiler release 21, Docker build/runtime JDK/JRE 21 and CI JDK 21. Historical Java 17 reports describe earlier revisions.

Mandate lifecycle and remaining gaps: [SDD-MANDATES.md](docs/SDD-MANDATES.md). Source security checks and CodeQL activation: [security/semgrep/README.md](security/semgrep/README.md).

All repository documentation, interface text, comments and messages must be written in English. CI checks tracked text files for Cyrillic characters with `python3 scripts/check-english.py`. This policy concerns repository content, not customer input or supported payment data.
