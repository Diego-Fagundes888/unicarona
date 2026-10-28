package com.unicarona.admin.repository;

import com.unicarona.admin.dto.AdminAccountDto;
import com.unicarona.shared.dto.PageDto;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

@Repository
public class AdminAccountRepository {
    public record Pair(UUID driver,UUID passenger) {}
    public record Pending(UUID id,UUID passenger) {}
    public record Money(UUID id,UUID reservation,String status) {}
    public record Request(UUID id,UUID driver,UUID passenger) {}
    private final JdbcTemplate sql;
    public AdminAccountRepository(JdbcTemplate sql) { this.sql=sql; }
    public PageDto<AdminAccountDto.Account> list(AdminAccountDto.Query input) {
        var page=input.page();return PageDto.from(sql.query("""
            SELECT u.id,u.name,u.email,u.account_status,un.name AS university,c.name AS course,coalesce(d.status,'not_requested') AS driver_status
            FROM users u LEFT JOIN student_profiles sp ON sp.user_id=u.id LEFT JOIN universities un ON un.id=sp.university_id
            LEFT JOIN courses c ON c.id=sp.course_id LEFT JOIN driver_profiles d ON d.user_id=u.id
            WHERE u.role='user' AND u.deleted_at IS NULL AND u.account_status<>'deleted'
            AND (?='' OR normalized_text(u.name||' '||u.email) LIKE '%'||normalized_text(?)||'%')
            AND (?::text IS NULL OR u.account_status=?) ORDER BY u.name,u.id LIMIT ? OFFSET ?
            """,(r,n) -> new AdminAccountDto.Account(r.getObject("id",UUID.class),r.getString("name"),r.getString("email"),r.getString("account_status"),r.getString("university"),r.getString("course"),r.getString("driver_status")),input.search(),input.search(),input.status(),input.status(),page.limit()+1,page.offset()),page.limit(),page.offset());
    }
    @Transactional(propagation=Propagation.MANDATORY) public List<Pair> participants(UUID id) {
        return sql.query("""
            SELECT driver_id,passenger_id FROM subscriptions_with_open_rights WHERE driver_id=? OR passenger_id=?
            UNION SELECT driver_id,passenger_id FROM ride_requests WHERE (driver_id=? OR passenger_id=?) AND status IN ('pending','accepted')
            UNION SELECT rr.driver_id,rr.passenger_id FROM ride_reservations rr JOIN rides r ON r.id=rr.ride_id
            WHERE (rr.driver_id=? OR rr.passenger_id=?) AND rr.status='confirmed' AND r.status='planned'
            """,(r,n) -> new Pair(r.getObject("driver_id",UUID.class),r.getObject("passenger_id",UUID.class)),id,id,id,id,id,id);
    }
    @Transactional(propagation=Propagation.MANDATORY) public void status(UUID id,String status) { sql.update("UPDATE users SET account_status=?,updated_at=now() WHERE id=?",status,id); }
    public boolean voluntarilyDeactivated(UUID id){return Boolean.TRUE.equals(sql.queryForObject("SELECT voluntarily_deactivated_at IS NOT NULL FROM users WHERE id=?",Boolean.class,id));}
    public boolean adult(UUID id){return Boolean.TRUE.equals(sql.queryForObject("SELECT birth_date<=((now() AT TIME ZONE 'America/Sao_Paulo')::date-interval '18 years')::date FROM users WHERE id=?",Boolean.class,id));}
    public boolean closurePending(UUID id){return Boolean.TRUE.equals(sql.queryForObject("SELECT EXISTS(SELECT 1 FROM privacy_account_closures WHERE user_id=? AND status IN ('scheduled','pending_obligations'))",Boolean.class,id));}
    @Transactional(propagation=Propagation.MANDATORY) public List<UUID> contracts(UUID id) { return sql.query("SELECT id FROM subscriptions_with_open_rights WHERE driver_id=? OR passenger_id=? ORDER BY id FOR UPDATE",(r,n) -> r.getObject(1,UUID.class),id,id); }
    @Transactional(propagation=Propagation.MANDATORY) public void lockPlanned(UUID id) {
        sql.queryForList("""
            SELECT r.id FROM rides r WHERE r.status='planned' AND (r.driver_id=? OR
            EXISTS(SELECT 1 FROM ride_reservations rr WHERE rr.ride_id=r.id AND rr.passenger_id=? AND rr.status='confirmed') OR
            EXISTS(SELECT 1 FROM subscription_schedule sc JOIN subscriptions s ON s.id=sc.subscription_id WHERE sc.ride_id=r.id AND sc.held AND (s.driver_id=? OR s.passenger_id=?))) ORDER BY r.id FOR UPDATE
            """,id,id,id,id);
    }
    @Transactional(propagation=Propagation.MANDATORY) public List<Pending> pending(UUID id) { return sql.query("SELECT rr.id,rr.passenger_id FROM ride_reservations rr JOIN rides r ON r.id=rr.ride_id WHERE (rr.driver_id=? OR rr.passenger_id=?) AND rr.status='confirmed' AND r.status='planned' ORDER BY rr.id FOR UPDATE OF rr",(r,n) -> new Pending(r.getObject("id",UUID.class),r.getObject("passenger_id",UUID.class)),id,id); }
    @Transactional(propagation=Propagation.MANDATORY) public List<Money> parcels(List<Pending> reservations) {
        if(reservations.isEmpty()) return List.of();return sql.query("SELECT id,reservation_id,status FROM parcels WHERE reservation_id IN ("+String.join(",",Collections.nCopies(reservations.size(),"?"))+") ORDER BY id FOR UPDATE",(r,n) -> new Money(r.getObject("id",UUID.class),r.getObject("reservation_id",UUID.class),r.getString("status")),reservations.stream().map(Pending::id).toArray());
    }
    @Transactional(propagation=Propagation.MANDATORY) public void reviewReservation(UUID id) { sql.update("UPDATE ride_reservations SET status='under_review',updated_at=now() WHERE id=?",id); }
    @Transactional(propagation=Propagation.MANDATORY) public void cancelOffers(UUID id) { sql.update("UPDATE rides SET status='cancelled',version=version+1 WHERE driver_id=? AND status='planned'",id);sql.update("UPDATE routines SET status='paused',updated_at=now() WHERE driver_id=? AND status='active'",id); }
    @Transactional(propagation=Propagation.MANDATORY) public List<Request> cancelUnpaid(UUID id) {
        var requests=sql.query("UPDATE ride_requests SET status='cancelled',reason='account_review',updated_at=now() WHERE (driver_id=? OR passenger_id=?) AND status IN ('pending','accepted') RETURNING id,driver_id,passenger_id",(r,n) -> new Request(r.getObject("id",UUID.class),r.getObject("driver_id",UUID.class),r.getObject("passenger_id",UUID.class)),id,id);
        if(!requests.isEmpty()) sql.update("UPDATE payments SET status='expired',updated_at=now() WHERE request_id IN ("+String.join(",",Collections.nCopies(requests.size(),"?"))+") AND status IN ('awaiting','pending')",requests.stream().map(Request::id).toArray());return requests;
    }
}
