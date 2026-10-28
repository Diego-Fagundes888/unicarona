package com.unicarona.payment.provider;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import com.unicarona.UniCaronaApplication;
import com.unicarona.payment.config.MercadoPagoSettings;
import com.unicarona.payment.service.*;
import com.unicarona.job.service.MaintenanceService;
import com.unicarona.job.service.OutboxProcessor;
import com.unicarona.payment.ledger.AccountKind;
import com.unicarona.security.CryptoService;
import com.unicarona.support.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.unicarona.payment.ledger.LedgerCommand;
import com.unicarona.payment.ledger.LedgerCommand.Posting;
import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP and PostgreSQL business flow; upstream is an explicit local protocol fixture. */
class MercadoPagoHttpIT {
    private static RealPostgres database;
    private static ServletWebServerApplicationContext app;
    private static HttpServer upstream;
    private static JdbcTemplate sql;
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Map<String,ObjectNode> ORDERS=new ConcurrentHashMap<>();
    private static final Map<String,JsonNode> COMMANDS=new ConcurrentHashMap<>();
    private static final Map<String,List<String>> DEVICE_COMMANDS=new ConcurrentHashMap<>();
    private static final AtomicBoolean LOSE_RESPONSE=new AtomicBoolean();
    private static final AtomicBoolean LOCK_RESPONSE=new AtomicBoolean();
    private static final AtomicBoolean REJECT_CREATE=new AtomicBoolean();
    private static final Set<String> FAIL_GET=ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean CANCEL_RACE=new AtomicBoolean(),LOSE_CANCEL_RESPONSE=new AtomicBoolean();
    private static final Map<String,List<String>> CANCEL_KEYS=new ConcurrentHashMap<>();
    private static final Map<String,JsonNode> REFUND_COMMANDS=new ConcurrentHashMap<>();
    private static final AtomicBoolean LOSE_REFUND_RESPONSE=new AtomicBoolean(),PENDING_REFUND=new AtomicBoolean();
    private static final Set<String> REJECT_REFUND_ORDER=ConcurrentHashMap.newKeySet();
    private static String server,passwordHash;
    private static UUID university,course,classId;
    private static java.nio.file.Path privacyJournal;
    private static final String PASSWORD="Synthetic MP HTTP test 2026";
    private static final String SECRET="Synthetic-MercadoPago-webhook-secret-2026";
    private final HttpClient http=HttpClient.newHttpClient();
    private record Session(UUID id,String cookie,String csrf) { @Override public String toString() { return "Session[redacted]"; } }
    @TestConfiguration(proxyBeanMethods=false) static class ClientConfig {
        @Bean @Primary MercadoPagoOrdersClient localMercadoPagoClient(MercadoPagoSettings settings,ObjectMapper json) {
            // Exercises the live payer branch against the loopback fixture only. Never
            // enables production credentials, a public origin or real financial calls.
            if(Boolean.getBoolean("mercadopago.fixture.production-payer"))settings=new MercadoPagoSettings(settings.accessToken(),settings.publicKey(),settings.webhookSecret(),"production",settings.origin(),settings.timeoutMs());
            return new MercadoPagoOrdersClient(settings,json,URI.create("http://127.0.0.1:"+upstream.getAddress().getPort()));
        }
    }
    @BeforeAll static void start() throws Exception {
        upstream=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        upstream.createContext("/users/me",exchange->{byte[] bytes=(Boolean.getBoolean("mercadopago.fixture.production-payer")?"{\"country_id\":\"BR\",\"tags\":[]}":"{\"country_id\":\"BR\",\"tags\":[\"test_user\"]}").getBytes();exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});
        upstream.createContext("/v1/orders",exchange->{
            if("POST".equals(exchange.getRequestMethod()) && exchange.getRequestURI().getPath().endsWith("/refund")) {
                String path=exchange.getRequestURI().getPath(),orderId=path.substring("/v1/orders/".length(),path.length()-"/refund".length()),key=exchange.getRequestHeaders().getFirst("X-Idempotency-Key");
                var input=JSON.readTree(exchange.getRequestBody());var previous=REFUND_COMMANDS.putIfAbsent(key,input.deepCopy());
                if(REJECT_REFUND_ORDER.contains(orderId) || previous!=null && !previous.equals(input)) {byte[] bytes="{\"error\":\"synthetic_refund_rejected\"}".getBytes();exchange.sendResponseHeaders(400,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();return;}
                var order=ORDERS.get(orderId);var transactions=(ObjectNode)order.path("transactions");
                var refunds=transactions.withArray("refunds");String refundId="REF"+key.replace("-","").toUpperCase(Locale.ROOT);ObjectNode refund=null;
                for(var item:refunds)if(refundId.equals(item.path("id").asText()))refund=(ObjectNode)item;
                if(refund==null) {
                    String amount=input.has("transactions")?input.path("transactions").path(0).path("amount").asText():order.path("total_amount").asText();
                    String transaction=input.has("transactions")?input.path("transactions").path(0).path("id").asText():transactions.path("payments").path(0).path("id").asText();
                    refund=refunds.addObject().put("id",refundId).put("transaction_id",transaction).put("amount",amount).put("status",PENDING_REFUND.get()?"processing":"processed");
                    order.put("version",order.path("version").asInt()+1);
                    if(!PENDING_REFUND.get()) {long cents=0;for(var r:refunds)if("processed".equals(r.path("status").asText()))cents+=new java.math.BigDecimal(r.path("amount").asText()).movePointRight(2).longValueExact();boolean full=cents==new java.math.BigDecimal(order.path("total_amount").asText()).movePointRight(2).longValueExact();order.put("status",full?"refunded":"processed").put("status_detail",full?"refunded":"partially_refunded");}
                }
                var result=JSON.createObjectNode().put("id",orderId).put("status",order.path("status").asText()).put("status_detail",order.path("status_detail").asText());result.putObject("transactions").set("refunds",refunds.deepCopy());
                byte[] bytes=JSON.writeValueAsBytes(result);exchange.sendResponseHeaders(LOSE_REFUND_RESPONSE.compareAndSet(true,false)?503:201,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();return;
            }
            if("POST".equals(exchange.getRequestMethod()) && exchange.getRequestURI().getPath().endsWith("/cancel")) {
                String path=exchange.getRequestURI().getPath(),orderId=path.substring("/v1/orders/".length(),path.length()-"/cancel".length());
                String key=exchange.getRequestHeaders().getFirst("X-Idempotency-Key");CANCEL_KEYS.computeIfAbsent(orderId,id->new ArrayList<>()).add(key);
                var value=ORDERS.get(orderId);var transaction=(ObjectNode)value.path("transactions").path("payments").path(0);
                boolean race=CANCEL_RACE.compareAndSet(true,false);
                value.put("status",race?"processed":"canceled").put("status_detail",race?"accredited":"canceled").put("version",value.path("version").asInt()+1);
                transaction.put("status",race?"processed":"canceled").put("status_detail",race?"accredited":"canceled").put("paid_amount",race?value.path("total_amount").asText():"0.00");
                boolean lost=LOSE_CANCEL_RESPONSE.compareAndSet(true,false);
                byte[] bytes=JSON.writeValueAsBytes(Map.of("id",orderId,"status",value.path("status").asText(),"status_detail",value.path("status_detail").asText()));
                exchange.sendResponseHeaders(race?409:lost?503:200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();return;
            }
            if("GET".equals(exchange.getRequestMethod()) && FAIL_GET.contains(exchange.getRequestURI().getPath().substring("/v1/orders/".length()))) {
                byte[] bytes="{\"error\":\"synthetic_sensitive_upstream_message\"}".getBytes();exchange.sendResponseHeaders(503,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();return;
            }
            ObjectNode order;
            if("POST".equals(exchange.getRequestMethod())) {
                var input=JSON.readTree(exchange.getRequestBody());String key=exchange.getRequestHeaders().getFirst("X-Idempotency-Key");
                if(input.path("transactions").path("payments").path(0).path("payment_method").has("token")) {
                    var security=input.path("config").path("online").path("transaction_security");
                    if(!"on_fraud_risk".equals(security.path("validation").asText()) || !"required".equals(security.path("liability_shift").asText())) {
                        byte[] bytes="{\"errors\":[{\"code\":\"required_properties\"}]}".getBytes();exchange.sendResponseHeaders(400,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();return;
                    }
                }
                String orderId="ORD"+key.replace("-","").toUpperCase(Locale.ROOT);
                String device=exchange.getRequestHeaders().getFirst("X-meli-session-id");if(device!=null)DEVICE_COMMANDS.computeIfAbsent(orderId,unused->new java.util.concurrent.CopyOnWriteArrayList<>()).add(device);
                if(REJECT_CREATE.get()){byte[] bytes="{\"error\":\"synthetic_sensitive_rejection\"}".getBytes();exchange.sendResponseHeaders(400,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();return;}
                order=ORDERS.computeIfAbsent(orderId,id->{var value=JSON.createObjectNode();value.put("id",id).put("external_reference",input.path("external_reference").asText()).put("total_amount",input.path("total_amount").asText()).put("country_code","BRA").put("status","action_required").put("status_detail","waiting_transfer").put("version",1);value.putObject("transactions").putArray("payments").addObject().put("id","PAY"+key.replace("-","").toUpperCase(Locale.ROOT)).put("status","action_required").put("status_detail","waiting_transfer").put("paid_amount","0.00").putObject("payment_method").put("id","pix").put("qr_code","synthetic-qr").put("qr_code_base64","aW1hZ2U=");return value;});
                try(var image=MercadoPagoHttpIT.class.getResourceAsStream("/mercadopago-pix-fixture.png")) {
                    ((ObjectNode)order.path("transactions").path("payments").path(0).path("payment_method")).put("qr_code_base64",Base64.getEncoder().encodeToString(image.readAllBytes()));
                }
                var previous=COMMANDS.putIfAbsent(orderId,input.deepCopy());
                if(previous!=null && !previous.equals(input)) {byte[] bytes="{\"error\":\"idempotency_body_changed\"}".getBytes();exchange.sendResponseHeaders(422,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();return;}
                String token=input.path("transactions").path("payments").path(0).path("payment_method").path("token").asText();
                if(!token.isEmpty()) {
                    var transaction=(ObjectNode)order.path("transactions").path("payments").path(0);
                    transaction.set("payment_method",input.path("transactions").path("payments").path(0).path("payment_method").deepCopy());
                    if(token.contains("approved")) {order.put("status","processed").put("status_detail","accredited");transaction.put("status","processed").put("status_detail","accredited").put("paid_amount",input.path("total_amount").asText());}
                    else if(token.contains("refused")) {
                        order.put("status","failed").put("status_detail","failed");transaction.put("status","failed").put("status_detail",token.contains("insufficient")?"insufficient_amount":"rejected_by_issuer");
                        var refusal=JSON.createObjectNode();refusal.putArray("errors").addObject().put("code","failed").put("message","synthetic_sensitive_rejection");refusal.putObject("data").put("id",orderId);
                        byte[] bytes=JSON.writeValueAsBytes(refusal);exchange.sendResponseHeaders(402,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();return;
                    }
                    else if(token.contains("challenge")) {order.put("status","action_required").put("status_detail","pending_challenge");transaction.put("status","action_required").put("status_detail","pending_challenge");((ObjectNode)transaction.path("payment_method")).putObject("transaction_security").put("url","https://www.mercadopago.com.br/auth/card/validation/synthetic");}
                    else {order.put("status","processing").put("status_detail","in_process");transaction.put("status","processing").put("status_detail","in_process");}
                }
                if(LOCK_RESPONSE.compareAndSet(true,false)){byte[] bytes="{\"errors\":[{\"code\":\"resource_locked\",\"message\":\"synthetic_sensitive_lock\"}]}".getBytes();exchange.sendResponseHeaders(423,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();return;}
                if(LOSE_RESPONSE.compareAndSet(true,false)){byte[] bytes="{}".getBytes();exchange.sendResponseHeaders(503,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();return;}
            } else order=ORDERS.get(exchange.getRequestURI().getPath().substring("/v1/orders/".length()));
            byte[] bytes=JSON.writeValueAsBytes(order==null?Map.of("error","not_found"):order);exchange.sendResponseHeaders(order==null?404:200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });upstream.start();
        database=new RealPostgres();database.resetSchema();
        var args=new ArrayList<>(List.of(AuthTestSettings.arguments(database.apiConnectionUrl())));args.removeIf(a->a.startsWith("--PAYMENT_PROVIDER="));
        privacyJournal=java.nio.file.Files.createTempDirectory("unicarona-mp-privacy-");args.add("--PRIVACY_ERASURE_JOURNAL_PATH="+privacyJournal);
        if(System.getProperty("mercadopago.e2e.origin")!=null) {String origin=System.getProperty("mercadopago.e2e.origin");if(!origin.matches("http://127\\.0\\.0\\.1:[0-9]{1,5}"))throw new IllegalStateException("Only a local fixture origin is allowed");args.removeIf(a->a.startsWith("--APP_ORIGIN=") || a.startsWith("--server.port="));args.add("--APP_ORIGIN="+origin);args.add("--server.port="+URI.create(origin).getPort());}
        args.addAll(List.of("--PAYMENT_PROVIDER=mercadopago","--MERCADO_PAGO_ACCESS_TOKEN=synthetic-not-a-real-token","--MERCADO_PAGO_PUBLIC_KEY=synthetic-public-key","--MERCADO_PAGO_WEBHOOK_SECRET="+SECRET,"--MERCADO_PAGO_ENVIRONMENT=test")); // gitleaks:allow -- synthetic fixture, never used by production.
        app=(ServletWebServerApplicationContext)new SpringApplicationBuilder(UniCaronaApplication.class,ClientConfig.class).run(args.toArray(String[]::new));
        sql=app.getBean(JdbcTemplate.class);server="http://127.0.0.1:"+app.getWebServer().getPort();passwordHash=app.getBean(CryptoService.class).hashPassword(PASSWORD);
        assertSame(app.getBean("localMercadoPagoClient"),app.getBean(MercadoPagoOrdersClient.class));assertEquals("mercadopago",app.getBean(PaymentProvider.class).name());
        university=sql.queryForObject("INSERT INTO universities(slug,name,city,state_code) VALUES('mp-http','MP Test','Gama','DF') RETURNING id",UUID.class);
        course=sql.queryForObject("INSERT INTO courses(university_id,slug,name) VALUES(?,'mp-course','MP Course') RETURNING id",UUID.class,university);
        classId=sql.queryForObject("INSERT INTO classes(university_id,course_id,period_number,label) VALUES(?,?,3,'A') RETURNING id",UUID.class,university,course);
        sql.update("INSERT INTO pricing_versions(university_id,fuel_cents,efficiency,wear_cents,reference_passengers,commission_bps,discount_bps) VALUES(?,600,10,10,3,1000,1000)",university);
    }
    @BeforeEach void clearRateLimits() {sql.update("DELETE FROM rate_limits");sql.update("UPDATE mercado_pago_orders SET next_check_at=now()+interval '1 day'");sql.update("UPDATE passenger_debt_payments SET next_check_at=now()+interval '1 day'");FAIL_GET.clear();CANCEL_RACE.set(false);LOSE_CANCEL_RESPONSE.set(false);LOSE_RESPONSE.set(false);LOCK_RESPONSE.set(false);REJECT_CREATE.set(false);LOSE_REFUND_RESPONSE.set(false);PENDING_REFUND.set(false);REJECT_REFUND_ORDER.clear();}
    @AfterAll static void close() throws Exception {if(app!=null)app.close();if(database!=null)database.close();if(upstream!=null)upstream.stop(0);if(privacyJournal!=null){assertTrue(privacyJournal.getFileName().toString().startsWith("unicarona-mp-privacy-"));try(var files=java.nio.file.Files.list(privacyJournal)){for(var file:files.toList())java.nio.file.Files.delete(file);}java.nio.file.Files.delete(privacyJournal);}}
    private Session user(boolean driver) throws Exception {
        String email="mp-"+UUID.randomUUID()+"@example.test";
        UUID id=sql.queryForObject("INSERT INTO users(email,password_hash,name,birth_date,email_verified_at) VALUES(?,?,'MP Synthetic','2000-05-01',now()) RETURNING id",UUID.class,email,passwordHash);
        com.unicarona.support.LegalTestFixtures.acceptedBusinessUser(app,id);
        sql.update("INSERT INTO student_profiles(user_id,university_id,course_id,class_id,period_status,period_number,shift,region_label) VALUES(?,?,?,?,'numbered',3,'night','Gama')",id,university,course,classId);
        if(driver){sql.update("INSERT INTO driver_profiles(user_id,status,approved_at) VALUES(?,'approved',now())",id);sql.update("INSERT INTO vehicles(owner_id,model,color,plate,capacity,status) VALUES(?,'MP Synthetic','Black','ABC1D23',4,'approved')",id);}
        return session(id);
    }
    private Session session(UUID id) throws Exception {
        String email=sql.queryForObject("SELECT email FROM users WHERE id=?",String.class,id);
        var login=send("POST","/api/v1/auth/login",Map.of("email",email,"password",PASSWORD),null);assertEquals(200,login.statusCode());
        var cookies=new LinkedHashMap<String,String>();for(String header:login.headers().allValues("set-cookie")){var pair=header.split(";",2)[0].split("=",2);cookies.put(pair[0],pair[1]);}
        return new Session(id,String.join("; ",cookies.entrySet().stream().map(e->e.getKey()+"="+e.getValue()).toList()),cookies.get("uc_csrf"));
    }
    private HttpResponse<String> send(String method,String path,Object body,Session session) throws Exception {
        return send(method,path,body,session,UUID.randomUUID().toString());
    }
    private HttpResponse<String> send(String method,String path,Object body,Session session,String key) throws Exception {
        body=com.unicarona.support.CheckoutTestFixtures.reviewedBody(app,method,path,body);
        var request=HttpRequest.newBuilder(URI.create(server+path)).header("Origin",System.getProperty("mercadopago.e2e.origin",AuthTestSettings.ORIGIN)).header("idempotency-key",key);
        if(session!=null)request.header("Cookie",session.cookie()).header("x-csrf-token",session.csrf());
        request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));if(body!=null)request.header("Content-Type","application/json");
        return http.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    private UUID payment(Session passenger) throws Exception {
        var driver=user(true);var rideInput=new LinkedHashMap<String,Object>(Map.of("region","Gama","meeting","MP stop","direction","ida","date",LocalDate.now(ZoneId.of("America/Sao_Paulo")).plusDays(3).toString(),"time","18:30","capacity",1,"distance",10,"automatic",true));
        var ride=send("POST","/api/v1/rides",rideInput,driver);assertEquals(200,ride.statusCode(),ride.body());
        var request=send("POST","/api/v1/ride-requests",Map.of("type","avulsa","rideId",JSON.readTree(ride.body()).path("id").asText()),passenger);assertEquals(200,request.statusCode(),request.body());
        return sql.queryForObject("SELECT id FROM payments WHERE request_id=?",UUID.class,UUID.fromString(JSON.readTree(request.body()).path("id").asText()));
    }
    private String checkout(Session passenger,UUID payment) throws Exception {
        var response=send("POST","/api/v1/payments/"+payment+"/checkout",Map.of("method","pix","useCredit",false),passenger);
        assertEquals(200,response.statusCode(),response.body());
        return sql.queryForObject("SELECT order_id FROM mercado_pago_orders WHERE payment_id=?",String.class,payment);
    }
    private HttpResponse<String> webhook(String order,String status,String detail,boolean validSignature) throws Exception {
        String ts=Long.toString(Instant.now().toEpochMilli()),requestId=UUID.randomUUID().toString();
        String manifest="id:"+order.toLowerCase(Locale.ROOT)+";request-id:"+requestId+";ts:"+ts+";";
        String digest=new CryptoService().hmac(manifest,validSignature?SECRET:"wrong-secret");
        var body=Map.of("type","order","data",Map.of("id",order,"status",status,"status_detail",detail));
        var request=HttpRequest.newBuilder(URI.create(server+"/api/v1/webhooks/mercadopago?data.id="+order+"&type=order")).header("Content-Type","application/json").header("x-request-id",requestId).header("x-signature","ts="+ts+",v1="+digest).POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build();
        return http.send(request,HttpResponse.BodyHandlers.ofString());
    }
    private void state(String order,String status,String detail) {
        var value=ORDERS.get(order);value.put("status",status).put("status_detail",detail).put("version",value.path("version").asInt()+1);
        var transaction=(ObjectNode)value.path("transactions").path("payments").path(0);transaction.put("amount",value.path("total_amount").asText()).put("status",status).put("status_detail",detail).put("paid_amount","processed".equals(status)?value.path("total_amount").asText():"0.00");
    }
    @Test void pixApprovalAndDuplicateWebhookCreateExactlyOneReservationAndReceipt() throws Exception {
        var passenger=user(false);UUID payment=payment(passenger);String order=checkout(passenger,payment);
        assertEquals(0,sql.queryForObject("SELECT count(*) FROM ride_reservations WHERE payment_id=?",Integer.class,payment));
        state(order,"processed","accredited");assertEquals(200,webhook(order,"failed","forged-body-status",true).statusCode());
        assertEquals(200,webhook(order,"processed","accredited",true).statusCode());
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM ride_reservations WHERE payment_id=?",Integer.class,payment));
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM ledger_transactions WHERE payment_id=? AND kind='receipt'",Integer.class,payment));
        assertTrue(JSON.readTree(send("GET","/api/v1/payments/"+payment,null,passenger).body()).path("confirmed").asBoolean());
    }
    @Test void aVerifiedLateChargebackStillReconcilesAfterProfileErasureWithoutRestoringAccess()throws Exception {
        var passenger=user(false);var source=credit(passenger,"card");String version=JSON.readTree(send("GET","/api/v1/privacy/closure",null,passenger).body()).path("policyVersion").asText();
        var closed=send("POST","/api/v1/privacy/closure",Map.of("password",PASSWORD,"acknowledged",true,"policyVersion",version),passenger);assertEquals(202,closed.statusCode(),closed.body());UUID closure=UUID.fromString(JSON.readTree(closed.body()).path("id").asText());sql.update("UPDATE privacy_account_closures SET requested_at=now()-interval '8 days',reauthenticated_at=now()-interval '8 days',erase_after=now()-interval '1 day' WHERE id=?",closure);
        app.getBean(com.unicarona.privacy.service.PrivacyErasureMaintenance.class).run(20,()->false);assertEquals("completed",sql.queryForObject("SELECT status FROM privacy_account_closures WHERE id=?",String.class,closure));
        state(source.order(),"charged_back","settled");assertEquals(200,webhook(source.order(),"forged","forged",true).statusCode());assertEquals(200,webhook(source.order(),"forged","forged",true).statusCode());
        assertTrue(sql.queryForObject("SELECT chargeback_review FROM mercado_pago_orders WHERE payment_id=?",Boolean.class,source.payment()));assertEquals("approved",sql.queryForObject("SELECT status FROM payments WHERE id=?",String.class,source.payment()));assertEquals(source.cents(),app.getBean(PassengerDebtService.class).summary(passenger.id()).heldCreditCents());
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM ledger_transactions WHERE payment_id=? AND kind='receipt'",Integer.class,source.payment()));assertEquals(401,send("GET","/api/v1/wallet",null,passenger).statusCode());assertEquals("Conta excluída",sql.queryForObject("SELECT name FROM users WHERE id=?",String.class,passenger.id()));assertEquals(0,app.getBean(LedgerService.class).reconciliation().difference());
    }
    @Test void pendingDoesNotApproveAndOwnershipAndPriceTamperingAreRejected() throws Exception {
        var passenger=user(false);var outsider=user(false);UUID payment=payment(passenger);
        assertEquals(422,send("POST","/api/v1/payments/"+payment+"/checkout",Map.of("method","pix","useCredit",false,"price",1),passenger).statusCode());
        assertEquals(404,send("GET","/api/v1/payments/"+payment,null,outsider).statusCode());
        String order=checkout(passenger,payment);assertEquals(200,webhook(order,"processed","accredited",true).statusCode());
        assertEquals("pending",sql.queryForObject("SELECT status FROM payments WHERE id=?",String.class,payment));
        assertEquals(404,send("GET","/api/v1/payments/"+payment+"/gateway",null,outsider).statusCode());
        assertEquals("synthetic-qr",JSON.readTree(send("GET","/api/v1/payments/"+payment+"/gateway",null,passenger).body()).path("qrCode").asText());
    }
    @Test void invalidSignatureAndWrongProviderAmountCannotConfirmBooking() throws Exception {
        var passenger=user(false);UUID payment=payment(passenger);String order=checkout(passenger,payment);state(order,"processed","accredited");
        assertEquals(401,webhook(order,"processed","accredited",false).statusCode());
        var remote=ORDERS.get(order);remote.put("total_amount","999.99");((ObjectNode)remote.path("transactions").path("payments").path(0)).put("paid_amount","999.99");
        assertEquals(422,webhook(order,"processed","accredited",true).statusCode());
        assertEquals(0,sql.queryForObject("SELECT count(*) FROM ride_reservations WHERE payment_id=?",Integer.class,payment));
    }
    @Test void rejectedAndExpiredOrdersNeverReleaseAReservation() throws Exception {
        for(String status:List.of("failed","canceled")) {
            var passenger=user(false);UUID payment=payment(passenger);String order=checkout(passenger,payment);state(order,status,"failed".equals(status)?"card_insufficient_amount":"expired");
            assertEquals(200,webhook(order,status,"irrelevant-body",true).statusCode());
            assertEquals(0,sql.queryForObject("SELECT count(*) FROM ride_reservations WHERE payment_id=?",Integer.class,payment));
            assertEquals("failed",sql.queryForObject("SELECT status FROM payments WHERE id=?",String.class,payment));
        }
    }
    private Map<String,Object> card(String token) {return Map.of("token",token,"method","master","type","credit_card","installments",1,"identificationType","CPF","identificationNumber","12345678909");}
    private void prepareCard(Session user,UUID id) throws Exception {var response=send("POST","/api/v1/payments/"+id+"/checkout",Map.of("method","card","useCredit",false),user);assertEquals(200,response.statusCode(),response.body());}
    @Test void officialCardTokenCreatesOneReceiptAndErasesTheDurableToken() throws Exception {
        var passenger=user(false);UUID id=payment(passenger);prepareCard(passenger,id);
        var input=card("synthetic-approved-card");String key=UUID.randomUUID().toString();var result=send("POST","/api/v1/payments/"+id+"/card",input,passenger,key);assertEquals(200,result.statusCode(),result.body());
        assertTrue(JSON.readTree(result.body()).path("confirmed").asBoolean());
        var typed=JSON.convertValue(input,com.unicarona.payment.dto.MercadoPagoDto.CardInput.class);
        for(var order:List.of(List.of("paymentId","card"),List.of("card","paymentId"))) {
            var legacy=new LinkedHashMap<String,Object>();for(String field:order)legacy.put(field,"card".equals(field)?typed:id);
            legacyIdempotencyHash(passenger,key,"payment:card",legacy);
            assertEquals(200,send("POST","/api/v1/payments/"+id+"/card",input,passenger,key).statusCode());
        }
        assertEquals(409,send("POST","/api/v1/payments/"+id+"/card",card("different-synthetic-token"),passenger,key).statusCode());
        assertEquals(200,send("POST","/api/v1/payments/"+id+"/card",input,passenger).statusCode());
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM ride_reservations WHERE payment_id=?",Integer.class,id));
        var command=sql.queryForMap("SELECT * FROM mercado_pago_card_commands WHERE payment_id=?",id);assertEquals("done",command.get("status"));assertNull(command.get("payload_ciphertext"));
        String order=sql.queryForObject("SELECT order_id FROM mercado_pago_orders WHERE payment_id=?",String.class,id);
        assertEquals(String.format(Locale.ROOT,"%.2f",((Number)sql.queryForObject("SELECT external_cents FROM payments WHERE id=?",Integer.class,id)).intValue()/100.0),COMMANDS.get(order).path("total_amount").asText());
        assertFalse(COMMANDS.get(order).toString().contains("card_number"));assertFalse(COMMANDS.get(order).toString().contains("security_code"));
    }
    @Test void lostResponseRetainsEncryptedCommandAndRetriesTheSameOrder() throws Exception {
        var passenger=user(false);UUID id=payment(passenger);prepareCard(passenger,id);LOSE_RESPONSE.set(true);
        var result=send("POST","/api/v1/payments/"+id+"/card",card("synthetic-pending-card"),passenger);assertEquals(503,result.statusCode(),result.body());
        String ciphertext=sql.queryForObject("SELECT payload_ciphertext FROM mercado_pago_card_commands WHERE payment_id=?",String.class,id);assertNotNull(ciphertext);assertFalse(ciphertext.contains("synthetic-pending-card"));
        assertEquals("PROCESSING",JSON.readTree(send("GET","/api/v1/payments/"+id+"/gateway",null,passenger).body()).path("state").asText());
        var retried=send("POST","/api/v1/payments/"+id+"/sync",Map.of(),passenger);assertEquals(200,retried.statusCode(),retried.body());
        String order=sql.queryForObject("SELECT order_id FROM mercado_pago_orders WHERE payment_id=?",String.class,id);
        assertEquals("ORD"+id.toString().replace("-","").toUpperCase(Locale.ROOT),order);
        assertEquals("done",sql.queryForObject("SELECT status FROM mercado_pago_card_commands WHERE payment_id=?",String.class,id));
        assertEquals(0,sql.queryForObject("SELECT count(*) FROM ride_reservations WHERE payment_id=?",Integer.class,id));
        state(order,"processed","accredited");assertEquals(200,webhook(order,"processed","accredited",true).statusCode());
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM ride_reservations WHERE payment_id=?",Integer.class,id));
    }
    @Test void lostCardResponseThenProfileEmailCorrectionMustRetryTheOriginalRequest()throws Exception {
        var passenger=user(false);UUID id=payment(passenger);prepareCard(passenger,id);LOSE_RESPONSE.set(true);
        assertEquals(503,send("POST","/api/v1/payments/"+id+"/card",card("synthetic-pending-card"),passenger).statusCode());
        String order="ORD"+id.toString().replace("-","").toUpperCase(Locale.ROOT);var original=COMMANDS.get(order).deepCopy();
        String oldEmail=sql.queryForObject("SELECT email FROM users WHERE id=?",String.class,passenger.id());
        if(Boolean.getBoolean("mercadopago.fixture.production-payer"))assertEquals(oldEmail,original.path("payer").path("email").asText());
        String encrypted=sql.queryForObject("SELECT payload_ciphertext FROM mercado_pago_card_commands WHERE payment_id=?",String.class,id);assertFalse(encrypted.contains(oldEmail));
        String next=UUID.randomUUID()+"@example.test";
        assertEquals(202,send("POST","/api/v1/privacy/identity/email",Map.of("password",PASSWORD,"email",next),passenger).statusCode());
        var job=sql.queryForMap("SELECT o.payload::text,o.event_key FROM auth_tokens t JOIN outbox_jobs o ON o.event_key='auth:'||t.id::text WHERE t.user_id=? AND t.kind='change_email' AND t.used_at IS NULL AND t.invalidated_at IS NULL",passenger.id());
        String url=app.getBean(com.unicarona.privacy.service.PrivacyEvidenceCrypto.class).openMail(com.unicarona.privacy.service.PrivacyEvidenceCrypto.Field.MAIL_URL,passenger.id(),(String)job.get("event_key"),JSON.readTree((String)job.get("payload")).path("urlCiphertext").asText());
        assertEquals(200,send("POST","/api/v1/privacy/identity/email/confirm",Map.of("password",PASSWORD,"token",url.substring(url.indexOf("?token=")+7)),passenger).statusCode());
        assertEquals(401,send("GET","/api/v1/auth/me",null,passenger).statusCode());
        assertEquals(next,sql.queryForObject("SELECT email FROM users WHERE id=?",String.class,passenger.id()));var fresh=session(passenger.id());
        var retried=send("POST","/api/v1/payments/"+id+"/sync",Map.of(),fresh);assertEquals(200,retried.statusCode(),retried.body());assertFalse(retried.body().contains(oldEmail));
        assertEquals(original,COMMANDS.get(order));assertEquals("done",sql.queryForObject("SELECT status FROM mercado_pago_card_commands WHERE payment_id=?",String.class,id));assertNull(sql.queryForObject("SELECT payload_ciphertext FROM mercado_pago_card_commands WHERE payment_id=?",String.class,id));
        state(order,"processed","accredited");assertEquals(200,webhook(order,"forged","forged",true).statusCode());assertEquals(200,webhook(order,"forged","forged",true).statusCode());assertEquals(1,sql.queryForObject("SELECT count(*) FROM ledger_transactions WHERE payment_id=? AND kind='receipt'",Integer.class,id));
    }
    private void legacyCardPayload(UUID id)throws Exception {
        String raw=JSON.writeValueAsString(card("synthetic-pending-card"));
        sql.update("UPDATE mercado_pago_card_commands SET payload_ciphertext=? WHERE payment_id=?",app.getBean(CryptoService.class).encrypt(raw,AuthTestSettings.KEY),id);
    }
    @Test void legacyUnstartedCardFreezesCurrentContactBeforeItsFirstSubmission()throws Exception {
        var passenger=user(false);UUID id=payment(passenger);prepareCard(passenger,id);
        app.getBean(MercadoPagoTransactions.class).prepareCard(new com.unicarona.security.Actor(passenger.id(),"user","active",null,null,null,true,Set.of()),id,JSON.convertValue(card("synthetic-pending-card"),com.unicarona.payment.dto.MercadoPagoDto.CardInput.class),UUID.randomUUID().toString());legacyCardPayload(id);
        String next=UUID.randomUUID()+"@example.test";sql.update("UPDATE users SET email=? WHERE id=?",next,passenger.id());LOSE_RESPONSE.set(true);
        assertEquals(503,send("POST","/api/v1/payments/"+id+"/sync",Map.of(),passenger).statusCode());
        String ciphertext=sql.queryForObject("SELECT payload_ciphertext FROM mercado_pago_card_commands WHERE payment_id=?",String.class,id);
        var frozen=JSON.readTree(app.getBean(CryptoService.class).decrypt(ciphertext,AuthTestSettings.KEY));assertEquals(1,frozen.path("format").asInt());assertEquals(next,frozen.path("payerEmail").asText());
        assertEquals(200,send("POST","/api/v1/payments/"+id+"/sync",Map.of(),passenger).statusCode());assertNull(sql.queryForObject("SELECT payload_ciphertext FROM mercado_pago_card_commands WHERE payment_id=?",String.class,id));
    }
    @Test void legacyAlreadySentCardDoesNotGuessContactAndRecoversOnlyFromVerifiedOrder()throws Exception {
        var passenger=user(false);UUID id=payment(passenger);prepareCard(passenger,id);LOSE_RESPONSE.set(true);
        assertEquals(503,send("POST","/api/v1/payments/"+id+"/card",card("synthetic-pending-card"),passenger).statusCode());legacyCardPayload(id);
        String order="ORD"+id.toString().replace("-","").toUpperCase(Locale.ROOT);var original=COMMANDS.get(order).deepCopy();sql.update("UPDATE users SET email=? WHERE id=?",UUID.randomUUID()+"@example.test",passenger.id());
        var response=send("POST","/api/v1/payments/"+id+"/sync",Map.of(),passenger);assertEquals(503,response.statusCode());assertEquals("PAYMENT_COMMAND_CONTACT_UNKNOWN",JSON.readTree(response.body()).path("code").asText());
        assertEquals(1,sql.queryForObject("SELECT attempts FROM mercado_pago_card_commands WHERE payment_id=?",Integer.class,id));assertNotNull(sql.queryForObject("SELECT payload_ciphertext FROM mercado_pago_card_commands WHERE payment_id=?",String.class,id));assertEquals(original,COMMANDS.get(order));
        assertEquals("PAYMENT_COMMAND_CONTACT_UNKNOWN",JSON.readTree(send("GET","/api/v1/payments/"+id+"/gateway",null,passenger).body()).path("lastErrorCode").asText());
        state(order,"processed","accredited");assertEquals(401,webhook(order,"processed","accredited",false).statusCode());assertEquals(0,sql.queryForObject("SELECT count(*) FROM ride_reservations WHERE payment_id=?",Integer.class,id));
        assertEquals(200,webhook(order,"forged","forged",true).statusCode());assertEquals(200,webhook(order,"forged","forged",true).statusCode());
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM ride_reservations WHERE payment_id=?",Integer.class,id));assertEquals(1,sql.queryForObject("SELECT count(*) FROM ledger_transactions WHERE payment_id=? AND kind='receipt'",Integer.class,id));assertNull(sql.queryForObject("SELECT payload_ciphertext FROM mercado_pago_card_commands WHERE payment_id=?",String.class,id));
    }
    @Test void encryptedCardEnvelopeCannotBeMovedToAnotherPaymentEvenWithTheSameCard()throws Exception {
        var passenger=user(false);UUID first=payment(passenger),second=payment(passenger);
        for(UUID id:List.of(first,second)){prepareCard(passenger,id);app.getBean(MercadoPagoTransactions.class).prepareCard(new com.unicarona.security.Actor(passenger.id(),"user","active",null,null,null,true,Set.of()),id,JSON.convertValue(card("synthetic-pending-card"),com.unicarona.payment.dto.MercadoPagoDto.CardInput.class),UUID.randomUUID().toString());}
        sql.update("UPDATE mercado_pago_card_commands SET payload_ciphertext=(SELECT payload_ciphertext FROM mercado_pago_card_commands WHERE payment_id=?) WHERE payment_id=?",first,second);
        var response=send("POST","/api/v1/payments/"+second+"/sync",Map.of(),passenger);assertEquals(503,response.statusCode());assertEquals("PAYMENT_COMMAND_INVALID",JSON.readTree(response.body()).path("code").asText());
        assertEquals(0,sql.queryForObject("SELECT attempts FROM mercado_pago_card_commands WHERE payment_id=?",Integer.class,second));assertFalse(COMMANDS.containsKey("ORD"+second.toString().replace("-","").toUpperCase(Locale.ROOT)));
    }
    @Test void deviceMetadataIsEncryptedFrozenForRecoveryAndNeverReturnedToTheBrowser() throws Exception {
        var passenger=user(false);UUID id=payment(passenger);prepareCard(passenger,id);String marker="synthetic-device+/opaque==";
        for(String invalid:List.of("synthetic\r\nInjected: header","short","x".repeat(1025))) {
            var bad=new LinkedHashMap<String,Object>(card("synthetic-pending-card"));bad.put("deviceSessionId",invalid);assertEquals(422,send("POST","/api/v1/payments/"+id+"/card",bad,passenger).statusCode());
        }
        assertEquals(0,sql.queryForObject("SELECT count(*) FROM mercado_pago_card_commands WHERE payment_id=?",Integer.class,id));
        var input=new LinkedHashMap<String,Object>(card("synthetic-pending-card"));input.put("deviceSessionId",marker);LOSE_RESPONSE.set(true);
        var lost=send("POST","/api/v1/payments/"+id+"/card",input,passenger);assertEquals(503,lost.statusCode(),lost.body());assertFalse(lost.body().contains(marker));
        String encrypted=sql.queryForObject("SELECT payload_ciphertext FROM mercado_pago_card_commands WHERE payment_id=?",String.class,id);assertNotNull(encrypted);assertFalse(encrypted.contains(marker));
        var recovered=send("POST","/api/v1/payments/"+id+"/sync",Map.of(),passenger);assertEquals(200,recovered.statusCode(),recovered.body());assertFalse(recovered.body().contains(marker));
        String order=sql.queryForObject("SELECT order_id FROM mercado_pago_orders WHERE payment_id=?",String.class,id);assertEquals(List.of(marker,marker),DEVICE_COMMANDS.get(order));assertFalse(COMMANDS.get(order).toString().contains(marker));
        assertFalse(send("GET","/api/v1/payments/"+id+"/gateway",null,passenger).body().contains(marker));assertNull(sql.queryForObject("SELECT payload_ciphertext FROM mercado_pago_card_commands WHERE payment_id=?",String.class,id));
        state(order,"processed","accredited");assertEquals(200,webhook(order,"forged","forged",true).statusCode());assertEquals(1,sql.queryForObject("SELECT count(*) FROM ride_reservations WHERE payment_id=?",Integer.class,id));
    }
    @Test void challengeIsPendingAndCardTokenIsNeverExposedByPublicOrOwnedEndpoints() throws Exception {
        var passenger=user(false);UUID id=payment(passenger);prepareCard(passenger,id);
        assertEquals(200,send("POST","/api/v1/payments/"+id+"/card",card("synthetic-challenge-card"),passenger).statusCode());
        String detail=send("GET","/api/v1/payments/"+id+"/gateway",null,passenger).body();assertEquals("pending_challenge",JSON.readTree(detail).path("statusDetail").asText());
        assertFalse(detail.contains("synthetic-challenge-card"));assertFalse(detail.contains("synthetic-not-a-real-token"));assertFalse(detail.contains(SECRET));
        String config=send("GET","/api/v1/payments/config",null,null).body();assertTrue(JSON.readTree(config).path("cardEnabled").asBoolean());assertFalse(config.contains(SECRET));
        assertEquals(0,sql.queryForObject("SELECT count(*) FROM ride_reservations WHERE payment_id=?",Integer.class,id));
    }
    @Test void refusalDuringOrderCreationIsVerifiedRecordedAndErasesTheTokenWithoutReserving() throws Exception {
        var passenger=user(false);UUID id=payment(passenger);prepareCard(passenger,id);
        var response=send("POST","/api/v1/payments/"+id+"/card",card("synthetic-refused-card"),passenger);
        assertEquals(200,response.statusCode(),response.body());assertFalse(response.body().contains("synthetic_sensitive_rejection"));
        assertEquals("failed",JSON.readTree(response.body()).path("status").asText());
        assertEquals("REJECTED",sql.queryForObject("SELECT state FROM mercado_pago_orders WHERE payment_id=?",String.class,id));
        assertEquals("rejected_by_issuer",JSON.readTree(send("GET","/api/v1/payments/"+id+"/gateway",null,passenger).body()).path("statusDetail").asText());
        assertEquals("done",sql.queryForObject("SELECT status FROM mercado_pago_card_commands WHERE payment_id=?",String.class,id));
        assertNull(sql.queryForObject("SELECT payload_ciphertext FROM mercado_pago_card_commands WHERE payment_id=?",String.class,id));
        assertEquals(200,send("POST","/api/v1/payments/"+id+"/sync",Map.of(),passenger).statusCode());
        assertEquals(1,sql.queryForObject("SELECT attempts FROM mercado_pago_card_commands WHERE payment_id=?",Integer.class,id));
        assertEquals(0,sql.queryForObject("SELECT count(*) FROM ride_reservations WHERE payment_id=?",Integer.class,id));
        assertEquals(0,sql.queryForObject("SELECT count(*) FROM ledger_transactions WHERE payment_id=?",Integer.class,id));
    }
    @Test void cardRejectsRawNumbersManipulatedPriceOtherOwnerAndSecondToken() throws Exception {
        var passenger=user(false);var outsider=user(false);UUID id=payment(passenger);prepareCard(passenger,id);
        var input=new LinkedHashMap<String,Object>(card("synthetic-pending-card"));input.put("cvv","123");input.put("price",1);
        assertEquals(422,send("POST","/api/v1/payments/"+id+"/card",input,passenger).statusCode());
        assertEquals(404,send("POST","/api/v1/payments/"+id+"/card",card("synthetic-pending-card"),outsider).statusCode());
        assertEquals(200,send("POST","/api/v1/payments/"+id+"/card",card("synthetic-pending-card"),passenger).statusCode());
        assertEquals(409,send("POST","/api/v1/payments/"+id+"/card",card("synthetic-new-card-token"),passenger).statusCode());
        assertEquals(404,send("POST","/api/v1/payments/"+id+"/sync",Map.of(),outsider).statusCode());
        assertEquals(404,send("GET","/api/v1/payments/"+id+"/summary",null,outsider).statusCode());
        var summary=JSON.readTree(send("GET","/api/v1/payments/"+id+"/summary",null,passenger).body());assertEquals(summary.path("totalCents").asLong(),summary.path("platformCents").asLong()+summary.path("driverCents").asLong());
    }
    @Test void maintenanceRecoversMissingWebhookAndDuplicateChecksDoNotDuplicateMoneyOrSeats() throws Exception {
        var passenger=user(false);UUID id=payment(passenger);String order=checkout(passenger,id);state(order,"processed","accredited");
        app.getBean(MaintenanceService.class).run(1);
        assertTrue(JSON.readTree(send("GET","/api/v1/payments/"+id,null,passenger).body()).path("confirmed").asBoolean());
        sql.update("UPDATE mercado_pago_orders SET next_check_at=now() WHERE payment_id=?",id);
        app.getBean(MercadoPagoReconciliation.class).run(1,()->false);
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM ride_reservations WHERE payment_id=?",Integer.class,id));
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM ledger_transactions WHERE payment_id=? AND kind='receipt'",Integer.class,id));
        assertTrue(sql.queryForObject("SELECT next_check_at>now()+interval '5 hours' AND reconcile_lease IS NULL AND reconcile_failures=0 FROM mercado_pago_orders WHERE payment_id=?",Boolean.class,id));
    }
    @Test void reconciliationBacksOffAnUnavailableOrderAndStillProcessesTheNextOne() throws Exception {
        var passenger=user(false);UUID broken=payment(passenger),healthy=payment(passenger);
        String badOrder=checkout(passenger,broken),goodOrder=checkout(passenger,healthy);FAIL_GET.add(badOrder);state(goodOrder,"processed","accredited");
        app.getBean(MercadoPagoReconciliation.class).run(2,()->false);
        var failure=sql.queryForMap("SELECT reconcile_failures,reconcile_error_code,reconcile_lease,next_check_at>now() AS delayed FROM mercado_pago_orders WHERE payment_id=?",broken);
        assertEquals(1,failure.get("reconcile_failures"));assertEquals("PAYMENT_PROVIDER_UNAVAILABLE",failure.get("reconcile_error_code"));assertNull(failure.get("reconcile_lease"));assertEquals(true,failure.get("delayed"));
        assertEquals("pending",sql.queryForObject("SELECT status FROM payments WHERE id=?",String.class,broken));
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM ride_reservations WHERE payment_id=?",Integer.class,healthy));
        FAIL_GET.clear();state(badOrder,"processed","accredited");sql.update("UPDATE mercado_pago_orders SET next_check_at=now() WHERE payment_id=?",broken);
        app.getBean(MercadoPagoReconciliation.class).run(1,()->false);
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM ride_reservations WHERE payment_id=?",Integer.class,broken));
        assertEquals(0,sql.queryForObject("SELECT reconcile_failures FROM mercado_pago_orders WHERE payment_id=?",Integer.class,broken));
    }
    @Test void reconciliationClaimsAreExclusiveAndExpiredWorkerLeasesCanBeRecovered() throws Exception {
        var passenger=user(false);UUID id=payment(passenger);checkout(passenger,id);var transactions=app.getBean(MercadoPagoTransactions.class);
        var claims=new ArrayList<MercadoPagoTransactions.ReconcileClaim>();
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(4)) {
            var futures=new ArrayList<java.util.concurrent.Future<Optional<MercadoPagoTransactions.ReconcileClaim>>>();
            for(int n=0;n<4;n++)futures.add(pool.submit(transactions::claimReconciliation));
            for(var future:futures)future.get(10,java.util.concurrent.TimeUnit.SECONDS).ifPresent(claims::add);
        }
        assertEquals(1,claims.size());var original=claims.getFirst();assertEquals(id,original.payment());
        assertTrue(transactions.claimReconciliation().isEmpty());
        sql.update("UPDATE mercado_pago_orders SET reconcile_lease_until=now()-interval '1 second' WHERE payment_id=?",id);
        var recovered=transactions.claimReconciliation().orElseThrow();assertNotEquals(original.lease(),recovered.lease());
        transactions.finishReconciliation(original,null);
        assertEquals(recovered.lease(),sql.queryForObject("SELECT reconcile_lease FROM mercado_pago_orders WHERE payment_id=?",UUID.class,id));
        transactions.finishReconciliation(recovered,null);
        assertNull(sql.queryForObject("SELECT reconcile_lease FROM mercado_pago_orders WHERE payment_id=?",UUID.class,id));
    }
    @Test void reconciliationRejectsExternalAmountTamperingAndCreditsLateReceiptsWithoutOverbooking() throws Exception {
        var passenger=user(false);UUID mismatch=payment(passenger);String wrong=checkout(passenger,mismatch);state(wrong,"processed","accredited");
        ORDERS.get(wrong).put("total_amount","999.99");((ObjectNode)ORDERS.get(wrong).path("transactions").path("payments").path(0)).put("paid_amount","999.99");
        app.getBean(MercadoPagoReconciliation.class).run(1,()->false);
        assertEquals("WEBHOOK_MISMATCH",sql.queryForObject("SELECT reconcile_error_code FROM mercado_pago_orders WHERE payment_id=?",String.class,mismatch));
        assertEquals(0,sql.queryForObject("SELECT count(*) FROM ride_reservations WHERE payment_id=?",Integer.class,mismatch));
        UUID late=payment(passenger);String order=checkout(passenger,late);int cents=sql.queryForObject("SELECT external_cents FROM payments WHERE id=?",Integer.class,late);
        sql.update("UPDATE payments SET expires_at=now()-interval '1 minute' WHERE id=?",late);
        sql.update("UPDATE ride_requests SET expires_at=now()-interval '1 minute' WHERE id=(SELECT request_id FROM payments WHERE id=?)",late);
        state(order,"processed","accredited");app.getBean(MaintenanceService.class).run(100);
        assertEquals(0,sql.queryForObject("SELECT count(*) FROM ride_reservations WHERE payment_id=?",Integer.class,late));
        assertEquals(cents,app.getBean(LedgerService.class).wallet(passenger.id()).credit());
        assertEquals("APPROVED",sql.queryForObject("SELECT state FROM mercado_pago_orders WHERE payment_id=?",String.class,late));
    }
    private UUID routine(Session driver) throws Exception {
        var response=send("POST","/api/v1/routines",Map.of("region","Gama","meeting","MP monthly stop","direction","ambos","time","23:58","returnTime","23:59","weekdays",List.of(0,1,2,3,4,5,6),"capacity",1,"distance",10,"automatic",true),driver);
        assertEquals(200,response.statusCode(),response.body());return UUID.fromString(JSON.readTree(response.body()).path("id").asText());
    }
    private UUID monthly(Session driver,Session passenger,UUID routine,String start,UUID previous) throws Exception {
        var input=new LinkedHashMap<String,Object>(Map.of("type","mensal","routineId",routine,"startDate",start,"directions","ambos","weekdays",List.of(0,1,2,3,4,5,6)));
        if(previous!=null)input.put("previousContractId",previous);
        var request=send("POST","/api/v1/ride-requests",input,passenger);assertEquals(200,request.statusCode(),request.body());
        String requestId=JSON.readTree(request.body()).path("id").asText();
        var accepted=send("POST","/api/v1/ride-requests/"+requestId+"/respond",Map.of("accept",true),driver);assertEquals(200,accepted.statusCode(),accepted.body());
        return UUID.fromString(JSON.readTree(accepted.body()).path("paymentId").asText());
    }
    @Test void monthlyPixUsesFrozenDiscountedScheduleAndCancelRenewalPreservesThePaidPeriod() throws Exception {
        var driver=user(true);var passenger=user(false);UUID routine=routine(driver);
        UUID id=monthly(driver,passenger,routine,LocalDate.now(ZoneId.of("America/Sao_Paulo")).plusDays(1).toString(),null);
        var summary=JSON.readTree(send("GET","/api/v1/payments/"+id+"/summary",null,passenger).body());
        assertEquals(summary.path("subtotalCents").asLong()-summary.path("discountCents").asLong(),summary.path("totalCents").asLong());assertTrue(summary.path("discountCents").asLong()>0);
        int expected=sql.queryForObject("SELECT count(*) FROM request_lines WHERE request_id=(SELECT request_id FROM payments WHERE id=?)",Integer.class,id);assertTrue(expected>2);
        String order=checkout(passenger,id);assertEquals(0,sql.queryForObject("SELECT count(*) FROM ride_reservations WHERE payment_id=?",Integer.class,id));
        state(order,"processed","accredited");assertEquals(200,webhook(order,"forged","forged",true).statusCode());assertEquals(200,webhook(order,"forged","forged",true).statusCode());
        UUID contract=sql.queryForObject("SELECT DISTINCT subscription_id FROM ride_reservations WHERE payment_id=?",UUID.class,id);
        long total=summary.path("totalCents").asLong();var ledger=app.getBean(LedgerService.class);
        assertEquals(expected,sql.queryForObject("SELECT count(*) FROM ride_reservations WHERE payment_id=? AND status='confirmed'",Integer.class,id));
        assertEquals(total,sql.queryForObject("SELECT sum(cents) FROM parcels WHERE payment_id=?",Long.class,id));
        assertEquals(total,ledger.balance(passenger.id(),AccountKind.FUTURE));assertEquals(0,ledger.balance(driver.id(),AccountKind.DRIVER_AVAILABLE));
        assertEquals(200,send("POST","/api/v1/subscriptions/"+contract+"/cancel-renewal",Map.of(),passenger).statusCode());
        assertEquals("cancelled_renewal",sql.queryForObject("SELECT status FROM subscriptions WHERE id=?",String.class,contract));
        assertEquals(expected,sql.queryForObject("SELECT count(*) FROM ride_reservations WHERE payment_id=? AND status='confirmed'",Integer.class,id));
        assertEquals(total,ledger.balance(passenger.id(),AccountKind.FUTURE));assertEquals(0,ledger.reconciliation().difference());
    }
    @Test void monthlyRenewalFailureKeepsGraceAndNewVerifiedPaymentReactivatesTheContract() throws Exception {
        var driver=user(true);var passenger=user(false);UUID routine=routine(driver);
        // A historical contract fixture isolates the 72-hour renewal clock; the new payment is real local HTTP.
        UUID old=sql.queryForObject("INSERT INTO subscriptions(routine_id,driver_id,passenger_id,class_id,start_date,end_date,weekdays,directions,total_cents,discount_bps,status,renew) VALUES(?,?,?,?,CURRENT_DATE-2,CURRENT_DATE-1,ARRAY[0,1,2,3,4,5,6]::smallint[],'ambos',1000,1000,'active',true) RETURNING id",UUID.class,routine,driver.id(),passenger.id(),classId);
        app.getBean(MaintenanceService.class).run(100);
        assertEquals("grace",sql.queryForObject("SELECT status FROM subscriptions WHERE id=?",String.class,old));
        assertTrue(sql.queryForObject("SELECT grace_until>now() AND grace_until<=now()+interval '72 hours' FROM subscriptions WHERE id=?",Boolean.class,old));
        String start=LocalDate.now(ZoneId.of("America/Sao_Paulo")).plusDays(1).toString();
        UUID rejected=monthly(driver,passenger,routine,start,old);String rejectedOrder=checkout(passenger,rejected);state(rejectedOrder,"failed","failed");assertEquals(200,webhook(rejectedOrder,"approved","forged",true).statusCode());
        assertEquals("grace",sql.queryForObject("SELECT status FROM subscriptions WHERE id=?",String.class,old));
        assertEquals(0,sql.queryForObject("SELECT count(*) FROM ride_reservations WHERE payment_id=?",Integer.class,rejected));
        UUID retried=monthly(driver,passenger,routine,start,old);String order=checkout(passenger,retried);state(order,"processed","accredited");assertEquals(200,webhook(order,"forged","forged",true).statusCode());
        assertEquals("renewed",sql.queryForObject("SELECT status FROM subscriptions WHERE id=?",String.class,old));
        assertEquals(0,sql.queryForObject("SELECT count(*) FROM subscription_schedule WHERE subscription_id=? AND held",Integer.class,old));
        assertEquals(1,sql.queryForObject("SELECT count(DISTINCT subscription_id) FROM ride_reservations WHERE payment_id=?",Integer.class,retried));
        assertEquals("active",sql.queryForObject("SELECT status FROM subscriptions WHERE previous_subscription_id=?",String.class,old));
        assertEquals(0,app.getBean(LedgerService.class).reconciliation().difference());
    }
    @Test void cardIdempotencyKeyCannotBeReusedForAnotherPaymentEvenWithTheSameToken() throws Exception {
        var passenger=user(false);UUID first=payment(passenger),second=payment(passenger);prepareCard(passenger,first);prepareCard(passenger,second);
        String key=UUID.randomUUID().toString();var input=card("synthetic-pending-card");
        assertEquals(200,send("POST","/api/v1/payments/"+first+"/card",input,passenger,key).statusCode());
        var changed=send("POST","/api/v1/payments/"+second+"/card",input,passenger,key);assertEquals(409,changed.statusCode(),changed.body());
        assertEquals("IDEMPOTENCY_CONFLICT",JSON.readTree(changed.body()).path("code").asText());
        assertEquals(0,sql.queryForObject("SELECT count(*) FROM mercado_pago_card_commands WHERE payment_id=?",Integer.class,second));
        assertNull(sql.queryForObject("SELECT provider_reference FROM payments WHERE id=?",String.class,second));
    }
    @Test void lostPixResponseIsRecoveredByTheOutboxWithAnIdenticalFrozenPayload() throws Exception {
        var passenger=user(false);UUID id=payment(passenger);LOSE_RESPONSE.set(true);
        var initial=send("POST","/api/v1/payments/"+id+"/checkout",Map.of("method","pix","useCredit",false),passenger);assertEquals(503,initial.statusCode(),initial.body());
        String order="ORD"+id.toString().replace("-","").toUpperCase(Locale.ROOT);var original=COMMANDS.get(order).deepCopy();
        assertNull(sql.queryForObject("SELECT provider_reference FROM payments WHERE id=?",String.class,id));
        assertEquals("pending",sql.queryForObject("SELECT status FROM mercado_pago_pix_commands WHERE payment_id=?",String.class,id));
        sql.update("UPDATE payments SET expires_at=expires_at-interval '10 minutes' WHERE id=?",id);
        sql.update("UPDATE outbox_jobs SET available_at=now()+interval '1 day' WHERE NOT(type='mercadopago_pix' AND payload->>'paymentId'=?)",id.toString());
        assertTrue(app.getBean(OutboxProcessor.class).processOne());
        assertEquals(original,COMMANDS.get(order));assertEquals(order,sql.queryForObject("SELECT provider_reference FROM payments WHERE id=?",String.class,id));
        assertEquals("done",sql.queryForObject("SELECT status FROM mercado_pago_pix_commands WHERE payment_id=?",String.class,id));
        assertEquals("done",sql.queryForObject("SELECT status FROM outbox_jobs WHERE type='mercadopago_pix' AND payload->>'paymentId'=?",String.class,id.toString()));
        assertEquals("synthetic-qr",JSON.readTree(send("GET","/api/v1/payments/"+id+"/gateway",null,passenger).body()).path("qrCode").asText());
        state(order,"processed","accredited");assertEquals(200,webhook(order,"forged","forged",true).statusCode());
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM ride_reservations WHERE payment_id=?",Integer.class,id));
    }
    @Test void neverSubmittedExpiredOrCancelledCommandsCannotStartAnExternalCharge() throws Exception {
        for(String method:List.of("pix","card"))for(boolean cancel:List.of(false,true)) {
            var passenger=user(false);UUID id=payment(passenger);
            assertEquals(200,send("POST","/api/v1/payments/"+id+"/checkout",Map.of("method","card","useCredit",false),passenger).statusCode());
            var commands=app.getBean(MercadoPagoTransactions.class);
            if("card".equals(method))commands.prepareCard(new com.unicarona.security.Actor(passenger.id(),"user","active",null,null,null,true,Set.of()),id,JSON.convertValue(card("synthetic-approved-card"),com.unicarona.payment.dto.MercadoPagoDto.CardInput.class),UUID.randomUUID().toString());
            else {sql.update("UPDATE payments SET method='pix' WHERE id=?",id);commands.preparePix(id,sql.queryForObject("SELECT external_cents FROM payments WHERE id=?",Integer.class,id));}
            if(cancel)assertEquals(200,send("POST","/api/v1/ride-requests/"+sql.queryForObject("SELECT request_id FROM payments WHERE id=?",UUID.class,id)+"/cancel",Map.of(),passenger).statusCode());
            else sql.update("UPDATE payments SET expires_at=now()-interval '1 minute' WHERE id=?",id);
            if("card".equals(method))app.getBean(MercadoPagoService.class).submitCard(id);else app.getBean(MercadoPagoService.class).submitPix(id);
            assertFalse(ORDERS.containsKey("ORD"+id.toString().replace("-","").toUpperCase(Locale.ROOT)));
            assertNull(sql.queryForObject("SELECT provider_reference FROM payments WHERE id=?",String.class,id));
            String table="card".equals(method)?"mercado_pago_card_commands":"mercado_pago_pix_commands";
            assertEquals("failed",sql.queryForObject("SELECT status FROM "+table+" WHERE payment_id=?",String.class,id));
            if("card".equals(method))assertNull(sql.queryForObject("SELECT payload_ciphertext FROM mercado_pago_card_commands WHERE payment_id=?",String.class,id));
        }
    }
    @Test void cancelledInternalRequestCancelsThePendingExternalOrderAndPreservesCredit() throws Exception {
        var passenger=user(false);UUID id=payment(passenger);String order=checkout(passenger,id);
        UUID request=sql.queryForObject("SELECT request_id FROM payments WHERE id=?",UUID.class,id);
        assertEquals(200,send("POST","/api/v1/ride-requests/"+request+"/cancel",Map.of(),passenger).statusCode());
        app.getBean(MercadoPagoReconciliation.class).run(1,()->false);
        assertEquals("CANCELLED",sql.queryForObject("SELECT state FROM mercado_pago_orders WHERE payment_id=?",String.class,id));
        assertEquals("cancelled",sql.queryForObject("SELECT cancel_outcome FROM mercado_pago_orders WHERE payment_id=?",String.class,id));
        assertNotNull(sql.queryForObject("SELECT cancel_completed_at FROM mercado_pago_orders WHERE payment_id=?",java.sql.Timestamp.class,id));
        assertEquals(1,CANCEL_KEYS.get(order).size());assertEquals(0,app.getBean(LedgerService.class).wallet(passenger.id()).credit());
        assertEquals(0,sql.queryForObject("SELECT count(*) FROM ride_reservations WHERE payment_id=?",Integer.class,id));
    }
    @Test void cancellationResponseLostIsConfirmedByGetWithoutSendingASecondCancel() throws Exception {
        var passenger=user(false);UUID id=payment(passenger);String order=checkout(passenger,id);
        sql.update("UPDATE payments SET expires_at=now()-interval '1 minute' WHERE id=?",id);LOSE_CANCEL_RESPONSE.set(true);
        app.getBean(MaintenanceService.class).run(100);
        assertEquals("PAYMENT_PROVIDER_UNAVAILABLE",sql.queryForObject("SELECT reconcile_error_code FROM mercado_pago_orders WHERE payment_id=?",String.class,id));
        UUID command=sql.queryForObject("SELECT cancel_command_id FROM mercado_pago_orders WHERE payment_id=?",UUID.class,id);
        assertEquals(command.toString(),CANCEL_KEYS.get(order).getFirst());
        assertNull(sql.queryForObject("SELECT cancel_completed_at FROM mercado_pago_orders WHERE payment_id=?",java.sql.Timestamp.class,id));
        sql.update("UPDATE mercado_pago_orders SET next_check_at=now() WHERE payment_id=?",id);
        app.getBean(MercadoPagoReconciliation.class).run(1,()->false);
        assertEquals("CANCELLED",sql.queryForObject("SELECT state FROM mercado_pago_orders WHERE payment_id=?",String.class,id));
        assertEquals(command,sql.queryForObject("SELECT cancel_command_id FROM mercado_pago_orders WHERE payment_id=?",UUID.class,id));assertEquals(1,CANCEL_KEYS.get(order).size());
    }
    @Test void paymentWinningTheCancellationRaceGetsCreditAndNoInvalidReservation() throws Exception {
        var passenger=user(false);UUID id=payment(passenger);String order=checkout(passenger,id);int cents=sql.queryForObject("SELECT external_cents FROM payments WHERE id=?",Integer.class,id);
        sql.update("UPDATE payments SET expires_at=now()-interval '1 minute' WHERE id=?",id);CANCEL_RACE.set(true);
        app.getBean(MaintenanceService.class).run(100);
        assertNull(sql.queryForObject("SELECT cancel_outcome FROM mercado_pago_orders WHERE payment_id=?",String.class,id));
        sql.update("UPDATE mercado_pago_orders SET next_check_at=now() WHERE payment_id=?",id);app.getBean(MercadoPagoReconciliation.class).run(1,()->false);
        assertEquals("paid_before_cancel",sql.queryForObject("SELECT cancel_outcome FROM mercado_pago_orders WHERE payment_id=?",String.class,id));
        assertEquals("APPROVED",sql.queryForObject("SELECT state FROM mercado_pago_orders WHERE payment_id=?",String.class,id));
        assertEquals(cents,app.getBean(LedgerService.class).wallet(passenger.id()).credit());
        assertEquals(0,sql.queryForObject("SELECT count(*) FROM ride_reservations WHERE payment_id=?",Integer.class,id));
        assertEquals(1,CANCEL_KEYS.get(order).size());assertEquals(0,app.getBean(LedgerService.class).reconciliation().difference());
    }
    private record Credit(UUID payment,String order,int cents) {}
    private Credit credit(Session passenger) throws Exception {
        return credit(passenger,"pix");
    }
    private Credit credit(Session passenger,String method) throws Exception {
        UUID id=payment(passenger);String order;
        if("card".equals(method)) {
            prepareCard(passenger,id);var response=send("POST","/api/v1/payments/"+id+"/card",card("synthetic-approved-card"),passenger);assertEquals(200,response.statusCode(),response.body());
            order=sql.queryForObject("SELECT order_id FROM mercado_pago_orders WHERE payment_id=?",String.class,id);
        } else order=checkout(passenger,id);
        state(order,"processed","accredited");assertEquals(200,webhook(order,"forged","forged",true).statusCode());
        var reservation=sql.queryForMap("SELECT ride_id,driver_id FROM ride_reservations WHERE payment_id=?",id);
        var canceled=send("POST","/api/v1/rides/"+reservation.get("ride_id")+"/cancel",Map.of("reason","Synthetic driver cancellation for refund"),session((UUID)reservation.get("driver_id")));assertEquals(200,canceled.statusCode(),canceled.body());
        return new Credit(id,order,sql.queryForObject("SELECT external_cents FROM payments WHERE id=?",Integer.class,id));
    }
    private UUID refund(Session passenger,int cents,String key) throws Exception {
        var response=send("POST","/api/v1/transfers",Map.of("type","refund","cents",cents),passenger,key);assertEquals(200,response.statusCode(),response.body());return UUID.fromString(JSON.readTree(response.body()).path("id").asText());
    }
    @Test void financialMutationsRequireAuthenticationCsrfAndTheConfiguredOriginBeforeCreatingCommands() throws Exception {
        var passenger=user(false);UUID id=payment(passenger);
        var routes=new LinkedHashMap<String,Object>();
        routes.put("/api/v1/payments/"+id+"/card",card("synthetic-approved-card"));
        routes.put("/api/v1/payments/"+id+"/sync",Map.of());
        routes.put("/api/v1/payments/"+id+"/checkout",Map.of("method","pix","useCredit",false));
        routes.put("/api/v1/transfers",Map.of("type","refund","cents",1));
        var wrongCsrf=new Session(passenger.id(),passenger.cookie(),"synthetic-wrong-csrf");
        for(var route:routes.entrySet()) {
            var anonymous=send("POST",route.getKey(),route.getValue(),null);assertEquals(401,anonymous.statusCode(),anonymous.body());
            var csrf=send("POST",route.getKey(),route.getValue(),wrongCsrf);assertEquals(403,csrf.statusCode(),csrf.body());assertEquals("CSRF_INVALID",JSON.readTree(csrf.body()).path("code").asText());
            var request=HttpRequest.newBuilder(URI.create(server+route.getKey())).header("Origin","https://untrusted.example.test")
                .header("Cookie",passenger.cookie()).header("x-csrf-token",passenger.csrf()).header("Content-Type","application/json")
                .header("idempotency-key",UUID.randomUUID().toString()).POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(route.getValue()))).build();
            var origin=http.send(request,HttpResponse.BodyHandlers.ofString());assertEquals(403,origin.statusCode(),origin.body());assertEquals("ORIGIN_INVALID",JSON.readTree(origin.body()).path("code").asText());
        }
        assertEquals("awaiting",sql.queryForObject("SELECT status FROM payments WHERE id=?",String.class,id));
        assertEquals(0,sql.queryForObject("SELECT count(*) FROM mercado_pago_card_commands WHERE payment_id=?",Integer.class,id));
        assertEquals(0,sql.queryForObject("SELECT count(*) FROM mercado_pago_pix_commands WHERE payment_id=?",Integer.class,id));
        assertEquals(0,sql.queryForObject("SELECT count(*) FROM transfers WHERE user_id=?",Integer.class,passenger.id()));
        assertEquals(0,sql.queryForObject("SELECT count(*) FROM ledger_transactions WHERE payment_id=?",Integer.class,id));
    }
    @Test void firstProviderRejectionClosesOnlyTheNeverUncertainCommand() throws Exception {
        for(String method:List.of("pix","card")) {
            var passenger=user(false);UUID id=payment(passenger);if("card".equals(method))prepareCard(passenger,id);
            REJECT_CREATE.set(true);
            var response="card".equals(method)?send("POST","/api/v1/payments/"+id+"/card",card("synthetic-pending-card"),passenger):send("POST","/api/v1/payments/"+id+"/checkout",Map.of("method","pix","useCredit",false),passenger);
            assertEquals(422,response.statusCode(),response.body());assertFalse(response.body().contains("synthetic_sensitive_rejection"));REJECT_CREATE.set(false);
            String table="card".equals(method)?"mercado_pago_card_commands":"mercado_pago_pix_commands";
            assertEquals("failed",sql.queryForObject("SELECT status FROM "+table+" WHERE payment_id=?",String.class,id));
            assertEquals(1,sql.queryForObject("SELECT attempts FROM "+table+" WHERE payment_id=?",Integer.class,id));
            assertNull(sql.queryForObject("SELECT provider_reference FROM payments WHERE id=?",String.class,id));
            assertFalse(ORDERS.containsKey("ORD"+id.toString().replace("-","").toUpperCase(Locale.ROOT)));
            if("card".equals(method))assertNull(sql.queryForObject("SELECT payload_ciphertext FROM mercado_pago_card_commands WHERE payment_id=?",String.class,id));
        }
    }
    @Test void lockedIdempotencyKeyIsRecoverableForPixAndCardWithoutDiscardingTheirCommands() throws Exception {
        for(String method:List.of("pix","card")) {
            var passenger=user(false);UUID id=payment(passenger);if("card".equals(method))prepareCard(passenger,id);LOCK_RESPONSE.set(true);
            var first="card".equals(method)?send("POST","/api/v1/payments/"+id+"/card",card("synthetic-pending-card"),passenger):send("POST","/api/v1/payments/"+id+"/checkout",Map.of("method","pix","useCredit",false),passenger);
            assertEquals(503,first.statusCode(),first.body());assertFalse(first.body().contains("synthetic_sensitive_lock"));
            String table="card".equals(method)?"mercado_pago_card_commands":"mercado_pago_pix_commands";
            assertEquals("pending",sql.queryForObject("SELECT status FROM "+table+" WHERE payment_id=?",String.class,id));
            if("card".equals(method))assertNotNull(sql.queryForObject("SELECT payload_ciphertext FROM "+table+" WHERE payment_id=?",String.class,id));
            String order="ORD"+id.toString().replace("-","").toUpperCase(Locale.ROOT);var payload=COMMANDS.get(order).deepCopy();
            assertEquals(200,send("POST","/api/v1/payments/"+id+"/sync",Map.of(),passenger).statusCode());assertEquals(payload,COMMANDS.get(order));
            state(order,"processed","accredited");assertEquals(200,webhook(order,"forged","forged",true).statusCode());
            assertEquals("done",sql.queryForObject("SELECT status FROM "+table+" WHERE payment_id=?",String.class,id));
            if("card".equals(method))assertNull(sql.queryForObject("SELECT payload_ciphertext FROM "+table+" WHERE payment_id=?",String.class,id));
            assertEquals(1,sql.queryForObject("SELECT count(*) FROM ride_reservations WHERE payment_id=?",Integer.class,id));
            assertEquals(1,sql.queryForObject("SELECT count(*) FROM ledger_transactions WHERE payment_id=? AND kind='receipt'",Integer.class,id));
        }
    }
    @Test void rejectedRetryAfterLostResponseRetainsTheCommandAndCanStillConfirmTheOriginalOrder() throws Exception {
        for(String method:List.of("pix","card")) {
            var passenger=user(false);UUID id=payment(passenger);if("card".equals(method))prepareCard(passenger,id);LOSE_RESPONSE.set(true);
            var first="card".equals(method)?send("POST","/api/v1/payments/"+id+"/card",card("synthetic-pending-card"),passenger):send("POST","/api/v1/payments/"+id+"/checkout",Map.of("method","pix","useCredit",false),passenger);
            assertEquals(503,first.statusCode(),first.body());String order="ORD"+id.toString().replace("-","").toUpperCase(Locale.ROOT);var payload=COMMANDS.get(order).deepCopy();
            REJECT_CREATE.set(true);assertEquals(422,send("POST","/api/v1/payments/"+id+"/sync",Map.of(),passenger).statusCode());REJECT_CREATE.set(false);
            String table="card".equals(method)?"mercado_pago_card_commands":"mercado_pago_pix_commands";
            assertEquals("pending",sql.queryForObject("SELECT status FROM "+table+" WHERE payment_id=?",String.class,id));assertEquals(2,sql.queryForObject("SELECT attempts FROM "+table+" WHERE payment_id=?",Integer.class,id));
            if("card".equals(method))assertNotNull(sql.queryForObject("SELECT payload_ciphertext FROM mercado_pago_card_commands WHERE payment_id=?",String.class,id));
            assertEquals(0,sql.queryForObject("SELECT count(*) FROM ride_reservations WHERE payment_id=?",Integer.class,id));
            assertEquals(200,send("POST","/api/v1/payments/"+id+"/sync",Map.of(),passenger).statusCode());assertEquals(payload,COMMANDS.get(order));
            state(order,"processed","accredited");assertEquals(200,webhook(order,"forged","forged",true).statusCode());assertEquals(200,webhook(order,"forged","forged",true).statusCode());
            assertEquals("done",sql.queryForObject("SELECT status FROM "+table+" WHERE payment_id=?",String.class,id));
            assertEquals(1,sql.queryForObject("SELECT count(*) FROM ride_reservations WHERE payment_id=?",Integer.class,id));
            assertEquals(1,sql.queryForObject("SELECT count(*) FROM ledger_transactions WHERE payment_id=? AND kind='receipt'",Integer.class,id));
        }
    }
    @Test void fullRefundReturnsCreditedMoneyOnceAndKeepsTheOriginalReceiptImmutable() throws Exception {
        var passenger=user(false);var source=credit(passenger);String key=UUID.randomUUID().toString();UUID transfer=refund(passenger,source.cents(),key);
        assertEquals(transfer,refund(passenger,source.cents(),key));assertEquals(0,app.getBean(LedgerService.class).wallet(passenger.id()).credit());assertEquals(source.cents(),app.getBean(LedgerService.class).wallet(passenger.id()).processing());
        app.getBean(TransferService.class).submit(transfer);app.getBean(TransferService.class).submit(transfer);
        assertEquals("approved",sql.queryForObject("SELECT status FROM transfers WHERE id=?",String.class,transfer));
        assertEquals("REFUNDED",sql.queryForObject("SELECT state FROM mercado_pago_orders WHERE payment_id=?",String.class,source.payment()));
        assertEquals("approved",sql.queryForObject("SELECT status FROM payments WHERE id=?",String.class,source.payment()));
        assertEquals(source.cents(),app.getBean(LedgerService.class).wallet(passenger.id()).returned());assertEquals(0,app.getBean(LedgerService.class).wallet(passenger.id()).processing());
        UUID command=sql.queryForObject("SELECT id FROM mercado_pago_refunds WHERE transfer_id=?",UUID.class,transfer);assertTrue(REFUND_COMMANDS.get(command.toString()).isEmpty());
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM ledger_transactions WHERE event_key=?",Integer.class,"transfer:"+transfer+":completed"));assertEquals(0,app.getBean(LedgerService.class).reconciliation().difference());
        var detail=send("GET","/api/v1/transfers/"+transfer+"/refunds",null,passenger);assertEquals(200,detail.statusCode(),detail.body());assertEquals(source.cents(),JSON.readTree(detail.body()).path("returnedCents").asInt());
        assertEquals(404,send("GET","/api/v1/transfers/"+transfer+"/refunds",null,user(false)).statusCode());
    }
    @Test void partialRefundLostResponseReusesTheSameIdentityWithoutRestoringSpentCredit() throws Exception {
        var passenger=user(false);var source=credit(passenger);int cents=source.cents()/2;UUID transfer=refund(passenger,cents,UUID.randomUUID().toString());LOSE_REFUND_RESPONSE.set(true);
        var error=assertThrows(com.unicarona.shared.ApiException.class,()->app.getBean(TransferService.class).submit(transfer));assertEquals("REFUND_PROCESSING",error.code());
        assertEquals(cents,app.getBean(LedgerService.class).wallet(passenger.id()).processing());assertEquals(source.cents()-cents,app.getBean(LedgerService.class).wallet(passenger.id()).credit());
        UUID command=sql.queryForObject("SELECT id FROM mercado_pago_refunds WHERE transfer_id=?",UUID.class,transfer);assertNull(sql.queryForObject("SELECT provider_reference FROM mercado_pago_refunds WHERE id=?",String.class,command));
        app.getBean(TransferService.class).submit(transfer);
        assertEquals("approved",sql.queryForObject("SELECT status FROM transfers WHERE id=?",String.class,transfer));assertEquals("PARTIALLY_REFUNDED",sql.queryForObject("SELECT state FROM mercado_pago_orders WHERE payment_id=?",String.class,source.payment()));
        assertEquals(cents,app.getBean(LedgerService.class).wallet(passenger.id()).returned());assertEquals(source.cents()-cents,app.getBean(LedgerService.class).wallet(passenger.id()).credit());
        assertEquals(1,ORDERS.get(source.order()).path("transactions").path("refunds").size());assertEquals(MercadoPagoOrdersClient.amount(cents),REFUND_COMMANDS.get(command.toString()).path("transactions").path(0).path("amount").asText());
    }
    @Test void repeatedPartialRefundsReadFullHistoryAndRecoverTheSameCommandWithoutChoosingAnOlderEqualAmount() throws Exception {
        var passenger=user(false);var source=credit(passenger);var service=app.getBean(TransferService.class);
        UUID first=refund(passenger,100,UUID.randomUUID().toString());service.submit(first);
        UUID firstReference=sql.queryForObject("SELECT id FROM mercado_pago_refunds WHERE transfer_id=?",UUID.class,first);
        String firstExternal=sql.queryForObject("SELECT provider_reference FROM mercado_pago_refunds WHERE id=?",String.class,firstReference);
        UUID second=refund(passenger,100,UUID.randomUUID().toString());LOSE_REFUND_RESPONSE.set(true);
        assertEquals("REFUND_PROCESSING",assertThrows(com.unicarona.shared.ApiException.class,()->service.submit(second)).code());
        UUID secondCommand=sql.queryForObject("SELECT id FROM mercado_pago_refunds WHERE transfer_id=?",UUID.class,second);
        assertNull(sql.queryForObject("SELECT provider_reference FROM mercado_pago_refunds WHERE id=?",String.class,secondCommand));
        service.submit(second);service.submit(second);
        assertEquals("approved",sql.queryForObject("SELECT status FROM transfers WHERE id=?",String.class,second));
        assertNotEquals(firstExternal,sql.queryForObject("SELECT provider_reference FROM mercado_pago_refunds WHERE id=?",String.class,secondCommand));
        UUID last=refund(passenger,source.cents()-200,UUID.randomUUID().toString());service.submit(last);
        assertEquals(3,ORDERS.get(source.order()).path("transactions").path("refunds").size());
        assertEquals(3,sql.queryForObject("SELECT count(DISTINCT provider_reference) FROM mercado_pago_refunds WHERE payment_id=? AND status='approved'",Integer.class,source.payment()));
        assertEquals(source.cents(),app.getBean(LedgerService.class).wallet(passenger.id()).returned());assertEquals(0,app.getBean(LedgerService.class).wallet(passenger.id()).credit());assertEquals(0,app.getBean(LedgerService.class).wallet(passenger.id()).processing());
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM ledger_transactions WHERE payment_id=? AND kind='receipt'",Integer.class,source.payment()));assertEquals(0,app.getBean(LedgerService.class).reconciliation().difference());
    }
    @Test void rejectionFromAnOlderRefundAttemptCannotRestoreCreditAfterAnotherAttemptStartedOrAttached() throws Exception {
        for(String phase:List.of("second_started","second_attached","first_attached")) {
            var passenger=user(false);var source=credit(passenger);UUID transfer=refund(passenger,source.cents(),UUID.randomUUID().toString());
            UUID id=sql.queryForObject("SELECT id FROM mercado_pago_refunds WHERE transfer_id=?",UUID.class,transfer);
            var transactions=app.getBean(MercadoPagoRefundTransactions.class);
            var first=transactions.start(id).orElseThrow();var second="first_attached".equals(phase)?first:transactions.start(id).orElseThrow();
            assertEquals(1,first.attempts());assertEquals("first_attached".equals(phase)?1:2,second.attempts());
            if(!"second_started".equals(phase)) {
                var client=app.getBean(MercadoPagoOrdersClient.class);
                var result=client.refund(second.order(),second.transaction(),second.cents(),second.id(),true);
                transactions.attach(second,client.refundResult(result,second.order(),second.transaction(),second.cents()));
            }
            // An older POST rejection or a GET rejection after attachment is not proof that no refund exists.
            transactions.error(first,"PAYMENT_PROVIDER_REJECTED");
            assertEquals("processing",sql.queryForObject("SELECT status FROM mercado_pago_refunds WHERE id=?",String.class,id));
            assertEquals("pending",sql.queryForObject("SELECT status FROM transfers WHERE id=?",String.class,transfer));
            assertEquals(0,app.getBean(LedgerService.class).wallet(passenger.id()).credit());
            assertEquals(source.cents(),app.getBean(LedgerService.class).wallet(passenger.id()).processing());
            app.getBean(TransferService.class).submit(transfer);
            assertEquals("approved",sql.queryForObject("SELECT status FROM transfers WHERE id=?",String.class,transfer));
            assertEquals(source.cents(),app.getBean(LedgerService.class).wallet(passenger.id()).returned());
            assertEquals(0,app.getBean(LedgerService.class).wallet(passenger.id()).processing());
            assertEquals(1,ORDERS.get(source.order()).path("transactions").path("refunds").size());
            assertEquals(1,sql.queryForObject("SELECT count(*) FROM ledger_transactions WHERE event_key=?",Integer.class,"transfer:"+transfer+":completed"));
            assertEquals(0,app.getBean(LedgerService.class).reconciliation().difference());
        }
    }
    @Test void fullRefundWithLostResponseAndEarlyWebhookKeepsTheOriginalEmptyBody() throws Exception {
        var passenger=user(false);var source=credit(passenger);UUID transfer=refund(passenger,source.cents(),UUID.randomUUID().toString());LOSE_REFUND_RESPONSE.set(true);
        assertThrows(com.unicarona.shared.ApiException.class,()->app.getBean(TransferService.class).submit(transfer));
        assertEquals(200,webhook(source.order(),"forged","forged",true).statusCode());
        assertTrue(sql.queryForObject("SELECT untracked_refunds FROM mercado_pago_orders WHERE payment_id=?",Boolean.class,source.payment()));
        assertEquals(0,app.getBean(LedgerService.class).wallet(passenger.id()).returned());
        assertEquals(source.cents(),app.getBean(LedgerService.class).wallet(passenger.id()).processing());
        app.getBean(TransferService.class).submit(transfer);
        assertEquals("approved",sql.queryForObject("SELECT status FROM transfers WHERE id=?",String.class,transfer));
        assertFalse(sql.queryForObject("SELECT untracked_refunds FROM mercado_pago_orders WHERE payment_id=?",Boolean.class,source.payment()));
        assertEquals(source.cents(),app.getBean(LedgerService.class).wallet(passenger.id()).returned());
        assertEquals(1,ORDERS.get(source.order()).path("transactions").path("refunds").size());
        assertEquals(0,app.getBean(LedgerService.class).reconciliation().difference());
    }
    @Test void pendingRefundIsCompletedBySignedWebhookAndRejectsInvalidOrderSignature() throws Exception {
        var passenger=user(false);var source=credit(passenger);UUID transfer=refund(passenger,source.cents(),UUID.randomUUID().toString());PENDING_REFUND.set(true);
        assertThrows(com.unicarona.shared.ApiException.class,()->app.getBean(TransferService.class).submit(transfer));assertEquals("pending",sql.queryForObject("SELECT status FROM transfers WHERE id=?",String.class,transfer));
        assertEquals(0,app.getBean(LedgerService.class).wallet(passenger.id()).returned());
        var order=ORDERS.get(source.order());((ObjectNode)order.path("transactions").path("refunds").path(0)).put("status","processed");order.put("status","refunded").put("status_detail","refunded").put("version",order.path("version").asInt()+1);
        assertEquals(401,webhook(source.order(),"refunded","forged",false).statusCode());assertEquals(0,app.getBean(LedgerService.class).wallet(passenger.id()).returned());
        assertEquals(200,webhook(source.order(),"forged","forged",true).statusCode());assertEquals(200,webhook(source.order(),"forged","forged",true).statusCode());
        assertEquals("approved",sql.queryForObject("SELECT status FROM transfers WHERE id=?",String.class,transfer));assertEquals(source.cents(),app.getBean(LedgerService.class).wallet(passenger.id()).returned());
    }
    @Test void batchWithOneRejectedRefundRestoresOnlyTheUnreturnedPart() throws Exception {
        var passenger=user(false);var first=credit(passenger);var second=credit(passenger);UUID transfer=refund(passenger,first.cents()+second.cents(),UUID.randomUUID().toString());REJECT_REFUND_ORDER.add(second.order());
        app.getBean(TransferService.class).submit(transfer);
        assertEquals("partially_refunded",sql.queryForObject("SELECT status FROM transfers WHERE id=?",String.class,transfer));
        assertEquals(first.cents(),app.getBean(LedgerService.class).wallet(passenger.id()).returned());assertEquals(second.cents(),app.getBean(LedgerService.class).wallet(passenger.id()).credit());assertEquals(0,app.getBean(LedgerService.class).wallet(passenger.id()).processing());
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM mercado_pago_refunds WHERE transfer_id=? AND status='approved'",Integer.class,transfer));assertEquals(1,sql.queryForObject("SELECT count(*) FROM mercado_pago_refunds WHERE transfer_id=? AND status='failed'",Integer.class,transfer));
        assertEquals(0,app.getBean(LedgerService.class).reconciliation().difference());
    }
    @Test void refundCannotUseAnotherPassengersReceiptOrFutureParcelsAndCannotExceedCredit() throws Exception {
        var owner=user(false);var source=credit(owner);var outsider=user(false);
        assertEquals(409,send("POST","/api/v1/transfers",Map.of("type","refund","cents",source.cents()),outsider).statusCode());
        assertEquals(409,send("POST","/api/v1/transfers",Map.of("type","refund","cents",source.cents()+1),owner).statusCode());
        UUID active=payment(outsider);String order=checkout(outsider,active);state(order,"processed","accredited");assertEquals(200,webhook(order,"forged","forged",true).statusCode());
        assertEquals(409,send("POST","/api/v1/transfers",Map.of("type","refund","cents",1),outsider).statusCode());assertEquals(0,sql.queryForObject("SELECT count(*) FROM transfers WHERE user_id=?",Integer.class,outsider.id()));
        assertEquals(source.cents(),app.getBean(LedgerService.class).wallet(owner.id()).credit());
    }
    @Test void simultaneousRefundAndCreditCheckoutCannotSpendTheSameBalanceTwice() throws Exception {
        for(int attempt=0;attempt<3;attempt++) {
            var passenger=user(false);var source=credit(passenger);UUID next=payment(passenger);
            var ready=new java.util.concurrent.CountDownLatch(2);var go=new java.util.concurrent.CountDownLatch(1);
            try(var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                var purchase=pool.submit(()->{ready.countDown();go.await();return send("POST","/api/v1/payments/"+next+"/checkout",Map.of("method","pix","useCredit",true),passenger);});
                var refund=pool.submit(()->{ready.countDown();go.await();return send("POST","/api/v1/transfers",Map.of("type","refund","cents",source.cents()),passenger);});
                assertTrue(ready.await(5,java.util.concurrent.TimeUnit.SECONDS));go.countDown();
                var purchased=purchase.get(15,java.util.concurrent.TimeUnit.SECONDS);var returned=refund.get(15,java.util.concurrent.TimeUnit.SECONDS);
                assertEquals(200,purchased.statusCode(),purchased.body());assertTrue(Set.of(200,409).contains(returned.statusCode()),returned.body());
                boolean confirmed=JSON.readTree(purchased.body()).path("confirmed").asBoolean();
                assertEquals(confirmed?409:200,returned.statusCode());
                assertEquals(confirmed?1:0,sql.queryForObject("SELECT count(*) FROM ride_reservations WHERE payment_id=?",Integer.class,next));
                assertEquals(confirmed?source.cents():0,sql.queryForObject("SELECT credit_cents FROM payments WHERE id=?",Integer.class,next));
                assertEquals(confirmed?0:source.cents(),app.getBean(LedgerService.class).wallet(passenger.id()).processing());
                assertEquals(0,app.getBean(LedgerService.class).wallet(passenger.id()).credit());
                assertEquals(0,app.getBean(LedgerService.class).reconciliation().difference());
            }
        }
    }
    private String externalRefund(String order,int cents) {
        var external=ORDERS.get(order);
        String transaction=external.path("transactions").path("payments").path(0).path("id").asText();
        String reference="REF"+UUID.randomUUID().toString().replace("-","").toUpperCase(Locale.ROOT);
        ((ObjectNode)external.path("transactions")).putArray("refunds").addObject().put("id",reference).put("transaction_id",transaction).put("amount",MercadoPagoOrdersClient.amount(cents)).put("status","processed");
        external.put("status_detail","partially_refunded").put("version",external.path("version").asInt()+1);
        return reference;
    }
    @Test void spentCancelledCreditBecomesDebtAndBlocksEveryNewCheckoutWithoutDuplicateAssessment() throws Exception {
        var passenger=user(false);var source=credit(passenger);UUID consumed=payment(passenger);
        var spent=send("POST","/api/v1/payments/"+consumed+"/checkout",Map.of("method","pix","useCredit",true),passenger);
        assertEquals(200,spent.statusCode(),spent.body());assertTrue(JSON.readTree(spent.body()).path("confirmed").asBoolean());
        assertEquals(0,app.getBean(LedgerService.class).wallet(passenger.id()).credit());
        externalRefund(source.order(),source.cents()/2);
        assertEquals(401,webhook(source.order(),"forged","forged",false).statusCode());
        assertEquals(0,app.getBean(PassengerDebtService.class).summary(passenger.id()).dueCents());
        for(int n=0;n<3;n++)assertEquals(200,webhook(source.order(),"forged","forged",true).statusCode());
        var debt=send("GET","/api/v1/wallet/debt",null,passenger);assertEquals(200,debt.statusCode(),debt.body());
        assertEquals(source.cents()/2,JSON.readTree(debt.body()).path("dueCents").asLong());assertTrue(JSON.readTree(debt.body()).path("purchasesBlocked").asBoolean());
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM passenger_debt_assessments WHERE payment_id=?",Integer.class,source.payment()));
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM audit_logs WHERE resource_id=? AND action='passenger_debt_assessed'",Integer.class,source.payment()));
        assertEquals(401,send("GET","/api/v1/wallet/debt",null,null).statusCode());
        assertEquals(0,JSON.readTree(send("GET","/api/v1/wallet/debt",null,user(false)).body()).path("dueCents").asLong());
        for(String method:List.of("pix","card"))for(boolean credit:List.of(false,true)) {
            UUID next=payment(passenger);var denied=send("POST","/api/v1/payments/"+next+"/checkout",Map.of("method",method,"useCredit",credit),passenger);
            assertEquals(409,denied.statusCode(),denied.body());assertEquals("PASSENGER_DEBT",JSON.readTree(denied.body()).path("code").asText());
            assertNull(sql.queryForObject("SELECT provider_reference FROM payments WHERE id=?",String.class,next));
        }
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM ride_reservations WHERE payment_id=?",Integer.class,consumed));
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM ledger_transactions WHERE payment_id=? AND kind='receipt'",Integer.class,source.payment()));
        assertEquals(0,app.getBean(LedgerService.class).reconciliation().difference());
    }
    @Test void onlySettledChargebackCreatesDebtAndReservedRefundIsNeverMistakenForSpentCredit() throws Exception {
        var passenger=user(false);var source=credit(passenger,"card");UUID consumed=payment(passenger);
        assertEquals(200,send("POST","/api/v1/payments/"+consumed+"/checkout",Map.of("method","pix","useCredit",true),passenger).statusCode());
        state(source.order(),"charged_back","in_process");assertEquals(200,webhook(source.order(),"forged","forged",true).statusCode());
        assertEquals(0,app.getBean(PassengerDebtService.class).summary(passenger.id()).dueCents());
        state(source.order(),"charged_back","settled");assertEquals(200,webhook(source.order(),"forged","forged",true).statusCode());
        assertEquals(source.cents(),app.getBean(PassengerDebtService.class).summary(passenger.id()).dueCents());
        var other=user(false);var reserved=credit(other);refund(other,reserved.cents(),UUID.randomUUID().toString());
        externalRefund(reserved.order(),reserved.cents()/2);assertEquals(200,webhook(reserved.order(),"forged","forged",true).statusCode());
        assertEquals(0,app.getBean(PassengerDebtService.class).summary(other.id()).dueCents());
        assertEquals(reserved.cents(),app.getBean(LedgerService.class).wallet(other.id()).processing());
    }
    private Credit spentDebt(Session passenger) throws Exception {
        var source=credit(passenger);UUID consumed=payment(passenger);
        assertEquals(200,send("POST","/api/v1/payments/"+consumed+"/checkout",Map.of("method","pix","useCredit",true),passenger).statusCode());
        externalRefund(source.order(),source.cents());
        assertEquals(200,webhook(source.order(),"forged","forged",true).statusCode());
        assertEquals(source.cents(),app.getBean(PassengerDebtService.class).summary(passenger.id()).dueCents());return source;
    }
    @Test void debtPixOnlyUnblocksAfterVerifiedApprovalAndNeverCreatesAnotherRideReceipt() throws Exception {
        var passenger=user(false);var source=spentDebt(passenger);var outsider=user(false);
        assertEquals(401,send("POST","/api/v1/wallet/debt/checkout",Map.of(),null).statusCode());
        assertEquals(422,send("POST","/api/v1/wallet/debt/checkout",Map.of("amountCents",1),passenger).statusCode());
        var opened=send("POST","/api/v1/wallet/debt/checkout",Map.of(),passenger);assertEquals(200,opened.statusCode(),opened.body());
        UUID id=UUID.fromString(JSON.readTree(opened.body()).path("id").asText());String order=sql.queryForObject("SELECT order_id FROM passenger_debt_payments WHERE id=?",String.class,id);
        assertEquals(source.cents(),JSON.readTree(opened.body()).path("amountCents").asInt());
        assertEquals(id.toString(),JSON.readTree(send("POST","/api/v1/wallet/debt/checkout",Map.of(),passenger).body()).path("id").asText());
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM passenger_debt_payments WHERE passenger_id=?",Integer.class,passenger.id()));
        assertEquals(404,send("GET","/api/v1/wallet/debt/payments/"+id,null,outsider).statusCode());
        assertEquals(404,send("POST","/api/v1/wallet/debt/payments/"+id+"/sync",Map.of(),outsider).statusCode());
        assertTrue(app.getBean(PassengerDebtService.class).summary(passenger.id()).purchasesBlocked());
        state(order,"processed","accredited");assertEquals(401,webhook(order,"forged","forged",false).statusCode());
        assertTrue(app.getBean(PassengerDebtService.class).summary(passenger.id()).purchasesBlocked());
        for(int n=0;n<3;n++)assertEquals(200,webhook(order,"forged","forged",true).statusCode());
        assertEquals(0,app.getBean(PassengerDebtService.class).summary(passenger.id()).dueCents());
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM ledger_transactions WHERE event_key=?",Integer.class,"debt-payment:"+id+":confirmed"));
        assertEquals(0,sql.queryForObject("SELECT count(*) FROM payments WHERE id=?",Integer.class,id));
        assertEquals(0,sql.queryForObject("SELECT count(*) FROM ride_reservations WHERE payment_id=?",Integer.class,id));
        UUID next=payment(passenger);assertEquals(200,send("POST","/api/v1/payments/"+next+"/checkout",Map.of("method","pix","useCredit",false),passenger).statusCode());
        assertEquals(0,app.getBean(LedgerService.class).reconciliation().difference());
        assertThrows(RuntimeException.class,()->sql.update("UPDATE passenger_debt_payments SET amount_cents=1 WHERE id=?",id));
    }
    @Test void lostDebtPixResponseIsRecoveredByWorkerUsingTheSameFrozenCommand() throws Exception {
        var passenger=user(false);spentDebt(passenger);LOSE_RESPONSE.set(true);
        assertEquals(503,send("POST","/api/v1/wallet/debt/checkout",Map.of(),passenger).statusCode());
        UUID id=sql.queryForObject("SELECT id FROM passenger_debt_payments WHERE passenger_id=?",UUID.class,passenger.id());
        String order="ORD"+id.toString().replace("-","").toUpperCase(Locale.ROOT);JsonNode original=COMMANDS.get(order).deepCopy();
        app.getBean(DebtPaymentService.class).run(1,()->false);
        assertEquals(order,sql.queryForObject("SELECT order_id FROM passenger_debt_payments WHERE id=?",String.class,id));
        assertEquals(original,COMMANDS.get(order));assertTrue(app.getBean(PassengerDebtService.class).summary(passenger.id()).purchasesBlocked());
        state(order,"processed","accredited");ORDERS.get(order).put("total_amount","0.01");((ObjectNode)ORDERS.get(order).path("transactions").path("payments").path(0)).put("paid_amount","0.01");
        assertEquals(422,webhook(order,"forged","forged",true).statusCode());assertTrue(app.getBean(PassengerDebtService.class).summary(passenger.id()).purchasesBlocked());
        ORDERS.get(order).put("total_amount",original.path("total_amount").asText());
        ((ObjectNode)ORDERS.get(order).path("transactions").path("payments").path(0)).put("paid_amount",original.path("total_amount").asText());
        assertEquals(200,send("POST","/api/v1/wallet/debt/payments/"+id+"/sync",Map.of(),passenger).statusCode());
        assertFalse(app.getBean(PassengerDebtService.class).summary(passenger.id()).purchasesBlocked());
    }
    @Test void concurrentDebtCheckoutsShareOneProviderCommandEvenWithDifferentKeys() throws Exception {
        var passenger=user(false);spentDebt(passenger);var go=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var a=pool.submit(()->{go.await();return send("POST","/api/v1/wallet/debt/checkout",Map.of(),passenger);});
            var b=pool.submit(()->{go.await();return send("POST","/api/v1/wallet/debt/checkout",Map.of(),passenger);});go.countDown();
            var first=a.get(15,java.util.concurrent.TimeUnit.SECONDS);var second=b.get(15,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(200,first.statusCode(),first.body());assertEquals(200,second.statusCode(),second.body());
            assertEquals(JSON.readTree(first.body()).path("id"),JSON.readTree(second.body()).path("id"));
            assertEquals(1,sql.queryForObject("SELECT count(*) FROM passenger_debt_payments WHERE passenger_id=?",Integer.class,passenger.id()));
            assertTrue(app.getBean(PassengerDebtService.class).summary(passenger.id()).purchasesBlocked());
        }
    }
    @Test void alreadyRefundedDebtPaymentCannotBeRevivedByAStaleApproval() throws Exception {
        var passenger=user(false);spentDebt(passenger);
        var opened=send("POST","/api/v1/wallet/debt/checkout",Map.of(),passenger);UUID id=UUID.fromString(JSON.readTree(opened.body()).path("id").asText());
        String order=sql.queryForObject("SELECT order_id FROM passenger_debt_payments WHERE id=?",String.class,id);
        state(order,"refunded","refunded");assertEquals(200,webhook(order,"forged","forged",true).statusCode());
        state(order,"processed","accredited");assertEquals(200,webhook(order,"forged","forged",true).statusCode());
        assertTrue(app.getBean(PassengerDebtService.class).summary(passenger.id()).purchasesBlocked());
        assertNull(sql.queryForObject("SELECT paid_at FROM passenger_debt_payments WHERE id=?",java.sql.Timestamp.class,id));
        assertEquals(0,sql.queryForObject("SELECT count(*) FROM ledger_transactions WHERE event_key=?",Integer.class,"debt-payment:"+id+":confirmed"));
    }
    @Test void firstObservationOfPartiallyRefundedRepaymentSettlesOnlyNetMoney() throws Exception {
        var passenger=user(false);var source=spentDebt(passenger);
        var opened=send("POST","/api/v1/wallet/debt/checkout",Map.of(),passenger);UUID id=UUID.fromString(JSON.readTree(opened.body()).path("id").asText());
        String order=sql.queryForObject("SELECT order_id FROM passenger_debt_payments WHERE id=?",String.class,id);
        state(order,"processed","accredited");externalRefund(order,source.cents()/2);
        for(int n=0;n<2;n++)assertEquals(200,webhook(order,"forged","forged",true).statusCode());
        assertEquals(source.cents()/2,app.getBean(PassengerDebtService.class).summary(passenger.id()).dueCents());
        assertEquals("PARTIALLY_REFUNDED",sql.queryForObject("SELECT state FROM passenger_debt_payments WHERE id=?",String.class,id));
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM ledger_transactions WHERE event_key=?",Integer.class,"debt-payment:"+id+":confirmed"));
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM ledger_transactions WHERE event_key LIKE ?",Integer.class,"debt-payment:"+id+":reversed:%"));
        assertEquals(0,app.getBean(LedgerService.class).reconciliation().difference());
    }
    @Test void externalRefundOfDebtRepaymentReopensOnlyTheVerifiedAmountOnce() throws Exception {
        var passenger=user(false);var source=spentDebt(passenger);
        var opened=send("POST","/api/v1/wallet/debt/checkout",Map.of(),passenger);UUID id=UUID.fromString(JSON.readTree(opened.body()).path("id").asText());
        String order=sql.queryForObject("SELECT order_id FROM passenger_debt_payments WHERE id=?",String.class,id);
        state(order,"processed","accredited");assertEquals(200,webhook(order,"forged","forged",true).statusCode());
        externalRefund(order,source.cents()/2);for(int n=0;n<3;n++)assertEquals(200,webhook(order,"forged","forged",true).statusCode());
        assertEquals(source.cents()/2,app.getBean(PassengerDebtService.class).summary(passenger.id()).dueCents());
        ((ObjectNode)ORDERS.get(order).path("transactions")).remove("refunds");state(order,"processed","accredited");
        assertEquals(200,webhook(order,"forged","forged",true).statusCode());
        assertEquals(source.cents()/2,app.getBean(PassengerDebtService.class).summary(passenger.id()).dueCents());
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM ledger_transactions WHERE event_key LIKE ?",Integer.class,"debt-payment:"+id+":reversed:%"));
        assertEquals(0,app.getBean(LedgerService.class).reconciliation().difference());
    }
    @Test void reimbursedChargebackRemovesOnlyItsDebtAndReturnsAlreadyRegularizedMoneyAsCredit() throws Exception {
        for(boolean repaid:List.of(false,true)) {
            var passenger=user(false);var source=credit(passenger,"card");UUID consumed=payment(passenger);
            assertEquals(200,send("POST","/api/v1/payments/"+consumed+"/checkout",Map.of("method","pix","useCredit",true),passenger).statusCode());
            state(source.order(),"charged_back","settled");assertEquals(200,webhook(source.order(),"forged","forged",true).statusCode());
            if(repaid) {
                var opened=send("POST","/api/v1/wallet/debt/checkout",Map.of(),passenger);UUID id=UUID.fromString(JSON.readTree(opened.body()).path("id").asText());
                String order=sql.queryForObject("SELECT order_id FROM passenger_debt_payments WHERE id=?",String.class,id);
                state(order,"processed","accredited");assertEquals(200,webhook(order,"forged","forged",true).statusCode());
            }
            state(source.order(),"charged_back","reimbursed");for(int n=0;n<2;n++)assertEquals(200,webhook(source.order(),"forged","forged",true).statusCode());
            assertEquals(0,app.getBean(PassengerDebtService.class).summary(passenger.id()).dueCents());
            assertEquals(repaid?source.cents():0,app.getBean(LedgerService.class).wallet(passenger.id()).credit());
            assertEquals(1,sql.queryForObject("SELECT count(*) FROM passenger_debt_releases WHERE payment_id=?",Integer.class,source.payment()));
            assertEquals(0,app.getBean(LedgerService.class).reconciliation().difference());
        }
    }
    @Test void unknownExternalRefundRemainsHeldWhenALaterGetOmitsItsHistory() throws Exception {
        var passenger=user(false);var source=credit(passenger);var external=ORDERS.get(source.order());String reference=externalRefund(source.order(),source.cents()/2);
        assertEquals(200,webhook(source.order(),"forged","forged",true).statusCode());
        assertEquals(200,webhook(source.order(),"forged","forged",true).statusCode());
        ((ObjectNode)external.path("transactions")).remove("refunds");external.put("version",external.path("version").asInt()+1);
        assertEquals(200,webhook(source.order(),"forged","forged",true).statusCode());
        assertTrue(sql.queryForObject("SELECT untracked_refunds FROM mercado_pago_orders WHERE payment_id=?",Boolean.class,source.payment()));
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM mercado_pago_observed_refunds WHERE payment_id=?",Integer.class,source.payment()));
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM audit_logs WHERE resource_id=? AND action='mercadopago_untracked_refund'",Integer.class,source.payment()));
        var refused=send("POST","/api/v1/transfers",Map.of("type","refund","cents",1),passenger);assertEquals(409,refused.statusCode());assertEquals("REFUND_REQUIRES_REVIEW",JSON.readTree(refused.body()).path("code").asText());
        UUID next=payment(passenger);assertEquals(409,send("POST","/api/v1/payments/"+next+"/checkout",Map.of("method","pix","useCredit",true),passenger).statusCode());
        assertEquals(source.cents(),app.getBean(LedgerService.class).wallet(passenger.id()).credit());assertEquals(0,app.getBean(LedgerService.class).wallet(passenger.id()).returned());
        assertEquals(0,sql.queryForObject("SELECT count(*) FROM transfers WHERE user_id=?",Integer.class,passenger.id()));
        assertEquals(0,app.getBean(LedgerService.class).reconciliation().difference());
        assertThrows(RuntimeException.class,()->sql.update("UPDATE mercado_pago_observed_refunds SET amount_cents=amount_cents+1 WHERE provider_reference=?",reference));
        assertEquals(401,send("GET","/api/v1/wallet/refund-review",null,null).statusCode());
        var review=send("GET","/api/v1/wallet/refund-review",null,passenger);assertEquals(200,review.statusCode(),review.body());assertTrue(JSON.readTree(review.body()).path("creditUnderReview").asBoolean());
        assertFalse(JSON.readTree(send("GET","/api/v1/wallet/refund-review",null,user(false)).body()).path("creditUnderReview").asBoolean());
        sql.update("UPDATE users SET account_status='suspended' WHERE id=?",passenger.id());assertEquals(200,send("GET","/api/v1/wallet/refund-review",null,passenger).statusCode());
    }
    @Test void verifiedChargebackHoldsCreditEvenAfterRefundOrLaterApprovalWithoutRewritingTheReceipt() throws Exception {
        for(boolean alreadyRefunded:List.of(false,true)) {
            var passenger=user(false);var source=credit(passenger,"card");
            if(alreadyRefunded) {
                UUID transfer=refund(passenger,source.cents(),UUID.randomUUID().toString());app.getBean(TransferService.class).submit(transfer);
                credit(passenger,"card");
            }
            var ledger=app.getBean(LedgerService.class);long balance=ledger.wallet(passenger.id()).credit();UUID next=payment(passenger);
            state(source.order(),"charged_back","in_process");
            assertEquals(401,webhook(source.order(),"approved","forged",false).statusCode());
            assertFalse(JSON.readTree(send("GET","/api/v1/wallet/refund-review",null,passenger).body()).path("creditUnderReview").asBoolean());
            assertEquals(200,webhook(source.order(),"approved","forged",true).statusCode());
            assertTrue(JSON.readTree(send("GET","/api/v1/wallet/refund-review",null,passenger).body()).path("creditUnderReview").asBoolean());
            assertEquals("approved",sql.queryForObject("SELECT status FROM payments WHERE id=?",String.class,source.payment()));
            assertEquals(balance,ledger.wallet(passenger.id()).credit());
            var refused=send("POST","/api/v1/transfers",Map.of("type","refund","cents",1),passenger);assertEquals(409,refused.statusCode(),refused.body());assertEquals("REFUND_REQUIRES_REVIEW",JSON.readTree(refused.body()).path("code").asText());
            var use=send("POST","/api/v1/payments/"+next+"/checkout",Map.of("method","pix","useCredit",true),passenger);assertEquals(409,use.statusCode(),use.body());assertEquals("REFUND_REQUIRES_REVIEW",JSON.readTree(use.body()).path("code").asText());
            for(String detail:List.of("in_process","settled","reimbursed")) {
                state(source.order(),"charged_back",detail);assertEquals(200,webhook(source.order(),"approved","forged",true).statusCode());
            }
            state(source.order(),"processed","accredited");assertEquals(200,webhook(source.order(),"approved","forged",true).statusCode());
            assertTrue(JSON.readTree(send("GET","/api/v1/wallet/refund-review",null,passenger).body()).path("creditUnderReview").asBoolean());
            assertFalse(JSON.readTree(send("GET","/api/v1/wallet/refund-review",null,user(false)).body()).path("creditUnderReview").asBoolean());
            assertEquals(1,sql.queryForObject("SELECT count(*) FROM audit_logs WHERE action='mercadopago_chargeback_review' AND resource_id=?",Integer.class,source.payment()));
            assertEquals(1,sql.queryForObject("SELECT count(*) FROM ledger_transactions WHERE payment_id=? AND kind='receipt'",Integer.class,source.payment()));
            assertEquals(balance,ledger.wallet(passenger.id()).credit());assertEquals(0,ledger.reconciliation().difference());
            assertEquals(200,send("POST","/api/v1/payments/"+next+"/checkout",Map.of("method","pix","useCredit",false),passenger).statusCode());
            assertEquals(0,sql.queryForObject("SELECT credit_cents FROM payments WHERE id=?",Integer.class,next));
        }
    }
    @Test void chargebackPausesAnUnsentRefundButPreservesRecoveryOfAnAlreadyUncertainSend() throws Exception {
        for(boolean sent:List.of(false,true)) {
            var passenger=user(false);var source=credit(passenger,"card");UUID transfer=refund(passenger,source.cents(),UUID.randomUUID().toString());
            UUID command=sql.queryForObject("SELECT id FROM mercado_pago_refunds WHERE transfer_id=?",UUID.class,transfer);
            if(sent) {LOSE_REFUND_RESPONSE.set(true);assertThrows(com.unicarona.shared.ApiException.class,()->app.getBean(TransferService.class).submit(transfer));}
            state(source.order(),"charged_back","in_process");assertEquals(200,webhook(source.order(),"approved","forged",true).statusCode());
            if(!sent) {
                assertEquals("REFUND_PROCESSING",assertThrows(com.unicarona.shared.ApiException.class,()->app.getBean(TransferService.class).submit(transfer)).code());
                assertFalse(REFUND_COMMANDS.containsKey(command.toString()));assertEquals(0,sql.queryForObject("SELECT attempts FROM mercado_pago_refunds WHERE id=?",Integer.class,command));
                assertEquals("pending",sql.queryForObject("SELECT status FROM transfers WHERE id=?",String.class,transfer));
                assertEquals(source.cents(),app.getBean(LedgerService.class).wallet(passenger.id()).processing());
                assertEquals(0,app.getBean(LedgerService.class).wallet(passenger.id()).returned());
            } else {
                app.getBean(TransferService.class).submit(transfer);
                assertEquals("approved",sql.queryForObject("SELECT status FROM transfers WHERE id=?",String.class,transfer));
                assertEquals(1,ORDERS.get(source.order()).path("transactions").path("refunds").size());
                assertEquals(source.cents(),app.getBean(LedgerService.class).wallet(passenger.id()).returned());
            }
            assertTrue(JSON.readTree(send("GET","/api/v1/wallet/refund-review",null,passenger).body()).path("creditUnderReview").asBoolean());
            assertEquals(0,app.getBean(LedgerService.class).reconciliation().difference());
        }
    }
    @Test void settledRefundCannotBeDowngradedByANonterminalObservation() throws Exception {
        var passenger=user(false);var source=credit(passenger);UUID transfer=refund(passenger,source.cents(),UUID.randomUUID().toString());app.getBean(TransferService.class).submit(transfer);
        var external=ORDERS.get(source.order());((ObjectNode)external.path("transactions").path("refunds").path(0)).put("status","processing");external.put("version",external.path("version").asInt()+1);
        assertEquals(200,webhook(source.order(),"forged","forged",true).statusCode());
        assertEquals("approved",sql.queryForObject("SELECT status FROM transfers WHERE id=?",String.class,transfer));
        assertEquals("processed",sql.queryForObject("SELECT status FROM mercado_pago_observed_refunds WHERE payment_id=?",String.class,source.payment()));
        assertEquals(source.cents(),app.getBean(LedgerService.class).wallet(passenger.id()).returned());assertEquals(0,app.getBean(LedgerService.class).wallet(passenger.id()).credit());
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM ledger_transactions WHERE event_key=?",Integer.class,"transfer:"+transfer+":completed"));
    }
    private Session adminSession() throws Exception {var account=user(false);sql.update("UPDATE users SET role='admin' WHERE id=?",account.id());return session(account.id());}
    @Test void financialReviewAdministrationRequiresAdminAndShowsOnlySafeVerifiedMetadata() throws Exception {
        var passenger=user(false);var source=credit(passenger);String reference=externalRefund(source.order(),source.cents()/2);
        assertEquals(200,webhook(source.order(),"forged","forged",true).statusCode());var admin=adminSession();String route="/api/v1/admin/payment-reviews";
        assertEquals(401,send("GET",route,null,null).statusCode());assertEquals(403,send("GET",route,null,passenger).statusCode());
        assertEquals(403,send("GET",route+"/"+source.payment(),null,passenger).statusCode());
        assertEquals(403,send("POST",route+"/"+source.payment()+"/recheck",Map.of("reason","Synthetic review"),passenger).statusCode());
        var page=send("GET",route+"?limit=100",null,admin);assertEquals(200,page.statusCode(),page.body());
        boolean found=false;for(var item:JSON.readTree(page.body()).path("items"))if(source.payment().toString().equals(item.path("paymentId").asText())){found=true;assertTrue(item.path("untrackedRefunds").asBoolean());}assertTrue(found);
        assertEquals(422,send("GET",route+"?status=approved",null,admin).statusCode());assertEquals(422,send("GET",route+"?limit=1&limit=2",null,admin).statusCode());
        var detail=send("GET",route+"/"+source.payment(),null,admin);assertEquals(200,detail.statusCode(),detail.body());var data=JSON.readTree(detail.body());
        assertEquals(source.order(),data.path("payment").path("orderReference").asText());assertEquals(passenger.id().toString(),data.path("payment").path("passengerId").asText());
        assertEquals(source.cents(),data.path("wallet").path("credit").asLong());assertEquals(reference,data.path("refunds").path(0).path("reference").asText());assertFalse(data.path("refunds").path(0).path("linked").asBoolean());
        for(String forbidden:List.of(SECRET,PASSWORD,"synthetic-not-a-real-token","payload_ciphertext","identificationNumber","qr_base64","qrCode")){assertFalse(detail.body().contains(forbidden));assertFalse(page.body().contains(forbidden));}
        assertEquals(404,send("GET",route+"/"+UUID.randomUUID(),null,admin).statusCode());
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM ledger_transactions WHERE payment_id=? AND kind='receipt'",Integer.class,source.payment()));
    }
    @Test void administrativeRecheckKeepsFinancialHoldsAndAuditsRetriesWithoutDuplicatingReceipts() throws Exception {
        var passenger=user(false);var source=credit(passenger,"card");state(source.order(),"charged_back","in_process");assertEquals(200,webhook(source.order(),"forged","forged",true).statusCode());
        var admin=adminSession();String route="/api/v1/admin/payment-reviews/"+source.payment()+"/recheck",key=UUID.randomUUID().toString();var input=Map.of("reason","Conferir contestação verificada");
        assertEquals(403,send("POST",route,input,new Session(admin.id(),admin.cookie(),"synthetic-wrong-csrf")).statusCode());
        assertEquals(422,send("POST",route,Map.of("reason","Conferir caso","status","approved"),admin).statusCode());
        assertEquals(422,send("POST",route,Map.of("reason","  "),admin).statusCode());
        FAIL_GET.add(source.order());var failure=send("POST",route,input,admin,key);assertEquals(503,failure.statusCode(),failure.body());assertFalse(failure.body().contains("synthetic_sensitive_upstream_message"));FAIL_GET.remove(source.order());
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM audit_logs WHERE resource_id=? AND action='mercadopago_review_requested'",Integer.class,source.payment()));
        assertEquals(0,sql.queryForObject("SELECT count(*) FROM audit_logs WHERE resource_id=? AND action='mercadopago_review_checked'",Integer.class,source.payment()));
        for(int n=0;n<2;n++){var checked=send("POST",route,input,admin,key);assertEquals(200,checked.statusCode(),checked.body());assertTrue(JSON.readTree(checked.body()).path("checked").asBoolean());}
        assertEquals(409,send("POST",route,Map.of("reason","Outra justificativa"),admin,key).statusCode());
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM audit_logs WHERE resource_id=? AND action='mercadopago_review_requested'",Integer.class,source.payment()));
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM audit_logs WHERE resource_id=? AND action='mercadopago_review_checked'",Integer.class,source.payment()));
        assertTrue(sql.queryForObject("SELECT chargeback_review FROM mercado_pago_orders WHERE payment_id=?",Boolean.class,source.payment()));
        assertEquals(source.cents(),app.getBean(LedgerService.class).wallet(passenger.id()).credit());
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM ledger_transactions WHERE payment_id=? AND kind='receipt'",Integer.class,source.payment()));
        assertEquals(0,app.getBean(LedgerService.class).reconciliation().difference());
    }
    @Test void databaseRejectsRefundCompletionWithoutVerifiedExternalOperations() throws Exception {
        var passenger=user(false);var source=credit(passenger);UUID transfer=refund(passenger,source.cents(),UUID.randomUUID().toString());
        var tx=new TransactionTemplate(app.getBean(PlatformTransactionManager.class));
        assertThrows(RuntimeException.class,()->tx.executeWithoutResult(status->{
            app.getBean(LedgerService.class).post(LedgerCommand.transfer("transfer:"+transfer+":completed","refund","Synthetic forged completion",List.of(new Posting(passenger.id(),AccountKind.REFUND_PENDING,-source.cents()),new Posting(passenger.id(),AccountKind.REFUNDED,source.cents()))));
            sql.update("UPDATE transfers SET status='approved' WHERE id=?",transfer);
        }));
        assertEquals("pending",sql.queryForObject("SELECT status FROM transfers WHERE id=?",String.class,transfer));assertEquals(0,app.getBean(LedgerService.class).wallet(passenger.id()).returned());assertEquals(source.cents(),app.getBean(LedgerService.class).wallet(passenger.id()).processing());
    }
    private JsonNode resolution(Session admin,UUID payment) throws Exception {var response=send("GET","/api/v1/admin/payment-reviews/"+payment,null,admin);assertEquals(200,response.statusCode(),response.body());return JSON.readTree(response.body()).path("resolution");}
    private Map<String,Object> resolutionInput(JsonNode preview) {return Map.of("reason","Synthetic verified financial resolution","evidenceKey",preview.path("evidenceKey").asText());}
    /** Isolated test metadata: emulate every Map.of order that a previous JVM could have persisted. */
    private void legacyIdempotencyHash(Session actor,String key,String scope,Map<String,Object> body) throws Exception {
        String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(body)));
        assertEquals(1,sql.update("UPDATE idempotency_keys SET body_hash=? WHERE actor_id=? AND key=? AND scope=?",hash,actor.id(),key,scope));
    }
    @Test void cancelledCreditResolutionUsesVerifiedAmountAndIsImmutableAcrossReplayAndProviderFailure() throws Exception {
        var passenger=user(false);var source=credit(passenger);var admin=adminSession();externalRefund(source.order(),source.cents()/2);
        assertEquals(200,webhook(source.order(),"forged","forged",true).statusCode());var preview=resolution(admin,source.payment());
        assertTrue(preview.path("available").asBoolean(),preview.toString());assertEquals(source.cents()/2,preview.path("heldCreditCents").asLong());
        String route="/api/v1/admin/payment-reviews/"+source.payment()+"/resolve",key=UUID.randomUUID().toString();var body=resolutionInput(preview);
        assertEquals(401,send("POST",route,body,null).statusCode());assertEquals(403,send("POST",route,body,passenger).statusCode());
        var missingOrigin=HttpRequest.newBuilder(URI.create(server+route)).header("Cookie",admin.cookie()).header("x-csrf-token",admin.csrf()).header("Content-Type","application/json").header("idempotency-key",UUID.randomUUID().toString()).POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build();
        assertEquals(403,http.send(missingOrigin,HttpResponse.BodyHandlers.ofString()).statusCode());
        var missingCsrf=HttpRequest.newBuilder(URI.create(server+route)).header("Origin",AuthTestSettings.ORIGIN).header("Cookie",admin.cookie()).header("Content-Type","application/json").header("idempotency-key",UUID.randomUUID().toString()).POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build();
        assertEquals(403,http.send(missingCsrf,HttpResponse.BodyHandlers.ofString()).statusCode());
        var tampered=new HashMap<>(body);tampered.put("amountCents",1);assertEquals(422,send("POST",route,tampered,admin).statusCode());
        FAIL_GET.add(source.order());assertEquals(503,send("POST",route,body,admin,key).statusCode());assertEquals(source.cents(),app.getBean(LedgerService.class).wallet(passenger.id()).credit());
        FAIL_GET.remove(source.order());var resolved=send("POST",route,body,admin,key);assertEquals(200,resolved.statusCode(),resolved.body());
        FAIL_GET.add(source.order());assertEquals(resolved.body(),send("POST",route,body,admin,key).body());
        var fields=List.of("paymentId","reason","evidenceKey");var values=Map.of("paymentId",source.payment(),"reason",body.get("reason"),"evidenceKey",body.get("evidenceKey"));
        for(int first=0;first<3;first++)for(int second=0;second<3;second++)if(first!=second){int third=3-first-second;var legacy=new LinkedHashMap<String,Object>();for(int position:List.of(first,second,third))legacy.put(fields.get(position),values.get(fields.get(position)));legacyIdempotencyHash(admin,key,"mercadopago:review:resolve-request",legacy);assertEquals(resolved.body(),send("POST",route,body,admin,key).body());}
        assertEquals(409,send("POST",route,Map.of("reason","Different synthetic reason","evidenceKey",body.get("evidenceKey")),admin,key).statusCode());FAIL_GET.remove(source.order());
        assertEquals(source.cents()-source.cents()/2,app.getBean(LedgerService.class).wallet(passenger.id()).credit());assertEquals(0,app.getBean(PassengerDebtService.class).summary(passenger.id()).heldCreditCents());
        assertFalse(sql.queryForObject("SELECT untracked_refunds FROM mercado_pago_orders WHERE payment_id=?",Boolean.class,source.payment()));
        for(int i=0;i<3;i++)assertEquals(200,webhook(source.order(),"forged","forged",true).statusCode());
        assertFalse(sql.queryForObject("SELECT untracked_refunds FROM mercado_pago_orders WHERE payment_id=?",Boolean.class,source.payment()));
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM mercado_pago_review_resolutions WHERE payment_id=?",Integer.class,source.payment()));
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM audit_logs WHERE resource_id=? AND action='mercadopago_review_resolved'",Integer.class,source.payment()));
        assertThrows(RuntimeException.class,()->sql.update("UPDATE mercado_pago_review_resolutions SET held_credit_cents=0 WHERE payment_id=?",source.payment()));
        assertThrows(RuntimeException.class,()->sql.update("DELETE FROM mercado_pago_resolved_refunds WHERE resolution_id=(SELECT id FROM mercado_pago_review_resolutions WHERE payment_id=?)",source.payment()));
        assertEquals(0,app.getBean(LedgerService.class).reconciliation().difference());
    }
    @Test void resolvingSpentCancelledCreditDoesNotWaiveDebtOrRewriteReservations() throws Exception {
        var passenger=user(false);var source=spentDebt(passenger);var admin=adminSession();var preview=resolution(admin,source.payment());
        assertTrue(preview.path("available").asBoolean(),preview.toString());assertEquals(source.cents(),preview.path("registeredDebtCents").asLong());assertEquals(0,preview.path("heldCreditCents").asLong());
        var result=send("POST","/api/v1/admin/payment-reviews/"+source.payment()+"/resolve",resolutionInput(preview),admin);assertEquals(200,result.statusCode(),result.body());
        assertEquals(source.cents(),app.getBean(PassengerDebtService.class).summary(passenger.id()).dueCents());
        assertEquals(409,send("POST","/api/v1/payments/"+payment(passenger)+"/checkout",Map.of("method","pix","useCredit",false),passenger).statusCode());
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM ledger_transactions WHERE payment_id=? AND kind='receipt'",Integer.class,source.payment()));assertEquals(0,app.getBean(LedgerService.class).reconciliation().difference());
    }
    @Test void resolutionRefusesChangedOrMissingEvidenceBeforeAnyCreditAdjustment() throws Exception {
        var passenger=user(false);var source=credit(passenger);var admin=adminSession();externalRefund(source.order(),source.cents()/2);assertEquals(200,webhook(source.order(),"forged","forged",true).statusCode());
        var original=resolution(admin,source.payment());externalRefund(source.order(),1);
        var changed=send("POST","/api/v1/admin/payment-reviews/"+source.payment()+"/resolve",resolutionInput(original),admin);assertEquals(409,changed.statusCode(),changed.body());assertEquals("REVIEW_CHANGED",JSON.readTree(changed.body()).path("code").asText());
        assertEquals(0,sql.queryForObject("SELECT count(*) FROM mercado_pago_review_resolutions WHERE payment_id=?",Integer.class,source.payment()));assertEquals(source.cents(),app.getBean(LedgerService.class).wallet(passenger.id()).credit());
        ((ObjectNode)ORDERS.get(source.order()).path("transactions")).remove("refunds");ORDERS.get(source.order()).put("version",ORDERS.get(source.order()).path("version").asInt()+1);assertEquals(200,webhook(source.order(),"forged","forged",true).statusCode());
        var missing=resolution(admin,source.payment());assertFalse(missing.path("available").asBoolean());
        assertEquals(409,send("POST","/api/v1/admin/payment-reviews/"+source.payment()+"/resolve",resolutionInput(missing),admin).statusCode());assertEquals(source.cents(),app.getBean(LedgerService.class).wallet(passenger.id()).credit());
    }
    @Test void chargebackResolutionAndReimbursementRequireMatchingTransactionEvidence() throws Exception {
        var passenger=user(false);var source=credit(passenger,"card");var admin=adminSession();state(source.order(),"charged_back","settled");
        var transaction=(ObjectNode)ORDERS.get(source.order()).path("transactions").path("payments").path(0);String transactionId=transaction.path("id").asText();transaction.remove("id");
        assertEquals(502,webhook(source.order(),"forged","forged",true).statusCode());assertEquals(0,sql.queryForObject("SELECT count(*) FROM passenger_debt_assessments WHERE payment_id=?",Integer.class,source.payment()));transaction.put("id",transactionId);
        ((ObjectNode)ORDERS.get(source.order()).path("transactions").path("payments").path(0)).put("status","processed").put("status_detail","accredited");
        assertEquals(200,webhook(source.order(),"forged","forged",true).statusCode());assertEquals(0,sql.queryForObject("SELECT count(*) FROM passenger_debt_assessments WHERE payment_id=?",Integer.class,source.payment()));assertFalse(resolution(admin,source.payment()).path("available").asBoolean());
        state(source.order(),"charged_back","settled");assertEquals(200,webhook(source.order(),"forged","forged",true).statusCode());var preview=resolution(admin,source.payment());assertTrue(preview.path("available").asBoolean(),preview.toString());
        assertEquals(200,send("POST","/api/v1/admin/payment-reviews/"+source.payment()+"/resolve",resolutionInput(preview),admin).statusCode());assertEquals(0,app.getBean(LedgerService.class).wallet(passenger.id()).credit());
        assertEquals(200,webhook(source.order(),"forged","forged",true).statusCode());assertFalse(sql.queryForObject("SELECT chargeback_review FROM mercado_pago_orders WHERE payment_id=?",Boolean.class,source.payment()));
        state(source.order(),"charged_back","reimbursed");((ObjectNode)ORDERS.get(source.order()).path("transactions").path("payments").path(0)).put("status_detail","in_process");
        assertEquals(200,webhook(source.order(),"forged","forged",true).statusCode());assertEquals(0,app.getBean(LedgerService.class).wallet(passenger.id()).credit());
        state(source.order(),"charged_back","reimbursed");for(int n=0;n<2;n++)assertEquals(200,webhook(source.order(),"forged","forged",true).statusCode());
        assertEquals(source.cents(),app.getBean(LedgerService.class).wallet(passenger.id()).credit());assertEquals(0,app.getBean(PassengerDebtService.class).summary(passenger.id()).heldCreditCents());
        var reimbursed=resolution(admin,source.payment());assertTrue(reimbursed.path("available").asBoolean(),reimbursed.toString());assertEquals(200,send("POST","/api/v1/admin/payment-reviews/"+source.payment()+"/resolve",resolutionInput(reimbursed),admin).statusCode());
        assertFalse(sql.queryForObject("SELECT chargeback_review FROM mercado_pago_orders WHERE payment_id=?",Boolean.class,source.payment()));assertEquals(0,app.getBean(LedgerService.class).reconciliation().difference());
    }
    @Test void consumedRideChargebackRemainsAnAdministrativeDecisionAndPreservesDriverBalances() throws Exception {
        var passenger=user(false);UUID id=payment(passenger);String order=checkout(passenger,id);state(order,"processed","accredited");assertEquals(200,webhook(order,"forged","forged",true).statusCode());
        var leg=sql.queryForMap("SELECT id,ride_id,driver_id FROM ride_reservations WHERE payment_id=?",id);String reservation=leg.get("id").toString(),ride=leg.get("ride_id").toString();var driver=session((UUID)leg.get("driver_id"));RideFixtureClock.today(sql,(UUID)leg.get("ride_id"));
        var detail=send("GET","/api/v1/reservations/"+reservation,null,passenger);assertEquals(200,detail.statusCode(),detail.body());String pin=JSON.readTree(detail.body()).path("pin").asText();
        assertEquals(200,send("POST","/api/v1/reservations/"+reservation+"/board",Map.of("pin",pin),driver).statusCode());assertEquals(200,send("POST","/api/v1/rides/"+ride+"/start",Map.of(),driver).statusCode());
        assertEquals(200,send("POST","/api/v1/rides/"+ride+"/finish",Map.of("availability",true),driver).statusCode());assertEquals(200,send("POST","/api/v1/reservations/"+reservation+"/final",Map.of(),passenger).statusCode());
        var admin=adminSession();long driverBefore=sql.queryForObject("SELECT coalesce(sum(e.cents),0)::bigint FROM ledger_entries e JOIN ledger_accounts a ON a.id=e.account_id WHERE a.kind='driver_available'",Long.class);
        assertTrue(app.getBean(LedgerService.class).wallet(driver.id()).available()>0);
        state(order,"charged_back","settled");assertEquals(200,webhook(order,"forged","forged",true).statusCode());var preview=resolution(admin,id);assertFalse(preview.path("available").asBoolean());
        assertEquals(409,send("POST","/api/v1/admin/payment-reviews/"+id+"/resolve",resolutionInput(preview),admin).statusCode());
        assertEquals(0,app.getBean(PassengerDebtService.class).summary(passenger.id()).dueCents());assertEquals(1,sql.queryForObject("SELECT count(*) FROM ride_reservations WHERE payment_id=?",Integer.class,id));
        assertEquals(driverBefore,sql.queryForObject("SELECT coalesce(sum(e.cents),0)::bigint FROM ledger_entries e JOIN ledger_accounts a ON a.id=e.account_id WHERE a.kind='driver_available'",Long.class));assertEquals(0,app.getBean(LedgerService.class).reconciliation().difference());
    }
    @Test void refundedLifecycleRejectsForeignTransactionBeforeDisputeEffectsOrReviewEvidence() throws Exception {
        var passenger=user(false);var source=credit(passenger,"card");UUID transfer=refund(passenger,source.cents(),UUID.randomUUID().toString());app.getBean(TransferService.class).submit(transfer);
        assertEquals("REFUNDED",sql.queryForObject("SELECT state FROM mercado_pago_orders WHERE payment_id=?",String.class,source.payment()));
        state(source.order(),"charged_back","settled");((ObjectNode)ORDERS.get(source.order()).path("transactions").path("payments").path(0)).put("id","PAY"+UUID.randomUUID().toString().replace("-",""));
        assertEquals(422,webhook(source.order(),"forged","forged",true).statusCode());assertFalse(sql.queryForObject("SELECT chargeback_review FROM mercado_pago_orders WHERE payment_id=?",Boolean.class,source.payment()));
        assertEquals(0,sql.queryForObject("SELECT count(*) FROM passenger_debt_assessments WHERE payment_id=?",Integer.class,source.payment()));assertEquals(0,app.getBean(LedgerService.class).reconciliation().difference());
    }
    @Test void aNewExternalRefundReopensOnlyItsUnreconciledAmount() throws Exception {
        var passenger=user(false);var source=credit(passenger);var admin=adminSession();long adjusted=0;
        for(int part:List.of(source.cents()/2,source.cents()-source.cents()/2)) {
            externalRefund(source.order(),part);assertEquals(200,webhook(source.order(),"forged","forged",true).statusCode());
            var preview=resolution(admin,source.payment());assertTrue(preview.path("available").asBoolean(),preview.toString());assertEquals(part,preview.path("heldCreditCents").asLong());
            var done=send("POST","/api/v1/admin/payment-reviews/"+source.payment()+"/resolve",resolutionInput(preview),admin);assertEquals(200,done.statusCode(),done.body());adjusted+=part;
            for(int duplicate=0;duplicate<2;duplicate++)assertEquals(200,webhook(source.order(),"forged","forged",true).statusCode());
            assertEquals(source.cents()-adjusted,app.getBean(LedgerService.class).wallet(passenger.id()).credit());assertFalse(sql.queryForObject("SELECT untracked_refunds FROM mercado_pago_orders WHERE payment_id=?",Boolean.class,source.payment()));
        }
        assertEquals(2,sql.queryForObject("SELECT count(*) FROM mercado_pago_review_resolutions WHERE payment_id=?",Integer.class,source.payment()));assertEquals(2,sql.queryForObject("SELECT count(*) FROM mercado_pago_resolved_refunds WHERE resolution_id IN (SELECT id FROM mercado_pago_review_resolutions WHERE payment_id=?)",Integer.class,source.payment()));assertEquals(0,app.getBean(PassengerDebtService.class).summary(passenger.id()).heldCreditCents());assertEquals(0,app.getBean(LedgerService.class).reconciliation().difference());
    }
    @Test void repaymentChargebackNeedsMatchingTransactionEvidenceToReopenDebt() throws Exception {
        var passenger=user(false);var source=spentDebt(passenger);var created=send("POST","/api/v1/wallet/debt/checkout",Map.of(),passenger);assertEquals(200,created.statusCode(),created.body());UUID repayment=UUID.fromString(JSON.readTree(created.body()).path("id").asText());String order=sql.queryForObject("SELECT order_id FROM passenger_debt_payments WHERE id=?",String.class,repayment);
        state(order,"processed","accredited");assertEquals(200,webhook(order,"forged","forged",true).statusCode());assertEquals(0,app.getBean(PassengerDebtService.class).summary(passenger.id()).dueCents());
        state(order,"charged_back","settled");((ObjectNode)ORDERS.get(order).path("transactions").path("payments").path(0)).put("status_detail","in_process");assertEquals(200,webhook(order,"forged","forged",true).statusCode());
        assertEquals(0,app.getBean(PassengerDebtService.class).summary(passenger.id()).dueCents());
        state(order,"charged_back","settled");for(int n=0;n<2;n++)assertEquals(200,webhook(order,"forged","forged",true).statusCode());
        assertEquals(source.cents(),app.getBean(PassengerDebtService.class).summary(passenger.id()).dueCents());assertEquals(0,app.getBean(LedgerService.class).reconciliation().difference());
    }
    @Test void simultaneousAdministrativeResolutionsCannotAdjustTheSameCreditTwice() throws Exception {
        var passenger=user(false);var source=credit(passenger);var firstAdmin=adminSession();var secondAdmin=adminSession();externalRefund(source.order(),source.cents()/2);assertEquals(200,webhook(source.order(),"forged","forged",true).statusCode());
        var body=resolutionInput(resolution(firstAdmin,source.payment()));String route="/api/v1/admin/payment-reviews/"+source.payment()+"/resolve";
        var gate=new java.util.concurrent.CountDownLatch(1);try(var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var a=pool.submit(()->{gate.await();return send("POST",route,body,firstAdmin);});var b=pool.submit(()->{gate.await();return send("POST",route,body,secondAdmin);});gate.countDown();
            var outcomes=List.of(a.get(15,java.util.concurrent.TimeUnit.SECONDS),b.get(15,java.util.concurrent.TimeUnit.SECONDS));assertEquals(1,outcomes.stream().filter(r->r.statusCode()==200).count(),outcomes.toString());assertEquals(1,outcomes.stream().filter(r->r.statusCode()==409).count(),outcomes.toString());
        }
        assertEquals(source.cents()-source.cents()/2,app.getBean(LedgerService.class).wallet(passenger.id()).credit());assertEquals(1,sql.queryForObject("SELECT count(*) FROM mercado_pago_review_resolutions WHERE payment_id=?",Integer.class,source.payment()));assertEquals(0,app.getBean(LedgerService.class).reconciliation().difference());
    }
    /** Browser fixture bridge is in the test classpath only; no public test controls ship. */
    public static void main(String[] ignored) throws Exception {
        var wire=System.out;System.setOut(System.err);
        System.setProperty("mercadopago.e2e.origin",System.getenv().getOrDefault("JAVA_E2E_ORIGIN","http://127.0.0.1:3004"));
        var suite=new MercadoPagoHttpIT();
        try {
            start();var passenger=suite.user(false);UUID payment=suite.payment(passenger);
            String email=sql.queryForObject("SELECT email FROM users WHERE id=?",String.class,passenger.id());
            wire.println(JSON.writeValueAsString(Map.of("ready",true,"origin",server,"email",email,"password",PASSWORD,"payment",payment)));wire.flush();
            try(var input=new java.io.BufferedReader(new java.io.InputStreamReader(System.in,StandardCharsets.UTF_8))) {
                String line;while((line=input.readLine())!=null) {
                    var command=JSON.readTree(line);var response=new LinkedHashMap<String,Object>();response.put("id",command.path("id").asInt());
                    String action=command.path("action").asText();
                    try {
                        Object result=switch(action) {
                            case "close" -> true;
                            case "payment" -> suite.payment(passenger).toString();
                            case "expireReservation" -> {
                                UUID id=UUID.fromString(command.path("payment").asText());
                                sql.update("UPDATE payments SET expires_at=now()-interval '1 minute' WHERE id=?",id);
                                UUID request=sql.queryForObject("SELECT request_id FROM payments WHERE id=?",UUID.class,id);
                                yield app.getBean(com.unicarona.job.service.MaintenanceTransactions.class).request(new com.unicarona.job.repository.MaintenanceRepository.Request(request,passenger.id()));
                            }
                            // Independent browser journeys share one loopback IP in this disposable fixture.
                            // Preserve production limits; reset the fixture bucket between personas/scenarios.
                            case "resetFixtureRateLimit" -> {sql.update("DELETE FROM rate_limits");yield true;}
                            case "adminSession" -> {var admin=suite.adminSession();yield Map.of("email",sql.queryForObject("SELECT email FROM users WHERE id=?",String.class,admin.id()),"password",PASSWORD);}
                            case "credit" -> suite.credit(passenger);
                            case "spentDebt" -> suite.spentDebt(passenger);
                            case "debtState" -> {UUID id=UUID.fromString(command.path("payment").asText());String order=sql.queryForObject("SELECT order_id FROM passenger_debt_payments WHERE id=?",String.class,id);suite.state(order,command.path("status").asText(),command.path("detail").asText());yield suite.webhook(order,"forged","forged",true).statusCode();}
                            case "rejectRefund" -> {REJECT_REFUND_ORDER.add(command.path("order").asText());yield true;}
                            case "externalRefund" -> {UUID id=UUID.fromString(command.path("payment").asText());String order=sql.queryForObject("SELECT order_id FROM mercado_pago_orders WHERE payment_id=?",String.class,id);suite.externalRefund(order,sql.queryForObject("SELECT external_cents FROM payments WHERE id=?",Integer.class,id)/2);yield suite.webhook(order,"forged","forged",true).statusCode();}
                            case "submitRefund" -> {app.getBean(TransferService.class).submit(UUID.fromString(command.path("transfer").asText()));yield true;}
                            case "loseResponse" -> {LOSE_RESPONSE.set(true);yield true;}
                            case "state" -> {UUID id=UUID.fromString(command.path("payment").asText());String order=sql.queryForObject("SELECT order_id FROM mercado_pago_orders WHERE payment_id=?",String.class,id);suite.state(order,command.path("status").asText(),command.path("detail").asText());yield suite.webhook(order,"forged","forged",true).statusCode();}
                            default -> throw new IllegalArgumentException("Unknown fixture action");
                        };response.put("result",result);
                    }catch(Exception error){response.put("error",error instanceof com.unicarona.shared.ApiException api?api.code():"FIXTURE_ERROR");}
                    wire.println(JSON.writeValueAsString(response));wire.flush();if("close".equals(action))break;
                }
            }
        }finally{close();}
    }
}
