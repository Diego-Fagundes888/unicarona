package com.unicarona.privacy.service;

import com.unicarona.auth.config.AuthSettings;
import com.unicarona.security.CryptoService;
import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Purpose-bound keyed fingerprint, never an unkeyed digest or a list of identity documents. */
@Service
public class RegistrationRestrictions {
    private final JdbcTemplate sql;private final CryptoService crypto;private final AuthSettings auth;
    public RegistrationRestrictions(JdbcTemplate sql,CryptoService crypto,AuthSettings auth){this.sql=sql;this.crypto=crypto;this.auth=auth;}
    public String fingerprint(String email){return crypto.hmac("registration-email:"+email.strip().toLowerCase(Locale.ROOT),auth.pepper());}
    @Transactional(propagation=Propagation.MANDATORY) public String lock(String email){String value=fingerprint(email);sql.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,181071))",value);return value;}
    @Transactional(propagation=Propagation.MANDATORY) public boolean blocked(String fingerprint){return Boolean.TRUE.equals(sql.queryForObject("SELECT EXISTS(SELECT 1 FROM privacy_registration_restrictions WHERE email_hmac=? AND released_at IS NULL AND expires_at>now())",Boolean.class,fingerprint));}
}
