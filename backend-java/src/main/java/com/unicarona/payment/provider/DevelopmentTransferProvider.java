package com.unicarona.payment.provider;

import com.unicarona.payment.config.BillingSettings;
import com.unicarona.payment.dto.TransferDto;
import com.unicarona.shared.ApiException;

public final class DevelopmentTransferProvider implements TransferProvider {
    private final BillingSettings settings;
    private final WebhookAuthentication authentication;
    public DevelopmentTransferProvider(BillingSettings settings,WebhookAuthentication authentication) { this.settings=settings;this.authentication=authentication; }
    @Override public Result create(Input input) {
        ApiException.require(!settings.production(),"PROVIDER_UNAVAILABLE","Provedor de testes indisponível.",503);return new Result("dev_transfer_"+input.transferId());
    }
    @Override public void authenticate(TransferDto.Event event,String timestamp,String signature) { authentication.authenticate("transfer-v1",event,timestamp,signature); }
}
