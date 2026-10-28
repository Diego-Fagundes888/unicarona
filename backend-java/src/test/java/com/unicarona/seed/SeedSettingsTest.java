package com.unicarona.seed;

import com.unicarona.shared.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class SeedSettingsTest {
    @Test void passwordHasNoDefaultAndProductionIsRejectedBeforeCredentialAccess() {
        var environment=new MockEnvironment();var settings=new SeedSettings(environment);assertEquals("SEED_PASSWORD_REQUIRED",assertThrows(ApiException.class,settings::password).code());environment.setProperty("SEED_PASSWORD","short");assertEquals("SEED_PASSWORD_REQUIRED",assertThrows(ApiException.class,settings::password).code());environment.setProperty("NODE_ENV","production");assertEquals("SEED_FORBIDDEN",assertThrows(ApiException.class,settings::password).code());
    }
    @Test void scenariosRequireDevelopmentProviderAndNeverCommercialGateway() {
        var environment=new MockEnvironment().withProperty("PAYMENT_PROVIDER","mercadopago");var settings=new SeedSettings(environment);assertEquals("SEED_FORBIDDEN",assertThrows(ApiException.class,settings::scenariosAllowed).code());environment.setProperty("PAYMENT_PROVIDER","development");assertDoesNotThrow(settings::scenariosAllowed);environment.setProperty("NODE_ENV","production");assertEquals("SEED_FORBIDDEN",assertThrows(ApiException.class,settings::scenariosAllowed).code());
    }
    @Test void syntheticCredentialsAreReturnedOnlyFromExplicitPrivateConfiguration() {
        var environment=new MockEnvironment().withProperty("SEED_PASSWORD","Synthetic seed test only 2026");assertEquals("Synthetic seed test only 2026",new SeedSettings(environment).password());
    }
}
