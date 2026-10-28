package com.unicarona.privacy.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unicarona.auth.config.AuthSettings;
import com.unicarona.security.CryptoService;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Versioned AES-GCM evidence. Authentication binds ciphertext to its field, owner and resource. */
@Component
public final class PrivacyEvidenceCrypto {
    public enum Field { REQUEST_DETAILS, RESPONSE_BODY, RETAINED_CONTENT, DECISION_REASON, EMAIL_TARGET, HOLD_REASON, HOLD_EVENT_REASON, ACCEPTANCE_IP, CLOSURE_CONTACT, MAIL_TO, MAIL_URL, MAIL_CONTENT, HOLD_RECOVERY_CONTROL, HOLD_RECOVERY_FILE }
    private final String current,legacy;private final Map<String,byte[]> keys;private final CryptoService crypto;
    private final SecureRandom random=new SecureRandom();
    public PrivacyEvidenceCrypto(Environment env,AuthSettings auth,CryptoService crypto,ObjectMapper json){
        this.crypto=crypto;legacy=auth.encryptionKey();
        String id=env.getProperty("PRIVACY_EVIDENCE_KEY_ID","").strip(),encoded=env.getProperty("PRIVACY_EVIDENCE_KEYS","").strip();
        if(id.isEmpty()&&encoded.isEmpty()){
            if("production".equals(env.getProperty("NODE_ENV")))throw new IllegalStateException("Production requires a dedicated privacy encryption key ring.");
            id="derived-local-v1";encoded="{\""+id+"\":\""+crypto.hmac("unicarona-privacy-evidence-local-aes-v1",auth.pepper())+"\"}";
        }
        try{
            if(!id.matches("[a-zA-Z0-9_-]{1,48}")||encoded.length()>8192)throw new IllegalArgumentException();
            var parsed=json.createParser(encoded);parsed.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            com.fasterxml.jackson.databind.JsonNode values;try(parsed){values=json.readTree(parsed);if(parsed.nextToken()!=null)throw new IllegalArgumentException();}
            if(!values.isObject()||values.size()<1||values.size()>8)throw new IllegalArgumentException();
            var ring=new LinkedHashMap<String,byte[]>();var unique=new HashSet<String>();var fields=values.fields();
            while(fields.hasNext()){
                var field=fields.next();String key=field.getValue().asText().toLowerCase(Locale.ROOT);
                if(!field.getValue().isTextual()||!field.getKey().matches("[a-zA-Z0-9_-]{1,48}")||!key.matches("[a-f0-9]{64}")||key.equals(legacy.toLowerCase(Locale.ROOT))||!unique.add(key))throw new IllegalArgumentException();
                ring.put(field.getKey(),HexFormat.of().parseHex(key));
            }
            if(!ring.containsKey(id))throw new IllegalArgumentException();keys=Map.copyOf(ring);current=id;
        }catch(Exception invalid){throw new IllegalStateException("Invalid privacy encryption key ring.");}
    }
    public String seal(Field field,UUID owner,UUID resource,String text){return seal(field,owner,resource.toString(),text);}
    public String open(Field field,UUID owner,UUID resource,String encoded){return open(field,owner,resource.toString(),encoded);}
    public String sealMail(Field field,UUID owner,String event,String text){mailField(field);return seal(field,owner,event,text);}
    public String openMail(Field field,UUID owner,String event,String text){mailField(field);return open(field,owner,event,text);}
    private static void mailField(Field field){if(!Set.of(Field.MAIL_TO,Field.MAIL_URL,Field.MAIL_CONTENT).contains(field))throw new IllegalArgumentException("Invalid encrypted mail field.");}
    public String currentPrefix(){return "pvc1."+current+".";}
    public boolean isCurrent(String encoded){return encoded!=null&&encoded.startsWith(currentPrefix());}
    /** Inspection only: never authorizes decryption or removal of an old key. */
    public String keyId(String encoded){if(encoded==null)return "absent";if(!encoded.startsWith("pvc1."))return "legacy";String[] parts=encoded.split("\\.",-1);return parts.length==3&&parts[1].matches("[a-zA-Z0-9_-]{1,48}")?parts[1]:"invalid";}
    private String seal(Field field,UUID owner,String resource,String text){
        Objects.requireNonNull(text);byte[] iv=new byte[12];random.nextBytes(iv);
        try{
            byte[] encrypted=cipher(Cipher.ENCRYPT_MODE,current,iv,field,owner,resource).doFinal(text.getBytes(StandardCharsets.UTF_8));
            byte[] wire=new byte[12+encrypted.length];System.arraycopy(iv,0,wire,0,12);System.arraycopy(encrypted,0,wire,12,encrypted.length);
            return currentPrefix()+Base64.getUrlEncoder().withoutPadding().encodeToString(wire);
        }catch(Exception invalid){throw new IllegalStateException("Privacy evidence encryption failed.");}
    }
    private String open(Field field,UUID owner,String resource,String encoded){
        Objects.requireNonNull(encoded);
        // Only the original unversioned Base64 format uses the unchanged historical financial key.
        if(!encoded.startsWith("pvc1.")){
            if(!encoded.matches("[A-Za-z0-9+/]+={0,2}"))throw new IllegalStateException("Privacy evidence cannot be decrypted.");
            return crypto.decrypt(encoded,legacy);
        }
        try{
            String[] parts=encoded.split("\\.",-1);if(parts.length!=3||!keys.containsKey(parts[1]))throw new IllegalArgumentException();
            byte[] wire=Base64.getUrlDecoder().decode(parts[2]);if(wire.length<28)throw new IllegalArgumentException();
            return new String(cipher(Cipher.DECRYPT_MODE,parts[1],Arrays.copyOf(wire,12),field,owner,resource).doFinal(wire,12,wire.length-12),StandardCharsets.UTF_8);
        }catch(Exception invalid){throw new IllegalStateException("Privacy evidence cannot be decrypted.");}
    }
    private Cipher cipher(int mode,String id,byte[] iv,Field field,UUID owner,String resource)throws Exception {
        Objects.requireNonNull(owner);Objects.requireNonNull(field);
        if(resource==null||resource.isEmpty()||resource.length()>256||resource.contains("|")||resource.contains("\n"))throw new IllegalArgumentException();
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(mode,new SecretKeySpec(keys.get(id),"AES"),new GCMParameterSpec(128,iv));
        cipher.updateAAD(("unicarona-privacy-evidence|pvc1|"+id+"|"+field.name()+"|"+owner+"|"+resource).getBytes(StandardCharsets.UTF_8));return cipher;
    }
    @Override public String toString(){return "PrivacyEvidenceCrypto[redacted]";}
}
