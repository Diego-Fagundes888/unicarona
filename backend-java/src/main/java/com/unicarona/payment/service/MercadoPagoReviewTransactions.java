package com.unicarona.payment.service;

import com.unicarona.admin.service.AdminCatalogService;
import com.unicarona.payment.dto.MercadoPagoReviewDto;
import com.unicarona.security.Actor;
import com.unicarona.shared.*;
import com.unicarona.shared.repository.AuditRepository;
import com.unicarona.shared.repository.ActorsRepository;
import com.unicarona.notification.service.NotificationService;
import com.unicarona.payment.ledger.*;
import com.unicarona.payment.ledger.LedgerCommand.Posting;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @ConditionalOnProperty(name="PAYMENT_PROVIDER",havingValue="mercadopago")
public class MercadoPagoReviewTransactions {
    private final JdbcTemplate sql;private final IdempotencyExecutor idempotency;private final AuditRepository audit;private final ActorsRepository actors;private final MercadoPagoResolutionFacts facts;private final LedgerService ledger;private final NotificationService notices;
    public MercadoPagoReviewTransactions(JdbcTemplate sql,IdempotencyExecutor idempotency,AuditRepository audit,ActorsRepository actors,MercadoPagoResolutionFacts facts,LedgerService ledger,NotificationService notices) {this.sql=sql;this.idempotency=idempotency;this.audit=audit;this.actors=actors;this.facts=facts;this.ledger=ledger;this.notices=notices;}
    @Transactional public UUID requested(Actor actor,UUID payment,MercadoPagoReviewDto.Recheck input,String key) {
        AdminCatalogService.admin(actor);
        var body=new LinkedHashMap<String,Object>();body.put("paymentId",payment);body.put("reason",input.reason());
        return idempotency.execute(actor.id(),key,"mercadopago:review:request",body,UUID.class,()->{
            ApiException.require(Boolean.TRUE.equals(sql.queryForObject("SELECT EXISTS(SELECT 1 FROM mercado_pago_orders WHERE payment_id=? AND (untracked_refunds OR refund_history_incomplete OR chargeback_review))",Boolean.class,payment)),"REVIEW_NOT_FOUND","Caso financeiro em análise não encontrado.",404);
            UUID command=UUID.randomUUID();
            audit.record(actor.id(),"mercadopago_review_requested","payment",payment,Map.of("commandId",command,"reason",input.reason()));return command;
        });
    }
    @Transactional public MercadoPagoReviewDto.Checked checked(Actor actor,UUID payment,UUID command) {
        AdminCatalogService.admin(actor);
        var body=new LinkedHashMap<String,Object>();body.put("paymentId",payment);body.put("commandId",command);
        return idempotency.execute(actor.id(),command.toString(),"mercadopago:review:checked",body,MercadoPagoReviewDto.Checked.class,()->{
            audit.record(actor.id(),"mercadopago_review_checked","payment",payment,Map.of("commandId",command));return new MercadoPagoReviewDto.Checked(true);
        });
    }
    @Transactional public UUID resolutionRequested(Actor actor,UUID payment,MercadoPagoReviewDto.Resolve input,String key) {
        AdminCatalogService.admin(actor);
        return idempotency.executeUnorderedObject(actor.id(),key,"mercadopago:review:resolve-request",Map.of("paymentId",payment,"reason",input.reason(),"evidenceKey",input.evidenceKey()),UUID.class,()->{
            facts.read(payment);UUID command=UUID.randomUUID();audit.record(actor.id(),"mercadopago_resolution_requested","payment",payment,Map.of("commandId",command,"reason",input.reason(),"evidenceKey",input.evidenceKey()));return command;
        });
    }
    @Transactional(readOnly=true) public Optional<MercadoPagoReviewDto.Resolved> previous(Actor actor,UUID command) {
        AdminCatalogService.admin(actor);
        return sql.query("SELECT id,held_credit_cents,debt_cents FROM mercado_pago_review_resolutions WHERE command_id=? AND actor_id=?",(r,n)->new MercadoPagoReviewDto.Resolved(r.getObject("id",UUID.class),true,r.getLong("held_credit_cents"),r.getLong("debt_cents")),command,actor.id()).stream().findFirst();
    }
    @Transactional public MercadoPagoReviewDto.Resolved resolve(Actor actor,UUID payment,UUID command,MercadoPagoReviewDto.Resolve input) {
        AdminCatalogService.admin(actor);var prior=previous(actor,command);if(prior.isPresent())return prior.get();
        var identity=sql.queryForMap("SELECT p.passenger_id,q.driver_id FROM payments p JOIN ride_requests q ON q.id=p.request_id WHERE p.id=?",payment);
        actors.lockUsers(List.of((UUID)identity.get("passenger_id"),(UUID)identity.get("driver_id")));
        sql.queryForList("SELECT payment_id FROM mercado_pago_orders WHERE payment_id=? FOR UPDATE",payment);
        prior=previous(actor,command);if(prior.isPresent())return prior.get();
        var selected=facts.read(payment);var preview=selected.preview();
        ApiException.require(input.evidenceKey().equals(preview.evidenceKey()),"REVIEW_CHANGED","As evidências mudaram. Confira o caso atualizado antes de concluir.",409);
        ApiException.require(preview.available(),"REVIEW_REQUIRES_DECISION","Este caso ainda exige análise administrativa ou evidências adicionais. Nenhum saldo foi liberado.",409);
        UUID id=UUID.randomUUID(),journal=null;
        if(preview.heldCreditCents()>0)journal=ledger.post(LedgerCommand.transfer("review:"+id+":credit-reversal","external_credit_reversal","Crédito de cancelamento já devolvido pelo provedor",List.of(new Posting(selected.owner(),AccountKind.PASSENGER_CREDIT,-preview.heldCreditCents()),new Posting(null,AccountKind.PROVIDER_CLEARING,preview.heldCreditCents()))));
        sql.update("INSERT INTO mercado_pago_review_resolutions(id,command_id,payment_id,actor_id,reason,kind,evidence_key,receipt_id,reversal_cents,held_credit_cents,debt_cents,ledger_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",id,command,payment,actor.id(),input.reason(),preview.kind(),preview.evidenceKey(),selected.receipt(),preview.reversalCents(),preview.heldCreditCents(),preview.registeredDebtCents(),journal);
        for(var reference:selected.references())sql.update("INSERT INTO mercado_pago_resolved_refunds(provider_reference,resolution_id) VALUES(?,?)",reference,id);
        sql.update("UPDATE mercado_pago_orders SET untracked_refunds=false,refund_history_incomplete=false,chargeback_review=CASE WHEN ? IN ('settled_chargeback','reimbursed_chargeback') THEN false ELSE chargeback_review END,updated_at=now() WHERE payment_id=?",preview.kind(),payment);
        audit.record(actor.id(),"mercadopago_review_resolved","payment",payment,Map.of("resolutionId",id,"commandId",command,"reason",input.reason(),"kind",preview.kind(),"creditAdjustedCents",preview.heldCreditCents(),"registeredDebtCents",preview.registeredDebtCents(),"evidenceKey",preview.evidenceKey()));
        notices.notify(selected.owner(),"Análise financeira concluída","As movimentações deste pagamento foram conciliadas. Confira os valores e eventuais outras pendências na carteira.","wallet",payment);
        return new MercadoPagoReviewDto.Resolved(id,true,preview.heldCreditCents(),preview.registeredDebtCents());
    }
}
