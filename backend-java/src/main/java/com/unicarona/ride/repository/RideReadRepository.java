package com.unicarona.ride.repository;

import com.unicarona.ride.dto.*;
import com.unicarona.privacy.service.ParticipantAccessSql;
import com.unicarona.privacy.service.ParticipantIdentity;
import com.unicarona.shared.ApiException;
import java.sql.*;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;

@Repository
public class RideReadRepository {
    private static final String SELECT="""
        SELECT r.id,r.driver_id,r.routine_id,r.university_id,r.direction,r.capacity,r.price_cents,r.commission_bps,r.automatic,r.toll_cents,r.status,r.version,r.proposed_time,
        CASE WHEN privacy_ride_details_visible(r) THEN r.notes ELSE '' END AS notes,r.ride_date::text AS date,r.departure_time::text AS time,
        CASE WHEN privacy_route_details_visible(rt) THEN rt.region ELSE 'Região removida' END AS region,
        CASE WHEN privacy_route_details_visible(rt) THEN rt.meeting ELSE 'Detalhes removidos' END AS meeting,rt.distance_m,un.name AS university,CASE WHEN u.deleted_at IS NULL AND u.account_status<>'deleted' THEN u.name END AS driver_name,
        v.model,v.color,v.plate,CASE WHEN r.driver_id=viewer.id THEN c.name END AS course,
        CASE WHEN r.driver_id=viewer.id THEN cl.period_number END AS period_number,
        CASE WHEN r.driver_id=viewer.id THEN cl.label END AS class_label,(r.driver_id=viewer.id) AS own_academic,
        coalesce(d.status,'not_requested') AS driver_status,u.account_status,
        r.capacity-(SELECT count(*) FROM ride_reservations rr WHERE rr.ride_id=r.id AND rr.status IN ('confirmed','boarded','awaiting_confirmation'))
        -(SELECT count(*) FROM subscription_schedule sc WHERE sc.ride_id=r.id AND sc.held) AS available
        FROM rides r JOIN ride_routes rt ON rt.id=r.route_id JOIN universities un ON un.id=r.university_id
        JOIN users u ON u.id=r.driver_id JOIN vehicles v ON v.id=r.vehicle_id LEFT JOIN student_profiles s ON s.user_id=u.id
        LEFT JOIN courses c ON c.id=s.course_id LEFT JOIN classes cl ON cl.id=s.class_id LEFT JOIN driver_profiles d ON d.user_id=u.id
        CROSS JOIN (SELECT ?::uuid AS id) viewer
        """;
    public record Snapshot(RideDto.Item item,String fullPlate,String accountStatus,String driverStatus,String meeting,String notes,String ownerName) {
        @Override public String toString() { return "RideSnapshot[redacted]"; }
        public RideDto.Item full() {
            return withDetails(fullPlate,true,false);
        }
        public RideDto.Item own() { return withDetails(fullPlate,true,true); }
        public RideDto.Item beforePayment() { return withDetails("Disponível após confirmação",true,false); }
        public RideDto.Item protectedHistory() { return withDetails("Placa protegida",false,false); }
        private RideDto.Item withDetails(String plate,boolean operational,boolean owner) {
            var i=item;var d=i.driver();
            var identity=new RideDto.Driver(d.id(),owner?ownerName:d.name(),owner?d.course():"",owner?d.periodStatus():"protected",owner?d.periodNumber():null,owner?d.classLabel():"");
            return new RideDto.Item(i.id(),i.driverId(),i.routineId(),i.direction(),i.date(),i.time(),i.universityId(),i.region(),i.origin(),i.destination(),operational?meeting:"Detalhes do encontro protegidos",i.capacity(),i.available(),i.priceCents(),i.commission(),i.automatic(),i.distance(),i.toll(),i.status(),operational?notes:"",i.version(),i.proposedTime(),new RideDto.Vehicle(i.vehicle().model(),i.vehicle().color(),plate,i.capacity()),identity);
        }
    }
    private final JdbcTemplate sql;
    public RideReadRepository(JdbcTemplate sql) { this.sql=sql; }
    public Optional<UUID> university(UUID user) { return sql.query("SELECT university_id FROM student_profiles WHERE user_id=?",(r,n) -> r.getObject(1,UUID.class),user).stream().findFirst(); }
    public boolean blocked(UUID a,UUID b) { return Boolean.TRUE.equals(sql.queryForObject("SELECT EXISTS(SELECT 1 FROM user_blocks WHERE active AND ((by_id=? AND other_id=?) OR (by_id=? AND other_id=?)))",Boolean.class,a,b,b,a)); }
    public boolean linked(UUID ride,UUID user) { return Boolean.TRUE.equals(sql.queryForObject("SELECT EXISTS(SELECT 1 FROM ride_reservations WHERE ride_id=? AND passenger_id=? AND status IN ('confirmed','boarded','awaiting_confirmation','completed'))",Boolean.class,ride,user)); }
    public boolean operational(UUID ride,UUID user) {
        return Boolean.TRUE.equals(sql.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM ride_reservations rr JOIN rides r ON r.id=rr.ride_id
            JOIN universities un ON un.id=r.university_id JOIN users u ON u.id=rr.driver_id
            WHERE rr.ride_id=? AND rr.passenger_id=? AND ("""+ParticipantAccessSql.OPERATIONAL+"""
            )
            AND NOT EXISTS(SELECT 1 FROM user_blocks b WHERE b.active AND
              ((b.by_id=rr.driver_id AND b.other_id=rr.passenger_id) OR (b.by_id=rr.passenger_id AND b.other_id=rr.driver_id))))
            """,Boolean.class,ride,user));
    }
    public boolean acceptedRequest(UUID ride,UUID user) {
        return Boolean.TRUE.equals(sql.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM ride_requests q JOIN payments p ON p.request_id=q.id
              JOIN request_lines l ON l.request_id=q.id JOIN rides r ON r.id=l.ride_id JOIN users u ON u.id=q.driver_id
              WHERE l.ride_id=? AND q.passenger_id=? AND q.status='accepted' AND q.expires_at>now()
              AND p.status IN ('awaiting','pending') AND r.status='planned' AND u.deleted_at IS NULL AND u.account_status<>'deleted'
              AND NOT EXISTS(SELECT 1 FROM user_blocks b WHERE b.active AND
                ((b.by_id=q.passenger_id AND b.other_id=q.driver_id) OR (b.by_id=q.driver_id AND b.other_id=q.passenger_id))))
            """,Boolean.class,ride,user));
    }
    public Snapshot detail(UUID actor,UUID id) { return sql.query(SELECT+" WHERE r.id=?",(r,n) -> snapshot(r),actor,id).stream().findFirst().orElseThrow(() -> new ApiException("RIDE_NOT_FOUND","Carona não encontrada.",404)); }
    public List<RideDto.Item> search(UUID user,UUID university,RideQueries.Search input) {
        // Retain the legacy semantics: maxPrice=0 and automatic=false do not filter offers.
        int max=input.maxPrice()==null || input.maxPrice()==0?Integer.MAX_VALUE:input.maxPrice();
        return sql.query("WITH offers AS ("+SELECT+"""
            WHERE r.university_id=? AND r.driver_id<>? AND r.ride_date=?::date AND r.direction=?
            AND r.status='planned' AND u.account_status='active' AND d.status='approved' AND v.status='approved'
            AND (r.ride_date+r.departure_time) AT TIME ZONE un.time_zone>now()
            AND (?='' OR normalized_text(rt.region) LIKE '%'||normalized_text(?)||'%') AND r.departure_time>=?::time
            AND r.price_cents<=? AND (NOT ? OR r.automatic) AND (?::uuid IS NULL OR r.driver_id=?)
            AND NOT EXISTS(SELECT 1 FROM user_blocks b WHERE b.active AND ((b.by_id=? AND b.other_id=r.driver_id) OR (b.by_id=r.driver_id AND b.other_id=?))))
            SELECT * FROM offers WHERE NOT ? OR available>0 ORDER BY time,id LIMIT ? OFFSET ?
            """,(r,n) -> snapshot(r).item(),user,university,user,input.date(),input.direction(),input.region()==null?"":input.region(),input.region()==null?"":input.region(),input.time()==null?"00:00":input.time(),max,input.automatic(),input.driverId(),input.driverId()==null?null:UUID.fromString(input.driverId()),user,user,input.onlyAvailable(),input.page().limit()+1,input.page().offset());
    }
    public List<RideDto.Item> mine(UUID user,int limit,int offset,boolean upcoming) {
        return sql.query(SELECT+"""
            WHERE r.driver_id=? AND (NOT ? OR r.status='active' OR (r.status='planned' AND (r.ride_date+r.departure_time) AT TIME ZONE un.time_zone>now()))
            ORDER BY CASE WHEN ? AND r.status='active' THEN 0 ELSE 1 END,
            CASE WHEN ? THEN r.ride_date END ASC,CASE WHEN ? THEN r.departure_time END ASC,
            CASE WHEN NOT ? THEN r.ride_date END DESC,CASE WHEN NOT ? THEN r.departure_time END DESC,r.id LIMIT ? OFFSET ?
            """,(r,n) -> snapshot(r).own(),user,user,upcoming,upcoming,upcoming,upcoming,upcoming,upcoming,limit,offset);
    }
    private static Snapshot snapshot(ResultSet r) throws SQLException {
        UUID driver=r.getObject("driver_id",UUID.class),routine=r.getObject("routine_id",UUID.class);boolean ida="ida".equals(r.getString("direction"));
        int period=r.getInt("period_number");Integer periodNumber=r.wasNull()?null:period;
        String label=r.getString("class_label"),proposed=r.getString("proposed_time");
        var item=new RideDto.Item(r.getObject("id",UUID.class),driver,routine==null?"":routine.toString(),r.getString("direction"),r.getString("date"),r.getString("time").substring(0,5),
            r.getObject("university_id",UUID.class),r.getString("region"),r.getString(ida?"region":"university"),r.getString(ida?"university":"region"),"Disponível após confirmação",r.getInt("capacity"),r.getLong("available"),
            r.getInt("price_cents"),r.getInt("commission_bps")/10000.0,r.getBoolean("automatic"),r.getInt("distance_m")/1000.0,r.getInt("toll_cents")/100.0,r.getString("status"),"",r.getInt("version"),proposed==null?"":proposed.substring(0,5),
            new RideDto.Vehicle(r.getString("model"),r.getString("color"),"Disponível após confirmação",r.getInt("capacity")),
            new RideDto.Driver(driver,ParticipantIdentity.label(r.getString("driver_name")),r.getBoolean("own_academic")?r.getString("course"):"",r.getBoolean("own_academic")?(periodNumber==null || periodNumber==0?"undefined":"numbered"):"protected",periodNumber,label==null?"":label));
        return new Snapshot(item,r.getString("plate"),r.getString("account_status"),r.getString("driver_status"),r.getString("meeting"),r.getString("notes"),r.getString("driver_name"));
    }
}
