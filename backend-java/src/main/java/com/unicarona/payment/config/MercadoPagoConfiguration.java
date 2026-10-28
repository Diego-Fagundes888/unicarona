package com.unicarona.payment.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unicarona.auth.config.AuthSettings;
import com.unicarona.notification.service.NotificationService;
import com.unicarona.payment.provider.*;
import com.unicarona.payment.repository.MercadoPagoReadRepository;
import com.unicarona.payment.service.*;
import com.unicarona.security.CryptoService;
import com.unicarona.shared.ExternalCalls;
import com.unicarona.shared.IdempotencyExecutor;
import com.unicarona.shared.TransactionRetries;
import com.unicarona.shared.repository.ActorsRepository;
import com.unicarona.shared.repository.AuditRepository;
import com.unicarona.shared.repository.OutboxRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "PAYMENT_PROVIDER", havingValue = "mercadopago")
public class MercadoPagoConfiguration {
  @Bean
  MercadoPagoSettings mercadoPagoSettings(Environment env) {
    return MercadoPagoSettings.from(env);
  }

  @Bean
  @ConditionalOnMissingBean
  MercadoPagoOrdersClient mercadoPagoClient(MercadoPagoSettings settings, ObjectMapper json) {
    return new MercadoPagoOrdersClient(settings, json);
  }

  @Bean
  MercadoPagoTransactions mercadoPagoTransactions(
      JdbcTemplate sql,
      PaymentTransactions payments,
      WebhookReceipts receipts,
      MercadoPagoSettings settings,
      AuthSettings auth,
      CryptoService crypto,
      ObjectMapper json,
      IdempotencyExecutor idempotency,
      OutboxRepository jobs,
      MercadoPagoRefundTransactions refunds,
      ActorsRepository actors) {
    return new MercadoPagoTransactions(
        sql, payments, receipts, settings, auth, crypto, json, idempotency, jobs, refunds, actors);
  }

  @Bean
  MercadoPagoService mercadoPagoService(
      MercadoPagoOrdersClient client,
      MercadoPagoSettings settings,
      JdbcTemplate sql,
      MercadoPagoTransactions transactions,
      WebhookReceipts receipts,
      TransactionRetries retries,
      ExternalCalls external,
      ObjectMapper json,
      DebtPaymentTransactions debtPayments,
      MercadoPagoReadRepository reads) {
    return new MercadoPagoService(
        client,
        settings,
        sql,
        transactions,
        receipts,
        retries,
        external,
        json,
        debtPayments,
        reads);
  }

  @Bean
  DebtPaymentService debtPaymentService(
      JdbcTemplate sql,
      DebtPaymentTransactions tx,
      MercadoPagoService payments,
      MercadoPagoOrdersClient client,
      MercadoPagoSettings settings,
      TransactionRetries retries,
      ExternalCalls external) {
    return new DebtPaymentService(sql, tx, payments, client, settings, retries, external);
  }

  @Bean
  PaymentProvider mercadoPagoProvider(MercadoPagoService service) {
    return new MercadoPagoPaymentProvider(service);
  }

  @Bean
  TransferProvider mercadoPagoTransferProvider() {
    return new UnavailableTransferProvider();
  }

  @Bean
  MercadoPagoReconciliation mercadoPagoReconciliation(
      MercadoPagoTransactions transactions,
      MercadoPagoService service,
      MercadoPagoOrdersClient client,
      MercadoPagoSettings settings,
      TransactionRetries retries,
      ExternalCalls external) {
    return new MercadoPagoReconciliation(
        transactions, service, client, settings, retries, external);
  }

  @Bean
  MercadoPagoRefundTransactions mercadoPagoRefundTransactions(
      JdbcTemplate sql,
      ActorsRepository actors,
      LedgerService ledger,
      NotificationService notices,
      AuditRepository audit,
      PassengerDebtService debts) {
    return new MercadoPagoRefundTransactions(sql, actors, ledger, notices, audit, debts);
  }

  @Bean
  MercadoPagoRefundService mercadoPagoRefundService(
      JdbcTemplate sql,
      MercadoPagoRefundTransactions transactions,
      MercadoPagoService payments,
      MercadoPagoOrdersClient client,
      MercadoPagoSettings settings,
      TransactionRetries retries,
      ExternalCalls external) {
    return new MercadoPagoRefundService(
        sql, transactions, payments, client, settings, retries, external);
  }
}
