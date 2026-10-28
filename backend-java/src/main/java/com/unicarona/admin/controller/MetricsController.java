package com.unicarona.admin.controller;

import com.unicarona.admin.service.*;
import com.unicarona.security.Actor;
import com.zaxxer.hikari.HikariDataSource;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import javax.sql.DataSource;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @Tag(name="Admin") @SecurityRequirement(name="cookieAuth")
public class MetricsController {
    public record Pool(int total,int idle,int waiting) {}
    public record Metrics(HttpMetrics.Snapshot http,Pool database) {}
    private final HttpMetrics metrics;private final HikariDataSource database;
    public MetricsController(HttpMetrics metrics,DataSource database) { this.metrics=metrics;this.database=(HikariDataSource)database; }
    @GetMapping("/api/v1/admin/metrics") @Operation(summary="Métricas agregadas do processo e do pool, sem dados de alunos")
    public Metrics metrics(@AuthenticationPrincipal Actor actor) { AdminCatalogService.admin(actor);var pool=database.getHikariPoolMXBean();return new Metrics(metrics.snapshot(),new Pool(pool.getTotalConnections(),pool.getIdleConnections(),pool.getThreadsAwaitingConnection())); }
}
