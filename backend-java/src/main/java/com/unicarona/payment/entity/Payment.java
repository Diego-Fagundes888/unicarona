package com.unicarona.payment.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.SourceType;
import org.hibernate.annotations.DynamicUpdate;

@Entity @Table(name="payments") @DynamicUpdate
public class Payment {
    @Id private UUID id;
    @Column(name="request_id") private UUID requestId;
    @Column(name="passenger_id") private UUID passengerId;
    @Column(name="amount_cents") private int amountCents;
    @Column(name="credit_cents") private int creditCents;
    @Column(name="external_cents") private int externalCents;
    private String provider;
    @Column(name="provider_reference") private String providerReference;
    private String status,method;
    @Column(name="expires_at") private Instant expiresAt;
    @Column(name="paid_at") private Instant paidAt;
    @Column(name="checkout_url") private String checkoutUrl;
    @Column(name="gateway_environment") private String gatewayEnvironment;
    @Column(name="credit_requested") private boolean creditRequested;
    @CreationTimestamp(source=SourceType.DB) @Column(name="created_at",updatable=false) private Instant createdAt;
    @UpdateTimestamp(source=SourceType.DB) @Column(name="updated_at") private Instant updatedAt;
    protected Payment() {}
    public Payment(UUID request,UUID passenger,int amount,String provider,Instant expires) { id=UUID.randomUUID();requestId=request;passengerId=passenger;amountCents=amount;this.provider=provider;expiresAt=expires;status="awaiting";method="pix"; }
    public UUID getId() { return id; }
    public UUID getRequestId() { return requestId; }
    public UUID getPassengerId() { return passengerId; }
    public int getAmountCents() { return amountCents; }
    public String getStatus() { return status; }
    public Instant getExpiresAt() { return expiresAt; }
    public int getCreditCents() { return creditCents; }
    public int getExternalCents() { return externalCents; }
    public String getProvider() { return provider; }
    public String getProviderReference() { return providerReference; }
    public String getCheckoutUrl() { return checkoutUrl; }
    public String getMethod() { return method; }
    public boolean isCreditRequested() { return creditRequested; }
    public Instant getPaidAt() { return paidAt; }
    public void prepare(String method,boolean useCredit,int credit) { this.method=method;creditRequested=useCredit;creditCents=credit;externalCents=amountCents-credit;status="pending"; }
    public void approve(Instant now,boolean unavailable) { status="approved";paidAt=now;if(unavailable) creditCents=0; }
    public void fail() { status="failed"; }
}
