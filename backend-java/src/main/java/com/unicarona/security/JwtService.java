package com.unicarona.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.unicarona.auth.config.AuthSettings;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;

@Component
public class JwtService {
    private static final String ISSUER = "unicarona", AUDIENCE = "unicarona-api";
    private final NimbusJwtEncoder encoder;
    private final NimbusJwtDecoder decoder;
    public JwtService(AuthSettings settings) {
        var key = new SecretKeySpec(settings.jwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(ISSUER), jwt ->
            jwt.getAudience().contains(AUDIENCE) && jwt.getExpiresAt() != null && jwt.getIssuedAt() != null
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"))));
    }
    public String issue(UUID user, UUID session, UUID family, Instant issued, Instant expires) {
        var claims = JwtClaimsSet.builder().issuer(ISSUER).audience(List.of(AUDIENCE)).subject(user.toString())
            .issuedAt(issued).expiresAt(expires).id(UUID.randomUUID().toString())
            .claim("sid", session.toString()).claim("family", family.toString()).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
    public boolean matches(String token, Actor actor) {
        try {
            Jwt jwt = decoder.decode(token);
            return actor.id().toString().equals(jwt.getSubject()) && actor.sessionId().toString().equals(jwt.getClaimAsString("sid"))
                && actor.familyId().toString().equals(jwt.getClaimAsString("family"));
        } catch (JwtException | IllegalArgumentException ignored) { return false; }
    }
}
