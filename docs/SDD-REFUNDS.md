# SDD Core refunds: credit, investigation and recovery

These are three independent processes. Crediting the payer does not wait for the creditor to repay the bank and does not require available creditor funds. All money and bank control accounts here are virtual.

## Requirements and sources

- [EPC SDD Core rulebook 2025 v1.2](https://www.europeanpaymentscouncil.eu/sites/default/files/kb/file/2026-09/EPC016-06%202025%20SDD%20Core%20Rulebook%20version%201.2.pdf): unconditional refunds within eight weeks and unauthorised debit claims within 13 months.
- [EPC reason codes v8.1](https://www.europeanpaymentscouncil.eu/document-library/guidance-documents/guidance-reason-codes-sepa-direct-debit-r-transactions): MD06 for unconditional Core refunds and MD01 for unauthorised debit refunds.
- [PSD2 Articles 71-73](https://eur-lex.europa.eu/legal-content/EN/TXT/?uri=CELEX:32015L2366): unauthorised payment refunds are immediate, no later than the end of the next business day; credit value dates must not be later than the original debit. The PSP bears the burden of proving authorisation; recorded use of an instrument alone is insufficient evidence.

This implements limited common scenarios. National law, contract terms, the notification deadline exception when information was not provided, the fraud exception with written competent-authority notice, interest/compensation and interbank procedures remain incomplete. These tests do not establish full PSD2/SEPA compliance.

## Money movements

Example: the payer had 100 EUR; the creditor received 10 EUR and spent it.

1. The payer requests a refund. The bank credits 10 EUR, restoring the payer's balance to 100 EUR without changing the creditor account.
2. Two ledger rows share a booking ID: `SDD_RECOVERY = -10` and payer account `+10`. They sum to zero. The control account's negative sign is the opposing side of the customer credit; this is not a complete production general ledger.
3. `sdd_refund` records a separate bank receivable: amount 10, recoveredAmount 0, outstandingAmount 10. The receivable is separate from the creditor's account funds.
4. An independent operator later performs a simulated 4 EUR recovery when creditor availability permits. Postings: creditor `-4`, `SDD_RECOVERY = +4`. The payer is not debited; outstandingAmount becomes 6.
5. Another 6 EUR recovery closes the receivable and returns this operation's control-account balance to zero.

Real recovery requires contractual debit rights, an external PSP/CSM and settlement confirmation. A lab operator button does not grant legal authority.

`ledger.account_id` and `ledger.bank_account` are mutually exclusive: a CHECK requires exactly one. Old rows without account_id migrate to `TREASURY`. `value_date` is separate from booking time; refund value dates use the original debit date. Statements and camt.053 include it. Interest is not calculated, so restoring interest and other financial consequences is not verified.

## Deadlines and retries

- Eight-week deadline: debit date plus 8 weeks.
- Unauthorised SDD deadline: debit date plus 13 calendar months, accounting for month lengths and leap years rather than a fixed number of days.
- UTC dates include the entire last day. This is an explicit lab policy; a production timezone/calendar must be agreed with the PSP.
- Retrying an existing refund returns the result without another posting, even after the deadline. New late requests are rejected.
- Unique payment_id in sdd_refund, payment locks and one transaction protect concurrent requests. Credit, postings, events and outbox commit together.
- Recovery idempotency binds the operator, amount and payment. Changing amount/payment under the same key conflicts. Recovery cannot exceed outstandingAmount or consume reserved creditor funds.

## Unauthorised payment claims

A customer alleges `NO_MANDATE`, `REVOKED_MANDATE` or `OTHER_UNAUTHORISED`. The service immediately credits the payer and opens `REFUNDED_UNDER_REVIEW`. The allegation is not automatically proven absence of authorisation.

An operator closes the case as `CONFIRMED_UNAUTHORISED` or `AUTHORISATION_EVIDENCE_FOUND`. The latter requires a SHA-256 hash of external evidence. A hash only references reviewable material; signed mandate storage and evidence verification are absent. Finding evidence does not automatically debit the payer again.

An operator cannot review a payment involving their own account as either party, even with bank.ops. Another customer cannot create or read the payer's case. bank.agent does not grant monetary operation access. HTTP ops routes require bank.ops and MFA.

Audit events record subjects/action types without free text containing PII. audit_log is not yet a tamper-resistant archive.

## API and UI

| Action | Method and path |
|---|---|
| Payer unconditional refund | POST `/api/v1/payments/{id}/refund` |
| Unauthorised SDD claim | POST `/api/v1/payments/{id}/sdd-dispute` |
| Payer's case | GET `/api/v1/payments/{id}/sdd-dispute` |
| Operator case list | GET `/api/v1/ops/sdd-disputes?page=0` |
| Investigation decision | POST `/api/v1/ops/sdd-disputes/{caseId}/decision` |
| Bank receivables | GET `/api/v1/ops/sdd-refunds?page=0` |
| Simulated recovery | POST `/api/v1/ops/sdd-refunds/{paymentId}/recoveries`, Idempotency-Key |

Example bodies:

```json
{"allegation":"NO_MANDATE"}
```

```json
{"decision":"CONFIRMED_UNAUTHORISED"}
```

```json
{"amount":4.00}
```

The signed-in payer uses **Refunds & disputes** with the collection ID. An independent bank.ops operator uses **Payment operations**. The customer UI does not yet list all incoming SDDs; obtain the ID from the collection creation API. Mandate creation and creditor collection initiation remain API operations.

## Test coverage

`BankIntegrationTest`: empty/reserved creditor funds, bank receivables, duplicate/concurrent requests, last-day deadlines, 13 calendar months with leap-year February, value dates, partial recovery, duplicate recovery protection, permissions/self-review, evidence requirements, no automatic payer re-debit and full rollback on audit failure.

`HttpE2ETest`: real HTTP/JWT/DB, other customers, scopes, MFA, allegation/evidence validation, retries, investigation and partial recovery. `PostgresKafkaTest`: migrations, refunds, recovery and outbox/consumer on real containers. `SddMigrationTest`: pre-V4 postings and post-upgrade constraints. Playwright: payer refund/claim and independent investigation/recovery against the real UI/backend with a mock IdP.

`PerformanceTest`: 120 HTTP requests, 8 workers, 12 collections with 10 attempts each; half unconditional refunds, half unauthorised claims, with creditor funds already spent. It checks p50/p95/p99, posting/case counts, ledger balance and one credit per payment. p95 < 2000 ms is a regression signal, not a production SLA; short H2 runs do not establish production capacity. Results are saved in `target/sdd-performance-results.json` and CI artifacts.

Full EPC TVS validation and external acceptance have not been performed. Adding a pacs.004 reason code does not establish a complete EPC message profile.
