package com.unicarona.privacy.service;

import com.unicarona.privacy.dto.IdentityCorrectionDto;
import com.unicarona.privacy.dto.PrivacyDto;
import com.unicarona.security.Actor;
import com.unicarona.shared.TransactionRetries;
import java.time.LocalDate;
import org.springframework.stereotype.Service;

@Service
public class IdentityCorrectionService {
    private final IdentityCorrectionTransactions transactions;private final PrivacyReauthentication reauthentication;private final TransactionRetries retries;
    public IdentityCorrectionService(IdentityCorrectionTransactions transactions,PrivacyReauthentication reauthentication,TransactionRetries retries){this.transactions=transactions;this.reauthentication=reauthentication;this.retries=retries;}
    public IdentityCorrectionDto.State state(Actor actor){return transactions.state(actor.id());}
    public IdentityCorrectionDto.Accepted request(Actor actor,IdentityCorrectionDto.EmailRequest input,String key){String hash=reauthentication.confirm(actor,input.password());return retries.call(()->transactions.requestEmail(actor,hash,input.email(),key));}
    public IdentityCorrectionDto.Completed confirm(Actor actor,IdentityCorrectionDto.EmailConfirm input,String key){String hash=reauthentication.confirm(actor,input.password());return retries.call(()->transactions.confirmEmail(actor,hash,input.token(),key));}
    public IdentityCorrectionDto.Completed cancel(Actor actor,PrivacyDto.Reauthenticate input,String key){String hash=reauthentication.confirm(actor,input.password());return retries.call(()->transactions.cancelEmail(actor,hash,key));}
    public IdentityCorrectionDto.Completed birth(Actor actor,IdentityCorrectionDto.Birth input,String key){String hash=reauthentication.confirm(actor,input.password());return retries.call(()->transactions.birth(actor,hash,LocalDate.parse(input.birth()),key));}
}
