package com.unicarona.auth.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unicarona.auth.config.AuthSettings;
import com.unicarona.auth.dto.AuthDto;
import com.unicarona.auth.dto.SessionTokens;
import com.unicarona.auth.repository.AuthRepository;
import com.unicarona.security.Actor;
import com.unicarona.security.CryptoService;
import com.unicarona.security.JwtService;
import com.unicarona.shared.ApiException;
import com.unicarona.user.dto.ProfileDto;
import com.unicarona.user.repository.ProfileRepository;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Separate proxied transactional boundary: password hashing and delivery never hold row locks. */
@Service
public class AuthTransactions {
  private final AuthRepository repository;
  private final ProfileRepository profiles;
  private final AuthSettings settings;
  private final CryptoService crypto;
  private final JwtService jwt;
  private final ObjectMapper mapper;
  private final com.unicarona.notification.config.EmailSettings emailSettings;
  private final com.unicarona.privacy.service.LegalDocumentService legal;
  private final com.unicarona.privacy.service.RegistrationRestrictions restrictions;
  private final com.unicarona.privacy.service.PrivacyEvidenceCrypto evidence;

  public AuthTransactions(
      AuthRepository repository,
      ProfileRepository profiles,
      AuthSettings settings,
      CryptoService crypto,
      JwtService jwt,
      ObjectMapper mapper,
      com.unicarona.notification.config.EmailSettings emailSettings,
      com.unicarona.privacy.service.LegalDocumentService legal,
      com.unicarona.privacy.service.RegistrationRestrictions restrictions,
      com.unicarona.privacy.service.PrivacyEvidenceCrypto evidence) {
    this.repository = repository;
    this.profiles = profiles;
    this.settings = settings;
    this.crypto = crypto;
    this.jwt = jwt;
    this.mapper = mapper;
    this.emailSettings = emailSettings;
    this.legal = legal;
    this.restrictions = restrictions;
    this.evidence = evidence;
  }

  public record LoginResult(SessionTokens tokens, ProfileDto profile) {
    @Override
    public String toString() {
      return "LoginResult[redacted]";
    }
  }

  public record RefreshResult(SessionTokens tokens, String error) {
    @Override
    public String toString() {
      return "RefreshResult[redacted]";
    }
  }

  @Transactional
  public void register(AuthDto.Register input, String hash) {
    repository.lockTimeout();
    // Validate the same public catalog for new/duplicate addresses, without an existence-dependent
    // response.
    repository.validateRegistrationAcademic(input.academic());
    ApiException.require(
        Boolean.TRUE.equals(input.terms())
            && input.legal() != null
            && Boolean.TRUE.equals(input.legal().privacyNotice()),
        "LEGAL_CONFIRMATION_REQUIRED",
        "Confirme os Termos e a leitura da Política de Privacidade.",
        422);
    String fingerprint = restrictions.lock(input.email());
    var documents = legal.registrationDocuments(input.legal().documents());
    // Same generic response and no email/user insertion for a purpose-bound, unexpired restriction.
    if (restrictions.blocked(fingerprint)) return;
    repository
        .insertUser(input, hash)
        .ifPresent(
            id -> {
              legal.record(id, documents, "registration");
              repository.saveAcademic(id, input.academic());
              issueEmail(id, "verify_email", input.email());
              repository.audit(
                  id,
                  "account_registered",
                  "{\"legalEvidence\":\"versioned-documents\",\"selfDeclaredAdult\":true}");
            });
  }

