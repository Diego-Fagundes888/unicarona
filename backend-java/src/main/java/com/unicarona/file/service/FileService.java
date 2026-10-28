package com.unicarona.file.service;

import com.unicarona.file.dto.FileDto;
import com.unicarona.file.repository.FileAccessRepository;
import com.unicarona.file.repository.StoredFileRepository;
import com.unicarona.file.storage.PrivateFileStorage;
import com.unicarona.security.Actor;
import com.unicarona.shared.ApiException;
import com.unicarona.shared.TransactionRetries;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class FileService {
    public record Download(byte[] data,String contentType,String purpose) {
        @Override public String toString() { return "Download[redacted]"; }
    }
    private final ImageProcessor images;
    private final PrivateFileStorage storage;
    private final FileTransactions transactions;
    private final StoredFileRepository files;
    private final FileAccessRepository access;
    private final TransactionRetries retries;
    public FileService(ImageProcessor images,PrivateFileStorage storage,FileTransactions transactions,StoredFileRepository files,FileAccessRepository access,TransactionRetries retries) {
        this.images=images;this.storage=storage;this.transactions=transactions;this.files=files;this.access=access;this.retries=retries;
    }
    public FileDto upload(Actor actor,String purpose,byte[] source) throws IOException {
        ApiException.require(purpose!=null && Set.of("photo","identity","license","vehicle").contains(purpose),"VALIDATION_ERROR","Confira os campos informados.",422);
        var prepared=images.prepare(purpose,source);UUID id=UUID.randomUUID();String digest;
        try { digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(prepared.data())); }
        catch(java.security.NoSuchAlgorithmException error) { throw new IllegalStateException("SHA-256 unavailable."); }
        try{return retries.call(()->transactions.upload(actor,id,purpose,prepared,digest));}
        catch(java.io.UncheckedIOException unavailable){throw unavailable.getCause();}
    }
    public Download download(Actor actor,UUID id) throws IOException {
        var file=files.findByIdAndDeletedAtIsNull(id).orElseThrow(() -> new ApiException("FILE_NOT_FOUND","Arquivo não encontrado.",404));
        if(!"photo".equals(file.getPurpose()))ApiException.require(access.documentAvailable(id),"FILE_NOT_FOUND","Arquivo não encontrado.",404);
        if(!file.getOwnerId().equals(actor.id()) && !"admin".equals(actor.role())) {
            ApiException.require("photo".equals(file.getPurpose()),"FORBIDDEN","Arquivo não disponível.",404);
            ApiException.require(access.linkedPhoto(actor.id(),file.getOwnerId(),id),"FORBIDDEN","Arquivo não disponível.",404);
        }
        return new Download(storage.get(file.getStorageKey()),file.getContentType(),file.getPurpose());
    }
}
