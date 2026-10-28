package com.unicarona.privacy.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Purpose metadata only. Never reconstructs a balance or decides an external payment. */
@Service
public class PrivacyContractIdentityPurpose {
    public record Fact(String kind,UUID id,String sha256){@Override public String toString(){return "ContractIdentityFact[redacted]";}}
    public record Purpose(int format,int revision,String policy,boolean pending,
        @JsonInclude(JsonInclude.Include.ALWAYS) Instant lastFact,List<Fact> facts){@Override public String toString(){return "ContractIdentityPurpose[redacted]";}}
    private static final String POLICY="contract-identity-v1";
    private static final Set<String> KINDS=Set.of("ledger_entry","occurrence","webhook","transfer","card_command","pix_command","debt_payment","privacy_request");
    private final JdbcTemplate sql;private final ObjectMapper json;private final ObjectReader typed;private UUID afterId;
    public PrivacyContractIdentityPurpose(JdbcTemplate sql,ObjectMapper json){this.sql=sql;this.json=json;typed=json.copy().disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).readerFor(Purpose.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).without(DeserializationFeature.ACCEPT_FLOAT_AS_INT);}
    public static boolean applies(String category,boolean holdOnly){return !holdOnly&&Set.of("contract_identity","vehicle_identity").contains(category);}
    public void validate(Purpose value)throws IOException {
        if(value==null)return;
        if(value.format()!=1||value.revision()<1||!POLICY.equals(value.policy())||value.facts()==null||value.facts().size()>10000)throw new IOException("Contract identity purpose is invalid.");
        var ids=new HashSet<String>();for(var fact:value.facts())if(fact==null||fact.kind()==null||!KINDS.contains(fact.kind())||fact.id()==null||fact.sha256()==null||!fact.sha256().matches("[a-f0-9]{64}")||!ids.add(fact.kind()+":"+fact.id()))throw new IOException("Contract identity fact boundary is invalid.");
    }
    public Purpose read(String raw){
        if(raw==null)return null;
        try {Purpose value=typed.readValue(raw);validate(value);return value;}
        catch(Exception invalid){throw new IllegalStateException("Contract identity purpose cannot be authenticated against its schema.");}
    }
    public String encode(Purpose value){try{return value==null?null:json.writeValueAsString(value);}catch(Exception invalid){throw new IllegalStateException("Contract identity purpose cannot be encoded.");}}
    public boolean factsPresent(UUID owner,String category,UUID resource,Purpose value){return value==null||Boolean.TRUE.equals(sql.queryForObject("SELECT privacy_identity_facts_present(?,?,?,?::jsonb)",Boolean.class,owner,category,resource,encodeFacts(value)));}
    private String encodeFacts(Purpose value){try{return json.writeValueAsString(value.facts());}catch(Exception invalid){throw new IllegalStateException("Contract identity facts cannot be encoded.");}}
    public boolean required(UUID owner,String category,UUID resource,Purpose value){return (value!=null&&(value.pending()||!factsPresent(owner,category,resource,value)))||Boolean.TRUE.equals(sql.queryForObject("SELECT privacy_identity_purpose_open(?,?,?)",Boolean.class,owner,category,resource));}
    private boolean refresh(Map<String,Object> row){
        UUID owner=(UUID)row.get("user_id"),resource=(UUID)row.get("resource_id");String category=(String)row.get("category");Purpose previous=read(row.get("financial_purpose")==null?null:row.get("financial_purpose").toString());
        // Missing previously recorded business facts is not proof of settlement.
        // In particular, an old restored balance cannot silently end the purpose.
        if(previous!=null&&!factsPresent(owner,category,resource,previous))return false;
        var sample=sql.queryForMap("WITH facts AS MATERIALIZED (SELECT * FROM privacy_identity_purpose_facts(?,?,?) ORDER BY kind,id LIMIT 10001) SELECT privacy_identity_purpose_open(?,?,?) AS pending,(SELECT coalesce(jsonb_agg(jsonb_build_object('kind',kind,'id',id,'sha256',sha256) ORDER BY kind,id),'[]')::text FROM facts) AS facts,(SELECT max(happened) FROM facts) AS last_fact",owner,category,resource,owner,category,resource);
        List<Fact> facts;try{facts=json.readerForListOf(Fact.class).readValue((String)sample.get("facts"));}catch(Exception invalid){throw new IllegalStateException("Contract identity source facts cannot be read.");}
        Instant last=sample.get("last_fact")==null?null:((Timestamp)sample.get("last_fact")).toInstant();if(previous!=null&&previous.lastFact()!=null&&(last==null||last.isBefore(previous.lastFact())))last=previous.lastFact();
        boolean pending=Boolean.TRUE.equals(sample.get("pending"));Instant until=((Timestamp)row.get("delete_after")).toInstant();if(last!=null){Instant defence=last.atOffset(ZoneOffset.UTC).plusYears(5).toInstant();if(defence.isAfter(until))until=defence;}
        if(previous!=null&&previous.pending()==pending&&Objects.equals(previous.lastFact(),last)&&previous.facts().equals(facts)&&until.equals(((Timestamp)row.get("delete_after")).toInstant()))return false;
        Purpose next=new Purpose(1,previous==null?1:Math.incrementExact(previous.revision()),POLICY,pending,last,List.copyOf(facts));try{validate(next);}catch(IOException invalid){throw new IllegalStateException("Contract identity fact capacity or schema needs review.");}
        sql.update("UPDATE privacy_retained_records SET financial_purpose=?::jsonb,delete_after=? WHERE id=?",encode(next),Timestamp.from(until),row.get("id"));
        return previous!=null;
    }
    /** Snapshot preparation records observed purpose without deleting another person's data. */
    public void refreshForSnapshot(){
        var rows=sql.queryForList("SELECT * FROM privacy_retained_records WHERE NOT hold_only AND category IN ('contract_identity','vehicle_identity') ORDER BY id LIMIT 10001");if(rows.size()>10000)throw new IllegalStateException("Contract identity evidence exceeds the control capacity.");for(var row:rows)refresh(row);
    }
    /** Caller already holds the global checkpoint lock. Only this path expires autonomous copies. */
    public synchronized void expireBatch(int batch){
        var rows=sql.queryForList("SELECT * FROM privacy_retained_records WHERE NOT hold_only AND category IN ('contract_identity','vehicle_identity') AND (?::uuid IS NULL OR id>?::uuid) ORDER BY id LIMIT ?",afterId,afterId,batch);
        if(rows.isEmpty()&&afterId!=null){afterId=null;rows=sql.queryForList("SELECT * FROM privacy_retained_records WHERE NOT hold_only AND category IN ('contract_identity','vehicle_identity') ORDER BY id LIMIT ?",batch);}if(rows.isEmpty())return;
        for(var row:rows){afterId=(UUID)row.get("id");UUID owner=(UUID)row.get("user_id");if(sql.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE SKIP LOCKED",owner).isEmpty())continue;if(refresh(row))continue;
            sql.update("DELETE FROM privacy_retained_records r WHERE r.id=? AND r.delete_after<=now() AND NOT privacy_identity_evidence_required(r) AND NOT EXISTS(SELECT 1 FROM privacy_legal_holds h WHERE h.user_id=r.user_id AND h.released_at IS NULL AND h.expires_at>now() AND (h.category=r.category OR h.category='financial') AND (h.resource_id IS NULL OR h.resource_id=r.resource_id))",row.get("id"));
        }
    }
    public void expireBeforeReadiness(){
        var rows=sql.queryForList("SELECT * FROM privacy_retained_records WHERE NOT hold_only AND category IN ('contract_identity','vehicle_identity') ORDER BY user_id,id LIMIT 10001");if(rows.size()>10000)throw new IllegalStateException("Contract identity evidence exceeds the control capacity.");
        for(var row:rows){sql.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",row.get("user_id"));if(refresh(row))continue;
            sql.update("DELETE FROM privacy_retained_records r WHERE r.id=? AND r.delete_after<=now() AND NOT privacy_identity_evidence_required(r) AND NOT EXISTS(SELECT 1 FROM privacy_legal_holds h WHERE h.user_id=r.user_id AND h.released_at IS NULL AND h.expires_at>now() AND (h.category=r.category OR h.category='financial') AND (h.resource_id IS NULL OR h.resource_id=r.resource_id))",row.get("id"));
        }
    }
    public boolean successor(Purpose old,Purpose next){
        if(old==null)return true;if(next==null||next.revision()<old.revision())return false;if(next.revision()==old.revision())return old.equals(next);
        return next.facts().containsAll(old.facts())&&(old.lastFact()==null||(next.lastFact()!=null&&!next.lastFact().isBefore(old.lastFact())));
    }
}
