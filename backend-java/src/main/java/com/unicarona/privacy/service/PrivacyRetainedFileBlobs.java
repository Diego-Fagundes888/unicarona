package com.unicarona.privacy.service;

import com.fasterxml.jackson.databind.*;
import com.unicarona.privacy.config.PrivacySigningKeys;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import static com.unicarona.privacy.config.PrivacySigningKeys.Purpose.JOURNAL;
import static com.unicarona.privacy.service.PrivacyEvidenceCrypto.Field.HOLD_RECOVERY_FILE;

/** Encrypted independent copies of files under retention, outside expirable backup containers.
 * References belong inside the encrypted typed control snapshot, never in a public URL. */
@Service
public class PrivacyRetainedFileBlobs {
    private static final int MAX_FILE=5*1024*1024,MAX_RECORD=12*1024*1024;
    public record Reference(UUID blob,UUID owner,UUID file,int bytes,String sha256,String digest){@Override public String toString(){return "RetainedFileReference[redacted]";}}
    private record Payload(UUID owner,UUID file,int bytes,String sha256,String content){@Override public String toString(){return "RetainedFilePayload[redacted]";}}
    private record Envelope(int version,UUID dataset,UUID blob,String ciphertext,String keyId,String signature){}
    private final PrivacyBackupCatalog catalog;private final PrivacyHoldControlJournal journal;private final PrivacyEvidenceCrypto evidence;private final PrivacySigningKeys keys;private final ObjectMapper json;private final Clock clock;private final com.fasterxml.jackson.core.JsonFactory recordFactory;
    public PrivacyRetainedFileBlobs(PrivacyBackupCatalog catalog,PrivacyHoldControlJournal journal,PrivacyEvidenceCrypto evidence,PrivacySigningKeys keys,ObjectMapper json,Clock clock){this.catalog=catalog;this.journal=journal;this.evidence=evidence;this.keys=keys;this.json=json;this.clock=clock;recordFactory=json.getFactory().copy();recordFactory.setStreamReadConstraints(com.fasterxml.jackson.core.StreamReadConstraints.builder().maxStringLength(MAX_RECORD).maxNestingDepth(64).build());}
    private static String hash(byte[] bytes){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}catch(java.security.NoSuchAlgorithmException invalid){throw new IllegalStateException("SHA256 unavailable");}}
    private static void valid(Reference ref)throws IOException {if(ref==null||ref.blob()==null||ref.owner()==null||ref.file()==null||ref.bytes()<1||ref.bytes()>MAX_FILE||ref.sha256()==null||!ref.sha256().matches("[a-f0-9]{64}")||ref.digest()==null||!ref.digest().matches("[a-f0-9]{64}"))throw new IOException("Invalid retained file reference.");}
    private Path root()throws IOException {Path root=catalog.heldFileControlRoot();PrivacyOperationFiles.createPrivateDirectories(root);return root;}
    private String body(Envelope e){return "held-file-control-v1|"+e.dataset()+"|"+e.blob()+"|"+PrivacyBackupCatalog.sha(e.ciphertext());}
    private Envelope envelope(Path path,UUID dataset)throws IOException {
        try{
            Envelope value;try(var parser=recordFactory.createParser(PrivacyOperationFiles.read(path,MAX_RECORD))){parser.enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);value=json.readValue(parser,Envelope.class);if(parser.nextToken()!=null)throw new IOException("Trailing retained file record data.");}
            if(value.version()!=1||!Objects.equals(value.dataset(),dataset)||value.blob()==null||!path.getFileName().toString().equals(value.blob()+".json")||value.ciphertext()==null||!value.ciphertext().startsWith("pvc1.")||!keys.verify(JOURNAL,value.keyId(),body(value),value.signature()))throw new IOException("Retained file record integrity check failed.");return value;
        }catch(IOException failure){throw failure;}catch(RuntimeException failure){throw new IOException("Retained file record cannot be verified.");}
    }
    private byte[] readLocked(Reference ref,UUID dataset)throws IOException {
        valid(ref);Path path=PrivacyOperationFiles.child(root(),ref.blob()+".json");byte[] record=PrivacyOperationFiles.read(path,MAX_RECORD);if(!hash(record).equals(ref.digest()))throw new IOException("Retained file record is missing or changed.");Envelope envelope=envelope(path,dataset);
        try{
            Payload value;String clear=evidence.open(HOLD_RECOVERY_FILE,dataset,ref.blob(),envelope.ciphertext());try(var parser=recordFactory.createParser(clear)){parser.enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);value=json.readValue(parser,Payload.class);if(parser.nextToken()!=null)throw new IOException("Trailing retained file payload data.");}
            if(!ref.owner().equals(value.owner())||!ref.file().equals(value.file())||ref.bytes()!=value.bytes()||!ref.sha256().equals(value.sha256())||value.content()==null||value.content().length()>7*1024*1024)throw new IOException("Retained file identity does not match its reference.");
            byte[] bytes=Base64.getDecoder().decode(value.content());if(bytes.length!=ref.bytes()||!hash(bytes).equals(ref.sha256()))throw new IOException("Retained file content does not match its expected hash.");return bytes;
        }catch(IOException failure){throw failure;}catch(RuntimeException failure){throw new IOException("Retained file cannot be decrypted.");}
    }
    public byte[] read(Reference ref)throws IOException {return catalog.locked(()->{UUID dataset=catalog.store().datasetId();if(dataset==null)throw new IOException("Retained files need a bound dataset.");return readLocked(ref,dataset);});}
    /** Reuse is verified, not assumed; a key change creates another immutable encrypted blob.
     * The old reference remains available until its control snapshot stops protecting it. */
    public Reference capture(UUID owner,UUID file,int size,String sha256,byte[] data,Reference reusable)throws IOException {
        if(owner==null||file==null||data==null||size<1||size>MAX_FILE||data.length!=size||sha256==null||!sha256.matches("[a-f0-9]{64}")||!hash(data).equals(sha256))throw new IOException("Retained file source does not match its metadata.");
        return catalog.locked(()->{
            UUID dataset=catalog.store().datasetId();if(dataset==null)throw new IOException("Retained files need a bound dataset.");
            if(reusable!=null){valid(reusable);if(!owner.equals(reusable.owner())||!file.equals(reusable.file())||size!=reusable.bytes()||!sha256.equals(reusable.sha256()))throw new IOException("Retained file reuse does not match the resource.");readLocked(reusable,dataset);Envelope old=envelope(PrivacyOperationFiles.child(root(),reusable.blob()+".json"),dataset);if(evidence.isCurrent(old.ciphertext())&&old.keyId().equals(keys.current(JOURNAL)))return reusable;}
            UUID id=UUID.randomUUID();String clear=json.writeValueAsString(new Payload(owner,file,size,sha256,Base64.getEncoder().encodeToString(data))),cipher=evidence.seal(HOLD_RECOVERY_FILE,dataset,id,clear),key=keys.current(JOURNAL);var unsigned=new Envelope(1,dataset,id,cipher,key,"");var signed=new Envelope(1,dataset,id,cipher,key,keys.sign(JOURNAL,key,body(unsigned)));byte[] record=json.writeValueAsBytes(signed);if(record.length>MAX_RECORD)throw new IOException("Retained file record exceeds its bound.");
            PrivacyOperationFiles.atomicWrite(PrivacyOperationFiles.child(root(),id+".json"),record);return new Reference(id,owner,file,size,sha256,hash(record));
        });
    }
    private Set<Reference> protectedReferences()throws IOException {
        var refs=new HashSet<Reference>();for(var snapshot:journal.snapshotsForFileCleanupLocked()){
            JsonNode state=snapshot.state();if(!state.path("format").isInt()||!Set.of(1,2,3).contains(state.path("format").asInt())||!state.path("kind").asText().equals("privacy-retention-snapshot")||!state.path("files").isArray()||state.path("files").size()>10000)throw new IOException("Typed retention control is required before file cleanup.");
            for(var item:state.path("files")){try(var parser=json.treeAsTokens(item.path("blob"))){Reference ref=json.readerFor(Reference.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(parser);valid(ref);refs.add(ref);}catch(IOException failure){throw failure;}catch(RuntimeException failure){throw new IOException("Invalid retained file reference in control snapshot.");}}
        }return refs;
    }
    /** Call under database retention coordination. The file lock keeps checkpoint reads and deletion
     * together; a one-day allowance also protects blobs written before publishing a preparation. */
    public int pruneUnreferenced(int batch)throws IOException {
        if(batch<1||batch>10000)throw new IllegalArgumentException("Invalid retained file cleanup batch.");return catalog.locked(()->{
            UUID dataset=catalog.store().datasetId();if(dataset==null)throw new IOException("Retained files need a bound dataset.");Set<Reference> protectedRefs=protectedReferences();var protectedIds=new HashSet<UUID>();for(var ref:protectedRefs){readLocked(ref,dataset);protectedIds.add(ref.blob());}
            List<Path> paths;try(var files=Files.list(root())){paths=files.toList();}if(paths.size()>100000)throw new IOException("Retained file inventory exceeds its bound.");
            // Validate the entire inventory before deleting any record.
            var removable=new ArrayList<Path>();for(Path path:paths){if(PrivacyOperationFiles.interruptedTemporary(path,MAX_RECORD))continue;if(!path.getFileName().toString().matches("[a-f0-9-]{36}\\.json")||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("Unexpected retained file record.");Envelope value=envelope(path,dataset);if(!protectedIds.contains(value.blob())&&Files.getLastModifiedTime(path,LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(clock.instant().minus(Duration.ofDays(1))))removable.add(path);}
            int removed=PrivacyOperationFiles.pruneInterruptedTemporaries(root(),clock.instant(),batch,MAX_RECORD);for(Path path:removable){if(removed>=batch)break;Files.delete(path);removed++;}return removed;
        });
    }
}
