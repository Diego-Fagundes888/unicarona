package com.unicarona.payment.service;

import com.unicarona.payment.entity.Parcel;
import com.unicarona.payment.ledger.*;
import com.unicarona.payment.ledger.LedgerCommand.Posting;
import com.unicarona.payment.repository.ParcelRepository;
import com.unicarona.shared.ApiException;
import java.util.*;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Internal financial primitive: the caller's transaction also records the domain decision. */
@Service
public class ParcelService {
    public enum Outcome { RELEASED, CREDITED, RETAINED; public String value() { return name().toLowerCase(Locale.ROOT); } }
    private final ParcelRepository parcels;
    private final LedgerService ledger;
    private final PricingCalculator pricing;
    private final JdbcTemplate sql;
    public ParcelService(ParcelRepository parcels,LedgerService ledger,PricingCalculator pricing,JdbcTemplate sql) { this.parcels=parcels;this.ledger=ledger;this.pricing=pricing;this.sql=sql; }
    private Parcel lock(UUID id) {
        sql.execute("SET LOCAL lock_timeout='3s'");return parcels.lockById(id).orElseThrow(() -> new ApiException("PARCEL_NOT_FOUND","Parcela não encontrada.",404));
    }
    @Transactional(propagation=Propagation.MANDATORY) public void block(UUID id,String reason) {
        var parcel=lock(id);if(!Set.of("future","awaiting").contains(parcel.getStatus())) return;
        ledger.post(LedgerCommand.block(id,parcel.getPaymentId(),reason,List.of(new Posting(parcel.getPassengerId(),AccountKind.FUTURE,-parcel.getCents()),new Posting(parcel.getPassengerId(),AccountKind.BLOCKED,parcel.getCents()))));
        parcel.block(reason);
    }
    @Transactional(propagation=Propagation.MANDATORY) public void settle(UUID id,Outcome outcome,String reason,boolean review) {
        var parcel=lock(id);if(outcome.value().equals(parcel.getStatus())) return;
        ApiException.require(Set.of("future","awaiting","blocked").contains(parcel.getStatus()),"PARCEL_SETTLED","Esta parcela já foi encerrada.",409);
        ApiException.require(!"blocked".equals(parcel.getStatus()) || review,"PARCEL_BLOCKED","A parcela exige análise da ocorrência.",409);
        var from="blocked".equals(parcel.getStatus())?AccountKind.BLOCKED:AccountKind.FUTURE;
        int fee=outcome==Outcome.RELEASED?Math.toIntExact(pricing.commission(parcel.getCents(),parcel.getCommissionBps())):0;
        int net=outcome==Outcome.RELEASED?parcel.getCents()-fee:0;
        var entries=new ArrayList<Posting>();entries.add(new Posting(parcel.getPassengerId(),from,-parcel.getCents()));
        switch(outcome) {
            case CREDITED -> entries.add(new Posting(parcel.getPassengerId(),AccountKind.PASSENGER_CREDIT,parcel.getCents()));
            case RETAINED -> entries.add(new Posting(null,AccountKind.RETAINED,parcel.getCents()));
            case RELEASED -> { if(net!=0) entries.add(new Posting(parcel.getDriverId(),AccountKind.DRIVER_AVAILABLE,net));if(fee!=0) entries.add(new Posting(null,AccountKind.COMMISSION,fee)); }
        }
        ledger.post(LedgerCommand.settle(id,parcel.getPaymentId(),outcome.value(),reason,entries));
        Instant now=sql.queryForObject("SELECT now()",java.sql.Timestamp.class).toInstant();parcel.settle(outcome.value(),reason,fee,net,now);
    }
}
