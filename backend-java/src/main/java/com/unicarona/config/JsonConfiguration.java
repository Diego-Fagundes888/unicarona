package com.unicarona.config;

import com.fasterxml.jackson.databind.MapperFeature;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JsonConfiguration {
    @Bean Jackson2ObjectMapperBuilderCustomizer strictScalarInputs() {
        return builder -> builder.featuresToDisable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
    }
}
