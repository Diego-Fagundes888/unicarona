package com.unicarona.privacy;

import com.fasterxml.jackson.databind.*;
import com.unicarona.UniCaronaApplication;
import com.unicarona.privacy.dto.LegalDto;
import com.unicarona.privacy.service.*;
import com.unicarona.security.CryptoService;
import com.unicarona.shared.ApiException;
import com.unicarona.support.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Actual HTTP/PostgreSQL; fixture publication is explicit and does not publish repository drafts in the real application. */
class LegalHttpIT {
    private static RealPostgres db;private static ServletWebServerApplicationContext app;private static JdbcTemplate sql;private static String server,hash;private static Path journal;
    private static LegalDocumentTemplates.Bundle draft,baseline;private static LegalPublicationService publisher;private static TransactionTemplate tx;
    private static final String PASSWORD="Synthetic legal fixture password 2026";
    private final HttpClient http=HttpClient.newHttpClient();private final ObjectMapper json=new ObjectMapper();
    private record Session(UUID id,String cookie,String csrf){@Override public String toString(){return "LegalTestSession[redacted]";}}
    @BeforeAll static void start()throws Exception {
        db=new RealPostgres();db.resetSchema();journal=Files.createTempDirectory("unicarona-legal-journal-");var args=new ArrayList<>(List.of(AuthTestSettings.arguments(db.apiConnectionUrl())));args.removeIf(value->value.startsWith("--LEGAL_"));
        args.addAll(List.of("--LEGAL_CONTROLLER_NAME=Synthetic responsible person","--LEGAL_PRIVACY_EMAIL=privacy@example.test","--LEGAL_CITY=Synthetic city","--LEGAL_STATE=GO","--LEGAL_COMMERCIAL_OPERATION=false","--LEGAL_HOSTING_DESCRIPTION=Synthetic local test infrastructure","--LEGAL_TRANSFER_DESCRIPTION=Synthetic provider review fixture","--PRIVACY_ERASURE_JOURNAL_PATH="+journal));
        app=(ServletWebServerApplicationContext)new SpringApplicationBuilder(UniCaronaApplication.class).run(args.toArray(String[]::new));sql=app.getBean(JdbcTemplate.class);server="http://127.0.0.1:"+app.getWebServer().getPort();hash=app.getBean(CryptoService.class).hashPassword(PASSWORD);tx=new TransactionTemplate(app.getBean(PlatformTransactionManager.class));
        assertEquals(0,sql.queryForObject("SELECT count(*) FROM legal_document_heads",Integer.class));
        assertEquals(503,HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(server+"/api/v1/legal/documents")).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode());
        draft=app.getBean(LegalDocumentTemplates.class).prepare();assertEquals("draft",draft.publicationState());publisher=app.getBean(LegalPublicationService.class);baseline=new LegalDocumentTemplates.Bundle("published",draft.documents());publisher.publish(baseline);
    }
    @AfterAll static void close()throws Exception {if(app!=null)app.close();if(db!=null)db.close();if(journal!=null){assertTrue(journal.getFileName().toString().startsWith("unicarona-legal-journal-"));try(var files=Files.list(journal)){assertEquals(0,files.count());}Files.delete(journal);}}
    @BeforeEach void resetHeads(){sql.update("DELETE FROM rate_limits");for(var doc:baseline.documents())sql.update("UPDATE legal_document_heads SET version=? WHERE slug=?",doc.version(),doc.slug());}
    private HttpResponse<String> send(String method,String path,Object body,Session session,String key)throws Exception {
        var request=HttpRequest.newBuilder(URI.create(server+path)).header("Origin",AuthTestSettings.ORIGIN).header("Content-Type","application/json");if(session!=null)request.header("Cookie",session.cookie()).header("x-csrf-token",session.csrf());if(key!=null)request.header("idempotency-key",key);
        request.method(method,Set.of("GET","HEAD").contains(method)?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));return http.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode tree(HttpResponse<String> response)throws Exception{return json.readTree(response.body());}
    private JsonNode ok(HttpResponse<String> response)throws Exception{assertEquals(200,response.statusCode(),response.body());return tree(response);}
    private Session session()throws Exception {
        String email=UUID.randomUUID()+"@example.test";UUID id=sql.queryForObject("INSERT INTO users(email,password_hash,name,birth_date,email_verified_at) VALUES(?,?,'Synthetic legal user','2000-01-01',now()) RETURNING id",UUID.class,email,hash);
        var result=send("POST","/api/v1/auth/login",Map.of("email",email,"password",PASSWORD),null,null);assertEquals(200,result.statusCode(),result.body());var cookies=new LinkedHashMap<String,String>();for(String header:result.headers().allValues("set-cookie")){var pair=header.split(";",2)[0].split("=",2);cookies.put(pair[0],pair[1]);}return new Session(id,String.join("; ",cookies.entrySet().stream().map(e->e.getKey()+"="+e.getValue()).toList()),cookies.get("uc_csrf"));
    }
    private List<LegalDto.Reference> references(boolean driver)throws Exception {
        var refs=new ArrayList<LegalDto.Reference>();for(var doc:ok(send("GET","/api/v1/legal/documents",null,null,null)).path("documents")){if(driver||!"driver-terms".equals(doc.path("reference").path("slug").asText()))refs.add(json.treeToValue(doc.path("reference"),LegalDto.Reference.class));}return refs;
    }
    private Map<String,Object> input(boolean driver)throws Exception{return Map.of("terms",true,"privacyNotice",true,"driverTerms",driver,"documents",references(driver));}
    private void accept(Session user,boolean driver)throws Exception{ok(send("POST","/api/v1/privacy/legal",input(driver),user,UUID.randomUUID().toString()));}
    private LegalDocumentTemplates.Prepared change(String slug,int revision,boolean material)throws Exception {
        var original=baseline.documents().stream().filter(d->d.slug().equals(slug)).findFirst().orElseThrow();var content=app.getBean(ObjectMapper.class).readTree(original.content());var canonical=new TreeMap<String,String>();canonical.put("title",original.title());canonical.put("body",content.path("body").asText()+"\n\nSynthetic change fixture "+revision+" "+UUID.randomUUID());String body=app.getBean(ObjectMapper.class).writeValueAsString(canonical);String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));return new LegalDocumentTemplates.Prepared(slug,revision,original.title(),original.kind(),original.scopes(),material,"Synthetic change notice",body,digest,"1."+revision+"."+digest.substring(0,16));
    }
    private void publish(LegalDocumentTemplates.Prepared changed){var docs=new ArrayList<>(baseline.documents());docs.removeIf(d->d.slug().equals(changed.slug()));docs.add(changed);publisher.publish(new LegalDocumentTemplates.Bundle("published",docs));}
    @Test void publicDocumentsHaveAllSixActualBodiesVerifiedHashesAndStableArchiveLinks()throws Exception {
        var result=ok(send("GET","/api/v1/legal/documents",null,null,null));assertEquals(6,result.path("documents").size());
        for(var document:result.path("documents")){String body=document.path("body").asText();assertFalse(body.contains("{{"));assertTrue(body.length()>2000);var canonical=new TreeMap<String,String>();canonical.put("body",body);canonical.put("title",document.path("title").asText());String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(app.getBean(ObjectMapper.class).writeValueAsBytes(canonical)));assertEquals(digest,document.path("reference").path("contentSha256").asText());String path="/api/v1/legal/documents/"+document.path("reference").path("slug").asText()+"/"+document.path("reference").path("version").asText();assertEquals(body,ok(send("GET",path,null,null,null)).path("body").asText());}
        assertTrue(ok(send("GET","/api/v1/legal/documents/privacy",null,null,null)).path("body").asText().contains("Synthetic responsible person"));assertEquals(200,send("HEAD","/api/v1/legal/documents",null,null,null).statusCode());
    }
    @Test void publicReaderAndNewPrivacyAssetsAreServedWithoutLoginAndReadingDoesNotCreateAcceptance()throws Exception {
        int receipts=sql.queryForObject("SELECT count(*) FROM legal_acceptances",Integer.class);
        var page=send("GET","/legal.html?document=privacy",null,null,null);
        assertEquals(200,page.statusCode());assertTrue(page.headers().firstValue("content-type").orElseThrow().startsWith("text/html"));
        assertTrue(page.body().contains("data-legal-reader"));assertTrue(page.body().contains("data-legal-navigation"));
        assertTrue(page.body().contains("no-referrer"));assertFalse(page.body().contains("Synthetic responsible person"));
        String policy=page.headers().firstValue("content-security-policy").orElseThrow();String scripts=Arrays.stream(policy.split(";")).map(String::trim).filter(value->value.startsWith("script-src ")).findFirst().orElseThrow();assertTrue(scripts.contains("'self'"));assertFalse(scripts.contains("'unsafe-inline'"));
        for(String asset:List.of("legal.js","privacy.js","privacy.css")){
            var response=send("GET","/"+asset+"?v=privacy-test",null,null,null);assertEquals(200,response.statusCode(),asset);
            assertFalse(response.headers().firstValue("content-type").orElse("").startsWith("text/html"),asset);
            // Compare against packaged source, so a stale frontend fixture cannot pass as this implementation.
            try(var input=LegalHttpIT.class.getResourceAsStream("/static/"+asset)){assertNotNull(input,asset);assertEquals(new String(input.readAllBytes(),StandardCharsets.UTF_8),response.body(),asset);}
        }
        var login=send("GET","/login.html",null,null,null);assertEquals(200,login.statusCode());assertTrue(login.body().indexOf("src=\"legal.js?")<login.body().indexOf("src=\"login.js?"));
        var dashboard=send("GET","/dashboard.html",null,null,null);assertEquals(200,dashboard.statusCode());assertTrue(dashboard.body().indexOf("src=\"privacy.js?")<dashboard.body().indexOf("src=\"backend-ui.js?"));assertEquals("no-store",dashboard.headers().firstValue("cache-control").orElseThrow());
        ok(send("GET","/api/v1/legal/documents/privacy",null,null,null));assertEquals(receipts,sql.queryForObject("SELECT count(*) FROM legal_acceptances",Integer.class));
        assertEquals(401,send("GET","/api/v1/privacy/legal",null,null,null).statusCode());
    }
    @Test void servedHtmlUsesConfiguredPublicContactAndCannotReuseStaleConditionalContext()throws Exception {
        for(String path:List.of("/legal.html","/login.html","/dashboard.html")){
            var request=HttpRequest.newBuilder(URI.create(server+path)).header("If-Modified-Since","Wed, 01 Jan 2098 00:00:00 GMT").header("If-None-Match","*").GET().build();
            var response=http.send(request,HttpResponse.BodyHandlers.ofString());
            assertEquals(200,response.statusCode(),path);
            assertTrue(response.body().contains("<meta name=\"unicarona-privacy-contact\" content=\"privacy@example.test\">"),path);
            assertFalse(response.body().contains("privacy@example.invalid"),path);
            assertEquals("no-store",response.headers().firstValue("cache-control").orElseThrow(),path);
            assertTrue(response.headers().firstValue("etag").isEmpty(),path+" must not serve a static ETag");
            assertTrue(response.headers().firstValue("last-modified").isEmpty(),path+" must not serve a static Last-Modified");
            String csp=response.headers().firstValue("content-security-policy").orElseThrow();
            String scripts=Arrays.stream(csp.split(";")).map(String::trim).filter(v->v.startsWith("script-src ")).findFirst().orElseThrow();
            assertFalse(scripts.contains("'unsafe-inline'"));
            assertEquals(path.equals("/dashboard.html"),response.body().contains("name=\"unicarona-csp-nonce\""));
        }
        assertTrue(send("GET","/legal.html",null,null,null).body().contains("mailto:privacy@example.test"));
    }
    @Test void publicationAndNotificationsNeverInventAcceptanceOrMarketingAndAreNotRepeated()throws Exception {
        var a=session();publisher.publish(baseline);publisher.notifyChanges();publisher.notifyChanges();assertEquals(0,sql.queryForObject("SELECT count(*) FROM legal_acceptances WHERE user_id=?",Integer.class,a.id()));assertEquals(5,sql.queryForObject("SELECT count(*) FROM legal_change_notices WHERE user_id=?",Integer.class,a.id()));assertEquals(5,sql.queryForObject("SELECT count(*) FROM notifications WHERE user_id=?",Integer.class,a.id()));assertEquals(5,ok(send("GET","/api/v1/privacy/legal",null,a,null)).path("required").size());
        assertThrows(IllegalStateException.class,()->publisher.publish(draft));assertFalse(app.getBean(PrivacyRightsService.class).preferences(new com.unicarona.security.Actor(a.id(),"user","active",null,null,null,true,Set.of())).marketing());
    }
    @Test void explicitAcceptStoresVersionsHashesKindSourceAndTimeOnlyForItsOwner()throws Exception {
        var a=session();var b=session();var body=input(false);String key=UUID.randomUUID().toString();var result=send("POST","/api/v1/privacy/legal",body,a,key);var value=ok(result);assertEquals(0,value.path("required").size());assertEquals(1,value.path("driverRequired").size());assertEquals(result.body(),send("POST","/api/v1/privacy/legal",body,a,key).body());assertEquals(5,sql.queryForObject("SELECT count(*) FROM legal_acceptances WHERE user_id=? AND source='terms_update' AND accepted_at IS NOT NULL",Integer.class,a.id()));assertEquals(1,sql.queryForObject("SELECT count(*) FROM legal_acceptances WHERE user_id=? AND kind='privacy_notice'",Integer.class,a.id()));assertEquals(0,ok(send("GET","/api/v1/privacy/legal/history",null,b,null)).path("items").size());assertEquals(0,sql.queryForObject("SELECT count(*) FROM privacy_consent_events WHERE user_id=?",Integer.class,a.id()));
        assertThrows(RuntimeException.class,()->sql.update("UPDATE legal_acceptances SET content_sha256=? WHERE user_id=?","f".repeat(64),a.id()));assertThrows(RuntimeException.class,()->sql.update("DELETE FROM legal_acceptances WHERE user_id=?",a.id()));
    }
    @Test void confirmationsCsrfAndCurrentContentAreMandatoryAndAnotherOwnerCannotBeSelected()throws Exception {
        var a=session();var b=session();var body=new LinkedHashMap<>(input(false));body.put("terms",false);assertEquals(422,send("POST","/api/v1/privacy/legal",body,a,UUID.randomUUID().toString()).statusCode());body=new LinkedHashMap<>(input(false));body.put("privacyNotice",false);assertEquals(422,send("POST","/api/v1/privacy/legal",body,a,UUID.randomUUID().toString()).statusCode());body=new LinkedHashMap<>(input(false));body.put("userId",b.id().toString());assertEquals(422,send("POST","/api/v1/privacy/legal",body,a,UUID.randomUUID().toString()).statusCode());
        assertEquals(403,send("POST","/api/v1/privacy/legal",input(false),new Session(a.id(),a.cookie(),"wrong"),UUID.randomUUID().toString()).statusCode());var refs=new ArrayList<>(references(false));var ref=refs.getFirst();refs.set(0,new LegalDto.Reference(ref.slug(),ref.version(),"f".repeat(64)));body=new LinkedHashMap<>(input(false));body.put("documents",refs);assertEquals(409,send("POST","/api/v1/privacy/legal",body,a,UUID.randomUUID().toString()).statusCode());assertEquals(0,sql.queryForObject("SELECT count(*) FROM legal_acceptances WHERE user_id IN (?,?)",Integer.class,a.id(),b.id()));
    }
    @Test void driverContractNeedsItsOwnExplicitConfirmation()throws Exception {
        var a=session();var body=new LinkedHashMap<>(input(true));body.put("driverTerms",false);assertEquals(422,send("POST","/api/v1/privacy/legal",body,a,UUID.randomUUID().toString()).statusCode());accept(a,true);assertEquals(1,sql.queryForObject("SELECT count(*) FROM legal_acceptances WHERE user_id=? AND kind='driver_contract'",Integer.class,a.id()));tx.executeWithoutResult(status->app.getBean(LegalDocumentService.class).requireCurrent(a.id(),true));
    }
    @Test void materialUpdateRequiresNewAcceptanceButNonmaterialNoticeDoesNotFabricateReadProof()throws Exception {
        var a=session();accept(a,false);var oldPrivacy=baseline.documents().stream().filter(d->d.slug().equals("privacy")).findFirst().orElseThrow();var privacy=change("privacy",oldPrivacy.revision()+1,false);publish(privacy);assertEquals(0,ok(send("GET","/api/v1/privacy/legal",null,a,null)).path("required").size());assertEquals(oldPrivacy.version(),sql.queryForObject("SELECT document_version FROM legal_acceptances WHERE user_id=? AND document_slug='privacy'",String.class,a.id()));
        var terms=change("terms",2,true);publish(terms);assertEquals(1,ok(send("GET","/api/v1/privacy/legal",null,a,null)).path("required").size());var denied=assertThrows(ApiException.class,()->tx.executeWithoutResult(status->app.getBean(LegalDocumentService.class).requireCurrent(a.id(),false)));assertEquals(428,denied.status());publisher.notifyChanges();publisher.notifyChanges();assertEquals(1,sql.queryForObject("SELECT count(*) FROM legal_change_notices WHERE user_id=? AND document_slug='terms' AND document_version=?",Integer.class,a.id(),terms.version()));accept(a,false);tx.executeWithoutResult(status->app.getBean(LegalDocumentService.class).requireCurrent(a.id(),false));assertEquals(2,sql.queryForObject("SELECT count(*) FROM legal_acceptances WHERE user_id=? AND document_slug='terms'",Integer.class,a.id()));assertNotNull(ok(send("GET","/api/v1/legal/documents/terms/"+baseline.documents().stream().filter(d->d.slug().equals("terms")).findFirst().orElseThrow().version(),null,null,null)).path("body"));
    }
    @Test void sameRevisionCannotSilentlyChangeContentAndAnOlderApplicationCannotRollbackTheHead()throws Exception {
        var changed=change("terms",1,true);assertThrows(IllegalStateException.class,()->publish(changed));assertEquals(0,sql.queryForObject("SELECT count(*) FROM legal_document_versions WHERE slug='terms' AND version=?",Integer.class,changed.version()));var newer=change("terms",2,true);publish(newer);publisher.publish(baseline);assertEquals(newer.version(),ok(send("GET","/api/v1/legal/documents/terms",null,null,null)).path("reference").path("version").asText());assertThrows(RuntimeException.class,()->sql.update("UPDATE legal_document_versions SET title='Changed' WHERE slug='terms' AND version=?",newer.version()));
    }
    @Test void databaseRejectsReceiptWithWrongContentHashOrWrongKind()throws Exception {
        var a=session();var terms=baseline.documents().stream().filter(d->d.slug().equals("terms")).findFirst().orElseThrow();assertThrows(RuntimeException.class,()->sql.update("INSERT INTO legal_acceptances(user_id,document_slug,document_version,kind,content_sha256,source,retain_until) VALUES(?,'terms',?,'contract',?,'terms_update',now()+interval '5 years')",a.id(),terms.version(),"f".repeat(64)));assertThrows(RuntimeException.class,()->sql.update("INSERT INTO legal_acceptances(user_id,document_slug,document_version,kind,content_sha256,source,retain_until) VALUES(?,'terms',?,'privacy_notice',?,'terms_update',now()+interval '5 years')",a.id(),terms.version(),terms.hash()));
    }
    @Test void suspendedUserKeepsDocumentAccessAndCanRegisterAnExplicitAcceptance()throws Exception {
        var a=session();sql.update("UPDATE users SET account_status='suspended' WHERE id=?",a.id());assertEquals(200,send("GET","/api/v1/privacy/legal",null,a,null).statusCode());accept(a,false);assertEquals("suspended",sql.queryForObject("SELECT account_status FROM users WHERE id=?",String.class,a.id()));
    }
    @Test void registrationRequiresExplicitPrivacyNoticeAndActualCurrentVersionsAndDoesNotEnableMarketing()throws Exception {
        UUID university=sql.queryForObject("INSERT INTO universities(slug,name,city,state_code) VALUES(?,'Synthetic university','Synthetic city','GO') RETURNING id",UUID.class,"legal-"+UUID.randomUUID());UUID course=sql.queryForObject("INSERT INTO courses(university_id,slug,name) VALUES(?,'synthetic','Synthetic course') RETURNING id",UUID.class,university);String email=UUID.randomUUID()+"@example.test";
        var body=new LinkedHashMap<String,Object>(Map.of("name","Synthetic registration user","email",email,"password",PASSWORD,"birth","2000-01-01","truth",true,"terms",true,"academic",Map.of("universityId",university.toString(),"courseId",course.toString(),"region","Synthetic region","periodStatus","numbered","periodNumber",1,"shift","night")));
        assertEquals(422,send("POST","/api/v1/auth/register",body,null,null).statusCode());body.put("legal",Map.of("privacyNotice",false,"documents",references(false)));assertEquals(422,send("POST","/api/v1/auth/register",body,null,null).statusCode());body.put("legal",Map.of("privacyNotice",true,"documents",references(false)));body.put("terms",false);assertEquals(422,send("POST","/api/v1/auth/register",body,null,null).statusCode());body.put("terms",true);
        var stale=new ArrayList<>(references(false));var first=stale.getFirst();stale.set(0,new LegalDto.Reference(first.slug(),"obsolete-version",first.contentSha256()));body.put("legal",Map.of("privacyNotice",true,"documents",stale));assertEquals(409,send("POST","/api/v1/auth/register",body,null,null).statusCode());assertEquals(0,sql.queryForObject("SELECT count(*) FROM users WHERE email=?",Integer.class,email));
        body.put("legal",Map.of("privacyNotice",true,"documents",references(false)));var response=send("POST","/api/v1/auth/register",body,null,null);assertEquals(202,response.statusCode(),response.body());UUID user=sql.queryForObject("SELECT id FROM users WHERE email=?",UUID.class,email);assertEquals(5,sql.queryForObject("SELECT count(*) FROM legal_acceptances WHERE user_id=? AND source='registration'",Integer.class,user));assertEquals(1,sql.queryForObject("SELECT count(*) FROM legal_acceptances WHERE user_id=? AND kind='privacy_notice'",Integer.class,user));assertEquals(0,sql.queryForObject("SELECT count(*) FROM privacy_consent_events WHERE user_id=?",Integer.class,user));assertFalse(sql.queryForObject("SELECT coalesce((SELECT marketing FROM privacy_preferences WHERE user_id=?),false)",Boolean.class,user));
        assertEquals(202,send("POST","/api/v1/auth/register",body,null,null).statusCode());assertEquals(5,sql.queryForObject("SELECT count(*) FROM legal_acceptances WHERE user_id=?",Integer.class,user));
    }
    @Test void closureConfirmationUsesThePolicyActuallyReadAndFreezesTheAcceptedGracePeriod()throws Exception {
        var a=session();String old=ok(send("GET","/api/v1/privacy/closure",null,a,null)).path("policyVersion").asText();
        publish(change("safety",2,true));
        assertEquals(409,send("POST","/api/v1/privacy/closure",Map.of("password",PASSWORD,"acknowledged",true,"policyVersion",old),a,UUID.randomUUID().toString()).statusCode());
        var state=ok(send("GET","/api/v1/privacy/closure",null,a,null));String current=state.path("policyVersion").asText();assertNotEquals(old,current);
        var response=send("POST","/api/v1/privacy/closure",Map.of("password",PASSWORD,"acknowledged",true,"policyVersion",current),a,UUID.randomUUID().toString());assertEquals(202,response.statusCode(),response.body());
        publish(change("safety",3,true));
        var frozen=ok(send("GET","/api/v1/privacy/closure",null,a,null));assertEquals(current,frozen.path("policyVersion").asText());assertEquals(state.path("graceDays"),frozen.path("graceDays"));
        assertEquals(state.path("graceDays").asInt(),sql.queryForObject("SELECT (obligations_snapshot->>'graceDays')::int FROM privacy_account_closures WHERE user_id=?",Integer.class,a.id()));
    }
    @Test void aReplayAfterPublicationReturnsItsHistoricalOutcomeWithoutInventingAcceptanceOfTheNewVersion()throws Exception {
        var a=session();var original=input(false);String key=UUID.randomUUID().toString();var first=send("POST","/api/v1/privacy/legal",original,a,key);ok(first);
        var update=change("terms",2,true);publish(update);
        assertEquals(first.body(),send("POST","/api/v1/privacy/legal",original,a,key).body());
        assertEquals(1,ok(send("GET","/api/v1/privacy/legal",null,a,null)).path("required").size());
        assertEquals(0,sql.queryForObject("SELECT count(*) FROM legal_acceptances WHERE user_id=? AND document_slug='terms' AND document_version=?",Integer.class,a.id(),update.version()));
        String policy=ok(send("GET","/api/v1/privacy/closure",null,a,null)).path("policyVersion").asText(),closureKey=UUID.randomUUID().toString();
        var closure=Map.of("password",PASSWORD,"acknowledged",true,"policyVersion",policy);var closed=send("POST","/api/v1/privacy/closure",closure,a,closureKey);assertEquals(202,closed.statusCode(),closed.body());
        publish(change("safety",2,true));assertEquals(closed.body(),send("POST","/api/v1/privacy/closure",closure,a,closureKey).body());
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM privacy_account_closures WHERE user_id=?",Integer.class,a.id()));
    }
    @Test void actualOfferDriverApplicationAndRequestEndpointsEnforceAcceptanceWithoutBlockingCancellation()throws Exception {
        var driver=session();var passenger=session();
        UUID university=sql.queryForObject("INSERT INTO universities(slug,name,city,state_code) VALUES(?,'Synthetic university','Synthetic city','GO') RETURNING id",UUID.class,"flow-"+UUID.randomUUID());
        UUID course=sql.queryForObject("INSERT INTO courses(university_id,slug,name) VALUES(?,'synthetic','Synthetic course') RETURNING id",UUID.class,university);
        for(var user:List.of(driver,passenger))sql.update("INSERT INTO student_profiles(user_id,university_id,course_id,period_status,period_number,shift,region_label) VALUES(?,?,?,'numbered',1,'night','Synthetic region')",user.id(),university,course);
        sql.update("INSERT INTO driver_profiles(user_id,status,approved_at) VALUES(?,'approved',now())",driver.id());
        sql.update("INSERT INTO vehicles(owner_id,model,color,plate,capacity,status) VALUES(?,'Synthetic Car','Black','ABC1D23',4,'approved')",driver.id());
        sql.update("INSERT INTO pricing_versions(university_id,fuel_cents,efficiency,wear_cents,reference_passengers,commission_bps,discount_bps) VALUES(?,600,10,10,3,1000,1000)",university);
        var application=Map.of("model","Synthetic Car","color","Black","plate","ABC1D23","capacity",4,"identityFileId",UUID.randomUUID().toString(),"licenseFileId",UUID.randomUUID().toString());
        assertEquals(428,send("POST","/api/v1/drivers/applications",application,passenger,UUID.randomUUID().toString()).statusCode());
        var offer=Map.of("region","Synthetic region","meeting","Synthetic meeting","direction","ida","date",java.time.LocalDate.now(java.time.ZoneId.of("America/Sao_Paulo")).plusDays(3).toString(),"time","18:00","capacity",4,"distance",10,"automatic",true);
        assertEquals(428,send("POST","/api/v1/rides",offer,driver,UUID.randomUUID().toString()).statusCode());accept(driver,false);
        assertEquals(428,send("POST","/api/v1/rides",offer,driver,UUID.randomUUID().toString()).statusCode());accept(driver,true);
        String ride=ok(send("POST","/api/v1/rides",offer,driver,UUID.randomUUID().toString())).path("id").asText();
        var request=Map.of("type","avulsa","rideId",ride);
        assertEquals(428,send("POST","/api/v1/ride-requests",request,passenger,UUID.randomUUID().toString()).statusCode());accept(passenger,false);
        String existing=ok(send("POST","/api/v1/ride-requests",request,passenger,UUID.randomUUID().toString())).path("id").asText();
        publish(change("terms",2,true));
        assertEquals(428,send("POST","/api/v1/rides",offer,driver,UUID.randomUUID().toString()).statusCode());
        assertEquals(200,send("POST","/api/v1/ride-requests/"+existing+"/cancel",Map.of(),passenger,UUID.randomUUID().toString()).statusCode());
        accept(passenger,false);var unavailable=send("POST","/api/v1/ride-requests",request,passenger,UUID.randomUUID().toString());assertEquals(409,unavailable.statusCode(),unavailable.body());assertEquals("COMBINATION_UNAVAILABLE",tree(unavailable).path("code").asText());assertFalse(unavailable.body().contains("driver-terms"));
    }
}
