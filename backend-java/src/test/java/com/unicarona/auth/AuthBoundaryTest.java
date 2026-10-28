package com.unicarona.auth;

import com.unicarona.auth.config.AuthSettings;
import com.unicarona.auth.service.AuthService;
import com.unicarona.security.Actor;
import com.unicarona.security.JwtService;
import com.unicarona.support.AuthTestSettings;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class AuthBoundaryTest {
    private AuthSettings settings() {
        return new AuthSettings(new MockEnvironment().withProperty("TOKEN_PEPPER",AuthTestSettings.PEPPER)
            .withProperty("PIN_ENCRYPTION_KEY",AuthTestSettings.KEY).withProperty("JWT_SECRET",AuthTestSettings.SECRET));
    }
    @Test void ageCutoffIncludesTheEighteenthBirthdayWithoutAdvancingAMarchBirthdayOnLeapDay() {
        assertTrue(AuthService.isAdult(LocalDate.of(2008,10,2),LocalDate.of(2026,10,2)));
        assertFalse(AuthService.isAdult(LocalDate.of(2008,10,3),LocalDate.of(2026,10,2)));
        assertFalse(AuthService.isAdult(LocalDate.of(2006,3,1),LocalDate.of(2024,2,29)));
        assertTrue(AuthService.isAdult(LocalDate.of(2006,3,1),LocalDate.of(2024,3,1)));
        assertTrue(AuthService.isAdult(LocalDate.of(2006,2,28),LocalDate.of(2024,2,29)));
        assertFalse(AuthService.isAdult(LocalDate.of(2008,2,29),LocalDate.of(2026,2,28)));
        assertTrue(AuthService.isAdult(LocalDate.of(2008,2,29),LocalDate.of(2026,3,1)));
    }
    @Test void missingSecretsCannotStartAuthentication() { assertThrows(IllegalStateException.class,() -> new AuthSettings(new MockEnvironment())); }
    @Test void jwtMustMatchTheRegisteredUserSessionAndFamilyAndRejectExpiredSignatures() {
        var jwt=new JwtService(settings()); var user=UUID.randomUUID(); var session=UUID.randomUUID(); var family=UUID.randomUUID();
        var actor=new Actor(user,"user","active",session,family,"synthetic",true,Set.of("ROLE_PASSENGER"));
        var now=Instant.now(); String token=jwt.issue(user,session,family,now,now.plusSeconds(900));
        assertTrue(jwt.matches(token,actor));
        assertFalse(jwt.matches(token,new Actor(user,"user","active",UUID.randomUUID(),family,"synthetic",true,Set.of())));
        assertFalse(jwt.matches(token.substring(0,token.length()-2)+"xx",actor));
        assertFalse(jwt.matches(jwt.issue(user,session,family,now.minusSeconds(1000),now.minusSeconds(200)),actor));
    }
}
