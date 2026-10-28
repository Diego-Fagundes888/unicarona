package com.unicarona.support;

import com.unicarona.UniCaronaApplication;
import com.unicarona.privacy.dto.PrivacyDto;
import com.unicarona.privacy.service.PrivacyRightsService;
import com.unicarona.security.Actor;
import com.unicarona.seed.SeedService;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.JdbcTemplate;

/** Test classpath only. A disposable local browser-review fixture, never a production entry point. */
public final class PrivacyPreviewServer {
    private PrivacyPreviewServer(){}
    public static void main(String[] ignored)throws Exception {
        Path root=Path.of(required("PRIVACY_PREVIEW_ROOT")).toAbsolutePath().normalize();
        if(!root.getFileName().toString().matches("[a-f0-9-]{36}")||!root.getParent().getFileName().toString().equals("java-privacy-preview"))throw new IllegalStateException("Expected a dedicated synthetic preview directory.");
        Files.createDirectories(root);String origin="http://localhost:4190";
        try(var database=new RealPostgres()){
            database.resetSchema();var args=new ArrayList<>(List.of(AuthTestSettings.arguments(database.apiConnectionUrl())));args.removeIf(value->value.startsWith("--APP_ORIGIN=")||value.startsWith("--server.port="));
            args.addAll(List.of("--APP_ORIGIN="+origin,"--server.address=127.0.0.1","--server.port=4190","--UNICARONA_MODE=api","--SEED_PASSWORD="+AuthTestSettings.SEED_PASSWORD,"--STORAGE_PATH="+root.resolve("uploads"),"--DEV_MAIL_PATH="+root.resolve("mail"),"--PRIVACY_ERASURE_JOURNAL_PATH="+root.resolve("journal"),"--PRIVACY_OPERATIONS_ROOT=","--FRONTEND_PATH="+Path.of("dist").toAbsolutePath().normalize()));
            try(var app=new SpringApplicationBuilder(UniCaronaApplication.class).run(args.toArray(String[]::new))){
                app.getBean(SeedService.class).run();var sql=app.getBean(JdbcTemplate.class);
                sql.update("UPDATE users SET email=replace(email,'@example.com','@example.test') WHERE email LIKE '%@example.com'");
                LegalTestFixtures.publish(app);for(UUID id:sql.query("SELECT id FROM users",(r,n)->r.getObject(1,UUID.class)))LegalTestFixtures.acceptedBusinessUser(app,id);
                UUID owner=sql.queryForObject("SELECT id FROM users WHERE email='ana@example.test'",UUID.class);
                app.getBean(PrivacyRightsService.class).request(new Actor(owner,"user","active",null,null,null,true,Set.of()),new PrivacyDto.Request("correction","Cenário sintético de revisão: preciso corrigir meus dados acadêmicos e entender o prazo de uso dos documentos."),UUID.randomUUID().toString());
                // Reproducible post-fix review states, prepared through the real services.
                var ownerActor=fixtureActor(app,owner,"user","ana@example.test");
                var closures=app.getBean(com.unicarona.privacy.service.PrivacyClosureService.class);
                closures.request(ownerActor,new PrivacyDto.ClosureRequest(AuthTestSettings.SEED_PASSWORD,true,closures.status(ownerActor).policyVersion()),UUID.randomUUID().toString());
                closures.cancel(ownerActor,new PrivacyDto.Reauthenticate(AuthTestSettings.SEED_PASSWORD),UUID.randomUUID().toString());
                UUID paused=sql.queryForObject("SELECT id FROM users WHERE email='lucas@example.test'",UUID.class);
                app.getBean(com.unicarona.privacy.service.AccountControlService.class).deactivate(fixtureActor(app,paused,"user","lucas@example.test"),new com.unicarona.privacy.dto.AccountControlDto.Deactivate(AuthTestSettings.SEED_PASSWORD,true),UUID.randomUUID().toString());
                UUID administrator=sql.queryForObject("SELECT id FROM users WHERE email='admin@example.test'",UUID.class);
                app.getBean(com.unicarona.privacy.service.AdminPrivacyHoldService.class).create(fixtureActor(app,administrator,"admin","admin@example.test"),new com.unicarona.privacy.dto.PrivacyHoldDto.Create(AuthTestSettings.SEED_PASSWORD,owner,"profile",owner,"regular_exercise_of_rights","Preservação sintética para conferência do estado próprio, sem dados reais.","QA-OWN-CONFIRM",java.time.Instant.now().plusSeconds(86400),java.time.Instant.now().plusSeconds(172800)),UUID.randomUUID().toString());
                driverDocumentFixture(app,owner,"docs-approved@example.test","Motorista com aprovação registrada","approved",true);
                driverDocumentFixture(app,owner,"docs-expired@example.test","Solicitação vencida · teste sintético","pending",true);
                driverDocumentFixture(app,owner,"docs-pending@example.test","Documentos aguardando análise","pending",false);
                UUID metadataOwner=sql.queryForObject("SELECT id FROM users WHERE email='docs-approved@example.test'",UUID.class);UUID metadataApplication=sql.queryForObject("SELECT id FROM driver_applications WHERE user_id=?",UUID.class,metadataOwner);sql.update("UPDATE driver_applications SET decision='Justificativa sintética de análise para conferir preservação por caso.',reviewed_by=? WHERE id=?",administrator,metadataApplication);
                app.getBean(com.unicarona.privacy.service.AdminPrivacyHoldService.class).create(fixtureActor(app,administrator,"admin","admin@example.test"),new com.unicarona.privacy.dto.PrivacyHoldDto.Create(AuthTestSettings.SEED_PASSWORD,metadataOwner,"driver_application",metadataApplication,"regular_exercise_of_rights","Caso sintético de revisão do cadastro, sem documentos ou dados reais.","QA-DRIVER-METADATA",java.time.Instant.now().plusSeconds(86400),java.time.Instant.now().plusSeconds(172800)),UUID.randomUUID().toString());
                System.out.println("PRIVACY_PREVIEW_READY "+origin+" synthetic accounts only");System.out.flush();new CountDownLatch(1).await();
            }
        }
    }
    private static void driverDocumentFixture(org.springframework.context.ApplicationContext app,UUID academicSource,String email,String name,String status,boolean due)throws Exception {
        var sql=app.getBean(JdbcTemplate.class);UUID owner=sql.queryForObject("INSERT INTO users(email,password_hash,name,birth_date,email_verified_at) VALUES(?,?,?,'2000-01-01',now()) RETURNING id",UUID.class,email,app.getBean(com.unicarona.security.CryptoService.class).hashPassword(AuthTestSettings.SEED_PASSWORD),name);
        sql.update("INSERT INTO student_profiles(user_id,university_id,course_id,class_id,period_status,period_number,shift,region_label) SELECT ?,university_id,course_id,class_id,period_status,period_number,shift,region_label FROM student_profiles WHERE user_id=?",owner,academicSource);LegalTestFixtures.acceptedBusinessUser(app,owner);
        var actor=fixtureActor(app,owner,"user",email);var files=app.getBean(com.unicarona.file.service.FileService.class);byte[] pdf="%PDF-1.7\nSynthetic browser review only, no identity document\n%%EOF".getBytes(java.nio.charset.StandardCharsets.US_ASCII);UUID identity=files.upload(actor,"identity",pdf).id(),license=files.upload(actor,"license",pdf).id();if(due)sql.update("UPDATE stored_files SET created_at=now()-interval '62 days',last_uploaded_at=now()-interval '62 days' WHERE owner_id=?",owner);
        UUID vehicle=sql.queryForObject("INSERT INTO vehicles(owner_id,model,color,plate,capacity,status) VALUES(?,'Carro de teste','Prata','ABC1D23',4,?) RETURNING id",UUID.class,owner,"approved".equals(status)?"approved":"pending");sql.update("INSERT INTO driver_profiles(user_id,status,approved_at) VALUES(?,?,CASE WHEN ? THEN now()-interval '31 days' ELSE NULL END)",owner,"approved".equals(status)?"approved":"pending","approved".equals(status));UUID application=sql.queryForObject("INSERT INTO driver_applications(user_id,vehicle_id,identity_file_id,license_file_id,status,created_at,reviewed_at,documents_expires_at) VALUES(?,?,?,?,?,CASE WHEN ? THEN now()-CASE WHEN ? THEN interval '61 days' ELSE interval '31 days' END ELSE now() END,CASE WHEN ? THEN now()-interval '31 days' ELSE NULL END,CASE WHEN ? THEN now()-interval '1 day' ELSE now()+interval '30 days' END) RETURNING id",UUID.class,owner,vehicle,identity,license,status,due,"approved".equals(status),"approved".equals(status),due);
        if(due){app.getBean(com.unicarona.privacy.service.PrivacyDriverDocuments.class).retire(new com.unicarona.privacy.service.PrivacyDriverDocuments.Candidate(application,owner),false);app.getBean(com.unicarona.privacy.service.PrivacyFileErasure.class).erase(identity);app.getBean(com.unicarona.privacy.service.PrivacyFileErasure.class).erase(license);}
    }
    private static Actor fixtureActor(org.springframework.context.ApplicationContext app,UUID id,String role,String email){
        var tokens=app.getBean(com.unicarona.auth.service.AuthService.class).login(email,AuthTestSettings.SEED_PASSWORD).tokens();
        return new Actor(id,role,"active",tokens.sessionId(),null,null,true,"admin".equals(role)?Set.of("ROLE_ADMIN"):Set.of());
    }
    private static String required(String key){String value=System.getenv(key);if(value==null||value.isBlank())throw new IllegalStateException("Synthetic preview configuration is missing.");return value;}
}
