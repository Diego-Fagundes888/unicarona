package com.unicarona.privacy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unicarona.auth.config.AuthSettings;
import com.unicarona.privacy.service.PrivacyEvidenceCrypto;
import com.unicarona.security.CryptoService;
import com.unicarona.support.AuthTestSettings;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;
import static com.unicarona.privacy.service.PrivacyEvidenceCrypto.Field.*;

class PrivacyEvidenceCryptoTest {
    private final CryptoService legacy=new CryptoService();private final ObjectMapper json=new ObjectMapper();
    private MockEnvironment env(){return new MockEnvironment().withProperty("NODE_ENV","test").withProperty("TOKEN_PEPPER",AuthTestSettings.PEPPER).withProperty("PIN_ENCRYPTION_KEY",AuthTestSettings.KEY).withProperty("JWT_SECRET",AuthTestSettings.SECRET);}
    private PrivacyEvidenceCrypto cipher(MockEnvironment env){return new PrivacyEvidenceCrypto(env,new AuthSettings(env),legacy,json);}
    private MockEnvironment ring(String id,String keys){return env().withProperty("PRIVACY_EVIDENCE_KEY_ID",id).withProperty("PRIVACY_EVIDENCE_KEYS",keys);}
    @Test void nonceIsRandomAndContextRejectsOtherOwnerRecordAndField(){
        var cipher=cipher(env());UUID owner=UUID.randomUUID(),id=UUID.randomUUID();String clear="Synthetic confidential details";
        String value=cipher.seal(REQUEST_DETAILS,owner,id,clear);assertNotEquals(value,cipher.seal(REQUEST_DETAILS,owner,id,clear));assertFalse(value.contains(clear));assertEquals(clear,cipher.open(REQUEST_DETAILS,owner,id,value));
        assertThrows(IllegalStateException.class,()->cipher.open(REQUEST_DETAILS,UUID.randomUUID(),id,value));assertThrows(IllegalStateException.class,()->cipher.open(REQUEST_DETAILS,owner,UUID.randomUUID(),value));assertThrows(IllegalStateException.class,()->cipher.open(RESPONSE_BODY,owner,id,value));
        assertThrows(IllegalStateException.class,()->legacy.decrypt(value,AuthTestSettings.KEY));assertEquals("PrivacyEvidenceCrypto[redacted]",cipher.toString());
    }
    @Test void rotationKeepsOldKeysReadableAndNeverFallsBackForUnknownOrTamperedEnvelope(){
        var old=cipher(ring("e1","{\"e1\":\""+"11".repeat(32)+"\"}"));
        var next=cipher(ring("e2","{\"e1\":\""+"11".repeat(32)+"\",\"e2\":\""+"22".repeat(32)+"\"}"));
        UUID owner=UUID.randomUUID(),id=UUID.randomUUID();String before=old.seal(DECISION_REASON,owner,id,"Synthetic decision");assertFalse(next.isCurrent(before));assertEquals("Synthetic decision",next.open(DECISION_REASON,owner,id,before));
        String after=next.seal(DECISION_REASON,owner,id,next.open(DECISION_REASON,owner,id,before));assertTrue(next.isCurrent(after));
        assertThrows(IllegalStateException.class,()->old.open(DECISION_REASON,owner,id,after));
        assertThrows(IllegalStateException.class,()->next.open(DECISION_REASON,owner,id,before.replace(".e1.",".e2.")));
        String[] parts=before.split("\\.");byte[] bytes=Base64.getUrlDecoder().decode(parts[2]);bytes[15]^=1;String tampered=parts[0]+"."+parts[1]+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        assertThrows(IllegalStateException.class,()->next.open(DECISION_REASON,owner,id,tampered));assertThrows(IllegalStateException.class,()->next.open(DECISION_REASON,owner,id,"pvc2.e1.invalid"));
    }
    @Test void legacyCiphertextStillOpensButNewWritesAreDedicated(){
        var cipher=cipher(env());UUID owner=UUID.randomUUID(),id=UUID.randomUUID();String before=legacy.encrypt("Synthetic original evidence",AuthTestSettings.KEY);
        assertEquals("legacy",cipher.keyId(before));assertEquals("Synthetic original evidence",cipher.open(RETAINED_CONTENT,owner,id,before));assertTrue(cipher.seal(RETAINED_CONTENT,owner,id,"new").startsWith("pvc1.derived-local-v1."));
        assertEquals("Synthetic original evidence",legacy.decrypt(before,AuthTestSettings.KEY));
    }
    @Test void mailCannotMoveBetweenEventsOrFields(){
        var cipher=cipher(env());UUID owner=UUID.randomUUID();String value=cipher.sealMail(MAIL_URL,owner,"identity-notice:synthetic","https://example.test/proof");
        assertEquals("https://example.test/proof",cipher.openMail(MAIL_URL,owner,"identity-notice:synthetic",value));assertThrows(IllegalStateException.class,()->cipher.openMail(MAIL_URL,owner,"identity-notice:another",value));assertThrows(IllegalStateException.class,()->cipher.openMail(MAIL_TO,owner,"identity-notice:synthetic",value));
        assertThrows(IllegalArgumentException.class,()->cipher.sealMail(REQUEST_DETAILS,owner,"event","value"));
    }
    @Test void productionRequiresExplicitDedicatedKeysAndRejectsMalformedOrReusedValues(){
        assertThrows(IllegalStateException.class,()->cipher(env().withProperty("NODE_ENV","production").withProperty("APP_ORIGIN","https://example.test")));
        for(String keys:List.of("{}","{\"e1\":\"bad\"}","{\"e1\":\""+AuthTestSettings.KEY+"\"}","{\"e1\":\""+"11".repeat(32)+"\",\"e1\":\""+"22".repeat(32)+"\"}","{\"e1\":\""+"11".repeat(32)+"\",\"e2\":\""+"11".repeat(32)+"\"}","{\"e1\":\""+"11".repeat(32)+"\"} {}"))assertThrows(IllegalStateException.class,()->cipher(ring("e1",keys)));
        assertThrows(IllegalStateException.class,()->cipher(ring("missing","{\"e1\":\""+"11".repeat(32)+"\"}")));
    }
}
