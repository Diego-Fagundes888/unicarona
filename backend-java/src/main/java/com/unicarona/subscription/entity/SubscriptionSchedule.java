package com.unicarona.subscription.entity;
import jakarta.persistence.*;
import java.io.Serializable;
import java.util.*;

@Entity @Table(name="subscription_schedule")
public class SubscriptionSchedule {
    @Embeddable public static class Key implements Serializable {
        @Column(name="subscription_id") private UUID subscriptionId;
        @Column(name="ride_id") private UUID rideId;
        protected Key() {}
        public Key(UUID subscription,UUID ride) { subscriptionId=subscription;rideId=ride; }
        @Override public boolean equals(Object other) { return this==other || other instanceof Key k && Objects.equals(subscriptionId,k.subscriptionId) && Objects.equals(rideId,k.rideId); }
        @Override public int hashCode() { return Objects.hash(subscriptionId,rideId); }
    }
    @EmbeddedId private Key id;
    @Column(name="reservation_id") private UUID reservationId;
    private int cents;
    private boolean held;
    protected SubscriptionSchedule() {}
    public SubscriptionSchedule(UUID subscription,UUID ride,UUID reservation,int cents) { id=new Key(subscription,ride);reservationId=reservation;this.cents=cents; }
}
