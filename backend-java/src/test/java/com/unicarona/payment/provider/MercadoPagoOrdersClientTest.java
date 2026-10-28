package com.unicarona.payment.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import com.unicarona.payment.config.MercadoPagoSettings;
import com.unicarona.shared.ApiException;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class MercadoPagoOrdersClientTest {
    private final ObjectMapper json=new ObjectMapper();
    private final UUID id=UUID.fromString("00000000-0000-4000-8000-000000000001");
    private final String orderId="ORD01M28P44G5FG8RJPM579EH56FV";
    private final MercadoPagoSettings settings=new MercadoPagoSettings("synthetic-token","synthetic-public","synthetic-secret-for-unit-testing-32","test","http://localhost:3002",1000);
    private MercadoPagoOrdersClient client() { return new MercadoPagoOrdersClient(settings,json); }
    private String order(String status,String detail) {
        return "{\"id\":\""+orderId+"\",\"external_reference\":\"UNICARONA_"+id+"\",\"total_amount\":\"12.34\",\"country_code\":\"BRA\",\"status\":\""+status+"\",\"status_detail\":\""+detail+"\",\"version\":1,\"transactions\":{\"payments\":[{\"id\":\"PAY01M28P44GDSD4JYTSK5SYZT6BH\",\"status\":\""+status+"\",\"status_detail\":\""+detail+"\",\"paid_amount\":\"12.34\",\"payment_method\":{\"id\":\"pix\"}}]}}";
    }
    @Test void approvedRequiresAnAccreditedTransactionAndExactPaidAmount() throws Exception {
        var snapshot=client().snapshot(json.readTree(order("processed","accredited")));
        assertEquals(MercadoPagoStatus.APPROVED,snapshot.state());assertEquals(1234,snapshot.amountCents());assertEquals(id,snapshot.paymentId());
        var invalid=json.readTree(order("processed","accredited"));((com.fasterxml.jackson.databind.node.ObjectNode)invalid.path("transactions").path("payments").path(0)).put("paid_amount","0.00");
        assertEquals("PAYMENT_PROVIDER_INVALID",assertThrows(ApiException.class,()->client().snapshot(invalid)).code());
    }
    @Test void refundsAreNotMisclassifiedAsNewReceipts() throws Exception {
        assertEquals(MercadoPagoStatus.REFUNDED,client().snapshot(json.readTree(order("processed","refunded"))).state());
        assertEquals(MercadoPagoStatus.PARTIALLY_REFUNDED,client().snapshot(json.readTree(order("processed","partially_refunded"))).state());
    }
    @Test void partialRefundResultUsesUniqueTransactionAndAmountAcrossHistory() throws Exception {
        var result=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(order("refunded","refunded"));
        var refunds=((com.fasterxml.jackson.databind.node.ObjectNode)result.path("transactions")).putArray("refunds");
        String transaction="PAY01M28P44GDSD4JYTSK5SYZT6BH",prior="REF01M28P44GDSD4JYTSK5SYZT6BA",current="REF01M28P44GDSD4JYTSK5SYZT6BB";
        refunds.addObject().put("id",prior).put("transaction_id",transaction).put("amount","1.00").put("status","processed");
        refunds.addObject().put("id",current).put("transaction_id",transaction).put("amount","11.34").put("status","processed");
        assertEquals(current,client().refundResult(result,orderId,transaction,1134).id());
        ((com.fasterxml.jackson.databind.node.ObjectNode)refunds.get(1)).put("amount","1.00");
        assertEquals("REFUND_MISMATCH",assertThrows(ApiException.class,()->client().refundResult(result,orderId,transaction,100)).code());
        assertEquals(current,client().refundResult(result,orderId,transaction,100,Set.of(prior)).id());
        assertEquals("REFUND_MISMATCH",assertThrows(ApiException.class,()->client().refundResult(result,orderId,transaction,100,Set.of(prior,current))).code());
    }
    @Test void partialRefundHistoryStillRejectsWrongIdentityAmountAndMalformedHistoricalEvidence() throws Exception {
        var result=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(order("refunded","refunded"));
        var refunds=((com.fasterxml.jackson.databind.node.ObjectNode)result.path("transactions")).putArray("refunds");
        String transaction="PAY01M28P44GDSD4JYTSK5SYZT6BH",reference="REF01M28P44GDSD4JYTSK5SYZT6BA";
        refunds.addObject().put("id",reference).put("transaction_id",transaction).put("amount","1.00").put("status","processed");
        for(var check:List.<org.junit.jupiter.api.function.Executable>of(
            ()->client().refundResult(result,"ORD01M28P44G5FG8RJPM579EH56FA",transaction,100),
            ()->client().refundResult(result,orderId,"PAY01M28P44GDSD4JYTSK5SYZT6BA",100),
            ()->client().refundResult(result,orderId,transaction,101)))
            assertEquals("REFUND_MISMATCH",assertThrows(ApiException.class,check).code());
        refunds.add(refunds.get(0).deepCopy());assertEquals("REFUND_MISMATCH",assertThrows(ApiException.class,()->client().refundResult(result,orderId,transaction,100,Set.of(reference))).code());
        ((com.fasterxml.jackson.databind.node.ObjectNode)refunds.get(1)).put("id","malformed").put("amount","2.00");
        assertEquals("REFUND_MISMATCH",assertThrows(ApiException.class,()->client().refundResult(result,orderId,transaction,100)).code());
    }
    @Test void absentDeviceMetadataPreservesLegacyCommandEncodingAndHashInput() throws Exception {
        String legacy="{\"token\":\"synthetic-card-token\",\"method\":\"master\",\"type\":\"credit_card\",\"installments\":1,\"identificationType\":\"CPF\",\"identificationNumber\":\"12345678909\"}";
        var input=json.readValue(legacy,com.unicarona.payment.dto.MercadoPagoDto.CardInput.class);
        assertNull(input.deviceSessionId());assertEquals(legacy,json.writeValueAsString(input));assertNull(input.card().deviceSessionId());assertEquals("CardInput[REDACTED]",input.toString());
    }
    @Test void deviceMetadataIsAValidatedHeaderAndNeverChangesTheOrderBody() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var device=new AtomicReference<String>();var sent=new AtomicReference<JsonNode>();var calls=new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/users/me",request->{byte[] bytes="{\"country_id\":\"BR\",\"tags\":[\"test_user\"]}".getBytes();request.sendResponseHeaders(200,bytes.length);request.getResponseBody().write(bytes);request.close();});
        server.createContext("/v1/orders",request->{calls.incrementAndGet();device.set(request.getRequestHeaders().getFirst("X-meli-session-id"));sent.set(json.readTree(request.getRequestBody().readAllBytes()));byte[] bytes=order("processed","accredited").getBytes();request.sendResponseHeaders(201,bytes.length);request.getResponseBody().write(bytes);request.close();});server.start();
        try {
            var gateway=new MercadoPagoOrdersClient(settings,json,URI.create("http://127.0.0.1:"+server.getAddress().getPort()));
            String marker="synthetic-device+/opaque==";var card=new MercadoPagoOrdersClient.Card("synthetic-card-token","master","credit_card",1,"CPF","12345678909",marker);
            gateway.create(id,1234,"passenger@example.test",Instant.now().plusSeconds(300),card);assertEquals(marker,device.get());assertFalse(sent.get().toString().contains(marker));assertEquals("Card[REDACTED]",card.toString());
            for(String invalid:List.of("synthetic\r\nAuthorization: injected","short","x".repeat(1025))) {
                assertEquals("CARD_DATA_INVALID",assertThrows(ApiException.class,()->gateway.create(id,1234,"passenger@example.test",Instant.now().plusSeconds(300),new MercadoPagoOrdersClient.Card("synthetic-card-token","master","credit_card",1,"CPF","12345678909",invalid))).code());
            }
            assertEquals(1,calls.get());
        }finally{server.stop(0);}
    }
    @Test void rejectedOrdersExposeOnlySupportedReasonsFromTheVerifiedTransaction() throws Exception {
        for(String reason:List.of("insufficient_amount","amount_limit_exceeded","bad_filled_card_data","rejected_by_issuer","3ds_challenge_expired")) {
            var value=json.readTree(order("failed","failed"));
            ((com.fasterxml.jackson.databind.node.ObjectNode)value.path("transactions").path("payments").path(0)).put("status_detail",reason);
            var snapshot=client().snapshot(value);assertEquals(MercadoPagoStatus.REJECTED,snapshot.state());assertEquals(reason,snapshot.statusDetail());
        }
        var value=json.readTree(order("failed","failed"));
        ((com.fasterxml.jackson.databind.node.ObjectNode)value.path("transactions").path("payments").path(0)).put("status_detail","synthetic_private_provider_message");
        assertEquals("failed",client().snapshot(value).statusDetail());
        value=json.readTree(order("action_required","pending_challenge"));
        ((com.fasterxml.jackson.databind.node.ObjectNode)value.path("transactions").path("payments").path(0)).put("status_detail","insufficient_amount");
        assertEquals("pending_challenge",client().snapshot(value).statusDetail());
    }
    @Test void rejectsForeignCurrencyProductionModeAndMalformedReference() throws Exception {
        for(String body:List.of(order("processing","in_process").replace("BRA","ARG"),
            order("processing","in_process").replace("UNICARONA_","OTHER_"),
            order("processing","in_process").replace("UNICARONA_","UNICARONA:"),
            order("processing","in_process").replace("\"version\":1","\"version\":1,\"live_mode\":true"),
            order("processing","in_process").replace("\"version\":1","\"version\":1,\"currency\":\"ARS\""),
            order("processing","in_process").replace("12.34","12.345"))) {
            var value=json.readTree(body);assertThrows(ApiException.class,()->client().snapshot(value));
        }
    }
    @Test void refusesUntrustedTicketUrls() throws Exception {
        var value=json.readTree(order("action_required","waiting_transfer"));
        ((com.fasterxml.jackson.databind.node.ObjectNode)value.path("transactions").path("payments").path(0).path("payment_method")).put("ticket_url","https://example.com/fake");
        assertThrows(ApiException.class,()->client().snapshot(value));
    }
    @Test void pixUsesExactServerAmountExternalReferenceAndStableIdempotencyKey() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var sent=new AtomicReference<String>();var key=new AtomicReference<String>();
        server.createContext("/users/me",request->{byte[] bytes="{\"country_id\":\"BR\",\"tags\":[\"test_user\"]}".getBytes();request.sendResponseHeaders(200,bytes.length);request.getResponseBody().write(bytes);request.close();});
        server.createContext("/v1/orders",request->{sent.set(new String(request.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));key.set(request.getRequestHeaders().getFirst("X-Idempotency-Key"));String reference=json.readTree(sent.get()).path("external_reference").asText();boolean valid=reference.matches("[A-Za-z0-9_-]{1,64}");byte[] bytes=(valid?order("action_required","waiting_transfer"):"{\"errors\":[{\"code\":\"property_value\"}]}").getBytes(java.nio.charset.StandardCharsets.UTF_8);request.sendResponseHeaders(valid?201:400,bytes.length);request.getResponseBody().write(bytes);request.close();});
        server.start();
        try {
            var gateway=new MercadoPagoOrdersClient(settings,json,URI.create("http://127.0.0.1:"+server.getAddress().getPort()));
            gateway.create(id,1234,"passenger@example.test",Instant.now().plusSeconds(300),null);
            var request=json.readTree(sent.get());assertEquals("12.34",request.path("total_amount").asText());
            assertEquals("UNICARONA_"+id,request.path("external_reference").asText());assertEquals(id.toString(),key.get());
            assertEquals("test_user_br@testuser.com",request.path("payer").path("email").asText());assertEquals("APRO",request.path("payer").path("first_name").asText());
            assertEquals("pix",request.path("transactions").path("payments").path(0).path("payment_method").path("id").asText());
            assertEquals("PT1800S",request.path("transactions").path("payments").path(0).path("expiration_time").asText());
        } finally { server.stop(0); }
    }
    @Test void cardSandboxUsesTheOfficialTestPayerAndLiveModePreservesTheServerEmail() throws Exception {
        for(String environment:List.of("test","production")) {
            HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var sent=new AtomicReference<String>();
            server.createContext("/users/me",request->{byte[] bytes=("test".equals(environment)?"{\"country_id\":\"BR\",\"tags\":[\"test_user\"]}":"{\"country_id\":\"BR\",\"tags\":[]}").getBytes();request.sendResponseHeaders(200,bytes.length);request.getResponseBody().write(bytes);request.close();});
            server.createContext("/v1/orders",request->{sent.set(new String(request.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));var security=json.readTree(sent.get()).path("config").path("online").path("transaction_security");boolean valid="on_fraud_risk".equals(security.path("validation").asText()) && "required".equals(security.path("liability_shift").asText());byte[] bytes=(valid?order("processed","accredited"):"{\"errors\":[{\"code\":\"required_properties\"}]}").getBytes(java.nio.charset.StandardCharsets.UTF_8);request.sendResponseHeaders(valid?201:400,bytes.length);request.getResponseBody().write(bytes);request.close();});server.start();
            try {
                var configuration=new MercadoPagoSettings("synthetic-token","synthetic-public","synthetic-secret-for-unit-testing-32",environment,"http://localhost:3002",1000);
                var gateway=new MercadoPagoOrdersClient(configuration,json,URI.create("http://127.0.0.1:"+server.getAddress().getPort()));
                gateway.create(id,1234,"passenger@example.test",Instant.now().plusSeconds(300),new MercadoPagoOrdersClient.Card("synthetic-card-token","master","credit_card",1,"CPF","12345678909"));
                assertEquals("test".equals(environment)?"test@testuser.com":"passenger@example.test",json.readTree(sent.get()).path("payer").path("email").asText());
            }finally{server.stop(0);}
        }
    }
    @Test void freshOrderLookupRejectsAnAccountFromTheWrongEnvironmentBeforeReadingTheOrder() throws Exception {
        for(String account:List.of("{\"country_id\":\"BR\",\"tags\":[]}","{\"country_id\":\"AR\",\"tags\":[\"test_user\"]}")) {
            HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var reads=new java.util.concurrent.atomic.AtomicInteger();
            server.createContext("/users/me",request->{byte[] bytes=account.getBytes();request.sendResponseHeaders(200,bytes.length);request.getResponseBody().write(bytes);request.close();});
            server.createContext("/v1/orders",request->{reads.incrementAndGet();byte[] bytes=order("processed","accredited").getBytes();request.sendResponseHeaders(200,bytes.length);request.getResponseBody().write(bytes);request.close();});server.start();
            try {
                var gateway=new MercadoPagoOrdersClient(settings,json,URI.create("http://127.0.0.1:"+server.getAddress().getPort()));
                var error=assertThrows(ApiException.class,()->gateway.get(orderId));assertEquals("PAYMENT_ENVIRONMENT_MISMATCH",error.code());assertEquals(0,reads.get());
            }finally{server.stop(0);}
        }
    }
    @Test void issuerRefusalEnvelopeRequiresFreshOrderLookupAndDoesNotTrustItsState() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var reads=new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/users/me",request->{byte[] bytes="{\"country_id\":\"BR\",\"tags\":[\"test_user\"]}".getBytes();request.sendResponseHeaders(200,bytes.length);request.getResponseBody().write(bytes);request.close();});
        server.createContext("/v1/orders",request->{
            boolean lookup="GET".equals(request.getRequestMethod());
            if(lookup)reads.incrementAndGet();
            // Contradictory envelope status must never override the fresh GET.
            byte[] bytes=(lookup?order("failed","rejected_by_issuer"):"{\"errors\":[{\"code\":\"failed\",\"message\":\"PRIVATE_SECRET_BODY\"}],\"data\":{"+"\"id\":\""+orderId+"\",\"status\":\"processed\",\"status_detail\":\"accredited\"}}").getBytes();
            request.sendResponseHeaders(lookup?200:402,bytes.length);request.getResponseBody().write(bytes);request.close();
        });server.start();
        try {
            var gateway=new MercadoPagoOrdersClient(settings,json,URI.create("http://127.0.0.1:"+server.getAddress().getPort()));
            var result=gateway.create(id,1234,"passenger@example.test",Instant.now().plusSeconds(300),new MercadoPagoOrdersClient.Card("synthetic-card-token","master","credit_card",1,"CPF","12345678909"));
            assertEquals(MercadoPagoStatus.REJECTED,result.state());assertEquals(1,reads.get());assertFalse(result.toString().contains("PRIVATE_SECRET_BODY"));
        }finally{server.stop(0);}
    }
    @Test void unavailableAndUnauthorizedErrorsDoNotLeakProviderBodies() throws Exception {
        for(int code:List.of(401,403,423,429,500)) {
            HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/users/me",request->{byte[] body="{\"country_id\":\"BR\",\"tags\":[\"test_user\"]}".getBytes();request.sendResponseHeaders(200,body.length);request.getResponseBody().write(body);request.close();});
            server.createContext("/v1/orders",request->{byte[] body="PRIVATE_SECRET_BODY".getBytes();request.sendResponseHeaders(code,body.length);request.getResponseBody().write(body);request.close();});server.start();
            try {
                var gateway=new MercadoPagoOrdersClient(settings,json,URI.create("http://127.0.0.1:"+server.getAddress().getPort()));
                var error=assertThrows(ApiException.class,()->gateway.get(orderId));assertFalse(error.toString().contains("PRIVATE_SECRET_BODY"));assertEquals(503,error.status());
            } finally {server.stop(0);}
        }
    }
    @Test void unknownStatesRequireReviewAndPendingNeverMeansApproved() {
        assertEquals(MercadoPagoStatus.PENDING,MercadoPagoStatus.map("action_required","waiting_capture"));
        assertEquals(MercadoPagoStatus.PROCESSING,MercadoPagoStatus.map("processing","in_process"));
        assertEquals(MercadoPagoStatus.REJECTED,MercadoPagoStatus.map("failed","card_insufficient_amount"));
        assertEquals(MercadoPagoStatus.EXPIRED,MercadoPagoStatus.map("canceled","expired"));
        assertEquals(MercadoPagoStatus.UNDER_REVIEW,MercadoPagoStatus.map("unexpected","unexpected"));
    }
}
