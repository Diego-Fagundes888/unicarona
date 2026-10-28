package com.unicarona.payment.service;

import com.fasterxml.jackson.databind.*;
import com.unicarona.auth.config.AuthSettings;
import com.unicarona.payment.dto.MercadoPagoDto.CardInput;
import com.unicarona.security.CryptoService;
import com.unicarona.shared.ApiException;
import java.util.*;

/** Private, context-bound recovery input. Never serialize a decoded command to a client or log. */
public final class MercadoPagoCardCommand {
    public record Decoded(CardInput card,String payerEmail,boolean legacy) {
        @Override public String toString(){return "CardCommand[REDACTED]";}
    }
    private static final Set<String> ENVELOPE=Set.of("format","kind","paymentId","ownerId","amountCents","payerEmail","card");
    private static final Set<String> CARD=Set.of("token","method","type","installments","identificationType","identificationNumber","deviceSessionId");
    private final ObjectMapper json;
    private final CryptoService crypto;
    private final AuthSettings auth;
    public MercadoPagoCardCommand(ObjectMapper json,CryptoService crypto,AuthSettings auth){this.json=json;this.crypto=crypto;this.auth=auth;}
    public String freeze(UUID payment,UUID owner,int cents,String email,CardInput card){
        try {
            require(payment!=null && owner!=null && cents>0 && validEmail(email));validateCard(json.valueToTree(card));
            var root=json.createObjectNode().put("format",1).put("kind","mercadopago-card").put("paymentId",payment.toString()).put("ownerId",owner.toString()).put("amountCents",cents).put("payerEmail",email);
            root.set("card",json.valueToTree(card));return crypto.encrypt(json.writeValueAsString(root),auth.encryptionKey());
        }catch(Exception error){throw invalid();}
    }
    public Decoded read(String ciphertext,String expectedHash,UUID payment,UUID owner,int cents){
        try {
            require(ciphertext!=null && ciphertext.length()<=16384);
            var root=json.reader().with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(crypto.decrypt(ciphertext,auth.encryptionKey()));
            boolean legacy=root.isObject() && root.has("token");JsonNode card;
            String email=null;
            if(legacy)card=root;
            else {
                require(fields(root).equals(ENVELOPE) && root.path("format").isInt() && root.path("format").intValue()==1 && "mercadopago-card".equals(root.path("kind").asText()));
                require(payment.toString().equals(root.path("paymentId").asText()) && owner.toString().equals(root.path("ownerId").asText()) && root.path("amountCents").isInt() && cents==root.path("amountCents").intValue());
                require(root.path("payerEmail").isTextual());email=root.path("payerEmail").textValue();require(validEmail(email));card=root.path("card");
            }
            validateCard(card);var input=json.treeToValue(card,CardInput.class);
            require(crypto.equalToken(expectedHash,crypto.hmac(json.writeValueAsString(input),auth.pepper())));
            return new Decoded(input,email,legacy);
        }catch(Exception error){throw invalid();}
    }
    private static boolean validEmail(String value){return value!=null && value.length()<=320 && value.indexOf('@')>0 && value.indexOf('@')==value.lastIndexOf('@') && value.indexOf('@')<value.length()-1 && value.chars().noneMatch(c->Character.isWhitespace(c)||Character.isISOControl(c));}
    private static Set<String> fields(JsonNode value){require(value.isObject());var result=new HashSet<String>();value.fieldNames().forEachRemaining(result::add);return result;}
    private static void validateCard(JsonNode card){
        var fields=fields(card);require(CARD.containsAll(fields) && fields.containsAll(Set.of("token","method","type","installments","identificationType","identificationNumber")));
        require(text(card,"token","[A-Za-z0-9_-]{8,256}") && text(card,"method","[A-Za-z0-9_]{1,32}") && text(card,"type","credit_card|debit_card") && card.path("installments").isInt() && card.path("installments").intValue()==1);
        require(text(card,"identificationType","CPF|CNPJ") && text(card,"identificationNumber","[0-9]{11}|[0-9]{14}") && card.path("identificationNumber").textValue().length()==("CPF".equals(card.path("identificationType").textValue())?11:14));
        require(!card.has("deviceSessionId") || text(card,"deviceSessionId","[\\x21-\\x7E]{8,1024}"));
    }
    private static boolean text(JsonNode root,String name,String pattern){return root.path(name).isTextual() && root.path(name).textValue().matches(pattern);}
    private static void require(boolean condition){if(!condition)throw invalid();}
    private static ApiException invalid(){return new ApiException("PAYMENT_COMMAND_INVALID","Pagamento indisponível. Procure o suporte.",503);}
}
