package com.unicarona.reservation.service;

import com.unicarona.reservation.dto.LifecycleDto;
import com.unicarona.security.Actor;
import com.unicarona.shared.TransactionRetries;
import org.springframework.stereotype.Service;

/** Retries wrap the transactional proxy; a retry never reuses the failed persistence context. */
@Service
public class LifecycleService {
    private final LifecycleTransactions transactions;
    private final TransactionRetries retries;
    public LifecycleService(LifecycleTransactions transactions,TransactionRetries retries) { this.transactions=transactions;this.retries=retries; }
    public LifecycleDto.Status start(Actor actor,String id,String key) { return retries.call(() -> transactions.start(actor,id,key)); }
    public LifecycleDto.Status board(Actor actor,String id,String pin,String key) { return retries.call(() -> transactions.board(actor,id,pin,key)); }
    public LifecycleDto.Status finish(Actor actor,String id,boolean availability,String key) { return retries.call(() -> transactions.finish(actor,id,availability,key)); }
    public LifecycleDto.Status confirmFinal(Actor actor,String id,String key) { return retries.call(() -> transactions.confirmFinal(actor,id,key)); }
    public LifecycleDto.Status contest(Actor actor,String id,String reason,String key) { return retries.call(() -> transactions.contest(actor,id,reason,key)); }
    public LifecycleDto.Cancelled cancelPassenger(Actor actor,String id,String reason,String key) { return retries.call(() -> transactions.cancelPassenger(actor,id,reason,key)); }
    public LifecycleDto.Status cancelRide(Actor actor,String id,String reason,String key) { return retries.call(() -> transactions.cancelRide(actor,id,reason,key)); }
    public LifecycleDto.Status noClass(Actor actor,String id,String reason,String key) { return retries.call(() -> transactions.noClass(actor,id,reason,key)); }
    public LifecycleDto.Absence absence(Actor actor,String id,String reason,String key) { return retries.call(() -> transactions.absence(actor,id,reason,key)); }
    public LifecycleDto.Status proposeTime(Actor actor,String id,String time,String reason,String key) { return retries.call(() -> transactions.proposeTime(actor,id,time,reason,key)); }
    public LifecycleDto.Answer respondTime(Actor actor,String id,boolean accept,String key) { return retries.call(() -> transactions.respondTime(actor,id,accept,key)); }
    public LifecycleDto.Time keepTime(Actor actor,String id,String key) { return retries.call(() -> transactions.keepTime(actor,id,key)); }
}
