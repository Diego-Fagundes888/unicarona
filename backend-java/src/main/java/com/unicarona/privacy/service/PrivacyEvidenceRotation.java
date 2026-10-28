package com.unicarona.privacy.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.unicarona.job.service.MaintenanceTask;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com.unicarona.privacy.service.PrivacyEvidenceCrypto.Field.*;

/** Small independent transactions; never changes evidence IDs, content or retention deadlines. */
@Service @Order(250)
public class PrivacyEvidenceRotation implements MaintenanceTask {
    public enum Source {
        REQUEST("privacy_requests","details_ciphertext",REQUEST_DETAILS),
        RESPONSE("privacy_request_responses","body_ciphertext",RESPONSE_BODY),
        RETAINED("privacy_retained_records","content_ciphertext",RETAINED_CONTENT),
        DECISION("privacy_account_decisions","reason_ciphertext",DECISION_REASON),
        EMAIL("privacy_email_changes","new_email_ciphertext",EMAIL_TARGET),
        HOLD("privacy_legal_holds","reason_ciphertext",HOLD_REASON),
        HOLD_EVENT("privacy_hold_events","body_ciphertext",HOLD_EVENT_REASON),
        CLOSURE_CONTACT("privacy_account_closures","completion_contact_ciphertext",PrivacyEvidenceCrypto.Field.CLOSURE_CONTACT),
        ACCEPTANCE("legal_acceptances","ip_ciphertext",ACCEPTANCE_IP),
        MAIL("outbox_jobs","payload",MAIL_CONTENT);
        final String table,column;final PrivacyEvidenceCrypto.Field field;
        Source(String table,String column,PrivacyEvidenceCrypto.Field field){this.table=table;this.column=column;this.field=field;}
        String from(){return table+" f"+(this==RESPONSE?" JOIN privacy_requests p ON p.id=f.request_id":this==MAIL?" LEFT JOIN auth_tokens token ON f.event_key='auth:'||token.id::text":"");}
        String owner(){return this==RESPONSE?"p.user_id":"f.user_id";}
    }
    public record Result(int inspected,int rotated,int failed){}
    private final JdbcTemplate sql;private final PrivacyEvidenceCrypto evidence;private final ObjectMapper json;private final TransactionTemplate transaction;private final PrivacyHoldRecovery recovery;
    private final EnumMap<Source,UUID> cursors=new EnumMap<>(Source.class);private int nextSource;private long nextAttempt;
    public PrivacyEvidenceRotation(JdbcTemplate sql,PrivacyEvidenceCrypto evidence,ObjectMapper json,PlatformTransactionManager manager,PrivacyHoldRecovery recovery){this.sql=sql;this.evidence=evidence;this.json=json;this.recovery=recovery;transaction=new TransactionTemplate(manager);transaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);transaction.setTimeout(5);}
    public synchronized Result runOnce(int batch,BooleanSupplier stopping){
        if(batch<1||batch>10000)throw new IllegalArgumentException("Invalid privacy rotation batch.");
        int inspected=0,rotated=0,failed=0,empty=0;
        while(inspected<batch&&empty<Source.values().length&&!stopping.getAsBoolean()){
            Source source=Source.values()[nextSource++%Source.values().length];if(nextSource>10000)nextSource=0;
            UUID after=cursors.get(source);List<UUID> candidates;
            try{candidates=candidates(source,after);}catch(Exception unavailable){failed++;empty++;continue;}
            if(candidates.isEmpty()){cursors.remove(source);empty++;continue;}
            empty=0;UUID id=candidates.getFirst();cursors.put(source,id);inspected++;
            try{if(Boolean.TRUE.equals(transaction.execute(status->rotate(source,id))))rotated++;}
            catch(Exception unavailable){failed++;}
        }
        return new Result(inspected,rotated,failed);
    }
    private List<UUID> candidates(Source source,UUID after){
        String needs=source==Source.MAIL?"f.type='email' AND coalesce(f.payload->>'ownerId',token.user_id::text) ~ '^[a-f0-9-]{36}$' AND (jsonb_exists(f.payload,'to') OR left(f.payload->>'toCiphertext',?)<>? OR left(f.payload->>'urlCiphertext',?)<>? OR left(f.payload->>'contentCiphertext',?)<>?)":"f."+source.column+" IS NOT NULL AND left(f."+source.column+",?)<>?";
        var args=new ArrayList<Object>();String prefix=evidence.currentPrefix();int count=source==Source.MAIL?3:1;for(int n=0;n<count;n++){args.add(prefix.length());args.add(prefix);}args.add(after);args.add(after);
        return sql.query("SELECT f.id FROM "+source.from()+" WHERE "+needs+" AND (?::uuid IS NULL OR f.id>?::uuid) ORDER BY f.id LIMIT 1",(r,n)->r.getObject(1,UUID.class),args.toArray());
    }
    private boolean rotate(Source source,UUID id){
        if(source==Source.HOLD||source==Source.HOLD_EVENT||source==Source.RETAINED)PrivacyRetentionLock.acquire(sql);
        sql.execute("SET LOCAL lock_timeout='1s'");sql.execute("SET LOCAL unicarona.privacy_key_rotation='on'");
        String owner=source==Source.MAIL?"coalesce(f.payload->>'ownerId',token.user_id::text)":source.owner();
        var rows=sql.queryForList("SELECT f.*,"+owner+" AS crypto_owner FROM "+source.from()+" WHERE f.id=? FOR UPDATE OF f SKIP LOCKED",id);if(rows.isEmpty())return false;var row=rows.getFirst();
        UUID user=UUID.fromString(row.get("crypto_owner").toString());
        if(source==Source.MAIL)return rotateMail(id,user,(String)row.get("event_key"),row.get("payload").toString());
        String value=(String)row.get(source.column);if(value==null||evidence.isCurrent(value))return false;
        String clear=evidence.open(source.field,user,id,value);
        if(source==Source.RETAINED&&"identity_change".equals(row.get("category"))){
            try{var tree=json.readTree(clear);if(!(tree instanceof ObjectNode object))throw new IllegalArgumentException();
                var nested=object.remove("new_email_ciphertext");if(nested!=null&&!nested.isNull()){
                    if(!nested.isTextual()||row.get("resource_id")==null)throw new IllegalArgumentException();
                    object.put("new_email",evidence.open(EMAIL_TARGET,user,(UUID)row.get("resource_id"),nested.asText()));
                }
                clear=json.writeValueAsString(object);
            }catch(Exception invalid){throw new IllegalStateException("Retained identity evidence cannot be rotated.");}
        }
        sql.update("UPDATE "+source.table+" SET "+source.column+"=? WHERE id=?",evidence.seal(source.field,user,id,clear),id);if(source==Source.HOLD||source==Source.HOLD_EVENT||source==Source.RETAINED)recovery.stage();return true;
    }
    private boolean rotateMail(UUID id,UUID owner,String event,String payload){
        try{
            ObjectNode body=(ObjectNode)json.readTree(payload);boolean changed=false;
            if(!body.has("ownerId")){body.put("ownerId",owner.toString());changed=true;}
            if(body.has("to")){if(!body.path("to").isTextual()||body.path("to").asText().isBlank())throw new IllegalArgumentException();body.put("toCiphertext",evidence.sealMail(MAIL_TO,owner,event,body.remove("to").asText()));changed=true;}
            for(var entry:Map.of("toCiphertext",MAIL_TO,"urlCiphertext",MAIL_URL,"contentCiphertext",MAIL_CONTENT).entrySet()){
                var value=body.get(entry.getKey());if(value==null&&entry.getKey().equals("contentCiphertext"))continue;if(value==null||!value.isTextual())throw new IllegalArgumentException();
                if(!evidence.isCurrent(value.asText())){body.put(entry.getKey(),evidence.sealMail(entry.getValue(),owner,event,evidence.openMail(entry.getValue(),owner,event,value.asText())));changed=true;}
            }
            if(changed)sql.update("UPDATE outbox_jobs SET payload=?::jsonb WHERE id=?",json.writeValueAsString(body),id);return changed;
        }catch(Exception invalid){throw new IllegalStateException("Privacy mail cannot be rotated.");}
    }
    @Override public synchronized void run(int batch,BooleanSupplier stopping){
        if(stopping.getAsBoolean()||(nextAttempt!=0&&System.nanoTime()-nextAttempt<0))return;
        nextAttempt=System.nanoTime()+TimeUnit.MINUTES.toNanos(1);
        try{var result=runOnce(batch,stopping);if(result.failed()>0)LoggerFactory.getLogger(getClass()).warn("event=privacy_evidence_rotation_pending code=EVIDENCE_KEY_RETRY count={}",result.failed());}
        catch(Exception unavailable){LoggerFactory.getLogger(getClass()).warn("event=privacy_evidence_rotation_pending code=EVIDENCE_KEY_RETRY");}
    }
}
