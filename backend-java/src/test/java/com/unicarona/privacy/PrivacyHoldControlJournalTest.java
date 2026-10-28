package com.unicarona.privacy;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.unicarona.auth.config.AuthSettings;
import com.unicarona.file.config.StorageSettings;
import com.unicarona.privacy.config.*;
import com.unicarona.privacy.service.*;
import com.unicarona.security.CryptoService;
import com.unicarona.support.AuthTestSettings;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;
import static com.unicarona.privacy.config.PrivacySigningKeys.Purpose.JOURNAL;

/** Real durable files, encryption, signatures and interrupted control states. No database commit is fabricated. */
class PrivacyHoldControlJournalTest {
    @TempDir Path temporary;
    private final ObjectMapper json=new ObjectMapper().registerModule(new JavaTimeModule());private final CryptoService crypto=new CryptoService();
    private final Clock clock=Clock.fixed(Instant.parse("2026-12-01T12:00:00Z"),ZoneOffset.UTC);
    private record Context(MockEnvironment env,PrivacyBackupCatalog catalog,PrivacySigningKeys keys,PrivacyHoldControlJournal journal,Path root,UUID dataset){}
    private Context context(String name)throws Exception {
        Path parent=temporary.resolve(name);var env=new MockEnvironment().withProperty("NODE_ENV","test").withProperty("TOKEN_PEPPER",AuthTestSettings.PEPPER).withProperty("PIN_ENCRYPTION_KEY",AuthTestSettings.KEY).withProperty("JWT_SECRET",AuthTestSettings.SECRET)
            .withProperty("STORAGE_PATH",parent.resolve("active").toString()).withProperty("FRONTEND_PATH",parent.resolve("public").toString()).withProperty("PRIVACY_ERASURE_JOURNAL_PATH",parent.resolve("journal").toString()).withProperty("PRIVACY_OPERATIONS_ROOT",parent.resolve("ops").toString())
            .withProperty("PRIVACY_BACKUP_KEY_ID","b1").withProperty("PRIVACY_BACKUP_KEYS","{\"b1\":\""+"A".repeat(43)+"\"}").withProperty("PRIVACY_JOURNAL_KEY_ID","j1").withProperty("PRIVACY_JOURNAL_KEYS","{\"j1\":\""+"B".repeat(43)+"\"}");
        var auth=new AuthSettings(env);var files=new StorageSettings(env);var keys=new PrivacySigningKeys(env,auth,crypto,json);var catalog=new PrivacyBackupCatalog(new PrivacyOperationsSettings(env,files),new PrivacySettings(env),keys,json,clock);catalog.initialize();UUID dataset=UUID.randomUUID();catalog.locked(()->{catalog.bindDataset(dataset);return null;});
        return new Context(env,catalog,keys,new PrivacyHoldControlJournal(catalog,new PrivacyEvidenceCrypto(env,auth,crypto,json),keys,json,clock),parent.resolve("ops"),dataset);
    }
    private PrivacyHoldControlJournal reopen(Context c){var auth=new AuthSettings(c.env());return new PrivacyHoldControlJournal(c.catalog(),new PrivacyEvidenceCrypto(c.env(),auth,crypto,json),c.keys(),json,clock);}
    private JsonNode state(String value){return json.createObjectNode().put("syntheticDecision",value);}
    private Path record(Context c,PrivacyHoldControlJournal.Prepared prepared){return c.root().resolve("hold-control").resolve(prepared.target().file());}
    @Test void preparationIsEncryptedAndNeverBecomesCommittedMerelyByRestartingTheReader()throws Exception {
        var c=context("pending");assertTrue(c.journal().current().isEmpty());String sensitive="Synthetic private case reason and person@example.test";
        var prepared=c.journal().prepare(state(sensitive));assertFalse(Files.readString(record(c,prepared)).contains(sensitive));assertFalse(Files.readString(c.root().resolve("retention/store.json")).contains("person@example.test"));
        assertThrows(IOException.class,c.journal()::current);var reopened=reopen(c);assertThrows(IOException.class,reopened::current);assertEquals(sensitive,reopened.pending().orElseThrow().state().path("syntheticDecision").asText());
        assertThrows(IOException.class,()->reopened.prepare(state("another decision")));assertThrows(IOException.class,()->reopened.pruneObsolete(10));assertTrue(Files.exists(record(c,prepared)));
        reopened.resolve(prepared,true);assertEquals(sensitive,reopened.current().orElseThrow().state().path("syntheticDecision").asText());assertTrue(reopened.pending().isEmpty());reopened.resolve(prepared,true);assertThrows(IOException.class,()->reopened.resolve(prepared,false));
    }
    @Test void anAcceptedLargeControlCanBeReadBackWithoutChangingThePublicJsonParserLimits()throws Exception {
        var c=context("large-control");int length=15*1024*1024;int before=json.getFactory().streamReadConstraints().getMaxStringLength();var prepared=c.journal().prepare(state("x".repeat(length)));c.journal().resolve(prepared,true);assertEquals(length,c.journal().current().orElseThrow().state().path("syntheticDecision").asText().length());assertEquals(before,json.getFactory().streamReadConstraints().getMaxStringLength());
    }
    @Test void aKnownRollbackRetainsThePreviousDecisionRatherThanPromotingTheProposedRelease()throws Exception {
        var c=context("rollback");var first=c.journal().prepare(state("active hold"));c.journal().resolve(first,true);var proposed=c.journal().prepare(state("release proposed but rolled back"));
        assertEquals(2,proposed.target().sequence());assertThrows(IOException.class,c.journal()::current);c.journal().resolve(proposed,false);assertEquals("active hold",c.journal().current().orElseThrow().state().path("syntheticDecision").asText());assertFalse(Files.exists(record(c,proposed)));
        var initial=context("initial-rollback");var abandoned=initial.journal().prepare(state("never committed"));initial.journal().resolve(abandoned,false);assertTrue(initial.journal().current().isEmpty());assertTrue(initial.journal().pending().isEmpty());
    }
    @Test void missingChangedOrReplacedCommittedControlFailsEvenWhenTheAnchorStillHasAValidSignature()throws Exception {
        var c=context("missing");var first=c.journal().prepare(state("decision one"));c.journal().resolve(first,true);byte[] original=Files.readAllBytes(record(c,first));Files.delete(record(c,first));assertThrows(IOException.class,c.journal()::current);
        Files.write(record(c,first),original);assertTrue(c.journal().current().isPresent());Files.writeString(record(c,first),"{}");assertThrows(IOException.class,c.journal()::current);Files.write(record(c,first),original);
        var second=c.journal().prepare(state("decision two"));c.journal().resolve(second,true);byte[] secondBytes=Files.readAllBytes(record(c,second));Files.writeString(record(c,second),"\n"+new String(secondBytes,java.nio.charset.StandardCharsets.UTF_8));assertThrows(IOException.class,()->c.journal().pruneObsolete(10));assertTrue(Files.exists(record(c,first)));Files.write(record(c,second),original);assertThrows(IOException.class,c.journal()::current);
    }
    @Test void revertingOnlyTheAnchorCannotHideANewerSignedDecisionOrAuthorizeItsDeletion()throws Exception {
        var c=context("rollback-anchor");var first=c.journal().prepare(state("old decision"));c.journal().resolve(first,true);Path store=c.root().resolve("retention/store.json");byte[] old=Files.readAllBytes(store);
        var second=c.journal().prepare(state("new confirmed hold"));c.journal().resolve(second,true);byte[] current=Files.readAllBytes(store);Files.write(store,old);
        assertThrows(IOException.class,c.journal()::current);assertThrows(IOException.class,()->c.journal().pruneObsolete(10));assertTrue(Files.exists(record(c,second)));Files.write(store,current);assertEquals("new confirmed hold",c.journal().current().orElseThrow().state().path("syntheticDecision").asText());
    }
    @Test void signedControlFromAnotherDatasetAndAnUnknownFileAreNeverAdoptedOrErased()throws Exception {
        var a=context("dataset-a");var b=context("dataset-b");var prepared=a.journal().prepare(state("belongs to A"));a.journal().resolve(prepared,true);
        Path destination=Files.createDirectories(b.root().resolve("hold-control")).resolve(prepared.target().file());Files.copy(record(a,prepared),destination);assertThrows(IOException.class,b.journal()::current);assertThrows(IOException.class,()->b.journal().pruneObsolete(10));assertTrue(Files.exists(destination));Files.delete(destination);
        Path unknown=destination.getParent().resolve("operator-note.txt");Files.writeString(unknown,"Synthetic operator data");assertThrows(IOException.class,b.journal()::current);assertThrows(IOException.class,()->b.journal().pruneObsolete(10));assertTrue(Files.exists(unknown));
    }
    @Test void oldHeadsAndInterruptedTemporaryRecordsExpireInBoundedBatchesWithoutDeletingTheCurrentHead()throws Exception {
        var c=context("cleanup");for(int n=0;n<3;n++){var prepared=c.journal().prepare(state("decision "+n));c.journal().resolve(prepared,true);}
        var current=c.journal().current().orElseThrow().head();assertEquals(1,c.journal().pruneObsolete(1));assertEquals(1,c.journal().pruneObsolete(1));assertEquals(0,c.journal().pruneObsolete(1));assertTrue(Files.exists(c.root().resolve("hold-control").resolve(current.file())));
        Path interrupted=c.root().resolve("hold-control/pending-"+UUID.randomUUID()+".tmp");Files.writeString(interrupted,"x".repeat(20000));Files.setLastModifiedTime(interrupted,FileTime.from(clock.instant().minus(Duration.ofDays(2))));assertEquals(1,c.journal().pruneObsolete(1));assertFalse(Files.exists(interrupted));assertEquals("decision 2",c.journal().current().orElseThrow().state().path("syntheticDecision").asText());
    }
    @Test void missingEncryptionKeyCannotBeReplacedWithAnEmptySnapshotOrNewPreparation()throws Exception {
        var c=context("wrong-key");var prepared=c.journal().prepare(state("protected snapshot"));c.journal().resolve(prepared,true);c.env().withProperty("PRIVACY_EVIDENCE_KEY_ID","other").withProperty("PRIVACY_EVIDENCE_KEYS","{\"other\":\""+"cd".repeat(32)+"\"}");var reopened=reopen(c);
        assertThrows(IOException.class,reopened::current);assertThrows(IOException.class,()->reopened.prepare(state("must not replace encrypted evidence")));assertEquals(prepared.target(),c.catalog().store().holdHead());
    }
    @Test void versionOneCatalogRemainsVerifiableAndSuppressionUpdatesPreserveTheNewControlAnchor()throws Exception {
        var c=context("v1");var old=c.catalog().store();String body="retained-copy-store-v1|"+old.id()+"|"+old.datasetId()+"|0|"+old.journalDigest()+"|none";var legacy=json.createObjectNode().put("version",1).put("id",old.id().toString()).put("datasetId",old.datasetId().toString()).put("minimumRestorePointEpochMs",0).put("journalDigest",old.journalDigest()).putNull("pending").put("keyId","j1").put("signature",c.keys().sign(JOURNAL,"j1",body));Files.writeString(c.root().resolve("retention/store.json"),json.writeValueAsString(legacy));assertEquals(1,c.catalog().store().version());
        var prepared=c.journal().prepare(state("current hold"));c.journal().resolve(prepared,true);assertEquals(2,c.catalog().store().version());var auth=new AuthSettings(c.env());var files=new StorageSettings(c.env());var suppression=new PrivacyErasureJournal(files,c.env(),json,crypto,auth,c.keys(),c.catalog(),clock);
        suppression.ensure(UUID.randomUUID(),UUID.randomUUID(),clock.instant(),clock.instant().plusSeconds(86400));assertEquals(1,suppression.entries().size());assertEquals(prepared.target(),c.catalog().store().holdHead());assertEquals("current hold",c.journal().current().orElseThrow().state().path("syntheticDecision").asText());
    }
}
