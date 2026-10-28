package com.unicarona.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {
    private final com.unicarona.admin.service.HttpMetrics metrics;
    public RequestIdFilter(com.unicarona.admin.service.HttpMetrics metrics) { this.metrics=metrics; }
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String candidate = request.getHeader("x-request-id");
        String id = candidate != null && candidate.matches("[a-zA-Z0-9-]{1,64}") ? candidate : UUID.randomUUID().toString();
        request.setAttribute("requestId", id); response.setHeader("X-Request-Id", id);
        boolean privateDownload="GET".equals(request.getMethod()) && request.getRequestURI().startsWith("/api/v1/files/");
        if (request.getRequestURI().startsWith("/api/v1/") && !privateDownload) response.setHeader("Cache-Control", "no-store");
        long started=System.nanoTime();
        try { chain.doFilter(request,response); }
        finally { metrics.completed(response.getStatus(),System.nanoTime()-started); }
    }
}
