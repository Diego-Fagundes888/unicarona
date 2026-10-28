package com.unicarona.privacy.service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

/** Fixed operator-owned storage. All callers use server-side paths, never paths from HTTP requests. */
public final class PrivacyOperationFiles {
    private PrivacyOperationFiles(){}
    public static void createPrivateDirectories(Path path)throws IOException {
        if(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"))Files.createDirectories(path,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        else Files.createDirectories(path);
        ordinary(path);
    }
    public static void ordinary(Path path)throws IOException {
        Path normalized=path.toAbsolutePath().normalize();
        if(Files.isSymbolicLink(normalized)||!normalized.equals(normalized.toRealPath()))throw new IOException("Redirected privacy-operation path.");
    }
    /** Access denied, unavailable storage and other I/O failures must never count as successful erasure. */
    public static boolean existsVerified(Path path,LinkOption... ignored)throws IOException {
        try{Files.readAttributes(path,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);return true;}
        catch(NoSuchFileException absent){return false;}
    }
    public static Path child(Path anchor,String name)throws IOException {
        if(name==null||!name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")||name.equals(".")||name.equals(".."))throw new IOException("Invalid retained-copy resource name.");
        ordinary(anchor);Path result=anchor.resolve(name).toAbsolutePath().normalize();
        if(!result.getParent().equals(anchor.toAbsolutePath().normalize()))throw new IOException("Retained-copy resource escaped its container.");
        if(PrivacyOperationFiles.existsVerified(result,LinkOption.NOFOLLOW_LINKS))ordinary(result);return result;
    }
    public static byte[] read(Path path,int maximum)throws IOException {
        ordinary(path);try(var input=Files.newInputStream(path,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){byte[] data=input.readNBytes(maximum+1);if(data.length>maximum)throw new IOException("Privacy-operation record exceeds its limit.");return data;}
    }
    public static void atomicWrite(Path path,byte[] data)throws IOException {
        createPrivateDirectories(path.getParent());if(PrivacyOperationFiles.existsVerified(path,LinkOption.NOFOLLOW_LINKS))ordinary(path);
        Path temporary=path.getParent().resolve("pending-"+UUID.randomUUID()+".tmp");
        try{
            boolean posix=FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
            var options=Set.of(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
            try(var channel=posix?FileChannel.open(temporary,options,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))):FileChannel.open(temporary,options)){
                var bytes=ByteBuffer.wrap(data);while(bytes.hasRemaining())channel.write(bytes);channel.force(true);
            }
            Files.move(temporary,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            try(var committed=FileChannel.open(path,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){committed.force(true);}
            if(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"))try(var directory=FileChannel.open(path.getParent(),StandardOpenOption.READ)){directory.force(true);}
        }finally{Files.deleteIfExists(temporary);}
    }
    public static boolean interruptedTemporary(Path path)throws IOException {
        return interruptedTemporary(path,16384);
    }
    public static boolean interruptedTemporary(Path path,int maximum)throws IOException {
        if(maximum<1||maximum>64*1024*1024)throw new IllegalArgumentException("Invalid interrupted-record bound.");
        if(!path.getFileName().toString().matches("pending-[a-f0-9-]{36}\\.tmp"))return false;
        ordinary(path);if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)>maximum)throw new IOException("Invalid interrupted privacy-operation file.");return true;
    }
    public static int pruneInterruptedTemporaries(Path directory,java.time.Instant now,int budget)throws IOException {
        return pruneInterruptedTemporaries(directory,now,budget,16384);
    }
    public static int pruneInterruptedTemporaries(Path directory,java.time.Instant now,int budget,int maximum)throws IOException {
        if(!PrivacyOperationFiles.existsVerified(directory,LinkOption.NOFOLLOW_LINKS))return 0;ordinary(directory);int removed=0;
        try(var files=Files.list(directory)){for(Path path:files.toList()){
            if(removed>=budget)break;
            if(interruptedTemporary(path,maximum)&&Files.getLastModifiedTime(path,LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(now.minus(java.time.Duration.ofDays(1)))){Files.delete(path);removed++;}
        }}return removed;
    }
}
