package com.unicarona.seed;

import org.slf4j.*;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component @org.springframework.core.annotation.Order(0)
@ConditionalOnExpression("'${UNICARONA_MODE:api}' == 'seed' || '${UNICARONA_MODE:api}' == 'seed-scenarios'")
public class SeedCommand implements ApplicationRunner {
    private static final Logger log=LoggerFactory.getLogger(SeedCommand.class);
    private final SeedService seed;private final SeedScenarios scenarios;private final Environment environment;
    public SeedCommand(SeedService seed,SeedScenarios scenarios,Environment environment) { this.seed=seed;this.scenarios=scenarios;this.environment=environment; }
    @Override public void run(ApplicationArguments args) {
        if("seed-scenarios".equals(environment.getProperty("UNICARONA_MODE"))) scenarios.run();else seed.run();
        log.info("event=development_seed_completed mode={} synthetic=true",environment.getProperty("UNICARONA_MODE"));
    }
}
