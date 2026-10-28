package com.unicarona.privacy.service;

import com.unicarona.auth.config.AuthSettings;
import com.unicarona.privacy.dto.PrivacyDto;
import com.unicarona.security.*;
import com.unicarona.shared.*;
import com.unicarona.shared.repository.AuditRepository;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminPrivacyService {
    private final JdbcTemplate sql;private final CryptoService crypto;private final AuthSettings settings;private final AuditRepository audit;private final IdempotencyExecutor keys;private final PrivacyEvidenceCrypto evidence;
    public AdminPrivacyService(JdbcTemplate sql,CryptoService crypto,AuthSettings settings,AuditRepository audit,IdempotencyExecutor keys,PrivacyEvidenceCrypto evidence){this.sql=sql;this.crypto=crypto;this.settings=settings;this.audit=audit;this.keys=keys;this.evidence=evidence;}
    private static void admin(Actor actor){ApiException.require(actor.authorities().contains("ROLE_ADMIN"),"FORBIDDEN","Acesso administrativo necessário.",403);}
    @Transactional(readOnly=true) public List<PrivacyDto.AdminItem> list(Actor actor,String status,int limit,int offset){
        admin(actor);return sql.query("SELECT p.id,p.user_id,p.kind,p.status,p.created_at,p.due_at,u.name,u.email FROM privacy_requests p JOIN users u ON u.id=p.user_id WHERE (?='' OR p.status=?) ORDER BY p.due_at,p.id LIMIT ? OFFSET ?",(r,n)->item(r),status,status,limit,offset);
    }
    private static PrivacyDto.AdminItem item(java.sql.ResultSet r)throws java.sql.SQLException {
        return new PrivacyDto.AdminItem(new PrivacyDto.Protocol(r.getObject("id",UUID.class),r.getString("kind"),r.getString("status"),ApiDates.timestamp(r.getTimestamp("created_at").toInstant()),ApiDates.timestamp(r.getTimestamp("due_at").toInstant())),r.getObject("user_id",UUID.class),r.getString("name"),r.getString("email"));
    }
    @Transactional public PrivacyDto.AdminDetail detail(Actor actor,UUID id){
        admin(actor);
        var item=sql.query("SELECT p.id,p.user_id,p.kind,p.status,p.created_at,p.due_at,u.name,u.email FROM privacy_requests p JOIN users u ON u.id=p.user_id WHERE p.id=?",(r,n)->item(r),id).stream().findFirst().orElseThrow(AdminPrivacyService::missing);
        String details=sql.queryForObject("SELECT details_ciphertext FROM privacy_requests WHERE id=?",String.class,id);
        var responses=sql.query("SELECT id,body_ciphertext,created_at FROM privacy_request_responses WHERE request_id=? ORDER BY created_at,id",(r,n)->new PrivacyDto.Response(evidence.open(PrivacyEvidenceCrypto.Field.RESPONSE_BODY,item.userId(),r.getObject("id",UUID.class),r.getString("body_ciphertext")),ApiDates.timestamp(r.getTimestamp("created_at").toInstant())),id);
        audit.record(actor.id(),"privacy_request_accessed","privacy_request",id,Map.of());
        return new PrivacyDto.AdminDetail(item,evidence.open(PrivacyEvidenceCrypto.Field.REQUEST_DETAILS,item.userId(),id,details),responses);
    }
    @Transactional public PrivacyDto.Protocol respond(Actor actor,UUID id,PrivacyDto.Review input,String key){
        admin(actor);var body=new LinkedHashMap<String,Object>();body.put("id",id);body.put("expectedStatus",input.expectedStatus());body.put("status",input.status());body.put("messageHmac",crypto.hmac(input.message(),settings.pepper()));body.put("actionPerformed",input.actionPerformed());
        return keys.execute(actor.id(),key,"privacy:respond",body,PrivacyDto.Protocol.class,()->{
            var requests=sql.queryForList("SELECT status,user_id FROM privacy_requests WHERE id=? FOR UPDATE",id);ApiException.require(!requests.isEmpty(),"PRIVACY_REQUEST_NOT_FOUND","Solicitação não encontrada.",404);
            String current=(String)requests.getFirst().get("status");ApiException.require(current.equals(input.expectedStatus()),"PRIVACY_REQUEST_CHANGED","A situação mudou. Recarregue antes de responder.",409);
            ApiException.require(!Set.of("completed","denied").contains(current),"PRIVACY_REQUEST_CLOSED","Abra uma nova solicitação para acrescentar informações.",409);
            ApiException.require(!"completed".equals(input.status())||Boolean.TRUE.equals(input.actionPerformed()),"PRIVACY_ACTION_CONFIRMATION_REQUIRED","Confirme que a providência descrita foi executada. Responder ao protocolo não executa exclusão ou correção.",409);
            UUID response=UUID.randomUUID();sql.update("INSERT INTO privacy_request_responses(id,request_id,author_id,body_ciphertext) VALUES(?,?,?,?)",response,id,actor.id(),evidence.seal(PrivacyEvidenceCrypto.Field.RESPONSE_BODY,(UUID)requests.getFirst().get("user_id"),response,input.message()));
            sql.update("UPDATE privacy_requests SET status=?,updated_at=now(),resolved_at=CASE WHEN ? IN ('completed','denied') THEN now() ELSE NULL END WHERE id=?",input.status(),input.status(),id);
            audit.record(actor.id(),"privacy_request_responded","privacy_request",id,Map.of("previousStatus",current,"status",input.status(),"actionPerformed",Boolean.TRUE.equals(input.actionPerformed())));
            return sql.queryForObject("SELECT id,kind,status,created_at,due_at FROM privacy_requests WHERE id=?",(r,n)->new PrivacyDto.Protocol(r.getObject("id",UUID.class),r.getString("kind"),r.getString("status"),ApiDates.timestamp(r.getTimestamp("created_at").toInstant()),ApiDates.timestamp(r.getTimestamp("due_at").toInstant())),id);
        });
    }
    private static ApiException missing(){return new ApiException("PRIVACY_REQUEST_NOT_FOUND","Solicitação não encontrada.",404);}
}
