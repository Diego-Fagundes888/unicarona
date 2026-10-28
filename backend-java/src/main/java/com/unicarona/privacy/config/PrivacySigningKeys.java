package com.unicarona.privacy.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unicarona.auth.config.AuthSettings;
import com.unicarona.security.CryptoService;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Purpose-separated versioned keys. Rotating these does not change authentication or financial keys. */
@Component
public final class PrivacySigningKeys {
    public enum Purpose { BACKUP, JOURNAL }
    private record Ring(String current,Map<String,String> keys) { @Override public String toString(){return "SigningRing[redacted]";} }
    private final Map<Purpose,Ring> rings;private final CryptoService crypto;
    @org.springframework.beans.factory.annotation.Autowired
    public PrivacySigningKeys(Environment env,AuthSettings auth,CryptoService crypto,ObjectMapper json){this(env,auth.pepper(),crypto,json);}
    public static PrivacySigningKeys forOperations(Environment env,CryptoService crypto,ObjectMapper json){return new PrivacySigningKeys(env,env.getProperty("TOKEN_PEPPER",""),crypto,json);}
    private PrivacySigningKeys(Environment env,String legacyPepper,CryptoService crypto,ObjectMapper json){
        this.crypto=crypto;var result=new EnumMap<Purpose,Ring>(Purpose.class);
        for(var purpose:Purpose.values()){
            String prefix="PRIVACY_"+purpose.name(),current=env.getProperty(prefix+"_KEY_ID","").strip(),encoded=env.getProperty(prefix+"_KEYS","").strip();
            if(current.isEmpty()&&encoded.isEmpty()){
                if("production".equals(env.getProperty("NODE_ENV")))throw new IllegalStateException("Production requires dedicated privacy signing key rings.");
                if(legacyPepper.length()<32)throw new IllegalStateException("Local derived privacy keys require the retained authentication pepper.");
                current="derived-local-v1";result.put(purpose,new Ring(current,Map.of(current,crypto.hmac("privacy-"+purpose.name().toLowerCase(Locale.ROOT)+"-local-signing-v1",legacyPepper))));continue;
            }
            if(!current.matches("[a-zA-Z0-9_-]{1,48}")||encoded.length()>8192)throw new IllegalStateException("Invalid privacy signing key configuration.");
            try{
                com.fasterxml.jackson.databind.JsonNode values;
                try(var parser=json.createParser(encoded)){parser.enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);values=json.readTree(parser);}
                if(!values.isObject()||values.size()<1||values.size()>8)throw new IllegalArgumentException();
                var keys=new LinkedHashMap<String,String>();var fields=values.fields();
                while(fields.hasNext()){var field=fields.next();String id=field.getKey(),secret=field.getValue().asText();
                    if(!field.getValue().isTextual()||!id.matches("[a-zA-Z0-9_-]{1,48}")||!secret.matches("[A-Za-z0-9_-]{43,128}"))throw new IllegalArgumentException();keys.put(id,secret);
                }
                if(!keys.containsKey(current))throw new IllegalArgumentException();result.put(purpose,new Ring(current,Map.copyOf(keys)));
            }catch(Exception invalid){throw new IllegalStateException("Invalid privacy signing key ring.");}
        }
        // The same configured secret must not serve both purposes.
        if(!Collections.disjoint(result.get(Purpose.BACKUP).keys().values(),result.get(Purpose.JOURNAL).keys().values()))throw new IllegalStateException("Privacy signing purposes require different keys.");
        rings=Map.copyOf(result);
    }
    public String current(Purpose purpose){return rings.get(purpose).current();}
    public String sign(Purpose purpose,String keyId,String body){
        String key=rings.get(purpose).keys().get(keyId);if(key==null)throw new IllegalStateException("Required privacy verification key is unavailable.");
        return crypto.hmac("unicarona-privacy-signature-v1|"+purpose.name()+"|"+keyId+"|"+body,key);
    }
    public boolean verify(Purpose purpose,String keyId,String body,String signature){return crypto.equalToken(signature,sign(purpose,keyId,body));}
    @Override public String toString(){return "PrivacySigningKeys[redacted]";}
}
