package com.unicarona.privacy;

import com.unicarona.privacy.config.PrivacySettings;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class PrivacySettingsTest {
    @Test void publicContactRejectsAttributeMarkupAndUrlQueryCharacters(){
        for(String invalid:java.util.List.of("privacy\"><img@exam.ple","privacy@example.test?subject=unsafe","privacy@example.test#fragment","privacy@example.test&extra"))
            assertThrows(IllegalStateException.class,()->new PrivacySettings(new MockEnvironment().withProperty("LEGAL_PRIVACY_EMAIL",invalid)));
        assertEquals("privacy+rights@example.test",new PrivacySettings(new MockEnvironment().withProperty("LEGAL_PRIVACY_EMAIL","privacy+rights@example.test")).contactEmail());
    }
    private MockEnvironment production(){return new MockEnvironment().withProperty("NODE_ENV","production").withProperty("LEGAL_CONTROLLER_NAME","Synthetic responsible person").withProperty("LEGAL_PRIVACY_EMAIL","privacy@example.test").withProperty("LEGAL_CITY","Synthetic city").withProperty("LEGAL_STATE","GO");}
    @Test void defaultsAreBoundedAndDevelopmentDoesNotInventAController(){
        var s=new PrivacySettings(new MockEnvironment());assertEquals("",s.controllerName());assertEquals(7,s.deletionGraceDays());assertEquals(365,s.registrationRestrictionDays());assertEquals(35,s.backupRetentionDays());assertFalse(s.commercialOperation());
    }
    @Test void productionRequiresControllerIdentityAndCommercialSupplierDetails(){
        assertThrows(IllegalStateException.class,()->new PrivacySettings(new MockEnvironment().withProperty("NODE_ENV","production")));
        assertDoesNotThrow(()->new PrivacySettings(production()));
        assertThrows(IllegalStateException.class,()->new PrivacySettings(production().withProperty("LEGAL_COMMERCIAL_OPERATION","true")));
        assertDoesNotThrow(()->new PrivacySettings(production().withProperty("LEGAL_COMMERCIAL_OPERATION","true").withProperty("LEGAL_PROVIDER_IDENTIFICATION","Synthetic fiscal fixture").withProperty("LEGAL_PROVIDER_ADDRESS","Synthetic correspondence fixture")));
    }
    @Test void rejectsUnboundedRestrictionsAndHeaderInjection(){
        assertThrows(IllegalStateException.class,()->new PrivacySettings(new MockEnvironment().withProperty("PRIVACY_REGISTRATION_RESTRICTION_DAYS","1000")));
        assertThrows(IllegalStateException.class,()->new PrivacySettings(new MockEnvironment().withProperty("PRIVACY_DELETION_GRACE_DAYS","0")));
        assertThrows(IllegalStateException.class,()->new PrivacySettings(new MockEnvironment().withProperty("LEGAL_PRIVACY_EMAIL","bad\r\nheader@example.test")));
        assertEquals("PrivacySettings[redacted]",new PrivacySettings(production()).toString());
    }
}
