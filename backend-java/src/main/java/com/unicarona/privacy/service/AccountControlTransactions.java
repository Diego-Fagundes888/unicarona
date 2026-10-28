package com.unicarona.privacy.service;

import com.unicarona.admin.dto.AdminAccountDto;
import com.unicarona.admin.repository.AdminAccountRepository;
import com.unicarona.admin.service.AdminAccountTransactions;
import com.unicarona.auth.config.AuthSettings;
import com.unicarona.notification.service.NotificationService;
import com.unicarona.privacy.config.PrivacySettings;
import com.unicarona.privacy.dto.*;
import com.unicarona.security.*;
import com.unicarona.shared.*;
import com.unicarona.shared.repository.*;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountControlTransactions {
    private final JdbcTemplate sql;private final ActorsRepository actors;private final AdminAccountRepository accounts;private final AdminAccountTransactions reviews;
    private final PrivacyClosureTransactions closures;private final AccountActivity activity;private final RegistrationRestrictions restrictions;
    private final IdempotencyExecutor keys;private final CryptoService crypto;private final AuthSettings auth;private final PrivacySettings settings;private final AuditRepository audit;private final NotificationService notices;private final PrivacyEvidenceCrypto evidence;
    public AccountControlTransactions(JdbcTemplate sql,ActorsRepository actors,AdminAccountRepository accounts,AdminAccountTransactions reviews,PrivacyClosureTransactions closures,AccountActivity activity,RegistrationRestrictions restrictions,IdempotencyExecutor keys,CryptoService crypto,AuthSettings auth,PrivacySettings settings,AuditRepository audit,NotificationService notices,PrivacyEvidenceCrypto evidence){this.sql=sql;this.actors=actors;this.accounts=accounts;this.reviews=reviews;this.closures=closures;this.activity=activity;this.restrictions=restrictions;this.keys=keys;this.crypto=crypto;this.auth=auth;this.settings=settings;this.audit=audit;this.notices=notices;this.evidence=evidence;}
    @Transactional(readOnly=true) public AccountControlDto.State state(UUID owner){
        return state(owner,false);
    }
    private AccountControlDto.State state(UUID owner,boolean retainedAdministrativeEvidence){
        var found=sql.queryForList("SELECT account_status FROM users WHERE id=?"+(retainedAdministrativeEvidence?"":" AND deleted_at IS NULL"),owner);ApiException.require(!found.isEmpty(),"ACCOUNT_UNAVAILABLE","A conta não está disponível.",404);
        String status=(String)found.getFirst().get("account_status");var closure=closures.status(owner);
        var until=sql.queryForObject("SELECT max(expires_at) FROM privacy_registration_restrictions WHERE source_user_id=? AND released_at IS NULL AND expires_at>now()",Timestamp.class,owner);
        return new AccountControlDto.State(status,"deactivated".equals(status)&&!Set.of("scheduled","pending_obligations","erasing").contains(closure.status()),until==null?null:ApiDates.timestamp(until.toInstant()),closure,sql.query("SELECT * FROM privacy_account_decisions WHERE user_id=? ORDER BY created_at DESC,id DESC LIMIT 50",(r,n)->notice(r),owner));
    }
    private AccountControlDto.Notice notice(java.sql.ResultSet r)throws java.sql.SQLException{return new AccountControlDto.Notice(r.getObject("id",UUID.class),r.getString("action"),r.getString("new_status"),r.getString("reason_code"),r.getString("public_reason"),ApiDates.timestamp(r.getTimestamp("created_at").toInstant()));}
    @Transactional public AccountControlDto.AdminState detail(Actor administrator,UUID owner){
        admin(administrator);var result=state(owner,true);var details=sql.query("SELECT * FROM privacy_account_decisions WHERE user_id=? ORDER BY created_at DESC,id DESC LIMIT 50",(r,n)->new AccountControlDto.PrivateDecision(notice(r),evidence.open(PrivacyEvidenceCrypto.Field.DECISION_REASON,owner,r.getObject("id",UUID.class),r.getString("reason_ciphertext"))),owner);
        var subject=sql.queryForObject("SELECT id,CASE WHEN deleted_at IS NOT NULL OR account_status='deleted' THEN 'Conta excluída' ELSE name END AS name,CASE WHEN deleted_at IS NOT NULL OR account_status='deleted' THEN '' ELSE email END AS email FROM users WHERE id=?",(r,n)->new AccountControlDto.Subject(r.getObject("id",UUID.class),r.getString("name"),r.getString("email")),owner);
        audit.record(administrator.id(),"account_decision_evidence_accessed","user",owner,Map.of("count",details.size()));return new AccountControlDto.AdminState(subject,result,details);
    }
    @Transactional public AccountControlDto.State deactivate(Actor actor,String expectedHash,boolean acknowledged,String key){
        var person=actors.lockUser(actor.id());session(actor,expectedHash);ApiException.require(acknowledged,"ACCOUNT_CONFIRMATION_REQUIRED","Confirme as consequências da desativação.",422);
        return keys.execute(actor.id(),key,"privacy:account:deactivate",Map.of("acknowledged",true),AccountControlDto.State.class,()->{
            ApiException.require("user".equals(person.role())&&"active".equals(person.accountStatus())&&!openClosure(actor.id()),"ACCOUNT_STATE","Só é possível desativar uma conta ativa sem encerramento em andamento.",409);
            sql.update("UPDATE users SET account_status='deactivated',voluntarily_deactivated_at=now(),updated_at=now() WHERE id=?",actor.id());activity.pause(actor.id(),"account_deactivation");revokeOthers(actor);
            audit.record(actor.id(),"account_deactivated","user",actor.id(),Map.of("existingFinancialRightsPreserved",true));
            notices.notify(actor.id(),"Conta desativada","Seu perfil saiu das novas combinações. Viagens já pagas e valores pendentes continuam disponíveis. Reative a conta em Privacidade e dados.","settings",null);return state(actor.id());
        });
    }
    @Transactional public AccountControlDto.State reactivate(Actor actor,String expectedHash,String key){
        var person=actors.lockUser(actor.id());session(actor,expectedHash);
        return keys.execute(actor.id(),key,"privacy:account:reactivate",Map.of("operation","reactivate"),AccountControlDto.State.class,()->{
            ApiException.require("deactivated".equals(person.accountStatus())&&!openClosure(actor.id()),"ACCOUNT_STATE","Apenas a desativação temporária pode ser revertida aqui. Restrições de segurança podem ser revistas pelo canal de recurso.",409);
            sql.update("UPDATE users SET account_status='active',voluntarily_deactivated_at=NULL,updated_at=now() WHERE id=?",actor.id());revokeOthers(actor);
            audit.record(actor.id(),"account_reactivated","user",actor.id(),Map.of("renewalsAndMarketingRemainOff",true));return state(actor.id());
        });
    }
    @Transactional public AccountControlDto.State decide(Actor administrator,String expectedHash,UUID owner,AccountControlDto.Decision input,String key){
        admin(administrator);ApiException.require(!owner.equals(administrator.id()),"ACCOUNT_UNAVAILABLE","Selecione uma conta de estudante.",422);
        // Match registration's lock order before any participant row lock; close/erase cannot race a ban into a fresh account.
        String fingerprint=null,email=null;
        if("ban".equals(input.action())){var emails=sql.queryForList("SELECT email FROM users WHERE id=? AND deleted_at IS NULL",owner);ApiException.require(!emails.isEmpty(),"ACCOUNT_UNAVAILABLE","Conta indisponível.",404);email=(String)emails.getFirst().get("email");fingerprint=restrictions.lock(email);}
        var ids=new ArrayList<UUID>();ids.add(owner);ids.add(administrator.id());accounts.participants(owner).forEach(p->{ids.add(p.driver());ids.add(p.passenger());});var people=actors.lockUsers(ids);session(administrator,expectedHash);
        if(accounts.participants(owner).stream().anyMatch(p->!ids.contains(p.driver())||!ids.contains(p.passenger())))throw new ConcurrencyFailureException("Account participants changed before decision locks",new java.sql.SQLException("Retry with current participants","40001"));
        var actualAdministrator=people.stream().filter(p->p.id().equals(administrator.id())).findFirst().orElseThrow();ApiException.require("admin".equals(actualAdministrator.role())&&"active".equals(actualAdministrator.accountStatus())&&actualAdministrator.emailVerified(),"FORBIDDEN","Acesso administrativo necessário.",403);
        var person=people.stream().filter(p->p.id().equals(owner)).findFirst().orElseThrow();ApiException.require("user".equals(person.role())&&!"deleted".equals(person.accountStatus()),"ACCOUNT_UNAVAILABLE","Selecione uma conta de estudante.",404);
        if(email!=null&&!email.equals(sql.queryForObject("SELECT email FROM users WHERE id=?",String.class,owner)))throw new ConcurrencyFailureException("Account identity changed before restriction lock",new java.sql.SQLException("Retry current identity","40001"));
        var body=new TreeMap<String,Object>();body.put("user",owner.toString());body.put("action",input.action());body.put("expectedStatus",input.expectedStatus());body.put("reasonCode",input.reasonCode());body.put("restrictionDays",input.restrictionDays());body.put("messageFingerprint",crypto.hmac(input.reason()+"\n"+input.publicReason(),auth.pepper()));String restricted=fingerprint;
        return keys.execute(administrator.id(),key,"privacy:account:decision",body,AccountControlDto.State.class,()->{
            ApiException.require("ban".equals(input.action())?input.restrictionDays()!=null&&input.restrictionDays()>=1&&input.restrictionDays()<=settings.registrationRestrictionDays():input.restrictionDays()==null,"RESTRICTION_PERIOD_REQUIRED","No banimento, escolha um prazo proporcional entre 1 e "+settings.registrationRestrictionDays()+" dias. Outras ações não criam restrição de recadastro.",422);
            ApiException.require(person.accountStatus().equals(input.expectedStatus()),"ACCOUNT_VERSION_CONFLICT","A situação da conta mudou. Recarregue antes de decidir.",409);
            String previous=person.accountStatus();
            switch(input.action()){
                case "ban","close"->{
                    ApiException.require(!"ban".equals(input.action())||!"banned".equals(previous),"ACCOUNT_STATE","A conta já está banida.",409);
                    if(!Set.of("suspended","banned").contains(previous))reviews.review(administrator,owner.toString(),new AdminAccountDto.Review("suspended",previous,input.publicReason()),"privacy-suspension-"+crypto.hmac(key,auth.pepper()));
                    activity.pause(owner,"platform_account_decision");
                    if("ban".equals(input.action())){
                        sql.update("UPDATE users SET account_status='banned',updated_at=now() WHERE id=?",owner);
                        sql.update("INSERT INTO privacy_registration_restrictions(email_hmac,reason_code,created_by,source_user_id,expires_at) VALUES(?,?,?,?,now()+?*interval '1 day')",restricted,input.reasonCode(),administrator.id(),owner,input.restrictionDays());
                    }
                    closures.platform(owner,administrator.id());
                    sql.update("UPDATE sessions SET revoked_at=coalesce(revoked_at,now()) WHERE user_id=?",owner);
                    sql.update("UPDATE auth_tokens SET used_at=coalesce(used_at,now()),invalidated_at=coalesce(invalidated_at,now()) WHERE user_id=? AND used_at IS NULL",owner);
                }
                case "lift_ban"->{
                    ApiException.require("banned".equals(previous),"ACCOUNT_STATE","Não há banimento vigente para revisar.",409);
                    // Removing a ban is not permission to resume offers or cancel a distinct closure decision.
                    sql.update("UPDATE users SET account_status='suspended',updated_at=now() WHERE id=?",owner);
                    sql.update("UPDATE privacy_registration_restrictions SET released_at=now() WHERE source_user_id=? AND released_at IS NULL",owner);
                }
                case "cancel_closure"->{
                    closures.cancelPlatform(owner);
                }
                default->throw new ApiException("VALIDATION_ERROR","Ação inválida.",422);
            }
            String status=sql.queryForObject("SELECT account_status FROM users WHERE id=?",String.class,owner);
            UUID decision=UUID.randomUUID();sql.update("INSERT INTO privacy_account_decisions(id,user_id,action,previous_status,new_status,reason_code,public_reason,reason_ciphertext,created_by,restriction_days) VALUES(?,?,?,?,?,?,?,?,?,?)",decision,owner,input.action(),previous,status,input.reasonCode(),input.publicReason(),evidence.seal(PrivacyEvidenceCrypto.Field.DECISION_REASON,owner,decision,input.reason()),administrator.id(),input.restrictionDays());
            audit.record(administrator.id(),"platform_account_decision","account_decision",decision,Map.of("user",owner.toString(),"action",input.action(),"previousStatus",previous,"status",status,"reasonCode",input.reasonCode()));
            notices.notify(owner,"Decisão sobre sua conta",input.publicReason()+" Você pode pedir revisão em Privacidade e dados. A revisão do banimento, o cancelamento do encerramento e a restauração do serviço são decisões distintas.","settings",decision);return state(owner);
        });
    }
    private boolean openClosure(UUID owner){return Boolean.TRUE.equals(sql.queryForObject("SELECT EXISTS(SELECT 1 FROM privacy_account_closures WHERE user_id=? AND status IN ('scheduled','pending_obligations','erasing'))",Boolean.class,owner));}
    private void revokeOthers(Actor actor){sql.update("UPDATE sessions SET revoked_at=coalesce(revoked_at,now()) WHERE user_id=? AND id<>?",actor.id(),actor.sessionId());sql.update("UPDATE auth_tokens SET used_at=coalesce(used_at,now()),invalidated_at=coalesce(invalidated_at,now()) WHERE user_id=? AND used_at IS NULL",actor.id());}
    private void session(Actor actor,String expectedHash){
        ApiException.require(Boolean.TRUE.equals(sql.queryForObject("SELECT EXISTS(SELECT 1 FROM users u JOIN sessions s ON s.user_id=u.id WHERE u.id=? AND u.deleted_at IS NULL AND u.password_hash=? AND s.id=? AND s.revoked_at IS NULL AND s.access_expires_at>now())",Boolean.class,actor.id(),expectedHash,actor.sessionId())),"REAUTHENTICATION_EXPIRED","A sessão ou a segurança da conta mudou. Entre novamente.",409);
    }
    private static void admin(Actor actor){ApiException.require("admin".equals(actor.role())&&"active".equals(actor.accountStatus()),"FORBIDDEN","Acesso administrativo necessário.",403);}
}
