# Detailed walkthrough plan

Study one complete story at a time and confirm each explanation with an experiment. Each topic starts with short theory, then code, a test, a failure and its correction.

| Session | Main question | Exercise |
|---|---|---|
| 1. Bank customer | How does login differ from a banking profile and KYC? | Register; attempt account opening before approval; check staff restrictions |
| 2. Money and ledger | Why not use double for money? What is the available balance? | Create a hold, inspect balance/held and find the two postings |
| 3. Transfers | Where does HTTP end and the banking lifecycle begin? | SCT/SCT Inst, wrong payee name, insufficient funds and timeout |
| 4. Concurrency | Why must an HTTP retry not debit twice? | Reuse an idempotency key, change the payload and send parallel requests |
| 5. ISO 20022 | How do pain, pacs and camt differ? | Inspect XML, validate XSD, try unsupported fields and XXE |
| 6. SDD and refunds | Who initiates direct debit? Why is recall not cancellation? | Mandate, revocation, refund, receivable and recovery postings |
| 7. Kafka and CQRS | What happens between commit and publish? | Outbox, duplicate events, sequence, stop/restart the relay |
| 8. Security | Why do JWT and MFA not replace ownership checks? | Another account, forged token, missing MFA and stale profile version |
| 9. Performance | What does p95 say, and what does a short H2 test not establish? | Increase load, use PostgreSQL and compare locking |
| 10. Regulation and interviews | Where does demonstration end and compliance begin? | Answer PSD2/DORA/GDPR/AML questions and explain remaining gaps |

After each topic, explain it in your own words and locate the relevant code. Useful checkpoints: What rolls back? What happens on retry? Who may perform the action? How can it be detected in the journal? What is simulated?

Start with README, V1/V2 migrations, CustomerSecurityE2ETest, BankIntegrationTest and BankService. Do not try to read the whole project in one sitting.
