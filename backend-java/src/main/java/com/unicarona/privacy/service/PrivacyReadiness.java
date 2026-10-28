package com.unicarona.privacy.service;

import org.springframework.stereotype.Component;

@Component
public final class PrivacyReadiness {
    private volatile boolean ready;
    private final java.util.List<Runnable> listeners=new java.util.concurrent.CopyOnWriteArrayList<>();
    public boolean ready(){return ready;}
    public void begin(){ready=false;}
    public void onReady(Runnable listener){listeners.add(java.util.Objects.requireNonNull(listener));if(ready)listener.run();}
    public void complete(){ready=true;listeners.forEach(Runnable::run);}
}
