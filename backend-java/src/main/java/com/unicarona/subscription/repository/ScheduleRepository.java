package com.unicarona.subscription.repository;

import com.unicarona.subscription.dto.SubscriptionDto;
import com.unicarona.shared.ApiException;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

@Repository
public class ScheduleRepository {
    public record RoutineData(UUID id,UUID driver,UUID university,UUID classId,List<Integer> weekdays,String idaTime,String voltaTime,String status,String zone) {}
    public record Dates(String today,String end) {}
    public record Offer(UUID id,String date,String time,String direction,long cents,int commissionBps,String status,long available,boolean alreadyReserved) {}
    private final JdbcTemplate sql;
    public ScheduleRepository(JdbcTemplate sql) { this.sql=sql; }
    public RoutineData routine(UUID id) {
        return sql.query("SELECT rt.*,un.time_zone FROM routines rt JOIN universities un ON un.id=rt.university_id WHERE rt.id=?",(r,n) -> {
            var weekdays=new ArrayList<Integer>();var values=r.getArray("weekdays");try { for(Object value:(Object[])values.getArray()) weekdays.add(((Number)value).intValue()); } finally { values.free(); }
            return new RoutineData(r.getObject("id",UUID.class),r.getObject("driver_id",UUID.class),r.getObject("university_id",UUID.class),r.getObject("class_id",UUID.class),List.copyOf(weekdays),r.getString("ida_time"),r.getString("volta_time"),r.getString("status"),r.getString("time_zone"));
        },id).stream().findFirst().orElseThrow(() -> new ApiException("ROUTINE_NOT_FOUND","Rotina não encontrada.",404));
    }
    @Transactional(propagation=Propagation.MANDATORY) public Dates dates(UUID university) {
        return sql.queryForObject("SELECT (now() AT TIME ZONE un.time_zone)::date::text AS today,(date_trunc('month',now() AT TIME ZONE un.time_zone)+interval '1 month - 1 day')::date::text AS end FROM universities un WHERE un.id=?",(r,n) -> new Dates(r.getString("today"),r.getString("end")),university);
    }
    @Transactional(propagation=Propagation.MANDATORY) public Dates dates(String zone,String start) {
        return sql.queryForObject("SELECT (now() AT TIME ZONE ?)::date::text AS today,(date_trunc('month',?::date)+interval '1 month - 1 day')::date::text AS end",(r,n) -> new Dates(r.getString("today"),r.getString("end")),zone,start);
    }
    @Transactional(propagation=Propagation.MANDATORY) public void materialize(UUID routine,String start,String end) {
        sql.update("""
            INSERT INTO rides(university_id,route_id,routine_id,driver_id,vehicle_id,direction,ride_date,departure_time,capacity,price_cents,commission_bps,pricing_version_id,automatic,notes)
            SELECT rt.university_id,rt.route_id,rt.id,rt.driver_id,rt.vehicle_id,direction.name,day::date,direction.departure,rt.capacity,direction.price,p.commission_bps,rt.pricing_version_id,rt.automatic,rt.notes
            FROM routines rt JOIN pricing_versions p ON p.id=rt.pricing_version_id
            CROSS JOIN generate_series(?::date,?::date,interval '1 day') day
            CROSS JOIN LATERAL (VALUES('ida',rt.ida_time,rt.ida_cents),('volta',rt.volta_time,rt.volta_cents)) direction(name,departure,price)
            WHERE rt.id=? AND direction.departure IS NOT NULL AND extract(dow FROM day)::smallint=ANY(rt.weekdays)
            ON CONFLICT(routine_id,ride_date,direction) DO NOTHING
            """,start,end,routine);
    }
    public boolean canRenew(UUID previous,UUID user,UUID routine,String start) {
        return Boolean.TRUE.equals(sql.queryForObject("SELECT EXISTS(SELECT 1 FROM subscriptions WHERE id=? AND passenger_id=? AND routine_id=? AND renew AND status IN ('active','grace','renew_conflict') AND (grace_until IS NULL OR grace_until>now()) AND ?::date>end_date)",Boolean.class,previous,user,routine,start));
    }
    @Transactional(propagation=Propagation.MANDATORY) public void stabilizeCalendar(UUID university) { sql.queryForList("SELECT id FROM universities WHERE id=? FOR SHARE",university); }
    public List<Offer> eligible(RoutineData routine,UUID actor,UUID classId,String start,String end,List<Integer> weekdays,List<String> directions,UUID previous) {
        return sql.execute((java.sql.Connection connection) -> {
            try(var statement=connection.prepareStatement("""
                SELECT r.*,r.ride_date::text AS date,r.departure_time::text AS time,
                r.capacity-(SELECT count(*) FROM ride_reservations rr WHERE rr.ride_id=r.id AND rr.status IN ('confirmed','boarded','awaiting_confirmation'))
                -(SELECT count(*) FROM subscription_schedule sc WHERE sc.ride_id=r.id AND sc.held AND (?::uuid IS NULL OR sc.subscription_id<>?)) AS available,
                EXISTS(SELECT 1 FROM ride_reservations rr WHERE rr.ride_id=r.id AND rr.passenger_id=? AND rr.status IN ('confirmed','boarded','awaiting_confirmation')) AS already_reserved
                FROM rides r WHERE r.routine_id=? AND r.ride_date BETWEEN ?::date AND ?::date AND extract(dow FROM r.ride_date)::smallint=ANY(?::smallint[])
                AND r.direction=ANY(?::text[]) AND (r.ride_date+r.departure_time) AT TIME ZONE ? > now()
                AND NOT EXISTS(SELECT 1 FROM calendar_exclusions c WHERE c.active AND c.university_id=r.university_id AND c.exclusion_date=r.ride_date AND (c.class_id IS NULL OR c.class_id=?))
                ORDER BY r.ride_date,r.departure_time,r.id
                """)) {
                var days=connection.createArrayOf("smallint",weekdays.toArray(Integer[]::new));var dirs=connection.createArrayOf("text",directions.toArray(String[]::new));
                try {
                    statement.setObject(1,previous);statement.setObject(2,previous);statement.setObject(3,actor);statement.setObject(4,routine.id());statement.setString(5,start);statement.setString(6,end);statement.setArray(7,days);statement.setArray(8,dirs);statement.setString(9,routine.zone());statement.setObject(10,classId);
                    try(var r=statement.executeQuery()) { var result=new ArrayList<Offer>();while(r.next()) result.add(new Offer(r.getObject("id",UUID.class),r.getString("date"),r.getString("time").substring(0,5),r.getString("direction"),r.getLong("price_cents"),r.getInt("commission_bps"),r.getString("status"),r.getLong("available"),r.getBoolean("already_reserved")));return result; }
                } finally { days.free();dirs.free(); }
            }
        });
    }
    @Transactional(propagation=Propagation.MANDATORY) public void lockRides(List<UUID> ids) {
        if(ids.isEmpty()) return;
        sql.execute((java.sql.Connection connection) -> { var array=connection.createArrayOf("uuid",ids.toArray(UUID[]::new));try(var statement=connection.prepareStatement("SELECT id FROM rides WHERE id=ANY(?::uuid[]) ORDER BY id FOR UPDATE")) { statement.setArray(1,array);try(var result=statement.executeQuery()) { while(result.next()) {} }return null; } finally { array.free(); } });
    }
    public List<SubscriptionDto.Exclusion> excluded(UUID university,UUID classId,String start,String end) {
        return sql.query("SELECT exclusion_date::text AS date,title FROM calendar_exclusions WHERE active AND university_id=? AND exclusion_date BETWEEN ?::date AND ?::date AND (class_id IS NULL OR class_id=?) ORDER BY exclusion_date,id",(r,n) -> new SubscriptionDto.Exclusion(r.getString("date"),r.getString("title")),university,start,end,classId);
    }
    public List<UUID> waitingToNotify(UUID routine,UUID classId,UUID driver) {
        return sql.query("""
            SELECT w.user_id FROM waitlist w JOIN student_profiles s ON s.user_id=w.user_id JOIN users u ON u.id=w.user_id
            WHERE w.routine_id=? AND w.status='waiting' AND s.notices AND s.class_id=? AND u.account_status='active'
            AND NOT EXISTS(SELECT 1 FROM user_blocks b WHERE b.active AND ((b.by_id=w.user_id AND b.other_id=?) OR (b.by_id=? AND b.other_id=w.user_id)))
            """,(r,n) -> r.getObject("user_id",UUID.class),routine,classId,driver,driver);
    }
}
