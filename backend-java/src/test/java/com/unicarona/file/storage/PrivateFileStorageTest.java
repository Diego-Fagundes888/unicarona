package com.unicarona.file.storage;

import com.unicarona.shared.ApiException;
import java.nio.file.*;
import java.util.UUID;
import java.util.List;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PrivateFileStorageTest {
    @TempDir Path directory;
    @Test void rejectsPublicStorageTraversalAndOverwritesWhileReadingOnlyPrivateData() throws Exception {
        Path publicPath=directory.resolve("public");Files.createDirectories(publicPath);
        assertEquals("INVALID_STORAGE",assertThrows(ApiException.class,() -> new PrivateFileStorage(publicPath.resolve("private"),publicPath)).code());
        var storage=new PrivateFileStorage(directory.resolve("private"),publicPath);String key=UUID.randomUUID().toString();byte[] data="Synthetic private file".getBytes();
        storage.put(key,data);assertArrayEquals(data,storage.get(key));
        assertThrows(FileAlreadyExistsException.class,() -> storage.put(key,"other".getBytes()));assertArrayEquals(data,storage.get(key));
        assertEquals("INVALID_FILE",assertThrows(ApiException.class,() -> storage.get("../../private-document")).code());
        storage.remove(key);assertFalse(Files.exists(directory.resolve("private").resolve(key)));
    }
    @Test void aRootAliasIntoThePublicDirectoryIsRejectedBeforeWriting() throws Exception {
        Path publicPath=directory.resolve("public");Files.createDirectories(publicPath);Path alias=directory.resolve("private-alias");
        if(System.getProperty("os.name").toLowerCase().contains("windows")) {
            var command=new ProcessBuilder("powershell.exe","-NoProfile","-NonInteractive","-Command","New-Item -ItemType Junction -Path $env:TEST_STORAGE_ALIAS -Target $env:TEST_STORAGE_PUBLIC | Out-Null");
            command.environment().put("TEST_STORAGE_ALIAS",alias.toString());command.environment().put("TEST_STORAGE_PUBLIC",publicPath.toString());
            var process=command.start();assertTrue(process.waitFor(15,java.util.concurrent.TimeUnit.SECONDS));assertEquals(0,process.exitValue());
        } else Files.createSymbolicLink(alias,publicPath);
        try {
            var storage=new PrivateFileStorage(alias,publicPath);
            assertEquals("INVALID_STORAGE",assertThrows(ApiException.class,() -> storage.put(UUID.randomUUID().toString(),new byte[]{1})).code());
            try(var files=Files.list(publicPath)) { assertEquals(0,files.count()); }
        } finally { Files.deleteIfExists(alias); }
    }
    @Test void pagedFlatInventoryAndContentObservationsDoNotInferOwnership()throws Exception {
        var storage=new PrivateFileStorage(directory.resolve("inventory"),directory.resolve("public"));assertTrue(storage.inventory(null,2).isEmpty());assertFalse(Files.exists(directory.resolve("inventory")));
        var ids=List.of(UUID.fromString("00000000-0000-0000-0000-000000000003"),UUID.fromString("00000000-0000-0000-0000-000000000001"),UUID.fromString("00000000-0000-0000-0000-000000000002"));for(UUID id:ids)storage.put(id.toString(),new byte[]{1,2,3});var first=storage.inventory(null,2);assertEquals(List.of(ids.get(1),ids.get(2)),first);assertEquals(List.of(ids.get(0)),storage.inventory(first.getLast(),2));
        var original=storage.observe(ids.getFirst()).orElseThrow();assertEquals("PrivateFileObservation[redacted]",original.toString());assertEquals(3,original.bytes());assertEquals(original,storage.observe(ids.getFirst()).orElseThrow());Files.write(directory.resolve("inventory").resolve(ids.getFirst().toString()),new byte[]{3,2,1});var changed=storage.observe(ids.getFirst()).orElseThrow();assertNotEquals(original.fingerprint(),changed.fingerprint());assertThrows(IOException.class,()->storage.eraseExact(original.file(),original.bytes(),original.sha256()));assertTrue(storage.existsVerified(original.file().toString()));storage.eraseExact(changed.file(),changed.bytes(),changed.sha256());assertTrue(storage.observe(changed.file()).isEmpty());
    }
    @Test void unknownEntriesDirectoriesAndOversizedObservationsAreNotDisposableFiles()throws Exception {
        Path root=directory.resolve("unexpected");var storage=new PrivateFileStorage(root,directory.resolve("public"));UUID id=UUID.randomUUID();storage.put(id.toString(),new byte[]{9});Path note=Files.writeString(root.resolve("operator-note.txt"),"Synthetic operator note");assertThrows(IOException.class,()->storage.inventory(null,10));assertTrue(Files.exists(root.resolve(id.toString())));Files.delete(note);Path nested=Files.createDirectory(root.resolve(UUID.randomUUID().toString()));assertThrows(IOException.class,()->storage.inventory(null,10));Files.delete(nested);Files.write(root.resolve(id.toString()),new byte[5*1024*1024+1]);assertThrows(IOException.class,()->storage.observe(id));assertTrue(Files.exists(root.resolve(id.toString())));
    }
    @Test void anEmptyInterruptedFileCanBeIdentifiedAndRemovedByItsExactContent()throws Exception {
        var storage=new PrivateFileStorage(directory.resolve("empty"),directory.resolve("public"));UUID id=UUID.randomUUID();storage.put(id.toString(),new byte[0]);var observed=storage.observe(id).orElseThrow();assertEquals(0,observed.bytes());storage.eraseExact(id,observed.bytes(),observed.sha256());assertTrue(storage.observe(id).isEmpty());
    }
}
