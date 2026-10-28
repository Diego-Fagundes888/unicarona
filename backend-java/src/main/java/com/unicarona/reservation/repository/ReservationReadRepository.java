package com.unicarona.reservation.repository;

import com.unicarona.auth.config.AuthSettings;
import com.unicarona.reservation.dto.ReservationDto;
import com.unicarona.privacy.service.ParticipantAccessSql;
import com.unicarona.privacy.service.ParticipantIdentity;
import com.unicarona.security.CryptoService;
import com.unicarona.shared.ApiDates;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** A joined projection avoids lazy entities and performs the bilateral privacy checks in SQL. */
@Repository
public class ReservationReadRepository {
    private final JdbcTemplate sql;
    private final CryptoService crypto;
    private final AuthSettings settings;
    public ReservationReadRepository(JdbcTemplate sql,CryptoService crypto,AuthSettings settings) { this.sql=sql;this.crypto=crypto;this.settings=settings; }
    public Optional<String> ownedSide(UUID actor,UUID id) {
        return sql.query("SELECT passenger_id FROM ride_reservations WHERE id=? AND (passenger_id=? OR driver_id=?)",(row,n) -> row.getObject("passenger_id",UUID.class).equals(actor)?"passenger":"driver",id,actor,actor).stream().findFirst();
    }
    public List<ReservationDto.Item> list(UUID actor,String side,UUID ride,UUID contract,boolean upcoming,int limit,int offset,UUID id) {
        return sql.query("""
            SELECT rr.id,rr.ride_id,rr.passenger_id,rr.driver_id,rr.subscription_id,rr.status,rr.price_cents,
              rr.availability,rr.absence,rr.final_response,rr.confirmation_deadline,rr.proposed_time,rr.time_response,
              r.ride_date::text AS date,r.departure_time::text AS time,r.direction,r.status AS ride_status,
              CASE WHEN privacy_route_details_visible(rt) THEN rt.region ELSE 'Região removida' END AS region,
              CASE WHEN privacy_route_details_visible(rt) THEN rt.meeting ELSE 'Detalhes removidos' END AS meeting,
              CASE WHEN u.deleted_at IS NULL AND u.account_status<>'deleted' THEN u.name END AS other_name,photo.id AS photo_id,
              ("""+ParticipantAccessSql.OPERATIONAL+"""
              ) AS operational_contact,
              EXISTS(SELECT 1 FROM user_blocks b WHERE b.active AND ((b.by_id=? AND b.other_id=u.id) OR (b.by_id=u.id AND b.other_id=?))) AS personally_blocked,
              v.model,v.color,v.plate,p.status AS parcel_status,privacy_boarding_pin_available(rr) AS boarding_pin_available,
              CASE WHEN rr.passenger_id=? AND privacy_boarding_pin_available(rr) THEN rr.pin_ciphertext END AS pin_ciphertext
            FROM ride_reservations rr JOIN rides r ON r.id=rr.ride_id JOIN universities un ON un.id=r.university_id JOIN ride_routes rt ON rt.id=r.route_id JOIN vehicles v ON v.id=r.vehicle_id
            JOIN parcels p ON p.reservation_id=rr.id JOIN users u ON u.id=CASE WHEN rr.passenger_id=? THEN rr.driver_id ELSE rr.passenger_id END
            LEFT JOIN stored_files photo ON photo.id=u.photo_file_id AND photo.deleted_at IS NULL AND photo.purpose='photo' AND photo.owner_id=u.id
            WHERE CASE WHEN ?='driver' THEN rr.driver_id ELSE rr.passenger_id END=? AND (?::uuid IS NULL OR rr.ride_id=?)
              AND (NOT ? OR (rr.status IN ('confirmed','boarded') AND r.status IN ('planned','active') AND r.ride_date>=(now() AT TIME ZONE un.time_zone)::date))
              AND (?::uuid IS NULL OR rr.id=?) AND (?::uuid IS NULL OR rr.subscription_id=?)
            ORDER BY CASE WHEN ? THEN r.ride_date END ASC,CASE WHEN ? THEN r.departure_time END ASC,r.ride_date DESC,r.departure_time DESC,rr.id LIMIT ? OFFSET ?
            """,(row,n) -> {
                UUID passenger=row.getObject("passenger_id",UUID.class),subscription=row.getObject("subscription_id",UUID.class),photo=row.getObject("photo_id",UUID.class);
                String status=row.getString("status"),rideStatus=row.getString("ride_status"),proposed=row.getString("proposed_time");
                String pinCiphertext=row.getString("pin_ciphertext");
                boolean showPin=actor.equals(passenger) && row.getBoolean("boarding_pin_available") && pinCiphertext!=null;
                String photoUrl=id==null?null:photo!=null && !row.getBoolean("personally_blocked") && row.getBoolean("operational_contact")?"/api/v1/files/"+photo:"";
                var deadline=row.getTimestamp("confirmation_deadline");
                return new ReservationDto.Item(row.getObject("id",UUID.class),row.getObject("ride_id",UUID.class),passenger,row.getObject("driver_id",UUID.class),subscription==null?"":subscription.toString(),status,rideStatus,
                    row.getString("date"),row.getString("time").substring(0,5),row.getString("direction"),row.getString("region"),actor.equals(row.getObject("driver_id",UUID.class)) || (row.getBoolean("operational_contact") && !row.getBoolean("personally_blocked"))?row.getString("meeting"):"Detalhes do encontro protegidos",ParticipantIdentity.label(row.getString("other_name")),row.getInt("price_cents"),row.getString("parcel_status"),
                    row.getString("availability"),row.getString("absence"),row.getString("final_response"),ApiDates.timestamp(deadline==null?null:deadline.toInstant()),proposed==null?"":proposed.substring(0,5),row.getString("time_response"),photoUrl,
                    new ReservationDto.Vehicle(row.getString("model"),row.getString("color"),actor.equals(row.getObject("driver_id",UUID.class)) || (row.getBoolean("operational_contact") && !row.getBoolean("personally_blocked"))?row.getString("plate"):"Placa protegida"),showPin?crypto.decrypt(pinCiphertext,settings.encryptionKey()):null);
            },actor,actor,actor,actor,side,actor,ride,ride,upcoming,id,id,contract,contract,upcoming,upcoming,limit,offset);
    }
    public Optional<ReservationDto.Contact> contact(UUID actor,UUID id) {
        return sql.query("""
            SELECT u.name,u.phone FROM ride_reservations rr JOIN rides r ON r.id=rr.ride_id
            JOIN universities un ON un.id=r.university_id JOIN users u ON u.id=CASE WHEN rr.passenger_id=? THEN rr.driver_id ELSE rr.passenger_id END
            WHERE rr.id=? AND (rr.passenger_id=? OR rr.driver_id=?) AND ("""+ParticipantAccessSql.OPERATIONAL+"""
            )
              AND NOT EXISTS(SELECT 1 FROM user_blocks b WHERE b.active AND ((b.by_id=? AND b.other_id=u.id) OR (b.by_id=u.id AND b.other_id=?)))
            """,(row,n) -> new ReservationDto.Contact(ParticipantIdentity.label(row.getString("name")),row.getString("phone")==null?"":row.getString("phone")),actor,id,actor,actor,actor,actor).stream().findFirst();
    }
}
