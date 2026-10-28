package com.unicarona.payment.repository;

import com.unicarona.reservation.entity.RideRequest;
import com.unicarona.shared.repository.ActorsRepository;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

@Repository public class PaymentEligibilityRepository {
    private final JdbcTemplate sql;
    public PaymentEligibilityRepository(JdbcTemplate sql) { this.sql=sql; }
    public boolean purchasesAllowed(UUID owner) { return sql.queryForObject("SELECT coalesce(sum(e.cents),0)::bigint FROM ledger_accounts a JOIN ledger_entries e ON e.account_id=a.id WHERE a.owner_id=? AND a.kind='passenger_receivable'",Long.class,owner)>=0; }
    public boolean creditSafe(UUID owner) {return !Boolean.TRUE.equals(sql.queryForObject("SELECT EXISTS(SELECT 1 FROM mercado_pago_orders m JOIN payments p ON p.id=m.payment_id WHERE p.passenger_id=? AND (m.untracked_refunds OR m.chargeback_review))",Boolean.class,owner));}
    @Transactional(propagation=Propagation.MANDATORY) public boolean monthly(RideRequest request,List<ActorsRepository.Person> people) {
        if(!"mensal".equals(request.getType())) return true;
        var routine=sql.queryForList("SELECT status,class_id FROM routines WHERE id=?",request.getRoutineId());
        var preview=request.getPreview();String classId=preview.path("classId").asText();
        if(routine.isEmpty() || !ActorsRepository.sameClass(people.getFirst(),people.getLast()) || !people.getFirst().classId().toString().equals(classId) || !classId.equals(String.valueOf(routine.getFirst().get("class_id"))) || !"active".equals(routine.getFirst().get("status"))) return false;
        UUID university=people.stream().filter(p -> p.id().equals(request.getPassengerId())).findFirst().orElseThrow().universityId();sql.queryForList("SELECT id FROM universities WHERE id=? FOR SHARE",university);
        boolean excluded=Boolean.TRUE.equals(sql.queryForObject("SELECT EXISTS(SELECT 1 FROM request_lines l JOIN rides r ON r.id=l.ride_id JOIN calendar_exclusions e ON e.university_id=r.university_id AND e.exclusion_date=r.ride_date AND e.active AND (e.class_id IS NULL OR e.class_id=?::uuid) WHERE l.request_id=?)",Boolean.class,classId,request.getId()));
        if(excluded) return false;
        var days=new ArrayList<Integer>();preview.path("weekdays").forEach(d -> days.add(d.asInt()));String direction=preview.path("directions").asText();var dirs="ambos".equals(direction)?List.of("ida","volta"):List.of(direction);
        boolean missing=Boolean.TRUE.equals(sql.execute((java.sql.Connection connection) -> {
            var weekdays=connection.createArrayOf("smallint",days.toArray(Integer[]::new));var directions=connection.createArrayOf("text",dirs.toArray(String[]::new));
            try(var stmt=connection.prepareStatement("""
                SELECT EXISTS(SELECT 1 FROM rides r JOIN universities un ON un.id=r.university_id
                WHERE r.routine_id=? AND r.ride_date BETWEEN ?::date AND ?::date AND extract(dow FROM r.ride_date)::smallint=ANY(?::smallint[])
                AND r.direction=ANY(?::text[]) AND (r.ride_date+r.departure_time) AT TIME ZONE un.time_zone>now()
                AND NOT EXISTS(SELECT 1 FROM calendar_exclusions e WHERE e.active AND e.university_id=r.university_id AND e.exclusion_date=r.ride_date AND (e.class_id IS NULL OR e.class_id=?::uuid))
                AND NOT EXISTS(SELECT 1 FROM request_lines l WHERE l.request_id=? AND l.ride_id=r.id))
                """)) {
                stmt.setObject(1,request.getRoutineId());stmt.setString(2,preview.path("startDate").asText());stmt.setString(3,preview.path("endDate").asText());stmt.setArray(4,weekdays);stmt.setArray(5,directions);stmt.setString(6,classId);stmt.setObject(7,request.getId());try(var result=stmt.executeQuery()) { result.next();return result.getBoolean(1); }
            } finally { weekdays.free();directions.free(); }
        }));
        if(missing) return false;
        if(request.getPreviousSubscriptionId()!=null) return sql.query("SELECT renew AND status IN ('active','grace','renew_conflict') AND (grace_until IS NULL OR grace_until>now()) AND passenger_id=? AND routine_id=? AS eligible FROM subscriptions WHERE id=? FOR UPDATE",(r,n) -> r.getBoolean("eligible"),request.getPassengerId(),request.getRoutineId(),request.getPreviousSubscriptionId()).stream().findFirst().orElse(false);
        return true;
    }
}
