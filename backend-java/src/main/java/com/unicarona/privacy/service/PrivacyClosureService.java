package com.unicarona.privacy.service;

import com.unicarona.privacy.dto.PrivacyDto;
import com.unicarona.security.Actor;
import com.unicarona.shared.TransactionRetries;
import org.springframework.stereotype.Service;

/** Password verification occurs outside row-locking transactions. */
@Service
public class PrivacyClosureService {
    private final PrivacyClosureTransactions transactions;private final PrivacyReauthentication reauthentication;private final TransactionRetries retries;
    public PrivacyClosureService(PrivacyClosureTransactions transactions,PrivacyReauthentication reauthentication,TransactionRetries retries){this.transactions=transactions;this.reauthentication=reauthentication;this.retries=retries;}
    public PrivacyDto.Closure status(Actor actor){return transactions.status(actor.id());}
    public PrivacyDto.Closure request(Actor actor,PrivacyDto.ClosureRequest input,String key){com.unicarona.shared.ApiException.require(Boolean.TRUE.equals(input.acknowledged()),"CLOSURE_CONFIRMATION_REQUIRED","Confirme que leu as consequências do encerramento.",422);String expected=reauthentication.confirm(actor,input.password());return retries.call(()->transactions.request(actor,expected,input.policyVersion(),key));}
    public PrivacyDto.Closure cancel(Actor actor,PrivacyDto.Reauthenticate input,String key){String expected=reauthentication.confirm(actor,input.password());return retries.call(()->transactions.cancel(actor,expected,key));}
}
