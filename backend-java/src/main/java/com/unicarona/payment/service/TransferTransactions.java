package com.unicarona.payment.service;

import com.unicarona.payment.dto.TransferDto;
import com.unicarona.payment.entity.Transfer;
import com.unicarona.payment.ledger.*;
import com.unicarona.payment.ledger.LedgerCommand.Posting;
import com.unicarona.payment.repository.*;
import com.unicarona.security.Actor;
import com.unicarona.notification.service.NotificationService;
import com.unicarona.shared.*;
import com.unicarona.shared.repository.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.ObjectProvider;
import com.unicarona.payment.config.BillingSettings;

@Service
public class TransferTransactions {
    private final TransferRepository transfers;
    private final TransferReadRepository reads;
    private final ActorsRepository actors;
    private final IdempotencyExecutor idempotency;
    private final LedgerService ledger;
    private final OutboxRepository jobs;
    private final AuditRepository audit;
    private final NotificationService notices;
    private final WebhookReceiptRepository events;
    private final WebhookReceipts receipts;
    private final ObjectProvider<MercadoPagoRefundTransactions> mercadoPagoRefunds;
    private final BillingSettings settings;
    public TransferTransactions(TransferRepository transfers,TransferReadRepository reads,ActorsRepository actors,IdempotencyExecutor idempotency,
        LedgerService ledger,OutboxRepository jobs,AuditRepository audit,NotificationService notices,WebhookReceiptRepository events,WebhookReceipts receipts,ObjectProvider<MercadoPagoRefundTransactions> mercadoPagoRefunds,BillingSettings settings) {
        this.transfers=transfers;this.reads=reads;this.actors=actors;this.idempotency=idempotency;this.ledger=ledger;this.jobs=jobs;this.audit=audit;this.notices=notices;this.events=events;this.receipts=receipts;this.mercadoPagoRefunds=mercadoPagoRefunds;this.settings=settings;
    }
    @Transactional public TransferDto.Created request(Actor actor,TransferDto.Request input,String key) {
        return idempotency.execute(actor.id(),key,"transfer:create",input,TransferDto.Created.class,() -> {
            actors.lockUser(actor.id());String cadence=reads.cadence(actor.id()).orElseThrow(() -> new ApiException("PROFILE_REQUIRED","Perfil necessário.",422));
            boolean withdraw="withdraw".equals(input.type());
            ApiException.require(!withdraw || !"mercadopago".equals(settings.provider()),"PAYOUT_NOT_CONFIGURED","O repasse ainda não está habilitado. Seu saldo continua registrado.",503);
            var record=transfers.saveAndFlush(new Transfer(actor.id(),input.type(),input.cents(),withdraw?cadence:null));
            if(!withdraw) {var planner=mercadoPagoRefunds.getIfAvailable();if(planner!=null)planner.plan(record);}
            var source=withdraw?AccountKind.DRIVER_AVAILABLE:AccountKind.PASSENGER_CREDIT;var target=withdraw?AccountKind.PAYOUT_PENDING:AccountKind.REFUND_PENDING;
            ledger.post(LedgerCommand.transfer("transfer:"+record.getId()+":requested","transfer_requested","Saldo separado para "+input.type(),List.of(new Posting(actor.id(),source,-input.cents()),new Posting(actor.id(),target,input.cents()))));
            jobs.enqueue("transfer","transfer:"+record.getId(),Map.of("transferId",record.getId()));
            audit.record(actor.id(),"transfer_requested","transfer",record.getId(),Map.of("type",input.type(),"cents",input.cents()));return new TransferDto.Created(record.getId(),"pending");
        });
    }
    @Transactional public TransferDto.Received receive(UUID eventId,TransferDto.Event event) {
        var receipt=events.lockById(eventId).orElseThrow(() -> new IllegalStateException("Authenticated receipt disappeared."));
        if("processed".equals(receipt.getStatus())) return new TransferDto.Received(true,true);
        UUID transferId=UUID.fromString(event.transferId());actors.lockUser(reads.owner(transferId));
        var record=transfers.lockById(transferId).orElseThrow(() -> new ApiException("TRANSFER_NOT_FOUND","Transferência não encontrada.",404));
        ApiException.require(event.reference().equals(record.getProviderReference()) && record.getCents()==event.amountCents(),"WEBHOOK_MISMATCH","Evento não corresponde à transferência.",422);
        boolean pending="pending".equals(record.getStatus());
        if(!pending) ApiException.require(record.getStatus().equals(event.status()),"TRANSFER_STATE_CONFLICT","O provedor informou resultados contraditórios. Necessária conciliação administrativa.",409);
        else {
            boolean withdraw="withdraw".equals(record.getType()),approved="approved".equals(event.status());
            var from=withdraw?AccountKind.PAYOUT_PENDING:AccountKind.REFUND_PENDING;
            var to=approved?(withdraw?AccountKind.PAID_OUT:AccountKind.REFUNDED):(withdraw?AccountKind.DRIVER_AVAILABLE:AccountKind.PASSENGER_CREDIT);
            ledger.post(LedgerCommand.transfer("transfer:"+record.getId()+":completed",approved?record.getType():"transfer_failed",approved?"Transferência confirmada pelo provedor":"Falha do provedor; saldo restaurado",List.of(new Posting(record.getUserId(),from,-record.getCents()),new Posting(record.getUserId(),to,record.getCents()))));
            record.complete(event.status());
            notices.notify(record.getUserId(),approved?"Transferência concluída":"Transferência não concluída",approved?"Resultado confirmado pelo provedor.":"O saldo voltou para sua carteira.","wallet",record.getId());
            audit.record(null,"transfer_provider_result","transfer",record.getId(),Map.of("status",event.status()));
        }
        receipts.processed(eventId);return new TransferDto.Received(true,!pending);
    }
    @Transactional public void submitted(UUID id,String reference) { transfers.submitted(id,reference); }
}
