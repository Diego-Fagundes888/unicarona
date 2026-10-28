package com.unicarona.config;

import java.nio.file.Path;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfiguration implements WebMvcConfigurer {
    private final Environment environment;
    public WebConfiguration(Environment environment) { this.environment = environment; }
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String root = Path.of(environment.getProperty("FRONTEND_PATH", "dist")).toAbsolutePath().normalize().toUri().toString();
        registry.addResourceHandler("/**").addResourceLocations(root.endsWith("/") ? root : root + "/", "classpath:/static/").setCachePeriod(300);
    }
    @Override
    public void addViewControllers(ViewControllerRegistry registry) { registry.addViewController("/").setViewName("forward:/index.html"); }
}
