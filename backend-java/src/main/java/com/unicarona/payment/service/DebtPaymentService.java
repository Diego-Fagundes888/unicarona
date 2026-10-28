package com.unicarona.payment.service;

import com.unicarona.payment.config.MercadoPagoSettings;
import com.unicarona.payment.provider.MercadoPagoOrdersClient;
import com.unicarona.security.Actor;
import com.unicarona.shared.*;
import com.unicarona.job.service.MaintenanceTask;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.*;

/** Uses the official Orders client and shared signed-webhook dispatcher. */
public class DebtPaymentService implements MaintenanceTask {
    private final JdbcTemplate sql;private final DebtPaymentTransactions tx;
    private final MercadoPagoService payments;private final MercadoPagoOrdersClient client;
    private final MercadoPagoSettings settings;private final TransactionRetries retries;private final ExternalCalls external;
    public DebtPaymentService(JdbcTemplate sql,DebtPaymentTransactions tx,MercadoPagoService payments,MercadoPagoOrdersClient client,MercadoPagoSettings settings,TransactionRetries retries,ExternalCalls external) {
        this.sql=sql;this.tx=tx;this.payments=payments;this.client=client;this.settings=settings;this.retries=retries;this.external=external;
    }
    public DebtPaymentTransactions.Detail checkout(Actor actor,String key) {
        UUID id=retries.call(()->tx.prepare(actor,key));submit(id);return tx.detail(actor.id(),id);
    }
    public DebtPaymentTransactions.Detail detail(Actor actor,UUID id) {return tx.detail(actor.id(),id);}
    public DebtPaymentTransactions.Detail sync(Actor actor,UUID id) {
        tx.detail(actor.id(),id);refresh(id);return tx.detail(actor.id(),id);
    }
    public void submit(UUID id) {
        var command=retries.call(()->tx.start(id));if(command.isEmpty())return;var c=command.get();
        try {
            var snapshot=external.call(settings.timeoutMs(),"PAYMENT_PROVIDER_UNAVAILABLE","O Pix ainda não foi confirmado. Atualize o status para retomar a mesma cobrança.",()->client.createPix(c.id(),c.cents(),c.email(),1800));
            ApiException.require(snapshot.paymentId().equals(id) && snapshot.amountCents()==c.cents(),"WEBHOOK_MISMATCH","Pagamento externo incompatível.",422);
            payments.apply(snapshot);
        } catch(ApiException error){retries.run(()->tx.failed(id,error.code()));throw error;}
    }
    private void refresh(UUID id) {
        String order=sql.queryForObject("SELECT order_id FROM passenger_debt_payments WHERE id=?",String.class,id);
        if(order==null){submit(id);return;}
        var snapshot=external.call(settings.timeoutMs(),"PAYMENT_PROVIDER_UNAVAILABLE","Não foi possível atualizar o pagamento. Tente novamente.",()->client.get(order));
        ApiException.require(snapshot.paymentId().equals(id) && snapshot.orderId().equals(order),"WEBHOOK_MISMATCH","Pagamento externo incompatível.",422);
        payments.apply(snapshot);
    }
    @Override @Transactional(propagation=Propagation.NEVER) public void run(int batch,BooleanSupplier stopping) {
        if(batch<1 || batch>10000)throw new IllegalArgumentException("Invalid maintenance batch.");
        for(int n=0;n<Math.min(20,batch) && !stopping.getAsBoolean();n++) {
            var claimed=retries.call(tx::claim);if(claimed.isEmpty())return;var claim=claimed.get();
            try {refresh(claim.id());retries.run(()->tx.finish(claim,null));}
            catch(RuntimeException error){String code=error instanceof ApiException api?api.code():"PROCESSING_ERROR";retries.run(()->tx.finish(claim,code));}
        }
    }
}
