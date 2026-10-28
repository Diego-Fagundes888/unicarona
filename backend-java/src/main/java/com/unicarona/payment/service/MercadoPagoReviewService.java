package com.unicarona.payment.service;

import com.unicarona.admin.service.AdminCatalogService;
import com.unicarona.payment.config.MercadoPagoSettings;
import com.unicarona.payment.dto.MercadoPagoReviewDto;
import com.unicarona.payment.provider.MercadoPagoOrdersClient;
import com.unicarona.security.Actor;
import com.unicarona.shared.*;
import com.unicarona.shared.dto.PageDto;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service @ConditionalOnProperty(name="PAYMENT_PROVIDER",havingValue="mercadopago")
public class MercadoPagoReviewService {
    private static final String SELECT="SELECT m.*,p.passenger_id,u.name AS passenger_name,p.status AS payment_status,p.amount_cents,p.external_cents FROM mercado_pago_orders m JOIN payments p ON p.id=m.payment_id JOIN users u ON u.id=p.passenger_id ";
    private final JdbcTemplate sql;private final LedgerService ledger;private final MercadoPagoReviewTransactions transactions;
    private final MercadoPagoService payments;private final MercadoPagoOrdersClient client;private final MercadoPagoSettings settings;
    private final ExternalCalls external;private final TransactionRetries retries;
    private final MercadoPagoResolutionFacts facts;
    public MercadoPagoReviewService(JdbcTemplate sql,LedgerService ledger,MercadoPagoReviewTransactions transactions,MercadoPagoService payments,MercadoPagoOrdersClient client,MercadoPagoSettings settings,ExternalCalls external,TransactionRetries retries,MercadoPagoResolutionFacts facts) {
        this.sql=sql;this.ledger=ledger;this.transactions=transactions;this.payments=payments;this.client=client;this.settings=settings;this.external=external;this.retries=retries;this.facts=facts;
    }
    private MercadoPagoReviewDto.Item item(java.sql.ResultSet r,int n) throws java.sql.SQLException {
        var checked=r.getTimestamp("review_checked_at");if(checked==null)checked=r.getTimestamp("checked_at");
        return new MercadoPagoReviewDto.Item(r.getObject("payment_id",UUID.class),r.getObject("passenger_id",UUID.class),r.getString("passenger_name"),r.getString("order_id"),r.getString("payment_status"),r.getString("state"),r.getString("provider_status"),r.getString("status_detail"),r.getInt("amount_cents"),r.getInt("external_cents"),r.getInt("refunded_cents"),r.getBoolean("untracked_refunds"),r.getBoolean("refund_history_incomplete"),r.getBoolean("chargeback_review"),checked==null?null:ApiDates.timestamp(checked.toInstant()),r.getString("reconcile_error_code"));
    }
    @Transactional(readOnly=true) public PageDto<MercadoPagoReviewDto.Item> list(Actor actor,Pagination page) {
        AdminCatalogService.admin(actor);
        return PageDto.from(sql.query(SELECT+"WHERE m.untracked_refunds OR m.refund_history_incomplete OR m.chargeback_review ORDER BY m.updated_at DESC,m.payment_id LIMIT ? OFFSET ?",this::item,page.limit()+1,page.offset()),page.limit(),page.offset());
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ) public MercadoPagoReviewDto.Detail detail(Actor actor,UUID payment) {
        AdminCatalogService.admin(actor);var rows=sql.query(SELECT+"WHERE m.payment_id=?",this::item,payment);
        ApiException.require(!rows.isEmpty(),"REVIEW_NOT_FOUND","Caso financeiro não encontrado.",404);var selected=rows.getFirst();
        var refunds=sql.query("""
            SELECT o.*,EXISTS(SELECT 1 FROM mercado_pago_refunds r WHERE r.payment_id=o.payment_id AND r.provider_reference=o.provider_reference AND r.transfer_id IS NOT NULL) AS linked,EXISTS(SELECT 1 FROM mercado_pago_resolved_refunds r WHERE r.provider_reference=o.provider_reference) AS reconciled
            FROM mercado_pago_observed_refunds o WHERE o.payment_id=? ORDER BY o.provider_reference LIMIT 101
            """,(r,n)->new MercadoPagoReviewDto.ObservedRefund(r.getString("provider_reference"),r.getString("transaction_id"),r.getInt("amount_cents"),r.getString("status"),r.getBoolean("linked"),r.getBoolean("reconciled"),ApiDates.timestamp(r.getTimestamp("last_observed_at").toInstant())),payment);
        var counts=sql.queryForMap("SELECT count(*) AS pending,count(*) FILTER(WHERE attempts>0) AS uncertain FROM mercado_pago_refunds WHERE payment_id=? AND status IN ('pending','processing') AND transfer_id IS NOT NULL",payment);
        return new MercadoPagoReviewDto.Detail(selected,ledger.wallet(selected.passengerId()),List.copyOf(refunds.subList(0,Math.min(100,refunds.size()))),refunds.size()>100,((Number)counts.get("pending")).intValue(),((Number)counts.get("uncertain")).intValue(),facts.read(payment).preview());
    }
    @Transactional(propagation=Propagation.NEVER) public MercadoPagoReviewDto.Checked recheck(Actor actor,UUID payment,MercadoPagoReviewDto.Recheck input,String key) {
        AdminCatalogService.admin(actor);UUID command=retries.call(()->transactions.requested(actor,payment,input,key));
        String order=sql.queryForObject("SELECT order_id FROM mercado_pago_orders WHERE payment_id=?",String.class,payment);
        var snapshot=external.call(settings.timeoutMs(),"PAYMENT_PROVIDER_UNAVAILABLE","Não foi possível consultar o caso no provedor. Tente novamente com a mesma solicitação.",()->client.get(order));
        ApiException.require(order.equals(snapshot.orderId()) && payment.equals(snapshot.paymentId()),"WEBHOOK_MISMATCH","Pagamento externo incompatível.",422);
        payments.apply(snapshot);return retries.call(()->transactions.checked(actor,payment,command));
    }
    @Transactional(propagation=Propagation.NEVER) public MercadoPagoReviewDto.Resolved resolve(Actor actor,UUID payment,MercadoPagoReviewDto.Resolve input,String key) {
        AdminCatalogService.admin(actor);UUID command=retries.call(()->transactions.resolutionRequested(actor,payment,input,key));
        var previous=transactions.previous(actor,command);if(previous.isPresent())return previous.get();
        String order=sql.queryForObject("SELECT order_id FROM mercado_pago_orders WHERE payment_id=?",String.class,payment);
        var snapshot=external.call(settings.timeoutMs(),"PAYMENT_PROVIDER_UNAVAILABLE","Não foi possível verificar a conciliação no provedor. Tente novamente com a mesma solicitação.",()->client.get(order));
        ApiException.require(order.equals(snapshot.orderId()) && payment.equals(snapshot.paymentId()),"WEBHOOK_MISMATCH","Pagamento externo incompatível.",422);
        payments.apply(snapshot);return retries.call(()->transactions.resolve(actor,payment,command,input));
    }
}
