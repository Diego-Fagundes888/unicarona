package com.unicarona.privacy.service;

import com.unicarona.privacy.dto.*;
import com.unicarona.security.Actor;
import com.unicarona.shared.*;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AccountControlService {
    private final AccountControlTransactions transactions;private final PrivacyReauthentication reauthentication;private final TransactionRetries retries;
    public AccountControlService(AccountControlTransactions transactions,PrivacyReauthentication reauthentication,TransactionRetries retries){this.transactions=transactions;this.reauthentication=reauthentication;this.retries=retries;}
    public AccountControlDto.State state(Actor actor){return transactions.state(actor.id());}
    public AccountControlDto.State deactivate(Actor actor,AccountControlDto.Deactivate input,String key){String expected=reauthentication.confirm(actor,input.password());return retries.call(()->transactions.deactivate(actor,expected,Boolean.TRUE.equals(input.acknowledged()),key));}
    public AccountControlDto.State reactivate(Actor actor,PrivacyDto.Reauthenticate input,String key){String expected=reauthentication.confirm(actor,input.password());return retries.call(()->transactions.reactivate(actor,expected,key));}
    public AccountControlDto.AdminState detail(Actor actor,UUID owner){return transactions.detail(actor,owner);}
    public AccountControlDto.State decide(Actor actor,UUID owner,AccountControlDto.Decision input,String key){ApiException.require("admin".equals(actor.role()),"FORBIDDEN","Acesso administrativo necessário.",403);String expected=reauthentication.confirm(actor,input.password());return retries.call(()->transactions.decide(actor,expected,owner,input,key));}
}
