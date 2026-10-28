package com.unicarona.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unicarona.privacy.service.PrivacyEvidenceCrypto;
import java.util.UUID;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/** Read only synthetic test messages by the token-owner relation, never by an exposed recipient. */
public final class EncryptedMailFixture {
    private EncryptedMailFixture(){}
    public static String url(ApplicationContext app,JdbcTemplate sql,UUID owner,String kind)throws Exception {
        var row=sql.queryForMap("SELECT o.event_key,o.payload::text FROM outbox_jobs o JOIN auth_tokens t ON o.event_key='auth:'||t.id::text WHERE t.user_id=? AND t.kind=? AND t.used_at IS NULL AND t.invalidated_at IS NULL",owner,kind);
        var payload=new ObjectMapper().readTree((String)row.get("payload"));
        return app.getBean(PrivacyEvidenceCrypto.class).openMail(PrivacyEvidenceCrypto.Field.MAIL_URL,owner,(String)row.get("event_key"),payload.path("urlCiphertext").asText());
    }
    public static UUID job(JdbcTemplate sql,UUID owner){return sql.queryForObject("SELECT o.id FROM outbox_jobs o JOIN auth_tokens t ON o.event_key='auth:'||t.id::text WHERE t.user_id=? AND t.kind='verify_email' AND t.used_at IS NULL AND t.invalidated_at IS NULL",UUID.class,owner);}
}
