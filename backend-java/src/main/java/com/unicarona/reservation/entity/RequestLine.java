package com.unicarona.reservation.entity;

import jakarta.persistence.*;
import java.io.Serializable;
import java.util.*;

@Entity @Table(name="request_lines")
public class RequestLine {
    @Embeddable public static class Key implements Serializable {
        @Column(name="request_id") private UUID requestId;
        @Column(name="ride_id") private UUID rideId;
        protected Key() {}
        public Key(UUID request,UUID ride) { requestId=request;rideId=ride; }
        @Override public boolean equals(Object other) { return this==other || other instanceof Key k && Objects.equals(requestId,k.requestId) && Objects.equals(rideId,k.rideId); }
        @Override public int hashCode() { return Objects.hash(requestId,rideId); }
    }
    @EmbeddedId private Key id;
    private int cents;
    @Column(name="commission_bps") private int commissionBps;
    protected RequestLine() {}
    public RequestLine(UUID request,UUID ride,int cents,int bps) { id=new Key(request,ride);this.cents=cents;commissionBps=bps; }
}
