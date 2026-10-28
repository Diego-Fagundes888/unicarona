ALTER TABLE universities ADD COLUMN time_zone text NOT NULL DEFAULT 'America/Sao_Paulo';
ALTER TABLE universities ADD CONSTRAINT university_time_zone CHECK(time_zone ~ '^[A-Za-z_]+/[A-Za-z_]+$');
CREATE FUNCTION nonnegative_ledger_account() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE k text; total bigint;
BEGIN
 SELECT kind INTO k FROM ledger_accounts WHERE id=NEW.account_id FOR UPDATE;
 IF k<>'provider_clearing' THEN
  SELECT coalesce(sum(cents),0) INTO total FROM ledger_entries WHERE account_id=NEW.account_id;
  IF total<0 THEN RAISE EXCEPTION 'Negative ledger account' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER ledger_nonnegative AFTER INSERT ON ledger_entries
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION nonnegative_ledger_account();
