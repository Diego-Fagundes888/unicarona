package com.unicarona.reservation.config;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public final class ReservationSettings {
    private final int requestHours,checkoutMinutes;
    public ReservationSettings(Environment env) {
        requestHours=env.getProperty("REQUEST_EXPIRY_HOURS",Integer.class,24);checkoutMinutes=env.getProperty("CHECKOUT_EXPIRY_MINUTES",Integer.class,30);
        if(requestHours<1 || requestHours>72 || checkoutMinutes<5 || checkoutMinutes>120) throw new IllegalStateException("Invalid request/checkout expiry configuration.");
    }
    public int requestHours() { return requestHours; }
    public int checkoutMinutes() { return checkoutMinutes; }
}
