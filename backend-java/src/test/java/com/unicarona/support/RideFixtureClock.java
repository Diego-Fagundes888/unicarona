package com.unicarona.support;

import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Move a chosen synthetic leg to today without violating one routine/direction per day. */
public final class RideFixtureClock {
    private RideFixtureClock() {}
    public static void today(JdbcTemplate sql,UUID ride) {
        move(sql,ride,null);
    }
    public static void onDate(JdbcTemplate sql,UUID ride,LocalDate day) {
        move(sql,ride,Objects.requireNonNull(day));
    }
    private static void move(JdbcTemplate sql,UUID ride,LocalDate day) {
        var tx=new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(sql.getDataSource())));
        tx.executeWithoutResult(status -> {
            var target=sql.queryForMap("SELECT r.routine_id,r.direction,r.ride_date,(now() AT TIME ZONE un.time_zone)::date AS today FROM rides r JOIN universities un ON un.id=r.university_id WHERE r.id=? FOR UPDATE OF r",ride);
            LocalDate old=((java.sql.Date)target.get("ride_date")).toLocalDate(),today=day==null?((java.sql.Date)target.get("today")).toLocalDate():day;
            if(old.equals(today))return;
            var collisions=target.get("routine_id")==null?List.<UUID>of():sql.query("SELECT id FROM rides WHERE routine_id=? AND direction=? AND ride_date=? AND id<>? FOR UPDATE",(row,n)->row.getObject("id",UUID.class),target.get("routine_id"),target.get("direction"),today,ride);
            if(!collisions.isEmpty()) {
                LocalDate parking=sql.queryForObject("SELECT max(ride_date)+1 FROM rides WHERE routine_id=? AND direction=?",LocalDate.class,target.get("routine_id"),target.get("direction"));
                sql.update("UPDATE rides SET ride_date=? WHERE id=?",parking,collisions.getFirst());
            }
            sql.update("UPDATE rides SET ride_date=? WHERE id=?",today,ride);
            if(!collisions.isEmpty())sql.update("UPDATE rides SET ride_date=? WHERE id=?",old,collisions.getFirst());
        });
    }
}
