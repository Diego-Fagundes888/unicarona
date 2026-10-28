package com.unicarona.privacy;

import com.unicarona.config.LegacyFlywayMigrationStrategy;
import com.unicarona.config.LegacyMigrationManifest;
import com.unicarona.support.RealPostgres;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Schema invariants only; not evidence that the end-user privacy flows are implemented. */
class PrivacySchemaIT {
    private static RealPostgres database;
    private JdbcTemplate sql;
    @BeforeAll static void start(){database=new RealPostgres();}
    @AfterAll static void close(){if(database!=null)database.close();}
    @BeforeEach void reset()throws Exception {
        database.resetSchema();
        var flyway=Flyway.configure().dataSource(database.dataSource()).locations("classpath:db/migration").cleanDisabled(true).load();
        new LegacyFlywayMigrationStrategy(database.dataSource(),new LegacyMigrationManifest()).migrate(flyway);
        sql=new JdbcTemplate(database.dataSource());
    }
    private UUID user(){return sql.queryForObject("INSERT INTO users(email,password_hash,name,birth_date) VALUES(?,'synthetic-not-an-authentication-fixture','Synthetic privacy fixture','2000-01-01') RETURNING id",UUID.class,UUID.randomUUID()+"@example.test");}
    private void document(){sql.update("INSERT INTO legal_document_versions(slug,version,title,content,content_sha256,change_summary,requires_acceptance,effective_at) VALUES('terms','test-v1','Synthetic terms','{\"sections\":[]}',?,'Synthetic initial version',true,now())","a".repeat(64));}
    @Test void publishedContentCannotBeRewrittenOrDeletedWhileHeadsCanSelectAVersion(){
        document();sql.update("INSERT INTO legal_document_heads(slug,version) VALUES('terms','test-v1')");
        assertThrows(RuntimeException.class,()->sql.update("UPDATE legal_document_versions SET title='Rewritten' WHERE slug='terms'"));
        assertThrows(RuntimeException.class,()->sql.update("DELETE FROM legal_document_versions WHERE slug='terms'"));
        assertEquals("Synthetic terms",sql.queryForObject("SELECT title FROM legal_document_versions",String.class));
    }
    @Test void noAcceptanceIsInventedAndMarketingStartsDisabled(){
        UUID user=user();assertEquals(0,sql.queryForObject("SELECT count(*) FROM legal_acceptances",Integer.class));
        sql.update("INSERT INTO privacy_preferences(user_id) VALUES(?)",user);
        assertFalse(sql.queryForObject("SELECT marketing FROM privacy_preferences WHERE user_id=?",Boolean.class,user));
        assertThrows(RuntimeException.class,()->sql.update("UPDATE privacy_preferences SET marketing=true WHERE user_id=?",user));
    }
    @Test void acceptanceIsBoundToExistingDocumentVersionAndHasAFiniteRetention(){
        UUID user=user();document();
        assertThrows(RuntimeException.class,()->sql.update("INSERT INTO legal_acceptances(user_id,document_slug,document_version,kind,content_sha256,source,retain_until) VALUES(?,'terms','missing','contract',?,'registration',now()+interval '5 years')",user,"a".repeat(64)));
        assertThrows(RuntimeException.class,()->sql.update("INSERT INTO legal_acceptances(user_id,document_slug,document_version,kind,content_sha256,source,retain_until) VALUES(?,'terms','test-v1','contract',?,'registration',now()-interval '1 day')",user,"a".repeat(64)));
        sql.update("INSERT INTO legal_acceptances(user_id,document_slug,document_version,kind,content_sha256,source,retain_until) VALUES(?,'terms','test-v1','contract',?,'registration',now()+interval '5 years')",user,"a".repeat(64));
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM legal_acceptances WHERE user_id=?",Integer.class,user));
    }
    @Test void banFingerprintCannotBeRawEmailOrAnIndefiniteRestriction(){
        UUID admin=user();
        assertThrows(RuntimeException.class,()->sql.update("INSERT INTO privacy_registration_restrictions(email_hmac,reason_code,created_by,expires_at) VALUES('person@example.test','security',?,now()+interval '1 day')",admin));
        assertThrows(RuntimeException.class,()->sql.update("INSERT INTO privacy_registration_restrictions(email_hmac,reason_code,created_by,expires_at) VALUES(?,'security',?,now()+interval '2 years')","b".repeat(64),admin));
        sql.update("INSERT INTO privacy_registration_restrictions(email_hmac,reason_code,created_by,expires_at) VALUES(?,'security',?,now()+interval '90 days')","b".repeat(64),admin);
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM privacy_registration_restrictions",Integer.class));
    }
    @Test void anAccurateMinorBirthDateRequiresRestrictedServiceAndCannotBeRestoredToActive(){
        UUID owner=user();String correction="UPDATE users SET birth_date=((now() AT TIME ZONE 'America/Sao_Paulo')::date-interval '17 years')::date WHERE id=?";
        assertThrows(RuntimeException.class,()->sql.update(correction,owner));
        sql.update("UPDATE users SET account_status='suspended' WHERE id=?",owner);sql.update(correction,owner);
        assertThrows(RuntimeException.class,()->sql.update("UPDATE users SET account_status='active' WHERE id=?",owner));
        assertThrows(RuntimeException.class,()->sql.update("UPDATE users SET account_status='deactivated' WHERE id=?",owner));
        assertThrows(RuntimeException.class,()->sql.update("UPDATE users SET birth_date=(now() AT TIME ZONE 'America/Sao_Paulo')::date+1 WHERE id=?",owner));
    }
    @Test void restoreWatermarkBeginsUnestablishedAndCannotRegressAndErasedFilesCanBeRescanned(){
        assertNull(sql.queryForObject("SELECT observed_at FROM privacy_restore_state WHERE singleton",java.sql.Timestamp.class));
        sql.update("UPDATE privacy_restore_state SET observed_at=now() WHERE singleton");
        assertThrows(RuntimeException.class,()->sql.update("UPDATE privacy_restore_state SET observed_at=now()-interval '1 day' WHERE singleton"));
        assertThrows(RuntimeException.class,()->sql.update("UPDATE privacy_restore_state SET observed_at=NULL WHERE singleton"));
        assertThrows(RuntimeException.class,()->sql.update("DELETE FROM privacy_restore_state"));
        assertThrows(RuntimeException.class,()->sql.update("UPDATE privacy_restore_state SET dataset_id=gen_random_uuid()"));
        UUID owner=user(),file=UUID.randomUUID();sql.update("INSERT INTO stored_files(id,owner_id,purpose,storage_key,content_type,byte_size,sha256) VALUES(?,?,'identity',?,'image/png',3,?)",file,owner,file.toString(),"a".repeat(64));
        assertTrue(sql.queryForObject("SELECT erasure_last_scan_at='epoch'::timestamptz FROM stored_files WHERE id=?",Boolean.class,file));
        assertThrows(RuntimeException.class,()->sql.update("UPDATE stored_files SET erasure_last_scan_at=NULL WHERE id=?",file));
    }
}
