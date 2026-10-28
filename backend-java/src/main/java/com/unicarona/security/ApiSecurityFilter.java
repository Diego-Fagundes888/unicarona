package com.unicarona.security;

import com.unicarona.auth.config.AuthSettings;
import com.unicarona.auth.service.AuthService;
import com.unicarona.shared.ApiException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

public class ApiSecurityFilter extends OncePerRequestFilter {
  private static final Set<String> PUBLIC =
      Set.of(
          "/api/v1/catalogs",
          "/api/v1/runtime",
          "/api/v1/payments/config",
          "/api/v1/auth/register",
          "/api/v1/auth/login",
          "/api/v1/auth/verify-email",
          "/api/v1/auth/resend-verification",
          "/api/v1/auth/email-service-status",
          "/api/v1/auth/forgot-password",
          "/api/v1/auth/reset-password",
          "/api/v1/auth/refresh");
  private static final Set<String> RESTRICTED =
      Set.of(
          "/api/v1/auth/me",
          "/api/v1/auth/logout",
          "/api/v1/auth/change-password",
          "/api/v1/users/me",
          "/api/v1/users/me/settings",
          "/api/v1/wallet",
          "/api/v1/wallet/debt",
          "/api/v1/transfers");
  private final AuthService auth;
  private final AuthSettings settings;
  private final CryptoService crypto;
  private final HandlerExceptionResolver errors;
  private final RequestContractValidation contracts;
  private final com.unicarona.privacy.service.PrivacyReadiness readiness;

