package com.unicarona.job.config;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Calendar boundaries can be exercised in tests without changing database or provider time. */
@Configuration(proxyBeanMethods=false)
public class MaintenanceClockConfiguration {
    @Bean @ConditionalOnMissingBean(Clock.class)
    Clock monthlyMaintenanceClock() { return Clock.systemUTC(); }
}
