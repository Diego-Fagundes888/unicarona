package com.unicarona.file;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unicarona.UniCaronaApplication;
import com.unicarona.security.CryptoService;
import com.unicarona.support.AuthTestSettings;
import com.unicarona.support.JavaApplicationPeer;
import com.unicarona.support.RealPostgres;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

class FileHttpIT {
    @TempDir static Path temporary;
    private static RealPostgres database;
    private static ServletWebServerApplicationContext app;
    private static JdbcTemplate sql;
    private static Path storage;
    private static String server,passwordHash;
    private static UUID university,course,classId;
    private static final String PASSWORD="Synthetic file HTTP 2026";
    private static final String BOUNDARY="SyntheticFileBoundary2026";
    private final ObjectMapper json=new ObjectMapper();
    private final HttpClient http=HttpClient.newHttpClient();
    private record Session(UUID id,String cookie,String csrf) {
        @Override public String toString() { return "Session[redacted]"; }
    }
    @BeforeAll static void start() throws Exception {
        database=new RealPostgres();database.resetSchema();storage=temporary.resolve("private");
        var args=new ArrayList<>(List.of(AuthTestSettings.arguments(database.apiConnectionUrl())));
        args.add("--STORAGE_PATH="+storage);args.add("--STORAGE_USER_QUOTA_BYTES=4096");args.add("--STORAGE_USER_MAX_FILES=3");
        app=(ServletWebServerApplicationContext)new SpringApplicationBuilder(UniCaronaApplication.class).run(args.toArray(String[]::new));
        sql=app.getBean(JdbcTemplate.class);server="http://127.0.0.1:"+app.getWebServer().getPort();passwordHash=app.getBean(CryptoService.class).hashPassword(PASSWORD);
        university=sql.queryForObject("INSERT INTO universities(slug,name,city,state_code) VALUES('file-university','File University','Gama','DF') RETURNING id",UUID.class);
        course=sql.queryForObject("INSERT INTO courses(university_id,slug,name) VALUES(?,'file-course','File Course') RETURNING id",UUID.class,university);
        classId=sql.queryForObject("INSERT INTO classes(university_id,course_id,period_number,label) VALUES(?,?,1,'A') RETURNING id",UUID.class,university,course);
    }
    @BeforeEach void limits() { sql.update("DELETE FROM rate_limits"); }
    @AfterAll static void close() { if(app!=null) app.close();if(database!=null) database.close(); }
    private HttpResponse<byte[]> send(String method,String path,Object body,Session session) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(server+path)).header("Origin",AuthTestSettings.ORIGIN);
        if(session!=null) request.header("Cookie",session.cookie()).header("x-csrf-token",session.csrf());
        if(body!=null) request.header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        else request.method(method,HttpRequest.BodyPublishers.noBody());
        return http.send(request.build(),HttpResponse.BodyHandlers.ofByteArray());
    }
    private JsonNode tree(HttpResponse<byte[]> response) throws Exception { return json.readTree(response.body()); }
    private void code(HttpResponse<byte[]> response,int status,String code) throws Exception { assertEquals(status,response.statusCode());assertEquals(code,tree(response).path("code").asText()); }
    private Session user(boolean admin) throws Exception {
        String email="file-"+UUID.randomUUID()+"@example.test";
        UUID id=sql.queryForObject("INSERT INTO users(email,password_hash,name,birth_date,role,email_verified_at) VALUES(?,?,'Synthetic File','2000-05-01',?,now()) RETURNING id",UUID.class,email,passwordHash,admin?"admin":"user");
        com.unicarona.support.LegalTestFixtures.acceptedBusinessUser(app,id);
        if(!admin) sql.update("INSERT INTO student_profiles(user_id,university_id,course_id,class_id,period_status,period_number,shift,region_label) VALUES(?,?,?,?,'numbered',1,'night','Gama')",id,university,course,classId);
        var response=send("POST","/api/v1/auth/login",Map.of("email",email,"password",PASSWORD),null);assertEquals(200,response.statusCode());
        var cookies=new LinkedHashMap<String,String>();
        for(String header:response.headers().allValues("set-cookie")) { var pair=header.split(";",2)[0].split("=",2);cookies.put(pair[0],pair[1]); }
        return new Session(id,String.join("; ",cookies.entrySet().stream().map(e -> e.getKey()+"="+e.getValue()).toList()),cookies.get("uc_csrf"));
    }
    private byte[] pdf(String text) { return ("%PDF-1.7\nSynthetic "+text+"\n%%EOF").getBytes(StandardCharsets.US_ASCII); }
    private byte[] png(int width,int height) throws Exception {
        var image=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);var bytes=new ByteArrayOutputStream();assertTrue(ImageIO.write(image,"png",bytes));return bytes.toByteArray();
    }
    private byte[] multipart(List<String> fields,byte[] file,int files) throws IOException {
        var output=new ByteArrayOutputStream();
        for(String field:fields) {
            String[] pair=field.split("=",2);
            output.write(("--"+BOUNDARY+"\r\nContent-Disposition: form-data; name=\""+pair[0]+"\"\r\n\r\n"+pair[1]+"\r\n").getBytes(StandardCharsets.UTF_8));
        }
        for(int index=0;index<files;index++) {
            output.write(("--"+BOUNDARY+"\r\nContent-Disposition: form-data; name=\"arbitrary-file-part\"; filename=\"ignored.svg\"\r\nContent-Type: text/html\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            output.write(file);output.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        }
        output.write(("--"+BOUNDARY+"--\r\n").getBytes(StandardCharsets.US_ASCII));return output.toByteArray();
    }
    private HttpResponse<byte[]> upload(Session user,List<String> fields,byte[] file,int count,boolean csrf) throws Exception {
        return upload(user,fields,file,count,csrf,"");
    }
    private HttpResponse<byte[]> upload(Session user,List<String> fields,byte[] file,int count,boolean csrf,String query) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(server+"/api/v1/files"+query)).header("Origin",AuthTestSettings.ORIGIN)
            .header("Content-Type","multipart/form-data; boundary="+BOUNDARY);
        if(user!=null) { request.header("Cookie",user.cookie());if(csrf) request.header("x-csrf-token",user.csrf()); }
        return http.send(request.POST(HttpRequest.BodyPublishers.ofByteArray(multipart(fields,file,count))).build(),HttpResponse.BodyHandlers.ofByteArray());
    }
    private HttpResponse<byte[]> upload(Session user,String purpose,byte[] data) throws Exception { return upload(user,List.of("purpose="+purpose),data,1,true); }
    private String store(Session user,String purpose,byte[] data) throws Exception {
        var response=upload(user,purpose,data);assertEquals(200,response.statusCode());return tree(response).path("id").asText();
    }
    private long diskCount() throws IOException { if(!Files.exists(storage)) return 0;try(var files=Files.list(storage)) { return files.count(); } }
    @Test void documentsUseDetectedTypeAndRemainPrivateAndByteExact() throws Exception {
        var owner=user(false);var other=user(false);var admin=user(true);byte[] peer=pdf("peer");
        var upload=upload(owner,List.of("purpose=identity"),peer,1,true,"?purpose=ignored&extra=a&extra=b&extra=c");assertEquals(200,upload.statusCode());var file=tree(upload);String id=file.path("id").asText();
        var fields=new HashSet<String>();file.fieldNames().forEachRemaining(fields::add);assertEquals(Set.of("id","purpose","contentType","byteSize"),fields);
        assertEquals("application/pdf",file.path("contentType").asText());assertEquals(peer.length,file.path("byteSize").asInt());
        var download=send("GET","/api/v1/files/"+id,null,owner);assertEquals(200,download.statusCode());assertArrayEquals(peer,download.body());
        assertEquals("private, no-store",download.headers().firstValue("cache-control").orElseThrow());
        assertEquals("attachment; filename=\"documento.pdf\"",download.headers().firstValue("content-disposition").orElseThrow());
        assertEquals("default-src 'none'; sandbox",download.headers().firstValue("content-security-policy").orElseThrow());
        assertArrayEquals(peer,send("GET","/api/v1/files/"+id,null,admin).body());
        code(send("GET","/api/v1/files/"+id,null,other),404,"FORBIDDEN");code(send("GET","/api/v1/files/"+id,null,null),401,"UNAUTHENTICATED");
        assertFalse(send("GET","/"+id,null,null).statusCode()==200);
        code(send("GET","/api/v1/files/bad-id",null,owner),422,"VALIDATION_ERROR");
        sql.update("UPDATE users SET account_status='suspended' WHERE id=?",owner.id());
        assertEquals(200,send("GET","/api/v1/files/"+id,null,owner).statusCode());code(upload(owner,"identity",peer),403,"ACCOUNT_RESTRICTED");
        sql.update("UPDATE stored_files SET deleted_at=now() WHERE id=?",UUID.fromString(id));code(send("GET","/api/v1/files/"+id,null,admin),404,"FILE_NOT_FOUND");
    }
    @Test void deduplicationIsOwnerAndPurposeScopedAndConcurrentUploadsLeaveNoOrphan() throws Exception {
        var owner=user(false);byte[] source=pdf("concurrent");long before=diskCount();
        try(var executor=Executors.newFixedThreadPool(2)) {
            var a=executor.submit(() -> upload(owner,"identity",source));var b=executor.submit(() -> upload(owner,"identity",source));
            var first=a.get(20,TimeUnit.SECONDS);var second=b.get(20,TimeUnit.SECONDS);assertEquals(200,first.statusCode());assertEquals(200,second.statusCode());assertEquals(tree(first),tree(second));
        }
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM stored_files WHERE owner_id=?",Integer.class,owner.id()));assertEquals(before+1,diskCount());
        String identity=store(owner,"identity",source),license=store(owner,"license",source);assertNotEquals(identity,license);
        String other=store(user(false),"identity",source);assertNotEquals(identity,other);
    }
    @Test void quotasIncludeDeletedFilesAndFailureCleansUpTheCandidate() throws Exception {
        var owner=user(false);for(int i=0;i<3;i++) store(owner,"identity",pdf("quota-"+i));long before=diskCount();
        var reused=upload(owner,"identity",pdf("quota-0"));assertEquals(200,reused.statusCode());assertEquals(before,diskCount());
        code(upload(owner,"identity",pdf("quota-3")),409,"FILE_QUOTA");assertEquals(before,diskCount());
        sql.update("UPDATE stored_files SET deleted_at=now() WHERE owner_id=?",owner.id());
        code(upload(owner,"identity",pdf("quota-0")),409,"FILE_QUOTA");assertEquals(before,diskCount());
        var byteOwner=user(false);byte[] large=Arrays.copyOf(pdf("byte quota"),5000);
        code(upload(byteOwner,"identity",large),409,"FILE_QUOTA");assertEquals(before,diskCount());assertEquals(0,sql.queryForObject("SELECT count(*) FROM stored_files WHERE owner_id=?",Integer.class,byteOwner.id()));
    }
    @Test void multipartAndContentLimitsAndCsrfAreEnforcedBeforePersistence() throws Exception {
        var owner=user(false);long before=diskCount();
        code(upload(owner,List.of("purpose=identity"),pdf("csrf"),1,false),403,"CSRF_INVALID");
        code(upload(owner,List.of("purpose=identity"),pdf("none"),0,true),422,"FILE_REQUIRED");
        code(upload(owner,List.of(),pdf("purpose"),1,true),422,"VALIDATION_ERROR");
        code(upload(owner,"unknown",pdf("unknown")),422,"VALIDATION_ERROR");
        code(upload(owner,List.of("purpose=identity","purpose=license"),pdf("duplicate purpose"),1,true),422,"VALIDATION_ERROR");
        code(upload(owner,List.of("purpose=identity"),pdf("two files"),2,true),413,"INVALID_REQUEST");
        code(upload(owner,List.of("purpose=identity","extra=a","extra=b","extra=c"),pdf("four fields"),1,true),413,"INVALID_REQUEST");
        code(upload(owner,"identity","<script>synthetic</script>".getBytes(StandardCharsets.US_ASCII)),422,"FILE_TYPE");
        code(upload(owner,"photo",pdf("photo PDF")),422,"FILE_TYPE");
        code(upload(owner,"identity",new byte[]{(byte)137,80,78,71,13,10,26,10,0}),422,"INVALID_IMAGE");
        code(upload(owner,"identity",new byte[0]),422,"FILE_SIZE");
        code(upload(owner,"photo",Arrays.copyOf(png(1,1),2*1024*1024+1)),422,"FILE_SIZE");
        code(upload(owner,"identity",Arrays.copyOf(pdf("oversized"),5*1024*1024+1)),413,"INVALID_REQUEST");
        var malformed=HttpRequest.newBuilder(URI.create(server+"/api/v1/files")).header("Origin",AuthTestSettings.ORIGIN)
            .header("Cookie",owner.cookie()).header("x-csrf-token",owner.csrf()).header("Content-Type","multipart/form-data")
            .POST(HttpRequest.BodyPublishers.ofString("synthetic invalid multipart")).build();
        code(http.send(malformed,HttpResponse.BodyHandlers.ofByteArray()),400,"INVALID_REQUEST");
        assertEquals(before,diskCount());assertEquals(0,sql.queryForObject("SELECT count(*) FROM stored_files WHERE owner_id=?",Integer.class,owner.id()));
    }
    @Test void photosAreConvertedAndSharedOnlyWithLinkedUnblockedParticipants() throws Exception {
        var driver=user(false);var passenger=user(false);var stranger=user(false);
        String photo=store(driver,"photo",png(1024,512));
        assertEquals(UUID.fromString(photo),sql.queryForObject("SELECT photo_file_id FROM users WHERE id=?",UUID.class,driver.id()));
        var download=send("GET","/api/v1/files/"+photo,null,driver);assertEquals("image/webp",download.headers().firstValue("content-type").orElseThrow());
        assertTrue(download.headers().firstValue("content-disposition").isEmpty());var image=ImageIO.read(new ByteArrayInputStream(download.body()));assertEquals(512,image.getWidth());assertEquals(256,image.getHeight());
        code(send("GET","/api/v1/files/"+photo,null,passenger),404,"FORBIDDEN");link(driver,passenger);
        assertEquals(200,send("GET","/api/v1/files/"+photo,null,passenger).statusCode());code(send("GET","/api/v1/files/"+photo,null,stranger),404,"FORBIDDEN");
        sql.update("INSERT INTO user_blocks(by_id,other_id) VALUES(?,?)",driver.id(),passenger.id());code(send("GET","/api/v1/files/"+photo,null,passenger),404,"FORBIDDEN");
        sql.update("UPDATE user_blocks SET active=false WHERE by_id=? AND other_id=?",driver.id(),passenger.id());assertEquals(200,send("GET","/api/v1/files/"+photo,null,passenger).statusCode());
        sql.update("INSERT INTO user_blocks(by_id,other_id) VALUES(?,?)",passenger.id(),driver.id());code(send("GET","/api/v1/files/"+photo,null,passenger),404,"FORBIDDEN");
    }
    @Test void replacingPhotoRevokesSavedPeerUrlsWithoutDeletingTheOwnersPrivateCopy() throws Exception {
        var driver=user(false);var passenger=user(false);var admin=user(true);link(driver,passenger);
        String old=store(driver,"photo",png(30,20));assertEquals(200,send("GET","/api/v1/files/"+old,null,passenger).statusCode());
        String current=store(driver,"photo",png(40,20));assertNotEquals(old,current);
        code(send("GET","/api/v1/files/"+old,null,passenger),404,"FORBIDDEN");
        assertEquals(200,send("GET","/api/v1/files/"+old,null,driver).statusCode());assertEquals(200,send("GET","/api/v1/files/"+old,null,admin).statusCode());
        assertEquals(200,send("GET","/api/v1/files/"+current,null,passenger).statusCode());
        sql.update("UPDATE users SET photo_file_id=NULL WHERE id=?",driver.id());code(send("GET","/api/v1/files/"+current,null,passenger),404,"FORBIDDEN");
        // Deliberate re-selection of a previously uploaded image makes only that image current again.
        assertEquals(old,store(driver,"photo",png(30,20)));assertEquals(200,send("GET","/api/v1/files/"+old,null,passenger).statusCode());
        code(send("GET","/api/v1/files/"+current,null,passenger),404,"FORBIDDEN");
    }
    private void participantAccess(Session driver,Session passenger,UUID ride,boolean allowed) throws Exception {
        UUID reservation=sql.queryForObject("SELECT id FROM ride_reservations WHERE ride_id=?",UUID.class,ride);
        for(Session actor:List.of(driver,passenger)) {
            UUID other=actor==driver?passenger.id():driver.id();
            UUID photo=sql.queryForObject("SELECT photo_file_id FROM users WHERE id=?",UUID.class,other);
            var contact=send("GET","/api/v1/reservations/"+reservation+"/contact",null,actor);
            var download=send("GET","/api/v1/files/"+photo,null,actor);
            if(allowed) { assertEquals(200,contact.statusCode());assertEquals(200,download.statusCode()); }
            else { code(contact,404,"CONTACT_UNAVAILABLE");code(download,404,"FORBIDDEN"); }
        }
        assertEquals(200,send("GET","/api/v1/rides/"+ride,null,passenger).statusCode());
        String plate=tree(send("GET","/api/v1/rides/"+ride,null,passenger)).path("vehicle").path("plate").asText();
        if(allowed) assertEquals("ABC1D23",plate);else assertNotEquals("ABC1D23",plate);
        assertEquals("ABC1D23",tree(send("GET","/api/v1/rides/"+ride,null,driver)).path("vehicle").path("plate").asText());
    }
    @Test void financialConfirmationAndCompletedHistoryDoNotExtendParticipantContactOrPhotoAccess() throws Exception {
        var driver=user(false);var passenger=user(false);UUID ride=link(driver,passenger);
        store(driver,"photo",png(30,20));store(passenger,"photo",png(20,30));participantAccess(driver,passenger,ride,true);
        sql.update("UPDATE ride_reservations SET status='boarded',boarded_at=now() WHERE ride_id=?",ride);sql.update("UPDATE rides SET status='active' WHERE id=?",ride);participantAccess(driver,passenger,ride,true);
        sql.update("UPDATE ride_reservations SET status='awaiting_confirmation' WHERE ride_id=?",ride);participantAccess(driver,passenger,ride,false);
        sql.update("UPDATE ride_reservations SET status='completed' WHERE ride_id=?",ride);sql.update("UPDATE rides SET status='completed' WHERE id=?",ride);participantAccess(driver,passenger,ride,false);
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM ride_reservations WHERE ride_id=?",Integer.class,ride));
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM payments WHERE request_id=(SELECT request_id FROM ride_reservations WHERE ride_id=?)",Integer.class,ride));
    }
    @Test void abandonedRecordsHaveABoundedWindowUsingTheCampusTimezoneEvenWithoutWorkerCleanup() throws Exception {
        var driver=user(false);var passenger=user(false);UUID ride=link(driver,passenger);
        store(driver,"photo",png(30,20));store(passenger,"photo",png(20,30));
        String zone=sql.queryForObject("SELECT time_zone FROM universities WHERE id=?",String.class,university);
        try {
            sql.update("UPDATE universities SET time_zone='Pacific/Honolulu' WHERE id=?",university);
            sql.update("UPDATE rides SET ride_date=((now()-interval '47 hours 59 minutes') AT TIME ZONE 'Pacific/Honolulu')::date,departure_time=((now()-interval '47 hours 59 minutes') AT TIME ZONE 'Pacific/Honolulu')::time WHERE id=?",ride);
            participantAccess(driver,passenger,ride,true);
            sql.update("UPDATE rides SET ride_date=((now()-interval '48 hours') AT TIME ZONE 'Pacific/Honolulu')::date,departure_time=((now()-interval '48 hours') AT TIME ZONE 'Pacific/Honolulu')::time WHERE id=?",ride);
            participantAccess(driver,passenger,ride,false);
            sql.update("UPDATE rides SET status='active' WHERE id=?",ride);participantAccess(driver,passenger,ride,false);
        } finally { sql.update("UPDATE universities SET time_zone=? WHERE id=?",zone,university); }
    }
    @Test void cancellingTheRideStopsSharingEvenWhenItsReservationHasNotBeenReconciledYet() throws Exception {
        var driver=user(false);var passenger=user(false);UUID ride=link(driver,passenger);
        store(driver,"photo",png(30,20));store(passenger,"photo",png(20,30));participantAccess(driver,passenger,ride,true);
        sql.update("UPDATE rides SET status='cancelled' WHERE id=?",ride);participantAccess(driver,passenger,ride,false);
    }
    @Test void bilateralBlocksCloseBothContactDirectionsAndPlatesWithoutCancellingTheContractRelationship() throws Exception {
        var driver=user(false);var passenger=user(false);UUID ride=link(driver,passenger);
        store(driver,"photo",png(30,20));store(passenger,"photo",png(20,30));participantAccess(driver,passenger,ride,true);
        for(boolean driverBlocks:List.of(true,false)) {
            UUID by=driverBlocks?driver.id():passenger.id(),other=driverBlocks?passenger.id():driver.id();
            sql.update("INSERT INTO user_blocks(by_id,other_id) VALUES(?,?)",by,other);participantAccess(driver,passenger,ride,false);
            assertEquals("confirmed",sql.queryForObject("SELECT status FROM ride_reservations WHERE ride_id=?",String.class,ride));
            sql.update("UPDATE user_blocks SET active=false WHERE by_id=? AND other_id=?",by,other);participantAccess(driver,passenger,ride,true);
        }
    }
    private UUID link(Session driver,Session passenger) {
        UUID vehicle=sql.queryForObject("INSERT INTO vehicles(owner_id,model,color,plate,capacity,status) VALUES(?,'Fixture','Blue','ABC1D23',4,'approved') RETURNING id",UUID.class,driver.id());
        UUID pricing=sql.queryForObject("INSERT INTO pricing_versions(university_id,fuel_cents,efficiency,wear_cents,reference_passengers,commission_bps,discount_bps) VALUES(?,600,10,10,3,1000,1000) RETURNING id",UUID.class,university);
        UUID route=sql.queryForObject("INSERT INTO ride_routes(university_id,driver_id,region,meeting,distance_m) VALUES(?,?,'Gama','Campus',10000) RETURNING id",UUID.class,university,driver.id());
        UUID ride=sql.queryForObject("INSERT INTO rides(university_id,route_id,driver_id,vehicle_id,direction,ride_date,departure_time,capacity,price_cents,commission_bps,pricing_version_id) VALUES(?,?,?,?,'ida',current_date+1,'18:00',4,600,1000,?) RETURNING id",UUID.class,university,route,driver.id(),vehicle,pricing);
        UUID request=sql.queryForObject("INSERT INTO ride_requests(passenger_id,driver_id,ride_id,type,status,expires_at) VALUES(?,?,?,'avulsa','confirmed',now()+interval '1 day') RETURNING id",UUID.class,passenger.id(),driver.id(),ride);
        sql.update("INSERT INTO request_lines(request_id,ride_id,cents,commission_bps) VALUES(?,?,600,1000)",request,ride);
        UUID payment=sql.queryForObject("INSERT INTO payments(request_id,passenger_id,amount_cents,provider,status,expires_at) VALUES(?,?,600,'development','awaiting',now()+interval '1 day') RETURNING id",UUID.class,request,passenger.id());
        sql.update("INSERT INTO ride_reservations(request_id,ride_id,passenger_id,driver_id,payment_id,status,pin_ciphertext,pin_hash,price_cents) VALUES(?,?,?,?,?,'confirmed','synthetic-cipher','synthetic-hash',600)",request,ride,passenger.id(),driver.id(),payment);
        return ride;
    }
    @Test void driverApplicationWorksWithDocumentsUploadedThroughRealHttp() throws Exception {
        var owner=user(false);var admin=user(true);String identity=store(owner,"identity",pdf("identity")),license=store(owner,"license",pdf("license"));
        var input=Map.of("model","Synthetic vehicle","color","Blue","plate","ABC1D23","capacity",4,"identityFileId",identity,"licenseFileId",license);
        var request=HttpRequest.newBuilder(URI.create(server+"/api/v1/drivers/applications")).header("Origin",AuthTestSettings.ORIGIN)
            .header("Cookie",owner.cookie()).header("x-csrf-token",owner.csrf()).header("idempotency-key","synthetic-file-"+UUID.randomUUID()).header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(input))).build();
        var response=http.send(request,HttpResponse.BodyHandlers.ofByteArray());assertEquals(200,response.statusCode());String id=tree(response).path("id").asText();
        assertArrayEquals(pdf("identity"),send("GET","/api/v1/files/"+identity,null,admin).body());
        var review=HttpRequest.newBuilder(URI.create(server+"/api/v1/admin/drivers/applications/"+id+"/review")).header("Origin",AuthTestSettings.ORIGIN)
            .header("Cookie",admin.cookie()).header("x-csrf-token",admin.csrf()).header("idempotency-key","synthetic-review-"+UUID.randomUUID()).header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("approve",true,"reason","Synthetic document review")))).build();
        assertEquals(200,http.send(review,HttpResponse.BodyHandlers.ofByteArray()).statusCode());
        assertEquals("approved",sql.queryForObject("SELECT status FROM driver_profiles WHERE user_id=?",String.class,owner.id()));
    }
    @Test void originalDocumentsAndPhotographsRemainReadableByBothBackends() throws Exception {
        var owner=user(false);byte[] document=pdf("cross-runtime");
        var headers=Map.of("origin",AuthTestSettings.ORIGIN,"cookie",owner.cookie(),"x-csrf-token",owner.csrf(),"content-type","multipart/form-data; boundary="+BOUNDARY);
        try(var peer=new JavaApplicationPeer(app, database.apiConnectionUrl(),storage)) {
            var legacy=peer.binaryRequest("POST","/api/v1/files?purpose=ignored&extra=a&extra=b&extra=c",multipart(List.of("purpose=identity"),document,1),headers);assertEquals(200,legacy.path("status").asInt());
            assertEquals(legacy.path("body"),tree(upload(owner,"identity",document)));
            String id=legacy.path("body").path("id").asText();var oldDownload=peer.request("GET","/api/v1/files/"+id,null,Map.of("cookie",owner.cookie()));
            var javaDownload=send("GET","/api/v1/files/"+id,null,owner);assertArrayEquals(Base64.getDecoder().decode(oldDownload.path("binary").asText()),javaDownload.body());
            assertEquals(oldDownload.path("headers").path("content-disposition").asText(),javaDownload.headers().firstValue("content-disposition").orElseThrow());
            var photo=peer.binaryRequest("POST","/api/v1/files",multipart(List.of("purpose=photo"),png(40,20),1),headers);assertEquals(200,photo.path("status").asInt());
            var javaPhoto=tree(upload(owner,"photo",png(40,20)));assertEquals(photo.path("body"),javaPhoto);
            String photoId=javaPhoto.path("id").asText();var read=peer.request("GET","/api/v1/files/"+photoId,null,Map.of("cookie",owner.cookie()));
            assertArrayEquals(Base64.getDecoder().decode(read.path("binary").asText()),send("GET","/api/v1/files/"+photoId,null,owner).body());
        }
    }
    @Test void swaggerDescribesMultipartBodyAndBinaryDownload() throws Exception {
        var paths=tree(send("GET","/api/docs/json",null,null)).path("paths");
        var schema=paths.path("/api/v1/files").path("post").path("requestBody").path("content").path("multipart/form-data").path("schema");
        assertEquals("binary",schema.path("properties").path("file").path("format").asText());assertEquals(4,schema.path("properties").path("purpose").path("enum").size());
        var responses=paths.path("/api/v1/files/{id}").path("get").path("responses");assertEquals("binary",responses.path("200").path("content").path("image/webp").path("schema").path("format").asText());
        assertTrue(paths.path("/api/v1/files").path("post").path("responses").has("413"));
    }
}
