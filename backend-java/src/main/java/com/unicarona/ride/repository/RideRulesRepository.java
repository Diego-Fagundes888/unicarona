package com.unicarona.ride.repository;

import com.unicarona.payment.service.PricingCalculator;
import com.unicarona.shared.ApiException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RideRulesRepository {
    public record Pricing(UUID id,PricingCalculator.Parameters params) {}
    public record Vehicle(UUID id,int capacity) {}
    public record Moment(boolean future,String name) {}
    private final JdbcTemplate sql;
    public RideRulesRepository(JdbcTemplate sql) { this.sql=sql; }
    public boolean detailsCurrent(UUID ride){return Boolean.TRUE.equals(sql.queryForObject("SELECT privacy_ride_details_visible(r) FROM rides r WHERE id=?",Boolean.class,ride));}
    public Pricing currentPricing(UUID university) {
        return sql.query("SELECT * FROM pricing_versions WHERE university_id=? ORDER BY created_at DESC,id DESC LIMIT 1",(r,n) -> new Pricing(r.getObject("id",UUID.class),
            new PricingCalculator.Parameters(r.getInt("fuel_cents"),r.getBigDecimal("efficiency"),r.getInt("wear_cents"),r.getInt("reference_passengers"),r.getInt("commission_bps"),r.getInt("discount_bps"))),university)
            .stream().findFirst().orElseThrow(() -> new ApiException("PRICING_UNAVAILABLE","A faculdade ainda não possui parâmetros de preço.",503));
    }
    public Optional<Vehicle> currentVehicle(UUID owner) {
        return sql.query("SELECT id,capacity FROM vehicles WHERE owner_id=? AND status='approved' AND deleted_at IS NULL",(r,n) -> new Vehicle(r.getObject("id",UUID.class),r.getInt("capacity")),owner).stream().findFirst();
    }
    public Optional<Vehicle> approvedVehicle(UUID id) {
        return sql.query("SELECT id,capacity FROM vehicles WHERE id=? AND status='approved'",(r,n) -> new Vehicle(r.getObject("id",UUID.class),r.getInt("capacity")),id).stream().findFirst();
    }
    public Optional<Moment> moment(UUID university,String date,String time) {
        return sql.query("SELECT (?::date+?::time) AT TIME ZONE time_zone>now() AS future,name FROM universities WHERE id=?",(r,n) -> new Moment(r.getBoolean("future"),r.getString("name")),date,time,university).stream().findFirst();
    }
    /** Call while holding the ride row lock when changing/reserving capacity. */
    public long seats(UUID ride,UUID exceptSubscription) {
        var result=sql.query("""
            SELECT r.capacity-(SELECT count(*) FROM ride_reservations rr WHERE rr.ride_id=r.id AND rr.status IN ('confirmed','boarded','awaiting_confirmation'))
            -(SELECT count(*) FROM subscription_schedule sc WHERE sc.ride_id=r.id AND sc.held AND (?::uuid IS NULL OR sc.subscription_id<>?)) AS available
            FROM rides r WHERE r.id=?
            """,(r,n) -> r.getLong("available"),exceptSubscription,exceptSubscription,ride);
        return result.stream().findFirst().orElseThrow(() -> new ApiException("RIDE_NOT_FOUND","Carona não encontrada.",404));
    }
}
