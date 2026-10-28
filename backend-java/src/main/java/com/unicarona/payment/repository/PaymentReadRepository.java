package com.unicarona.payment.repository;

import com.unicarona.payment.dto.PaymentDto;
import com.unicarona.shared.*;
import com.unicarona.privacy.service.ParticipantIdentity;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository public class PaymentReadRepository {
    public record Reference(UUID request,UUID passenger) {}
    private final JdbcTemplate sql;
    public PaymentReadRepository(JdbcTemplate sql) { this.sql=sql; }
    public Reference reference(UUID id) {
        return sql.query("SELECT request_id,passenger_id FROM payments WHERE id=?",(r,n) -> new Reference(r.getObject(1,UUID.class),r.getObject(2,UUID.class)),id).stream().findFirst().orElseThrow(() -> new ApiException("PAYMENT_NOT_FOUND","Cobrança não encontrada.",404));
    }
    public UUID ownRequest(UUID id,UUID passenger) {
        return sql.query("SELECT request_id FROM payments WHERE id=? AND passenger_id=?",(r,n) -> r.getObject(1,UUID.class),id,passenger).stream().findFirst().orElseThrow(() -> new ApiException("PAYMENT_NOT_FOUND","Pagamento não encontrado.",404));
    }
    public PaymentDto.Detail detail(UUID id,UUID passenger) {
        return sql.query("SELECT p.*,q.status AS request_status FROM payments p JOIN ride_requests q ON q.id=p.request_id WHERE p.id=? AND p.passenger_id=?",(r,n) -> new PaymentDto.Detail(r.getObject("id",UUID.class),r.getString("status"),r.getInt("amount_cents"),r.getInt("credit_cents"),r.getBoolean("credit_requested"),r.getInt("external_cents"),r.getString("method"),ApiDates.timestamp(r.getTimestamp("expires_at").toInstant()),"confirmed".equals(r.getString("request_status")),r.getString("checkout_url")==null?null:new PaymentDto.Checkout(r.getString("checkout_url"),r.getString("provider_reference"),r.getString("gateway_environment"))),id,passenger).stream().findFirst().orElseThrow(() -> new ApiException("PAYMENT_NOT_FOUND","Pagamento não encontrado.",404));
    }
    public boolean valid(UUID id) { return Boolean.TRUE.equals(sql.queryForObject("SELECT expires_at>now() FROM payments WHERE id=?",Boolean.class,id)); }
    public PaymentDto.Summary summary(UUID id,UUID passenger) {
        var rows=sql.queryForList("SELECT p.request_id,p.amount_cents,q.type,q.preview->>'originalCents' AS original_cents,CASE WHEN u.deleted_at IS NULL AND u.account_status<>'deleted' THEN u.name END AS driver_name FROM payments p JOIN ride_requests q ON q.id=p.request_id JOIN users u ON u.id=q.driver_id WHERE p.id=? AND p.passenger_id=?",id,passenger);
        ApiException.require(!rows.isEmpty(),"PAYMENT_NOT_FOUND","Pagamento não encontrado.",404);var row=rows.getFirst();
        long total=((Number)row.get("amount_cents")).longValue();long original=row.get("original_cents")==null?total:Long.parseLong((String)row.get("original_cents"));
        var lines=sql.queryForList("SELECT cents,commission_bps FROM request_lines WHERE request_id=?",row.get("request_id"));
        var pricing=new com.unicarona.payment.service.PricingCalculator();
        long platform=lines.stream().mapToLong(line -> pricing.commission(((Number)line.get("cents")).longValue(),((Number)line.get("commission_bps")).intValue())).sum();
        var trips=trips(id,passenger);
        return new PaymentDto.Summary((String)row.get("type"),ParticipantIdentity.label((String)row.get("driver_name")),lines.size(),original,original-total,total,platform,total-platform,trips,review(id,total,trips));
    }
    private java.util.List<PaymentDto.Trip> trips(UUID id,UUID passenger){
        return sql.query("""
            WITH details AS (
              SELECT r.id,r.ride_date::text AS date,r.departure_time::text AS time,r.direction,r.version,l.cents,
                CASE WHEN r.direction='ida' THEN CASE WHEN privacy_route_details_visible(rt) THEN rt.region ELSE 'Região removida' END ELSE un.name END AS origin,
                CASE WHEN r.direction='ida' THEN un.name ELSE CASE WHEN privacy_route_details_visible(rt) THEN rt.region ELSE 'Região removida' END END AS destination,rt.meeting,r.notes,
                q.status='accepted' AND q.expires_at>now() AND p.expires_at>now()
                  AND p.status IN ('awaiting','pending') AND r.status='planned' AND privacy_ride_details_visible(r) AND privacy_route_details_visible(rt)
                  AND (r.ride_date+r.departure_time) AT TIME ZONE un.time_zone>now()
                  AND u.deleted_at IS NULL AND u.account_status='active'
                  AND NOT EXISTS(SELECT 1 FROM user_blocks b WHERE b.active AND
                    ((b.by_id=q.passenger_id AND b.other_id=q.driver_id) OR (b.by_id=q.driver_id AND b.other_id=q.passenger_id))) AS allowed
              FROM payments p JOIN ride_requests q ON q.id=p.request_id JOIN request_lines l ON l.request_id=q.id
              JOIN rides r ON r.id=l.ride_id JOIN ride_routes rt ON rt.id=r.route_id
              JOIN universities un ON un.id=r.university_id JOIN users u ON u.id=q.driver_id
              WHERE p.id=? AND p.passenger_id=?
            ) SELECT id,date,time,direction,version,cents,origin,destination,allowed,
                CASE WHEN allowed THEN meeting END AS meeting,CASE WHEN allowed THEN notes END AS notes
              FROM details ORDER BY date,time,id
            """,(r,n)->new PaymentDto.Trip(r.getObject("id",UUID.class),r.getString("date"),r.getString("time").substring(0,5),r.getString("direction"),r.getString("origin"),r.getString("destination"),r.getString("meeting"),r.getString("notes"),r.getInt("cents"),r.getInt("version"),r.getBoolean("allowed")),id,passenger);
    }
    private static String review(UUID payment,long total,java.util.List<PaymentDto.Trip> trips){
        if(trips.isEmpty()||trips.stream().anyMatch(t->!t.detailsAvailable()))return null;
        try {
            // Length-prefixed fields avoid ambiguous concatenation; no raw trip data is persisted here.
            var bytes=new java.io.ByteArrayOutputStream();var data=new java.io.DataOutputStream(bytes);
            data.writeUTF(payment.toString());data.writeLong(total);data.writeInt(trips.size());
            for(var t:trips){data.writeUTF(t.rideId().toString());data.writeUTF(t.date());data.writeUTF(t.time());data.writeUTF(t.direction());data.writeUTF(t.origin());data.writeUTF(t.destination());data.writeUTF(t.meeting());data.writeUTF(t.notes()==null?"":t.notes());data.writeInt(t.cents());data.writeInt(t.version());}
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch(java.io.IOException|java.security.NoSuchAlgorithmException error){throw new IllegalStateException("Could not prepare trip review.",error);}
    }
    public void requireTripReview(UUID id,UUID passenger,String supplied){
        String expected=summary(id,passenger).tripReview();
        ApiException.require(supplied!=null&&expected!=null&&expected.equals(supplied),"TRIP_REVIEW_REQUIRED","Os detalhes da carona precisam ser conferidos novamente. Atualize o resumo antes de continuar para pagamento.",409);
    }
    public java.time.Instant now() { return sql.queryForObject("SELECT now()",(r,n) -> r.getTimestamp(1).toInstant()); }
    public boolean blocked(UUID a,UUID b) { return Boolean.TRUE.equals(sql.queryForObject("SELECT EXISTS(SELECT 1 FROM user_blocks WHERE active AND ((by_id=? AND other_id=?) OR (by_id=? AND other_id=?)))",Boolean.class,a,b,b,a)); }
}
