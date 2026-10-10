CREATE TABLE account (
 id VARCHAR(36) PRIMARY KEY, iban VARCHAR(34) NOT NULL UNIQUE, owner_id VARCHAR(100) NOT NULL,
 name VARCHAR(140) NOT NULL, balance DECIMAL(19,2) NOT NULL DEFAULT 0,
 held DECIMAL(19,2) NOT NULL DEFAULT 0, version BIGINT NOT NULL DEFAULT 0,
 CHECK (balance >= 0), CHECK (held >= 0), CHECK (balance >= held)
);
CREATE TABLE mandate (
 id VARCHAR(36) PRIMARY KEY, debtor_id VARCHAR(36) NOT NULL REFERENCES account(id),
 creditor_id VARCHAR(36) NOT NULL REFERENCES account(id), active BOOLEAN NOT NULL,
 valid_until DATE NOT NULL, max_amount DECIMAL(19,2) NOT NULL CHECK(max_amount>0)
);
CREATE TABLE payment (
 id VARCHAR(36) PRIMARY KEY, actor VARCHAR(100) NOT NULL, idem_key VARCHAR(100) NOT NULL,
 fingerprint VARCHAR(64) NOT NULL, debtor_id VARCHAR(36) NOT NULL REFERENCES account(id),
 creditor_id VARCHAR(36) NOT NULL REFERENCES account(id), amount DECIMAL(19,2) NOT NULL CHECK(amount>0),
 scheme VARCHAR(20) NOT NULL, end_to_end_id VARCHAR(35) NOT NULL, mandate_id VARCHAR(36),
 status VARCHAR(24) NOT NULL, route VARCHAR(20), reason VARCHAR(40),
 created_at TIMESTAMP WITH TIME ZONE NOT NULL, deadline TIMESTAMP WITH TIME ZONE,
 settled_at TIMESTAMP WITH TIME ZONE, UNIQUE(actor,idem_key)
);
CREATE TABLE ledger (
 id VARCHAR(36) PRIMARY KEY, booking_id VARCHAR(120) NOT NULL, account_id VARCHAR(36),
 delta DECIMAL(19,2) NOT NULL, booked_at TIMESTAMP WITH TIME ZONE NOT NULL,
 UNIQUE(booking_id,account_id)
);
CREATE TABLE payment_event (
 id VARCHAR(36) PRIMARY KEY, payment_id VARCHAR(36) NOT NULL REFERENCES payment(id),
 seq BIGINT NOT NULL, status VARCHAR(24) NOT NULL, reason VARCHAR(40),
 occurred_at TIMESTAMP WITH TIME ZONE NOT NULL, UNIQUE(payment_id,seq)
);
CREATE TABLE outbox (
 id VARCHAR(36) PRIMARY KEY REFERENCES payment_event(id), payment_id VARCHAR(36) NOT NULL,
 payload TEXT NOT NULL, published BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE TABLE inbox (id VARCHAR(36) PRIMARY KEY, received_at TIMESTAMP WITH TIME ZONE NOT NULL);
CREATE TABLE payment_view (
 id VARCHAR(36) PRIMARY KEY, status VARCHAR(24) NOT NULL, event_seq BIGINT NOT NULL
);
CREATE TABLE consent (
 id VARCHAR(36) PRIMARY KEY, account_id VARCHAR(36) NOT NULL REFERENCES account(id),
 tpp VARCHAR(100) NOT NULL, expires_at TIMESTAMP WITH TIME ZONE NOT NULL, active BOOLEAN NOT NULL
);
CREATE INDEX ix_payment_deadline ON payment(status,deadline);
CREATE INDEX ix_outbox_pending ON outbox(published);
CREATE INDEX ix_ledger_account ON ledger(account_id,booked_at);
CREATE INDEX ix_account_owner ON account(owner_id);
