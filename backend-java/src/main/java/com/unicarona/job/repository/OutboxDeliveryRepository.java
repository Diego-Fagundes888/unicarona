package com.unicarona.job.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class OutboxDeliveryRepository {
    private final JdbcTemplate sql;
    private final ObjectMapper json;
    public OutboxDeliveryRepository(JdbcTemplate sql,ObjectMapper json) { this.sql=sql;this.json=json; }
    public record Claim(UUID id,String type,JsonNode payload,int attempt) {
        @Override public String toString() { return "OutboxClaim[redacted]"; }
    }
    @Transactional public Optional<Claim> claim() {
        var rows=sql.query("""
            SELECT id,type,payload::text,attempts FROM outbox_jobs
            WHERE (status='pending' AND available_at<=now()) OR (status='processing' AND leased_until<now())
            ORDER BY available_at,id FOR UPDATE SKIP LOCKED LIMIT 1
            """,(row,n) -> {
                try { return new Claim(row.getObject("id",UUID.class),row.getString("type"),json.readTree(row.getString("payload")),Math.addExact(row.getInt("attempts"),1)); }
                catch(com.fasterxml.jackson.core.JsonProcessingException error) { throw new java.sql.SQLException("Invalid outbox payload."); }
            });
        if(rows.isEmpty()) return Optional.empty();var job=rows.getFirst();
        sql.update("UPDATE outbox_jobs SET status='processing',attempts=attempts+1,leased_until=now()+interval '60 seconds' WHERE id=?",job.id());
        return Optional.of(job);
    }
    @Transactional public void done(Claim job) {
        done(job,true);
    }
    @Transactional public void done(Claim job,boolean emailSent) {
        int changed=sql.update("UPDATE outbox_jobs SET status='done',completed_at=now(),leased_until=NULL,payload=CASE WHEN type='email' THEN '{}'::jsonb ELSE payload END WHERE id=? AND status='processing' AND attempts=?",job.id(),job.attempt());
        if(changed==1&&"email".equals(job.type())){
            if(emailSent)sql.update("UPDATE auth_tokens SET sent_at=coalesce(sent_at,now()) FROM outbox_jobs o WHERE o.id=? AND o.event_key='auth:'||auth_tokens.id::text",job.id());
            emailAudit(job,emailSent?"email_delivery_completed":"email_delivery_skipped");
        }
    }
    @Transactional public void failed(Claim job,String code) {
        int delay=job.attempt()>=12?3600:1<<job.attempt();
        int changed=sql.update("""
            UPDATE outbox_jobs SET status=?,leased_until=NULL,available_at=now()+?*interval '1 second',last_error_code=?
            WHERE id=? AND status='processing' AND attempts=?
            """,job.attempt()>=8?"dead":"pending",delay,code,job.id(),job.attempt());
        if(changed==1&&"email".equals(job.type())){
            sql.update("UPDATE auth_tokens SET last_delivery_error_at=now() FROM outbox_jobs o WHERE o.id=? AND o.event_key='auth:'||auth_tokens.id::text",job.id());
            emailAudit(job,"email_delivery_failed");
        }
    }
    private void emailAudit(Claim job,String action){
        if(job.payload().path("ownerId").asText().matches("[a-f0-9-]{36}")){
            sql.update("INSERT INTO audit_logs(actor_id,action,resource_type,resource_id,metadata) VALUES(?::uuid,?,'email_job',?,jsonb_build_object('attempt',?,'kind',?))",job.payload().path("ownerId").asText(),action,job.id(),job.attempt(),job.payload().path("kind").asText());return;
        }
        sql.update("INSERT INTO audit_logs(actor_id,action,resource_type,resource_id,metadata) SELECT t.user_id,?,'auth_token',t.id,jsonb_build_object('attempt',?) FROM auth_tokens t JOIN outbox_jobs o ON o.event_key='auth:'||t.id::text WHERE o.id=?",action,job.attempt(),job.id());
    }
}
