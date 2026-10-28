package com.unicarona;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;

@SpringBootApplication(exclude = org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
public class UniCaronaApplication {
    public static void main(String[] args) {
        var context=application().run(args);
        if(java.util.Set.of("migrate","seed","seed-scenarios").contains(context.getEnvironment().getProperty("UNICARONA_MODE","api"))) context.close();
    }
    /** Resolve the mode after ConfigData, so the same environment and .env work for both processes. */
    public static SpringApplication application() {
        var application=new SpringApplication(UniCaronaApplication.class);
        application.addListeners((ApplicationListener<ApplicationEnvironmentPreparedEvent>) event -> {
            String mode=event.getEnvironment().getProperty("UNICARONA_MODE","api");
            switch(mode) {
                case "api" -> application.setWebApplicationType(WebApplicationType.SERVLET);
                case "worker","migrate" -> application.setWebApplicationType(WebApplicationType.NONE);
                case "seed","seed-scenarios" -> {
                    if("production".equals(event.getEnvironment().getProperty("NODE_ENV"))) throw new IllegalArgumentException("Development seed is forbidden in production.");
                    application.setWebApplicationType(WebApplicationType.NONE);
                }
                default -> throw new IllegalArgumentException("UNICARONA_MODE must be api, worker, migrate, seed or seed-scenarios.");
            }
        });
        return application;
    }
}