  private void issueEmail(UUID user, String kind, String email) {
    String token = crypto.randomToken();
    UUID id =
        repository.issueEmailToken(
            user,
            kind,
            digest(token),
            "verify_email".equals(kind) ? emailSettings.verificationMinutes() : 30);
    String url =
        settings.origin()
            + "/login.html#"
            + ("verify_email".equals(kind) ? "verificar" : "redefinir")
            + "?token="
            + token;
    try {
      String name = profiles.find(user).name();
      var content =
          new com.unicarona.notification.template.AuthEmailTemplate(emailSettings)
              .render(
                  new com.unicarona.notification.provider.EmailProvider.Message(
                      id, email, kind, url, name));
      String event = "auth:" + id;
      repository.enqueueEmail(
          id,
          mapper.writeValueAsString(
              Map.of(
                  "ownerId",
                  user.toString(),
                  "toCiphertext",
                  evidence.sealMail(
                      com.unicarona.privacy.service.PrivacyEvidenceCrypto.Field.MAIL_TO,
                      user,
                      event,
                      email),
                  "kind",
                  kind,
                  "urlCiphertext",
                  evidence.sealMail(
                      com.unicarona.privacy.service.PrivacyEvidenceCrypto.Field.MAIL_URL,
                      user,
                      event,
                      url),
                  "contentCiphertext",
                  evidence.sealMail(
                      com.unicarona.privacy.service.PrivacyEvidenceCrypto.Field.MAIL_CONTENT,
                      user,
                      event,
                      mapper.writeValueAsString(content)))));
      repository.audit(user, "email_requested", "{}");
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Could not serialize email job.");
    }
  }

  @Transactional
  public LoginResult login(UUID user, String expectedHash) {
    repository.lockTimeout();
    var current = repository.lockUser(user, true).orElseThrow(AuthTransactions::credentials);
    ApiException.require(
        expectedHash.equals(current.get("password_hash")),
        "INVALID_CREDENTIALS",
        "E-mail ou senha incorretos.",
        401);
    ApiException.require(
        current.get("email_verified_at") != null,
        "EMAIL_UNVERIFIED",
        "Confirme seu e-mail antes de entrar.",
        403);
    return new LoginResult(createSession(user, UUID.randomUUID(), 0), profiles.find(user));
  }

  private SessionTokens createSession(UUID user, UUID family, int generation) {
    UUID id = UUID.randomUUID();
    String refresh = crypto.randomToken(), csrf = crypto.randomToken();
    var now = repository.databaseNow();
    String access =
        jwt.issue(user, id, family, now, now.plusSeconds(settings.accessMinutes() * 60L));
    repository.insertSession(
        id,
        user,
        family,
        generation,
        digest(access),
        digest(refresh),
        digest(csrf),
        settings.accessMinutes(),
        settings.refreshDays());
    return new SessionTokens(
        access, refresh, csrf, id, settings.accessMinutes(), settings.refreshDays());
  }

  public record VerificationOutcome(String code, String message) {}

  @Transactional
  public VerificationOutcome verifyEmail(String token) {
    repository.lockTimeout();
    var identity = repository.tokenUser(digest(token), "verify_email");
    if (identity.isEmpty()) {
      repository.audit(null, "email_verification_invalid", "{}");
      return new VerificationOutcome(
          "TOKEN_INVALID", "Este link não é válido. Solicite outro e-mail de confirmação.");
    }
    UUID user = identity.get();
    // Use the same user -> token ordering as resend/reset to prevent deadlocks.
    var current = repository.lockUser(user, true);
    if (current.isEmpty())
      return new VerificationOutcome(
          "TOKEN_INVALID", "Este link não é válido. Solicite outro e-mail de confirmação.");
    var found = repository.lockVerificationToken(digest(token));
    if (found.isEmpty()) return new VerificationOutcome("TOKEN_INVALID", "Este link não é válido.");
    var entry = found.get();
    String code = null, message = null;
    if (entry.get("invalidated_at") != null) {
      code = "TOKEN_REPLACED";
      message = "Um novo link foi solicitado. Use o e-mail mais recente.";
    } else if (entry.get("used_at") != null) {
      code = "TOKEN_USED";
      message = "Este link já foi utilizado. Tente entrar na sua conta.";
    } else if (Boolean.TRUE.equals(entry.get("expired"))) {
      code = "TOKEN_EXPIRED";
      message = "Este link expirou. Solicite outro e-mail de confirmação.";
    }
    if (code != null) {
      repository.audit(
          user, "email_verification_" + code.substring(6).toLowerCase(java.util.Locale.ROOT), "{}");
      return new VerificationOutcome(code, message);
    }
    // A token must still belong to this unverified live account; no ID comes from the browser.
    if (current.get().get("email_verified_at") != null)
      return new VerificationOutcome(
          "TOKEN_USED", "Seu e-mail já foi confirmado. Entre na sua conta.");
    repository.verifyEmail((UUID) entry.get("id"), user);
    repository.audit(user, "email_verified", "{}");
    return new VerificationOutcome(null, null);
  }

