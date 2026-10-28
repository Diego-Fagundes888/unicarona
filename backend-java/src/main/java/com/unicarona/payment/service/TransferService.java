package com.unicarona.payment.service;

import com.unicarona.payment.config.BillingSettings;
import com.unicarona.payment.dto.TransferDto;
import com.unicarona.payment.provider.TransferProvider;
import com.unicarona.payment.repository.*;
import com.unicarona.security.Actor;
import com.unicarona.shared.*;
import com.unicarona.shared.dto.PageDto;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.ObjectProvider;

@Service
public class TransferService {
    private final TransferTransactions transactions;
    private final TransferRepository transfers;
    private final TransferReadRepository reads;
    private final WebhookReceipts receipts;
    private final TransferProvider provider;
    private final BillingSettings settings;
    private final TransactionRetries retries;
    private final ExternalCalls external;
    private final ObjectProvider<MercadoPagoRefundService> mercadoPagoRefunds;
    public TransferService(TransferTransactions transactions,TransferRepository transfers,TransferReadRepository reads,WebhookReceipts receipts,
        TransferProvider provider,BillingSettings settings,TransactionRetries retries,ExternalCalls external,ObjectProvider<MercadoPagoRefundService> mercadoPagoRefunds) {
        this.transactions=transactions;this.transfers=transfers;this.reads=reads;this.receipts=receipts;this.provider=provider;this.settings=settings;this.retries=retries;this.external=external;this.mercadoPagoRefunds=mercadoPagoRefunds;
    }
    public TransferDto.Created request(Actor actor,TransferDto.Request input,String key) { return retries.call(() -> transactions.request(actor,input,key)); }
    @Transactional(readOnly=true) public PageDto<TransferDto.Item> list(Actor actor,int limit,int offset) { return PageDto.from(reads.list(actor.id(),limit+1,offset),limit,offset); }
    public TransferDto.Received receive(TransferDto.Event event,String timestamp,String signature) {
        provider.authenticate(event,timestamp,signature);
        UUID receipt=retries.call(() -> receipts.record(settings.provider()+"-transfer",event.eventId(),event));
        try { return retries.call(() -> transactions.receive(receipt,event)); }
        catch(RuntimeException error) { receipts.failed(receipt,error);throw error; }
    }
    /** Called by the Java outbox worker; the provider deduplicates by transferId. */
    public void submit(UUID id) {
        var record=transfers.findById(id).orElseThrow(() -> new ApiException("TRANSFER_NOT_FOUND","Transferência não encontrada.",404));
        if(!"pending".equals(record.getStatus()))return;
        if("refund".equals(record.getType())) {var refunds=mercadoPagoRefunds.getIfAvailable();if(refunds!=null){refunds.submit(id);return;}}
        if(record.getProviderReference()!=null)return;
        var result=external.call(settings.timeoutMs(),"TRANSFER_PROVIDER_UNAVAILABLE","Provedor de transferências temporariamente indisponível.",
            () -> provider.create(new TransferProvider.Input(id,record.getCents(),record.getType())));
        retries.run(() -> transactions.submitted(id,result.reference()));
    }
}
