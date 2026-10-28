package com.unicarona.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unicarona.payment.ledger.*;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

/** Fixed serialization vectors protect fingerprints stored before the backend migration. */
class LedgerFingerprintTest {
  static Stream<Arguments> historicalLayouts() {
    UUID owner = UUID.fromString("00000000-0000-4000-8000-000000000001"),
        payment = UUID.fromString("00000000-0000-4000-8000-000000000002"),
        parcel = UUID.fromString("00000000-0000-4000-8000-000000000003");
    var entries =
        List.of(
            new LedgerCommand.Posting(null, AccountKind.PROVIDER_CLEARING, -100),
            new LedgerCommand.Posting(owner, AccountKind.PASSENGER_CREDIT, 100));
    String postings =
        ",\"entries\":[{\"owner\":null,\"kind\":\"provider_clearing\",\"cents\":-100},{\"owner\":\"00000000-0000-4000-8000-000000000001\",\"kind\":\"passenger_credit\",\"cents\":100}]}";
    return Stream.of(
        Arguments.of(
            LedgerCommand.transfer("fixture", "funding", "Fixture", entries),
            "{\"kind\":\"funding\",\"reason\":\"Fixture\"" + postings),
        Arguments.of(
            LedgerCommand.receipt(payment, "Fixture", entries),
            "{\"kind\":\"receipt\",\"reason\":\"Fixture\",\"paymentId\":\"00000000-0000-4000-8000-000000000002\""
                + postings),
        Arguments.of(
            LedgerCommand.receiptCredit(payment, "Fixture", entries),
            "{\"kind\":\"receipt_credit\",\"paymentId\":\"00000000-0000-4000-8000-000000000002\",\"reason\":\"Fixture\""
                + postings),
        Arguments.of(
            LedgerCommand.block(parcel, payment, "Fixture", entries),
            "{\"kind\":\"dispute_hold\",\"parcelId\":\"00000000-0000-4000-8000-000000000003\",\"paymentId\":\"00000000-0000-4000-8000-000000000002\",\"reason\":\"Fixture\""
                + postings),
        Arguments.of(
            LedgerCommand.settle(parcel, payment, "credited", "Fixture", entries),
            "{\"kind\":\"credited\",\"paymentId\":\"00000000-0000-4000-8000-000000000002\",\"parcelId\":\"00000000-0000-4000-8000-000000000003\",\"reason\":\"Fixture\""
                + postings));
  }

  @ParameterizedTest
  @MethodSource("historicalLayouts")
  void preservesHistoricalFingerprintBytes(LedgerCommand command, String expected)
      throws Exception {
    assertEquals(expected, new ObjectMapper().writeValueAsString(command.fingerprintBody()));
  }
}
