package com.unicarona.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unicarona.UniCaronaApplication;
import com.unicarona.job.repository.MaintenanceRepository;
import com.unicarona.job.service.MaintenanceTransactions;
import com.unicarona.payment.service.LedgerService;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/** Test classpath only. Selected synthetic contracts, real services, no clock endpoint or SQL writes. */
public final class MonthlyGraceSandboxHarness {
    @TestConfiguration(proxyBeanMethods=false)
    public static class CalendarConfiguration {
        @Bean @Primary MutableMaintenanceClock controlledMonthlyMaintenanceClock() { return new MutableMaintenanceClock(); }
    }
    public static void main(String[] ignored) throws Exception {
        var database=URI.create(Objects.requireNonNull(System.getenv("DATABASE_URL")));
        if(!Set.of("localhost","127.0.0.1").contains(database.getHost()) || database.getPort()!=55432 || !"/unicarona_mercadopago_sandbox".equals(database.getPath()) || !"test".equals(System.getenv("MERCADO_PAGO_ENVIRONMENT")) || !"mercadopago".equals(System.getenv("PAYMENT_PROVIDER")) || "production".equals(System.getenv("NODE_ENV"))) throw new IllegalStateException("Dedicated official sandbox required");
        Path allowPath=Path.of(Objects.requireNonNull(System.getenv("MP_GRACE_TEST_ALLOWED"))).toAbsolutePath().normalize();
        if(!allowPath.startsWith(Path.of(".local").toAbsolutePath().normalize())) throw new IllegalStateException("Private allowlist required");
        var json=new ObjectMapper();var allowed=new HashSet<UUID>();json.readTree(Files.readString(allowPath)).path("contracts").forEach(n -> allowed.add(UUID.fromString(n.asText())));
        if(allowed.isEmpty() || allowed.size()>3) throw new IllegalStateException("At most three synthetic contracts required");
        PrintStream wire=System.out;System.setOut(System.err);
        try(var app=new SpringApplicationBuilder(UniCaronaApplication.class,CalendarConfiguration.class).web(WebApplicationType.NONE).run("--UNICARONA_MODE=migrate","--spring.main.banner-mode=off","--logging.level.root=WARN");var input=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8))) {
            var sql=app.getBean(JdbcTemplate.class);var clock=app.getBean(MutableMaintenanceClock.class);var work=app.getBean(MaintenanceTransactions.class);var reads=app.getBean(MaintenanceRepository.class);
            for(var contract:allowed) {
                Boolean synthetic=sql.queryForObject("SELECT EXISTS(SELECT 1 FROM subscriptions s JOIN users p ON p.id=s.passenger_id JOIN users d ON d.id=s.driver_id JOIN routines rt ON rt.id=s.routine_id JOIN ride_routes route ON route.id=rt.route_id WHERE s.id=? AND p.email='ana@example.com' AND d.email='carolina@example.com' AND route.meeting LIKE 'Ensaio oficial de carência 15out%')",Boolean.class,contract);
                if(!Boolean.TRUE.equals(synthetic)) throw new IllegalStateException("Contract is not an allowlisted synthetic fixture");
            }
            wire.println("{\"ready\":true,\"clockScope\":\"monthly-maintenance-only\",\"financialSqlWrites\":false}");wire.flush();
            String line;while((line=input.readLine())!=null) {
                var request=json.readTree(line);String id=request.path("id").asText();
                if("close".equals(request.path("action").asText())) { wire.println(json.writeValueAsString(Map.of("id",id,"closed",true)));wire.flush();break; }
                var response=new LinkedHashMap<String,Object>();response.put("id",id);
                try {
                    var contract=UUID.fromString(request.path("contractId").asText());if(!allowed.contains(contract)) throw new IllegalArgumentException("Contract not allowed");
                    var end=LocalDate.parse(sql.queryForObject("SELECT end_date::text FROM subscriptions WHERE id=?",String.class,contract));var instant=Instant.parse(request.path("at").asText());
                    var boundary=end.plusDays(1).atStartOfDay(ZoneId.of("America/Sao_Paulo")).toInstant();
                    if(instant.isBefore(boundary.minusSeconds(1)) || instant.isAfter(boundary.plus(Duration.ofDays(6)))) throw new IllegalArgumentException("Outside grace test window");
                    clock.set(instant);response.put("candidate",reads.contracts(10000).contains(contract));response.put("changed",work.subscription(contract));
                    response.put("contract",sql.queryForMap("SELECT status,grace_until,(SELECT count(*)::int FROM subscription_schedule sc WHERE sc.subscription_id=s.id AND held) AS held,(SELECT count(*)::int FROM ride_reservations rr WHERE rr.subscription_id=s.id) AS reservations FROM subscriptions s WHERE id=?",contract));
                    response.put("clock",instant.toString());response.put("ledgerDifference",app.getBean(LedgerService.class).reconciliation().difference());
                }catch(Exception error) { response.put("error","MONTHLY_GRACE_ACTION_FAILED"); }
                wire.println(json.writeValueAsString(response));wire.flush();
            }
        }
    }
}
