package com.unicarona.payment.service;

import com.unicarona.payment.config.BillingSettings;
import com.unicarona.payment.entity.Payment;
import com.unicarona.payment.repository.PaymentRepository;
import com.unicarona.reservation.config.ReservationSettings;
import com.unicarona.reservation.entity.RideRequest;
import com.unicarona.reservation.repository.RequestRulesRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class AcceptedPayment {
    private final PaymentRepository payments;
    private final RequestRulesRepository rules;
    private final BillingSettings billing;
    private final ReservationSettings settings;
    public AcceptedPayment(PaymentRepository payments,RequestRulesRepository rules,BillingSettings billing,ReservationSettings settings) { this.payments=payments;this.rules=rules;this.billing=billing;this.settings=settings; }
    /** The caller owns the request/participant locks and has flushed its frozen lines. */
    @Transactional(propagation=Propagation.MANDATORY) public UUID create(RideRequest request) {
        var existing=payments.findByRequestId(request.getId());if(existing.isPresent()) return existing.get().getId();
        var payment=new Payment(request.getId(),request.getPassengerId(),rules.total(request.getId()),billing.provider(),rules.checkoutExpiry(request.getExpiresAt(),settings.checkoutMinutes()));
        return payments.saveAndFlush(payment).getId();
    }
}
