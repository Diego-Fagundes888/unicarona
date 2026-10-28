package com.unicarona.notification.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unicarona.auth.config.AuthSettings;
import com.unicarona.job.repository.OutboxDeliveryRepository.Claim;
import com.unicarona.notification.provider.EmailProvider;
import com.unicarona.notification.template.AuthEmailTemplate;
import com.unicarona.security.CryptoService;
import com.unicarona.privacy.service.PrivacyEvidenceCrypto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** No database lock is held during delivery. Consuming/superseding a token always wins validation. */
@Service
public class AuthEmailDelivery {
    private final JdbcTemplate sql;private final AuthSettings settings;private final CryptoService crypto;private final ObjectMapper json;private final EmailProvider provider;private final PrivacyEvidenceCrypto evidence;private final com.unicarona.privacy.service.PrivacyClosureMail closureMail;
    public AuthEmailDelivery(JdbcTemplate sql,AuthSettings settings,CryptoService crypto,ObjectMapper json,EmailProvider provider,PrivacyEvidenceCrypto evidence,com.unicarona.privacy.service.PrivacyClosureMail closureMail){this.sql=sql;this.settings=settings;this.crypto=crypto;this.json=json;this.provider=provider;this.evidence=evidence;this.closureMail=closureMail;}
    public boolean deliver(Claim job) throws Exception {
        var rows=sql.queryForList("""
            SELECT o.event_key,t.id,t.user_id,t.kind,t.used_at,t.invalidated_at,t.expires_at<=now() AS expired,u.deleted_at,u.email_verified_at,u.email,u.password_hash
            FROM outbox_jobs o LEFT JOIN auth_tokens t ON o.event_key='auth:'||t.id::text LEFT JOIN users u ON u.id=t.user_id WHERE o.id=?
            """,job.id());
        if(rows.isEmpty())return false;
        var row=rows.getFirst();
        String event=(String)row.get("event_key");
        if(job.payload().path("kind").asText().startsWith("closure_")&&!event.startsWith("closure-notice:"))return false;
        String destination=job.payload().path("toCiphertext").isTextual()?open(job,event,PrivacyEvidenceCrypto.Field.MAIL_TO,"toCiphertext"):job.payload().path("to").asText();
        if(((String)row.get("event_key")).startsWith("auth:")) {
            if(row.get("id")==null||row.get("used_at")!=null||row.get("invalidated_at")!=null||Boolean.TRUE.equals(row.get("expired"))||row.get("deleted_at")!=null
                ||("verify_email".equals(row.get("kind"))&&row.get("email_verified_at")!=null))return false;
            if("change_email".equals(row.get("kind"))){
                var pending=sql.queryForList("SELECT c.* FROM privacy_email_changes c JOIN users u ON u.id=c.user_id WHERE c.token_id=? AND c.status='pending' AND c.eligible AND c.expires_at>now() AND u.account_status IN ('active','deactivated')",row.get("id"));
                if(pending.isEmpty())return false;var change=pending.getFirst();
                if(!destination.equals(evidence.open(PrivacyEvidenceCrypto.Field.EMAIL_TARGET,(java.util.UUID)change.get("user_id"),(java.util.UUID)change.get("id"),(String)change.get("new_email_ciphertext")))||!crypto.hmac("email-change-credential:"+row.get("password_hash"),settings.pepper()).equals(change.get("credential_hmac"))||!crypto.hmac("registration-email:"+row.get("email"),settings.pepper()).equals(change.get("original_email_hmac")))return false;
            }else if(!destination.equals(row.get("email")))return false;
        }else if(((String)row.get("event_key")).startsWith("identity-notice:")){
            if(!java.util.Set.of("email_change_notice","email_changed_notice").contains(job.payload().path("kind").asText())||!job.payload().path("ownerId").asText().matches("[a-f0-9-]{36}")||!Boolean.TRUE.equals(sql.queryForObject("SELECT EXISTS(SELECT 1 FROM users u JOIN outbox_jobs o ON o.id=? WHERE u.id=?::uuid AND u.deleted_at IS NULL AND o.created_at>now()-interval '7 days')",Boolean.class,job.id(),job.payload().path("ownerId").asText())))return false;
        }else if(event.startsWith("closure-notice:")){
            if(!closureMail.allowed(job.id(),event,job.payload(),destination))return false;
        }
        var frozen=job.payload().path("contentCiphertext").isTextual()?json.readValue(open(job,event,PrivacyEvidenceCrypto.Field.MAIL_CONTENT,"contentCiphertext"),AuthEmailTemplate.Content.class):null;
        provider.send(new EmailProvider.Message(job.id(),destination,job.payload().path("kind").asText(),open(job,event,PrivacyEvidenceCrypto.Field.MAIL_URL,"urlCiphertext"),"",frozen));
        return true;
    }
    private String open(Claim job,String event,PrivacyEvidenceCrypto.Field field,String property){
        String value=job.payload().path(property).asText();
        if(!value.startsWith("pvc1."))return crypto.decrypt(value,settings.encryptionKey());
        return evidence.openMail(field,java.util.UUID.fromString(job.payload().path("ownerId").asText()),event,value);
    }
}
