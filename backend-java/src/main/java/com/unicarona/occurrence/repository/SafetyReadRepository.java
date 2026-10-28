package com.unicarona.occurrence.repository;

import com.unicarona.occurrence.dto.SafetyDto;
import com.unicarona.shared.*;
import com.unicarona.privacy.service.ParticipantIdentity;
import com.unicarona.shared.dto.PageDto;
import java.sql.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Flat DTO projections; a detail page loads related cases in one additional query. */
@Repository
public class SafetyReadRepository {
    private final JdbcTemplate sql;
    public SafetyReadRepository(JdbcTemplate sql) { this.sql=sql; }
    private static final String CASE_FIELDS="""
        SELECT o.id,o.kind,o.reason,o.severe,o.status,o.outcome,o.decision,o.created_at,o.reviewed_at,o.reservation_id,
        reporter.id AS reporter_id,reporter.name AS reporter_name,other_user.id AS other_id,other_user.name AS other_name,
        (SELECT count(*) FROM occurrence_parcels op WHERE op.occurrence_id=o.id) AS affected
        FROM occurrences o JOIN users reporter ON reporter.id=o.reporter_id LEFT JOIN users other_user ON other_user.id=o.other_id
        """;
    public PageDto<SafetyDto.Item> list(UUID actor,boolean admin,int limit,int offset) {
        var rows=sql.query(CASE_FIELDS+" WHERE (? AND o.status IN ('pending','financial_pending')) OR (NOT ? AND o.reporter_id=?) ORDER BY o.created_at DESC,o.id LIMIT ? OFFSET ?",
            (r,n) -> item(r,admin),admin,admin,actor,limit+1,offset);return PageDto.from(rows,limit,offset);
    }
    public PageDto<SafetyDto.PersonalBlock> blocks(UUID actor,int limit,int offset) {
        var rows=sql.query("SELECT b.id,b.other_id,CASE WHEN u.deleted_at IS NULL AND u.account_status<>'deleted' THEN u.name END AS name,b.created_at FROM user_blocks b JOIN users u ON u.id=b.other_id WHERE b.by_id=? AND b.active ORDER BY b.created_at DESC,b.id LIMIT ? OFFSET ?",
            (r,n) -> new SafetyDto.PersonalBlock(r.getObject("id",UUID.class),r.getObject("other_id",UUID.class),ParticipantIdentity.label(r.getString("name")),timestamp(r,"created_at")),actor,limit+1,offset);return PageDto.from(rows,limit,offset);
    }
    private record ParcelRow(UUID id,UUID reservation,int cents,String status,int fee,int net,String reason,String date,String time,String direction,
        String rideStatus,String reservationStatus,String availability,boolean monthly,String passenger,String driver) {
        SafetyDto.Parcel dto(List<SafetyDto.Related> related) { return new SafetyDto.Parcel(id,reservation,cents,status,fee,net,reason,date,time,direction,rideStatus,reservationStatus,availability,monthly,passenger,driver,related); }
    }
    public SafetyDto.Detail detail(UUID id,int limit,int offset) {
        var report=sql.query(CASE_FIELDS+" WHERE o.id=?",(r,n) -> item(r,true),id).stream().findFirst().orElseThrow(() -> new ApiException("OCCURRENCE_NOT_FOUND","Ocorrência não encontrada.",404));
        var rows=sql.query("""
            SELECT p.id,p.reservation_id,p.cents,p.status,p.commission_cents,p.driver_cents,p.reason,
            rr.availability,rr.subscription_id,rr.status AS reservation_status,r.ride_date::text AS date,r.departure_time::text AS time,r.direction,r.status AS ride_status,
            passenger.name AS passenger_name,driver.name AS driver_name FROM occurrence_parcels op JOIN parcels p ON p.id=op.parcel_id
            JOIN ride_reservations rr ON rr.id=p.reservation_id JOIN rides r ON r.id=rr.ride_id
            JOIN users passenger ON passenger.id=p.passenger_id JOIN users driver ON driver.id=p.driver_id
            WHERE op.occurrence_id=? ORDER BY r.ride_date,r.departure_time,p.id LIMIT ? OFFSET ?
            """,(r,n) -> new ParcelRow(r.getObject("id",UUID.class),r.getObject("reservation_id",UUID.class),r.getInt("cents"),r.getString("status"),r.getInt("commission_cents"),r.getInt("driver_cents"),r.getString("reason"),r.getString("date"),r.getString("time").substring(0,5),r.getString("direction"),r.getString("ride_status"),r.getString("reservation_status"),r.getString("availability"),r.getObject("subscription_id")!=null,r.getString("passenger_name"),r.getString("driver_name")),id,limit+1,offset);
        var page=rows.stream().limit(limit).toList();var related=new HashMap<UUID,List<SafetyDto.Related>>();
        if(!page.isEmpty()) sql.query("SELECT op.parcel_id,o.id,o.kind,o.status,o.reason,o.outcome,o.decision,o.reviewed_at FROM occurrence_parcels op JOIN occurrences o ON o.id=op.occurrence_id WHERE op.parcel_id IN ("+String.join(",",Collections.nCopies(page.size(),"?"))+") ORDER BY o.created_at,o.id",
            r -> { related.computeIfAbsent(r.getObject("parcel_id",UUID.class),ignored -> new ArrayList<>()).add(new SafetyDto.Related(r.getObject("id",UUID.class),r.getString("kind"),r.getString("status"),r.getString("reason"),r.getString("outcome"),r.getString("decision"),timestamp(r,"reviewed_at"))); },page.stream().map(ParcelRow::id).toArray());
        return new SafetyDto.Detail(report.id(),report.kind(),report.reason(),report.status(),report.severe(),report.outcome(),report.decision(),report.participants(),page.stream().map(p -> p.dto(List.copyOf(related.getOrDefault(p.id(),List.of())))).toList(),rows.size()>limit?offset+limit:null);
    }
    private static SafetyDto.Item item(ResultSet r,boolean admin) throws SQLException {
        UUID other=r.getObject("other_id",UUID.class);var participants=admin?new SafetyDto.Participants(new SafetyDto.Person(r.getObject("reporter_id",UUID.class),r.getString("reporter_name")),other==null?null:new SafetyDto.Person(other,r.getString("other_name"))):null;
        return new SafetyDto.Item(r.getObject("id",UUID.class),r.getString("kind"),r.getString("reason"),r.getBoolean("severe"),r.getString("status"),r.getString("outcome"),r.getString("decision"),timestamp(r,"created_at"),timestamp(r,"reviewed_at"),r.getObject("reservation_id",UUID.class),r.getLong("affected"),participants);
    }
    private static String timestamp(ResultSet r,String name) throws SQLException { var value=r.getTimestamp(name);return ApiDates.timestamp(value==null?null:value.toInstant()); }
}
