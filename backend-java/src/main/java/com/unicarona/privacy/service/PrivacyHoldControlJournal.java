package com.unicarona.privacy.service;

import com.fasterxml.jackson.databind.*;
import com.unicarona.privacy.config.PrivacySigningKeys;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import static com.unicarona.privacy.config.PrivacySigningKeys.Purpose.JOURNAL;
import static com.unicarona.privacy.service.PrivacyEvidenceCrypto.Field.HOLD_RECOVERY_CONTROL;
import static com.unicarona.privacy.service.PrivacyBackupCatalog.ControlHead;

/** Independent encrypted control records. Database commit/replay coordination is a separate layer.
 * A prepared record is deliberately never treated as an approved retention decision. */
@Service
public class PrivacyHoldControlJournal {
    private static final int MAX_PLAIN=16*1024*1024,MAX_RECORD=24*1024*1024;
    public record Prepared(ControlHead target){@Override public String toString(){return "PreparedHoldControl[redacted]";}}
    public record Snapshot(ControlHead head,JsonNode state){@Override public String toString(){return "HoldControlSnapshot[redacted]";}}
    private record Envelope(int version,UUID dataset,long sequence,UUID operation,String ciphertext,String keyId,String signature){}
    private final PrivacyBackupCatalog catalog;private final PrivacyEvidenceCrypto evidence;private final PrivacySigningKeys keys;private final ObjectMapper json;private final Clock clock;private final com.fasterxml.jackson.core.JsonFactory recordFactory;
    public PrivacyHoldControlJournal(PrivacyBackupCatalog catalog,PrivacyEvidenceCrypto evidence,PrivacySigningKeys keys,ObjectMapper json,Clock clock){this.catalog=catalog;this.evidence=evidence;this.keys=keys;this.json=json;this.clock=clock;recordFactory=json.getFactory().copy();recordFactory.setStreamReadConstraints(com.fasterxml.jackson.core.StreamReadConstraints.builder().maxStringLength(MAX_RECORD).maxNestingDepth(64).build());}
    public boolean enabled(){return catalog.enabled();}
    boolean currentNeedsRekey()throws IOException {
        return catalog.locked(()->{var store=catalog.store();if(store.holdPending()!=null)throw new IOException("Resolve hold control before rekeying.");checkInventory(store);if(store.holdHead()==null)return false;read(store.holdHead(),store.datasetId());var value=envelope(PrivacyOperationFiles.child(root(),store.holdHead().file()),store.datasetId());return !evidence.isCurrent(value.ciphertext())||!keys.current(JOURNAL).equals(value.keyId());});
    }
    private static String digest(byte[] data){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(data));}catch(java.security.NoSuchAlgorithmException error){throw new IllegalStateException("SHA256 unavailable");}}
    private String body(Envelope e){return "hold-recovery-envelope-v1|"+e.dataset()+"|"+e.sequence()+"|"+e.operation()+"|"+PrivacyBackupCatalog.sha(e.ciphertext());}
    private Path root()throws IOException {Path root=catalog.holdControlRoot();PrivacyOperationFiles.createPrivateDirectories(root);return root;}
    private List<Path> records()throws IOException {
        List<Path> paths;try(var files=Files.list(root())){paths=files.toList();}if(paths.size()>10000)throw new IOException("Hold control record inventory exceeds its bound.");
        var result=new ArrayList<Path>();for(Path path:paths){if(PrivacyOperationFiles.interruptedTemporary(path,MAX_RECORD))continue;
            if(!path.getFileName().toString().matches("[a-f0-9-]{36}\\.json")||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("Unexpected hold control record.");result.add(path);
        }return result;
    }
    private Envelope envelope(Path file,UUID dataset)throws IOException {
        try{
            Envelope e;try(var parser=recordFactory.createParser(PrivacyOperationFiles.read(file,MAX_RECORD))){parser.enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);e=json.readValue(parser,Envelope.class);if(parser.nextToken()!=null)throw new IOException("Trailing control record data.");}
            if(e.version()!=1||!Objects.equals(e.dataset(),dataset)||e.operation()==null||e.sequence()<1||!file.getFileName().toString().equals(e.operation()+".json")||e.ciphertext()==null||!e.ciphertext().startsWith("pvc1.")||!keys.verify(JOURNAL,e.keyId(),body(e),e.signature()))throw new IOException("Hold control record integrity check failed.");return e;
        }catch(IOException error){throw error;}catch(RuntimeException invalid){throw new IOException("Hold control record cannot be verified.");}
    }
    private Snapshot read(ControlHead head,UUID dataset)throws IOException {
        Path path=PrivacyOperationFiles.child(root(),head.file());byte[] bytes=PrivacyOperationFiles.read(path,MAX_RECORD);
        if(!digest(bytes).equals(head.digest()))throw new IOException("Hold control record is missing, changed or rolled back.");Envelope e=envelope(path,dataset);
        if(e.sequence()!=head.sequence()||!e.operation().equals(head.operation()))throw new IOException("Hold control identity does not match its checkpoint.");
        try{
            String clear=evidence.open(HOLD_RECOVERY_CONTROL,dataset,e.operation(),e.ciphertext());if(clear.getBytes(StandardCharsets.UTF_8).length>MAX_PLAIN)throw new IOException("Hold control snapshot exceeds its bound.");
            JsonNode state;try(var parser=recordFactory.createParser(clear)){parser.enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);state=json.readTree(parser);if(parser.nextToken()!=null)throw new IOException("Trailing control snapshot data.");}
            if(state==null||!state.isObject())throw new IOException("Hold control snapshot must be an object.");return new Snapshot(head,state);
        }catch(IOException error){throw error;}catch(RuntimeException invalid){throw new IOException("Hold control snapshot cannot be decrypted.");}
    }
    private void checkInventory(PrivacyBackupCatalog.Store store)throws IOException {
        long highest=store.holdPending()!=null?store.holdPending().sequence():store.holdHead()==null?0:store.holdHead().sequence();
        for(Path path:records()){
            Envelope e=envelope(path,store.datasetId());
            boolean referenced=path.getFileName().toString().equals(store.holdHead()==null?"":store.holdHead().file())||path.getFileName().toString().equals(store.holdPending()==null?"":store.holdPending().file());
            if(e.sequence()>highest||(!referenced&&e.sequence()==highest))throw new IOException("Unanchored hold control requires database recovery; do not assume rollback.");
        }
    }
    public Optional<Snapshot> current()throws IOException {
        return catalog.locked(()->{var store=catalog.store();if(store.holdPending()!=null)throw new IOException("Unresolved hold control preparation requires database recovery.");checkInventory(store);return store.holdHead()==null?Optional.empty():Optional.of(read(store.holdHead(),store.datasetId()));});
    }
    /** Exposed only to a trusted database coordinator; reading it does not resolve its outcome. */
    public Optional<Snapshot> pending()throws IOException {
        return catalog.locked(()->{var store=catalog.store();checkInventory(store);return store.holdPending()==null?Optional.empty():Optional.of(read(store.holdPending(),store.datasetId()));});
    }
    /** Internal cleanup view under the caller's operations lock. Both states protect their files;
     * this grants no authority to consume a preparation as a committed business decision. */
    List<Snapshot> snapshotsForFileCleanupLocked()throws IOException {
        var store=catalog.store();checkInventory(store);var snapshots=new ArrayList<Snapshot>();if(store.holdHead()!=null)snapshots.add(read(store.holdHead(),store.datasetId()));if(store.holdPending()!=null)snapshots.add(read(store.holdPending(),store.datasetId()));return List.copyOf(snapshots);
    }
    public Prepared prepare(JsonNode state)throws IOException {
        if(state==null||!state.isObject())throw new IOException("Hold control snapshot must be an object.");byte[] plain=json.writeValueAsBytes(state);if(plain.length>MAX_PLAIN)throw new IOException("Hold control snapshot exceeds its bound.");
        return catalog.locked(()->{
            var store=catalog.store();if(store.datasetId()==null||store.holdPending()!=null)throw new IOException("Hold control needs a bound dataset and a resolved preparation.");checkInventory(store);if(store.holdHead()!=null)read(store.holdHead(),store.datasetId());
            long sequence=store.holdHead()==null?1:Math.addExact(store.holdHead().sequence(),1);UUID operation=UUID.randomUUID();String cipher=evidence.seal(HOLD_RECOVERY_CONTROL,store.datasetId(),operation,new String(plain,StandardCharsets.UTF_8)),key=keys.current(JOURNAL);
            var unsigned=new Envelope(1,store.datasetId(),sequence,operation,cipher,key,"");var signed=new Envelope(1,unsigned.dataset(),sequence,operation,cipher,key,keys.sign(JOURNAL,key,body(unsigned)));byte[] bytes=json.writeValueAsBytes(signed);if(bytes.length>MAX_RECORD)throw new IOException("Hold control envelope exceeds its bound.");
            String name=operation+".json";PrivacyOperationFiles.atomicWrite(PrivacyOperationFiles.child(root(),name),bytes);var head=new ControlHead(sequence,operation,name,digest(bytes));catalog.beginHoldChange(head);return new Prepared(head);
        });
    }
    /** Called only after the database coordinator proves COMMIT or receives a known ROLLBACK. */
    public void resolve(Prepared prepared,boolean committed)throws IOException {
        if(prepared==null||prepared.target()==null)throw new IOException("Missing hold control preparation.");catalog.locked(()->{
            var store=catalog.store();ControlHead target=prepared.target();if(store.holdHead()!=null&&(store.holdHead().equals(target)||store.holdHead().sequence()>target.sequence())){if(!committed)throw new IOException("A committed hold control cannot be classified as rolled back.");return null;}
            if(!target.equals(store.holdPending()))throw new IOException("Hold control outcome does not match its preparation.");checkInventory(store);read(target,store.datasetId());if(!committed&&store.holdHead()!=null)read(store.holdHead(),store.datasetId());
            // A crash between either operation deliberately leaves an unresolved checkpoint.
            // Only a verified rollback may remove this exact, signed preparation.
            if(!committed)Files.delete(PrivacyOperationFiles.child(root(),target.file()));catalog.completeHoldChange(target,committed);return null;
        });
    }
    /** Retires obsolete signed heads only, never the committed or prepared control record. */
    public int pruneObsolete(int batch)throws IOException {
        if(batch<1||batch>10000)throw new IllegalArgumentException("Invalid control cleanup batch.");return catalog.locked(()->{
            var store=catalog.store();checkInventory(store);if(store.holdPending()!=null)throw new IOException("Resolve hold control before retiring obsolete records.");if(store.holdHead()!=null)read(store.holdHead(),store.datasetId());int removed=PrivacyOperationFiles.pruneInterruptedTemporaries(root(),clock.instant(),batch,MAX_RECORD);
            for(Path path:records()){
                if(removed>=batch)break;Envelope e=envelope(path,store.datasetId());
                if(store.holdHead()!=null&&e.sequence()<store.holdHead().sequence()){Files.delete(path);removed++;}
            }return removed;
        });
    }
}
