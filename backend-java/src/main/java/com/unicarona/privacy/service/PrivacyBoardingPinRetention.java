package com.unicarona.privacy.service;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Retires only the authentication material; contracts and money remain intact. */
@Service
public class PrivacyBoardingPinRetention {
    public record Candidate(UUID id,UUID ride,UUID passenger,UUID driver){}
    private final JdbcTemplate sql;
    public PrivacyBoardingPinRetention(JdbcTemplate sql){this.sql=sql;}
    public List<Candidate> candidates(UUID after,int batch){
        if(batch<1||batch>10000)throw new IllegalArgumentException("Invalid boarding PIN retention batch.");
        return sql.query("SELECT rr.id,rr.ride_id,rr.passenger_id,rr.driver_id FROM ride_reservations rr WHERE rr.pin_erased_at IS NULL AND NOT privacy_boarding_pin_window(rr) AND (?::uuid IS NULL OR rr.id>?::uuid) ORDER BY rr.id LIMIT ?",(r,n)->new Candidate(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getObject(3,UUID.class),r.getObject(4,UUID.class)),after,after,batch);
    }
    @Transactional public boolean retire(Candidate candidate,boolean skipLocked){
        String lock=skipLocked?" FOR UPDATE SKIP LOCKED":" FOR UPDATE";
        for(UUID owner:java.util.stream.Stream.of(candidate.passenger(),candidate.driver()).sorted().toList())if(sql.queryForList("SELECT id FROM users WHERE id=?"+lock,owner).isEmpty())return false;
        if(sql.queryForList("SELECT id FROM rides WHERE id=?"+lock,candidate.ride()).isEmpty())return false;
        if(sql.queryForList("SELECT id FROM ride_reservations WHERE id=? AND ride_id=? AND passenger_id=? AND driver_id=?"+lock,candidate.id(),candidate.ride(),candidate.passenger(),candidate.driver()).isEmpty())return false;
        return sql.update("UPDATE ride_reservations rr SET pin_ciphertext=NULL,pin_hash=NULL WHERE rr.id=? AND pin_erased_at IS NULL AND NOT privacy_boarding_pin_window(rr)",candidate.id())==1;
    }
}