  @Transactional
  public void resend(String email) {
    repository.lockTimeout();
    repository
        .lockEmail(email)
        .ifPresent(
            user -> {
              if (user.get("email_verified_at") == null)
                issueEmail((UUID) user.get("id"), "verify_email", (String) user.get("email"));
            });
  }

  @Transactional
  public void forgot(String email) {
    repository.lockTimeout();
    repository
        .lockEmail(email)
        .ifPresent(
            user ->
                issueEmail((UUID) user.get("id"), "reset_password", (String) user.get("email")));
  }

  @Transactional
  public void reset(String token, String newHash) {
    repository.lockTimeout();
    UUID user =
        repository
            .tokenUser(digest(token), "reset_password")
            .orElseThrow(AuthTransactions::invalidToken);
    repository.lockUser(user, false).orElseThrow(AuthTransactions::invalidToken);
    repository
        .lockValidToken(digest(token), "reset_password")
        .orElseThrow(AuthTransactions::invalidToken);
    repository.resetPassword(user, newHash);
    repository.audit(user, "password_reset", "{}");
  }

  @Transactional
  public void change(Actor actor, String expectedHash, String newHash) {
    repository.lockTimeout();
    repository.lockUser(actor.id(), false);
    ApiException.require(
        repository.changePassword(actor.id(), expectedHash, newHash),
        "PASSWORD_CHANGED",
        "Sua senha foi alterada. Entre novamente.",
        409);
    repository.revokeUser(actor.id());
    repository.audit(actor.id(), "password_changed", "{}");
  }

  @Transactional
  public RefreshResult refresh(String token, String csrf) {
    repository.lockTimeout();
    var ref = repository.refreshUser(digest(token));
    if (ref.isEmpty() || repository.lockUser(ref.get(), true).isEmpty())
      return new RefreshResult(null, "SESSION_EXPIRED");
    var found = repository.lockRefresh(digest(token));
    if (found.isEmpty()) return new RefreshResult(null, "SESSION_EXPIRED");
    var s = found.get();
    if (!crypto.equalToken((String) s.get("csrf_hash"), digest(csrf))
        || !Boolean.TRUE.equals(s.get("valid_expiry")))
      return new RefreshResult(null, "SESSION_EXPIRED");
    if (s.get("revoked_at") != null) {
      if (s.get("replaced_by") != null) repository.revokeFamily((UUID) s.get("family_id"));
      return new RefreshResult(null, "SESSION_REUSED");
    }
    var tokens =
        createSession(
            ref.get(), (UUID) s.get("family_id"), ((Number) s.get("generation")).intValue() + 1);
    repository.replaceSession((UUID) s.get("id"), tokens.sessionId());
    return new RefreshResult(tokens, null);
  }

  @Transactional
  public void logout(Actor actor) {
    repository.lockTimeout();
    repository.lockUser(actor.id(), false);
    repository.revokeFamily(actor.familyId());
  }

  private String digest(String token) {
    return crypto.hmac(token, settings.pepper());
  }

  private static ApiException invalidToken() {
    return new ApiException("TOKEN_INVALID", "Link inválido, utilizado ou expirado.", 400);
  }

  private static ApiException credentials() {
    return new ApiException("INVALID_CREDENTIALS", "E-mail ou senha incorretos.", 401);
  }
}
