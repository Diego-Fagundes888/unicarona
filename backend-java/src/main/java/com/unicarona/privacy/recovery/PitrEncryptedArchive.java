package com.unicarona.privacy.recovery;

import com.fasterxml.jackson.databind.*;
import com.unicarona.privacy.service.PrivacyOperationFiles;
import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/** Encrypted finite recovery bundle. No database connection, provider call or public endpoint. */
public final class PitrEncryptedArchive {
    public record Index(int format,UUID id,UUID dataset,String systemId,long created,long expires,String state,
                        String target,String targetLsn,String targetWal,String keyId,String signature){
        @Override public String toString(){return "PitrIndex[technical metadata redacted]";}
    }
    private static final byte[] MAGIC="UCPITR01".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final long MAX_BYTES=16L*1024*1024*1024;
    private static final Provider STREAMING_PROVIDER=new org.bouncycastle.jce.provider.BouncyCastleProvider();
    private final Path root,operations;private final Map<String,byte[]> keys;private final Clock clock;private final ObjectMapper json;
    public PitrEncryptedArchive(Path root,Map<String,byte[]> keys,Clock clock)throws IOException {
        this.root=root.toAbsolutePath().normalize();this.clock=Objects.requireNonNull(clock);this.keys=new HashMap<>();
        if(this.root.getParent()==null||!Set.of("archives","backups").contains(this.root.getParent().getFileName().toString())
          ||!UUID.fromString(this.root.getFileName().toString()).toString().equals(this.root.getFileName().toString()))throw new IOException("Recovery bundle requires a catalog resource UUID.");
        operations=this.root.getParent().getParent();PrivacyOperationFiles.ordinary(this.root);
        if(keys.isEmpty()||keys.size()>32)throw new IOException("Recovery key ring is invalid.");
        for(var item:keys.entrySet()){if(!item.getKey().matches("[A-Za-z0-9_-]{1,40}")||item.getValue()==null||item.getValue().length!=32)throw new IOException("Recovery key is invalid.");this.keys.put(item.getKey(),item.getValue().clone());}
        json=new ObjectMapper().disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
    }
    @FunctionalInterface private interface Work<T>{T run()throws IOException;}
    @FunctionalInterface private interface Writer{void write(OutputStream out)throws IOException;}
    private <T>T locked(Work<T> work)throws IOException {return withLock(root.resolve("pitr-io.lock"),work);}
    private <T>T gate(Work<T> work)throws IOException {return withLock(operations.resolve("retention/operations.lock"),work);}
    private <T>T withLock(Path lock,Work<T> work)throws IOException {
        PrivacyOperationFiles.ordinary(root);PrivacyOperationFiles.ordinary(lock.getParent());
        try(var channel=FileChannel.open(lock,StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);FileLock acquired=null;
            while(acquired==null){try{acquired=channel.tryLock();}catch(OverlappingFileLockException busy){}
                if(acquired==null){if(System.nanoTime()-until>=0)throw new IOException("Recovery bundle coordination is busy.");try{Thread.sleep(20);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException("Recovery operation interrupted.");}}}
            try(var held=acquired){PrivacyOperationFiles.ordinary(root);return work.run();}
        }
    }
    public void initialize(UUID dataset,String systemId,Instant created,Instant expires,String keyId)throws IOException {locked(()->{
        if(Files.exists(root.resolve("index.json"),LinkOption.NOFOLLOW_LINKS))throw new IOException("Recovery bundle already initialized.");
        if(dataset==null||systemId==null||!systemId.matches("[0-9]{1,20}")||!keys.containsKey(keyId)||created==null||expires==null
          ||!expires.isAfter(created)||expires.isAfter(created.plus(Duration.ofDays(90)))||!expires.isAfter(clock.instant()))throw new IOException("Recovery bundle identity or retention is invalid.");
        writeIndex(new Index(1,UUID.fromString(root.getFileName().toString()),dataset,systemId,created.toEpochMilli(),expires.toEpochMilli(),"open","","","",keyId,""));return null;
    });}
    public Index index()throws IOException{return locked(this::readIndex);}
    private byte[] derive(String id,String purpose)throws IOException {
        byte[] key=keys.get(id);if(key==null)throw new IOException("Recovery key is unavailable.");
        try{var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));return mac.doFinal(("unicarona-pitr-v1:"+purpose).getBytes(java.nio.charset.StandardCharsets.UTF_8));}catch(GeneralSecurityException error){throw new IOException("Recovery cryptography is unavailable.");}
    }
    private byte[] canonical(Index v)throws IOException{return json.writeValueAsBytes(new Index(v.format(),v.id(),v.dataset(),v.systemId(),v.created(),v.expires(),v.state(),v.target(),v.targetLsn(),v.targetWal(),v.keyId(),""));}
    private String sign(Index v)throws IOException {try{var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(derive(v.keyId(),"index"),"HmacSHA256"));return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(canonical(v)));}catch(GeneralSecurityException error){throw new IOException("Recovery signing is unavailable.");}}
    private void writeIndex(Index v)throws IOException {gate(()->{if(v.expires()<=clock.millis())throw new IOException("Recovery bundle has expired.");var signed=new Index(v.format(),v.id(),v.dataset(),v.systemId(),v.created(),v.expires(),v.state(),v.target(),v.targetLsn(),v.targetWal(),v.keyId(),sign(v));PrivacyOperationFiles.atomicWrite(root.resolve("index.json"),json.writeValueAsBytes(signed));return null;});}
    private Index readIndex()throws IOException {
        Index v;try{v=json.readValue(PrivacyOperationFiles.read(root.resolve("index.json"),8192),Index.class);}catch(RuntimeException bad){throw new IOException("Recovery index is invalid.");}
        if(v.format()!=1||v.id()==null||!v.id().toString().equals(root.getFileName().toString())||v.dataset()==null||v.systemId()==null||!v.systemId().matches("[0-9]{1,20}")
          ||v.created()<0||v.expires()<=v.created()||v.expires()-v.created()>Duration.ofDays(90).toMillis()||v.state()==null||!Set.of("open","sealed").contains(v.state())||v.target()==null||v.targetLsn()==null||v.targetWal()==null||v.keyId()==null||v.signature()==null
          ||!MessageDigest.isEqual(sign(v).getBytes(java.nio.charset.StandardCharsets.US_ASCII),v.signature().getBytes(java.nio.charset.StandardCharsets.US_ASCII)))throw new IOException("Recovery index authentication failed.");
        if(v.expires()<=clock.millis())throw new IOException("Recovery bundle has expired.");
        if(v.state().equals("open")&&(!v.target().isEmpty()||!v.targetLsn().isEmpty()||!v.targetWal().isEmpty()))throw new IOException("Open recovery index has a target.");
        if(v.state().equals("sealed")&&(!v.target().matches("unicarona-[a-f0-9-]{36}")||!v.targetLsn().matches("[0-9A-F]{1,8}/[0-9A-F]{1,8}")||!walName(v.targetWal())))throw new IOException("Recovery target is invalid.");return v;
    }
    private static boolean walName(String name){return name!=null&&name.matches("(?:[0-9A-F]{24}|[0-9A-F]{8}\\.history|[0-9A-F]{24}\\.[0-9A-F]{8}\\.backup)");}
    private static void objectName(String name)throws IOException {if(!"base.zip".equals(name)&&!walName(name))throw new IOException("Recovery object name is invalid.");}
    private byte[] aad(Index v,String name,String keyId){return ("UCPITR01|"+v.id()+"|"+v.dataset()+"|"+v.systemId()+"|"+name+"|"+keyId).getBytes(java.nio.charset.StandardCharsets.UTF_8);}
    private Cipher cipher(int mode,String keyId,byte[] nonce,byte[] aad)throws IOException {try{var cipher=Cipher.getInstance("AES/GCM/NoPadding",STREAMING_PROVIDER);cipher.init(mode,new SecretKeySpec(derive(keyId,"data"),"AES"),new GCMParameterSpec(128,nonce));cipher.updateAAD(aad);return cipher;}catch(GeneralSecurityException error){throw new IOException("Recovery cryptography failed.");}}
    private static MessageDigest digest()throws IOException {try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException impossible){throw new IOException("Recovery digest is unavailable.");}}
    private byte[] decode(Index index,String name,OutputStream destination)throws IOException {
        objectName(name);Path input=root.resolve(name+".gcm");PrivacyOperationFiles.ordinary(input);var hash=digest();
        try(var raw=new DataInputStream(Files.newInputStream(input,LinkOption.NOFOLLOW_LINKS))){
            if(!Arrays.equals(raw.readNBytes(8),MAGIC))throw new IOException("Recovery object format is invalid.");String keyId=raw.readUTF();if(!keyId.equals(index.keyId()))throw new IOException("Recovery object key identity mismatch.");byte[] nonce=raw.readNBytes(12);if(nonce.length!=12)throw new IOException("Recovery object is truncated.");
            // Keep bounded chunks in memory. Callers use private staging or a null sink;
            // nothing may be published or extracted until doFinal authenticates the tag.
            var decrypt=cipher(Cipher.DECRYPT_MODE,keyId,nonce,aad(index,name,keyId));byte[] buffer=new byte[65536];long bytes=0;int n;
            while((n=raw.read(buffer))!=-1){byte[] clear=decrypt.update(buffer,0,n);if(clear!=null){bytes+=clear.length;if(bytes>MAX_BYTES)throw new IOException("Recovery object exceeds its bound.");hash.update(clear);destination.write(clear);}}
            byte[] tail=decrypt.doFinal();bytes+=tail.length;if(bytes>MAX_BYTES)throw new IOException("Recovery object exceeds its bound.");hash.update(tail);destination.write(tail);
        }catch(GeneralSecurityException invalid){throw new IOException("Recovery object is missing or failed authentication.",invalid);
        }catch(IOException invalid){throw new IOException("Recovery object is missing or failed authentication.",invalid);}return hash.digest();
    }
    private byte[] seal(Index index,String name,Writer writer)throws IOException {
        objectName(name);if(!index.state().equals("open"))throw new IOException("A sealed recovery bundle cannot accept new objects.");
        Path destination=root.resolve(name+".gcm"),pending=root.resolve("pending-"+UUID.randomUUID()+".tmp");byte[] nonce=new byte[12];new SecureRandom().nextBytes(nonce);var hash=digest();
        try{
            try(var raw=new DataOutputStream(Files.newOutputStream(pending,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS))){
                raw.write(MAGIC);raw.writeUTF(index.keyId());raw.write(nonce);
                try(var encrypted=new CipherOutputStream(raw,cipher(Cipher.ENCRYPT_MODE,index.keyId(),nonce,aad(index,name,index.keyId())))){
                    var measured=new FilterOutputStream(encrypted){long count;@Override public void write(int v)throws IOException{write(new byte[]{(byte)v},0,1);}@Override public void write(byte[] data,int offset,int length)throws IOException{count+=length;if(count>MAX_BYTES)throw new IOException("Recovery object exceeds its bound.");hash.update(data,offset,length);out.write(data,offset,length);}@Override public void close()throws IOException{flush();}};
                    writer.write(measured);measured.flush();
                }
            }
            byte[] result=hash.digest();try(var file=FileChannel.open(pending,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){file.force(true);}
            return gate(()->{
                if(!index.equals(readIndex()))throw new IOException("Recovery bundle changed before publication.");
                if(Files.exists(destination,LinkOption.NOFOLLOW_LINKS)){if(!MessageDigest.isEqual(result,decode(index,name,OutputStream.nullOutputStream())))throw new IOException("Recovery object name already contains different data.");return result;}
                Files.move(pending,destination,StandardCopyOption.ATOMIC_MOVE);try(var file=FileChannel.open(destination,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){file.force(true);}forceDirectory(root);return result;
            });
        }finally{Files.deleteIfExists(pending);}
    }
    public void archiveWal(Path dataRoot,Path source,String name)throws IOException {locked(()->{
        if(!walName(name))throw new IOException("Invalid WAL archive name.");var index=readIndex();Path data=dataRoot.toAbsolutePath().normalize();PrivacyOperationFiles.ordinary(data);Path path=source.isAbsolute()?source.toAbsolutePath().normalize():data.resolve(source).normalize();
        if(!path.equals(data.resolve("pg_wal").resolve(name)))throw new IOException("WAL source must be its configured cluster file.");PrivacyOperationFiles.ordinary(path);if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("WAL source is not a regular file.");
        if(name.matches("[0-9A-F]{24}")){try(var input=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){byte[] header=input.readNBytes(40);if(header.length!=40||(ByteBuffer.wrap(header).order(ByteOrder.nativeOrder()).getShort(2)&2)==0||!Long.toUnsignedString(ByteBuffer.wrap(header).order(ByteOrder.nativeOrder()).getLong(24)).equals(index.systemId()))throw new IOException("WAL belongs to a different cluster.");}}
        var before=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);seal(index,name,out->{try(var input=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){input.transferTo(out);}var after=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);if(before.size()!=after.size()||!before.lastModifiedTime().equals(after.lastModifiedTime())||!Objects.equals(before.fileKey(),after.fileKey()))throw new IOException("WAL changed while archiving.");});return null;
    });}
    private static void forceDirectory(Path path)throws IOException {if(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"))try(var directory=FileChannel.open(path,StandardOpenOption.READ)){directory.force(true);}}
    public void sealBase(Path base)throws IOException {locked(()->{
        var index=readIndex();PrivacyOperationFiles.ordinary(base);if(!Files.isRegularFile(base.resolve("backup_label"),LinkOption.NOFOLLOW_LINKS)||!Files.isRegularFile(base.resolve("backup_manifest"),LinkOption.NOFOLLOW_LINKS)||Files.exists(base.resolve("postmaster.pid"),LinkOption.NOFOLLOW_LINKS))throw new IOException("Base source must be a completed physical backup.");
        var manifest=json.readTree(PrivacyOperationFiles.read(base.resolve("backup_manifest"),64*1024*1024));if(!index.systemId().equals(manifest.path("System-Identifier").asText()))throw new IOException("Physical base belongs to a different cluster.");
        List<Path> files;try(var listing=Files.walk(base)){files=listing.filter(p->!p.equals(base)).sorted(Comparator.comparing(p->base.relativize(p).toString())).toList();}if(files.size()>1000000)throw new IOException("Physical base has too many files.");
        seal(index,"base.zip",out->{try(var zip=new ZipOutputStream(out)){
            for(Path path:files){PrivacyOperationFiles.ordinary(path);if(!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS)&&!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("Physical base contains a special file.");String relative=base.relativize(path).toString().replace('\\','/');var entry=new ZipEntry(relative+(Files.isDirectory(path)?"/":""));entry.setTime(0);zip.putNextEntry(entry);if(!entry.isDirectory())try(var input=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){input.transferTo(zip);}zip.closeEntry();}
        }});return null;
    });}
    public void finish(String target,String lsn,String wal)throws IOException {locked(()->{
        var v=readIndex();if(!target.matches("unicarona-[a-f0-9-]{36}")||!lsn.matches("[0-9A-F]{1,8}/[0-9A-F]{1,8}")||!wal.matches("[0-9A-F]{24}"))throw new IOException("Recovery finish target is invalid.");
        if(v.state().equals("sealed")){if(!v.target().equals(target)||!v.targetLsn().equals(lsn)||!v.targetWal().equals(wal))throw new IOException("Recovery target cannot be replaced.");decode(v,"base.zip",OutputStream.nullOutputStream());decode(v,wal,OutputStream.nullOutputStream());return null;}
        decode(v,"base.zip",OutputStream.nullOutputStream());decode(v,wal,OutputStream.nullOutputStream());writeIndex(new Index(v.format(),v.id(),v.dataset(),v.systemId(),v.created(),v.expires(),"sealed",target,lsn,wal,v.keyId(),""));return null;
    });}
    /** Private staging is authenticated completely before the PostgreSQL target is published. */
    public void fetchWal(Path dataRoot,Path destination,String name)throws IOException {locked(()->{
        if(!walName(name))throw new IOException("Invalid requested WAL name.");var v=readIndex();if(!v.state().equals("sealed"))throw new IOException("Recovery bundle has not been sealed.");Path data=dataRoot.toAbsolutePath().normalize();PrivacyOperationFiles.ordinary(data);Path target=destination.isAbsolute()?destination.toAbsolutePath().normalize():data.resolve(destination).normalize();
        if(!target.getParent().equals(data.resolve("pg_wal"))||!(Set.of("RECOVERYXLOG","RECOVERYHISTORY").contains(target.getFileName().toString())||target.getFileName().toString().equals(name)))throw new IOException("WAL restore destination is invalid.");PrivacyOperationFiles.ordinary(target.getParent());Path pending=target.resolveSibling("pending-"+UUID.randomUUID()+".tmp");
        try{try(var out=Files.newOutputStream(pending,StandardOpenOption.CREATE_NEW,LinkOption.NOFOLLOW_LINKS)){decode(v,name,out);}try(var file=FileChannel.open(pending,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){file.force(true);}gate(()->{if(!v.equals(readIndex()))throw new IOException("Recovery bundle changed before publication.");if(Files.exists(target,LinkOption.NOFOLLOW_LINKS))PrivacyOperationFiles.ordinary(target);Files.move(pending,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);return null;});}finally{Files.deleteIfExists(pending);}return null;
    });}
    public void restoreBase(Path destination)throws IOException {locked(()->{
        var v=readIndex();if(!v.state().equals("sealed"))throw new IOException("Recovery bundle has not been sealed.");Path target=destination.toAbsolutePath().normalize();PrivacyOperationFiles.ordinary(target.getParent());if(target.startsWith(operations)||operations.startsWith(target)||Files.exists(target,LinkOption.NOFOLLOW_LINKS))throw new IOException("Physical restore destination must be absent and separate from retained copies.");
        Path pending=target.resolveSibling("pitr-restore-"+UUID.randomUUID());PrivacyOperationFiles.createPrivateDirectories(pending);Path zipFile=pending.resolve("authenticated-base.zip");Path tree=pending.resolve("data");PrivacyOperationFiles.createPrivateDirectories(tree);
        try{
            try(var out=Files.newOutputStream(zipFile,StandardOpenOption.CREATE_NEW,LinkOption.NOFOLLOW_LINKS)){decode(v,"base.zip",out);}
            long expanded=0;int entries=0;try(var zip=new ZipInputStream(Files.newInputStream(zipFile))){ZipEntry entry;while((entry=zip.getNextEntry())!=null){
                if(++entries>1000000||entry.getName().contains("\\")||entry.getName().startsWith("/")||entry.getName().contains(":"))throw new IOException("Physical archive entry is invalid.");Path file=tree.resolve(entry.getName()).normalize();if(!file.startsWith(tree)||file.equals(tree))throw new IOException("Physical archive path escapes its root.");
                if(entry.isDirectory()){PrivacyOperationFiles.createPrivateDirectories(file);continue;}PrivacyOperationFiles.createPrivateDirectories(file.getParent());try(var out=Files.newOutputStream(file,StandardOpenOption.CREATE_NEW,LinkOption.NOFOLLOW_LINKS)){byte[] buffer=new byte[65536];int n;while((n=zip.read(buffer))!=-1){expanded+=n;if(expanded>128L*1024*1024*1024)throw new IOException("Physical archive expansion exceeds its bound.");out.write(buffer,0,n);}}if(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"))Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------"));
            }}
            if(!Files.isRegularFile(tree.resolve("backup_manifest"))||!Files.isRegularFile(tree.resolve("backup_label"))||Files.exists(tree.resolve("postmaster.pid")))throw new IOException("Physical archive does not contain a safe base.");
            Files.delete(zipFile);gate(()->{if(!v.equals(readIndex()))throw new IOException("Recovery bundle changed before publication.");Files.move(tree,target,StandardCopyOption.ATOMIC_MOVE);return null;});Files.delete(pending);
        }catch(IOException|RuntimeException failure){removeStaging(pending);if(failure instanceof IOException io)throw io;throw new IOException("Physical archive extraction failed.",failure);}return null;
    });}
    private static void removeStaging(Path pending)throws IOException {PrivacyOperationFiles.ordinary(pending);if(!pending.getFileName().toString().matches("pitr-restore-[a-f0-9-]{36}"))throw new IOException("Unexpected recovery staging directory.");try(var files=Files.walk(pending)){for(Path path:files.sorted(Comparator.reverseOrder()).toList()){PrivacyOperationFiles.ordinary(path);Files.delete(path);}}}
}
