package com.unicarona.seed;

import com.unicarona.shared.ApiException;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Opt-in development data; credentials never have a built-in fallback or a printable record. */
@Component
public class SeedSettings {
    private final Environment environment;
    public SeedSettings(Environment environment) { this.environment=environment; }
    public void allowed() { ApiException.require(!"production".equals(environment.getProperty("NODE_ENV")),"SEED_FORBIDDEN","Dados de demonstração não são permitidos em produção.",500); }
    public String password() {
        allowed();String value=environment.getProperty("SEED_PASSWORD","");ApiException.require(value.length()>=16,"SEED_PASSWORD_REQUIRED","Configure SEED_PASSWORD com pelo menos 16 caracteres no ambiente privado.",500);return value;
    }
    public void scenariosAllowed() { allowed();ApiException.require("development".equals(environment.getProperty("PAYMENT_PROVIDER","development")),"SEED_FORBIDDEN","Cenários sintéticos exigem gateway local de desenvolvimento.",500); }
}
