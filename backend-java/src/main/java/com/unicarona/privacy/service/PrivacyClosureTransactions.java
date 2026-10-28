package com.unicarona.privacy.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unicarona.notification.service.NotificationService;
import com.unicarona.privacy.config.PrivacySettings;
import com.unicarona.privacy.dto.PrivacyDto;
import com.unicarona.security.Actor;
import com.unicarona.shared.*;
import com.unicarona.shared.repository.AuditRepository;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reversible request stage. Completion and irreversible erasure belong to the maintenance transaction. */
@Service
public class PrivacyClosureTransactions {
    public static final String POLICY_VERSION="closure-2026-10-16.1";
    private final JdbcTemplate sql;private final PrivacySettings settings;private final AuditRepository audit;private final IdempotencyExecutor keys;private final NotificationService notices;private final ObjectMapper json;private final AccountActivity activity;private final PrivacyClosureMail mail;
    public PrivacyClosureTransactions(JdbcTemplate sql,PrivacySettings settings,AuditRepository audit,IdempotencyExecutor keys,NotificationService notices,ObjectMapper json,AccountActivity activity,PrivacyClosureMail mail){this.sql=sql;this.settings=settings;this.audit=audit;this.keys=keys;this.notices=notices;this.json=json;this.activity=activity;this.mail=mail;}
    @Transactional(readOnly=true) public PrivacyDto.Closure status(UUID owner){
        var obligations=obligations(owner);var balances=balances(owner);
        var rows=sql.queryForList("SELECT id,status,initiated_by,requested_at,erase_after,policy_version,coalesce((obligations_snapshot->>'graceDays')::int,?) AS grace_days FROM privacy_account_closures WHERE user_id=? ORDER BY requested_at DESC,id DESC LIMIT 1",settings.deletionGraceDays(),owner);
        if(rows.isEmpty())return new PrivacyDto.Closure(null,"none",null,null,false,settings.deletionGraceDays(),policyVersion(),obligations,balances,null);
        var row=rows.getFirst();String state=(String)row.get("status");boolean open=Set.of("scheduled","pending_obligations").contains(state);
        if(open)state=obligations.stream().anyMatch(PrivacyDto.Obligation::delaysErasure)?"pending_obligations":"scheduled";
        return new PrivacyDto.Closure((UUID)row.get("id"),state,date(row,"requested_at"),date(row,"erase_after"),open&&"user".equals(row.get("initiated_by")),open?((Number)row.get("grace_days")).intValue():settings.deletionGraceDays(),open?(String)row.get("policy_version"):policyVersion(),obligations,balances,(String)row.get("initiated_by"));
    }
    private String policyVersion(){return policyVersion(false);}
    private String policyVersion(boolean lock){
        var published=sql.query("SELECT v.content_sha256 FROM legal_document_heads h JOIN legal_document_versions v ON v.slug=h.slug AND v.version=h.version WHERE h.slug='safety' AND v.effective_at<=now()"+(lock?" FOR SHARE OF h":""),(r,n)->r.getString(1));
        // Privacy rights remain usable before contract publication; the action still binds its actual grace period.
        return POLICY_VERSION+".g"+settings.deletionGraceDays()+"."+(published.isEmpty()?"unpublished":published.getFirst().substring(0,16));
    }
    @Transactional public PrivacyDto.Closure request(Actor actor,String expectedHash,String policyVersion,String key){
        var user=lockOwner(actor,expectedHash);
        return keys.execute(actor.id(),key,"privacy:closure:request",Map.of("policyVersion",policyVersion),PrivacyDto.Closure.class,()->{
            var current=status(actor.id());if(Set.of("scheduled","pending_obligations").contains(current.status()))return current;
            String currentPolicy=policyVersion(true);ApiException.require(currentPolicy.equals(policyVersion),"CLOSURE_POLICY_CHANGED","Leia as consequências atualizadas antes de confirmar o encerramento.",409);
            String previous=(String)user.get("account_status");
            ApiException.require(Set.of("active","deactivated","suspended","banned").contains(previous),"ACCOUNT_UNAVAILABLE","A conta não está disponível para essa operação.",409);
            // Closing does not remove a safety restriction; a suspended account retains its restricted status.
            sql.update("UPDATE users SET account_status=CASE WHEN account_status IN ('active','deactivated') THEN 'closing' ELSE account_status END,updated_at=now() WHERE id=?",actor.id());
            sql.update("UPDATE sessions SET revoked_at=now() WHERE user_id=? AND id<>? AND revoked_at IS NULL",actor.id(),actor.sessionId());
            sql.update("UPDATE auth_tokens SET used_at=coalesce(used_at,now()),invalidated_at=coalesce(invalidated_at,now()) WHERE user_id=? AND used_at IS NULL",actor.id());
            activity.pause(actor.id(),"account_closure");
            var obligations=obligations(actor.id());String state=obligations.stream().anyMatch(PrivacyDto.Obligation::delaysErasure)?"pending_obligations":"scheduled";
            UUID closure=sql.queryForObject("INSERT INTO privacy_account_closures(user_id,initiated_by,status,previous_account_status,reauthenticated_at,erase_after,policy_version,obligations_snapshot) VALUES(?,'user',?,?,now(),now()+?*interval '1 day',?,?::jsonb) RETURNING id",UUID.class,actor.id(),state,previous,settings.deletionGraceDays(),currentPolicy,serialize(Map.of("items",obligations,"balances",balances(actor.id()),"graceDays",settings.deletionGraceDays())));
            audit.record(actor.id(),"account_closure_requested","privacy_account_closure",closure,Map.of("policyVersion",currentPolicy,"graceDays",settings.deletionGraceDays(),"status",state));
            notices.notify(actor.id(),"Encerramento solicitado","Novas combinações foram desativadas. Você pode cancelar o pedido antes da exclusão definitiva. Confira o prazo e as pendências em Privacidade e dados.","settings",closure);
            mail.current(actor.id(),closure,"requested");
            return status(actor.id());
        });
    }
    @Transactional public PrivacyDto.Closure cancel(Actor actor,String expectedHash,String key){
        lockOwner(actor,expectedHash);
        return keys.execute(actor.id(),key,"privacy:closure:cancel",Map.of("operation","cancel"),PrivacyDto.Closure.class,()->{
            var rows=sql.queryForList("SELECT id,previous_account_status FROM privacy_account_closures WHERE user_id=? AND initiated_by='user' AND status IN ('scheduled','pending_obligations') FOR UPDATE",actor.id());
            ApiException.require(!rows.isEmpty(),"CLOSURE_UNAVAILABLE","Não há pedido de encerramento que possa ser cancelado.",409);
            var closure=rows.getFirst();String previous=(String)closure.get("previous_account_status");
            sql.update("UPDATE privacy_account_closures SET status='cancelled',cancelled_at=now() WHERE id=?",closure.get("id"));
            // A later administrative suspension always wins; no restoration of renewals or marketing choices.
            sql.update("UPDATE users SET account_status=CASE WHEN account_status IN ('suspended','banned') THEN account_status ELSE ? END,updated_at=now() WHERE id=?",previous,actor.id());
            audit.record(actor.id(),"account_closure_cancelled","privacy_account_closure",(UUID)closure.get("id"),Map.of());
            notices.notify(actor.id(),"Encerramento cancelado","Sua conta foi preservada. Preferências de marketing, ofertas pausadas e renovações automáticas não foram reativadas.","settings",(UUID)closure.get("id"));
            mail.current(actor.id(),(UUID)closure.get("id"),"cancelled");
            return status(actor.id());
        });
    }
    private Map<String,Object> lockOwner(Actor actor,String expectedHash){
        sql.execute("SET LOCAL lock_timeout='3s'");
        var rows=sql.queryForList("SELECT id,password_hash,account_status FROM users WHERE id=? AND deleted_at IS NULL FOR UPDATE",actor.id());
        ApiException.require(!rows.isEmpty()&&expectedHash.equals(rows.getFirst().get("password_hash")),"REAUTHENTICATION_EXPIRED","A segurança da conta mudou. Confirme sua senha novamente.",409);
        ApiException.require(Boolean.TRUE.equals(sql.queryForObject("SELECT EXISTS(SELECT 1 FROM sessions WHERE id=? AND user_id=? AND revoked_at IS NULL AND access_expires_at>now())",Boolean.class,actor.sessionId(),actor.id())),"SESSION_EXPIRED","Entre novamente para confirmar essa operação.",401);
        return rows.getFirst();
    }
    /** Administrative action already reauthenticated and holding ordered participant locks. */
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void platform(UUID owner,UUID administrator){
        var existing=sql.queryForList("SELECT id,initiated_by FROM privacy_account_closures WHERE user_id=? AND status IN ('scheduled','pending_obligations') FOR UPDATE",owner);
        if(!existing.isEmpty()){
            if("user".equals(existing.getFirst().get("initiated_by"))){
                sql.update("UPDATE privacy_account_closures SET initiated_by='platform' WHERE id=?",existing.getFirst().get("id"));
                audit.record(administrator,"account_closure_assumed_by_platform","privacy_account_closure",(UUID)existing.getFirst().get("id"),Map.of("originalDeadlinePreserved",true));
                mail.current(owner,(UUID)existing.getFirst().get("id"),"platform");
            }
            return;
        }
        String previous=sql.queryForObject("SELECT account_status FROM users WHERE id=? AND deleted_at IS NULL",String.class,owner);
        ApiException.require(Set.of("suspended","banned").contains(previous),"ACCOUNT_STATE","Restrinja o serviço antes de encerrar a conta pela plataforma.",409);
        String policy=policyVersion(true);var obligations=obligations(owner);String state=obligations.stream().anyMatch(PrivacyDto.Obligation::delaysErasure)?"pending_obligations":"scheduled";
        UUID id=sql.queryForObject("INSERT INTO privacy_account_closures(user_id,initiated_by,status,previous_account_status,reauthenticated_at,erase_after,policy_version,obligations_snapshot) VALUES(?,'platform',?,?,now(),now()+?*interval '1 day',?,?::jsonb) RETURNING id",UUID.class,owner,state,previous,settings.deletionGraceDays(),policy,serialize(Map.of("items",obligations,"balances",balances(owner),"graceDays",settings.deletionGraceDays(),"administratorReauthenticated",true)));
        audit.record(administrator,"platform_account_closure_requested","privacy_account_closure",id,Map.of("policyVersion",policy,"graceDays",settings.deletionGraceDays(),"status",state));
        mail.current(owner,id,"platform");
    }
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY) public void cancelPlatform(UUID owner){
        var rows=sql.queryForList("SELECT id FROM privacy_account_closures WHERE user_id=? AND initiated_by='platform' AND status IN ('scheduled','pending_obligations') FOR UPDATE",owner);
        ApiException.require(!rows.isEmpty(),"CLOSURE_UNAVAILABLE","Não há encerramento da plataforma que ainda possa ser cancelado.",409);UUID id=(UUID)rows.getFirst().get("id");
        sql.update("UPDATE privacy_account_closures SET status='cancelled',cancelled_at=now() WHERE id=?",id);mail.current(owner,id,"cancelled");
    }
    public List<PrivacyDto.Obligation> obligations(UUID owner){
        var items=new ArrayList<PrivacyDto.Obligation>();
        add(items,owner,"rides","Caronas e reservas ainda em andamento",true,"SELECT count(*) FROM ride_reservations WHERE (passenger_id=? OR driver_id=?) AND status IN ('confirmed','boarded','awaiting_confirmation')",2);
        add(items,owner,"active_drive","Viagem iniciada pelo motorista",true,"SELECT count(*) FROM rides WHERE driver_id=? AND status='active'",1);
        add(items,owner,"requests","Solicitações abertas; cancele ou aguarde o encerramento",true,"SELECT count(*) FROM ride_requests WHERE (passenger_id=? OR driver_id=?) AND status IN ('pending','accepted')",2);
        add(items,owner,"payments","Cobranças ou devoluções aguardando confirmação",true,"SELECT count(*) FROM payments WHERE passenger_id=? AND status IN ('awaiting','pending','refund_pending')",1);
        add(items,owner,"provider_commands","Comandos de cobrança aguardando resposta do provedor",true,"SELECT count(*) FROM (SELECT c.payment_id FROM mercado_pago_card_commands c JOIN payments p ON p.id=c.payment_id WHERE p.passenger_id=? AND c.status='pending' UNION SELECT c.payment_id FROM mercado_pago_pix_commands c JOIN payments p ON p.id=c.payment_id WHERE p.passenger_id=? AND c.status='pending') pending",2);
        add(items,owner,"provider_refunds","Devoluções aguardando resposta do Mercado Pago",true,"SELECT count(*) FROM mercado_pago_refunds WHERE user_id=? AND status IN ('pending','processing')",1);
        add(items,owner,"transfers","Repasses ou devoluções em processamento",true,"SELECT count(*) FROM transfers WHERE user_id=? AND status='pending'",1);
        add(items,owner,"debt_payment","Pagamento de regularização em processamento",true,"SELECT count(*) FROM passenger_debt_payments WHERE passenger_id=? AND state IN ('CREATED','PENDING','PROCESSING','UNDER_REVIEW')",1);
        add(items,owner,"reviews","Disputas preservadas para decisão administrativa",false,"SELECT count(*) FROM occurrences WHERE (reporter_id=? OR other_id=?) AND status<>'resolved'",2);
        return List.copyOf(items);
    }
    public Map<String,Long> balances(UUID owner){
        var balances=new TreeMap<String,Long>();sql.query("SELECT a.kind,coalesce(sum(e.cents),0) AS cents FROM ledger_accounts a LEFT JOIN ledger_entries e ON e.account_id=a.id WHERE a.owner_id=? GROUP BY a.kind HAVING coalesce(sum(e.cents),0)<>0",(org.springframework.jdbc.core.RowCallbackHandler)r->balances.put(r.getString("kind"),r.getLong("cents")),owner);return Collections.unmodifiableMap(balances);
    }
    private void add(List<PrivacyDto.Obligation> items,UUID owner,String code,String label,boolean delays,String query,int repetitions){long count=sql.queryForObject(query,Long.class,Collections.nCopies(repetitions,owner).toArray());if(count>0)items.add(new PrivacyDto.Obligation(code,label,count,delays));}
    private String serialize(Object value){try{return json.writeValueAsString(value);}catch(JsonProcessingException error){throw new IllegalStateException("Could not serialize closure summary.");}}
    private static String date(Map<String,Object> row,String field){return ApiDates.timestamp(((Timestamp)row.get(field)).toInstant());}
}
