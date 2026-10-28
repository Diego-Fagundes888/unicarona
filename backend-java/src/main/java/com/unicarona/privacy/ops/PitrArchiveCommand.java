package com.unicarona.privacy.ops;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unicarona.privacy.recovery.PitrEncryptedArchive;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;

/** PostgreSQL archive/restore command. Keys exist only in the inherited private environment. */
public final class PitrArchiveCommand {
    private PitrArchiveCommand(){}
    public static void main(String[] args){
        try{
            if(args.length!=3||!Set.of("seal","fetch").contains(args[0]))throw new IllegalArgumentException();
            var encoded=new ObjectMapper().readValue(required("UNICARONA_PITR_KEYS"),new TypeReference<Map<String,String>>(){});var keys=new HashMap<String,byte[]>();for(var item:encoded.entrySet())keys.put(item.getKey(),Base64.getDecoder().decode(item.getValue()));
            var store=new PitrEncryptedArchive(Path.of(required("UNICARONA_PITR_ROOT")),keys,Clock.systemUTC());Path data=Path.of(required("UNICARONA_PITR_DATA_ROOT"));
            if(args[0].equals("seal"))store.archiveWal(data,Path.of(args[1]),args[2]);else store.fetchWal(data,Path.of(args[1]),args[2]);
        }catch(Exception failure){if(!(args.length>0&&args[0].equals("fetch")&&failure instanceof java.nio.file.NoSuchFileException))System.err.println("PITR_ARCHIVE_OPERATION_FAILED");System.exit(1);}
    }
    private static String required(String key){String value=System.getenv(key);if(value==null||value.isBlank())throw new IllegalArgumentException();return value;}
}
