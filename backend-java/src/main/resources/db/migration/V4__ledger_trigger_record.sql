-- A CASE expression resolves both record fields during compilation. Separate
-- statements permit this trigger to work on either table's row shape.
CREATE OR REPLACE FUNCTION balanced_ledger_transaction() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE target uuid; total bigint; n bigint;
BEGIN
 IF TG_TABLE_NAME='ledger_transactions' THEN target:=NEW.id;
 ELSE target:=NEW.transaction_id; END IF;
 SELECT coalesce(sum(cents),0),count(*) INTO total,n FROM ledger_entries WHERE transaction_id=target;
 IF total<>0 OR n<2 THEN RAISE EXCEPTION 'Unbalanced ledger' USING ERRCODE='23514'; END IF;
 RETURN NULL;
END $$;
