package com.unicarona.job.service;

import com.unicarona.job.config.WorkerSettings;
import com.unicarona.shared.ApiException;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** Stop accepting work before draining the current item; keep the datasource alive until completion. */
@Component
@ConditionalOnProperty(name="UNICARONA_MODE",havingValue="worker")
public class WorkerLifecycle implements SmartLifecycle {
    private static final Logger log=LoggerFactory.getLogger(WorkerLifecycle.class);
    private final MaintenanceService maintenance;
    private final OutboxProcessor outbox;
    private final WorkerSettings settings;
    private final com.unicarona.privacy.service.PrivacyReadiness privacy;
    private final Object monitor=new Object();
    private final List<Runnable> completions=new ArrayList<>();
    private volatile boolean stopping=true,running;
    private Thread thread;
    public WorkerLifecycle(MaintenanceService maintenance,OutboxProcessor outbox,WorkerSettings settings,com.unicarona.privacy.service.PrivacyReadiness privacy) { this.maintenance=maintenance;this.outbox=outbox;this.settings=settings;this.privacy=privacy;privacy.onReady(()->{synchronized(monitor){monitor.notifyAll();}}); }
    @Override public void start() {
        synchronized(monitor) {
            if(thread!=null) return;
            stopping=false;running=true;
            thread=Thread.ofPlatform().name("unicarona-worker").daemon(false).unstarted(this::loop);
            thread.start();
        }
    }
    private void loop() {
        long nextMaintenance=System.nanoTime();
        try {
            while(begin()) {
                try {
                    if(!privacy.ready()){awaitPrivacy();continue;}
                    if(System.nanoTime()-nextMaintenance>=0) {
                        maintenance.run(100,() -> stopping||!privacy.ready());
                        nextMaintenance=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(settings.maintenanceMillis());
                    }
                    if(!begin()) break;
                    if(!privacy.ready())continue;
                    if(!outbox.processOne()) idle();
                }catch(Exception error) {
                    // Provider messages, payloads, tokens and exception stack traces never enter operational logs.
                    log.error("event=worker_iteration_failed code={}",errorCode(error));idle();
                }
            }
        }finally {
            List<Runnable> callbacks;
            synchronized(monitor) { stopping=true;running=false;thread=null;callbacks=List.copyOf(completions);completions.clear();monitor.notifyAll(); }
            callbacks.forEach(Runnable::run);
        }
    }
    private boolean begin() { synchronized(monitor) { return !stopping; } }
    private void awaitPrivacy(){
        synchronized(monitor){
            while(!stopping&&!privacy.ready())try{monitor.wait();}catch(InterruptedException interrupted){stopping=true;Thread.currentThread().interrupt();}
        }
    }
    private void idle() {
        synchronized(monitor) {
            if(stopping) return;
            try { monitor.wait(settings.idleMillis()); }
            catch(InterruptedException interrupted) { stopping=true;Thread.currentThread().interrupt(); }
        }
    }
    static String errorCode(Throwable error) {
        Set<Throwable> visited=Collections.newSetFromMap(new IdentityHashMap<>());
        for(Throwable cause=error;cause!=null && visited.add(cause);cause=cause.getCause()) {
            if(cause instanceof SQLException sql && sql.getSQLState()!=null && sql.getSQLState().matches("[0-9A-Z]{5}")) return sql.getSQLState();
            if(cause instanceof ApiException api && api.code().matches("[A-Z][A-Z0-9_]{0,79}")) return api.code();
        }
        return "WORKER_ERROR";
    }
    @Override public void stop(Runnable callback) {
        boolean alreadyStopped;
        synchronized(monitor) { stopping=true;alreadyStopped=thread==null;if(!alreadyStopped) completions.add(callback);monitor.notifyAll(); }
        if(alreadyStopped) callback.run();
    }
    @Override public void stop() {
        var completion=new CountDownLatch(1);stop(completion::countDown);
        try { completion.await(); }catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
    @Override public boolean isRunning() { return running; }
    @Override public int getPhase() { return Integer.MAX_VALUE; }
}
