package com.unicarona.driver.repository;

import com.unicarona.driver.dto.DriverDto;
import com.unicarona.shared.ApiDates;
import com.unicarona.shared.ApiException;
import com.unicarona.file.repository.FileAccessRepository;
import com.unicarona.file.storage.PrivateFileLock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DriverRepository {
    public record LockedApplication(UUID id,UUID user,UUID vehicle,String status,int capacity,UUID identity,UUID license) {}
    public record ReviewDocument(UUID id,int bytes,String sha256) {}
    private final JdbcTemplate sql;
    private final FileAccessRepository fileAccess;
    public DriverRepository(JdbcTemplate sql,FileAccessRepository fileAccess) { this.sql=sql;this.fileAccess=fileAccess; }
    public boolean ownsDocuments(UUID owner,UUID identity,UUID license) {
        if(identity==null||license==null)return false;
        var docs=sql.queryForList("SELECT id,purpose FROM stored_files WHERE owner_id=? AND id IN (?,?) AND deleted_at IS NULL AND erasure_requested_at IS NULL AND erased_at IS NULL",owner,identity,license);
        return docs.stream().anyMatch(d -> identity.equals(d.get("id")) && "identity".equals(d.get("purpose")))
            && docs.stream().anyMatch(d -> license.equals(d.get("id")) && "license".equals(d.get("purpose")))&&fileAccess.documentAvailable(identity)&&fileAccess.documentAvailable(license);
    }
    public DriverDto.Status create(UUID user,DriverDto.Apply input) {
        sql.update("UPDATE driver_applications SET status='replaced' WHERE user_id=? AND status='pending'",user);
        UUID vehicle=sql.queryForObject("INSERT INTO vehicles(owner_id,model,color,plate,capacity,status) VALUES(?,?,?,?,?,'pending') RETURNING id",UUID.class,user,input.model(),input.color(),input.plate(),input.capacity());
        UUID application=sql.queryForObject("INSERT INTO driver_applications(user_id,vehicle_id,identity_file_id,license_file_id) VALUES(?,?,?,?) RETURNING id",UUID.class,user,vehicle,UUID.fromString(input.identityFileId()),UUID.fromString(input.licenseFileId()));
        return new DriverDto.Status(application,"pending");
    }
    public List<DriverDto.Application> applications(UUID user,boolean admin,int limit,int offset,String status) {
        return sql.query("""
            SELECT a.*,CASE WHEN coalesce(a.reviewed_at,a.created_at)>clock_timestamp()-interval '90 days' THEN a.decision END AS visible_decision,CASE WHEN a.status='pending' AND a.documents_expires_at<=clock_timestamp() THEN 'expired' ELSE a.status END AS effective_status,
            CASE WHEN a.documents_retired_at IS NOT NULL THEN 'retired' WHEN a.documents_expires_at<=clock_timestamp() THEN 'expired'
              WHEN fi.deleted_at IS NULL AND fl.deleted_at IS NULL AND fi.erasure_requested_at IS NULL AND fl.erasure_requested_at IS NULL AND fi.id IS NOT NULL AND fl.id IS NOT NULL THEN 'available' ELSE 'unavailable' END AS documents_status,
            CASE WHEN a.documents_retired_at IS NULL AND a.documents_expires_at>clock_timestamp() AND fi.deleted_at IS NULL AND fi.erasure_requested_at IS NULL THEN fi.id END AS identity_visible,
            CASE WHEN a.documents_retired_at IS NULL AND a.documents_expires_at>clock_timestamp() AND fl.deleted_at IS NULL AND fl.erasure_requested_at IS NULL THEN fl.id END AS license_visible,
            v.model,v.color,v.plate,v.capacity,u.name FROM driver_applications a JOIN vehicles v ON v.id=a.vehicle_id JOIN users u ON u.id=a.user_id
            LEFT JOIN stored_files fi ON fi.id=a.identity_file_id AND fi.owner_id=a.user_id LEFT JOIN stored_files fl ON fl.id=a.license_file_id AND fl.owner_id=a.user_id
            WHERE (?::boolean OR a.user_id=?) AND (?::text IS NULL OR CASE WHEN a.status='pending' AND a.documents_expires_at<=clock_timestamp() THEN 'expired' ELSE a.status END=?) ORDER BY a.created_at DESC,a.id LIMIT ? OFFSET ?
            """,(r,n) -> new DriverDto.Application(r.getObject("id",UUID.class),r.getObject("user_id",UUID.class),r.getString("effective_status"),r.getString("visible_decision"),
                ApiDates.timestamp(r.getTimestamp("created_at").toInstant()),r.getTimestamp("reviewed_at")==null?null:ApiDates.timestamp(r.getTimestamp("reviewed_at").toInstant()),
                r.getObject("identity_visible",UUID.class),r.getObject("license_visible",UUID.class),r.getString("model"),r.getString("color"),r.getString("plate"),r.getInt("capacity"),r.getString("name"),r.getString("documents_status"),ApiDates.timestamp(r.getTimestamp("documents_expires_at").toInstant()),r.getString("documents_policy_version")),admin,user,status,status,limit,offset);
    }
    public Optional<UUID> applicationUser(UUID application) {
        return sql.query("SELECT user_id FROM driver_applications WHERE id=?",(r,n) -> r.getObject("user_id",UUID.class),application).stream().findFirst();
    }
    public LockedApplication lockApplication(UUID id) {
        var rows=sql.query("SELECT a.id,a.user_id,a.vehicle_id,a.status,v.capacity,a.identity_file_id,a.license_file_id FROM driver_applications a JOIN vehicles v ON v.id=a.vehicle_id WHERE a.id=? FOR UPDATE OF a,v",
            (r,n) -> new LockedApplication(r.getObject("id",UUID.class),r.getObject("user_id",UUID.class),r.getObject("vehicle_id",UUID.class),r.getString("status"),r.getInt("capacity"),r.getObject("identity_file_id",UUID.class),r.getObject("license_file_id",UUID.class)),id);
        if(rows.isEmpty()) throw new ApiException("APPLICATION_NOT_FOUND","Solicitação não encontrada.",404); return rows.getFirst();
    }
    public boolean documentsReviewable(LockedApplication a){return Boolean.TRUE.equals(sql.queryForObject("SELECT documents_retired_at IS NULL AND documents_expires_at>clock_timestamp() AND identity_file_id IS NOT NULL AND license_file_id IS NOT NULL FROM driver_applications WHERE id=?",Boolean.class,a.id()));}
    public List<ReviewDocument> reviewDocuments(LockedApplication a){
        java.util.stream.Stream.of(a.identity(),a.license()).sorted().forEach(id->PrivateFileLock.acquire(sql,id));
        return sql.query("SELECT id,byte_size,sha256 FROM stored_files WHERE owner_id=? AND id IN (?,?) AND deleted_at IS NULL AND erasure_requested_at IS NULL AND erased_at IS NULL",(r,n)->new ReviewDocument(r.getObject(1,UUID.class),r.getInt(2),r.getString(3)),a.user(),a.identity(),a.license());
    }
    public void approveVehicle(LockedApplication application) {
        sql.queryForList("SELECT id FROM rides WHERE driver_id=? AND status IN ('planned','active') ORDER BY id FOR UPDATE",application.user());
        var over=sql.queryForList("""
            SELECT r.id FROM rides r WHERE r.driver_id=? AND r.status IN ('planned','active') AND
            ((SELECT count(*) FROM ride_reservations rr WHERE rr.ride_id=r.id AND rr.status IN ('confirmed','boarded','awaiting_confirmation'))+
             (SELECT count(*) FROM subscription_schedule sc WHERE sc.ride_id=r.id AND sc.held))>? LIMIT 1
            """,application.user(),application.capacity());
        ApiException.require(over.isEmpty(),"VEHICLE_TOO_SMALL","O veículo não comporta reservas existentes. Resolva os trechos antes de aprovar.",409);
        sql.update("UPDATE vehicles SET status='retired',updated_at=now() WHERE owner_id=? AND status='approved'",application.user());
        sql.update("UPDATE vehicles SET status='approved',updated_at=now() WHERE id=?",application.vehicle());
        sql.update("UPDATE rides SET vehicle_id=?,capacity=least(capacity,?),updated_at=now(),version=version+1 WHERE driver_id=? AND status='planned'",application.vehicle(),application.capacity(),application.user());
        sql.update("UPDATE routines SET vehicle_id=?,capacity=least(capacity,?),updated_at=now() WHERE driver_id=?",application.vehicle(),application.capacity(),application.user());
    }
    public void decide(LockedApplication application,UUID reviewer,boolean approve,String reason) {
        String status=approve?"approved":"rejected";
        if(!approve) sql.update("UPDATE vehicles SET status='rejected',updated_at=now() WHERE id=?",application.vehicle());
        sql.update("UPDATE driver_applications SET status=?,decision=?,reviewed_by=?,reviewed_at=now(),documents_expires_at=now()+interval '30 days' WHERE id=?",status,reason,reviewer,application.id());
    }
}
