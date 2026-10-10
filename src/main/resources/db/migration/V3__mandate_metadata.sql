-- No fabricated signing dates or creditor identifiers for existing laboratory mandates.
alter table mandate add column mandate_reference varchar(35);
alter table mandate add column creditor_identifier varchar(35);
alter table mandate add column signature_date date;
alter table mandate add column sequence_type varchar(4);
