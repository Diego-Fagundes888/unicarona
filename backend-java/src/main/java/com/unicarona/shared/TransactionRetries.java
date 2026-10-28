package com.unicarona.shared;

import java.sql.SQLException;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** Call outside a transactional proxy so every retry starts a fresh transaction. */
@Component
public class TransactionRetries {
    public <T> T call(Supplier<T> operation) {
        for(int attempt=0;;attempt++) {
            try { return operation.get(); }
            catch(RuntimeException error) {
                if(attempt>=3 || !isRetryable(error)) throw error;
                try { Thread.sleep(10L*(attempt+1)); }
                catch(InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); throw new ApiException("SERVICE_UNAVAILABLE","Serviço temporariamente indisponível. Tente novamente.",503);
                }
            }
        }
    }
    public void run(Runnable operation) { call(() -> { operation.run(); return null; }); }
    private boolean isRetryable(Throwable error) {
        for(Throwable cause=error;cause!=null;cause=cause.getCause())
            if(cause instanceof SQLException sql && ("40001".equals(sql.getSQLState()) || "40P01".equals(sql.getSQLState()))) return true;
        return false;
    }
}
