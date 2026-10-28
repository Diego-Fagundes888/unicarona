package com.unicarona.admin.service;

import com.unicarona.admin.dto.*;
import com.unicarona.admin.repository.AdminCatalogRepository;
import com.unicarona.security.Actor;
import com.unicarona.shared.*;
import com.unicarona.shared.dto.PageDto;
import org.springframework.stereotype.Service;

@Service
public class AdminCatalogService {
    private final AdminCatalogRepository repository;private final AdminCatalogTransactions transactions;private final TransactionRetries retries;
    public AdminCatalogService(AdminCatalogRepository repository,AdminCatalogTransactions transactions,TransactionRetries retries) { this.repository=repository;this.transactions=transactions;this.retries=retries; }
    public static void admin(Actor actor) { ApiException.require("admin".equals(actor.role()),"FORBIDDEN","Acesso administrativo necessário.",403); }
    public PageDto<AdminCatalogDto.Pricing> pricing(Actor actor,AdminCatalogQueries.Pricing input) { admin(actor);repository.university(input.university(),false);return repository.pricing(input); }
    public PageDto<AdminCatalogDto.Calendar> calendar(Actor actor,AdminCatalogQueries.Calendar input) { admin(actor);ApiException.require(input.end().compareTo(input.start())>=0,"INVALID_DATE_RANGE","O fim do intervalo deve vir depois do início.",422);repository.university(input.university(),false);return repository.calendar(input); }
    public AdminCatalogDto.Pricing pricing(Actor actor,AdminCatalogDto.PricingInput input,String key) { admin(actor);return retries.call(() -> transactions.pricing(actor,input,key)); }
    public AdminCatalogDto.Calendar calendar(Actor actor,AdminCatalogDto.CalendarInput input,String key) { admin(actor);return retries.call(() -> transactions.calendar(actor,input,key)); }
    public AdminCatalogDto.Deactivated deactivate(Actor actor,String id,String reason,String key) { admin(actor);return retries.call(() -> transactions.deactivate(actor,id,reason,key)); }
}
