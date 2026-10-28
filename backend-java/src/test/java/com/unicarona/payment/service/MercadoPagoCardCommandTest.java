package com.unicarona.payment.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.unicarona.auth.config.AuthSettings;
import com.unicarona.payment.dto.MercadoPagoDto.CardInput;
import com.unicarona.security.CryptoService;
import com.unicarona.shared.ApiException;
import com.unicarona.support.AuthTestSettings;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class MercadoPagoCardCommandTest {
    private final ObjectMapper json=new ObjectMapper();
    private final CryptoService crypto=new CryptoService();
    private final AuthSettings auth=new AuthSettings(new MockEnvironment().withProperty("TOKEN_PEPPER",AuthTestSettings.PEPPER).withProperty("PIN_ENCRYPTION_KEY",AuthTestSettings.KEY).withProperty("JWT_SECRET",AuthTestSettings.SECRET));
    private final MercadoPagoCardCommand codec=new MercadoPagoCardCommand(json,crypto,auth);
    private final UUID payment=UUID.randomUUID(),owner=UUID.randomUUID();
    private final CardInput card=new CardInput("synthetic-card-token","master","credit_card",1,"CPF","12345678909","synthetic-device");
    private String hash()throws Exception{return crypto.hmac(json.writeValueAsString(card),auth.pepper());}
    private String freeze(){return codec.freeze(payment,owner,1234,"old@example.test",card);}
    private String encrypt(String raw){return crypto.encrypt(raw,auth.encryptionKey());}
    private ObjectNode envelope()throws Exception{return (ObjectNode)json.readTree(crypto.decrypt(freeze(),auth.encryptionKey()));}
    private void invalid(String encoded)throws Exception{var failure=assertThrows(ApiException.class,()->codec.read(encoded,hash(),payment,owner,1234));assertEquals("PAYMENT_COMMAND_INVALID",failure.code());assertFalse(failure.getMessage().contains("synthetic-card-token"));assertFalse(failure.getMessage().contains("old@example.test"));}
    @Test void frozenRecoveryContactAndDeviceArePrivateAndBoundToTheOriginalInput()throws Exception {
        String encoded=freeze();assertFalse(encoded.contains("old@example.test"));assertFalse(encoded.contains(card.token()));var decoded=codec.read(encoded,hash(),payment,owner,1234);
        assertEquals(card,decoded.card());assertEquals("old@example.test",decoded.payerEmail());assertFalse(decoded.legacy());assertEquals("CardCommand[REDACTED]",decoded.toString());
        assertEquals("CardSubmission[REDACTED]",new MercadoPagoTransactions.CardSubmission(1234,java.time.Instant.now(),decoded.payerEmail(),card).toString());
    }
    @Test void rejectsCiphertextCopiedAcrossPaymentOwnerOrAmount()throws Exception {
        String encoded=freeze(),hash=hash();
        assertThrows(ApiException.class,()->codec.read(encoded,hash,UUID.randomUUID(),owner,1234));assertThrows(ApiException.class,()->codec.read(encoded,hash,payment,UUID.randomUUID(),1234));assertThrows(ApiException.class,()->codec.read(encoded,hash,payment,owner,1235));assertThrows(ApiException.class,()->codec.read(encoded,"0".repeat(64),payment,owner,1234));
    }
    @Test void legacyInputIsRecognizedWithoutInventingAContact()throws Exception {
        var decoded=codec.read(encrypt(json.writeValueAsString(card)),hash(),payment,owner,1234);assertTrue(decoded.legacy());assertNull(decoded.payerEmail());assertEquals(card,decoded.card());
    }
    @Test void rejectsUnrecognizedFieldsFormatsTypesAndMissingContext()throws Exception {
        for(String field:List.of("format","kind","paymentId","ownerId","amountCents","payerEmail","card")){var root=envelope();root.remove(field);invalid(encrypt(root.toString()));}
        var root=envelope();root.put("format",2);invalid(encrypt(root.toString()));root=envelope();root.put("format",4294967297L);invalid(encrypt(root.toString()));root=envelope();root.put("format","1");invalid(encrypt(root.toString()));root=envelope();root.put("unexpected","value");invalid(encrypt(root.toString()));
        for(String email:List.of("old@example.test\r\nInjected","@","a@@b","a@")){root=envelope();root.put("payerEmail",email);invalid(encrypt(root.toString()));}
    }
    @Test void rejectsDuplicateKeysTrailingDocumentsAndUnauthenticatedCiphertext()throws Exception {
        String raw=envelope().toString();invalid(encrypt(raw.substring(0,raw.length()-1)+",\"format\":1}"));invalid(encrypt(raw+" {}"));invalid("malformed-ciphertext");invalid(null);invalid("x".repeat(16385));
        byte[] wire=Base64.getDecoder().decode(freeze());wire[wire.length-1]^=1;invalid(Base64.getEncoder().encodeToString(wire));
    }
    @Test void rejectsRawCardFieldsNullDeviceAndCardFieldsWithWrongTypes()throws Exception {
        for(String field:List.of("card_number","security_code","cvv")){var root=envelope();((ObjectNode)root.path("card")).put(field,"synthetic-rejected");invalid(encrypt(root.toString()));}
        var root=envelope();((ObjectNode)root.path("card")).put("installments","1");invalid(encrypt(root.toString()));root=envelope();((ObjectNode)root.path("card")).putNull("deviceSessionId");invalid(encrypt(root.toString()));
    }
    @Test void absentOptionalDeviceStillPreservesTheLegacyInputHash()throws Exception {
        var old=new CardInput(card.token(),card.method(),card.type(),1,"CPF",card.identificationNumber(),null);String hash=crypto.hmac(json.writeValueAsString(old),auth.pepper());
        var decoded=codec.read(codec.freeze(payment,owner,1234,"old@example.test",old),hash,payment,owner,1234);assertNull(decoded.card().deviceSessionId());assertEquals(old,decoded.card());
    }
}
