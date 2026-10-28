package com.unicarona.privacy.service;

import com.unicarona.admin.repository.AdminAccountRepository;
import com.unicarona.admin.service.AdminAccountTransactions;
import com.unicarona.auth.config.AuthSettings;
import com.unicarona.auth.repository.AuthRepository;
import com.unicarona.auth.service.AuthService;
import com.unicarona.notification.service.NotificationService;
import com.unicarona.privacy.dto.IdentityCorrectionDto;
import com.unicarona.security.*;
import com.unicarona.shared.*;
import com.unicarona.shared.repository.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdentityCorrectionTransactions {
    private static final String RETAIN_TARGET="CASE WHEN EXISTS(SELECT 1 FROM privacy_legal_holds h WHERE h.user_id=privacy_email_changes.user_id AND h.category='identity_change' AND h.released_at IS NULL AND h.expires_at>now() AND (h.resource_id IS NULL OR h.resource_id=privacy_email_changes.id)) THEN new_email_ciphertext ELSE NULL END";
    private final JdbcTemplate sql;private final ActorsRepository actors;private final AuthRepository tokens;private final AuthService limits;private final AuthSettings auth;private final CryptoService crypto;private final RegistrationRestrictions restrictions;private final PrivacyEvidenceCrypto evidence;
    private final IdempotencyExecutor keys;private final AuditRepository audit;private final NotificationService notices;private final IdentityMail mail;private final AdminAccountRepository accounts;private final AdminAccountTransactions reviews;
    public IdentityCorrectionTransactions(JdbcTemplate sql,ActorsRepository actors,AuthRepository tokens,AuthService limits,AuthSettings auth,CryptoService crypto,RegistrationRestrictions restrictions,IdempotencyExecutor keys,AuditRepository audit,NotificationService notices,IdentityMail mail,AdminAccountRepository accounts,AdminAccountTransactions reviews,PrivacyEvidenceCrypto evidence){this.sql=sql;this.actors=actors;this.tokens=tokens;this.limits=limits;this.auth=auth;this.crypto=crypto;this.restrictions=restrictions;this.keys=keys;this.audit=audit;this.notices=notices;this.mail=mail;this.accounts=accounts;this.reviews=reviews;this.evidence=evidence;}
    @Transactional(readOnly=true) public IdentityCorrectionDto.State state(UUID owner){
        var rows=sql.queryForList("SELECT c.id,c.new_email_ciphertext,c.expires_at,c.credential_hmac,u.password_hash FROM privacy_email_changes c JOIN users u ON u.id=c.user_id JOIN auth_tokens t ON t.id=c.token_id WHERE c.user_id=? AND c.status='pending' AND c.expires_at>now() AND t.expires_at>now() AND t.used_at IS NULL AND t.invalidated_at IS NULL AND u.deleted_at IS NULL",owner);
        if(rows.isEmpty()||!credential((String)rows.getFirst().get("password_hash")).equals(rows.getFirst().get("credential_hmac")))return new IdentityCorrectionDto.State(null);
        var r=rows.getFirst();String address=evidence.open(PrivacyEvidenceCrypto.Field.EMAIL_TARGET,owner,(UUID)r.get("id"),(String)r.get("new_email_ciphertext"));return new IdentityCorrectionDto.State(new IdentityCorrectionDto.PendingEmail((UUID)r.get("id"),mask(address),ApiDates.timestamp(((Timestamp)r.get("expires_at")).toInstant())));
    }
    @Transactional public IdentityCorrectionDto.Accepted requestEmail(Actor actor,String expectedHash,String email,String key){
        String target=restrictions.lock(email);var user=lock(actor,expectedHash,false);available(user);
        return keys.execute(actor.id(),key,"privacy:identity:email:request",Map.of("emailHmac",target),IdentityCorrectionDto.Accepted.class,()->{
            ApiException.require(!email.equals(user.get("email")),"EMAIL_UNCHANGED","Informe um endereço diferente do atual.",422);limits.limit("identity-email:"+actor.id(),3,3600);
            cancelPending(actor.id());
            boolean eligible=!Boolean.TRUE.equals(sql.queryForObject("SELECT EXISTS(SELECT 1 FROM users WHERE email=?)",Boolean.class,email))&&!restrictions.blocked(target);
            String token=crypto.randomToken();UUID tokenId=tokens.issueEmailToken(actor.id(),"change_email",digest(token),30);
            UUID change=UUID.randomUUID();sql.update("INSERT INTO privacy_email_changes(id,user_id,token_id,new_email_ciphertext,new_email_hmac,original_email_hmac,credential_hmac,eligible,expires_at) VALUES(?,?,?,?,?,?,?,?,now()+interval '30 minutes')",change,actor.id(),tokenId,evidence.seal(PrivacyEvidenceCrypto.Field.EMAIL_TARGET,actor.id(),change,email),target,restrictions.fingerprint((String)user.get("email")),credential(expectedHash),eligible);
            if(eligible)mail.enqueue(actor.id(),email,"change_email",auth.origin()+"/login.html#alterar-email?token="+token,"auth:"+tokenId);
            mail.enqueue(actor.id(),(String)user.get("email"),"email_change_notice",auth.origin()+"/login.html","identity-notice:"+change+":requested");
            audit.record(actor.id(),"email_change_requested","identity_change",change,Map.of("expiresMinutes",30));
            // Identical response/state shape for an address already used or restricted; no existence oracle.
            return new IdentityCorrectionDto.Accepted(true,"Se o novo endereço puder ser utilizado, enviaremos um link de confirmação. Seu e-mail de acesso continua o mesmo até a conclusão.");
        });
    }
    @Transactional public IdentityCorrectionDto.Completed confirmEmail(Actor actor,String expectedHash,String token,String key){
        var before=sql.queryForList("SELECT c.id,c.new_email_ciphertext FROM privacy_email_changes c JOIN auth_tokens t ON t.id=c.token_id WHERE c.user_id=? AND t.token_hash=?",actor.id(),digest(token));
        ApiException.require(!before.isEmpty(),"TOKEN_INVALID","Link inválido, utilizado ou expirado.",400);
        String target=before.getFirst().get("new_email_ciphertext")==null?null:evidence.open(PrivacyEvidenceCrypto.Field.EMAIL_TARGET,actor.id(),(UUID)before.getFirst().get("id"),(String)before.getFirst().get("new_email_ciphertext"));
        if(target!=null)restrictions.lock(target);var user=lock(actor,expectedHash,false);
        return keys.execute(actor.id(),key,"privacy:identity:email:confirm",Map.of("tokenHash",digest(token)),IdentityCorrectionDto.Completed.class,()->{
            available(user);
            var rows=sql.queryForList("SELECT c.*,t.used_at,t.invalidated_at,t.expires_at>now() AS valid_time FROM privacy_email_changes c JOIN auth_tokens t ON t.id=c.token_id WHERE c.id=? AND c.user_id=? FOR UPDATE OF c,t",before.getFirst().get("id"),actor.id());
            ApiException.require(!rows.isEmpty(),"TOKEN_INVALID","Link inválido, utilizado ou expirado.",400);var row=rows.getFirst();
            ApiException.require(target!=null&&"pending".equals(row.get("status"))&&Boolean.TRUE.equals(row.get("eligible"))&&Boolean.TRUE.equals(row.get("valid_time"))&&row.get("used_at")==null&&row.get("invalidated_at")==null&&credential(expectedHash).equals(row.get("credential_hmac"))&&restrictions.fingerprint((String)user.get("email")).equals(row.get("original_email_hmac"))&&!restrictions.blocked(restrictions.fingerprint(target))&&!Boolean.TRUE.equals(sql.queryForObject("SELECT EXISTS(SELECT 1 FROM users WHERE email=? AND id<>?)",Boolean.class,target,actor.id())),"TOKEN_INVALID","Link inválido, utilizado ou expirado.",400);
            sql.update("UPDATE users SET email=?,email_verified_at=now(),updated_at=now() WHERE id=?",target,actor.id());
            sql.update("UPDATE privacy_email_changes SET status='confirmed',completed_at=now(),new_email_ciphertext="+RETAIN_TARGET+",credential_hmac=NULL WHERE id=?",row.get("id"));
            sql.update("UPDATE auth_tokens SET used_at=coalesce(used_at,now()) WHERE id=?",row.get("token_id"));
            sql.update("UPDATE auth_tokens SET used_at=now(),invalidated_at=coalesce(invalidated_at,now()) WHERE user_id=? AND used_at IS NULL",actor.id());
            sql.update("UPDATE sessions SET revoked_at=coalesce(revoked_at,now()) WHERE user_id=?",actor.id());
            UUID change=(UUID)row.get("id");
            mail.enqueue(actor.id(),(String)user.get("email"),"email_changed_notice",auth.origin()+"/login.html","identity-notice:"+change+":completed-old");
            mail.enqueue(actor.id(),target,"email_changed_notice",auth.origin()+"/login.html","identity-notice:"+change+":completed-new");
            audit.record(actor.id(),"email_changed","identity_change",change,Map.of("ownershipConfirmed",true,"sessionsRevoked",true));
            return new IdentityCorrectionDto.Completed("updated","E-mail alterado e confirmado. Entre novamente com o novo endereço.");
        });
    }
    @Transactional public IdentityCorrectionDto.Completed cancelEmail(Actor actor,String expectedHash,String key){
        lock(actor,expectedHash,false);
        return keys.execute(actor.id(),key,"privacy:identity:email:cancel",Map.of("operation","cancel"),IdentityCorrectionDto.Completed.class,()->{cancelPending(actor.id());audit.record(actor.id(),"email_change_cancelled","user",actor.id(),Map.of());return new IdentityCorrectionDto.Completed("cancelled","O pedido de troca de e-mail foi cancelado. Seu endereço de acesso foi preservado.");});
    }
    @Transactional public IdentityCorrectionDto.Completed birth(Actor actor,String expectedHash,LocalDate birth,String key){
        LocalDate today=LocalDate.now(ZoneId.of("America/Sao_Paulo"));ApiException.require(!birth.isAfter(today)&&birth.getYear()>=1,"INVALID_BIRTH_DATE","Informe uma data de nascimento válida.",422);
        var user=lock(actor,expectedHash,true);ApiException.require("user".equals(user.get("role")),"ACCOUNT_UNAVAILABLE","Use o canal de privacidade para corrigir essa conta.",409);
        return keys.execute(actor.id(),key,"privacy:identity:birth",Map.of("birthHmac",crypto.hmac("birth-correction:"+birth,auth.pepper())),IdentityCorrectionDto.Completed.class,()->{
            boolean underage=!AuthService.isAdult(birth,today);
            if(underage&&Set.of("active","deactivated","closing").contains(user.get("account_status")))reviews.restrictForAgeCorrection(actor,(String)user.get("account_status"),birth,"age-"+crypto.hmac(key,auth.pepper()));
            sql.update("UPDATE users SET birth_date=?::date,updated_at=now() WHERE id=?",birth.toString(),actor.id());
            audit.record(actor.id(),"birth_date_corrected","user",actor.id(),Map.of("serviceEligibilityRestricted",underage,"ageVerification","self_declared"));
            notices.notify(actor.id(),"Data de nascimento corrigida",underage?"O serviço exige 18 anos completos. Novas combinações foram suspensas; seus direitos e valores continuam sujeitos ao tratamento aplicável. Peça revisão em Privacidade e dados.":"Sua data de nascimento foi atualizada. A correção não comprova idade ou identidade e não remove outras restrições.","settings",null);
            return new IdentityCorrectionDto.Completed(underage?"restricted":"updated",underage?"Data corrigida. O uso do serviço foi restringido porque exige 18 anos completos. Você pode tratar seus direitos e pedir revisão em Privacidade e dados.":"Data de nascimento corrigida. Outras restrições e preferências foram preservadas.");
        });
    }
    private Map<String,Object> lock(Actor actor,String expectedHash,boolean participants){
        sql.execute("SET LOCAL lock_timeout='3s'");
        var ids=new ArrayList<UUID>();ids.add(actor.id());if(participants)accounts.participants(actor.id()).forEach(p->{ids.add(p.driver());ids.add(p.passenger());});actors.lockUsers(ids);
        if(participants&&accounts.participants(actor.id()).stream().anyMatch(p->!ids.contains(p.driver())||!ids.contains(p.passenger())))throw new org.springframework.dao.ConcurrencyFailureException("Identity participants changed before locking",new java.sql.SQLException("Retry with current participants","40001"));
        var rows=sql.queryForList("SELECT id,email,role,account_status,password_hash FROM users WHERE id=? AND deleted_at IS NULL",actor.id());
        ApiException.require(!rows.isEmpty()&&expectedHash.equals(rows.getFirst().get("password_hash"))&&Boolean.TRUE.equals(sql.queryForObject("SELECT EXISTS(SELECT 1 FROM sessions WHERE user_id=? AND id=? AND revoked_at IS NULL AND access_expires_at>now())",Boolean.class,actor.id(),actor.sessionId())),"REAUTHENTICATION_EXPIRED","A segurança da conta ou a sessão mudou. Entre novamente.",409);return rows.getFirst();
    }
    private void available(Map<String,Object> user){ApiException.require("user".equals(user.get("role"))&&Set.of("active","deactivated").contains(user.get("account_status"))&&!Boolean.TRUE.equals(sql.queryForObject("SELECT EXISTS(SELECT 1 FROM privacy_account_closures WHERE user_id=? AND status IN ('scheduled','pending_obligations','erasing'))",Boolean.class,user.get("id"))),"IDENTITY_ASSISTED_CORRECTION","Peça uma correção assistida em Privacidade e dados enquanto houver restrição ou encerramento em andamento.",409);}
    private void cancelPending(UUID owner){
        sql.update("UPDATE auth_tokens t SET used_at=coalesce(used_at,now()),invalidated_at=coalesce(invalidated_at,now()) FROM privacy_email_changes c WHERE c.user_id=? AND c.status='pending' AND c.token_id=t.id",owner);
        sql.update("UPDATE privacy_email_changes SET status='cancelled',completed_at=now(),new_email_ciphertext="+RETAIN_TARGET+",credential_hmac=NULL WHERE user_id=? AND status='pending'",owner);
    }
    private String credential(String hash){return crypto.hmac("email-change-credential:"+hash,auth.pepper());}
    private String digest(String token){return crypto.hmac(token,auth.pepper());}
    private static String mask(String email){int at=email.indexOf('@');return email.substring(0,1)+"***"+email.substring(at);}
}
