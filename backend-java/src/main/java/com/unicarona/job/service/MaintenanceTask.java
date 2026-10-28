package com.unicarona.job.service;

import java.util.function.BooleanSupplier;

/** Optional bounded provider work; invoked between maintenance items and shutdown checks. */
public interface MaintenanceTask {
    void run(int batch,BooleanSupplier stopping);
}
