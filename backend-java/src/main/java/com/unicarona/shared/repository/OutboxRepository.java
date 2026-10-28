package com.unicarona.shared.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

@Repository
public class OutboxRepository {
    private final JdbcTemplate sql;
    private final ObjectMapper json;
    public OutboxRepository(JdbcTemplate sql,ObjectMapper json) { this.sql=sql;this.json=json; }
    @Transactional(propagation=Propagation.MANDATORY) public void enqueue(String type,String key,Map<String,?> payload) {
        try { sql.update("INSERT INTO outbox_jobs(type,event_key,payload) VALUES(?,?,?::jsonb) ON CONFLICT(event_key) DO NOTHING",type,key,json.writeValueAsString(payload)); }
        catch(com.fasterxml.jackson.core.JsonProcessingException error) { throw new IllegalStateException("Cannot encode outbox job."); }
    }
}
