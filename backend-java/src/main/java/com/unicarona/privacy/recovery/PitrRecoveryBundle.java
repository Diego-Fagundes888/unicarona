package com.unicarona.privacy.recovery;

import com.unicarona.privacy.service.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** One bounded capture window; the existing catalog owns expiry of base and WAL together. */
public final class PitrRecoveryBundle {
    private final PrivacyBackupCatalog catalog;private final PrivacyBackupCatalog.Copy copy;private final PitrEncryptedArchive archive;
    private PitrRecoveryBundle(PrivacyBackupCatalog catalog,PrivacyBackupCatalog.Copy copy,PitrEncryptedArchive archive){this.catalog=catalog;this.copy=copy;this.archive=archive;}
    public static PitrRecoveryBundle reserve(PrivacyBackupCatalog catalog,Path operations,UUID dataset,String systemId,Map<String,byte[]> keys,String keyId,Clock clock)throws IOException {
        String name=UUID.randomUUID().toString();var copy=catalog.locked(()->{if(dataset==null||!dataset.equals(catalog.store().datasetId()))throw new IOException("Recovery capture must match the bound privacy dataset.");Path parent=operations.resolve("archives");PrivacyOperationFiles.createPrivateDirectories(parent);Path root=PrivacyOperationFiles.child(parent,name);if(Files.exists(root,LinkOption.NOFOLLOW_LINKS))throw new IOException("Recovery bundle resource already exists.");PrivacyOperationFiles.createPrivateDirectories(root);return catalog.reserve("archives",name);});
        var archive=new PitrEncryptedArchive(operations.resolve("archives").resolve(name),keys,clock);
        archive.initialize(dataset,systemId,Instant.ofEpochMilli(copy.createdAtEpochMs()),Instant.ofEpochMilli(copy.expiresAtEpochMs()),keyId);return new PitrRecoveryBundle(catalog,copy,archive);
    }
    public PitrEncryptedArchive archive(){return archive;}
    public UUID copyId(){return copy.id();}
    public Path resource(Path operations){return operations.resolve(copy.container()).resolve(copy.name());}
    /** Oldest data is the base, not the later replay target: suppression instructions must survive it. */
    public PrivacyBackupCatalog.Copy finalizeCapture(Instant baseSnapshotStarted)throws IOException {
        var index=archive.index();if(!index.state().equals("sealed"))throw new IOException("Recovery capture is not sealed.");
        return catalog.locked(()->catalog.finalizeSnapshot(copy.id(),baseSnapshotStarted));
    }
}
