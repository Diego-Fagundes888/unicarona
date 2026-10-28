package com.unicarona.privacy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unicarona.auth.config.AuthSettings;
import com.unicarona.config.*;
import com.unicarona.privacy.service.*;
import com.unicarona.security.CryptoService;
import com.unicarona.support.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static com.unicarona.privacy.service.PrivacyEvidenceCrypto.Field.*;

class PrivacyEvidenceRotationIT {
    private static RealPostgres db;private JdbcTemplate sql;private DataSourceTransactionManager manager;
    private final CryptoService legacy=new CryptoService();private final ObjectMapper json=new ObjectMapper();
    @BeforeAll static void start(){db=new RealPostgres();}
    @AfterAll static void close(){if(db!=null)db.close();}
    @BeforeEach void reset()throws Exception {
        db.resetSchema();new LegacyFlywayMigrationStrategy(db.dataSource(),new LegacyMigrationManifest()).migrate(Flyway.configure().dataSource(db.dataSource()).locations("classpath:db/migration").cleanDisabled(true).load());sql=new JdbcTemplate(db.dataSource());manager=new DataSourceTransactionManager(db.dataSource());
    }
    private PrivacyEvidenceCrypto cipher(String current,String keys){var env=new MockEnvironment().withProperty("NODE_ENV","test").withProperty("TOKEN_PEPPER",AuthTestSettings.PEPPER).withProperty("PIN_ENCRYPTION_KEY",AuthTestSettings.KEY).withProperty("JWT_SECRET",AuthTestSettings.SECRET).withProperty("PRIVACY_EVIDENCE_KEY_ID",current).withProperty("PRIVACY_EVIDENCE_KEYS",keys);return new PrivacyEvidenceCrypto(env,new AuthSettings(env),legacy,json);}
    private String one(){return "{\"e1\":\""+"11".repeat(32)+"\"}";}
    private String two(){return "{\"e1\":\""+"11".repeat(32)+"\",\"e2\":\""+"22".repeat(32)+"\"}";}
    private UUID owner(){return sql.queryForObject("INSERT INTO users(email,password_hash,name,birth_date) VALUES(?,'synthetic-not-login','Synthetic evidence owner','2000-01-01') RETURNING id",UUID.class,UUID.randomUUID()+"@example.test");}
    private PrivacyEvidenceRotation rotation(PrivacyEvidenceCrypto cipher){return new PrivacyEvidenceRotation(sql,cipher,json,manager,org.mockito.Mockito.mock(PrivacyHoldRecovery.class));}
    private void request(UUID owner,UUID id,String encrypted){sql.update("INSERT INTO privacy_requests(id,user_id,kind,details_ciphertext,due_at) VALUES(?,?,'access',?,now()+interval '15 days')",id,owner,encrypted);}
    private String value(String table,String column,UUID id){return sql.queryForObject("SELECT "+column+" FROM "+table+" WHERE id=?",String.class,id);}
    @Test void allStoredEvidenceAndMailRotateWithoutChangingDatesMeaningOrFinancialKey()throws Exception {
        var cipher=cipher("e2",two());UUID owner=owner(),request=UUID.randomUUID(),response=UUID.randomUUID(),retained=UUID.randomUUID(),decision=UUID.randomUUID(),email=UUID.randomUUID(),hold=UUID.randomUUID(),holdEvent=UUID.randomUUID(),accept=UUID.randomUUID(),mail=UUID.randomUUID(),closure=UUID.randomUUID();String original=legacy.encrypt("Synthetic private value",AuthTestSettings.KEY);request(owner,request,original);
        sql.update("INSERT INTO privacy_request_responses(id,request_id,author_id,body_ciphertext) VALUES(?,?,?,?)",response,request,owner,original);
        sql.update("INSERT INTO privacy_retained_records(id,user_id,category,content_ciphertext,reason,legal_basis,delete_after) VALUES(?,?,'contract_identity',?,'Synthetic case','regular_exercise_of_rights',now()+interval '5 years')",retained,owner,original);
        sql.update("INSERT INTO privacy_account_decisions(id,user_id,action,previous_status,new_status,reason_code,public_reason,reason_ciphertext,created_by) VALUES(?,?,'close','active','closing','security','Synthetic public case',?,?)",decision,owner,original,owner);
        sql.update("INSERT INTO privacy_email_changes(id,user_id,new_email_ciphertext,new_email_hmac,original_email_hmac,credential_hmac,eligible,expires_at) VALUES(?,?,?,?,?,'synthetic-credential',true,now()+interval '30 minutes')",email,owner,original,"a".repeat(64),"b".repeat(64));
        sql.update("INSERT INTO privacy_legal_holds(id,user_id,category,resource_type,legal_basis,reason_ciphertext,created_by,review_at,expires_at) VALUES(?,?,'profile','user','regular_exercise_of_rights',?,?,now()+interval '1 day',now()+interval '2 days')",hold,owner,original,owner);
        sql.update("INSERT INTO privacy_hold_events(id,hold_id,user_id,revision,action,body_ciphertext,created_by) VALUES(?,?,?,1,'legacy_snapshot',?,?)",holdEvent,hold,owner,original,owner);
        sql.update("INSERT INTO privacy_account_closures(id,user_id,initiated_by,status,previous_account_status,reauthenticated_at,erase_after,policy_version,completion_contact_ciphertext,notice_contact_expires_at) VALUES(?,?,'user','scheduled','active',now(),now()+interval '7 days','synthetic-closure-policy',?,now()+interval '90 days')",closure,owner,original);
        sql.update("INSERT INTO legal_document_versions(slug,version,title,content,content_sha256,change_summary,requires_acceptance,effective_at) VALUES('terms','cipher-test','Synthetic document','{}',?,'Synthetic initial content',true,now())","c".repeat(64));
        sql.update("INSERT INTO legal_acceptances(id,user_id,document_slug,document_version,kind,content_sha256,source,ip_ciphertext,ip_delete_after,retain_until) VALUES(?,?,'terms','cipher-test','contract',?,'registration',?,now()+interval '180 days',now()+interval '5 years')",accept,owner,"c".repeat(64),original);
        String event="identity-notice:synthetic";sql.update("INSERT INTO outbox_jobs(id,type,event_key,payload) VALUES(?,'email',?,?::jsonb)",mail,event,json.writeValueAsString(Map.of("ownerId",owner.toString(),"kind","email_change_notice","toCiphertext",original,"urlCiphertext",original,"contentCiphertext",original)));
        String before=value("privacy_account_decisions","(to_jsonb(privacy_account_decisions)-'reason_ciphertext')::text",decision),acceptBefore=value("legal_acceptances","(to_jsonb(legal_acceptances)-'ip_ciphertext')::text",accept);
        var result=rotation(cipher).runOnce(100,()->false);assertEquals(10,result.rotated(),result.toString());assertEquals(0,result.failed());
        for(var row:List.of(new Object[]{"privacy_requests","details_ciphertext",request,REQUEST_DETAILS},new Object[]{"privacy_request_responses","body_ciphertext",response,RESPONSE_BODY},new Object[]{"privacy_retained_records","content_ciphertext",retained,RETAINED_CONTENT},new Object[]{"privacy_account_decisions","reason_ciphertext",decision,DECISION_REASON},new Object[]{"privacy_email_changes","new_email_ciphertext",email,EMAIL_TARGET},new Object[]{"privacy_legal_holds","reason_ciphertext",hold,HOLD_REASON},new Object[]{"privacy_hold_events","body_ciphertext",holdEvent,HOLD_EVENT_REASON},new Object[]{"privacy_account_closures","completion_contact_ciphertext",closure,CLOSURE_CONTACT},new Object[]{"legal_acceptances","ip_ciphertext",accept,ACCEPTANCE_IP})){
            String encrypted=value((String)row[0],(String)row[1],(UUID)row[2]);assertTrue(cipher.isCurrent(encrypted));assertEquals("Synthetic private value",cipher.open((PrivacyEvidenceCrypto.Field)row[3],owner,(UUID)row[2],encrypted));
        }
        var payload=json.readTree(value("outbox_jobs","payload::text",mail));assertEquals("Synthetic private value",cipher.openMail(MAIL_URL,owner,event,payload.path("urlCiphertext").asText()));
        assertEquals(before,value("privacy_account_decisions","(to_jsonb(privacy_account_decisions)-'reason_ciphertext')::text",decision));assertEquals(acceptBefore,value("legal_acceptances","(to_jsonb(legal_acceptances)-'ip_ciphertext')::text",accept));
        assertEquals("Synthetic private value",legacy.decrypt(original,AuthTestSettings.KEY));assertEquals(0,rotation(cipher).runOnce(100,()->false).rotated());
        assertNotEquals("on",sql.queryForObject("SELECT current_setting('unicarona.privacy_key_rotation',true)",String.class));
    }
    @Test void unreadableOldKeyDoesNotOverwriteDataOrStarveLaterRecordsInSmallBatches(){
        UUID owner=owner();var old=cipher("e1",one());var next=cipher("e2",two());var absent=cipher("gone","{\"gone\":\""+"33".repeat(32)+"\"}");
        UUID broken=UUID.fromString("00000000-0000-0000-0000-000000000001");String unavailable=absent.seal(REQUEST_DETAILS,owner,broken,"Synthetic unavailable evidence");request(owner,broken,unavailable);
        var ids=new ArrayList<UUID>();for(int n=0;n<4;n++){UUID id=UUID.randomUUID();ids.add(id);request(owner,id,old.seal(REQUEST_DETAILS,owner,id,"Synthetic readable evidence"));}
        var rotation=rotation(next);int failures=0;for(int n=0;n<12;n++){var result=rotation.runOnce(1,()->false);assertTrue(result.inspected()<=1);failures+=result.failed();}
        assertTrue(failures>0);assertEquals(unavailable,value("privacy_requests","details_ciphertext",broken));
        for(UUID id:ids){String value=value("privacy_requests","details_ciphertext",id);assertTrue(next.isCurrent(value));assertEquals("Synthetic readable evidence",next.open(REQUEST_DETAILS,owner,id,value));}
        assertEquals(0,rotation.runOnce(10,()->true).inspected());
    }
    @Test void nestedRetainedIdentityLosesHiddenOldKeyDependencyWithoutLosingEvidence()throws Exception {
        UUID owner=owner(),change=UUID.randomUUID(),id=UUID.randomUUID();var old=cipher("e1",one());var next=cipher("e2",two());
        String nested=old.seal(EMAIL_TARGET,owner,change,"new-address@example.test");String outer=legacy.encrypt(json.writeValueAsString(Map.of("new_email_ciphertext",nested,"id",change.toString(),"status","confirmed")),AuthTestSettings.KEY);
        sql.update("INSERT INTO privacy_retained_records(id,user_id,category,resource_type,resource_id,content_ciphertext,reason,legal_basis,delete_after) VALUES(?,?,'identity_change','identity_change',?,?,'Synthetic case','regular_exercise_of_rights',now()+interval '1 day')",id,owner,change,outer);
        String before=value("privacy_retained_records","(to_jsonb(privacy_retained_records)-'content_ciphertext')::text",id);assertEquals(1,rotation(next).runOnce(5,()->false).rotated());
        var onlyNew=cipher("e2","{\"e2\":\""+"22".repeat(32)+"\"}");var result=json.readTree(onlyNew.open(RETAINED_CONTENT,owner,id,value("privacy_retained_records","content_ciphertext",id)));
        assertFalse(result.has("new_email_ciphertext"));assertEquals("new-address@example.test",result.path("new_email").asText());assertEquals("confirmed",result.path("status").asText());assertEquals(before,value("privacy_retained_records","(to_jsonb(privacy_retained_records)-'content_ciphertext')::text",id));
    }
    @Test void rotationFlagCannotRewriteDecisionOrShortenItsRetention(){
        UUID owner=owner(),id=UUID.randomUUID();String original=legacy.encrypt("Synthetic decision reason",AuthTestSettings.KEY);var cipher=cipher("e2",two());
        sql.update("INSERT INTO privacy_account_decisions(id,user_id,action,previous_status,new_status,reason_code,public_reason,reason_ciphertext,created_by) VALUES(?,?,'close','active','closing','security','Synthetic public case',?,?)",id,owner,original,owner);
        String encrypted=cipher.seal(DECISION_REASON,owner,id,"Synthetic decision reason");assertThrows(RuntimeException.class,()->sql.update("UPDATE privacy_account_decisions SET reason_ciphertext=? WHERE id=?",encrypted,id));
        assertThrows(RuntimeException.class,()->new TransactionTemplate(manager).execute(status->{sql.execute("SET LOCAL unicarona.privacy_key_rotation='on'");return sql.update("UPDATE privacy_account_decisions SET reason_ciphertext=?,public_reason='A replacement reason' WHERE id=?",encrypted,id);}));
        assertThrows(RuntimeException.class,()->new TransactionTemplate(manager).execute(status->{sql.execute("SET LOCAL unicarona.privacy_key_rotation='on'");return sql.update("UPDATE privacy_account_decisions SET reason_ciphertext=?,retain_until=now()+interval '1 day' WHERE id=?",encrypted,id);}));
        assertEquals(original,value("privacy_account_decisions","reason_ciphertext",id));assertEquals(1,rotation(cipher).runOnce(2,()->false).rotated());
    }
    @Test void legacyAuthMailRecipientIsProtectedUsingTheExistingTokenOwnerAndOptionalFrozenContent()throws Exception {
        UUID owner=owner(),token=UUID.randomUUID(),job=UUID.randomUUID();String event="auth:"+token;
        sql.update("INSERT INTO auth_tokens(id,user_id,kind,token_hash,expires_at) VALUES(?,?,'verify_email',?,now()+interval '1 hour')",token,owner,"d".repeat(64));
        String url="https://example.test/synthetic-link";sql.update("INSERT INTO outbox_jobs(id,type,event_key,payload) VALUES(?,'email',?,?::jsonb)",job,event,json.writeValueAsString(Map.of("to","legacy@example.test","kind","verify_email","urlCiphertext",legacy.encrypt(url,AuthTestSettings.KEY))));
        var cipher=cipher("e2",two());var result=rotation(cipher).runOnce(20,()->false);assertEquals(1,result.rotated(),result.toString());assertEquals(0,result.failed());
        var payload=json.readTree(value("outbox_jobs","payload::text",job));assertFalse(payload.has("to"));assertFalse(payload.toString().contains("legacy@example.test"));assertFalse(payload.has("contentCiphertext"));assertEquals(owner.toString(),payload.path("ownerId").asText());
        assertEquals("legacy@example.test",cipher.openMail(MAIL_TO,owner,event,payload.path("toCiphertext").asText()));assertEquals(url,cipher.openMail(MAIL_URL,owner,event,payload.path("urlCiphertext").asText()));assertEquals("d".repeat(64),value("auth_tokens","token_hash",token));
    }
}
