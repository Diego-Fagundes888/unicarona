package com.unicarona.privacy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unicarona.auth.config.AuthSettings;
import com.unicarona.file.config.StorageSettings;
import com.unicarona.privacy.config.*;
import com.unicarona.privacy.recovery.*;
import com.unicarona.privacy.service.*;
import com.unicarona.security.CryptoService;
import com.unicarona.support.AuthTestSettings;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class PitrRecoveryBundleTest {
    @TempDir Path temporary;private PrivacyBackupCatalog catalog;private Path operations;private UUID dataset;private final Clock clock=Clock.fixed(Instant.parse("2026-10-26T12:00:00Z"),ZoneOffset.UTC);private final Map<String,byte[]> keys=Map.of("recovery",new byte[32]);
    @BeforeEach void setup()throws Exception {operations=temporary.resolve("ops");var env=new MockEnvironment().withProperty("NODE_ENV","test").withProperty("TOKEN_PEPPER",AuthTestSettings.PEPPER).withProperty("PIN_ENCRYPTION_KEY",AuthTestSettings.KEY).withProperty("JWT_SECRET",AuthTestSettings.SECRET).withProperty("STORAGE_PATH",temporary.resolve("files").toString()).withProperty("FRONTEND_PATH",temporary.resolve("public").toString()).withProperty("PRIVACY_ERASURE_JOURNAL_PATH",temporary.resolve("journal").toString()).withProperty("PRIVACY_OPERATIONS_ROOT",operations.toString());var json=new ObjectMapper();var crypto=new CryptoService();var auth=new AuthSettings(env);var files=new StorageSettings(env);catalog=new PrivacyBackupCatalog(new PrivacyOperationsSettings(env,files),new PrivacySettings(env),new PrivacySigningKeys(env,auth,crypto,json),json,clock);catalog.initialize();dataset=UUID.randomUUID();}
    private void bind()throws Exception {catalog.locked(()->{catalog.bindDataset(dataset);return null;});}
    private PitrRecoveryBundle reserve(UUID id)throws Exception {return PitrRecoveryBundle.reserve(catalog,operations,id,"123456",keys,"recovery",clock);}
    @Test void unboundAndForeignDatasetCannotRegisterARecoveryResource()throws Exception {assertThrows(IOException.class,()->reserve(dataset));assertEquals(0,catalog.inventory().present());bind();assertThrows(IOException.class,()->reserve(UUID.randomUUID()));assertEquals(0,catalog.inventory().present());}
    @Test void anInterruptedCaptureRemainsPendingAndBlocksSuppressionExpiry()throws Exception {bind();var bundle=reserve(dataset);var inventory=catalog.inventory();assertEquals(1,inventory.present());var copy=inventory.copies().getFirst();assertEquals(bundle.copyId(),copy.id());assertEquals("pending",copy.kind());assertEquals(0,copy.restorePointEpochMs());assertTrue(catalog.hasSurvivingCopyAtOrBefore(clock.instant()));assertThrows(IOException.class,()->bundle.finalizeCapture(clock.instant()));assertEquals(copy,catalog.inventory().copies().getFirst());}
    @Test void theEncryptedIndexUsesTheCatalogsOriginalFiniteDeadline()throws Exception {bind();var bundle=reserve(dataset);var copy=catalog.inventory().copies().getFirst();var index=bundle.archive().index();assertEquals(copy.createdAtEpochMs(),index.created());assertEquals(copy.expiresAtEpochMs(),index.expires());assertEquals(Duration.ofDays(35).toMillis(),index.expires()-index.created());assertThrows(IOException.class,()->bundle.archive().initialize(dataset,"123456",clock.instant(),clock.instant().plus(Duration.ofDays(60)),"recovery"));assertEquals(copy.expiresAtEpochMs(),bundle.archive().index().expires());}
}
