# Mandatory production requirements

Production security and conformance with applicable SEPA and ISO 20022 standards are acceptance targets. The current version does not yet meet them. A learning mechanism must not be presented as a certified integration.

| Area | Required outcome | Acceptance evidence |
|---|---|---|
| SEPA SCT/SCT Inst/SDD Core | Applicable rulebooks and usage guidelines with pinned versions | Rule matrix, positive/negative scheme tests and participant/partner validation |
| ISO 20022 | XML and scheme semantics, complete statuses and R-transactions | XSD, scheme validators, interoperability fixtures and no silent field loss |
| VOP/SCA | Real payee verification and strong customer authentication with dynamic linking | Provider integration and security tests |
| Interbank settlement | Bank/PSP connectivity, liquidity, unknown outcomes and reconciliation | Sandbox conformance and participant operating scenarios |
| KYC/AML/sanctions | Verified data sources, monitoring, case management and human investigation | Provider contract tests and approved procedures |
| Keys and access | KMS/HSM, rotation, least privilege, secret manager, TLS/mTLS and Kafka ACL | Independent audit, pentest and revocation/rotation tests |
| Money and resilience | Production database, recovery, high availability, duplicate/loss protection | PostgreSQL concurrency, fault injection, backup restore and sustained load/soak tests |
| DORA/GDPR | Incident procedures, retention, supplier controls and data subject rights | Organisational and legal applicability assessment |

Selecting the exact profile requires the organisation's role (bank/PSP/learning project), applicable schemes and channels, partner bank/providers, sandbox access, target SLAs and infrastructure. Do not supply secrets or customer documents in chat or the repository. Real connectivity and production acceptance require appropriate specialists and providers.

This document records outstanding obligations. It is not a certificate, legal opinion or permission to use the application with real money.
