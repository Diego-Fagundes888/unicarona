package com.unicarona.privacy.config;

import com.unicarona.file.config.StorageSettings;
import java.nio.file.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Active data, configuration and runtimes are outside the only two expirable containers. */
@Component
public final class PrivacyOperationsSettings {
    private final Path root;
    public PrivacyOperationsSettings(Environment env,StorageSettings files){
        String value=env.getProperty("PRIVACY_OPERATIONS_ROOT","").strip();root=value.isEmpty()?null:Path.of(value).toAbsolutePath().normalize();
        if(root==null){if("production".equals(env.getProperty("NODE_ENV")))throw new IllegalStateException("Production requires an independent retained-copy inventory.");return;}
        if(root.getParent()==null||root.getNameCount()<2)throw new IllegalStateException("Private operations cannot use a filesystem or top-level directory as their root.");
        Path journal=Path.of(env.getProperty("PRIVACY_ERASURE_JOURNAL_PATH",files.root().resolveSibling(files.root().getFileName()+"-privacy-journal").toString())).toAbsolutePath().normalize();
        for(String container:new String[]{"backups","archives","retention","hold-control","hold-control-files"})for(Path protectedPath:new Path[]{files.root(),files.publicRoot(),journal}){
            Path candidate=root.resolve(container);if(candidate.startsWith(protectedPath)||protectedPath.startsWith(candidate))throw new IllegalStateException("Retained copies must be separate from active/public data and erasure journal.");
        }
        if(root.startsWith(files.publicRoot())||root.equals(files.root())||root.equals(journal))throw new IllegalStateException("Invalid private operations root.");
    }
    public boolean enabled(){return root!=null;}
    public Path root(){if(root==null)throw new IllegalStateException("Private operations inventory is not configured.");return root;}
    @Override public String toString(){return "PrivacyOperationsSettings[redacted]";}
}
