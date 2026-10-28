package com.unicarona.privacy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.unicarona.auth.config.AuthSettings;
import com.unicarona.file.config.StorageSettings;
import com.unicarona.privacy.config.*;
import com.unicarona.privacy.service.*;
import com.unicarona.security.CryptoService;
import com.unicarona.support.AuthTestSettings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;
import static com.unicarona.privacy.config.PrivacySigningKeys.Purpose.*;

class PrivacyCopiesTest {
    @TempDir Path temporary;
    private final ObjectMapper json=new ObjectMapper().registerModule(new JavaTimeModule()).disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final CryptoService crypto=new CryptoService();
    private final Clock clock=Clock.fixed(Instant.parse("2026-12-01T12:00:00Z"),ZoneOffset.UTC);
    private record Context(MockEnvironment env,PrivacySigningKeys keys,PrivacyBackupCatalog catalog,PrivacyErasureJournal journal,Path root,Path journalRoot){}
    private MockEnvironment environment(Path root,boolean enabled){return new MockEnvironment().withProperty("NODE_ENV","test").withProperty("TOKEN_PEPPER",AuthTestSettings.PEPPER).withProperty("PIN_ENCRYPTION_KEY",AuthTestSettings.KEY).withProperty("JWT_SECRET",AuthTestSettings.SECRET)
        .withProperty("STORAGE_PATH",root.resolve("active-files").toString()).withProperty("FRONTEND_PATH",root.resolve("public").toString()).withProperty("PRIVACY_ERASURE_JOURNAL_PATH",root.resolve("journal").toString())
        .withProperty("PRIVACY_OPERATIONS_ROOT",enabled?root.resolve("ops").toString():"").withProperty("PRIVACY_BACKUP_KEY_ID","b1").withProperty("PRIVACY_BACKUP_KEYS","{\"b1\":\""+"A".repeat(43)+"\"}")
        .withProperty("PRIVACY_JOURNAL_KEY_ID","j1").withProperty("PRIVACY_JOURNAL_KEYS","{\"j1\":\""+"B".repeat(43)+"\"}");}
    private Context context(String name)throws Exception {return context(environment(temporary.resolve(name),true),true);}
    private UUID bind(Context c)throws Exception {UUID dataset=UUID.randomUUID();c.catalog().locked(()->{c.catalog().bindDataset(dataset);return null;});return dataset;}
    private Context context(MockEnvironment env,boolean initialize)throws Exception {
        return context(env,initialize,clock);
    }
    private Context context(MockEnvironment env,boolean initialize,Clock testClock)throws Exception {
        var auth=new AuthSettings(env);var files=new StorageSettings(env);var keys=new PrivacySigningKeys(env,auth,crypto,json);var operations=new PrivacyOperationsSettings(env,files);
        var catalog=new PrivacyBackupCatalog(operations,new PrivacySettings(env),keys,json,testClock);
        if(initialize)catalog.initialize();
        return new Context(env,keys,catalog,new PrivacyErasureJournal(files,env,json,crypto,auth,keys,catalog,testClock),operations.enabled()?operations.root():temporary,Path.of(env.getProperty("PRIVACY_ERASURE_JOURNAL_PATH")));
    }
    // Protocol-only fixture: DB-backed completion and retirement are exercised separately in ITs.
    private int prune(Context c,int batch)throws Exception {return c.journal().pruneExpired(batch,new PrivacyErasureJournal.RetirementVerifier(){
        public PrivacyErasureJournal.Verification account(PrivacyErasureJournal.Entry e){return PrivacyErasureJournal.Verification.COMPLETE;}
        public PrivacyErasureJournal.Verification file(PrivacyErasureJournal.FileEntry e){return PrivacyErasureJournal.Verification.COMPLETE;}
        public PrivacyErasureJournal.Verification orphan(PrivacyErasureJournal.OrphanEntry e){return PrivacyErasureJournal.Verification.COMPLETE;}
    });}
    private Path copy(Context c,String name)throws Exception {return Files.createDirectories(c.root().resolve("backups").resolve(name));}
    @Test void orphanSuppressionHasNoInventedOwnerAndDetectsMissingChangedOrForeignEntries()throws Exception {
        var c=context("orphan-integrity");UUID dataset=bind(c),file=UUID.randomUUID();Instant at=clock.instant();var entry=c.journal().ensureOrphan(file,3,"a".repeat(64),at,at.plus(Duration.ofDays(37)));assertEquals(dataset,entry.dataset());assertEquals("OrphanFileErasureEntry[redacted]",entry.toString());assertTrue(c.journal().entries().isEmpty());assertTrue(c.journal().fileEntries().isEmpty());assertEquals(List.of(entry),c.journal().orphanEntries());assertEquals(entry,c.journal().ensureOrphan(file,3,"a".repeat(64),at.plusSeconds(50),at.plus(Duration.ofDays(60))));assertThrows(IOException.class,()->c.journal().ensureOrphan(file,3,"b".repeat(64),at,at.plus(Duration.ofDays(37))));
        Path path=c.journalRoot().resolve("orphan-"+file);String saved=Files.readString(path);assertFalse(saved.contains("owner"));Files.delete(path);assertThrows(IOException.class,c.journal()::entries);Files.writeString(path,saved);assertEquals(1,c.journal().orphanEntries().size());Files.writeString(path,saved.replace("\"bytes\":3","\"bytes\":4"));assertThrows(IOException.class,c.journal()::orphanEntries);Files.writeString(path,saved);
        var foreign=context("orphan-other-dataset");bind(foreign);Files.createDirectories(foreign.journalRoot());Files.copy(path,foreign.journalRoot().resolve(path.getFileName()));assertThrows(IOException.class,foreign.journal()::orphanEntries);
    }
    @Test void orphanSuppressionUsesTheSamePhysicalBackupBarrierAndDoesNotStarveInTheMixedQueue()throws Exception {
        var c=context("orphan-expiry");bind(c);Instant at=clock.instant().minus(Duration.ofDays(80));UUID orphan=UUID.randomUUID();var pending=c.journal().ensureOrphan(orphan,0,"c".repeat(64),at,at.plus(Duration.ofDays(37)));
        Path old=copy(c,UUID.randomUUID().toString());Files.writeString(old.resolve("orphan"),"Synthetic old bytes");register(c,old,clock.instant().minus(Duration.ofDays(40)));assertEquals(0,prune(c,1));var confirmed=c.journal().confirmOrphanAbsent(pending);assertEquals(at,confirmed.preparedAt());assertEquals(pending.suppressionUntil(),confirmed.suppressionUntil());assertEquals(clock.instant(),confirmed.confirmedAbsentAt());assertEquals(0,prune(c,1),"A copy after preparation but before confirmed erasure still blocks retirement");c.catalog().locked(()->c.catalog().prune(100));
        c.journal().ensureFile(UUID.randomUUID(),UUID.randomUUID(),at.plusSeconds(10),at.plus(Duration.ofDays(37)));c.journal().ensure(UUID.randomUUID(),UUID.randomUUID(),at.plusSeconds(20),at.plus(Duration.ofDays(37)));assertEquals(1,prune(c,1));assertTrue(c.journal().orphanEntries().isEmpty());assertEquals(1,c.journal().fileEntries().size());assertEquals(1,c.journal().entries().size());assertEquals(clock.instant().toEpochMilli(),c.catalog().store().minimumRestorePointEpochMs());
    }
    @Test void orphanSignatureRotationPreservesContentIdentityAndTheOriginalDeadline()throws Exception {
        var c=context("orphan-rekey");bind(c);Instant at=clock.instant();var before=c.journal().confirmOrphanAbsent(c.journal().ensureOrphan(UUID.randomUUID(),4,"d".repeat(64),at,at.plus(Duration.ofDays(37))));c.env().withProperty("PRIVACY_JOURNAL_KEY_ID","j2").withProperty("PRIVACY_JOURNAL_KEYS","{\"j1\":\""+"B".repeat(43)+"\",\"j2\":\""+"D".repeat(43)+"\"}");var next=context(c.env(),false);assertEquals(1,next.journal().rekey(1));c.env().withProperty("PRIVACY_JOURNAL_KEYS","{\"j2\":\""+"D".repeat(43)+"\"}");var after=context(c.env(),false).journal().orphanEntries().getFirst();assertEquals(before.dataset(),after.dataset());assertEquals(before.file(),after.file());assertEquals(before.sha256(),after.sha256());assertEquals(before.preparedAt(),after.preparedAt());assertEquals(before.suppressionUntil(),after.suppressionUntil());assertEquals(before.confirmedAbsentAt(),after.confirmedAbsentAt());assertEquals("j2",after.keyId());
    }
    @Test void interruptedOrphanPublicationAndRetirementRecoverThroughTheIndexedProtocol()throws Exception {
        var c=context("orphan-interruption");UUID dataset=bind(c),file=UUID.randomUUID();Instant at=clock.instant().minus(Duration.ofDays(40)),until=at.plus(Duration.ofDays(37));String key=c.keys().current(JOURNAL),sha="e".repeat(64),name="orphan-"+file;String body="privacy-orphan-file-erasure-v1|"+dataset+"|"+file+"|3|"+sha+"|"+at+"|"+until+"|pending";var entry=new PrivacyErasureJournal.OrphanEntry(1,dataset,file,3,sha,at,until,null,key,c.keys().sign(JOURNAL,key,body));String encoded=json.writeValueAsString(entry),empty=PrivacyBackupCatalog.sha(""),after=PrivacyBackupCatalog.sha(name+":"+PrivacyBackupCatalog.sha(encoded)+"\n");c.catalog().locked(()->{c.catalog().beginJournalChange(new PrivacyBackupCatalog.PendingJournal("put",name,empty,after,encoded),Instant.EPOCH);return null;});assertEquals(List.of(entry),c.journal().orphanEntries());assertNull(c.catalog().store().pending());
        c.catalog().locked(()->{c.catalog().beginJournalChange(new PrivacyBackupCatalog.PendingJournal("remove",name,after,empty,""),at);return null;});assertTrue(c.journal().orphanEntries().isEmpty());assertNull(c.catalog().store().pending());assertEquals(at.toEpochMilli(),c.catalog().store().minimumRestorePointEpochMs());
    }
    @Test void orphanIntentDoesNotBecomeConfirmedWhileBytesExistAndReintroducedBytesInvalidateTheWitness()throws Exception {
        var c=context("orphan-absence-witness");bind(c);UUID file=UUID.randomUUID();byte[] content={7,8,9};String sha=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(content));Path active=Files.createDirectories(Path.of(c.env().getProperty("STORAGE_PATH"))).resolve(file.toString());Files.write(active,content);Instant at=clock.instant().minus(Duration.ofDays(40));var pending=c.journal().ensureOrphan(file,content.length,sha,at,at.plus(Duration.ofDays(37)));assertThrows(IOException.class,()->c.journal().confirmOrphanAbsent(pending));assertEquals(0,prune(c,10));assertTrue(Files.exists(active));Files.delete(active);var confirmed=c.journal().confirmOrphanAbsent(pending);assertNotNull(confirmed.confirmedAbsentAt());Files.write(active,content);assertEquals(0,prune(c,10));assertNull(c.journal().orphanEntries().getFirst().confirmedAbsentAt());assertArrayEquals(content,Files.readAllBytes(active));
    }
    private PrivacyBackupCatalog.Copy register(Context c,Path copy,Instant created)throws Exception{return c.catalog().locked(()->c.catalog().register("backups",copy.getFileName().toString(),created,created,false));}
    @Test void cleanupIsBoundedPreservesActiveDataAndDoesNotExtendAnExistingDeadline()throws Exception {
        var c=context("bounded");Path target=copy(c,UUID.randomUUID().toString());Files.writeString(target.resolve("one"),"Synthetic retained bytes");Files.writeString(target.resolve("two"),"Synthetic retained bytes");
        Path active=Files.createDirectories(temporary.resolve("bounded/active-files"));Files.writeString(active.resolve("keep"),"Active data");
        var first=register(c,target,clock.instant().minus(Duration.ofDays(40)));var again=register(c,target,clock.instant());assertEquals(first.expiresAtEpochMs(),again.expiresAtEpochMs());
        var partial=c.catalog().locked(()->c.catalog().prune(1));assertEquals(1,partial.deletedPaths());assertEquals(0,partial.completedCopies());assertTrue(Files.exists(target));
        c.catalog().locked(()->c.catalog().prune(20));assertFalse(Files.exists(target));assertEquals("Active data",Files.readString(active.resolve("keep")));assertEquals(1,c.catalog().inventory().copies().size());
    }
    @Test void unknownCopyAndTamperedRecordBlockCleanupAndSuppressionExpiry()throws Exception {
        var c=context("unknown");Path target=copy(c,UUID.randomUUID().toString());Files.writeString(target.resolve("proof"),"Keep until verified");var recorded=register(c,target,clock.instant().minus(Duration.ofDays(40)));
        Path unknown=copy(c,"unregistered");assertThrows(IOException.class,c.catalog()::inventory);assertThrows(IOException.class,()->c.catalog().locked(()->c.catalog().prune(20)));assertTrue(Files.exists(target.resolve("proof")));Files.delete(unknown);
        Path record=c.root().resolve("retention/copies").resolve(recorded.id()+".json");var value=json.readTree(Files.readString(record));((com.fasterxml.jackson.databind.node.ObjectNode)value).put("name","another-resource");Files.writeString(record,json.writeValueAsString(value));
        assertThrows(IOException.class,c.catalog()::inventory);assertTrue(Files.exists(target.resolve("proof")));
    }
    @Test void invalidPathsAndActiveOverlapAreRejected()throws Exception {
        var c=context("paths");assertThrows(IOException.class,()->c.catalog().register("data","postgres",clock.instant(),clock.instant(),true));assertThrows(IOException.class,()->c.catalog().register("backups","../data",clock.instant(),clock.instant(),true));
        var env=environment(temporary.resolve("overlap"),true).withProperty("STORAGE_PATH",temporary.resolve("overlap/ops/backups/live").toString());assertThrows(IllegalStateException.class,()->new PrivacyOperationsSettings(env,new StorageSettings(env)));
        assertThrows(IllegalStateException.class,()->new PrivacyOperationsSettings(environment(temporary.resolve("root"),true).withProperty("PRIVACY_OPERATIONS_ROOT",Path.of("/").toAbsolutePath().toString()),new StorageSettings(environment(temporary.resolve("root"),true))));
    }
    @Test void independentHoldStoresCannotOverlapActivePublicOrSuppressionData(){
        for(String container:List.of("hold-control","hold-control-files"))for(String property:List.of("STORAGE_PATH","FRONTEND_PATH","PRIVACY_ERASURE_JOURNAL_PATH")){
            Path parent=temporary.resolve(container+"-"+property.toLowerCase(java.util.Locale.ROOT));var env=environment(parent,true).withProperty(property,parent.resolve("ops").resolve(container).resolve("nested").toString());assertThrows(IllegalStateException.class,()->new PrivacyOperationsSettings(env,new StorageSettings(env)));
        }
    }
    @Test void nestedJunctionOrSymlinkNeverDeletesItsDestination()throws Exception {
        var c=context("links");Path target=copy(c,UUID.randomUUID().toString());Path elsewhere=Files.createDirectory(temporary.resolve("outside-copy"));Path sentinel=Files.writeString(elsewhere.resolve("keep"),"External synthetic bytes");Path link=target.resolve("redirect");
        if(System.getProperty("os.name").startsWith("Windows")){
            assertTrue(link.toAbsolutePath().normalize().startsWith(temporary.toAbsolutePath().normalize()));
            var process=new ProcessBuilder(System.getenv("ComSpec"),"/c","mklink","/J",link.toString(),elsewhere.toString()).redirectErrorStream(true).start();process.getInputStream().readAllBytes();assertEquals(0,process.waitFor());
        }else Files.createSymbolicLink(link,elsewhere);
        register(c,target,clock.instant().minus(Duration.ofDays(40)));assertThrows(IOException.class,()->c.catalog().locked(()->c.catalog().prune(50)));assertEquals("External synthetic bytes",Files.readString(sentinel));
    }
    @Test void journalCannotExpireUntilEveryEarlierCopyIsPhysicallyGone()throws Exception {
        var c=context("barrier");Instant prepared=clock.instant().minus(Duration.ofDays(38));UUID owner=UUID.randomUUID(),closure=UUID.randomUUID();
        c.journal().ensure(owner,closure,prepared,prepared.plus(Duration.ofDays(37)));Path target=copy(c,UUID.randomUUID().toString());Files.writeString(target.resolve("profile"),"Old synthetic profile");register(c,target,prepared.minus(Duration.ofDays(2)));
        c.journal().confirmAccount(c.journal().entries().getFirst());
        assertEquals(0,prune(c,20));assertTrue(Files.exists(c.journalRoot().resolve(closure.toString())));
        c.catalog().locked(()->c.catalog().prune(20));assertEquals(1,prune(c,20));assertTrue(c.journal().entries().isEmpty());assertEquals(clock.instant().toEpochMilli(),c.catalog().store().minimumRestorePointEpochMs());
        String minimal=Files.readString(c.root().resolve("retention/store.json"));assertFalse(minimal.contains(owner.toString()));assertFalse(minimal.contains(closure.toString()));assertNull(c.catalog().store().pending());
    }
    @Test void missingJournalFileIsDetectedEvenWhenTheOtherSignaturesAreValid()throws Exception {
        var c=context("missing");UUID first=UUID.randomUUID(),second=UUID.randomUUID();Instant now=clock.instant();c.journal().ensure(UUID.randomUUID(),first,now,now.plusSeconds(86400));c.journal().ensure(UUID.randomUUID(),second,now,now.plusSeconds(86400));
        Path path=c.journalRoot().resolve(first.toString());byte[] saved=Files.readAllBytes(path);Files.delete(path);assertThrows(IOException.class,c.journal()::entries);Files.write(path,saved);assertEquals(2,c.journal().entries().size());
    }
    private String digest(Map<String,String> files)throws Exception {var value=new StringBuilder();for(var e:new TreeMap<>(files).entrySet())value.append(e.getKey()).append(':').append(PrivacyBackupCatalog.sha(e.getValue())).append('\n');return PrivacyBackupCatalog.sha(value.toString());}
    @Test void interruptedJournalPublicationAndRetirementRecoverOnEitherSideOfTheFileWrite()throws Exception {
        var c=context("recovery");Instant at=clock.instant().minus(Duration.ofDays(40)),until=at.plus(Duration.ofDays(37));UUID closure=UUID.randomUUID(),owner=UUID.randomUUID();String name=closure.toString(),key=c.keys().current(JOURNAL);
        String body="privacy-erasure-journal-v2|"+owner+"|"+closure+"|"+at+"|"+until;
        String payload=json.writeValueAsString(new PrivacyErasureJournal.Entry(2,owner,closure,at,until,key,c.keys().sign(JOURNAL,key,body)));
        String empty=digest(Map.of()),populated=digest(Map.of(name,payload));
        c.catalog().locked(()->{c.catalog().beginJournalChange(new PrivacyBackupCatalog.PendingJournal("put",name,empty,populated,payload),Instant.EPOCH);return null;});
        assertEquals(1,c.journal().entries().size());assertNull(c.catalog().store().pending());
        c.catalog().locked(()->{c.catalog().beginJournalChange(new PrivacyBackupCatalog.PendingJournal("remove",name,populated,empty,""),at);return null;});
        Files.delete(c.journalRoot().resolve(name));assertEquals(0,c.journal().entries().size());assertNull(c.catalog().store().pending());assertEquals(at.toEpochMilli(),c.catalog().store().minimumRestorePointEpochMs());
        // Opposite crash boundaries: file already published, and retirement not yet applied to its file.
        c.catalog().locked(()->{c.catalog().beginJournalChange(new PrivacyBackupCatalog.PendingJournal("put",name,empty,populated,payload),Instant.EPOCH);return null;});
        Files.writeString(c.journalRoot().resolve(name),payload);assertEquals(1,c.journal().entries().size());assertNull(c.catalog().store().pending());
        c.catalog().locked(()->{c.catalog().beginJournalChange(new PrivacyBackupCatalog.PendingJournal("remove",name,populated,empty,""),at);return null;});
        assertEquals(0,c.journal().entries().size());assertFalse(Files.exists(c.journalRoot().resolve(name)));assertNull(c.catalog().store().pending());
    }
    @Test void purposeKeysRotateWithoutChangingExpiryOrRequiringAuthenticationKeyRotation()throws Exception {
        var first=context("rotation");Path target=copy(first,UUID.randomUUID().toString());Files.writeString(target.resolve("keep"),"Current backup");var original=register(first,target,clock.instant());UUID closure=UUID.randomUUID(),owner=UUID.randomUUID();Instant at=clock.instant();first.journal().ensure(owner,closure,at,at.plus(Duration.ofDays(37)));
        var env=first.env().withProperty("PRIVACY_BACKUP_KEY_ID","b2").withProperty("PRIVACY_BACKUP_KEYS","{\"b1\":\""+"A".repeat(43)+"\",\"b2\":\""+"C".repeat(43)+"\"}").withProperty("PRIVACY_JOURNAL_KEY_ID","j2").withProperty("PRIVACY_JOURNAL_KEYS","{\"j1\":\""+"B".repeat(43)+"\",\"j2\":\""+"D".repeat(43)+"\"}");
        var rotated=context(env,false);rotated.catalog().locked(()->rotated.catalog().rekey(10));assertEquals(1,rotated.journal().rekey(10));assertEquals(original.expiresAtEpochMs(),rotated.catalog().inventory().copies().getFirst().expiresAtEpochMs());
        env.withProperty("PRIVACY_BACKUP_KEYS","{\"b2\":\""+"C".repeat(43)+"\"}").withProperty("PRIVACY_JOURNAL_KEYS","{\"j2\":\""+"D".repeat(43)+"\"}");var retired=context(env,false);
        assertEquals("j2",retired.journal().entries().getFirst().keyId());assertEquals("b2",retired.catalog().inventory().copies().getFirst().keyId());assertEquals(AuthTestSettings.PEPPER,new AuthSettings(env).pepper());
    }
    @Test void oldJournalFormatRemainsReadableAndCanBeResigned()throws Exception {
        Path base=temporary.resolve("legacy");var c=context(environment(base,false),false);Files.createDirectories(c.journalRoot());UUID owner=UUID.randomUUID(),closure=UUID.randomUUID();Instant at=clock.instant(),until=at.plus(Duration.ofDays(37));
        var old=new LinkedHashMap<String,Object>();old.put("version",1);old.put("owner",owner);old.put("closure",closure);old.put("preparedAt",at);old.put("suppressionUntil",until);old.put("signature",crypto.hmac("privacy-erasure-journal-v1|"+owner+"|"+closure+"|"+at+"|"+until,AuthTestSettings.PEPPER));Files.writeString(c.journalRoot().resolve(closure.toString()),json.writeValueAsString(old));
        assertEquals(1,c.journal().entries().getFirst().version());assertEquals(1,c.journal().rekey(10));assertEquals(3,c.journal().entries().getFirst().version());assertEquals(until,c.journal().entries().getFirst().suppressionUntil());assertEquals(0,prune(c,10));
    }
    @Test void reservationsBlockExpiryUntilFinalizedAndCannotReuseAnExistingCopyName()throws Exception {
        var c=context("reserve");String name=UUID.randomUUID().toString();Path target=copy(c,name);var reservation=c.catalog().locked(()->c.catalog().reserve("backups",name));
        assertTrue(c.catalog().hasSurvivingCopyAtOrBefore(clock.instant().minus(Duration.ofDays(100))));
        c.catalog().locked(()->c.catalog().finalizeSnapshot(reservation.id(),clock.instant()));assertFalse(c.catalog().hasSurvivingCopyAtOrBefore(clock.instant().minus(Duration.ofDays(100))));assertThrows(IOException.class,()->c.catalog().locked(()->c.catalog().reserve("backups",name)));assertTrue(Files.exists(target));
    }
    @Test void productionAndMalformedKeyRingsCannotFallBackSilently()throws Exception {
        var env=environment(temporary.resolve("keys"),false);var auth=new AuthSettings(env);
        env.withProperty("PRIVACY_BACKUP_KEY_ID","").withProperty("PRIVACY_BACKUP_KEYS","").withProperty("NODE_ENV","production");assertThrows(IllegalStateException.class,()->new PrivacySigningKeys(env,auth,crypto,json));
        env.withProperty("NODE_ENV","test").withProperty("PRIVACY_BACKUP_KEY_ID","b").withProperty("PRIVACY_BACKUP_KEYS","{\"b\":\"short\"}");assertThrows(IllegalStateException.class,()->new PrivacySigningKeys(env,auth,crypto,json));
        env.withProperty("PRIVACY_BACKUP_KEYS","{\"b\":\""+"B".repeat(43)+"\"}");assertThrows(IllegalStateException.class,()->new PrivacySigningKeys(env,auth,crypto,json));
    }
    @Test void newerSnapshotsDoNotKeepAnExpiredUserJournalForever()throws Exception {
        var c=context("newer");Instant prepared=clock.instant().minus(Duration.ofDays(38));c.journal().ensure(UUID.randomUUID(),UUID.randomUUID(),prepared,prepared.plus(Duration.ofDays(37)));
        c.journal().confirmAccount(c.journal().entries().getFirst());Path newer=copy(c,UUID.randomUUID().toString());Files.writeString(newer.resolve("current"),"Newer synthetic state");register(c,newer,clock.instant().plusSeconds(1));
        assertEquals(1,prune(c,10));assertTrue(Files.exists(newer.resolve("current")));assertEquals(1,c.catalog().inventory().present());
    }
    @Test void missingRetainedKeyFailsClosedAndDoesNotRewriteAnyEvidence()throws Exception {
        var c=context("retired-too-early");UUID closure=UUID.randomUUID();Instant at=clock.instant();c.journal().ensure(UUID.randomUUID(),closure,at,at.plus(Duration.ofDays(37)));String saved=Files.readString(c.root().resolve("retention/store.json"));
        c.env().withProperty("PRIVACY_JOURNAL_KEY_ID","j2").withProperty("PRIVACY_JOURNAL_KEYS","{\"j2\":\""+"D".repeat(43)+"\"}");var missing=context(c.env(),false);
        assertThrows(IllegalStateException.class,missing.journal()::entries);assertEquals(saved,Files.readString(c.root().resolve("retention/store.json")));
    }
    @Test void interruptedTemporaryFilesExpireWithoutBeingMistakenForCommittedJournalEntries()throws Exception {
        var c=context("temporary");Files.createDirectories(c.journalRoot());Path staged=c.journalRoot().resolve("pending-"+UUID.randomUUID()+".tmp");Files.writeString(staged,"Synthetic uncommitted bytes");Files.setLastModifiedTime(staged,java.nio.file.attribute.FileTime.from(clock.instant().minus(Duration.ofDays(2))));
        assertTrue(c.journal().entries().isEmpty());prune(c,10);assertFalse(Files.exists(staged));
        Path metadata=c.root().resolve("retention/copies/pending-"+UUID.randomUUID()+".tmp");Files.writeString(metadata,"Synthetic uncommitted metadata");Files.setLastModifiedTime(metadata,java.nio.file.attribute.FileTime.from(clock.instant().minus(Duration.ofDays(2))));
        c.catalog().locked(()->c.catalog().prune(10));assertFalse(Files.exists(metadata));assertEquals(0,c.catalog().inventory().present());
    }
    @Test void completedOrFreshRecordsCannotStarveOtherExpiredCopiesInASmallBatch()throws Exception {
        var c=context("fairness");Path old=copy(c,UUID.randomUUID().toString()),another=copy(c,UUID.randomUUID().toString()),fresh=copy(c,UUID.randomUUID().toString());
        for(Path path:List.of(old,another,fresh))Files.writeString(path.resolve("bytes"),"Synthetic bytes");
        register(c,old,clock.instant().minus(Duration.ofDays(70)));register(c,another,clock.instant().minus(Duration.ofDays(40)));register(c,fresh,clock.instant());
        for(int n=0;n<12;n++)c.catalog().locked(()->c.catalog().prune(1));
        assertFalse(Files.exists(old));assertFalse(Files.exists(another));assertTrue(Files.exists(fresh.resolve("bytes")));assertEquals(1,c.catalog().inventory().present());
    }
    @Test void fileOnlyOperatorNeedsNoJwtPaymentSmtpOrEncryptionCredentials()throws Exception {
        Path root=temporary.resolve("file-operator");var env=new MockEnvironment().withProperty("NODE_ENV","test").withProperty("STORAGE_PATH",root.resolve("active").toString()).withProperty("FRONTEND_PATH",root.resolve("public").toString())
            .withProperty("PRIVACY_OPERATIONS_ROOT",root.resolve("ops").toString()).withProperty("PRIVACY_ERASURE_JOURNAL_PATH",root.resolve("journal").toString())
            .withProperty("PRIVACY_BACKUP_KEY_ID","b1").withProperty("PRIVACY_BACKUP_KEYS","{\"b1\":\""+"A".repeat(43)+"\"}").withProperty("PRIVACY_JOURNAL_KEY_ID","j1").withProperty("PRIVACY_JOURNAL_KEYS","{\"j1\":\""+"B".repeat(43)+"\"}");
        var keys=PrivacySigningKeys.forOperations(env,crypto,json);var files=new StorageSettings(env);var catalog=new PrivacyBackupCatalog(new PrivacyOperationsSettings(env,files),new PrivacySettings(env),keys,json,clock);catalog.initialize();
        var journal=PrivacyErasureJournal.forOperations(files,env,json,crypto,keys,catalog,clock);assertTrue(journal.entries().isEmpty());assertNull(env.getProperty("JWT_SECRET"));assertNull(env.getProperty("PIN_ENCRYPTION_KEY"));assertNull(env.getProperty("MERCADO_PAGO_ACCESS_TOKEN"));assertNull(env.getProperty("SMTP_PASS"));
    }
    @Test void submillisecondConfirmationRoundsTheRecoveryFloorConservatively()throws Exception {
        var c=context(environment(temporary.resolve("precision"),true),true,Clock.offset(clock,Duration.ofNanos(123456)));Instant prepared=clock.instant().minus(Duration.ofDays(38));c.journal().ensure(UUID.randomUUID(),UUID.randomUUID(),prepared,prepared.plus(Duration.ofDays(37)));c.journal().confirmAccount(c.journal().entries().getFirst());
        assertEquals(1,prune(c,10));assertEquals(clock.instant().toEpochMilli()+1,c.catalog().store().minimumRestorePointEpochMs());
    }
    @Test void concurrentApiAndWorkerChecksWaitForTheSameOperationsLock()throws Exception {
        var c=context("concurrent");var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)){
            var first=executor.submit(()->c.catalog().locked(()->{entered.countDown();try{assertTrue(release.await(3,java.util.concurrent.TimeUnit.SECONDS));}catch(InterruptedException interrupted){throw new IOException(interrupted);}return true;}));
            assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS));var second=executor.submit(()->c.catalog().locked(()->true));
            try{assertThrows(java.util.concurrent.TimeoutException.class,()->second.get(100,java.util.concurrent.TimeUnit.MILLISECONDS));}finally{release.countDown();}
            assertTrue(first.get(3,java.util.concurrent.TimeUnit.SECONDS));assertTrue(second.get(3,java.util.concurrent.TimeUnit.SECONDS));
        }
    }
    @Test void individualFileSuppressionIsSignedIndexedSeparateFromAccountClosureAndDoesNotExtendOnRetry()throws Exception {
        var c=context("file-entry");UUID owner=UUID.randomUUID(),file=UUID.randomUUID();Instant at=clock.instant(),until=at.plus(Duration.ofDays(37));
        var entry=c.journal().ensureFile(owner,file,at,until);assertEquals("FileErasureJournalEntry[redacted]",entry.toString());assertTrue(c.journal().entries().isEmpty());assertEquals(List.of(entry),c.journal().fileEntries());
        assertEquals(entry,c.journal().ensureFile(owner,file,at.plusSeconds(60),until.plusSeconds(60)));assertThrows(IOException.class,()->c.journal().ensureFile(UUID.randomUUID(),file,at,until));
        Path path=c.journalRoot().resolve("file-"+file);String original=Files.readString(path);Files.delete(path);assertThrows(IOException.class,c.journal()::entries);
        Files.writeString(path,original);assertEquals(entry,c.journal().fileEntries().getFirst());
        Files.writeString(path,original.replace(owner.toString(),UUID.randomUUID().toString()));assertThrows(IOException.class,c.journal()::fileEntries);
    }
    @Test void fileJournalRetirementWaitsForOldCopiesAndRaisesTheSameRestoreFloor()throws Exception {
        var c=context("file-expiry");Instant prepared=clock.instant().minus(Duration.ofDays(38));UUID file=UUID.randomUUID();
        c.journal().ensureFile(UUID.randomUUID(),file,prepared,prepared.plus(Duration.ofDays(37)));Path old=copy(c,UUID.randomUUID().toString());Files.writeString(old.resolve("bytes"),"Synthetic earlier file snapshot");register(c,old,prepared.minusSeconds(60));
        c.journal().confirmFileAbsent(c.journal().fileEntries().getFirst());
        assertEquals(0,prune(c,10));assertEquals(1,c.journal().fileEntries().size());
        c.catalog().locked(()->c.catalog().prune(100));assertFalse(Files.exists(old));assertEquals(1,prune(c,10));assertTrue(c.journal().fileEntries().isEmpty());assertEquals(clock.instant().toEpochMilli(),c.catalog().store().minimumRestorePointEpochMs());
    }
    @Test void fileSuppressionKeyRotationPreservesDatesAndTheOldKeyCanThenBeRetired()throws Exception {
        var c=context("file-rotation");Instant prepared=clock.instant();var before=c.journal().ensureFile(UUID.randomUUID(),UUID.randomUUID(),prepared,prepared.plus(Duration.ofDays(37)));
        c.env().withProperty("PRIVACY_JOURNAL_KEY_ID","j2").withProperty("PRIVACY_JOURNAL_KEYS","{\"j1\":\""+"B".repeat(43)+"\",\"j2\":\""+"D".repeat(43)+"\"}");var rotated=context(c.env(),false);assertEquals(1,rotated.journal().rekey(10));
        c.env().withProperty("PRIVACY_JOURNAL_KEYS","{\"j2\":\""+"D".repeat(43)+"\"}");var retired=context(c.env(),false);var after=retired.journal().fileEntries().getFirst();assertEquals("j2",after.keyId());assertEquals(before.preparedAt(),after.preparedAt());assertEquals(before.suppressionUntil(),after.suppressionUntil());
    }
    @Test void interruptedFileJournalChangesRecoverBeforeAndAfterWritingOrRemovingTheirEntry()throws Exception {
        for(boolean alreadyWritten:List.of(false,true)){
            var c=context("file-crash-"+alreadyWritten);Instant at=clock.instant().minus(Duration.ofDays(40)),until=at.plus(Duration.ofDays(37));UUID owner=UUID.randomUUID(),file=UUID.randomUUID();String name="file-"+file,key=c.keys().current(JOURNAL);
            String body="privacy-file-erasure-v1|"+owner+"|"+file+"|"+at+"|"+until;
            String payload=json.writeValueAsString(new PrivacyErasureJournal.FileEntry(1,owner,file,at,until,key,c.keys().sign(JOURNAL,key,body)));String empty=digest(Map.of()),populated=digest(Map.of(name,payload));
            c.catalog().locked(()->{c.catalog().beginJournalChange(new PrivacyBackupCatalog.PendingJournal("put",name,empty,populated,payload),Instant.EPOCH);return null;});
            if(alreadyWritten){Files.createDirectories(c.journalRoot());Files.writeString(c.journalRoot().resolve(name),payload);}
            assertEquals(file,c.journal().fileEntries().getFirst().file());assertTrue(c.journal().entries().isEmpty());assertNull(c.catalog().store().pending());
            c.catalog().locked(()->{c.catalog().beginJournalChange(new PrivacyBackupCatalog.PendingJournal("remove",name,populated,empty,""),at);return null;});
            if(alreadyWritten)Files.delete(c.journalRoot().resolve(name));assertTrue(c.journal().fileEntries().isEmpty());assertNull(c.catalog().store().pending());assertEquals(at.toEpochMilli(),c.catalog().store().minimumRestorePointEpochMs());
        }
    }
    @Test void mixedFileAndUserJournalRetirementSharesOneOldestFirstBatch()throws Exception {
        var c=context("file-fairness");Instant at=clock.instant().minus(Duration.ofDays(45));
        c.journal().ensure(UUID.randomUUID(),UUID.randomUUID(),at.plusSeconds(60),at.plus(Duration.ofDays(37)));
        c.journal().ensureFile(UUID.randomUUID(),UUID.randomUUID(),at,at.plus(Duration.ofDays(37)));
        c.journal().confirmAccount(c.journal().entries().getFirst());c.journal().confirmFileAbsent(c.journal().fileEntries().getFirst());
        assertEquals(1,prune(c,1));assertTrue(c.journal().fileEntries().isEmpty());assertEquals(1,c.journal().entries().size());
    }
    @Test void pendingAccountAndKnownFileCannotExpireOrBecomeConfirmedByRekeyOrFileOnlyTooling()throws Exception {
        var c=context("pending-confirmation");Instant at=clock.instant().minus(Duration.ofDays(80));c.journal().ensure(UUID.randomUUID(),UUID.randomUUID(),at,at.plus(Duration.ofDays(37)));c.journal().ensureFile(UUID.randomUUID(),UUID.randomUUID(),at,at.plus(Duration.ofDays(37)));
        assertNull(c.journal().entries().getFirst().confirmedAt());assertNull(c.journal().fileEntries().getFirst().confirmedAt());assertEquals(0,prune(c,100));assertThrows(IOException.class,()->c.journal().pruneExpired(100));c.journal().rekey(100);assertNull(c.journal().entries().getFirst().confirmedAt());assertNull(c.journal().fileEntries().getFirst().confirmedAt());assertEquals(0,c.catalog().store().minimumRestorePointEpochMs());
    }
    @Test void copiesBetweenPreparationAndActualAccountAndFileCompletionStillBlockRetirement()throws Exception {
        var c=context("completion-barrier");Instant at=clock.instant().minus(Duration.ofDays(80));c.journal().ensure(UUID.randomUUID(),UUID.randomUUID(),at,at.plus(Duration.ofDays(37)));c.journal().ensureFile(UUID.randomUUID(),UUID.randomUUID(),at,at.plus(Duration.ofDays(37)));Path between=copy(c,UUID.randomUUID().toString());Files.writeString(between.resolve("pending"),"Synthetic incomplete suppression");register(c,between,clock.instant().minus(Duration.ofDays(40)));
        var account=c.journal().confirmAccount(c.journal().entries().getFirst());var file=c.journal().confirmFileAbsent(c.journal().fileEntries().getFirst());assertEquals(0,prune(c,100));c.catalog().locked(()->c.catalog().prune(100));assertEquals(2,prune(c,100));assertEquals(account.confirmedAt(),file.confirmedAt());assertEquals(clock.instant().toEpochMilli(),c.catalog().store().minimumRestorePointEpochMs());
    }
    @Test void aBusyOldestDatabaseRecordDoesNotStarveOtherCompletedSuppressions()throws Exception {
        var c=context("busy-retirement");Instant at=clock.instant().minus(Duration.ofDays(80));UUID busy=UUID.randomUUID();c.journal().ensure(UUID.randomUUID(),busy,at,at.plus(Duration.ofDays(37)));c.journal().ensureFile(UUID.randomUUID(),UUID.randomUUID(),at.plusSeconds(1),at.plus(Duration.ofDays(37)));c.journal().confirmAccount(c.journal().entries().getFirst());c.journal().confirmFileAbsent(c.journal().fileEntries().getFirst());var verifier=new PrivacyErasureJournal.RetirementVerifier(){public PrivacyErasureJournal.Verification account(PrivacyErasureJournal.Entry e){return PrivacyErasureJournal.Verification.BUSY;}public PrivacyErasureJournal.Verification file(PrivacyErasureJournal.FileEntry e){return PrivacyErasureJournal.Verification.COMPLETE;}public PrivacyErasureJournal.Verification orphan(PrivacyErasureJournal.OrphanEntry e){return PrivacyErasureJournal.Verification.COMPLETE;}};
        assertEquals(0,c.journal().pruneExpired(1,verifier));assertEquals(1,c.journal().pruneExpired(1,verifier));assertTrue(c.journal().fileEntries().isEmpty());assertEquals(busy,c.journal().entries().getFirst().closure());
    }
}
