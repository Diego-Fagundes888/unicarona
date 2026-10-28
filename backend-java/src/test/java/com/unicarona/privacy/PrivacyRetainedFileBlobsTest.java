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
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class PrivacyRetainedFileBlobsTest {
    @TempDir Path temporary;private final ObjectMapper json=new ObjectMapper().registerModule(new JavaTimeModule());private final Clock clock=Clock.fixed(Instant.now(),ZoneOffset.UTC);private final CryptoService crypto=new CryptoService();
    private record Context(MockEnvironment env,PrivacyBackupCatalog catalog,PrivacyHoldControlJournal journal,PrivacyRetainedFileBlobs blobs,Path root){}
    private Context context(String name)throws Exception {
        Path parent=temporary.resolve(name);var env=new MockEnvironment().withProperty("NODE_ENV","test").withProperty("TOKEN_PEPPER",AuthTestSettings.PEPPER).withProperty("PIN_ENCRYPTION_KEY",AuthTestSettings.KEY).withProperty("JWT_SECRET",AuthTestSettings.SECRET).withProperty("STORAGE_PATH",parent.resolve("active").toString()).withProperty("FRONTEND_PATH",parent.resolve("public").toString()).withProperty("PRIVACY_OPERATIONS_ROOT",parent.resolve("ops").toString())
            .withProperty("PRIVACY_EVIDENCE_KEY_ID","e1").withProperty("PRIVACY_EVIDENCE_KEYS","{\"e1\":\""+"cd".repeat(32)+"\"}").withProperty("PRIVACY_BACKUP_KEY_ID","b1").withProperty("PRIVACY_BACKUP_KEYS","{\"b1\":\""+"A".repeat(43)+"\"}").withProperty("PRIVACY_JOURNAL_KEY_ID","j1").withProperty("PRIVACY_JOURNAL_KEYS","{\"j1\":\""+"B".repeat(43)+"\"}");
        var auth=new AuthSettings(env);var files=new StorageSettings(env);var keys=new PrivacySigningKeys(env,auth,crypto,json);var catalog=new PrivacyBackupCatalog(new PrivacyOperationsSettings(env,files),new PrivacySettings(env),keys,json,clock);catalog.initialize();catalog.locked(()->{catalog.bindDataset(UUID.randomUUID());return null;});var evidence=new PrivacyEvidenceCrypto(env,auth,crypto,json);var journal=new PrivacyHoldControlJournal(catalog,evidence,keys,json,clock);
        return new Context(env,catalog,journal,new PrivacyRetainedFileBlobs(catalog,journal,evidence,keys,json,clock),parent.resolve("ops"));
    }
    private static String sha(byte[] bytes)throws Exception {return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}
    private PrivacyRetainedFileBlobs.Reference capture(Context c,String text)throws Exception {byte[] bytes=text.getBytes(StandardCharsets.UTF_8);return c.blobs().capture(UUID.randomUUID(),UUID.randomUUID(),bytes.length,sha(bytes),bytes,null);}
    private Path file(Context c,PrivacyRetainedFileBlobs.Reference ref){return c.root().resolve("hold-control-files").resolve(ref.blob()+".json");}
    private JsonNode snapshot(PrivacyRetainedFileBlobs.Reference... refs){var state=json.createObjectNode().put("format",1).put("kind","privacy-retention-snapshot");state.putArray("holds");state.putArray("events");state.putArray("retained");var files=state.putArray("files");for(var ref:refs)files.addObject().set("blob",json.valueToTree(ref));return state;}
    private void aged(Context c,PrivacyRetainedFileBlobs.Reference ref)throws Exception {Files.setLastModifiedTime(file(c,ref),FileTime.from(clock.instant().minus(Duration.ofDays(2))));}
    @Test void independentFileIsEncryptedAndBoundToItsOwnerResourceAndContent()throws Exception {
        var c=context("private");String text="Synthetic private evidence, no real personal data";var ref=capture(c,text);String wire=Files.readString(file(c,ref));assertFalse(wire.contains(text));assertFalse(wire.contains(ref.owner().toString()));assertFalse(wire.contains(ref.file().toString()));assertArrayEquals(text.getBytes(StandardCharsets.UTF_8),c.blobs().read(ref));
        var otherOwner=new PrivacyRetainedFileBlobs.Reference(ref.blob(),UUID.randomUUID(),ref.file(),ref.bytes(),ref.sha256(),ref.digest());assertThrows(IOException.class,()->c.blobs().read(otherOwner));var otherFile=new PrivacyRetainedFileBlobs.Reference(ref.blob(),ref.owner(),UUID.randomUUID(),ref.bytes(),ref.sha256(),ref.digest());assertThrows(IOException.class,()->c.blobs().read(otherFile));
    }
    @Test void verifiedReuseDoesNotRewriteTheSameFileAndInvalidSourceCannotClaimPreservation()throws Exception {
        var c=context("reuse");byte[] data={1,2,3,4};UUID owner=UUID.randomUUID(),original=UUID.randomUUID();var ref=c.blobs().capture(owner,original,data.length,sha(data),data,null);assertEquals(ref,c.blobs().capture(owner,original,data.length,sha(data),data,ref));
        assertThrows(IOException.class,()->c.blobs().capture(owner,original,data.length,"a".repeat(64),data,null));assertThrows(IOException.class,()->c.blobs().capture(owner,original,0,sha(data),data,null));try(var listing=Files.list(file(c,ref).getParent())){assertEquals(1,listing.count());}
    }
    @Test void missingOrChangedCopyIsDetectedAndCannotBeSilentlyReused()throws Exception {
        var c=context("corrupt");var ref=capture(c,"Synthetic document bytes");byte[] original=Files.readAllBytes(file(c,ref));Files.delete(file(c,ref));assertThrows(IOException.class,()->c.blobs().read(ref));Files.write(file(c,ref),original);assertTrue(c.blobs().read(ref).length>0);Files.writeString(file(c,ref),"{}");assertThrows(IOException.class,()->c.blobs().read(ref));
        byte[] bytes="Synthetic document bytes".getBytes(StandardCharsets.UTF_8);assertThrows(IOException.class,()->c.blobs().capture(ref.owner(),ref.file(),bytes.length,sha(bytes),bytes,ref));
    }
    @Test void committedAndPreparedSnapshotsBothProtectTheirFilesDuringBoundedCleanup()throws Exception {
        var c=context("protected");var current=capture(c,"Synthetic confirmed file");var pending=capture(c,"Synthetic prepared file");var orphan=capture(c,"Synthetic abandoned file");for(var ref:List.of(current,pending,orphan))aged(c,ref);
        var first=c.journal().prepare(snapshot(current));c.journal().resolve(first,true);var second=c.journal().prepare(snapshot(pending));assertEquals(1,c.blobs().pruneUnreferenced(1));assertTrue(Files.exists(file(c,current)));assertTrue(Files.exists(file(c,pending)));assertFalse(Files.exists(file(c,orphan)));
        c.journal().resolve(second,false);assertEquals(1,c.blobs().pruneUnreferenced(1));assertFalse(Files.exists(file(c,pending)));assertTrue(Files.exists(file(c,current)));assertEquals(0,c.blobs().pruneUnreferenced(1));
    }
    @Test void newOrphansGetAnAllowanceAndAnUnknownFilePreventsAnyDeletion()throws Exception {
        var c=context("allowance");var fresh=capture(c,"Synthetic new orphan");assertEquals(0,c.blobs().pruneUnreferenced(10));aged(c,fresh);Path unknown=file(c,fresh).getParent().resolve("operator-note.txt");Files.writeString(unknown,"Synthetic operator note");assertThrows(IOException.class,()->c.blobs().pruneUnreferenced(10));assertTrue(Files.exists(file(c,fresh)));assertTrue(Files.exists(unknown));Files.delete(unknown);assertEquals(1,c.blobs().pruneUnreferenced(1));
    }
    @Test void anotherDatasetAndAnInvalidControlCannotAuthorizeFileCleanup()throws Exception {
        var a=context("a");var b=context("b");var ref=capture(a,"Synthetic source dataset file");Path copy=Files.createDirectories(b.root().resolve("hold-control-files")).resolve(ref.blob()+".json");Files.copy(file(a,ref),copy);assertThrows(IOException.class,()->b.blobs().read(ref));assertThrows(IOException.class,()->b.blobs().pruneUnreferenced(10));assertTrue(Files.exists(copy));
        var pending=a.journal().prepare(json.createObjectNode().put("syntheticUnrelatedState",true));a.journal().resolve(pending,true);aged(a,ref);assertThrows(IOException.class,()->a.blobs().pruneUnreferenced(10));assertTrue(Files.exists(file(a,ref)));
    }
    @Test void theFullUploadLimitRoundTripsThroughThePrivateParserWithoutRelaxingPublicJsonLimits()throws Exception {
        json.getFactory().setStreamReadConstraints(com.fasterxml.jackson.core.StreamReadConstraints.builder().maxStringLength(1024).build());var c=context("large");byte[] data=new byte[5*1024*1024];Arrays.fill(data,(byte)43);var ref=c.blobs().capture(UUID.randomUUID(),UUID.randomUUID(),data.length,sha(data),data,null);byte[] decoded=c.blobs().read(ref);assertEquals(data.length,decoded.length);assertEquals(sha(data),sha(decoded));assertEquals(1024,json.getFactory().streamReadConstraints().getMaxStringLength());
    }
    @Test void rotationCreatesAnotherImmutableReferenceAndKeepsThePreviousCopyReadableUntilRetired()throws Exception {
        var c=context("rotation");byte[] data="Synthetic rotation evidence".getBytes(StandardCharsets.UTF_8);var old=c.blobs().capture(UUID.randomUUID(),UUID.randomUUID(),data.length,sha(data),data,null);
        c.env().withProperty("PRIVACY_EVIDENCE_KEY_ID","e2").withProperty("PRIVACY_EVIDENCE_KEYS","{\"e1\":\""+"cd".repeat(32)+"\",\"e2\":\""+"ef".repeat(32)+"\"}").withProperty("PRIVACY_JOURNAL_KEY_ID","j2").withProperty("PRIVACY_JOURNAL_KEYS","{\"j1\":\""+"B".repeat(43)+"\",\"j2\":\""+"C".repeat(43)+"\"}");var auth=new AuthSettings(c.env());var keys=new PrivacySigningKeys(c.env(),auth,crypto,json);var files=new StorageSettings(c.env());var catalog=new PrivacyBackupCatalog(new PrivacyOperationsSettings(c.env(),files),new PrivacySettings(c.env()),keys,json,clock);var evidence=new PrivacyEvidenceCrypto(c.env(),auth,crypto,json);var journal=new PrivacyHoldControlJournal(catalog,evidence,keys,json,clock);var rotated=new PrivacyRetainedFileBlobs(catalog,journal,evidence,keys,json,clock);var next=rotated.capture(old.owner(),old.file(),old.bytes(),old.sha256(),data,old);
        assertNotEquals(old.blob(),next.blob());assertArrayEquals(data,rotated.read(old));assertArrayEquals(data,rotated.read(next));assertTrue(Files.exists(file(c,old)));assertTrue(Files.exists(file(c,next)));
    }
}
