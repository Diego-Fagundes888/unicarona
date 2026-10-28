package com.unicarona.privacy.service;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Caller owns the participant lock. Pausing never reverses existing paid financial rights. */
@Service
public class AccountActivity {
    private final JdbcTemplate sql;
    public AccountActivity(JdbcTemplate sql){this.sql=sql;}
    @Transactional(propagation=Propagation.MANDATORY) public void pause(UUID owner,String source){
        sql.update("UPDATE subscriptions SET renew=false,updated_at=now() WHERE (passenger_id=? OR driver_id=?) AND renew",owner,owner);
        sql.update("UPDATE routines SET status='paused',updated_at=now() WHERE driver_id=? AND status='active'",owner);
        sql.update("UPDATE waitlist SET status='cancelled' WHERE user_id=? AND status='waiting'",owner);
        boolean marketing=Boolean.TRUE.equals(sql.queryForObject("SELECT coalesce((SELECT marketing FROM privacy_preferences WHERE user_id=?),false)",Boolean.class,owner));
        sql.update("INSERT INTO privacy_preferences(user_id,marketing) VALUES(?,false) ON CONFLICT(user_id) DO UPDATE SET marketing=false,marketing_statement_version=NULL,updated_at=now()",owner);
        if(marketing){sql.update("UPDATE privacy_consent_events SET retain_until=greatest(retain_until,now()+interval '5 years') WHERE user_id=? AND purpose='marketing'",owner);sql.update("INSERT INTO privacy_consent_events(user_id,purpose,granted,statement_version,source,retain_until) VALUES(?,'marketing',false,?,?,now()+interval '5 years')",owner,PrivacyRightsService.MARKETING_VERSION,source);}
    }
}
