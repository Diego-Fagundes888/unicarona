package com.unicarona.privacy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.unicarona.privacy.service.PrivacyContractIdentityPurpose;
import com.unicarona.privacy.service.PrivacyContractIdentityPurpose.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

class ContractIdentityPurposeTest {
    private final ObjectMapper json=new ObjectMapper().registerModule(new JavaTimeModule());
    private final PrivacyContractIdentityPurpose policy=new PrivacyContractIdentityPurpose(new JdbcTemplate(),json);
    private final Fact fact=new Fact("ledger_entry",UUID.randomUUID(),"a".repeat(64));
    private Purpose value(){return new Purpose(1,1,"contract-identity-v1",true,Instant.parse("2020-01-01T12:00:00Z"),List.of(fact));}
    @Test void privatePurposeAndFactObjectsDoNotPrintTheirIdentities()throws Exception {assertEquals(value(),policy.read(policy.encode(value())));assertEquals("ContractIdentityPurpose[redacted]",value().toString());assertEquals("ContractIdentityFact[redacted]",fact.toString());}
    @Test void numericAndBooleanCoercionsCannotInventARecognizedSchema()throws Exception {
        for(String name:List.of("format","revision")){var node=json.valueToTree(value());((com.fasterxml.jackson.databind.node.ObjectNode)node).put(name,1.5);assertThrows(IllegalStateException.class,()->policy.read(node.toString()));((com.fasterxml.jackson.databind.node.ObjectNode)node).put(name,"1");assertThrows(IllegalStateException.class,()->policy.read(node.toString()));}
        var node=(com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(value());node.put("pending","false");assertThrows(IllegalStateException.class,()->policy.read(node.toString()));node.putNull("pending");assertThrows(IllegalStateException.class,()->policy.read(node.toString()));
    }
    @Test void unknownOrPartialSchemasAndUnrecognizedSourceKindsStayClosed()throws Exception {
        var node=(com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(value());node.put("extra","synthetic");assertThrows(IllegalStateException.class,()->policy.read(node.toString()));node.remove("extra");node.remove("facts");assertThrows(IllegalStateException.class,()->policy.read(node.toString()));
        for(Fact bad:List.of(new Fact("arbitrary_table",fact.id(),fact.sha256()),new Fact("ledger_entry",fact.id(),"bad"),new Fact(null,fact.id(),fact.sha256())))assertThrows(java.io.IOException.class,()->policy.validate(new Purpose(1,1,"contract-identity-v1",false,null,List.of(bad))));
    }
    @Test void aSuccessorCannotLoseKnownFactsRegressItsRevisionOrBackdateBusinessTime()throws Exception {
        var old=value();assertFalse(policy.successor(old,null));assertFalse(policy.successor(old,new Purpose(1,1,old.policy(),false,old.lastFact(),old.facts())));assertFalse(policy.successor(old,new Purpose(1,2,old.policy(),false,old.lastFact(),List.of())));assertFalse(policy.successor(old,new Purpose(1,2,old.policy(),false,old.lastFact().minusSeconds(1),old.facts())));
        var facts=new ArrayList<>(old.facts());facts.add(new Fact("webhook",UUID.randomUUID(),"b".repeat(64)));assertTrue(policy.successor(old,new Purpose(1,2,old.policy(),false,old.lastFact().plusSeconds(1),List.copyOf(facts))));
    }
    @Test void duplicateFactsAndOversizedBoundariesAreRejected()throws Exception {assertThrows(java.io.IOException.class,()->policy.validate(new Purpose(1,1,value().policy(),true,null,List.of(fact,fact))));assertThrows(java.io.IOException.class,()->policy.validate(new Purpose(1,1,value().policy(),true,null,Collections.nCopies(10001,fact))));}
}