  public ApiSecurityFilter(
      AuthService auth,
      AuthSettings settings,
      CryptoService crypto,
      HandlerExceptionResolver errors,
      RequestContractValidation contracts,
      com.unicarona.privacy.service.PrivacyReadiness readiness) {
    this.auth = auth;
    this.settings = settings;
    this.crypto = crypto;
    this.errors = errors;
    this.contracts = contracts;
    this.readiness = readiness;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().startsWith("/api/v1/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    try {
      String path = request.getRequestURI();
      ApiException.require(
          readiness.ready(),
          "PRIVACY_RECOVERY_PENDING",
          "O serviço está concluindo uma verificação de recuperação. Tente novamente em instantes.",
          503);
      auth.limit("api:" + request.getRemoteAddr(), 180, 60);
      boolean mutation = !Set.of("GET", "HEAD", "OPTIONS").contains(request.getMethod());
      if (mutation && !path.startsWith("/api/v1/webhooks/"))
        ApiException.require(
            settings.origin().equals(request.getHeader("Origin")),
            "ORIGIN_INVALID",
            "Origem da requisição não permitida.",
            403);
      request = contracts.validate(request);
      boolean publicLegal =
          Set.of("GET", "HEAD").contains(request.getMethod())
              && path.matches("/api/v1/legal/documents(?:/[^/]+(?:/[^/]+)?)?");
      if (!PUBLIC.contains(path) && !publicLegal && !path.startsWith("/api/v1/webhooks/")) {
        Actor actor = auth.authenticate(cookie(request, "uc_access"));
        // A valid session still owns its safety history and privacy rights if email verification is
        // lost.
        // This exception does not create a session for an unverified signup or unlock new
        // contracts.
        boolean essentialRights =
            path.startsWith("/api/v1/privacy/")
                || path.equals("/api/v1/occurrences")
                || path.equals("/api/v1/blocks")
                || path.matches("/api/v1/blocks/[^/]+/remove")
                || path.equals("/api/v1/auth/logout")
                || (!mutation && path.equals("/api/v1/auth/me"));
        ApiException.require(
            actor.emailVerified() || essentialRights,
            "EMAIL_UNVERIFIED",
            "Confirme seu e-mail antes de continuar.",
            403);
        var authentication =
            UsernamePasswordAuthenticationToken.authenticated(
                actor,
                null,
                actor.authorities().stream().map(SimpleGrantedAuthority::new).toList());
        SecurityContextHolder.getContext().setAuthentication(authentication);
        if (path.startsWith("/api/v1/admin/"))
          ApiException.require(
              actor.authorities().contains("ROLE_ADMIN"),
              "FORBIDDEN",
              "Acesso administrativo necessário.",
              403);
        boolean restricted =
            RESTRICTED.contains(path) && !("/api/v1/users/me".equals(path) && mutation);
        restricted =
            restricted
                || path.equals("/api/v1/notifications")
                || (path.startsWith("/api/v1/notifications/") && path.endsWith("/read"));
        restricted = restricted || (!mutation && path.startsWith("/api/v1/files/"));
        restricted = restricted || (!mutation && path.matches("/api/v1/rides/[^/]+"));
        restricted =
            restricted
                || (!mutation
                    && !path.equals("/api/v1/routines/mine")
                    && path.matches("/api/v1/routines/[^/]+"));
        restricted = restricted || (!mutation && path.matches("/api/v1/ride-requests/[^/]+"));
        restricted = restricted || (!mutation && path.matches("/api/v1/transfers/[^/]+/refunds"));
        restricted = restricted || (!mutation && path.equals("/api/v1/wallet/refund-review"));
        restricted =
            restricted
                || path.equals("/api/v1/wallet/debt/checkout")
                || path.matches("/api/v1/wallet/debt/payments/[^/]+(?:/sync)?");
        restricted =
            restricted
                || (!mutation
                    && (path.equals("/api/v1/subscriptions")
                        || path.matches("/api/v1/subscriptions/[^/]+(?:/rights)?")));
        restricted =
            restricted
                || (!mutation
                    && (path.equals("/api/v1/reservations")
                        || path.matches("/api/v1/reservations/[^/]+(?:/contact)?")));
        restricted =
            restricted
                || (mutation
                    && (path.matches("/api/v1/rides/[^/]+/(finish|cancel)")
                        || path.matches("/api/v1/reservations/[^/]+/(final|contest)")));
        restricted =
            restricted
                || path.equals("/api/v1/occurrences")
                || path.equals("/api/v1/blocks")
                || (mutation && path.matches("/api/v1/blocks/[^/]+/remove"));
        // Suspension limits the service, not a person's access to privacy rights.
        restricted = restricted || path.startsWith("/api/v1/privacy/");
        // Reversible closure permits completion/cancellation of existing contracts, never new
        // bookings.
        if (Set.of("closing", "deactivated").contains(actor.accountStatus())) {
          restricted =
              restricted
                  || (!mutation
                      && Set.of(
                              "/api/v1/rides/mine",
                              "/api/v1/routines/mine",
                              "/api/v1/ride-requests")
                          .contains(path));
          restricted =
              restricted
                  || (mutation
                      && (path.matches("/api/v1/rides/[^/]+/(start|no-class|keep-time)")
                          || path.matches(
                              "/api/v1/reservations/[^/]+/(board|cancel|absence|time-response)")
                          || path.matches("/api/v1/ride-requests/[^/]+/cancel")
                          || path.matches("/api/v1/subscriptions/[^/]+/(cancel-renewal|remove)")));
          restricted =
              restricted
                  || (!mutation && path.matches("/api/v1/payments/[^/]+(?:/summary|/gateway)?"))
                  || (mutation && path.matches("/api/v1/payments/[^/]+/sync"));
        }
        if (!restricted)
          ApiException.require(
              "active".equals(actor.accountStatus()),
              "ACCOUNT_RESTRICTED",
              "Conta em revisão. Você ainda pode acessar segurança e ocorrências.",
              403);
        if (mutation) {
          String csrf = request.getHeader("x-csrf-token");
          ApiException.require(
              crypto.equalToken(
                  crypto.hmac(csrf == null ? "" : csrf, settings.pepper()), actor.csrfHash()),
              "CSRF_INVALID",
              "Atualize a página e tente novamente.",
              403);
        }
      }
    } catch (Exception error) {
      SecurityContextHolder.clearContext();
      errors.resolveException(request, response, null, error);
      return;
    }
    chain.doFilter(request, response);
  }

  public static String cookie(HttpServletRequest request, String name) {
    if (request.getCookies() != null)
      for (Cookie cookie : request.getCookies())
        if (name.equals(cookie.getName())) return cookie.getValue();
    return null;
  }
}
