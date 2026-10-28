package com.unicarona.occurrence.repository;

import com.unicarona.shared.ApiException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

/** Explicit PostgreSQL locks keep safety decisions in the same transaction as the ledger. */
@Repository @Transactional(propagation=Propagation.MANDATORY)
public class SafetyRepository {
    private final JdbcTemplate sql;
    public SafetyRepository(JdbcTemplate sql) { this.sql=sql; }
    public void related(UUID actor,UUID other) {
        boolean found=Boolean.TRUE.equals(sql.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM users u JOIN student_profiles s ON s.user_id=u.id
            JOIN student_profiles mine ON mine.user_id=? AND mine.university_id=s.university_id
            WHERE u.id=? AND u.role='user' AND u.deleted_at IS NULL AND u.id<>? AND (
            EXISTS(SELECT 1 FROM rides r WHERE r.driver_id=u.id AND r.status='planned') OR
            EXISTS(SELECT 1 FROM ride_requests q WHERE (q.passenger_id=mine.user_id AND q.driver_id=u.id) OR (q.driver_id=mine.user_id AND q.passenger_id=u.id))))
            """,Boolean.class,actor,other,actor));
        ApiException.require(found,"PROFILE_NOT_FOUND","Perfil envolvido não encontrado.",404);
    }
    public record Reservation(UUID id,UUID ride) {}
    public Reservation reservation(UUID id,UUID actor,UUID other) {
        return sql.query("SELECT id,ride_id FROM ride_reservations WHERE id=? AND ((passenger_id=? AND driver_id=?) OR (passenger_id=? AND driver_id=?))",
            (r,n) -> new Reservation(r.getObject("id",UUID.class),r.getObject("ride_id",UUID.class)),id,actor,other,other,actor).stream().findFirst()
            .orElseThrow(() -> new ApiException("RESERVATION_NOT_FOUND","Reserva não encontrada.",404));
    }
    public record ParcelRef(UUID id,String status,String availability,UUID reservation) {}
    public ParcelRef lockReservation(Reservation reservation) {
        sql.queryForList("SELECT id FROM rides WHERE id=? FOR UPDATE",reservation.ride());sql.queryForList("SELECT id FROM ride_reservations WHERE id=? FOR UPDATE",reservation.id());
        return sql.query("SELECT id,status,reservation_id FROM parcels WHERE reservation_id=? FOR UPDATE",(r,n) -> new ParcelRef(r.getObject("id",UUID.class),r.getString("status"),null,r.getObject("reservation_id",UUID.class)),reservation.id()).getFirst();
    }
    public void underReview(UUID reservation) { sql.update("UPDATE ride_reservations SET status=CASE WHEN status='cancelled' THEN 'cancelled' ELSE 'under_review' END WHERE id=?",reservation); }
    public List<UUID> contracts(UUID a,UUID b) {
        return sql.query("SELECT id FROM subscriptions_with_open_rights WHERE ((driver_id=? AND passenger_id=?) OR (driver_id=? AND passenger_id=?)) ORDER BY id FOR UPDATE",
            (r,n) -> r.getObject("id",UUID.class),a,b,b,a);
    }
    public void cancelUnpaid(UUID a,UUID b) {
        var ids=sql.query("UPDATE ride_requests SET status='cancelled',reason='combination_unavailable' WHERE status IN ('pending','accepted') AND ((driver_id=? AND passenger_id=?) OR (driver_id=? AND passenger_id=?)) RETURNING id",(r,n) -> r.getObject("id",UUID.class),a,b,b,a);
        if(!ids.isEmpty()) sql.update("UPDATE payments SET status='expired' WHERE request_id IN ("+marks(ids.size())+") AND status IN ('awaiting','pending')",ids.toArray());
    }
    public UUID blockOwner(UUID id,UUID actor) {
        return sql.query("SELECT other_id FROM user_blocks WHERE id=? AND by_id=?",(r,n) -> r.getObject("other_id",UUID.class),id,actor).stream().findFirst().orElseThrow(() -> new ApiException("BLOCK_NOT_FOUND","Bloqueio não encontrado.",404));
    }
    public record ReportRef(UUID reporter,UUID other,String status) {}
    public ReportRef report(UUID id,boolean lock) {
        return sql.query("SELECT reporter_id,other_id,status FROM occurrences WHERE id=?"+(lock?" FOR UPDATE":""),(r,n) -> new ReportRef(r.getObject("reporter_id",UUID.class),r.getObject("other_id",UUID.class),r.getString("status")),id).stream().findFirst().orElseThrow(() -> new ApiException("OCCURRENCE_NOT_FOUND","Ocorrência não encontrada.",404));
    }
    public record Participants(UUID passenger,UUID driver,UUID ride) {}
    public List<Participants> participants(UUID report) {
        return sql.query("SELECT p.passenger_id,p.driver_id,rr.ride_id FROM occurrence_parcels op JOIN parcels p ON p.id=op.parcel_id JOIN ride_reservations rr ON rr.id=p.reservation_id WHERE op.occurrence_id=?",
            (r,n) -> new Participants(r.getObject("passenger_id",UUID.class),r.getObject("driver_id",UUID.class),r.getObject("ride_id",UUID.class)),report);
    }
    public void lockRides(Collection<UUID> ids) { var unique=ids.stream().distinct().toList();if(!unique.isEmpty()) sql.queryForList("SELECT id FROM rides WHERE id IN ("+marks(unique.size())+") ORDER BY id FOR UPDATE",unique.toArray()); }
    public List<ParcelRef> lockParcels(UUID report) {
        return sql.query("SELECT p.id,p.status,p.reservation_id,rr.availability FROM occurrence_parcels op JOIN parcels p ON p.id=op.parcel_id JOIN ride_reservations rr ON rr.id=p.reservation_id WHERE op.occurrence_id=? ORDER BY p.id FOR UPDATE OF p",
            (r,n) -> new ParcelRef(r.getObject("id",UUID.class),r.getString("status"),r.getString("availability"),r.getObject("reservation_id",UUID.class)),report);
    }
    public List<String> conflicts(List<ParcelRef> parcels,UUID report,String outcome) {
        if(parcels.isEmpty()) return List.of();var args=new ArrayList<Object>();parcels.forEach(p -> args.add(p.id()));args.add(report);args.add(outcome);
        return sql.query("SELECT DISTINCT o.id FROM occurrence_parcels op JOIN occurrences o ON o.id=op.occurrence_id JOIN parcels p ON p.id=op.parcel_id WHERE p.id IN ("+marks(parcels.size())+") AND p.status='blocked' AND o.id<>? AND o.status='resolved' AND o.outcome IS NOT NULL AND o.outcome<>? ORDER BY o.id",(r,n) -> r.getObject("id",UUID.class).toString(),args.toArray());
    }
    public void decide(UUID report,String outcome,String reason,UUID actor) { sql.update("UPDATE occurrences SET status='resolved',outcome=?,decision=?,reviewed_by=?,reviewed_at=now() WHERE id=?",outcome,reason,actor,report); }
    public boolean otherPending(UUID parcel,UUID report) {
        return Boolean.TRUE.equals(sql.queryForObject("SELECT EXISTS(SELECT 1 FROM occurrence_parcels op JOIN occurrences o ON o.id=op.occurrence_id WHERE op.parcel_id=? AND o.id<>? AND o.status IN ('pending','financial_pending'))",Boolean.class,parcel,report));
    }
    public void completed(UUID reservation) { sql.update("UPDATE ride_reservations SET status=CASE WHEN status='cancelled' OR EXISTS(SELECT 1 FROM rides r WHERE r.id=ride_reservations.ride_id AND r.status='cancelled') THEN 'cancelled' ELSE 'completed' END,updated_at=now() WHERE id=?",reservation); }
    private static String marks(int count) { return String.join(",",Collections.nCopies(count,"?")); }
}
