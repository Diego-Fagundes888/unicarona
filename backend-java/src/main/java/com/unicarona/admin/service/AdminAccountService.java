package com.unicarona.admin.service;

import com.unicarona.admin.dto.AdminAccountDto;
import com.unicarona.admin.repository.AdminAccountRepository;
import com.unicarona.security.Actor;
import com.unicarona.shared.TransactionRetries;
import com.unicarona.shared.dto.PageDto;
import org.springframework.stereotype.Service;

@Service
public class AdminAccountService {
    private final AdminAccountRepository repository;private final AdminAccountTransactions transactions;private final TransactionRetries retries;
    public AdminAccountService(AdminAccountRepository repository,AdminAccountTransactions transactions,TransactionRetries retries) { this.repository=repository;this.transactions=transactions;this.retries=retries; }
    public PageDto<AdminAccountDto.Account> list(Actor actor,AdminAccountDto.Query query) { AdminCatalogService.admin(actor);return repository.list(query); }
    public AdminAccountDto.Reviewed review(Actor actor,String id,AdminAccountDto.Review input,String key) { AdminCatalogService.admin(actor);return retries.call(() -> transactions.review(actor,id,input,key)); }
}
