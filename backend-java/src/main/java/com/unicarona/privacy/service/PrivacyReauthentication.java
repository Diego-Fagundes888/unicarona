package com.unicarona.privacy.service;

import com.unicarona.auth.service.AuthService;
import com.unicarona.security.Actor;
import com.unicarona.security.CryptoService;
import com.unicarona.shared.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** A session alone is insufficient for export or account closure; attempts persist outside the operation transaction. */
@Service
public class PrivacyReauthentication {
    private final JdbcTemplate sql;private final AuthService auth;private final CryptoService crypto;
    public PrivacyReauthentication(JdbcTemplate sql,AuthService auth,CryptoService crypto){this.sql=sql;this.auth=auth;this.crypto=crypto;}
    public String confirm(Actor actor,String password){
        auth.limitWithRetry("privacy-reauth:"+actor.id(),5,900);
        return verify(actor,password);
    }
    public String confirmAdministrator(Actor actor,String password){
        AdminPrivacyHoldTransactions.admin(actor);auth.limitWithRetry("privacy-admin-reauth:"+actor.id(),30,900);return verify(actor,password);
    }
    private String verify(Actor actor,String password){
        var rows=sql.queryForList("SELECT password_hash FROM users WHERE id=? AND deleted_at IS NULL",actor.id());
        ApiException.require(!rows.isEmpty()&&crypto.verifyPassword((String)rows.getFirst().get("password_hash"),password),"INVALID_CREDENTIALS","Não foi possível confirmar sua senha.",401);
        return (String)rows.getFirst().get("password_hash");
    }
}
