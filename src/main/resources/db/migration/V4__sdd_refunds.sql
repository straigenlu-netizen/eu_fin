-- Distinguish a bank receivable from a debit of a customer's account.
alter table ledger add column bank_account varchar(30);
alter table ledger add column value_date date;
update ledger set bank_account='TREASURY' where account_id is null;
update ledger set value_date=cast(booked_at as date);
alter table ledger add constraint ck_ledger_party check (
 (account_id is not null and bank_account is null) or
 (account_id is null and bank_account is not null)
);
alter table ledger alter column value_date set not null;

create table sdd_refund (
 payment_id varchar(36) primary key references payment(id),
 creditor_id varchar(36) not null references account(id),
 amount decimal(19,2) not null check(amount>0),
 recovered_amount decimal(19,2) not null default 0,
 kind varchar(20) not null check(kind in ('EIGHT_WEEK','UNAUTHORISED')),
 debit_date date not null, created_at timestamp with time zone not null,
 check(recovered_amount>=0 and recovered_amount<=amount)
);
create table sdd_recovery (
 id varchar(36) primary key,
 payment_id varchar(36) not null references sdd_refund(payment_id),
 actor varchar(100) not null, idem_key varchar(100) not null,
 amount decimal(19,2) not null check(amount>0), created_at timestamp with time zone not null,
 unique(actor,idem_key)
);
create table sdd_dispute (
 id varchar(36) primary key, payment_id varchar(36) not null unique references sdd_refund(payment_id),
 claimant varchar(100) not null, allegation varchar(30) not null,
 status varchar(40) not null, created_at timestamp with time zone not null,
 reviewed_by varchar(100), reviewed_at timestamp with time zone, evidence_hash varchar(64),
 check(allegation in ('NO_MANDATE','REVOKED_MANDATE','OTHER_UNAUTHORISED')),
 check(status in ('REFUNDED_UNDER_REVIEW','CONFIRMED_UNAUTHORISED','AUTHORISATION_EVIDENCE_FOUND'))
);
create index ix_sdd_dispute_status on sdd_dispute(status,created_at);
