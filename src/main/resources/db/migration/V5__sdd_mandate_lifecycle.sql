ALTER TABLE mandate ADD COLUMN last_presented_date DATE;
ALTER TABLE payment ADD COLUMN collection_sequence VARCHAR(4);
ALTER TABLE payment ADD COLUMN collection_date DATE;
UPDATE payment SET collection_sequence=(SELECT sequence_type FROM mandate WHERE mandate.id=payment.mandate_id), collection_date=CAST(created_at AS DATE) WHERE scheme='SDD_CORE';
UPDATE mandate SET last_presented_date=(SELECT MAX(CAST(created_at AS DATE)) FROM payment WHERE payment.mandate_id=mandate.id);
CREATE INDEX payment_mandate_status_idx ON payment(mandate_id,status);
ALTER TABLE payment ADD CONSTRAINT payment_collection_sequence CHECK (collection_sequence IS NULL OR collection_sequence IN ('FRST','RCUR','OOFF','FNAL'));
