package com.unicarona.support;

import java.time.*;
import java.util.concurrent.atomic.AtomicReference;

/** Only monthly maintenance uses this clock; payment signatures and provider time remain real. */
public final class MutableMaintenanceClock extends Clock {
    private final AtomicReference<Instant> current=new AtomicReference<>(Instant.now());
    public void set(Instant instant) { current.set(instant); }
    @Override public Instant instant() { return current.get(); }
    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(),zone); }
}
