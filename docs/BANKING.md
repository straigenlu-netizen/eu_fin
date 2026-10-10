# Banking domain concepts

## Initiation, clearing and settlement

Initiation creates an instruction. Validation checks format, customer, account, amount, availability and scheme. Screening checks restrictions and risk. Routing selects a channel. Clearing establishes participants' obligations. Settlement executes the transfer of funds. Reconciliation compares records and identifies discrepancies.

All parties in this project have accounts in one ledger. Routes `TIPS_SIM`, `RT1_SIM` and `SEPA_CLEARING` are learning choices, not participation in those systems. Real interbank settlement requires settlement accounts, liquidity, certificates, connectivity and participant rules.

| Scheme | Lab implementation | Limitations |
|---|---|---|
| SCT | EUR transfer, validation, reservation, postings and return | No banking calendar, cut-off or external participant exchange |
| SCT Inst | Ten-second deadline, timeout, reservation release and simulated routing | No real interbank SLA guarantee or production VOP |
| SDD Core | Mandate, collection, revocation; independent eight-week refund; immediate credit on an unauthorised claim within 13 calendar months; separate recovery | No complete pre-notification calendar, external refund settlement, statutory exceptions or complete evidence storage |
| Open Banking | Account-specific read consent, expiry, revocation and TPP binding | Not a certified PSD2 API; no real SCA or TPP registration |

An SDD mandate authorises the creditor to initiate a debit. It does not grant arbitrary withdrawal rights to bank staff. Open Banking consent grants data access; it does not automatically authorise payment initiation.

Refunds, investigation and bank receivables: [SDD-REFUNDS.md](SDD-REFUNDS.md), including APIs, UI, postings, dates and tests. Mandate lifecycle: [SDD-MANDATES.md](SDD-MANDATES.md).

## ISO 20022

| Message | Direction or purpose | Project support |
|---|---|---|
| pain.001.001.09 | Customer to bank: credit transfer instruction | Restricted import and export |
| pain.008.001.08 | Customer to bank: direct debit | Export |
| pacs.008.001.08 | Bank to bank: credit transfer | Export |
| pacs.003.001.08 | Bank to bank: direct debit | Export |
| pacs.002.001.10 | Payment processing status | Export |
| pacs.004.001.09 | Payment return | Export |
| camt.053.001.08 | Account statement and movements | Export |

XSD validates structure and types. SEPA business rules, usage guidelines, ISO versions and channel requirements are separate layers. Valid XML alone does not prove it can be sent to a network. The restricted profile supports one payment, EUR, known internal accounts, supported fields and the current execution date. Unsupported input is rejected instead of silently discarded.

XML uses the synthetic BIC `EUFIDEFFXXX`. MT103 preview parses a restricted body and rejects unknown fields. It is not full SWIFT MT-to-MX migration, a FIN message or CBPR+ support.

## Regulation

PSD2/Open Banking affects consent, authentication and TPP access. The Instant Payments Regulation affects timing, availability and payee verification. DORA covers operational resilience, ICT risk, incidents and third-party suppliers. GDPR requires a processing basis, minimisation, retention, data subject rights and protection. AML/sanctions require real data sources, monitoring, investigation and reporting.

This project demonstrates selected engineering mechanisms, not legal compliance. Staff KYC decisions and screening are simulations. Payee verification compares the submitted name to the internal account owner; it is not the EPC VOP scheme.

## Primary sources

- [EPC SCT](https://www.europeanpaymentscouncil.eu/what-we-do/epc-payment-schemes/sepa-credit-transfer-sct)
- [EPC SCT Inst](https://www.europeanpaymentscouncil.eu/what-we-do/epc-payment-schemes/sepa-instant-credit-transfer-sct-inst)
- [EPC SDD](https://www.europeanpaymentscouncil.eu/what-we-do/sepa-direct-debit)
- [EPC Verification of Payee](https://www.europeanpaymentscouncil.eu/what-we-do/other-schemes/verification-payee-vop)
- [ECB TIPS](https://www.ecb.europa.eu/paym/target/tips/html/index.en.html)
- [EBA Clearing RT1](https://www.ebaclearing.eu/services-instant-payments/rt1/)
- [ISO 20022 definitions](https://www.iso20022.org/iso-20022-message-definitions)
- [EBA DORA](https://www.eba.europa.eu/activities/direct-supervision-and-oversight/digital-operational-resilience-act)

Check rulebook versions and effective dates before real integration. Official URLs and SHA-256 hashes for the XSDs are stored alongside the schemas.
