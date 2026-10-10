# SDD mandate lifecycle in the lab

The payer account owner creates and revokes a mandate. Operator status alone does not permit granting consent on a customer's behalf.

For `OOFF` and `FNAL`, only one collection may be active at a time. A retry with the original idempotency key returns the original payment; a new key cannot create a second debit. After a pre-settlement rejection (`REJECTED`), a corrected instruction may be submitted. Reuse is blocked after settlement, Return or Refund. This is a conservative local model: separate re-presentation after Return, where EPC permits it, is not implemented.

The first payment retains `FRST`; subsequent instructions use `RCUR`. A recurrent mandate may start with `RCUR`: FRST is not mandatory. Collection type and date are saved in the payment, so exporting an old pain.008/pacs.003 does not change after another collection or server date change.

A new collection is rejected after 36 calendar months since the latest presentation. Reject, Return and Refund do not erase presentation activity. Here presentation means acceptance of an API instruction; real CSM presentation is absent. For an unused mandate, the signature date is not substituted for the last collection date. Migration restores the last date from saved payments; production migration requires a separate CSM history import. Inactivity blocks operations, but background legal cancellation and creditor notification are not implemented.

SCREEN, CLEAR and SETTLE recheck the mandate. Revocation or expiry rejects the payment with MD01, releases the hold and prevents a debit. Revoking an already settled mandate does not reverse postings: Refund/Return/claim procedures are needed.

Checks and writes execute in a transaction with account and mandate locks. The concurrency test submits two different OOFF idempotency keys.

## Limitations

The collection date currently equals the lab payment creation date. Fourteen-calendar-day pre-notification (or another agreed period), future scheduling, D-1, banking calendars, signatures/e-Mandates, amendments and complete R-transactions remain future work. Saving the date prevents XML drift; it does not prove SEPA timing compliance. Legacy mandates without metadata remain lab-only and cannot be exported to ISO.

Source: [EPC016-06, SDD Core Rulebook 2025 v1.2](https://www.europeanpaymentscouncil.eu/sites/default/files/kb/file/2026-09/EPC016-06%202025%20SDD%20Core%20Rulebook%20version%201.2.pdf), sections 4.2, 4.3.4 and 4.5.4. Overall status: **NOT_CONFIRMED**, not production certification.
