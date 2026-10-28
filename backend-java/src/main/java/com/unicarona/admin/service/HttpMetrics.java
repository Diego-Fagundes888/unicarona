package com.unicarona.admin.service;

import org.springframework.stereotype.Component;

/** Process-local completed response counters; no request or student data is retained. */
@Component
public class HttpMetrics {
    public record Snapshot(long requests,long errors,double totalMs) {}
    private long requests,errors;private double totalMs;
    public synchronized void completed(int status,long elapsedNanos) { requests++;if(status>=500) errors++;totalMs+=elapsedNanos/1_000_000d; }
    public synchronized Snapshot snapshot() { return new Snapshot(requests,errors,totalMs); }
}
