package com.unicarona.reservation.repository;

import com.unicarona.reservation.dto.*;
import com.unicarona.shared.*;
import com.unicarona.privacy.service.ParticipantIdentity;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RequestReadRepository {
    private final JdbcTemplate sql;
    public RequestReadRepository(JdbcTemplate sql) { this.sql=sql; }
    public List<RequestDto.Item> list(UUID actor,RequestQueries input) {
        return sql.query("""
            SELECT q.id,q.type,q.status,q.ride_id,q.routine_id,q.created_at,q.expires_at,
            p.id AS payment_id,p.status AS payment_status,p.amount_cents,u.id AS other_id,CASE WHEN u.deleted_at IS NULL AND u.account_status<>'deleted' THEN u.name END AS other_name,
            CASE WHEN rt.id IS NULL THEN NULL WHEN privacy_route_details_visible(rt) THEN rt.region ELSE 'Região removida' END AS region,r.ride_date::text AS date,r.departure_time::text AS time
            FROM ride_requests q LEFT JOIN payments p ON p.request_id=q.id JOIN users u ON u.id=CASE WHEN q.passenger_id=? THEN q.driver_id ELSE q.passenger_id END
            LEFT JOIN rides r ON r.id=q.ride_id LEFT JOIN ride_routes rt ON rt.id=r.route_id
            WHERE CASE WHEN ?='driver' THEN q.driver_id ELSE q.passenger_id END=? AND (?::text IS NULL OR q.status=?) ORDER BY q.created_at DESC,q.id LIMIT ? OFFSET ?
            """,(r,n) -> {
                int amount=r.getInt("amount_cents");Integer total=r.wasNull()?null:amount;String time=r.getString("time");
                return new RequestDto.Item(r.getObject("id",UUID.class),r.getString("type"),r.getString("status"),r.getObject("ride_id",UUID.class),r.getObject("routine_id",UUID.class),
                    ApiDates.timestamp(r.getTimestamp("created_at").toInstant()),ApiDates.timestamp(r.getTimestamp("expires_at").toInstant()),r.getObject("payment_id",UUID.class),r.getString("payment_status"),total,
                    new RequestDto.Other(r.getObject("other_id",UUID.class),ParticipantIdentity.label(r.getString("other_name"))),r.getString("region"),r.getString("date"),time==null?null:time.substring(0,5));
            },actor,input.side(),actor,input.status(),input.status(),input.page().limit()+1,input.page().offset());
    }
    public RequestDto.Detail detail(UUID actor,UUID id) {
        var header=sql.query("""
            SELECT q.*,p.id AS payment_id,p.status AS payment_status,p.amount_cents,CASE WHEN u.deleted_at IS NULL AND u.account_status<>'deleted' THEN u.name END AS other_name FROM ride_requests q
            LEFT JOIN payments p ON p.request_id=q.id JOIN users u ON u.id=CASE WHEN q.passenger_id=? THEN q.driver_id ELSE q.passenger_id END
            WHERE q.id=? AND (q.passenger_id=? OR q.driver_id=?)
            """,(r,n) -> {
                int amount=r.getInt("amount_cents");boolean absent=r.wasNull();return new Header(r.getObject("id",UUID.class),r.getString("type"),r.getString("status"),r.getObject("driver_id",UUID.class),r.getObject("passenger_id",UUID.class),r.getObject("routine_id",UUID.class),r.getObject("ride_id",UUID.class),r.getObject("payment_id",UUID.class),r.getString("payment_status"),absent?null:amount,ParticipantIdentity.label(r.getString("other_name")),ApiDates.timestamp(r.getTimestamp("expires_at").toInstant()));
            },actor,id,actor,actor).stream().findFirst().orElseThrow(() -> new ApiException("REQUEST_NOT_FOUND","Solicitação não encontrada.",404));
        var lines=sql.query("SELECT l.ride_id,r.ride_date::text AS date,r.departure_time::text AS time,r.direction,l.cents FROM request_lines l JOIN rides r ON r.id=l.ride_id WHERE l.request_id=? ORDER BY r.ride_date,r.departure_time,r.id LIMIT 62",
            (r,n) -> new RequestDto.Line(r.getObject("ride_id",UUID.class),r.getString("date"),r.getString("time").substring(0,5),r.getString("direction"),r.getInt("cents")),id);
        return new RequestDto.Detail(header.id(),header.type(),header.status(),header.driver(),header.passenger(),header.routine(),header.ride(),header.payment(),header.paymentStatus(),header.amount()==null?lines.stream().mapToLong(RequestDto.Line::cents).sum():header.amount(),header.otherName(),header.expiresAt(),lines);
    }
    private record Header(UUID id,String type,String status,UUID driver,UUID passenger,UUID routine,UUID ride,UUID payment,String paymentStatus,Integer amount,String otherName,String expiresAt) {}
}
