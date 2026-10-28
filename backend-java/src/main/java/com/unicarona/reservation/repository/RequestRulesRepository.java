package com.unicarona.reservation.repository;

import com.unicarona.shared.ApiException;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

@Repository
public class RequestRulesRepository {
    public enum Side { PASSENGER, DRIVER, PARTICIPANT }
    public record Participants(UUID passenger,UUID driver) {}
    public record Offer(UUID id,UUID driver,int capacity,int priceCents,int commissionBps,boolean automatic,String status,boolean future,String proposedTime) {}
    public record LockedLine(Offer ride,int cents,int commissionBps,String zone) {}
    private final JdbcTemplate sql;
    public RequestRulesRepository(JdbcTemplate sql) { this.sql=sql; }
    public UUID rideDriver(UUID id) {
        return sql.query("SELECT driver_id FROM rides WHERE id=?",(r,n) -> r.getObject(1,UUID.class),id).stream().findFirst().orElseThrow(() -> new ApiException("RIDE_NOT_FOUND","Carona não encontrada.",404));
    }
    public Participants participants(UUID id,UUID actor,Side side) {
        var reference=sql.query("SELECT passenger_id,driver_id FROM ride_requests WHERE id=?",(r,n) -> new Participants(r.getObject(1,UUID.class),r.getObject(2,UUID.class)),id).stream().findFirst().orElse(null);
        ApiException.require(reference!=null && switch(side) { case PASSENGER -> reference.passenger().equals(actor);case DRIVER -> reference.driver().equals(actor);case PARTICIPANT -> reference.passenger().equals(actor) || reference.driver().equals(actor); },"REQUEST_NOT_FOUND","Solicitação não encontrada.",404);return reference;
    }
    @Transactional(propagation=Propagation.MANDATORY) public Offer lockRide(UUID id) {
        return sql.query("""
            SELECT r.*,(r.ride_date+r.departure_time) AT TIME ZONE un.time_zone>now() AS future
            FROM rides r JOIN universities un ON un.id=r.university_id WHERE r.id=? FOR UPDATE OF r
            """,(r,n) -> offer(r),id).stream().findFirst().orElseThrow(() -> new ApiException("RIDE_FULL","Esta carona não tem vaga disponível.",409));
    }
    @Transactional(propagation=Propagation.MANDATORY) public List<LockedLine> lockedLines(UUID request) {
        return sql.query("""
            SELECT r.*,l.cents,l.commission_bps AS contracted_commission,un.time_zone,
            (r.ride_date+r.departure_time) AT TIME ZONE un.time_zone>now() AS future
            FROM request_lines l JOIN rides r ON r.id=l.ride_id JOIN universities un ON un.id=r.university_id
            WHERE l.request_id=? ORDER BY r.id FOR UPDATE OF r
            """,(r,n) -> new LockedLine(offer(r),r.getInt("cents"),r.getInt("contracted_commission"),r.getString("time_zone")),request);
    }
    @Transactional(propagation=Propagation.MANDATORY) public boolean linesAvailable(List<Offer> offers,UUID passenger,UUID previous) {
        for(var ride:offers) {
            if(!"planned".equals(ride.status()) || !ride.future() || ride.proposedTime()!=null) return false;
            var count=sql.queryForMap("""
                SELECT (SELECT count(*) FROM ride_reservations WHERE ride_id=? AND status IN ('confirmed','boarded','awaiting_confirmation'))+
                (SELECT count(*) FROM subscription_schedule WHERE ride_id=? AND held AND (?::uuid IS NULL OR subscription_id<>?)) AS occupied,
                EXISTS(SELECT 1 FROM ride_reservations WHERE ride_id=? AND passenger_id=? AND status IN ('confirmed','boarded','awaiting_confirmation')) AS own
                """,ride.id(),ride.id(),previous,previous,ride.id(),passenger);
            if(Boolean.TRUE.equals(count.get("own")) || ((Number)count.get("occupied")).longValue()>=ride.capacity()) return false;
        }
        return !offers.isEmpty();
    }
    public boolean valid(UUID request) { return Boolean.TRUE.equals(sql.queryForObject("SELECT expires_at>now() FROM ride_requests WHERE id=?",Boolean.class,request)); }
    @Transactional(propagation=Propagation.MANDATORY) public Instant requestExpiry(int hours) {
        return sql.queryForObject("SELECT now()+make_interval(hours=>?)",(r,n) -> r.getTimestamp(1).toInstant(),hours);
    }
    @Transactional(propagation=Propagation.MANDATORY) public Instant checkoutExpiry(Instant requestExpires,int minutes) {
        // pg's Date object in the reference backend preserves milliseconds for this bound parameter.
        var reference=requestExpires.truncatedTo(java.time.temporal.ChronoUnit.MILLIS).atOffset(ZoneOffset.UTC);
        return sql.queryForObject("SELECT LEAST(?::timestamptz,now()+make_interval(mins=>?))",(r,n) -> r.getTimestamp(1).toInstant(),reference,minutes);
    }
    public int total(UUID request) { return sql.queryForObject("SELECT sum(cents)::integer FROM request_lines WHERE request_id=?",Integer.class,request); }
    private static Offer offer(java.sql.ResultSet r) throws java.sql.SQLException {
        return new Offer(r.getObject("id",UUID.class),r.getObject("driver_id",UUID.class),r.getInt("capacity"),r.getInt("price_cents"),r.getInt("commission_bps"),r.getBoolean("automatic"),r.getString("status"),r.getBoolean("future"),r.getString("proposed_time"));
    }
}
