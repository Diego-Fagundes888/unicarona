package com.unicarona.payment.provider;

import com.unicarona.payment.dto.TransferDto;
import com.unicarona.shared.ApiException;

/** Keep the payable ledger; never manufacture an external payout confirmation. */
public final class UnavailableTransferProvider implements TransferProvider {
    @Override public Result create(Input input) {
        throw new ApiException("PAYOUT_NOT_CONFIGURED","O repasse externo depende da habilitação do provedor. O saldo permanece registrado.",503);
    }
    @Override public void authenticate(TransferDto.Event event,String timestamp,String signature) {
        throw new ApiException("WEBHOOK_UNAUTHORIZED","Provedor de repasses não habilitado.",401);
    }
}
