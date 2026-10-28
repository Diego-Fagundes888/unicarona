package com.unicarona.payment.provider;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.unicarona.payment.config.MercadoPagoSettings;
import com.unicarona.shared.ApiException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;

/** No redirect following, arbitrary upstream URL or logging of provider payloads. */
public final class MercadoPagoOrdersClient {
    public record Snapshot(String orderId,UUID paymentId,int amountCents,MercadoPagoStatus state,
        String status,String statusDetail,String qrCode,String qrBase64,String ticketUrl,long version,String challengeUrl,String transactionId,List<Refund> refunds,String transactionStatus,String transactionDetail) {}
    public record Refund(String id,String transactionId,int cents,String status) {}
    public record Card(String token,String method,String type,int installments,String identificationType,String identificationNumber,String deviceSessionId) {
        public Card(String token,String method,String type,int installments,String identificationType,String identificationNumber) {
            this(token,method,type,installments,identificationType,identificationNumber,null);
        }
        @Override public String toString() { return "Card[REDACTED]"; }
    }
    private final MercadoPagoSettings settings;
    private final ObjectMapper json;
    private final HttpClient http;
    private final URI base;
    private volatile boolean accountVerified;
    public MercadoPagoOrdersClient(MercadoPagoSettings settings,ObjectMapper json) {
        this(settings,json,URI.create("https://api.mercadopago.com"));
    }
    // Package-private: local fake server is allowed only to unit tests, never environment configuration.
    MercadoPagoOrdersClient(MercadoPagoSettings settings,ObjectMapper json,URI base) {
        this.settings=settings;this.json=json;this.base=base;
        http=HttpClient.newBuilder().connectTimeout(Duration.ofMillis(settings.timeoutMs())).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    public Snapshot create(UUID paymentId,int cents,String email,Instant expiry,Card card) throws Exception {
        return create(paymentId,cents,email,expiry,card,null);
    }
    public Snapshot createPix(UUID paymentId,int cents,String email,int expirationSeconds) throws Exception {
        ApiException.require(expirationSeconds>=1800 && expirationSeconds<=2592000,"PAYMENT_DATA_INVALID","Confira os dados do pagamento.",422);
        return create(paymentId,cents,email,null,null,expirationSeconds);
    }
    private Snapshot create(UUID paymentId,int cents,String email,Instant expiry,Card card,Integer frozenExpiration) throws Exception {
        verifyAccount();
        ApiException.require(cents>0 && email!=null && email.contains("@"),"PAYMENT_DATA_INVALID","Confira os dados do pagamento.",422);
        ObjectNode body=json.createObjectNode();body.put("type","online");body.put("total_amount",amount(cents));
        // Orders permits only letters, digits, hyphens and underscores (max 64 characters).
        body.put("external_reference","UNICARONA_"+paymentId);body.put("processing_mode","automatic");
        // Official Orders sandbox payer data. Live charges keep the server-owned user's email.
        var payer=body.putObject("payer").put("email",settings.test()?(card==null?"test_user_br@testuser.com":"test@testuser.com"):email);
        if(settings.test() && card==null)payer.put("first_name","APRO");
        var payment=body.putObject("transactions").putArray("payments").addObject().put("amount",amount(cents));
        var method=payment.putObject("payment_method");
        if(card==null) {
            method.put("id","pix").put("type","bank_transfer");
            long seconds=frozenExpiration!=null?frozenExpiration:Math.max(1800,Math.min(2592000,Duration.between(Instant.now(),expiry).getSeconds()));
            payment.put("expiration_time","PT"+seconds+"S");
        } else {
            ApiException.require(card.token()!=null && card.token().matches("[a-zA-Z0-9_-]{8,256}") && card.method()!=null && card.method().matches("[a-zA-Z0-9_]{1,32}") && Set.of("credit_card","debit_card").contains(card.type()) && card.installments()==1,
                "CARD_DATA_INVALID","Confira o cartão. O pagamento é realizado em uma parcela.",422);
            ApiException.require(card.deviceSessionId()==null || card.deviceSessionId().matches("[\\x21-\\x7E]{8,1024}"),"CARD_DATA_INVALID","Confira os dados do pagamento.",422);
            method.put("id",card.method()).put("type",card.type()).put("token",card.token()).put("installments",card.installments());
            body.putObject("config").putObject("online").putObject("transaction_security")
                .put("validation","on_fraud_risk").put("liability_shift","required");
            if(card.identificationNumber()!=null)body.withObject("payer").putObject("identification").put("type",card.identificationType()).put("number",card.identificationNumber());
        }
        return snapshot(send("POST","/v1/orders",body,paymentId.toString(),card==null?null:card.deviceSessionId()));
    }
    public Snapshot get(String id) throws Exception { validateId(id);verifyAccount();return snapshot(send("GET","/v1/orders/"+id,null,null)); }
    public JsonNode refund(String order,String transaction,int cents,UUID refundId) throws Exception {
        return refund(order,transaction,cents,refundId,false);
    }
    public JsonNode refund(String order,String transaction,int cents,UUID refundId,boolean full) throws Exception {
        verifyAccount();
        validateId(order);
        ApiException.require(transaction!=null && transaction.matches("PAY[A-Za-z0-9]{10,64}") && cents>0,"REFUND_INVALID","Devolução inválida.",422);
        var body=json.createObjectNode();if(!full)body.putArray("transactions").addObject().put("id",transaction).put("amount",amount(cents));
        return send("POST","/v1/orders/"+order+"/refund",body,refundId.toString());
    }
    public Refund refundResult(JsonNode result,String order,String transaction,int cents) {
        return refundResult(result,order,transaction,cents,Set.of());
    }
    public Refund refundResult(JsonNode result,String order,String transaction,int cents,Set<String> linkedReferences) {
        ApiException.require(order.equals(result.path("id").asText()),"REFUND_MISMATCH","Devolução externa incompatível.",502);
        // Orders may return the complete refund history, including earlier partial refunds.
        // Only already-linked immutable evidence may be excluded. Never choose an arbitrary match.
        var refunds=parseRefunds(result).stream().filter(r->r.transactionId().equals(transaction) && r.cents()==cents && !linkedReferences.contains(r.id())).toList();
        ApiException.require(refunds.size()==1,"REFUND_MISMATCH","Devolução externa incompatível.",502);
        return refunds.getFirst();
    }
    private List<Refund> parseRefunds(JsonNode order) {
        try {
            var refunds=new ArrayList<Refund>();var ids=new HashSet<String>();
            for(var value:order.path("transactions").path("refunds")) {
                String id=value.path("id").asText(),transaction=value.path("transaction_id").asText(),status=value.path("status").asText();
                int cents=new BigDecimal(value.path("amount").asText()).movePointRight(2).intValueExact();
                if(!id.matches("REF[A-Za-z0-9]{10,64}") || !transaction.matches("PAY[A-Za-z0-9]{10,64}") || cents<=0 || !ids.add(id) || status.length()>80)throw new IllegalArgumentException();
                refunds.add(new Refund(id,transaction,cents,status));
            }
            return refunds.stream().sorted(Comparator.comparing(Refund::id)).toList();
        }catch(RuntimeException error){throw new ApiException("REFUND_MISMATCH","Não foi possível validar a devolução no provedor.",502);}
    }
    public void cancel(String id,UUID commandId) throws Exception { verifyAccount();validateId(id);send("POST","/v1/orders/"+id+"/cancel",json.createObjectNode(),commandId.toString()); }
    private synchronized void verifyAccount() throws Exception {
        if(accountVerified)return;
        var account=send("GET","/users/me",null,null);
        boolean test=false;for(var tag:account.path("tags"))if("test_user".equals(tag.asText()))test=true;
        ApiException.require("BR".equals(account.path("country_id").asText()) && settings.test()==test,
            "PAYMENT_ENVIRONMENT_MISMATCH","A credencial não corresponde ao ambiente de pagamento configurado.",503);
        accountVerified=true;
    }
    private static void validateId(String id) { ApiException.require(id!=null && id.matches("ORD[A-Za-z0-9]{10,64}"),"PAYMENT_REFERENCE_INVALID","Referência de pagamento inválida.",422); }
    static String amount(int cents) { return BigDecimal.valueOf(cents,2).toPlainString(); }
    private JsonNode send(String method,String path,JsonNode body,String key) throws Exception {
        return send(method,path,body,key,null);
    }
    private JsonNode send(String method,String path,JsonNode body,String key,String deviceSessionId) throws Exception {
        var request=HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofMillis(settings.timeoutMs()))
            .header("Authorization","Bearer "+settings.accessToken()).header("Accept","application/json");
        if(key!=null)request.header("X-Idempotency-Key",key);
        if(deviceSessionId!=null)request.header("X-meli-session-id",deviceSessionId);
        if(body!=null)request.header("Content-Type","application/json");
        request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        var response=http.send(request.build(),HttpResponse.BodyHandlers.ofString());
        int status=response.statusCode();
        if(response.body().length()>2000000)throw new ApiException("PAYMENT_PROVIDER_INVALID","Resposta de pagamento inválida.",502);
        if(status==401 || status==403)throw new ApiException("PAYMENT_CONFIGURATION_ERROR","O pagamento está indisponível. Entre em contato com o suporte.",503);
        if(status==404)throw new ApiException("PROVIDER_PAYMENT_NOT_FOUND","Pagamento ainda não encontrado no provedor.",404);
        if(status==409 || status==423 || status==429 || status>=500)throw new ApiException("PAYMENT_PROVIDER_UNAVAILABLE","Não foi possível consultar o pagamento. Tente novamente; a cobrança será reutilizada.",503);
        // An issuer refusal can create an order and return HTTP 402 with its identity in data.
        // Read that order again; never treat the error envelope as verified payment state.
        if(status==402 && "POST".equals(method) && "/v1/orders".equals(path)) {
            String id=json.readTree(response.body()).path("data").path("id").asText();
            if(id.matches("ORD[A-Za-z0-9]{10,64}"))return send("GET","/v1/orders/"+id,null,null);
        }
        if(status<200 || status>=300)throw new ApiException("PAYMENT_PROVIDER_REJECTED","O provedor não aceitou a operação. Confira os dados ou procure o suporte.",422);
        return json.readTree(response.body());
    }
    Snapshot snapshot(JsonNode order) {
        try {
            String id=order.path("id").asText();validateId(id);
            String ref=order.path("external_reference").asText();if(!ref.matches("UNICARONA_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw new IllegalArgumentException();
            UUID payment=UUID.fromString(ref.substring(10));
            if(order.has("country_code") && !"BRA".equals(order.path("country_code").asText()))throw new IllegalArgumentException();
            if(order.has("currency_id") && !"BRL".equals(order.path("currency_id").asText()))throw new IllegalArgumentException();
            if(order.has("currency") && !"BRL".equals(order.path("currency").asText()))throw new IllegalArgumentException();
            if(order.has("live_mode") && order.path("live_mode").asBoolean()==settings.test())throw new IllegalArgumentException();
            int cents=new BigDecimal(order.path("total_amount").asText()).movePointRight(2).intValueExact();if(cents<=0)throw new IllegalArgumentException();
            String status=order.path("status").asText(),detail=order.path("status_detail").asText();
            var state=MercadoPagoStatus.map(status,detail);var payments=order.path("transactions").path("payments");
            if(payments.size()>1)throw new IllegalArgumentException();
            var transaction=payments.path(0);var method=transaction.path("payment_method");
            // The order may only say "failed"; the verified transaction carries the actionable reason.
            String transactionDetail=transaction.path("status_detail").asText();
            if("charged_back".equals(status) && (payments.size()!=1 || !transaction.path("id").asText().matches("PAY[A-Za-z0-9]{10,64}") || new BigDecimal(transaction.path("amount").asText()).movePointRight(2).intValueExact()!=cents))throw new IllegalArgumentException();
            if(state==MercadoPagoStatus.REJECTED && "failed".equals(transaction.path("status").asText())
                && Set.of("bad_filled_card_data","invalid_card_token","high_risk","rejected_by_issuer",
                    "required_call_for_authorize","max_attempts_exceeded","card_disabled","insufficient_amount",
                    "amount_limit_exceeded","processing_error","invalid_installments","3ds_challenge_expired",
                    "card_insufficient_amount").contains(transactionDetail))detail=transactionDetail;
            if(state==MercadoPagoStatus.APPROVED) {
                if(payments.size()!=1 || !"processed".equals(transaction.path("status").asText()) || !"accredited".equals(transaction.path("status_detail").asText())
                    || new BigDecimal(transaction.path("paid_amount").asText()).movePointRight(2).intValueExact()!=cents)throw new IllegalArgumentException();
            }
            String ticket=method.path("ticket_url").asText(null);
            if(ticket!=null) { URI uri=URI.create(ticket);if(!"https".equals(uri.getScheme()) || uri.getUserInfo()!=null || !Set.of("www.mercadopago.com.br","www.mercadopago.com").contains(uri.getHost()))throw new IllegalArgumentException(); }
            String challenge=method.path("transaction_security").path("url").asText(null);
            if(challenge!=null) { URI uri=URI.create(challenge);if(!"https".equals(uri.getScheme()) || uri.getUserInfo()!=null || (uri.getPort()!=-1 && uri.getPort()!=443) || !Set.of("www.mercadopago.com.br","www.mercadopago.com").contains(uri.getHost()))throw new IllegalArgumentException(); }
            var refunds=parseRefunds(order);int refunded=0;for(var refund:refunds) {if(!Set.of("failed","canceled","cancelled").contains(refund.status()))refunded=Math.addExact(refunded,refund.cents());}
            if(refunded>cents)throw new IllegalArgumentException();
            return new Snapshot(id,payment,cents,state,status,detail,method.path("qr_code").asText(null),method.path("qr_code_base64").asText(null),ticket,order.path("version").asLong(0),challenge,transaction.path("id").asText(null),refunds,transaction.path("status").asText(null),transaction.path("status_detail").asText(null));
        } catch(RuntimeException error) { throw new ApiException("PAYMENT_PROVIDER_INVALID","Não foi possível validar o pagamento no provedor.",502); }
    }
}
