package com.unicarona.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unicarona.payment.dto.FinanceDto;
import com.unicarona.payment.entity.LedgerHeader;
import com.unicarona.payment.ledger.*;
import com.unicarona.payment.repository.*;
import com.unicarona.shared.ApiException;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class LedgerService {
  private final LedgerRepository accounts;
  private final LedgerHeaderRepository headers;
  private final ObjectMapper json;

  public LedgerService(
      LedgerRepository accounts, LedgerHeaderRepository headers, ObjectMapper json) {
    this.accounts = accounts;
    this.headers = headers;
    this.json = json;
  }

  private record Account(UUID owner, AccountKind kind) {}

  @Transactional(propagation = Propagation.MANDATORY)
  public UUID post(LedgerCommand command) {
    BigInteger total = BigInteger.ZERO;
    for (var entry : command.entries()) {
      ApiException.require(
          entry.kind() != null
              && entry.cents() != 0
              && BigInteger.valueOf(entry.cents())
                      .abs()
                      .compareTo(BigInteger.valueOf(Amounts.MAXIMUM_SAFE))
                  <= 0,
          "LEDGER_UNBALANCED",
          "Movimentação financeira inválida.",
          500);
      total = total.add(BigInteger.valueOf(entry.cents()));
    }
    ApiException.require(
        command.entries().size() >= 2 && total.signum() == 0,
        "LEDGER_UNBALANCED",
        "Movimentação financeira inválida.",
        500);
    String hash;
    try {
      hash =
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(json.writeValueAsBytes(command.fingerprintBody())));
    } catch (Exception error) {
      throw new IllegalStateException("Cannot encode journal fingerprint.");
    }
    accounts.lockEvent(command.key());
    var previous = headers.findByEventKey(command.key());
    if (previous.isPresent()) {
      ApiException.require(
          hash.equals(previous.get().getBodyHash()),
          "IDEMPOTENCY_CONFLICT",
          "Evento financeiro repetido com dados diferentes.",
          409);
      return previous.get().getId();
    }
    var deltas = new LinkedHashMap<Account, Long>();
    var ids = new LinkedHashMap<Account, UUID>();
    command.entries().stream()
        .sorted(Comparator.comparing(e -> e.owner() + ":" + e.kind().value()))
        .forEach(
            entry -> {
              var account = new Account(entry.owner(), entry.kind());
              if (!ids.containsKey(account))
                ids.put(account, accounts.account(entry.owner(), entry.kind()));
              deltas.put(account, Amounts.add(deltas.getOrDefault(account, 0L), entry.cents()));
            });
    accounts.lockAccounts(ids.values());
    for (var delta : deltas.entrySet())
      if (delta.getKey().kind() != AccountKind.PROVIDER_CLEARING) {
        long balance =
            Amounts.add(
                accounts.balance(delta.getKey().owner(), delta.getKey().kind()), delta.getValue());
        ApiException.require(
            delta.getKey().kind() == AccountKind.PASSENGER_RECEIVABLE ? balance <= 0 : balance >= 0,
            "INSUFFICIENT_BALANCE",
            "Saldo insuficiente para esta operação.",
            409);
      }
    var header =
        headers.saveAndFlush(
            new LedgerHeader(
                command.key(),
                command.kind(),
                command.reason(),
                command.paymentId(),
                command.parcelId(),
                hash));
    deltas.forEach(
        (account, delta) -> {
          if (delta != 0) accounts.entry(header.getId(), ids.get(account), delta);
        });
    return header.getId();
  }

  @Transactional(readOnly = true)
  public long balance(UUID owner, AccountKind kind) {
    return accounts.balance(owner, kind);
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public FinanceDto.Wallet wallet(UUID owner) {
    var totals = accounts.totals(owner, false);
    var parcels = accounts.parcels(owner);
    return new FinanceDto.Wallet(
        parcels.future(),
        parcels.blocked(),
        value(totals, "driver_available"),
        value(totals, "passenger_credit"),
        Amounts.add(value(totals, "payout_pending"), value(totals, "refund_pending")),
        value(totals, "paid_out"),
        value(totals, "refunded"));
  }

  @Transactional(readOnly = true)
  public FinanceDto.Reconciliation reconciliation() {
    var totals = accounts.totals(null, true);
    long received = -value(totals, "provider_clearing");
    BigInteger allocation =
        totals.entrySet().stream()
            .filter(e -> !"provider_clearing".equals(e.getKey()))
            .map(e -> BigInteger.valueOf(e.getValue()))
            .reduce(BigInteger.ZERO, BigInteger::add);
    long allocated = Amounts.exact(allocation);
    return new FinanceDto.Reconciliation(
        received,
        allocated,
        Amounts.add(received, -allocated),
        value(totals, "future"),
        value(totals, "blocked"),
        value(totals, "driver_available"),
        value(totals, "passenger_credit"),
        value(totals, "commission"),
        value(totals, "retained"),
        Amounts.add(value(totals, "payout_pending"), value(totals, "refund_pending")),
        value(totals, "paid_out"),
        value(totals, "refunded"));
  }

  private long value(Map<String, Long> amounts, String kind) {
    return amounts.getOrDefault(kind, 0L);
  }
}
