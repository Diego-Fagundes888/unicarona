package com.unicarona.user.service;

import com.unicarona.security.Actor;
import com.unicarona.shared.TransactionRetries;
import com.unicarona.user.dto.*;
import org.springframework.stereotype.Service;

@Service
public class ProfileService {
    private final ProfileTransactions transactions;
    private final TransactionRetries retries;
    public ProfileService(ProfileTransactions transactions,TransactionRetries retries) { this.transactions=transactions;this.retries=retries; }
    public ProfileDto update(Actor actor,ProfileEditDto.Update input,String key) { return retries.call(() -> transactions.update(actor,input,key)); }
}
