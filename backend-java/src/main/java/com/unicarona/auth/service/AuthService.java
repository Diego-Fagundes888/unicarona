package com.unicarona.auth.service;

import com.unicarona.auth.config.AuthSettings;
import com.unicarona.auth.dto.AuthDto;
import com.unicarona.auth.dto.SessionTokens;
import com.unicarona.auth.repository.AuthRepository;
import com.unicarona.security.*;
import com.unicarona.shared.ApiException;
import com.unicarona.shared.TransactionRetries;
import com.unicarona.user.repository.UserRepository;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
  private final UserRepository users;
  private final AuthRepository repository;
  private final AuthTransactions transactions;
  private final AuthSettings settings;
  private final CryptoService crypto;
  private final JwtService jwt;
  private final String dummyHash;
  private final TransactionRetries retries;

  public AuthService(
      UserRepository users,
      AuthRepository repository,
      AuthTransactions transactions,
      AuthSettings settings,
      CryptoService crypto,
      JwtService jwt,
      TransactionRetries retries) {
    this.users = users;
    this.repository = repository;
    this.transactions = transactions;
    this.settings = settings;
    this.crypto = crypto;
    this.jwt = jwt;
    dummyHash = crypto.hashPassword(crypto.randomToken());
    this.retries = retries;
  }

  public void limit(String key, int maximum, int seconds) {
    ApiException.require(
        repository.incrementRate(crypto.hmac(key, settings.pepper()), seconds) <= maximum,
        "RATE_LIMITED",
        "Muitas tentativas. Aguarde para tentar novamente.",
        429);
  }

  public void limitWithRetry(String key, int maximum, int seconds) {
    String bucket = crypto.hmac(key, settings.pepper());
    if (repository.incrementRate(bucket, seconds) > maximum) {
      int remaining = repository.rateRetryAfter(bucket);
      int minutes = (remaining + 59) / 60;
      throw new ApiException(
          "RATE_LIMITED",
          "Muitas tentativas de confirmação. Aguarde até "
              + minutes
              + (minutes == 1 ? " minuto" : " minutos")
              + " para tentar novamente.",
          429,
          java.util.Map.of("retryAfterSeconds", remaining));
    }
  }

  public void register(AuthDto.Register input) {
    ApiException.require(
        isAdult(LocalDate.parse(input.birth()), LocalDate.now(ZoneId.of("America/Sao_Paulo"))),
        "UNDERAGE",
        "É necessário ter 18 anos completos.",
        422);
    String hash = crypto.hashPassword(input.password());
    retries.run(() -> transactions.register(input, hash));
    repository.reserveEmailCooldown(
        crypto.hmac("email-cooldown:" + input.email(), settings.pepper()), 60);
  }

  public static boolean isAdult(LocalDate birth, LocalDate today) {
    // Calendar cutoff matches the database: March 1 is not an eighteenth birthday on February 29.
    return !birth.isAfter(today.minusYears(18));
  }

  public AuthTransactions.LoginResult login(String email, String password) {
    var user = users.findByEmailAndDeletedAtIsNull(email);
    boolean valid =
        crypto.verifyPassword(user.map(u -> u.getPasswordHash()).orElse(dummyHash), password);
    ApiException.require(
        user.isPresent() && valid, "INVALID_CREDENTIALS", "E-mail ou senha incorretos.", 401);
    return retries.call(() -> transactions.login(user.get().getId(), user.get().getPasswordHash()));
  }

  public Actor authenticate(String token) {
    ApiException.require(
        token != null && !token.isEmpty(),
        "UNAUTHENTICATED",
        "Entre na sua conta para continuar.",
        401);
    Actor actor =
        repository
            .authenticate(crypto.hmac(token, settings.pepper()))
            .orElseThrow(AuthService::expired);
    // Legacy opaque tokens stay valid against the registry until their original expiry/revocation.
    ApiException.require(
        token.matches("[A-Za-z0-9_-]{43}") || jwt.matches(token, actor),
        "SESSION_EXPIRED",
        "Sua sessão expirou. Entre novamente.",
        401);
    return actor;
  }

  public SessionTokens refresh(String token, String csrf) {
    var result = retries.call(() -> transactions.refresh(token, csrf));
    // Throw only AFTER the transactional proxy commits family revocation on reuse.
    if (result.error() != null)
      throw new ApiException(result.error(), "Sua sessão expirou. Entre novamente.", 401);
    return result.tokens();
  }

  public void verify(String token) {
    var result = retries.call(() -> transactions.verifyEmail(token));
    if (result.code() != null) throw new ApiException(result.code(), result.message(), 400);
  }

  public void resend(String email) {
    int wait =
        repository.reserveEmailCooldown(
            crypto.hmac("email-cooldown:" + email, settings.pepper()), 60);
    if (wait > 0)
      throw new ApiException(
          "EMAIL_COOLDOWN",
          "Aguarde para solicitar outro e-mail.",
          429,
          java.util.Map.of("retryAfterSeconds", wait));
    limit("email-hour:" + email, 5, 3600);
    retries.run(() -> transactions.resend(email));
  }

  public void forgot(String email) {
    retries.run(() -> transactions.forgot(email));
  }

  public boolean emailServiceDelayed() {
    return repository.emailServiceDelayed();
  }

  public void reset(String token, String password) {
    String hash = crypto.hashPassword(password);
    retries.run(() -> transactions.reset(token, hash));
  }

  public void logout(Actor actor) {
    retries.run(() -> transactions.logout(actor));
  }

  public void change(Actor actor, String current, String next) {
    var user = users.findLiveById(actor.id());
    ApiException.require(
        user.isPresent() && crypto.verifyPassword(user.get().getPasswordHash(), current),
        "INVALID_CREDENTIALS",
        "Não foi possível confirmar sua senha.",
        401);
    String hash = crypto.hashPassword(next);
    retries.run(() -> transactions.change(actor, user.get().getPasswordHash(), hash));
  }

  private static ApiException expired() {
    return new ApiException("SESSION_EXPIRED", "Sua sessão expirou. Entre novamente.", 401);
  }
}
