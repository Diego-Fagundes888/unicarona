package com.unicarona.admin.repository;

import com.unicarona.admin.dto.*;
import com.unicarona.shared.*;
import com.unicarona.shared.dto.PageDto;
import java.sql.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Explicit projections avoid loading academic graphs; mutations lock the campus before its versions. */
@Repository
public class AdminCatalogRepository {
    private final JdbcTemplate sql;
    public AdminCatalogRepository(JdbcTemplate sql) { this.sql=sql; }
    private static final String CALENDAR="SELECT e.id,e.university_id,e.exclusion_date::text AS date,e.title,e.active,e.class_id,c.name AS course,cl.period_number,cl.label,e.created_at FROM calendar_exclusions e LEFT JOIN classes cl ON cl.id=e.class_id LEFT JOIN courses c ON c.id=cl.course_id";
    public String university(UUID id,boolean lock) { return sql.query("SELECT time_zone FROM universities WHERE id=? AND active"+(lock?" FOR UPDATE":""),(r,n) -> r.getString(1),id).stream().findFirst().orElseThrow(() -> new ApiException("UNIVERSITY_NOT_FOUND","Faculdade não encontrada.",404)); }
    public PageDto<AdminCatalogDto.Pricing> pricing(AdminCatalogQueries.Pricing input) { var page=input.page();return PageDto.from(sql.query("SELECT * FROM pricing_versions WHERE university_id=? ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",(r,n) -> pricing(r),input.university(),page.limit()+1,page.offset()),page.limit(),page.offset()); }
    public AdminCatalogDto.Pricing createPricing(UUID actor,AdminCatalogDto.PricingInput input,int discount) {
        return sql.query("INSERT INTO pricing_versions(university_id,fuel_cents,efficiency,wear_cents,reference_passengers,commission_bps,discount_bps,created_by,created_at) VALUES(?,?,?,?,?,?,?,?,clock_timestamp()) RETURNING *",
            (r,n) -> pricing(r),UUID.fromString(input.universityId()),input.fuelCents(),input.efficiency(),input.wearCents(),input.referencePassengers(),input.commissionBps(),discount,actor).getFirst();
    }
    private static AdminCatalogDto.Pricing pricing(ResultSet r) throws SQLException { return new AdminCatalogDto.Pricing(r.getObject("id",UUID.class),r.getObject("university_id",UUID.class),r.getInt("fuel_cents"),r.getDouble("efficiency"),r.getInt("wear_cents"),r.getInt("reference_passengers"),r.getInt("commission_bps"),r.getInt("discount_bps"),ApiDates.timestamp(r.getTimestamp("created_at").toInstant())); }
    public PageDto<AdminCatalogDto.Calendar> calendar(AdminCatalogQueries.Calendar input) { var page=input.page();return PageDto.from(sql.query(CALENDAR+" WHERE e.university_id=? AND e.exclusion_date BETWEEN ?::date AND ?::date ORDER BY e.exclusion_date,e.created_at DESC,e.id LIMIT ? OFFSET ?",(r,n) -> calendar(r),input.university(),input.start(),input.end(),page.limit()+1,page.offset()),page.limit(),page.offset()); }
    private static AdminCatalogDto.Calendar calendar(ResultSet r) throws SQLException { return new AdminCatalogDto.Calendar(r.getObject("id",UUID.class),r.getObject("university_id",UUID.class),r.getString("date"),r.getString("title"),r.getBoolean("active"),r.getObject("class_id",UUID.class),r.getString("course"),r.getObject("period_number",Integer.class),r.getString("label"),ApiDates.timestamp(r.getTimestamp("created_at").toInstant())); }
    public String today(String zone) { return sql.queryForObject("SELECT (now() AT TIME ZONE ?)::date::text",String.class,zone); }
    public UUID classId(AdminCatalogDto.ClassExclusion input) {
        UUID course=UUID.fromString(input.courseId()),university=UUID.fromString(input.universityId());ApiException.require(Boolean.TRUE.equals(sql.queryForObject("SELECT EXISTS(SELECT 1 FROM courses WHERE id=? AND university_id=? AND active)",Boolean.class,course,university)),"INVALID_COURSE","Selecione um curso dessa faculdade.",422);
        sql.update("INSERT INTO classes(university_id,course_id,period_number,label) VALUES(?,?,?,?) ON CONFLICT(course_id,period_number,label) DO NOTHING",university,course,input.periodNumber(),input.classLabel());return sql.queryForObject("SELECT id FROM classes WHERE university_id=? AND course_id=? AND period_number=? AND label=?",UUID.class,university,course,input.periodNumber(),input.classLabel());
    }
    public UUID createCalendar(UUID actor,AdminCatalogDto.CalendarInput input,UUID classId) {
        UUID university=UUID.fromString(input.universityId());ApiException.require(!Boolean.TRUE.equals(sql.queryForObject("SELECT EXISTS(SELECT 1 FROM calendar_exclusions WHERE university_id=? AND exclusion_date=?::date AND class_id IS NOT DISTINCT FROM ?::uuid AND active)",Boolean.class,university,input.date(),classId)),"CALENDAR_EXISTS","Já existe uma exclusão ativa para esta data e turma.",409);
        return sql.queryForObject("INSERT INTO calendar_exclusions(university_id,class_id,exclusion_date,title,created_by) VALUES(?,?,?::date,?,?) RETURNING id",UUID.class,university,classId,input.date(),input.title(),actor);
    }
    public AdminCatalogDto.Calendar calendar(UUID id) { return sql.query(CALENDAR+" WHERE e.id=?",(r,n) -> calendar(r),id).getFirst(); }
    public UUID calendarUniversity(UUID id) { return sql.query("SELECT university_id FROM calendar_exclusions WHERE id=?",(r,n) -> r.getObject(1,UUID.class),id).stream().findFirst().orElseThrow(() -> new ApiException("CALENDAR_NOT_FOUND","Data não encontrada.",404)); }
    public void deactivate(UUID id,UUID actor) {
        boolean active=sql.queryForObject("SELECT active FROM calendar_exclusions WHERE id=? FOR UPDATE",Boolean.class,id);ApiException.require(active,"CALENDAR_INACTIVE","Esta exclusão já foi desativada.",409);sql.update("UPDATE calendar_exclusions SET active=false,deactivated_by=?,updated_at=now() WHERE id=?",actor,id);
    }
}
