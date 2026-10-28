package com.unicarona.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unicarona.auth.config.AuthSettings;
import com.unicarona.auth.service.AuthService;
import com.unicarona.shared.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.HeaderWriterFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/** Cookie authentication backed by live session records; unmigrated operations remain closed. */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableMethodSecurity
public class SecurityConfiguration {
  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      ObjectMapper mapper,
      AuthService authService,
      AuthSettings settings,
      CryptoService crypto,
      RequestContractValidation contracts,
      com.unicarona.privacy.service.PrivacyReadiness readiness,
      com.unicarona.privacy.config.PrivacySettings privacy,
      @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver)
      throws Exception {
    return http.sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .csrf(csrf -> csrf.disable())
        .requestCache(cache -> cache.disable())
        .addFilterBefore(
            new ApiSecurityFilter(authService, settings, crypto, resolver, contracts, readiness),
            UsernamePasswordAuthenticationFilter.class)
        .addFilterBefore(new DashboardNonceFilter(privacy), HeaderWriterFilter.class)
        .formLogin(form -> form.disable())
        .httpBasic(basic -> basic.disable())
        .logout(logout -> logout.disable())
        .exceptionHandling(
            errors ->
                errors
                    .authenticationEntryPoint(
                        (request, response, error) ->
                            writeError(
                                mapper,
                                request,
                                response,
                                401,
                                "UNAUTHENTICATED",
                                "Entre na sua conta para continuar."))
                    .accessDeniedHandler(
                        (request, response, error) ->
                            writeError(
                                mapper,
                                request,
                                response,
                                403,
                                "FORBIDDEN",
                                "Acesso administrativo necessário.")))
        .authorizeHttpRequests(
            auth ->
                auth
                    // Servlet-internal completion of an already authorized stream; the initial POST
                    // still runs every check.
                    .requestMatchers(
                        request ->
                            request.getDispatcherType() == jakarta.servlet.DispatcherType.ASYNC
                                && Boolean.TRUE.equals(
                                    request.getAttribute("unicarona.authorizedPrivateDownload")))
                    .permitAll()
                    .requestMatchers(
                        "/health",
                        "/health/ready",
                        "/api/v1/catalogs",
                        "/api/v1/runtime",
                        "/api/docs/**",
                        "/api/docs",
                        "/swagger-ui/**")
                    .permitAll()
                    .requestMatchers(
                        org.springframework.http.HttpMethod.GET, "/api/v1/payments/config")
                    .permitAll()
                    .requestMatchers(
                        org.springframework.http.HttpMethod.GET,
                        "/api/v1/auth/email-service-status")
                    .permitAll()
                    .requestMatchers(
                        org.springframework.http.HttpMethod.GET, "/api/v1/privacy/account")
                    .authenticated()
                    .requestMatchers(
                        org.springframework.http.HttpMethod.GET, "/api/v1/privacy/identity")
                    .authenticated()
                    .requestMatchers(
                        org.springframework.http.HttpMethod.POST,
                        "/api/v1/privacy/identity/email",
                        "/api/v1/privacy/identity/email/confirm",
                        "/api/v1/privacy/identity/email/cancel",
                        "/api/v1/privacy/identity/birth")
                    .authenticated()
                    .requestMatchers(
                        org.springframework.http.HttpMethod.POST,
                        "/api/v1/privacy/account/deactivate",
                        "/api/v1/privacy/account/reactivate")
                    .authenticated()
                    .requestMatchers(
                        org.springframework.http.HttpMethod.GET, "/api/v1/admin/privacy/accounts/*")
                    .hasRole("ADMIN")
                    .requestMatchers(
                        org.springframework.http.HttpMethod.POST,
                        "/api/v1/admin/privacy/accounts/*/decision")
                    .hasRole("ADMIN")
                    .requestMatchers(
                        org.springframework.http.HttpMethod.GET,
                        "/api/v1/legal/documents",
                        "/api/v1/legal/documents/*",
                        "/api/v1/legal/documents/*/*")
                    .permitAll()
                    .requestMatchers(
                        org.springframework.http.HttpMethod.HEAD,
                        "/api/v1/legal/documents",
                        "/api/v1/legal/documents/*",
                        "/api/v1/legal/documents/*/*")
                    .permitAll()
                    .requestMatchers(
                        org.springframework.http.HttpMethod.GET, "/api/v1/transfers/*/refunds")
                    .authenticated()
                    .requestMatchers(
                        org.springframework.http.HttpMethod.POST,
                        "/api/v1/auth/register",
                        "/api/v1/auth/login",
                        "/api/v1/auth/verify-email",
                        "/api/v1/auth/resend-verification",
                        "/api/v1/auth/forgot-password",
                        "/api/v1/auth/reset-password",
                        "/api/v1/auth/refresh")
                    .permitAll()
                    .requestMatchers(
                        "/api/v1/auth/me", "/api/v1/auth/logout", "/api/v1/auth/change-password")
                    .authenticated()
                    .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/users/me")
                    .authenticated()
                    .requestMatchers(
                        org.springframework.http.HttpMethod.PATCH, "/api/v1/users/me/settings")
                    .authenticated()
                    .requestMatchers(org.springframework.http.HttpMethod.PATCH, "/api/v1/users/me")
                    .authenticated()
                    .requestMatchers(
                        org.springframework.http.HttpMethod.GET,
                        "/api/v1/privacy/preferences",
                        "/api/v1/privacy/requests",
                        "/api/v1/privacy/requests/*",
                        "/api/v1/privacy/closure")
                    .authenticated()
                    .requestMatchers(
                        org.springframework.http.HttpMethod.PATCH, "/api/v1/privacy/preferences")
                    .authenticated()
                    .requestMatchers(
                        org.springframework.http.HttpMethod.POST,
                        "/api/v1/privacy/requests",
                        "/api/v1/privacy/export",
                        "/api/v1/privacy/closure",
                        "/api/v1/privacy/closure/cancel")
                    .authenticated()
                    .requestMatchers(
                        org.springframework.http.HttpMethod.GET,
                        "/api/v1/privacy/legal",
                        "/api/v1/privacy/legal/history")
                    .authenticated()
                    .requestMatchers(
                        org.springframework.http.HttpMethod.POST, "/api/v1/privacy/legal")
                    .authenticated()
                    .requestMatchers(
                        org.springframework.http.HttpMethod.GET,
                        "/api/v1/admin/privacy/requests",
                        "/api/v1/admin/privacy/requests/*")
                    .hasRole("ADMIN")
                    .requestMatchers(
                        org.springframework.http.HttpMethod.POST,
                        "/api/v1/admin/privacy/requests/*/respond")
                    .hasRole("ADMIN")
                    .requestMatchers(
                        org.springframework.http.HttpMethod.GET, "/api/v1/privacy/retentions")
                    .authenticated()
                    .requestMatchers(
                        org.springframework.http.HttpMethod.GET, "/api/v1/admin/privacy/holds")
                    .hasRole("ADMIN")
                    .requestMatchers(
                        org.springframework.http.HttpMethod.POST,
                        "/api/v1/admin/privacy/holds",
                        "/api/v1/admin/privacy/holds/*/review",
                        "/api/v1/admin/privacy/holds/*/evidence",
                        "/api/v1/admin/privacy/holds/*/data")
                    .hasRole("ADMIN")
                    .requestMatchers(
                        org.springframework.http.HttpMethod.POST,
                        "/api/v1/users/me/academic-verification",
                        "/api/v1/notifications/*/read")
                    .authenticated()
                    .requestMatchers(
                        org.springframework.http.HttpMethod.GET, "/api/v1/notifications")
                    .authenticated()
                    .requestMatchers(
                        org.springframework.http.HttpMethod.GET,
                        "/api/v1/admin/academic-verifications")
                    .hasRole("ADMIN")
                    .requestMatchers(
                        org.springframework.http.HttpMethod.POST,
                        "/api/v1/admin/academic-verifications/*/review")
                    .hasRole("ADMIN")
                    .requestMatchers("/api/v1/drivers/applications")
                    .authenticated()
                    .requestMatchers(
                        org.springframework.http.HttpMethod.GET,
                        "/api/v1/admin/drivers/applications")
                    .hasRole("ADMIN")
                    .requestMatchers(
                        org.springframework.http.HttpMethod.POST,
                        "/api/v1/admin/drivers/applications/*/review")
                    .hasRole("ADMIN")
                    .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/v1/files")
                    .authenticated()
                    .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/files/*")
                    .authenticated()
                    .requestMatchers(
                        org.springframework.http.HttpMethod.GET,
                        "/api/v1/wallet",
                        "/api/v1/wallet/debt")
                    .authenticated()
                    .requestMatchers(
                        org.springframework.http.HttpMethod.GET, "/api/v1/wallet/debt/payments/*")
                    .authenticated()
                    .requestMatchers(
                        org.springframework.http.HttpMethod.POST,
                        "/api/v1/wallet/debt/checkout",
                        "/api/v1/wallet/debt/payments/*/sync")
                    .authenticated()
                    .requestMatchers(
                        org.springframework.http.HttpMethod.GET, "/api/v1/wallet/refund-review")
                    .authenticated()
                    .requestMatchers(
                        org.springframework.http.HttpMethod.GET, "/api/v1/admin/reconciliation")
                    .hasRole("ADMIN")
                    .requestMatchers(
                        org.springframework.http.HttpMethod.GET,
                        "/api/v1/admin/payment-reviews",
                        "/api/v1/admin/payment-reviews/*")
                    .hasRole("ADMIN")
                    .requestMatchers(
                        org.springframework.http.HttpMethod.POST,
                        "/api/v1/admin/payment-reviews/*/recheck",
                        "/api/v1/admin/payment-reviews/*/resolve")
                    .hasRole("ADMIN")
                    .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/admin/jobs")
                    .hasRole("ADMIN")
                    .requestMatchers(
                        org.springframework.http.HttpMethod.GET,
                        "/api/v1/admin/accounts",
                        "/api/v1/admin/pricing",
                        "/api/v1/admin/calendar",
                        "/api/v1/admin/metrics")
                    .hasRole("ADMIN")
                    .requestMatchers(
                        org.springframework.http.HttpMethod.POST,
                        "/api/v1/admin/accounts/*/review",
                        "/api/v1/admin/pricing",
                        "/api/v1/admin/calendar",
                        "/api/v1/admin/calendar/*/deactivate")
                    .hasRole("ADMIN")
                    .requestMatchers(
                        org.springframework.http.HttpMethod.POST, "/api/v1/admin/jobs/*/retry")
                    .hasRole("ADMIN")
                    .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/transfers")
                    .authenticated()
                    .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/v1/transfers")
                    .authenticated()
                    .requestMatchers(
                        org.springframework.http.HttpMethod.POST, "/api/v1/webhooks/transfers")
                    .permitAll()
                    .requestMatchers(
                        org.springframework.http.HttpMethod.POST, "/api/v1/webhooks/payments")
                    .permitAll()
                    .requestMatchers(
                        org.springframework.http.HttpMethod.POST, "/api/v1/webhooks/mercadopago")
                    .permitAll()
                    .requestMatchers(
                        org.springframework.http.HttpMethod.GET,
                        "/api/v1/payments/*/gateway",
                        "/api/v1/payments/*/summary")
                    .authenticated()
                    .requestMatchers(
                        org.springframework.http.HttpMethod.POST,
                        "/api/v1/payments/*/card",
                        "/api/v1/payments/*/sync")
                    .authenticated()
                    .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/payments/*")
                    .authenticated()
                    .requestMatchers(
                        org.springframework.http.HttpMethod.POST,
                        "/api/v1/payments/*/checkout",
                        "/api/v1/development/payments/*/result")
                    .authenticated()
                    .requestMatchers(
                        org.springframework.http.HttpMethod.GET,
                        "/api/v1/rides",
                        "/api/v1/rides/mine",
                        "/api/v1/rides/*")
                    .authenticated()
                    .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/v1/rides")
                    .authenticated()
                    .requestMatchers(
                        org.springframework.http.HttpMethod.POST,
                        "/api/v1/rides/*/start",
                        "/api/v1/rides/*/finish",
                        "/api/v1/rides/*/cancel",
                        "/api/v1/rides/*/no-class",
                        "/api/v1/rides/*/time-proposal",
                        "/api/v1/rides/*/keep-time")
                    .authenticated()
                    .requestMatchers(
                        org.springframework.http.HttpMethod.POST,
                        "/api/v1/reservations/*/board",
                        "/api/v1/reservations/*/final",
                        "/api/v1/reservations/*/contest",
                        "/api/v1/reservations/*/cancel",
                        "/api/v1/reservations/*/absence",
                        "/api/v1/reservations/*/time-response")
                    .authenticated()
                    .requestMatchers(org.springframework.http.HttpMethod.PATCH, "/api/v1/rides/*")
                    .authenticated()
                    .requestMatchers(
                        org.springframework.http.HttpMethod.GET,
                        "/api/v1/routines",
                        "/api/v1/routines/*")
                    .authenticated()
                    .requestMatchers(
                        org.springframework.http.HttpMethod.POST,
                        "/api/v1/routines",
                        "/api/v1/subscriptions/preview")
                    .authenticated()
                    .requestMatchers(
                        org.springframework.http.HttpMethod.GET,
                        "/api/v1/subscriptions",
                        "/api/v1/subscriptions/*",
                        "/api/v1/subscriptions/*/rights",
                        "/api/v1/waitlist")
                    .authenticated()
                    .requestMatchers(
                        org.springframework.http.HttpMethod.POST,
                        "/api/v1/subscriptions/*/cancel-renewal",
                        "/api/v1/subscriptions/*/remove",
                        "/api/v1/waitlist",
                        "/api/v1/waitlist/*/leave")
                    .authenticated()
                    .requestMatchers(
                        org.springframework.http.HttpMethod.PATCH,
                        "/api/v1/routines/*/availability")
                    .authenticated()
                    .requestMatchers(
                        org.springframework.http.HttpMethod.GET,
                        "/api/v1/ride-requests",
                        "/api/v1/ride-requests/*")
                    .authenticated()
                    .requestMatchers(
                        org.springframework.http.HttpMethod.GET,
                        "/api/v1/reservations",
                        "/api/v1/reservations/*",
                        "/api/v1/reservations/*/contact")
                    .authenticated()
                    .requestMatchers(
                        org.springframework.http.HttpMethod.GET,
                        "/api/v1/occurrences",
                        "/api/v1/blocks")
                    .authenticated()
                    .requestMatchers(
                        org.springframework.http.HttpMethod.POST,
                        "/api/v1/occurrences",
                        "/api/v1/blocks",
                        "/api/v1/blocks/*/remove")
                    .authenticated()
                    .requestMatchers(
                        org.springframework.http.HttpMethod.GET,
                        "/api/v1/admin/occurrences",
                        "/api/v1/admin/occurrences/*")
                    .hasRole("ADMIN")
                    .requestMatchers(
                        org.springframework.http.HttpMethod.POST,
                        "/api/v1/admin/occurrences/*/review")
                    .hasRole("ADMIN")
                    .requestMatchers(
                        org.springframework.http.HttpMethod.POST,
                        "/api/v1/ride-requests",
                        "/api/v1/ride-requests/*/respond",
                        "/api/v1/ride-requests/*/cancel")
                    .authenticated()
                    .requestMatchers(org.springframework.http.HttpMethod.HEAD, "/api/v1/admin/**")
                    .hasRole("ADMIN")
                    // The filter resolves HEAD only against an existing GET contract, with the same
                    // auth/role checks.
                    .requestMatchers(org.springframework.http.HttpMethod.HEAD, "/api/v1/**")
                    .authenticated()
                    .requestMatchers("/api/**")
                    .denyAll()
                    .anyRequest()
                    .permitAll())
        .headers(
            headers ->
                headers.addHeaderWriter(
                    (request, response) -> {
                      // Downloads can impose a stricter policy than the application document.
                      if (!response.containsHeader("Content-Security-Policy"))
                        response.setHeader(
                            "Content-Security-Policy",
                            ContentSecurityPolicy.value(
                                (String)
                                    request.getAttribute(ContentSecurityPolicy.NONCE_ATTRIBUTE)));
                    }))
        .build();
  }

  private static void writeError(
      ObjectMapper mapper,
      HttpServletRequest request,
      HttpServletResponse response,
      int status,
      String code,
      String message)
      throws IOException {
    response.setStatus(status);
    response.setContentType("application/json");
    response.setCharacterEncoding("UTF-8");
    mapper.writeValue(
        response.getOutputStream(),
        new ApiError(code, message, Map.of(), String.valueOf(request.getAttribute("requestId"))));
  }
}
