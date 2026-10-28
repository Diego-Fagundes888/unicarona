package com.unicarona.subscription.repository;

import com.unicarona.subscription.dto.ContractDto;
import com.unicarona.privacy.service.ParticipantIdentity;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

@Repository
public class WaitlistRepository {
    private final JdbcTemplate sql;
    public WaitlistRepository(JdbcTemplate sql) { this.sql=sql; }
    public record Routine(UUID driver,UUID classId) {}
    public Optional<Routine> routine(UUID id) {
        return sql.query("SELECT driver_id,class_id FROM routines WHERE id=?",(row,n) -> new Routine(row.getObject("driver_id",UUID.class),row.getObject("class_id",UUID.class)),id).stream().findFirst();
    }
    @Transactional(propagation=Propagation.MANDATORY) public UUID join(UUID user,UUID routine) {
        return sql.queryForObject("INSERT INTO waitlist(user_id,routine_id) VALUES(?,?) ON CONFLICT(user_id,routine_id) WHERE status='waiting' DO UPDATE SET user_id=excluded.user_id RETURNING id",UUID.class,user,routine);
    }
    @Transactional(propagation=Propagation.MANDATORY) public void prioritize(UUID user,UUID routine) {
        sql.update("INSERT INTO waitlist(user_id,routine_id,priority) VALUES(?,?,true) ON CONFLICT(user_id,routine_id) WHERE status='waiting' DO UPDATE SET priority=true",user,routine);
    }
    public Optional<UUID> leave(UUID user,UUID id) {
        return sql.query("UPDATE waitlist SET status='cancelled' WHERE id=? AND user_id=? RETURNING id",(row,n) -> row.getObject("id",UUID.class),id,user).stream().findFirst();
    }
    public List<ContractDto.Interest> interests(UUID user) {
        return sql.query("""
            SELECT w.id,w.routine_id,w.priority,rt.region,CASE WHEN u.deleted_at IS NULL AND u.account_status<>'deleted' THEN u.name END AS name FROM waitlist w JOIN routines r ON r.id=w.routine_id
            JOIN ride_routes rt ON rt.id=r.route_id JOIN users u ON u.id=r.driver_id
            WHERE w.user_id=? AND w.status='waiting' ORDER BY w.created_at DESC LIMIT 100
            """,(row,n) -> new ContractDto.Interest(row.getObject("id",UUID.class),row.getObject("routine_id",UUID.class),row.getBoolean("priority"),row.getString("region"),ParticipantIdentity.label(row.getString("name"))),user);
    }
}
