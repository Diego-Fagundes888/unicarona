package com.unicarona.shared;

import io.swagger.v3.oas.annotations.Operation;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RuntimeController {
    private final Environment environment;
    public RuntimeController(Environment environment) { this.environment = environment; }
    public record RuntimeDto(String paymentEnvironment, String emailEnvironment) {}
    @GetMapping("/api/v1/runtime")
    @Operation(summary = "Ambiente das integrações públicas")
    public RuntimeDto runtime() {
        return new RuntimeDto("development".equals(environment.getProperty("PAYMENT_PROVIDER", "development")) || "test".equals(environment.getProperty("MERCADO_PAGO_ENVIRONMENT","test")) ? "test" : "live",
            "development".equals(environment.getProperty("EMAIL_PROVIDER", "development")) ? "local" : "delivery");
    }
}
