package com.unicarona.reservation.repository;

import com.unicarona.shared.ApiException;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

/** SQL preserves database timestamps, frozen evidence, campus dates and ordered row locks. */
@Repository @Transactional(propagation=Propagation.MANDATORY)
public class LifecycleRepository {
    private final JdbcTemplate sql;
    public LifecycleRepository(JdbcTemplate sql) { this.sql=sql; }
    public record Ride(UUID id,UUID driver,String status,boolean today,String date,String time,String zone,String proposedTime) {}
    public record Reservation(UUID id,UUID ride,UUID passenger,UUID driver,UUID contract,String status,String pinHash,Instant boardedAt,String availability,String absence,String timeResponse,Instant deadline) {
        @Override public String toString() { return "Reservation[id="+id+", status="+status+", pinHash=redacted]"; }
    }
    public UUID driver(UUID ride,UUID actor,boolean own) {
        return sql.query("SELECT driver_id FROM rides WHERE id=? AND (NOT ? OR driver_id=?)",(row,n) -> row.getObject("driver_id",UUID.class),ride,own,actor).stream().findFirst().orElseThrow(() -> new ApiException("RIDE_NOT_FOUND","Carona não encontrada.",404));
    }
    public List<UUID> passengers(UUID ride) { return sql.query("SELECT passenger_id FROM ride_reservations WHERE ride_id=?",(row,n) -> row.getObject("passenger_id",UUID.class),ride); }
    public UUID reservationRide(UUID id,UUID actor,boolean driver) {
        return sql.query("SELECT ride_id FROM ride_reservations WHERE id=? AND CASE WHEN ? THEN driver_id ELSE passenger_id END=?",(row,n) -> row.getObject("ride_id",UUID.class),id,driver,actor).stream().findFirst().orElseThrow(() -> new ApiException("RESERVATION_NOT_FOUND","Reserva não encontrada.",404));
    }
    public Ride lockRide(UUID id) {
        return sql.query("SELECT r.id,r.driver_id,r.status,(now() AT TIME ZONE un.time_zone)::date=r.ride_date AS today,r.ride_date::text AS date,r.departure_time::text AS time,un.time_zone,r.proposed_time::text AS proposed FROM rides r JOIN universities un ON un.id=r.university_id WHERE r.id=? FOR UPDATE OF r",(row,n) -> new Ride(row.getObject("id",UUID.class),row.getObject("driver_id",UUID.class),row.getString("status"),row.getBoolean("today"),row.getString("date"),row.getString("time").substring(0,5),row.getString("time_zone"),row.getString("proposed")),id).stream().findFirst().orElseThrow(() -> new ApiException("RIDE_NOT_FOUND","Carona não encontrada.",404));
    }
    public List<Reservation> lockReservations(UUID ride) {
        return sql.query("SELECT id,ride_id,passenger_id,driver_id,subscription_id,status,pin_hash,boarded_at,availability,absence,time_response,confirmation_deadline FROM ride_reservations WHERE ride_id=? ORDER BY id FOR UPDATE",(row,n) -> {
            var boarded=row.getTimestamp("boarded_at");var deadline=row.getTimestamp("confirmation_deadline");return new Reservation(row.getObject("id",UUID.class),row.getObject("ride_id",UUID.class),row.getObject("passenger_id",UUID.class),row.getObject("driver_id",UUID.class),row.getObject("subscription_id",UUID.class),row.getString("status"),row.getString("pin_hash"),boarded==null?null:boarded.toInstant(),row.getString("availability"),row.getString("absence"),row.getString("time_response"),deadline==null?null:deadline.toInstant());
        },ride);
    }
    public List<UUID> lockParcels(List<Reservation> reservations) {
        if(reservations.isEmpty()) return List.of();
        return sql.query("SELECT id FROM parcels WHERE reservation_id IN ("+String.join(",",Collections.nCopies(reservations.size(),"?"))+") ORDER BY id FOR UPDATE",(row,n) -> row.getObject("id",UUID.class),reservations.stream().map(Reservation::id).toArray());
    }
    public void start(UUID ride) { sql.update("UPDATE rides SET status='active',started_at=now(),version=version+1 WHERE id=?",ride); }
    public void finish(UUID ride) { sql.update("UPDATE rides SET status='completed',completed_at=now(),version=version+1 WHERE id=?",ride); }
    public void rideState(UUID ride,String status) { sql.update("UPDATE rides SET status=?,version=version+1 WHERE id=?",status,ride); }
    public boolean boardingPinAvailable(UUID reservation) { return Boolean.TRUE.equals(sql.queryForObject("SELECT privacy_boarding_pin_available(rr) FROM ride_reservations rr WHERE id=?",Boolean.class,reservation)); }
    public void board(UUID reservation) { sql.update("UPDATE ride_reservations SET status='boarded',boarded_at=now(),updated_at=now() WHERE id=?",reservation); }
    public void awaitFinal(UUID reservation,UUID parcel,String availability) {
        sql.update("UPDATE ride_reservations SET status='awaiting_confirmation',availability=?,confirmation_deadline=now()+interval '24 hours',updated_at=now() WHERE id=?",availability,reservation);
        sql.update("UPDATE parcels SET status='awaiting' WHERE id=?",parcel);
    }
    public void finalConfirmed(UUID reservation) { sql.update("UPDATE ride_reservations SET status='completed',final_response='confirmed',updated_at=now() WHERE id=?",reservation); }
    public void review(UUID reservation,boolean contest) {
        if(contest) sql.update("UPDATE ride_reservations SET status='under_review',final_response='contested',updated_at=now() WHERE id=?",reservation);
        else sql.update("UPDATE ride_reservations SET status='under_review' WHERE id=?",reservation);
    }
    public void cancel(UUID reservation,String reason,boolean timestamp) {
        if(timestamp) sql.update("UPDATE ride_reservations SET status='cancelled',cancel_reason=?,updated_at=now() WHERE id=?",reason,reservation);
        else sql.update("UPDATE ride_reservations SET status='cancelled',cancel_reason=? WHERE id=?",reason,reservation);
    }
    public void countedAbsence(UUID reservation,String absence) { sql.update("UPDATE ride_reservations SET absence=? WHERE id=?",absence,reservation); }
    public long absenceCount(UUID contract) { return sql.queryForObject("SELECT count(*) FROM ride_reservations WHERE subscription_id=? AND absence IN ('notified_absence','passenger_absent')",Long.class,contract); }
    public void notifyAbsence(UUID reservation,String reason) { sql.update("UPDATE ride_reservations SET absence='notified',absence_reason=? WHERE id=?",reason,reservation); }
    public void cancelUnpaidAndReleaseHolds(UUID ride,String reason) {
        var requests=sql.query("UPDATE ride_requests SET status='cancelled',reason=? WHERE id IN (SELECT request_id FROM request_lines WHERE ride_id=?) AND status IN ('pending','accepted') RETURNING id",(row,n) -> row.getObject("id",UUID.class),reason,ride);
        if(!requests.isEmpty()) { var args=new ArrayList<Object>(requests);sql.update("UPDATE payments SET status='expired' WHERE request_id IN ("+String.join(",",Collections.nCopies(requests.size(),"?"))+") AND status IN ('awaiting','pending')",args.toArray()); }
        sql.update("UPDATE subscription_schedule SET held=false WHERE ride_id=? AND held",ride);
    }
    public boolean future(Ride ride,String time) { return Boolean.TRUE.equals(sql.queryForObject("SELECT (?::date+?::time) AT TIME ZONE ?>now()",Boolean.class,ride.date(),time,ride.zone())); }
    public void propose(UUID ride,String time,String reason,boolean linked) {
        if(linked) sql.update("UPDATE rides SET proposed_time=?::time,proposal_reason=?,version=version+1 WHERE id=?",time,reason,ride);
        else sql.update("UPDATE rides SET departure_time=?::time,proposed_time=NULL,proposal_reason=NULL,version=version+1 WHERE id=?",time,ride);
    }
    public void reservationProposal(UUID reservation,String time) { sql.update("UPDATE ride_reservations SET proposed_time=?::time,time_response='pending' WHERE id=?",time,reservation); }
    public void answer(UUID reservation,boolean accept) { sql.update("UPDATE ride_reservations SET time_response=? WHERE id=?",accept?"accepted":"declined",reservation); }
    public void acceptProposal(UUID ride) { sql.update("UPDATE rides SET departure_time=proposed_time,proposed_time=NULL,proposal_reason=NULL,version=version+1 WHERE id=?",ride); }
    public void keepTime(UUID ride) { sql.update("UPDATE rides SET proposed_time=NULL,proposal_reason=NULL,version=version+1 WHERE id=?",ride); }
    public void withdraw(UUID reservation) { sql.update("UPDATE ride_reservations SET proposed_time=NULL,time_response='withdrawn' WHERE id=?",reservation); }
}
