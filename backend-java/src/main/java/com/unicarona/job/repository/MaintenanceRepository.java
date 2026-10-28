package com.unicarona.job.repository;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

/** Candidate scans are bounded; each write rechecks state after the shared domain locks. */
@Repository
public class MaintenanceRepository {
    public record Request(UUID id,UUID passenger) {}
    public record Final(UUID id,UUID ride) {}
    public record Period(Instant deadline,String start,boolean ended,boolean renew,String status,Instant grace) {}
    private final JdbcTemplate sql;private final Clock clock;
    public MaintenanceRepository(JdbcTemplate sql,Clock clock) { this.sql=sql;this.clock=clock; }
    private static final String DUE_REQUEST="q.status IN ('pending','accepted') AND (q.expires_at<=now() OR EXISTS(SELECT 1 FROM payments p WHERE p.request_id=q.id AND p.expires_at<=now() AND p.status IN ('awaiting','pending')))";
    public List<Request> requests(int limit) { return sql.query("SELECT q.id,q.passenger_id FROM ride_requests q WHERE "+DUE_REQUEST+" ORDER BY q.expires_at,q.id LIMIT ?",(r,n) -> new Request(r.getObject("id",UUID.class),r.getObject("passenger_id",UUID.class)),limit); }
    public List<Final> finals(int limit) { return sql.query("SELECT id,ride_id FROM ride_reservations WHERE status='awaiting_confirmation' AND confirmation_deadline<=now() ORDER BY confirmation_deadline,id LIMIT ?",(r,n) -> new Final(r.getObject("id",UUID.class),r.getObject("ride_id",UUID.class)),limit); }
    public List<UUID> contracts(int limit) {
        var now=clock.instant().atOffset(ZoneOffset.UTC);
        return sql.query("SELECT s.id FROM subscriptions s JOIN routines rt ON rt.id=s.routine_id JOIN universities un ON un.id=rt.university_id WHERE (s.status IN ('active','cancelled_renewal') AND s.end_date<(?::timestamptz AT TIME ZONE un.time_zone)::date) OR (s.status IN ('grace','renew_conflict') AND s.grace_until<=?::timestamptz) ORDER BY s.end_date,s.id LIMIT ?",(r,n) -> r.getObject("id",UUID.class),now,now,limit);
    }
    @Transactional(propagation=Propagation.MANDATORY) public boolean due(UUID request) { return Boolean.TRUE.equals(sql.queryForObject("SELECT EXISTS(SELECT 1 FROM ride_requests q WHERE q.id=? AND "+DUE_REQUEST+")",Boolean.class,request)); }
    @Transactional(propagation=Propagation.MANDATORY) public void expireRequest(UUID id) { sql.update("UPDATE ride_requests SET status='expired',reason='deadline',updated_at=now() WHERE id=?",id);sql.update("UPDATE payments SET status='expired',updated_at=now() WHERE request_id=? AND status IN ('awaiting','pending')",id); }
    @Transactional(propagation=Propagation.MANDATORY) public void timeoutFinal(UUID id) { sql.update("UPDATE ride_reservations SET status='completed',final_response='timeout',updated_at=now() WHERE id=?",id); }
    @Transactional(propagation=Propagation.MANDATORY) public Period period(UUID id) {
        var now=clock.instant().atOffset(ZoneOffset.UTC);
        return sql.queryForObject("SELECT ((s.end_date+1)::timestamp AT TIME ZONE un.time_zone)+interval '72 hours' AS deadline,greatest(s.end_date+1,(?::timestamptz AT TIME ZONE un.time_zone)::date)::text AS start,s.end_date<(?::timestamptz AT TIME ZONE un.time_zone)::date AS ended,s.renew,s.status,s.grace_until FROM subscriptions s JOIN routines rt ON rt.id=s.routine_id JOIN universities un ON un.id=rt.university_id WHERE s.id=?",
            (r,n) -> { var grace=r.getTimestamp("grace_until");return new Period(r.getTimestamp("deadline").toInstant(),r.getString("start"),r.getBoolean("ended"),r.getBoolean("renew"),r.getString("status"),grace==null?null:grace.toInstant()); },now,now,id);
    }
    @Transactional(propagation=Propagation.MANDATORY) public void graceDeadline(UUID id,Instant deadline) { sql.update("UPDATE subscriptions SET grace_until=?::timestamptz WHERE id=?",deadline.atOffset(java.time.ZoneOffset.UTC),id); }
    @Transactional(propagation=Propagation.MANDATORY) public void grace(UUID id,boolean held) { sql.update("UPDATE subscriptions SET status=?,updated_at=now() WHERE id=?",held?"grace":"renew_conflict",id); }
    @Transactional(propagation=Propagation.MANDATORY) public void hold(UUID contract,UUID ride,long cents) { sql.update("INSERT INTO subscription_schedule(subscription_id,ride_id,cents,held) VALUES(?,?,?,true) ON CONFLICT(subscription_id,ride_id) DO UPDATE SET held=true",contract,ride,cents); }
    @Transactional(propagation=Propagation.MANDATORY) public void savepoint() { sql.execute("SAVEPOINT renewal_holds"); }
    @Transactional(propagation=Propagation.MANDATORY) public void releaseSavepoint() { sql.execute("RELEASE SAVEPOINT renewal_holds"); }
    @Transactional(propagation=Propagation.MANDATORY) public void rollbackSavepoint() { sql.execute("ROLLBACK TO SAVEPOINT renewal_holds");sql.execute("RELEASE SAVEPOINT renewal_holds"); }
    public void pruneRateLimits() { sql.update("DELETE FROM rate_limits WHERE expires_at<now()"); }
}
