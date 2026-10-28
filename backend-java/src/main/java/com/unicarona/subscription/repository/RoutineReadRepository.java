package com.unicarona.subscription.repository;

import com.unicarona.ride.dto.RideDto;
import com.unicarona.subscription.dto.*;
import com.unicarona.shared.ApiException;
import com.unicarona.privacy.service.ParticipantAccessSql;
import com.unicarona.privacy.service.ParticipantIdentity;
import java.sql.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RoutineReadRepository {
    private static final String ACCEPTED="""
        EXISTS(SELECT 1 FROM ride_requests q JOIN payments p ON p.request_id=q.id
          JOIN request_lines l ON l.request_id=q.id JOIN rides r ON r.id=l.ride_id
          WHERE q.routine_id=rt.id AND q.passenger_id=viewer.id AND q.driver_id=rt.driver_id
          AND q.status='accepted' AND q.expires_at>now() AND p.status IN ('awaiting','pending')
          AND r.status='planned' AND r.route_id=rt.route_id AND u.deleted_at IS NULL AND u.account_status<>'deleted'
          AND NOT EXISTS(SELECT 1 FROM user_blocks b WHERE b.active AND
            ((b.by_id=viewer.id AND b.other_id=u.id) OR (b.by_id=u.id AND b.other_id=viewer.id))))
        """;
    private static final String COORDINATE="""
        EXISTS(SELECT 1 FROM ride_reservations rr JOIN rides r ON r.id=rr.ride_id
          JOIN universities un ON un.id=r.university_id
          WHERE rr.passenger_id=viewer.id AND rr.driver_id=rt.driver_id AND r.routine_id=rt.id AND r.route_id=rt.route_id
          AND ("""+ParticipantAccessSql.OPERATIONAL+"""
          ) AND NOT EXISTS(SELECT 1 FROM user_blocks b WHERE b.active AND
             ((b.by_id=viewer.id AND b.other_id=u.id) OR (b.by_id=u.id AND b.other_id=viewer.id))))
        """;
    private static final String CATALOG="""
        mine.university_id=rt.university_id AND rt.status='active' AND u.account_status='active'
        AND u.deleted_at IS NULL AND EXISTS(SELECT 1 FROM driver_profiles d WHERE d.user_id=u.id AND d.status='approved')
        AND NOT EXISTS(SELECT 1 FROM user_blocks b WHERE b.active AND
            ((b.by_id=viewer.id AND b.other_id=u.id) OR (b.by_id=u.id AND b.other_id=viewer.id)))
        """;
    private static final String SELECT="""
        SELECT rt.id,rt.driver_id,rt.weekdays,rt.ida_time,rt.volta_time,rt.ida_cents,rt.volta_cents,rt.capacity,rt.automatic,rt.status,
        CASE WHEN privacy_routine_details_visible(rt) THEN rt.notes ELSE '' END AS notes,
        CASE WHEN privacy_route_details_visible(rr) THEN rr.region ELSE 'Região removida' END AS region,
        CASE WHEN privacy_route_details_visible(rr) THEN rr.meeting ELSE 'Detalhes removidos' END AS meeting,rr.distance_m,CASE WHEN u.deleted_at IS NULL AND u.account_status<>'deleted' THEN u.name END AS name,
        CASE WHEN rt.driver_id=viewer.id THEN cl.period_number END AS period_number,
        CASE WHEN rt.driver_id=viewer.id THEN cl.label END AS class_label,
        CASE WHEN rt.driver_id=viewer.id THEN c.name END AS course,
        ("""+COORDINATE+" OR "+ACCEPTED+"""
        ) AS coordinating,
        (rt.class_id=mine.class_id) AS compatible
        FROM routines rt JOIN ride_routes rr ON rr.id=rt.route_id JOIN users u ON u.id=rt.driver_id JOIN classes cl ON cl.id=rt.class_id
        JOIN courses c ON c.id=cl.course_id CROSS JOIN (SELECT ?::uuid AS id) viewer LEFT JOIN student_profiles mine ON mine.user_id=viewer.id
        """;
    private final JdbcTemplate sql;
    public RoutineReadRepository(JdbcTemplate sql) { this.sql=sql; }
    public SubscriptionDto.Routine detail(UUID user,UUID id) {
        return sql.query(SELECT+"""
            WHERE rt.id=? AND (rt.driver_id=? OR ("""+COORDINATE+") OR ("+ACCEPTED+") OR ("+CATALOG+"))",
            (r,n) -> dto(r,user,false),user,id,user).stream().findFirst().orElseThrow(() -> new ApiException("ROUTINE_NOT_FOUND","Rotina não encontrada.",404));
    }
    public List<SubscriptionDto.Routine> mine(UUID user,int limit,int offset) {
        return sql.query(SELECT+" WHERE rt.driver_id=? ORDER BY rt.created_at DESC,rt.id LIMIT ? OFFSET ?",(r,n) -> dto(r,user,true),user,user,limit,offset);
    }
    public List<SubscriptionDto.Routine> search(UUID user,RoutineQueries.Search input) {
        ApiException.require(Boolean.TRUE.equals(sql.queryForObject("SELECT EXISTS(SELECT 1 FROM student_profiles WHERE user_id=?)",Boolean.class,user)),"PROFILE_REQUIRED","Complete seu perfil.",422);
        return sql.query(SELECT+"""
            JOIN driver_profiles d ON d.user_id=u.id
            WHERE rt.university_id=mine.university_id AND rt.driver_id<>? AND rt.status='active' AND u.account_status='active' AND d.status='approved'
            AND (?='' OR normalized_text(rr.region) LIKE '%'||normalized_text(?)||'%')
            AND (NOT ? OR rt.class_id=mine.class_id) AND CASE WHEN ?='volta' THEN rt.volta_time ELSE rt.ida_time END>=?::time
            AND NOT EXISTS(SELECT 1 FROM user_blocks b WHERE b.active AND ((b.by_id=? AND b.other_id=rt.driver_id) OR (b.by_id=rt.driver_id AND b.other_id=?)))
            ORDER BY compatible DESC NULLS LAST,rt.id LIMIT ? OFFSET ?
            """,(r,n) -> dto(r,user,false),user,user,input.region()==null?"":input.region(),input.region()==null?"":input.region(),input.compatibleOnly(),input.direction()==null?"ida":input.direction(),input.time()==null?"00:00":input.time(),user,user,input.page().limit()+1,input.page().offset());
    }
    private static SubscriptionDto.Routine dto(ResultSet r,UUID actor,boolean mine) throws SQLException {
        var times=new LinkedHashMap<String,String>();var prices=new LinkedHashMap<String,Integer>();
        for(String direction:List.of("ida","volta")) {
            String time=r.getString(direction+"_time");if(time!=null) times.put(direction,time.substring(0,5));int price=r.getInt(direction+"_cents");if(!r.wasNull() && price!=0) prices.put(direction,price);
        }
        UUID driver=r.getObject("driver_id",UUID.class);var weekdays=new ArrayList<Integer>();var array=r.getArray("weekdays");
        try { for(Object value:(Object[])array.getArray()) weekdays.add(((Number)value).intValue()); } finally { array.free(); }
        double distance=r.getInt("distance_m")/1000.0;
        boolean own=mine||driver.equals(actor),operational=own||r.getBoolean("coordinating");
        return new SubscriptionDto.Routine(r.getObject("id",UUID.class),driver,r.getString("region"),operational?r.getString("meeting"):"Disponível após confirmação",List.copyOf(weekdays),times,prices,new SubscriptionDto.Distances(distance,distance),r.getInt("capacity"),r.getBoolean("automatic"),r.getString("status"),operational?r.getString("notes"):"",own || r.getBoolean("compatible"),
            new RideDto.Driver(driver,own?r.getString("name"):ParticipantIdentity.label(r.getString("name")),own?r.getString("course"):"",own?"numbered":"protected",own?r.getInt("period_number"):null,own?r.getString("class_label"):""));
    }
}
