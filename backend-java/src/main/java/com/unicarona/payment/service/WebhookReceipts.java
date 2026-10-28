package com.unicarona.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unicarona.shared.ApiException;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class WebhookReceipts {
    private final JdbcTemplate sql;
    private final ObjectMapper json;
    public WebhookReceipts(JdbcTemplate sql,ObjectMapper json) { this.sql=sql;this.json=json; }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public UUID record(String provider,String event,Object payload) {
        try {
            byte[] encoded=json.writeValueAsBytes(payload);String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded));
            sql.execute("SET LOCAL lock_timeout='3s'");
            sql.update("INSERT INTO webhook_events(provider,event_id,payload_hash,payload) VALUES(?,?,?,?::jsonb) ON CONFLICT DO NOTHING",provider,event,hash,new String(encoded,java.nio.charset.StandardCharsets.UTF_8));
            var receipt=sql.queryForMap("SELECT id,payload_hash FROM webhook_events WHERE provider=? AND event_id=?",provider,event);
            ApiException.require(hash.equals(receipt.get("payload_hash")),"WEBHOOK_CONFLICT","Evento repetido com conteúdo diferente.",409);return (UUID)receipt.get("id");
        } catch(ApiException error) { throw error; }
        catch(java.io.IOException | java.security.NoSuchAlgorithmException error) { throw new IllegalStateException("Cannot encode webhook receipt."); }
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW) public void failed(UUID id,RuntimeException error) {
        sql.update("UPDATE webhook_events SET status='failed',last_error_code=? WHERE id=? AND status<>'processed'",error instanceof ApiException application?application.code():"PROCESSING_ERROR",id);
    }
    @Transactional(propagation=Propagation.MANDATORY) public void processed(UUID id) {
        sql.update("UPDATE webhook_events SET status='processed',processed_at=now(),last_error_code=NULL WHERE id=?",id);
    }
}
