package com.unicarona.shared.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AuditRepository {
    private final JdbcTemplate sql;
    private final ObjectMapper json;
    public AuditRepository(JdbcTemplate sql,ObjectMapper json) { this.sql=sql; this.json=json; }
    public void record(UUID actor,String action,String resourceType,UUID resource,Map<String,?> metadata) {
        try { sql.update("INSERT INTO audit_logs(actor_id,action,resource_type,resource_id,metadata) VALUES(?,?,?,?,?::jsonb)",actor,action,resourceType,resource,json.writeValueAsString(metadata)); }
        catch(JsonProcessingException e) { throw new IllegalStateException("Could not serialize audit metadata."); }
    }
}
