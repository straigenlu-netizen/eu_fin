# SEPA / ISO 20022 assessment

Assessment date: 2026-10-10. Result: **full SEPA conformance is not confirmed; production launch must wait until the listed gaps are closed**. This is an engineering assessment of source code and automated tests, not an opinion from EPC, a bank, regulator or independent auditor.

## Standards baseline

The assessment checked EPC rulebooks 2025 v1.2 (effective 30 September 2026), implementation guidelines 2025 v1.0 and ISO 2019:

- [SCT](https://www.europeanpaymentscouncil.eu/what-we-do/epc-payment-schemes/sepa-credit-transfer-sct/sepa-credit-transfer-rulebook-and)
- [SCT Inst](https://www.europeanpaymentscouncil.eu/what-we-do/epc-payment-schemes/sepa-instant-credit-transfer-sct-inst/sepa-instant-credit-transfer)
- [SDD Core](https://www.europeanpaymentscouncil.eu/what-we-do/epc-payment-schemes/sepa-direct-debit-sdd/sepa-direct-debit-core-rulebook-and)
- [SCT Inst Inter-PSP IG](https://www.europeanpaymentscouncil.eu/document-library/implementation-guidelines/sepa-instant-credit-transfer-inter-psp-implementation-2)
- [Slash rules for references and identifiers](https://www.europeanpaymentscouncil.eu/document-library/guidance-documents/clarification-paper-use-slashes-references-identifications-and)
- [SDD mandates and refunds](https://www.europeanpaymentscouncil.eu/what-we-do/sepa-direct-debit)

EPC postponed the planned November 2026 end of unstructured addresses without announcing a replacement date. Do not treat the previous date as a current requirement. The current restricted name profile limits names to 70 characters; future changes must not be applied early.

Official base ISO XSDs and SHA-256 hashes are listed in `src/main/resources/iso/xsd/sources.json`. `sha256` identifies the original download; `storedSha256` identifies the LF-normalised copy. `IsoSchemaIntegrityTest` checks stored bytes; newline normalisation does not alter XML content. Provenance and checksums do not establish EPC business-rule conformance.

## Assessment matrix

| Area | Implementation/evidence | Status and limitations |
|---|---|---|
| pain.001.001.09, pain.008.001.08, pacs.008.001.08, pacs.003.001.08, pacs.002.001.10, pacs.004.001.09, camt.053.001.08 | `IsoService.validate`, official ISO XSDs; `isoRoundtripAndInvalidInput`, `mandateAndRefund`, `IsoSchemaIntegrityTest` | Syntax checked for selected examples, not all ISO messages or the full EPC profile |
| EUR/precision/SLEV/SEPA/control sum | Single pain.001 import and internal transfer exports; negative integration tests | Internal accounts, one transaction and current date only |
| Names and identifiers | `Rules.sepaName`, `Rules.sepaReference`; boundary/slash tests | Partial EPC rules; no complete permitted character-set validation for every attribute |
| SCT Inst AT-T056 | Saved `createdAt` supplies UTC `AccptncDtTm` with exactly three fractional digits; timestamp regression test | Internal acceptance time only; no evidence of CSM exchange/confirmation within ten seconds |
| SDD mandate | Stored reference, creditorIdentifier, signatureDate and sequenceType used by XML | Declared metadata, not verified signatures or registered creditor IDs. Legacy metadata-free mandates cannot be exported |
| Signature date preservation | `directDebitXmlKeepsActualMandateMetadata`, `legacyMandatesCannotFabricateSepaMetadata`, `incompleteOrFutureDatedMandateMetadataIsRejected` | Current-date substitution removed; incomplete/future metadata rejected |
| EPC TVS/cross-field rules | Requires applicable schemas and positive/negative reference datasets | **Not confirmed**. Official ZIP download returned HTTP 403 in the assessment environment. Do not substitute homemade TVS; production XML must retain the default ISO namespace |
| SCT/SCT Inst/SDD R-transactions | Lab transitions and individual XML messages | **Incomplete**: reason codes, correlation, camt.056/029, pacs.028, investigations and scheme-specific deadlines |
| SDD refunds | Creditor-independent eight-week credit; immediate credit for unauthorised claims within 13 calendar months; separate bank receivable | **Partial**: [SDD-REFUNDS.md](SDD-REFUNDS.md); no external settlement, complete exceptions/compensation or evidence validation |
| SDD timing and mandates | Activity/expiry/limit checks; OOFF/FNAL reuse prevention, FRST to RCUR, 36-month inactivity from latest API acceptance, checks before settlement | No pre-notification, calendar/cut-off, CSM presentation, complete amendment/R-transaction lifecycle or consent evidence; [details](SDD-MANDATES.md) |
| SEPA coverage | IBAN validation for ten countries, virtual internal accounts and synthetic BIC EUFIDEFFXXX | **Not confirmed**: incomplete geography/reachability and no real participants |
| TIPS/RT1/settlement | TIPS_SIM/RT1_SIM simulators | **Not connected**: no external clearing, settlement finality, liquidity or CSM reconciliation |
| VOP/SCA/AML | Internal name comparison, roles and simulated screening | **Does not meet complete production requirements**: real providers, dynamic linking, regulatory procedures and validation required |

pacs002/pacs004 exports provide basic ISO XML. New local SDD refunds use MD06/MD01; other lab returns retain `LAB_RETURN`. The full EPC message profile is unconfirmed. camt.053 is a learning full-history statement, not a certified periodic bank statement. FRST/RCUR/OOFF/FNAL have a restricted local lifecycle with saved collection type/date. Return re-presentation and full EPC lifecycle remain incomplete.

## Mandate metadata

`POST /api/v1/mandates` accepts additional fields. This example uses a fictional creditor ID and is not for real submission:

```json
{
  "debtorId": "<payer-account-id>",
  "creditorId": "<creditor-account-id>",
  "validUntil": "2027-01-01",
  "maxAmount": 20.00,
  "mandateReference": "MANDATE-2026-001",
  "creditorIdentifier": "DE98ZZZ09999999999",
  "signatureDate": "2026-09-01",
  "sequenceType": "RCUR"
}
```

Supply all four metadata fields together. Omitting them preserves legacy lab API compatibility, but pain.008/pacs.003 export returns `SEPA_MANDATE_METADATA_REQUIRED`. Creation is API-only; there is no portal mandate creation form yet. Creditor ID syntax validation does not verify issuance or national checksum rules.

## Requirements before confirming full conformance

1. Fix the project's role (C2PSP gateway, PSP or bank core), schemes, message directions and contractual PSP/CSM. Obtain its interface versions and sandbox.
2. Implement the complete EPC profile, TVS/reference datasets, semantic rules, calendars, R-transactions and mandate/refund procedures. Basic refunds/cases exist locally; external settlement and statutory exceptions remain open. Trace each requirement to implementation and positive/negative tests.
3. Add authentic external transport, correlation, unknown-outcome recovery, liquidity and reconciliation; pass the selected PSP/CSM interoperability and acceptance tests.
4. Complete separate security/compliance assessments for PSD2/IPR/DORA/GDPR/AML with accountable people and operational procedures. CodeQL runs automatically for public repositories; private repositories require supported Code Security and `CODEQL_ENABLED=true`. The six-rule Semgrep baseline is not equivalent to CodeQL.
5. Change overall status only after reproducible evidence, approvals and acceptance. A local XSD test cannot issue certification or establish complete conformance.

## Reverification and evidence

Use JDK 21 with `./mvnw -Pcontainers verify`; browser: `bash e2e/run-local.sh`. GitHub Actions reports results for a specific commit. A test listed in this matrix is not itself proof of success; record evidence after CI completes.

Historical refund run: [Actions 38048465852](https://github.com/straigenlu-netizen/eu_fin/actions/runs/38048465852), commit `ddbe24ae0fa2a2b104eca6d77826b6692b683d10`: 81 executed tests, one conditional Kafka skip, zero failures/errors; 24 browser checks and Trivy passed. CodeQL was not confirmed. SDD load: 120 requests, 8 workers, p95 40 ms/p99 51 ms on H2, not a production SLA.

Latest tested mandate implementation: [Actions 38051899042](https://github.com/straigenlu-netizen/eu_fin/actions/runs/38051899042), commit `c35a91e08c9ebb4388937ada1971a5c9bff18761`: workflow SUCCESS, 88 executed tests, one conditional skip, 24 browser checks, Semgrep and Trivy passed; CodeQL explicitly skipped. [CI report](CI-RESULTS.md) and [machine-readable assessment](sepa-iso-assessment.json).

English-only verification and CodeQL activation: [Actions 38054109827](https://github.com/straigenlu-netizen/eu_fin/actions/runs/38054109827), commit `af7a6ec556bb51cd3bf9081e27bf1fa086616898`: all jobs passed, including CodeQL analysis, 88 executed tests, 24 browser checks and the 108-file language guard. This does not change overall NOT_CONFIRMED conformance status.
