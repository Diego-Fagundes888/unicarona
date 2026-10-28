package com.unicarona.payment.provider;

import com.unicarona.payment.dto.TransferDto;
import java.util.UUID;

public interface TransferProvider {
    record Input(UUID transferId,int amountCents,String type) {}
    record Result(String reference) {}
    Result create(Input input) throws Exception;
    void authenticate(TransferDto.Event event,String timestamp,String signature);
}
