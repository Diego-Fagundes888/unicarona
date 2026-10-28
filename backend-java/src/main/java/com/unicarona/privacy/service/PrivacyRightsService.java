package com.unicarona.privacy.service;

import com.unicarona.auth.config.AuthSettings;
import com.unicarona.auth.service.AuthService;
import com.unicarona.privacy.dto.PrivacyDto;
import com.unicarona.security.*;
import com.unicarona.shared.*;
import com.unicarona.shared.repository.AuditRepository;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PrivacyRightsService {
    public static final String MARKETING_VERSION="marketing-2026-10-16.1";
    public static final String MARKETING_STATEMENT="Desejo receber por e-mail novidades e campanhas do UniCarona. Posso cancelar a qualquer momento. Isso não altera os avisos essenciais de conta, segurança, pagamento e carona.";
    private final JdbcTemplate sql;private final CryptoService crypto;private final AuthSettings settings;private final AuditRepository audit;private final IdempotencyExecutor keys;private final AuthService auth;private final PrivacyEvidenceCrypto evidence;
    public PrivacyRightsService(JdbcTemplate sql,CryptoService crypto,AuthSettings settings,AuditRepository audit,IdempotencyExecutor keys,AuthService auth,PrivacyEvidenceCrypto evidence){this.sql=sql;this.crypto=crypto;this.settings=settings;this.audit=audit;this.keys=keys;this.auth=auth;this.evidence=evidence;}
    @Transactional(readOnly=true) public PrivacyDto.Preferences preferences(Actor actor){
        boolean marketing=Boolean.TRUE.equals(sql.queryForObject("SELECT coalesce((SELECT marketing FROM privacy_preferences WHERE user_id=?),false)",Boolean.class,actor.id()));
        return new PrivacyDto.Preferences(marketing,MARKETING_VERSION,MARKETING_STATEMENT);
    }
    @Transactional public PrivacyDto.Preferences updatePreferences(Actor actor,PrivacyDto.PreferenceUpdate input){
        if(input.marketing())ApiException.require(MARKETING_VERSION.equals(input.statementVersion()),"CONSENT_VERSION_CHANGED","Leia a autorização atual antes de escolher receber novidades.",409);
        lockOwner(actor);
        var previous=preferences(actor);
        sql.update("INSERT INTO privacy_preferences(user_id,marketing,marketing_statement_version) VALUES(?,?,?) ON CONFLICT(user_id) DO UPDATE SET marketing=excluded.marketing,marketing_statement_version=excluded.marketing_statement_version,updated_at=now()",actor.id(),input.marketing(),input.marketing()?MARKETING_VERSION:null);
        if(previous.marketing()!=input.marketing()){
            if(!input.marketing())sql.update("UPDATE privacy_consent_events SET retain_until=greatest(retain_until,now()+interval '5 years') WHERE user_id=? AND purpose='marketing'",actor.id());
            sql.update("INSERT INTO privacy_consent_events(user_id,purpose,granted,statement_version,source,retain_until) VALUES(?,'marketing',?,?,'privacy_center',now()+interval '5 years')",actor.id(),input.marketing(),MARKETING_VERSION);
            audit.record(actor.id(),input.marketing()?"marketing_granted":"marketing_revoked","user",actor.id(),Map.of("statementVersion",MARKETING_VERSION));
        }
        return preferences(actor);
    }
    @Transactional public PrivacyDto.Protocol request(Actor actor,PrivacyDto.Request input,String key){
        lockOwner(actor);
        // A plain body hash would permit guesses about short confidential request details after a DB leak.
        var fingerprint=Map.of("kind",input.kind(),"detailsHmac",crypto.hmac(input.details(),settings.pepper()));
        return keys.execute(actor.id(),key,"privacy:request",fingerprint,PrivacyDto.Protocol.class,()->{
            auth.limit("privacy-request:"+actor.id(),5,86400);
            // Fifteen days is the internal response target, not a claim that every LGPD right has this statutory deadline.
            UUID id=UUID.randomUUID();sql.update("INSERT INTO privacy_requests(id,user_id,kind,details_ciphertext,due_at) VALUES(?,?,?,?,now()+interval '15 days')",id,actor.id(),input.kind(),evidence.seal(PrivacyEvidenceCrypto.Field.REQUEST_DETAILS,actor.id(),id,input.details()));
            audit.record(actor.id(),"privacy_request_received","privacy_request",id,Map.of("kind",input.kind()));
            return protocol(actor.id(),id);
        });
    }
    private void lockOwner(Actor actor){
        ApiException.require(!sql.queryForList("SELECT id FROM users WHERE id=? AND deleted_at IS NULL FOR UPDATE",actor.id()).isEmpty(),"ACCOUNT_UNAVAILABLE","A conta não está disponível para essa operação.",409);
    }
    @Transactional(readOnly=true) public java.util.List<PrivacyDto.Protocol> list(Actor actor,int limit,int offset){
        return sql.query("SELECT id,kind,status,created_at,due_at FROM privacy_requests WHERE user_id=? ORDER BY created_at DESC,id LIMIT ? OFFSET ?",(r,n)->new PrivacyDto.Protocol(r.getObject("id",UUID.class),r.getString("kind"),r.getString("status"),ApiDates.timestamp(r.getTimestamp("created_at").toInstant()),ApiDates.timestamp(r.getTimestamp("due_at").toInstant())),actor.id(),limit,offset);
    }
    private PrivacyDto.Protocol protocol(UUID owner,UUID id){
        return sql.query("SELECT id,kind,status,created_at,due_at FROM privacy_requests WHERE user_id=? AND id=?",(r,n)->new PrivacyDto.Protocol(r.getObject("id",UUID.class),r.getString("kind"),r.getString("status"),ApiDates.timestamp(r.getTimestamp("created_at").toInstant()),ApiDates.timestamp(r.getTimestamp("due_at").toInstant())),owner,id).stream().findFirst().orElseThrow(()->new ApiException("PRIVACY_REQUEST_NOT_FOUND","Solicitação não encontrada.",404));
    }
    @Transactional(readOnly=true) public PrivacyDto.Detail detail(Actor actor,UUID id){
        var protocol=protocol(actor.id(),id);
        String details=sql.queryForObject("SELECT details_ciphertext FROM privacy_requests WHERE id=? AND user_id=?",String.class,id,actor.id());
        var responses=sql.query("SELECT id,body_ciphertext,created_at FROM privacy_request_responses WHERE request_id=? ORDER BY created_at,id",(r,n)->new PrivacyDto.Response(evidence.open(PrivacyEvidenceCrypto.Field.RESPONSE_BODY,actor.id(),r.getObject("id",UUID.class),r.getString("body_ciphertext")),ApiDates.timestamp(r.getTimestamp("created_at").toInstant())),id);
        return new PrivacyDto.Detail(protocol,evidence.open(PrivacyEvidenceCrypto.Field.REQUEST_DETAILS,actor.id(),id,details),responses);
    }
}
