package com.unicarona.shared;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import org.springframework.stereotype.Component;

/** Network work runs without holding database transactions and is interrupted on timeout. */
@Component
public class ExternalCalls {
    @FunctionalInterface public interface Call<T> { T run() throws Exception; }
    private final ExecutorService executor=Executors.newVirtualThreadPerTaskExecutor();
    public <T> T call(int milliseconds,String code,String message,Call<T> operation) {
        var task=executor.submit(() -> operation.run());
        try { return task.get(milliseconds,TimeUnit.MILLISECONDS); }
        catch(ExecutionException error) {
            if(error.getCause() instanceof ApiException application) throw application;throw new ApiException(code,message,503);
        } catch(InterruptedException error) { Thread.currentThread().interrupt();throw new ApiException(code,message,503); }
        catch(TimeoutException error) { throw new ApiException(code,message,503); }
        finally { if(!task.isDone()) task.cancel(true); }
    }
    @PreDestroy public void close() { executor.shutdownNow(); }
}
