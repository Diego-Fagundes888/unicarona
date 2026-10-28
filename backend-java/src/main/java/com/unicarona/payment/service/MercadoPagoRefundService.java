package com.unicarona.payment.service;

import com.unicarona.payment.config.MercadoPagoSettings;
import com.unicarona.payment.provider.MercadoPagoOrdersClient;
import com.unicarona.shared.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.slf4j.*;
import org.springframework.transaction.annotation.*;

/** No synthetic transfer webhook: only the Orders API's fresh refund evidence completes a batch. */
public class MercadoPagoRefundService {
    private static final Logger LOG=LoggerFactory.getLogger(MercadoPagoRefundService.class);
    private final JdbcTemplate sql;private final MercadoPagoRefundTransactions transactions;private final MercadoPagoService payments;private final MercadoPagoOrdersClient client;private final MercadoPagoSettings settings;private final TransactionRetries retries;private final ExternalCalls external;
    public MercadoPagoRefundService(JdbcTemplate sql,MercadoPagoRefundTransactions transactions,MercadoPagoService payments,MercadoPagoOrdersClient client,MercadoPagoSettings settings,TransactionRetries retries,ExternalCalls external) {this.sql=sql;this.transactions=transactions;this.payments=payments;this.client=client;this.settings=settings;this.retries=retries;this.external=external;}
    @Transactional(propagation=Propagation.NEVER) public void submit(UUID transfer) {
        var ids=sql.queryForList("SELECT id FROM mercado_pago_refunds WHERE transfer_id=? AND status IN ('pending','processing') ORDER BY created_at,id",UUID.class,transfer);
        for(var id:ids) {
            var started=retries.call(()->transactions.start(id));if(started.isEmpty())continue;var command=started.get();
            try {
                if(command.reference()==null) {
                    // Both amounts are frozen identities. A later webhook must not change an uncertain POST's body.
                    var result=external.call(settings.timeoutMs(),"PAYMENT_PROVIDER_UNAVAILABLE","Não foi possível confirmar a devolução. A mesma operação será retomada.",()->client.refund(command.order(),command.transaction(),command.cents(),command.id(),command.cents()==command.externalCents()));
                    var refund=client.refundResult(result,command.order(),command.transaction(),command.cents(),transactions.linkedReferences(command));
                    retries.run(()->transactions.attach(command,refund));
                }
                var snapshot=external.call(settings.timeoutMs(),"PAYMENT_PROVIDER_UNAVAILABLE","Não foi possível conferir a devolução.",()->client.get(command.order()));
                ApiException.require(snapshot.orderId().equals(command.order()) && snapshot.paymentId().equals(command.payment()),"REFUND_MISMATCH","Devolução externa incompatível.",502);
                payments.apply(snapshot);
                LOG.info("event=mercadopago_refund_checked refund={} payment={} state={}",id,command.payment(),snapshot.state());
            }catch(ApiException error){retries.run(()->transactions.error(command,error.code()));LOG.warn("event=mercadopago_refund_failed refund={} code={}",id,error.code());}
        }
        ApiException.require(!Boolean.TRUE.equals(sql.queryForObject("SELECT EXISTS(SELECT 1 FROM mercado_pago_refunds WHERE transfer_id=? AND status IN ('pending','processing'))",Boolean.class,transfer)),"REFUND_PROCESSING","A devolução está sendo conferida no provedor.",503);
    }
}
