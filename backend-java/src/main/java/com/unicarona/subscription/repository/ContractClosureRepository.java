package com.unicarona.subscription.repository;

import com.unicarona.shared.ApiException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

/** PostgreSQL locks and campus time define which paid evidence is still genuinely future. */
@Repository
@Transactional(propagation=Propagation.MANDATORY)
public class ContractClosureRepository {
    private final JdbcTemplate sql;
    public ContractClosureRepository(JdbcTemplate sql) { this.sql=sql; }
    public record Participants(UUID driver,UUID passenger) {}
    public Participants participants(UUID id,UUID actor,String side) {
        var reference=sql.query("SELECT driver_id,passenger_id FROM subscriptions WHERE id=?",(row,n) -> new Participants(row.getObject("driver_id",UUID.class),row.getObject("passenger_id",UUID.class)),id).stream().findFirst();
        ApiException.require(reference.isPresent() && (actor==null || switch(side) {
            case "driver" -> reference.get().driver().equals(actor);
            case "passenger" -> reference.get().passenger().equals(actor);
            default -> reference.get().driver().equals(actor) || reference.get().passenger().equals(actor);
        }),"SUBSCRIPTION_NOT_FOUND","Mensalidade não encontrada.",404);
        return reference.orElseThrow();
    }
    public List<UUID> futureRides(UUID contract) {
        return sql.query("""
            SELECT r.id FROM rides r JOIN ride_reservations rr ON rr.ride_id=r.id JOIN universities un ON un.id=r.university_id
            WHERE rr.subscription_id=? AND r.status='planned' AND (r.ride_date+r.departure_time) AT TIME ZONE un.time_zone>now()
            ORDER BY r.id FOR UPDATE OF r
            """,(row,n) -> row.getObject("id",UUID.class),contract);
    }
    public List<UUID> linkedOpenPeriods(UUID contract) {
        return sql.query("""
            WITH RECURSIVE ancestors AS (
             SELECT s.id,s.previous_subscription_id,s.driver_id,s.passenger_id,s.routine_id,ARRAY[s.id] AS visited
             FROM subscriptions s WHERE s.id=?
             UNION ALL
             SELECT s.id,s.previous_subscription_id,s.driver_id,s.passenger_id,s.routine_id,a.visited||s.id
             FROM subscriptions s JOIN ancestors a ON s.id=a.previous_subscription_id
             WHERE s.driver_id=a.driver_id AND s.passenger_id=a.passenger_id AND s.routine_id=a.routine_id
              AND NOT s.id=ANY(a.visited)
            ), root AS (SELECT id FROM ancestors ORDER BY cardinality(visited) DESC LIMIT 1), periods AS (
             SELECT s.id,s.driver_id,s.passenger_id,s.routine_id,ARRAY[s.id] AS visited
             FROM subscriptions s JOIN root ON root.id=s.id
             UNION ALL
             SELECT s.id,s.driver_id,s.passenger_id,s.routine_id,p.visited||s.id
             FROM subscriptions s JOIN periods p ON s.previous_subscription_id=p.id
             WHERE s.driver_id=p.driver_id AND s.passenger_id=p.passenger_id AND s.routine_id=p.routine_id
              AND NOT s.id=ANY(p.visited)
            )
            SELECT s.id FROM subscriptions s JOIN periods p ON p.id=s.id
            WHERE EXISTS(SELECT 1 FROM subscriptions_with_open_rights live WHERE live.id=s.id)
            ORDER BY s.id FOR UPDATE OF s
            """,(r,n)->r.getObject("id",UUID.class),contract);
    }
    public void cancelUnpaidRenewals(List<UUID> periods) {
        if(periods.isEmpty()) return;
        var requests=sql.query("UPDATE ride_requests SET status='cancelled',reason='combination_unavailable',updated_at=now() WHERE previous_subscription_id IN ("+placeholders(periods.size())+") AND status IN ('pending','accepted') RETURNING id",
            (r,n)->r.getObject("id",UUID.class),periods.toArray());
        if(!requests.isEmpty()) sql.update("UPDATE payments SET status='expired',updated_at=now() WHERE request_id IN ("+placeholders(requests.size())+") AND status IN ('awaiting','pending')",requests.toArray());
    }
    public List<UUID> reservations(UUID contract,List<UUID> rides) {
        if(rides.isEmpty()) return List.of();
        var args=new ArrayList<Object>();args.add(contract);args.addAll(rides);
        return sql.query("SELECT id FROM ride_reservations WHERE subscription_id=? AND ride_id IN ("+placeholders(rides.size())+") AND status='confirmed' ORDER BY id FOR UPDATE",(row,n) -> row.getObject("id",UUID.class),args.toArray());
    }
    public record Parcel(UUID id,UUID reservation,String status) {}
    public List<Parcel> parcels(List<UUID> reservations) {
        if(reservations.isEmpty()) return List.of();
        return sql.query("SELECT id,reservation_id,status FROM parcels WHERE reservation_id IN ("+placeholders(reservations.size())+") ORDER BY id FOR UPDATE",(row,n) -> new Parcel(row.getObject("id",UUID.class),row.getObject("reservation_id",UUID.class),row.getString("status")),reservations.toArray());
    }
    public void cancelReservation(UUID id,String reason) {
        // The old closure intentionally retains updated_at of this paid evidence.
        sql.update("UPDATE ride_reservations SET status='cancelled',cancel_reason=? WHERE id=?",reason,id);
    }
    private static String placeholders(int count) { return String.join(",",Collections.nCopies(count,"?")); }
}
