package com.unicarona.privacy.service;

import com.unicarona.shared.repository.AuditRepository;
import java.io.IOException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Expire operational text only. Transport facts, prices, reservations and money are not rewritten. */
@Service
public class PrivacyTripDetailsRetention {
    public record Candidate(UUID route){}
    private final JdbcTemplate sql;private final PrivacyHoldControlTransactions controls;private final AuditRepository audit;
    public PrivacyTripDetailsRetention(JdbcTemplate sql,PrivacyHoldControlTransactions controls,AuditRepository audit){this.sql=sql;this.controls=controls;this.audit=audit;}
    public List<Candidate> candidates(UUID after,int batch){if(batch<1||batch>10000)throw new IllegalArgumentException("Invalid trip text retention batch.");return sql.query("""
        SELECT rt.id FROM ride_routes rt WHERE (?::uuid IS NULL OR rt.id>?) AND NOT privacy_route_details_case(rt.id) AND (
         privacy_route_details_retirable(rt)
         OR EXISTS(SELECT 1 FROM rides r WHERE r.route_id=rt.id AND r.details_retired_at IS NULL AND r.details_expires_at<=clock_timestamp())
         OR EXISTS(SELECT 1 FROM routines r WHERE r.route_id=rt.id AND r.details_retired_at IS NULL AND NOT privacy_routine_details_visible(r)))
        ORDER BY rt.id LIMIT ?
        """,(r,n)->new Candidate(r.getObject(1,UUID.class)),after,after,batch);}
    @Transactional(rollbackFor=IOException.class) public boolean retire(Candidate candidate,boolean skipLocked)throws IOException {
        controls.requireCurrentForMaintenance();if(!Boolean.TRUE.equals(sql.queryForObject("SELECT privacy_trip_route_lock(?,?)",Boolean.class,candidate.route(),skipLocked)))return false;
        String lock=skipLocked?" FOR UPDATE SKIP LOCKED":" FOR UPDATE";
        if(sql.queryForList("SELECT id FROM ride_routes WHERE id=?"+lock,candidate.route()).isEmpty())return false;
        if(Boolean.TRUE.equals(sql.queryForObject("SELECT privacy_route_details_case(?)",Boolean.class,candidate.route())))return false;
        int rides=sql.update("UPDATE rides SET notes='',proposal_reason=NULL,details_retired_at=clock_timestamp() WHERE id IN (SELECT id FROM rides r WHERE route_id=? AND details_retired_at IS NULL AND details_expires_at<=clock_timestamp() ORDER BY id LIMIT 500"+lock+")",candidate.route());
        int routines=sql.update("UPDATE routines SET notes='',details_retired_at=clock_timestamp() WHERE id IN (SELECT id FROM routines r WHERE route_id=? AND details_retired_at IS NULL AND NOT privacy_routine_details_visible(r) ORDER BY id LIMIT 500"+lock+")",candidate.route());
        int routes=sql.update("UPDATE ride_routes rt SET region='Região removida',meeting='Detalhes removidos',origin_address=NULL,destination_address=NULL,origin_lat=NULL,origin_lng=NULL,destination_lat=NULL,destination_lng=NULL,details_retired_at=clock_timestamp() WHERE id=? AND privacy_route_details_retirable(rt)",candidate.route());
        if(rides+routines+routes>0)audit.record(null,"operational_trip_text_retired","privacy_retention",null,Map.of("rides",rides,"routines",routines,"routes",routes,"policyVersion","trip-details-v1"));return rides+routines+routes>0;
    }
}
