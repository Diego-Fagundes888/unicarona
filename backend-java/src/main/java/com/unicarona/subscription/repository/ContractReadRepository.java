package com.unicarona.subscription.repository;

import com.unicarona.shared.ApiDates;
import com.unicarona.shared.ApiException;
import com.unicarona.privacy.service.ParticipantIdentity;
import com.unicarona.subscription.dto.ContractDto;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ContractReadRepository {
    private final JdbcTemplate sql;
    public ContractReadRepository(JdbcTemplate sql) { this.sql=sql; }
    public List<ContractDto.Item> list(UUID actor,String side,int limit,int offset,UUID id) {
        return sql.query("""
            SELECT s.id,s.routine_id,s.driver_id,s.passenger_id,s.start_date::text AS start,s.end_date::text AS end,
              s.weekdays,s.directions,s.total_cents,s.discount_bps,s.status,s.renew,s.grace_until,CASE WHEN u.deleted_at IS NULL AND u.account_status<>'deleted' THEN u.name END AS other_name,
              CASE WHEN privacy_route_details_visible(rt) THEN rt.region ELSE 'Região removida' END AS region,
              (SELECT count(*) FROM ride_reservations rr WHERE rr.subscription_id=s.id AND absence IN ('notified_absence','passenger_absent')) AS absences,
              (SELECT count(*) FROM subscription_schedule sc WHERE sc.subscription_id=s.id AND held) AS holds
            FROM subscriptions s JOIN routines routine ON routine.id=s.routine_id JOIN ride_routes rt ON rt.id=routine.route_id
            JOIN users u ON u.id=CASE WHEN s.passenger_id=? THEN s.driver_id ELSE s.passenger_id END
            WHERE (CASE WHEN ?='driver' THEN s.driver_id ELSE s.passenger_id END=? OR (?::uuid IS NOT NULL AND s.driver_id=?))
              AND (?::uuid IS NULL OR s.id=?) ORDER BY s.created_at DESC,s.id LIMIT ? OFFSET ?
            """,(row,n) -> {
                var days=new ArrayList<Integer>();var array=row.getArray("weekdays");
                try { for(Object day:(Object[])array.getArray()) days.add(((Number)day).intValue()); } finally { array.free(); }
                var grace=row.getTimestamp("grace_until");
                return new ContractDto.Item(row.getObject("id",UUID.class),row.getObject("routine_id",UUID.class),row.getObject("driver_id",UUID.class),row.getObject("passenger_id",UUID.class),
                    row.getString("start"),row.getString("end"),List.copyOf(days),row.getString("directions"),row.getInt("total_cents"),row.getInt("discount_bps")/10000.0,row.getString("status"),row.getBoolean("renew"),
                    ApiDates.timestamp(grace==null?null:grace.toInstant()),row.getLong("absences"),row.getLong("holds"),ParticipantIdentity.label(row.getString("other_name")),row.getString("region"));
            },actor,side,actor,id,actor,id,id,limit,offset);
    }
    public boolean openRights(UUID actor,UUID id) {
        var rows=sql.query("SELECT EXISTS(SELECT 1 FROM subscriptions_with_open_rights live WHERE live.id=s.id) AS open_rights FROM subscriptions s WHERE s.id=? AND (s.driver_id=? OR s.passenger_id=?)",
            (r,n)->r.getBoolean("open_rights"),id,actor,actor);
        ApiException.require(!rows.isEmpty(),"SUBSCRIPTION_NOT_FOUND","Mensalidade não encontrada.",404);return rows.getFirst();
    }
}
