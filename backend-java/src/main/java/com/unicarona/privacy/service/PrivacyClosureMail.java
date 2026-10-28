package com.unicarona.privacy.service;

import com.fasterxml.jackson.databind.*;
import com.unicarona.notification.config.EmailSettings;
import com.unicarona.notification.provider.EmailProvider;
import com.unicarona.notification.template.AuthEmailTemplate;
import com.unicarona.privacy.config.PrivacySettings;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Essential notices use the existing encrypted transactional outbox, with lifecycle checks at delivery. */
@Service
public class PrivacyClosureMail {
    private final JdbcTemplate sql;private final ObjectMapper json;private final PrivacyEvidenceCrypto evidence;private final EmailSettings email;private final PrivacySettings privacy;
    public PrivacyClosureMail(JdbcTemplate sql,ObjectMapper json,PrivacyEvidenceCrypto evidence,EmailSettings email,PrivacySettings privacy){this.sql=sql;this.json=json;this.evidence=evidence;this.email=email;this.privacy=privacy;}
    @Transactional(propagation=Propagation.MANDATORY) public void current(UUID owner,UUID closure,String phase){
        if(!Set.of("requested","platform","cancelled").contains(phase))throw new IllegalArgumentException("Invalid closure notice phase.");
        var rows=sql.queryForList("SELECT u.email,c.erase_after FROM users u JOIN privacy_account_closures c ON c.user_id=u.id WHERE u.id=? AND c.id=? AND u.deleted_at IS NULL AND u.email_verified_at IS NOT NULL AND c.notice_suppressed_at IS NULL",owner,closure);
        if(rows.isEmpty())return;var row=rows.getFirst();enqueue(owner,closure,phase,(String)row.get("email"),((Timestamp)row.get("erase_after")).toInstant());
    }
    @Transactional(propagation=Propagation.MANDATORY) public void capture(UUID owner,UUID closure){
        var rows=sql.queryForList("SELECT u.email FROM users u JOIN privacy_account_closures c ON c.user_id=u.id WHERE u.id=? AND c.id=? AND u.deleted_at IS NULL AND u.email_verified_at IS NOT NULL AND c.notice_suppressed_at IS NULL AND c.notice_contact_expires_at IS NULL",owner,closure);
        if(rows.isEmpty())return;
        sql.update("UPDATE privacy_account_closures SET completion_contact_ciphertext=?,notice_contact_expires_at=now()+interval '90 days' WHERE id=? AND notice_contact_expires_at IS NULL",evidence.seal(PrivacyEvidenceCrypto.Field.CLOSURE_CONTACT,owner,closure,(String)rows.getFirst().get("email")),closure);
    }
    @Transactional(propagation=Propagation.MANDATORY) public void complete(UUID owner,UUID closure){
        var rows=sql.queryForList("SELECT completion_contact_ciphertext FROM privacy_account_closures WHERE id=? AND user_id=? AND status='completed' AND completion_contact_ciphertext IS NOT NULL AND notice_suppressed_at IS NULL AND completion_notice_enqueued_at IS NULL AND notice_contact_expires_at>now()",closure,owner);
        if(rows.isEmpty()){sql.update("UPDATE privacy_account_closures SET completion_contact_ciphertext=NULL WHERE id=? AND (notice_contact_expires_at<=now() OR notice_suppressed_at IS NOT NULL)",closure);return;}
        String destination=evidence.open(PrivacyEvidenceCrypto.Field.CLOSURE_CONTACT,owner,closure,(String)rows.getFirst().get("completion_contact_ciphertext"));enqueue(owner,closure,"completed",destination,null);
        sql.update("UPDATE privacy_account_closures SET completion_contact_ciphertext=NULL,completion_notice_enqueued_at=now() WHERE id=?",closure);
    }
    public List<UUID> due(int batch){return sql.query("SELECT id FROM privacy_account_closures WHERE status='completed' AND completion_contact_ciphertext IS NOT NULL AND completion_notice_enqueued_at IS NULL AND notice_suppressed_at IS NULL AND notice_contact_expires_at>now() AND notice_retry_after<=now() ORDER BY notice_retry_after,id LIMIT ?",(r,n)->r.getObject(1,UUID.class),batch);}
    @Transactional public void enqueueCompleted(UUID closure){
        sql.execute("SET LOCAL lock_timeout='3s'");var rows=sql.queryForList("SELECT user_id FROM privacy_account_closures WHERE id=? FOR UPDATE SKIP LOCKED",closure);if(!rows.isEmpty())complete((UUID)rows.getFirst().get("user_id"),closure);
    }
    @Transactional public void failed(UUID closure){sql.update("UPDATE privacy_account_closures SET notice_retry_after=now()+interval '5 minutes' WHERE id=? AND completion_notice_enqueued_at IS NULL",closure);}
    @Transactional(propagation=Propagation.MANDATORY) public void suppressRestored(UUID owner){
        sql.update("UPDATE privacy_account_closures SET completion_contact_ciphertext=NULL,notice_suppressed_at=coalesce(notice_suppressed_at,now()) WHERE user_id=?",owner);
        sql.update("UPDATE outbox_jobs SET payload='{}',status=CASE WHEN status IN ('pending','processing') THEN 'done' ELSE status END,completed_at=coalesce(completed_at,now()),leased_until=NULL WHERE type='email' AND event_key LIKE 'closure-notice:%' AND payload->>'ownerId'=?",owner.toString());
    }
    private void enqueue(UUID owner,UUID closure,String phase,String destination,Instant eraseAfter){
        String event="closure-notice:"+closure+":"+phase,kind="closure_"+phase,url="completed".equals(phase)?email.privacyUrl():email.frontendOrigin()+"/login.html";
        try{
            Instant registered=sql.queryForObject("SELECT requested_at FROM privacy_account_closures WHERE id=? AND user_id=?",Timestamp.class,closure,owner).toInstant();
            var content=new AuthEmailTemplate(email).renderClosure(new EmailProvider.Message(UUID.randomUUID(),destination,kind,url),eraseAfter,privacy.contactEmail(),registered);
            var payload=Map.of("ownerId",owner.toString(),"closureId",closure.toString(),"kind",kind,"toCiphertext",evidence.sealMail(PrivacyEvidenceCrypto.Field.MAIL_TO,owner,event,destination),"urlCiphertext",evidence.sealMail(PrivacyEvidenceCrypto.Field.MAIL_URL,owner,event,url),"contentCiphertext",evidence.sealMail(PrivacyEvidenceCrypto.Field.MAIL_CONTENT,owner,event,json.writeValueAsString(content)));
            sql.update("INSERT INTO outbox_jobs(type,event_key,payload) VALUES('email',?,?::jsonb) ON CONFLICT(event_key) DO NOTHING",event,json.writeValueAsString(payload));
        }catch(com.fasterxml.jackson.core.JsonProcessingException invalid){throw new IllegalStateException("Could not protect closure notice.");}
    }
    public boolean allowed(UUID job,String event,JsonNode payload,String destination){
        String owner=payload.path("ownerId").asText(),closure=payload.path("closureId").asText(),kind=payload.path("kind").asText();
        if(!owner.matches("[a-f0-9-]{36}")||!closure.matches("[a-f0-9-]{36}")||!Set.of("closure_requested","closure_platform","closure_cancelled","closure_completed").contains(kind))return false;
        String phase=kind.substring("closure_".length());if(!event.equals("closure-notice:"+closure+":"+phase))return false;
        var rows=sql.queryForList("SELECT c.status,c.initiated_by,c.notice_suppressed_at,c.completion_notice_enqueued_at,c.notice_contact_expires_at,NOT EXISTS(SELECT 1 FROM privacy_account_closures newer WHERE newer.user_id=c.user_id AND (newer.requested_at,newer.id)>(c.requested_at,c.id)) AS is_latest,u.email,u.email_verified_at,u.deleted_at,o.created_at FROM privacy_account_closures c JOIN users u ON u.id=c.user_id JOIN outbox_jobs o ON o.id=? AND o.event_key=? WHERE c.id=?::uuid AND c.user_id=?::uuid",job,event,closure,owner);
        if(rows.isEmpty())return false;var row=rows.getFirst();if(row.get("notice_suppressed_at")!=null)return false;
        Instant at=sql.queryForObject("SELECT now()",Timestamp.class).toInstant();
        if("completed".equals(phase))return "completed".equals(row.get("status"))&&row.get("deleted_at")!=null&&row.get("completion_notice_enqueued_at")!=null&&row.get("notice_contact_expires_at")!=null&&((Timestamp)row.get("notice_contact_expires_at")).toInstant().isAfter(at);
        if(!Boolean.TRUE.equals(row.get("is_latest"))||row.get("deleted_at")!=null||row.get("email_verified_at")==null||!destination.equals(row.get("email"))||!((Timestamp)row.get("created_at")).toInstant().plus(java.time.Duration.ofDays(7)).isAfter(at))return false;
        return "cancelled".equals(phase)?"cancelled".equals(row.get("status")):Set.of("scheduled","pending_obligations").contains(row.get("status"))&&("platform".equals(phase)?"platform":"user").equals(row.get("initiated_by"));
    }
}
