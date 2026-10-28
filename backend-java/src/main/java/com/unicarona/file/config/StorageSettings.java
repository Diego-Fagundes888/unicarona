package com.unicarona.file.config;

import java.nio.file.Path;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class StorageSettings {
    private final Path root,publicRoot;
    private final long quota;
    private final int maximumFiles;
    public StorageSettings(Environment env) {
        root=Path.of(env.getProperty("STORAGE_PATH",".local/storage")).toAbsolutePath().normalize();
        publicRoot=Path.of(env.getProperty("FRONTEND_PATH","dist")).toAbsolutePath().normalize();
        quota=env.getProperty("STORAGE_USER_QUOTA_BYTES",Long.class,52428800L);
        maximumFiles=env.getProperty("STORAGE_USER_MAX_FILES",Integer.class,100);
        if(quota<1 || quota>1073741824L || maximumFiles<1 || maximumFiles>1000) throw new IllegalStateException("Invalid storage quota configuration.");
    }
    public Path root() { return root; }
    public Path publicRoot() { return publicRoot; }
    public long quota() { return quota; }
    public int maximumFiles() { return maximumFiles; }
}
