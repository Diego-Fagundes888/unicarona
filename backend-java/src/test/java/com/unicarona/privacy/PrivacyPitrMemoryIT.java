package com.unicarona.privacy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unicarona.privacy.recovery.PitrEncryptedArchive;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Crypto boundary stress only: synthetic WAL-shaped bytes, not native PostgreSQL recovery. */
class PrivacyPitrMemoryIT {
    @TempDir(factory=PrivacyNativePitrIT.PrivateTemp.class) Path temporary;
    private static final String WAL="000000010000000000000001";
    private int invoke(String action,Path path,Path root,Path data,String ring)throws Exception {
        boolean windows=System.getProperty("os.name").startsWith("Windows");Path java=Path.of(System.getProperty("java.home"),"bin",windows?"java.exe":"java");Path jar=Path.of("target/unicarona-backend-1.0.0-SNAPSHOT.jar").toAbsolutePath();Path log=temporary.resolve("probe-"+UUID.randomUUID()+".private.log");
        var command=new ProcessBuilder(java.toString(),"-Xmx64m","-XX:+ExitOnOutOfMemoryError","-Dloader.main=com.unicarona.privacy.ops.PitrArchiveCommand","-cp",jar.toString(),"org.springframework.boot.loader.launch.PropertiesLauncher",action,path.toString(),WAL).redirectErrorStream(true).redirectOutput(log.toFile());command.environment().clear();for(String name:List.of("SystemRoot","WINDIR","TEMP","TMP")){String value=System.getenv(name);if(value!=null)command.environment().put(name,value);}command.environment().putAll(Map.of("UNICARONA_PITR_ROOT",root.toString(),"UNICARONA_PITR_DATA_ROOT",data.toString(),"UNICARONA_PITR_KEYS",ring));var process=command.start();if(!process.waitFor(45,TimeUnit.SECONDS)){process.destroy();process.waitFor(5,TimeUnit.SECONDS);fail("Bounded-heap PITR probe exceeded its time limit.");}assertFalse(Files.readString(log).contains("OutOfMemoryError"),"Recovery must not buffer the entire plaintext object in heap.");return process.exitValue();
    }
    private byte[] digest(Path path)throws Exception {var digest=MessageDigest.getInstance("SHA-256");try(var input=Files.newInputStream(path)){byte[] buffer=new byte[65536];int n;while((n=input.read(buffer))!=-1)digest.update(buffer,0,n);}return digest.digest();}
    @Test void objectLargerThanHeapRoundTripsAndBadTagCannotReplaceExistingDestination()throws Exception {
        Files.createDirectories(temporary.resolve("ops/retention"));Path root=Files.createDirectories(temporary.resolve("ops/archives/"+UUID.randomUUID())),data=Files.createDirectories(temporary.resolve("source"));Files.createDirectory(data.resolve("pg_wal"));Path wal=data.resolve("pg_wal/"+WAL);byte[] chunk=new byte[65536];new Random(20261026).nextBytes(chunk);ByteBuffer.wrap(chunk).order(ByteOrder.nativeOrder()).putShort(2,(short)2).putLong(24,123456L);try(var out=Files.newOutputStream(wal)){for(int n=0;n<1536;n++)out.write(chunk);}assertEquals(96L*1024*1024,Files.size(wal));
        byte[] key=new byte[32];new SecureRandom().nextBytes(key);var archive=new PitrEncryptedArchive(root,Map.of("memory",key),Clock.systemUTC());Instant now=Instant.now();archive.initialize(UUID.randomUUID(),"123456",now,now.plus(Duration.ofDays(1)),"memory");String ring=new ObjectMapper().writeValueAsString(Map.of("memory",Base64.getEncoder().encodeToString(key)));assertEquals(0,invoke("seal",wal,root,data,ring));
        Path base=Files.createDirectory(temporary.resolve("base"));Files.writeString(base.resolve("backup_label"),"Synthetic crypto probe");Files.writeString(base.resolve("backup_manifest"),"{\"System-Identifier\":123456}");archive.sealBase(base);archive.finish("unicarona-"+UUID.randomUUID(),"0/1000100",WAL);Path restored=Files.createDirectories(temporary.resolve("restored/pg_wal")).getParent(),destination=restored.resolve("pg_wal/RECOVERYXLOG");assertEquals(0,invoke("fetch",destination,root,restored,ring));byte[] expected=digest(wal);assertArrayEquals(expected,digest(destination));
        try(var file=new RandomAccessFile(root.resolve(WAL+".gcm").toFile(),"rw")){file.seek(file.length()-1);int old=file.read();file.seek(file.length()-1);file.write(old^1);}assertEquals(1,invoke("fetch",destination,root,restored,ring));assertArrayEquals(expected,digest(destination));try(var children=Files.list(restored.resolve("pg_wal"))){assertEquals(1,children.count());}
    }
}
