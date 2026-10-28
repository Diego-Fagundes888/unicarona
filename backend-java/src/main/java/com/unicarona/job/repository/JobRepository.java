package com.unicarona.job.repository;

import com.unicarona.job.dto.JobDto;
import com.unicarona.shared.ApiDates;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

/** Operational reads deliberately never select the private payload or event key. */
@Repository
public class JobRepository {
    private static final Set<String> SAFE_ERRORS=Set.of("Error","TypeError","AbortError","TimeoutError","AppError");
    private final JdbcTemplate sql;
    public JobRepository(JdbcTemplate sql) { this.sql=sql; }
    public List<JobDto.Item> list(String status,int limit,int offset) {
        return sql.query("""
            SELECT id,type,status,attempts,available_at,leased_until,created_at,completed_at,last_error_code
            FROM outbox_jobs WHERE status=? ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?
            """,(row,n) -> item(row),status,limit,offset);
    }
    public JobDto.Summary summary() {
        var counts=new java.util.HashMap<String,Integer>();
        sql.query("SELECT status,count(*)::int AS count FROM outbox_jobs GROUP BY status",(org.springframework.jdbc.core.RowCallbackHandler)row -> counts.put(row.getString("status"),row.getInt("count")));
        return new JobDto.Summary(counts.getOrDefault("pending",0),counts.getOrDefault("processing",0),counts.getOrDefault("done",0),counts.getOrDefault("dead",0));
    }
    public record Locked(UUID id,String type,String status,int attempts) {}
    @Transactional(propagation=Propagation.MANDATORY) public java.util.Optional<Locked> lock(UUID id) {
        return sql.query("SELECT id,type,status,attempts FROM outbox_jobs WHERE id=? FOR UPDATE",(row,n) -> new Locked(row.getObject("id",UUID.class),row.getString("type"),row.getString("status"),row.getInt("attempts")),id).stream().findFirst();
    }
    @Transactional(propagation=Propagation.MANDATORY) public void retry(UUID id) {
        sql.update("UPDATE outbox_jobs SET status='pending',available_at=now(),leased_until=NULL,completed_at=NULL WHERE id=?",id);
    }
    private static JobDto.Item item(ResultSet row) throws SQLException {
        String type=row.getString("type"),error=row.getString("last_error_code");
        return new JobDto.Item(row.getObject("id",UUID.class),Set.of("email","transfer","mercadopago_pix","mercadopago_card","mercadopago_debt_pix").contains(type)?type:"unknown",row.getString("status"),row.getInt("attempts"),
            date(row,"available_at"),date(row,"leased_until"),date(row,"created_at"),date(row,"completed_at"),error==null||error.isEmpty()?null:SAFE_ERRORS.contains(error)?error:"TASK_FAILED");
    }
    private static String date(ResultSet row,String column) throws SQLException {
        var value=row.getTimestamp(column);return ApiDates.timestamp(value==null?null:value.toInstant());
    }
}
