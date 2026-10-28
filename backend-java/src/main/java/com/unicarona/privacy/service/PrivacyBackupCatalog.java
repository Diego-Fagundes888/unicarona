package com.unicarona.privacy.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unicarona.privacy.config.*;
import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import static com.unicarona.privacy.config.PrivacySigningKeys.Purpose.*;

/** Independent inventory. Only the immediate children of backups/archives can ever expire. */
@Service
public class PrivacyBackupCatalog {
    public record PendingJournal(String operation,String name,String beforeDigest,String afterDigest,String payload){}
    public record ControlHead(long sequence,UUID operation,String file,String digest){}
    public record Store(int version,UUID id,UUID datasetId,long minimumRestorePointEpochMs,String journalDigest,PendingJournal pending,ControlHead holdHead,ControlHead holdPending,String keyId,String signature){}
    public record Copy(int version,UUID id,UUID storeId,String container,String name,String kind,long createdAtEpochMs,long restorePointEpochMs,long expiresAtEpochMs,long erasedAtEpochMs,String keyId,String signature){}
    public record Inventory(Store store,List<Copy> copies,int present){}
    public record Pruned(int deletedPaths,int completedCopies,int removedRecords){}
    @FunctionalInterface public interface Work<T>{T run()throws IOException;}
    private final PrivacyOperationsSettings settings;private final PrivacySettings policy;private final PrivacySigningKeys keys;private final ObjectMapper json;private final Clock clock;
    public PrivacyBackupCatalog(PrivacyOperationsSettings settings,PrivacySettings policy,PrivacySigningKeys keys,ObjectMapper json,Clock clock){this.settings=settings;this.policy=policy;this.keys=keys;this.json=json;this.clock=clock;}
    public boolean enabled(){return settings.enabled();}
    Path holdControlRoot(){return settings.root().resolve("hold-control");}
    Path heldFileControlRoot(){return settings.root().resolve("hold-control-files");}
    private Path metadata(){return settings.root().resolve("retention");}
    private Path records(){return metadata().resolve("copies");}
    private Path storeFile(){return metadata().resolve("store.json");}
    public <T>T locked(Work<T> work)throws IOException {
        if(!enabled())throw new IOException("Private operations inventory is not configured.");
        PrivacyOperationFiles.ordinary(settings.root());PrivacyOperationFiles.createPrivateDirectories(metadata());
        Path lock=metadata().resolve("operations.lock");if(PrivacyOperationFiles.existsVerified(lock,LinkOption.NOFOLLOW_LINKS))PrivacyOperationFiles.ordinary(lock);
        try(var channel=FileChannel.open(lock,StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
            FileLock acquired=null;long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
            while(acquired==null){
                try{acquired=channel.tryLock();}catch(OverlappingFileLockException busy){}
                if(acquired!=null)break;if(System.nanoTime()-deadline>=0)throw new IOException("Private operations maintenance is already running.");
                try{Thread.sleep(10);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new java.io.InterruptedIOException("Private operations lock wait interrupted.");}
            }
            try(var held=acquired){return work.run();}
        }
    }
    public Store initialize()throws IOException {
        Path root=settings.root();PrivacyOperationFiles.createPrivateDirectories(root);
        Path owner=root.resolve("owner.json");
        if(!PrivacyOperationFiles.existsVerified(owner)){
            try(var children=Files.list(root)){if(children.findAny().isPresent())throw new IOException("Refusing to initialize an unmanaged nonempty operations directory.");}
            PrivacyOperationFiles.atomicWrite(owner,json.writeValueAsBytes(Map.of("version",1,"application","unicarona-private")));
        }
        if(!"unicarona-private".equals(json.readTree(PrivacyOperationFiles.read(owner,8192)).path("application").asText()))throw new IOException("Private operations owner marker is missing.");
        return locked(()->{
            if(PrivacyOperationFiles.existsVerified(storeFile()))return store();
            PrivacyOperationFiles.createPrivateDirectories(records());
            try(var existing=Files.list(records())){if(existing.findAny().isPresent())throw new IOException("Inventory has records but lost its store identity.");}
            var value=signedStore(UUID.randomUUID(),null,0,sha(""),null,null,null);PrivacyOperationFiles.atomicWrite(storeFile(),json.writeValueAsBytes(value));return value;
        });
    }
    public Store store()throws IOException {
        var s=json.readValue(PrivacyOperationFiles.read(storeFile(),8192),Store.class);
        if(!Set.of(1,2).contains(s.version())||s.id()==null||s.minimumRestorePointEpochMs()<0||s.journalDigest()==null||!s.journalDigest().matches("[a-f0-9]{64}")
            ||(s.version()==1&&(s.holdHead()!=null||s.holdPending()!=null))||!validHead(s.holdHead())||!validHead(s.holdPending())
            ||(s.holdPending()!=null&&s.holdPending().sequence()!=(s.holdHead()==null?1:s.holdHead().sequence()+1))
            ||!keys.verify(JOURNAL,s.keyId(),storeBody(s),s.signature()))throw new IOException("Retained-copy store integrity check failed.");return s;
    }
    private String storeBody(UUID id,UUID dataset,long floor,String digest,PendingJournal p){return "retained-copy-store-v1|"+id+"|"+(dataset==null?"unbound":dataset)+"|"+floor+"|"+digest+"|"+(p==null?"none":p.operation()+"|"+p.name()+"|"+p.beforeDigest()+"|"+p.afterDigest()+"|"+sha(p.payload()));}
    private static boolean validHead(ControlHead h){return h==null||(h.sequence()>0&&h.operation()!=null&&h.file()!=null&&h.file().equals(h.operation()+".json")&&h.digest()!=null&&h.digest().matches("[a-f0-9]{64}"));}
    private String headBody(ControlHead h){return h==null?"none":h.sequence()+"|"+h.operation()+"|"+h.file()+"|"+h.digest();}
    private String storeBody(Store s){String base=storeBody(s.id(),s.datasetId(),s.minimumRestorePointEpochMs(),s.journalDigest(),s.pending());return s.version()==1?base:"retained-copy-store-v2|"+base+"|"+headBody(s.holdHead())+"|"+headBody(s.holdPending());}
    private Store signedStore(UUID id,UUID dataset,long floor,String digest,PendingJournal pending,ControlHead holdHead,ControlHead holdPending){String key=keys.current(JOURNAL);var s=new Store(2,id,dataset,floor,digest,pending,holdHead,holdPending,key,"");return new Store(s.version(),id,dataset,floor,digest,pending,holdHead,holdPending,key,keys.sign(JOURNAL,key,storeBody(s)));}
    public void bindDataset(UUID dataset)throws IOException {
        var s=store();if(s.datasetId()!=null){if(!s.datasetId().equals(dataset))throw new IOException("Database belongs to a different private recovery dataset.");return;}
        if(s.minimumRestorePointEpochMs()>0)throw new IOException("An unbound suppression checkpoint requires operator recovery.");
        PrivacyOperationFiles.atomicWrite(storeFile(),json.writeValueAsBytes(signedStore(s.id(),dataset,s.minimumRestorePointEpochMs(),s.journalDigest(),s.pending(),s.holdHead(),s.holdPending())));
    }
    public void beginJournalChange(PendingJournal pending,Instant floor)throws IOException {
        var current=store();if(current.pending()!=null||!current.journalDigest().equals(pending.beforeDigest())||!Set.of("put","remove").contains(pending.operation())||!pending.name().matches("(?:(?:file|orphan)-)?[a-f0-9-]{36}")||!pending.afterDigest().matches("[a-f0-9]{64}")||pending.payload()==null||pending.payload().length()>4096)throw new IOException("Invalid pending suppression operation.");
        long conservativeFloor=floor.toEpochMilli();if(floor.getNano()%1000000!=0)conservativeFloor=Math.addExact(conservativeFloor,1);
        PrivacyOperationFiles.atomicWrite(storeFile(),json.writeValueAsBytes(signedStore(current.id(),current.datasetId(),Math.max(current.minimumRestorePointEpochMs(),conservativeFloor),current.journalDigest(),pending,current.holdHead(),current.holdPending())));
    }
    public void completeJournalChange()throws IOException {
        var current=store();if(current.pending()==null)return;
        PrivacyOperationFiles.atomicWrite(storeFile(),json.writeValueAsBytes(signedStore(current.id(),current.datasetId(),current.minimumRestorePointEpochMs(),current.pending().afterDigest(),null,current.holdHead(),current.holdPending())));
    }
    /** Caller holds the operations file lock; a database decision must resolve the preparation. */
    void beginHoldChange(ControlHead proposed)throws IOException {
        var s=store();long expected=s.holdHead()==null?1:Math.addExact(s.holdHead().sequence(),1);
        if(s.datasetId()==null||s.holdPending()!=null||!validHead(proposed)||proposed==null||proposed.sequence()!=expected)throw new IOException("Invalid hold recovery preparation.");
        PrivacyOperationFiles.atomicWrite(storeFile(),json.writeValueAsBytes(signedStore(s.id(),s.datasetId(),s.minimumRestorePointEpochMs(),s.journalDigest(),s.pending(),s.holdHead(),proposed)));
    }
    void completeHoldChange(ControlHead expected,boolean committed)throws IOException {
        var s=store();if(expected==null||!validHead(expected))throw new IOException("Invalid hold recovery completion.");
        if(!expected.equals(s.holdPending())){
            if(s.holdHead()!=null&&(s.holdHead().equals(expected)||s.holdHead().sequence()>expected.sequence()))return;
            throw new IOException("Hold recovery completion does not match its preparation.");
        }
        PrivacyOperationFiles.atomicWrite(storeFile(),json.writeValueAsBytes(signedStore(s.id(),s.datasetId(),s.minimumRestorePointEpochMs(),s.journalDigest(),s.pending(),committed?expected:s.holdHead(),null)));
    }
    public static String sha(String value){try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException unavailable){throw new IllegalStateException("SHA256 unavailable");}}
    private String body(Copy c){return "retained-copy-v1|"+c.id()+"|"+c.storeId()+"|"+c.container()+"|"+c.name()+"|"+c.kind()+"|"+c.createdAtEpochMs()+"|"+c.restorePointEpochMs()+"|"+c.expiresAtEpochMs()+"|"+c.erasedAtEpochMs();}
    private Copy sign(Copy c){String key=keys.current(BACKUP);return new Copy(1,c.id(),c.storeId(),c.container(),c.name(),c.kind(),c.createdAtEpochMs(),c.restorePointEpochMs(),c.expiresAtEpochMs(),c.erasedAtEpochMs(),key,keys.sign(BACKUP,key,body(c)));}
    private Path resource(Copy c)throws IOException {
        if(!Set.of("backups","archives").contains(c.container()))throw new IOException("Invalid retained-copy container.");
        Path container=settings.root().resolve(c.container());PrivacyOperationFiles.createPrivateDirectories(container);
        if(!Files.getFileStore(settings.root()).equals(Files.getFileStore(container)))throw new IOException("Retained copies require the configured local filesystem; mounted copy stores need a dedicated verified adapter.");
        return PrivacyOperationFiles.child(container,c.name());
    }
    private void validate(Copy c,Store store)throws IOException {
        if(c.version()!=1||c.id()==null||!store.id().equals(c.storeId())||!Set.of("snapshot","historical","pending").contains(c.kind())||c.createdAtEpochMs()<0||c.createdAtEpochMs()>clock.millis()+300000
            ||c.restorePointEpochMs()<0||c.restorePointEpochMs()>clock.millis()+300000||(!"snapshot".equals(c.kind())&&c.restorePointEpochMs()!=0)
            ||c.expiresAtEpochMs()<=c.createdAtEpochMs()||c.expiresAtEpochMs()-c.createdAtEpochMs()>Duration.ofDays(90).toMillis()||c.erasedAtEpochMs()<0
            ||!keys.verify(BACKUP,c.keyId(),body(c),c.signature()))throw new IOException("Retained-copy record integrity check failed.");
        resource(c);
    }
    public Inventory inventory()throws IOException {
        var store=store();PrivacyOperationFiles.ordinary(records());var copies=new ArrayList<Copy>();var identified=new HashSet<String>();
        List<Path> names;try(var listing=Files.list(records())){names=listing.toList();}if(names.size()>10000)throw new IOException("Retained-copy inventory exceeds its supported size.");
        for(Path name:names){
            if(PrivacyOperationFiles.interruptedTemporary(name))continue;
            if(!name.getFileName().toString().matches("[a-f0-9-]{36}\\.json"))throw new IOException("Unexpected retained-copy inventory entry.");
            var c=json.readValue(PrivacyOperationFiles.read(name,16384),Copy.class);validate(c,store);
            if(!name.getFileName().toString().equals(c.id()+".json")||!identified.add(c.container()+"/"+c.name()))throw new IOException("Conflicting retained-copy record.");copies.add(c);
        }
        int present=0;
        for(String type:List.of("backups","archives")){
            Path directory=settings.root().resolve(type);PrivacyOperationFiles.createPrivateDirectories(directory);
            try(var listing=Files.list(directory)){for(Path entry:listing.toList()){
                PrivacyOperationFiles.ordinary(entry);if(!identified.contains(type+"/"+entry.getFileName()))throw new IOException("Unregistered retained copy prevents suppression expiry.");present++;
            }}
        }
        return new Inventory(store,List.copyOf(copies),present);
    }
    /** Import uses historical for files/collections that may themselves contain earlier snapshots. */
    public Copy register(String container,String name,Instant created,Instant restorePoint,boolean historical)throws IOException {
        var s=store();var c=new Copy(1,UUID.randomUUID(),s.id(),container,name,historical?"historical":"snapshot",created.toEpochMilli(),historical?0:restorePoint.toEpochMilli(),created.plus(Duration.ofDays(policy.backupRetentionDays())).toEpochMilli(),0,"","");c=sign(c);validate(c,s);
        if(!PrivacyOperationFiles.existsVerified(resource(c),LinkOption.NOFOLLOW_LINKS))throw new IOException("Cannot register an absent retained copy.");
        PrivacyOperationFiles.ordinary(records());
        try(var listing=Files.list(records())){for(Path entry:listing.toList()){
            if(PrivacyOperationFiles.interruptedTemporary(entry))continue;
            var existing=json.readValue(PrivacyOperationFiles.read(entry,16384),Copy.class);validate(existing,s);
            if(existing.container().equals(container)&&existing.name().equals(name)){
                if(existing.erasedAtEpochMs()!=0)throw new IOException("Do not reuse an expired retained-copy resource name.");return existing; // Never extend an existing deadline.
            }
        }}
        PrivacyOperationFiles.atomicWrite(records().resolve(c.id()+".json"),json.writeValueAsBytes(c));return c;
    }
    public boolean hasSurvivingCopyAtOrBefore(Instant prepared)throws IOException {
        return hasSurvivingCopyAtOrBefore(inventory(),prepared);
    }
    public boolean hasSurvivingCopyAtOrBefore(Inventory inventory,Instant prepared)throws IOException {
        for(var c:inventory.copies())if(c.restorePointEpochMs()<=prepared.toEpochMilli()&&PrivacyOperationFiles.existsVerified(resource(c),LinkOption.NOFOLLOW_LINKS))return true;return false;
    }
    /** Reserve before starting a database snapshot; an interrupted writer remains conservative. */
    public Copy reserve(String container,String name)throws IOException {
        try(var listing=Files.list(records())){for(Path entry:listing.toList()){
            if(PrivacyOperationFiles.interruptedTemporary(entry))continue;
            var existing=json.readValue(PrivacyOperationFiles.read(entry,16384),Copy.class);
            if(existing.container().equals(container)&&existing.name().equals(name))throw new IOException("Retained-copy resource names must be unique.");
        }}
        var c=register(container,name,clock.instant(),Instant.EPOCH,true);
        if(!"historical".equals(c.kind()))throw new IOException("Resource was already finalized.");
        var pending=sign(new Copy(c.version(),c.id(),c.storeId(),c.container(),c.name(),"pending",c.createdAtEpochMs(),0,c.expiresAtEpochMs(),0,"",""));
        PrivacyOperationFiles.atomicWrite(records().resolve(c.id()+".json"),json.writeValueAsBytes(pending));return pending;
    }
    public Copy finalizeSnapshot(UUID id,Instant restorePoint)throws IOException {
        var c=json.readValue(PrivacyOperationFiles.read(records().resolve(id+".json"),16384),Copy.class);var store=store();validate(c,store);
        if(!"pending".equals(c.kind())||c.erasedAtEpochMs()!=0||restorePoint.toEpochMilli()<store.minimumRestorePointEpochMs()||restorePoint.toEpochMilli()<c.createdAtEpochMs()-300000)throw new IOException("Snapshot cannot be finalized at this restore point.");
        var ready=sign(new Copy(c.version(),c.id(),c.storeId(),c.container(),c.name(),"snapshot",c.createdAtEpochMs(),restorePoint.toEpochMilli(),c.expiresAtEpochMs(),0,"",""));validate(ready,store);
        PrivacyOperationFiles.atomicWrite(records().resolve(id+".json"),json.writeValueAsBytes(ready));return ready;
    }
    /** Caller holds the cross-process lock. Each deletion stays inside one already-validated immediate child. */
    public Pruned prune(int budget)throws IOException {
        if(budget<1||budget>10000)throw new IllegalArgumentException("Invalid retained-copy cleanup batch.");
        var all=inventory();int deleted=PrivacyOperationFiles.pruneInterruptedTemporaries(metadata(),clock.instant(),budget),completed=0,removed=0,processed=0;
        if(deleted<budget)deleted+=PrivacyOperationFiles.pruneInterruptedTemporaries(records(),clock.instant(),budget-deleted);
        var ordered=new ArrayList<>(all.copies());ordered.sort(Comparator.comparingLong(Copy::expiresAtEpochMs).thenComparing(Copy::id));
        for(var c:ordered){
            if(deleted>=budget||processed>=budget)break;long due=Math.min(c.expiresAtEpochMs(),c.createdAtEpochMs()+Duration.ofDays(policy.backupRetentionDays()).toMillis());if(due>clock.millis())continue;
            Path target=resource(c);boolean present=PrivacyOperationFiles.existsVerified(target,LinkOption.NOFOLLOW_LINKS);
            if(!present&&c.erasedAtEpochMs()!=0&&c.erasedAtEpochMs()>=clock.millis()-Duration.ofDays(90).toMillis())continue;
            processed++;if(present)deleted+=erase(target,budget-deleted);
            if(!PrivacyOperationFiles.existsVerified(target,LinkOption.NOFOLLOW_LINKS)){
                if(c.erasedAtEpochMs()==0){
                    var done=sign(new Copy(c.version(),c.id(),c.storeId(),c.container(),c.name(),c.kind(),c.createdAtEpochMs(),c.restorePointEpochMs(),c.expiresAtEpochMs(),clock.millis(),"",""));
                    PrivacyOperationFiles.atomicWrite(records().resolve(c.id()+".json"),json.writeValueAsBytes(done));completed++;
                }else if(c.erasedAtEpochMs()<clock.millis()-Duration.ofDays(90).toMillis()){
                    Files.delete(records().resolve(c.id()+".json"));removed++;
                }
            }
        }
        return new Pruned(deleted,completed,removed);
    }
    private int erase(Path target,int budget)throws IOException {
        PrivacyOperationFiles.ordinary(target);final int[] deleted={0};
        Files.walkFileTree(target,new SimpleFileVisitor<>(){
            private void verify(Path path)throws IOException {
                Path normalized=path.toAbsolutePath().normalize();if(!normalized.startsWith(target)||!normalized.startsWith(settings.root().resolve("backups"))&&!normalized.startsWith(settings.root().resolve("archives")))throw new IOException("Retained-copy cleanup escaped its allowed scope.");PrivacyOperationFiles.ordinary(path);
                if(!Files.getFileStore(settings.root()).equals(Files.getFileStore(path)))throw new IOException("Retained-copy cleanup refuses mounted child filesystems.");
            }
            @Override public FileVisitResult preVisitDirectory(Path dir,BasicFileAttributes attrs)throws IOException {verify(dir);return deleted[0]>=budget?FileVisitResult.TERMINATE:FileVisitResult.CONTINUE;}
            @Override public FileVisitResult visitFile(Path file,BasicFileAttributes attrs)throws IOException {verify(file);if(deleted[0]>=budget)return FileVisitResult.TERMINATE;Files.delete(file);deleted[0]++;return FileVisitResult.CONTINUE;}
            @Override public FileVisitResult postVisitDirectory(Path dir,IOException error)throws IOException {if(error!=null)throw error;verify(dir);if(deleted[0]>=budget)return FileVisitResult.TERMINATE;try{Files.delete(dir);deleted[0]++;}catch(DirectoryNotEmptyException pending){}return FileVisitResult.CONTINUE;}
        });return deleted[0];
    }
    public int rekey(int batch)throws IOException {
        var all=inventory();int changed=0;
        if(!all.store().keyId().equals(keys.current(JOURNAL))){PrivacyOperationFiles.atomicWrite(storeFile(),json.writeValueAsBytes(signedStore(all.store().id(),all.store().datasetId(),all.store().minimumRestorePointEpochMs(),all.store().journalDigest(),all.store().pending(),all.store().holdHead(),all.store().holdPending())));changed++;}
        for(var c:all.copies()){if(changed>=batch)break;if(!c.keyId().equals(keys.current(BACKUP))){PrivacyOperationFiles.atomicWrite(records().resolve(c.id()+".json"),json.writeValueAsBytes(sign(c)));changed++;}}
        return changed;
    }
}
