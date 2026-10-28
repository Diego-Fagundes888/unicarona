package com.unicarona.payment.service;

import com.unicarona.job.service.MaintenanceTask;
import com.unicarona.payment.provider.MercadoPagoOrdersClient;
import com.unicarona.payment.config.MercadoPagoSettings;
import com.unicarona.shared.*;
import java.util.function.BooleanSupplier;
import org.slf4j.*;
import org.springframework.transaction.annotation.*;

/** Signed webhooks are primary; fresh GETs recover missing notifications and external changes. */
public class MercadoPagoReconciliation implements MaintenanceTask {
    private static final Logger LOG=LoggerFactory.getLogger(MercadoPagoReconciliation.class);
    private final MercadoPagoTransactions transactions;
    private final MercadoPagoService service;
    private final MercadoPagoOrdersClient client;
    private final MercadoPagoSettings settings;
    private final TransactionRetries retries;
    private final ExternalCalls external;
    public MercadoPagoReconciliation(MercadoPagoTransactions transactions,MercadoPagoService service,MercadoPagoOrdersClient client,MercadoPagoSettings settings,TransactionRetries retries,ExternalCalls external) {
        this.transactions=transactions;this.service=service;this.client=client;this.settings=settings;this.retries=retries;this.external=external;
    }
    @Override @Transactional(propagation=Propagation.NEVER) public void run(int batch,BooleanSupplier stopping) {
        if(batch<1 || batch>10000)throw new IllegalArgumentException("Reconciliation batch must be between 1 and 10000.");
        // Keep shutdown responsive even when the caller has a large maintenance batch.
        for(int n=0;n<Math.min(batch,20);n++) {
            if(stopping.getAsBoolean())return;
            var claimed=retries.call(transactions::claimReconciliation);if(claimed.isEmpty())return;var claim=claimed.get();
            try {
                var snapshot=external.call(settings.timeoutMs(),"PAYMENT_PROVIDER_UNAVAILABLE","Não foi possível atualizar o pagamento.",() -> client.get(claim.order()));
                ApiException.require(snapshot.paymentId().equals(claim.payment()) && snapshot.orderId().equals(claim.order()),"WEBHOOK_MISMATCH","Pagamento externo incompatível.",422);
                service.apply(snapshot);
                service.cancelIfNecessary(snapshot);
                retries.run(() -> transactions.finishReconciliation(claim,null));
                LOG.info("event=mercadopago_reconciled payment={} state={}",claim.payment(),snapshot.state());
            } catch(RuntimeException error) {
                String code=error instanceof ApiException api?api.code():"PROCESSING_ERROR";
                retries.run(() -> transactions.finishReconciliation(claim,code));
                LOG.warn("event=mercadopago_reconciliation_failed payment={} code={}",claim.payment(),code);
            }
        }
    }
}
