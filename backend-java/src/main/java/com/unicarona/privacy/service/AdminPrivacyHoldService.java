package com.unicarona.privacy.service;

import com.unicarona.privacy.dto.*;
import com.unicarona.security.Actor;
import com.unicarona.shared.TransactionRetries;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class AdminPrivacyHoldService {
    private final AdminPrivacyHoldTransactions transactions;private final PrivacyReauthentication reauthentication;private final TransactionRetries retries;
    public AdminPrivacyHoldService(AdminPrivacyHoldTransactions transactions,PrivacyReauthentication reauthentication,TransactionRetries retries){this.transactions=transactions;this.reauthentication=reauthentication;this.retries=retries;}
    public List<PrivacyHoldDto.Item> list(Actor actor,UUID owner,String state,int limit,int offset){return transactions.list(actor,owner,state,limit,offset);}
    public List<PrivacyHoldDto.OwnRetention> own(Actor actor,int limit,int offset){return transactions.own(actor,limit,offset);}
    public PrivacyHoldDto.Item create(Actor actor,PrivacyHoldDto.Create input,String key){String expected=reauthentication.confirmAdministrator(actor,input.password());return retries.call(()->transactions.create(actor,expected,input,key));}
    public PrivacyHoldDto.Item review(Actor actor,UUID id,PrivacyHoldDto.Review input,String key){String expected=reauthentication.confirmAdministrator(actor,input.password());return retries.call(()->transactions.review(actor,expected,id,input,key));}
    public PrivacyHoldDto.Evidence detail(Actor actor,UUID id,PrivacyDto.Reauthenticate input){String expected=reauthentication.confirmAdministrator(actor,input.password());return retries.call(()->transactions.detail(actor,expected,id));}
    public AdminPrivacyHoldTransactions.HeldData heldData(Actor actor,UUID id,PrivacyDto.Reauthenticate input){String expected=reauthentication.confirmAdministrator(actor,input.password());return retries.call(()->transactions.heldData(actor,expected,id));}
}
