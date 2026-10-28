package com.unicarona.job.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="UNICARONA_MODE",havingValue="worker")
public class WorkerSettings {
    private final long maintenanceMillis,idleMillis;
    public WorkerSettings(Environment environment) {
        maintenanceMillis=positive(environment,"WORKER_MAINTENANCE_INTERVAL_MS",15000);
        idleMillis=positive(environment,"WORKER_IDLE_MS",1000);
    }
    private long positive(Environment environment,String name,long fallback) {
        long value=environment.getProperty(name,Long.class,fallback);
        if(value<1 || value>86400000) throw new IllegalArgumentException(name+" must be between 1 and 86400000.");
        return value;
    }
    public long maintenanceMillis() { return maintenanceMillis; }
    public long idleMillis() { return idleMillis; }
}
