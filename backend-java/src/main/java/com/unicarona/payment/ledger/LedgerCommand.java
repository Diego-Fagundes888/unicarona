package com.unicarona.payment.ledger;

import java.util.*;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** Property order preserves the SHA-256 journal fingerprints written by the original backend. */
public record LedgerCommand(String key,String kind,String reason,UUID paymentId,UUID parcelId,List<Posting> entries,Layout layout) {
    @JsonPropertyOrder({"owner","kind","cents"}) public record Posting(UUID owner,AccountKind kind,long cents) {}
    public enum Layout { TRANSFER, RECEIPT, RECEIPT_CREDIT, PARCEL_BLOCK, PARCEL_SETTLE }
    public LedgerCommand { entries=List.copyOf(entries); }
    public LinkedHashMap<String,Object> fingerprintBody() {
        var body=new LinkedHashMap<String,Object>();body.put("kind",kind);
        switch(layout) {
            case TRANSFER -> body.put("reason",reason);
            case RECEIPT -> { body.put("reason",reason);body.put("paymentId",paymentId); }
            case RECEIPT_CREDIT -> { body.put("paymentId",paymentId);body.put("reason",reason); }
            case PARCEL_BLOCK -> { body.put("parcelId",parcelId);body.put("paymentId",paymentId);body.put("reason",reason); }
            case PARCEL_SETTLE -> { body.put("paymentId",paymentId);body.put("parcelId",parcelId);body.put("reason",reason); }
        }
        body.put("entries",entries);return body;
    }
    public static LedgerCommand transfer(String key,String kind,String reason,List<Posting> entries) { return new LedgerCommand(key,kind,reason,null,null,entries,Layout.TRANSFER); }
    public static LedgerCommand receipt(UUID payment,String reason,List<Posting> entries) { return new LedgerCommand("payment:"+payment+":confirmed","receipt",reason,payment,null,entries,Layout.RECEIPT); }
    public static LedgerCommand receiptCredit(UUID payment,String reason,List<Posting> entries) { return new LedgerCommand("payment:"+payment+":unavailable","receipt_credit",reason,payment,null,entries,Layout.RECEIPT_CREDIT); }
    public static LedgerCommand block(UUID parcel,UUID payment,String reason,List<Posting> entries) { return new LedgerCommand("parcel:"+parcel+":blocked","dispute_hold",reason,payment,parcel,entries,Layout.PARCEL_BLOCK); }
    public static LedgerCommand settle(UUID parcel,UUID payment,String outcome,String reason,List<Posting> entries) { return new LedgerCommand("parcel:"+parcel+":settled",outcome,reason,payment,parcel,entries,Layout.PARCEL_SETTLE); }
}
