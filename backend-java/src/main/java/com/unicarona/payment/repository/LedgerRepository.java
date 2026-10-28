package com.unicarona.payment.repository;

import com.unicarona.payment.ledger.AccountKind;
import com.unicarona.payment.ledger.Amounts;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class LedgerRepository {
    private final JdbcTemplate sql;
    public LedgerRepository(JdbcTemplate sql) { this.sql=sql; }
    public void lockEvent(String key) {
        sql.execute("SET LOCAL lock_timeout='3s'");
        sql.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,1))",key);
    }
    public UUID account(UUID owner,AccountKind kind) {
        sql.update("INSERT INTO ledger_accounts(owner_id,kind) VALUES(?,?) ON CONFLICT DO NOTHING",owner,kind.value());
        return sql.queryForObject("SELECT id FROM ledger_accounts WHERE owner_id IS NOT DISTINCT FROM ? AND kind=?",UUID.class,owner,kind.value());
    }
    public void lockAccounts(Collection<UUID> accounts) {
        String placeholders=String.join(",",Collections.nCopies(accounts.size(),"?"));
        sql.queryForList("SELECT id FROM ledger_accounts WHERE id IN ("+placeholders+") ORDER BY id FOR UPDATE",accounts.toArray());
    }
    public long balance(UUID owner,AccountKind kind) {
        return Amounts.parse(sql.queryForObject("SELECT coalesce(sum(e.cents),0)::text FROM ledger_accounts a LEFT JOIN ledger_entries e ON e.account_id=a.id WHERE a.owner_id IS NOT DISTINCT FROM ? AND a.kind=?",String.class,owner,kind.value()));
    }
    public void entry(UUID transaction,UUID account,long cents) { sql.update("INSERT INTO ledger_entries(transaction_id,account_id,cents) VALUES(?,?,?)",transaction,account,cents); }
    public Map<String,Long> totals(UUID owner,boolean all) {
        String query="SELECT a.kind,coalesce(sum(e.cents),0)::text AS cents FROM ledger_accounts a LEFT JOIN ledger_entries e ON e.account_id=a.id"+(all?"":" WHERE a.owner_id=?")+" GROUP BY a.kind";
        var result=new HashMap<String,Long>();
        sql.query(query,rs -> { result.put(rs.getString("kind"),Amounts.parse(rs.getString("cents"))); },all?new Object[0]:new Object[]{owner});return result;
    }
    public record Parcels(long future,long blocked) {}
    public Parcels parcels(UUID owner) {
        return sql.queryForObject("SELECT coalesce(sum(cents) FILTER(WHERE status IN ('future','awaiting')),0)::text AS future,coalesce(sum(cents) FILTER(WHERE status='blocked'),0)::text AS blocked FROM parcels WHERE driver_id=? OR passenger_id=?",
            (r,n) -> new Parcels(Amounts.parse(r.getString("future")),Amounts.parse(r.getString("blocked"))),owner,owner);
    }
}
