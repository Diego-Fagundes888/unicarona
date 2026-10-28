package com.unicarona.subscription.service;

import com.unicarona.security.Actor;
import com.unicarona.shared.*;
import com.unicarona.shared.dto.PageDto;
import com.unicarona.subscription.dto.*;
import com.unicarona.subscription.repository.RoutineReadRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RoutineService {
    private final RoutineTransactions transactions;
    private final RoutineReadRepository reads;
    private final TransactionRetries retries;
    public RoutineService(RoutineTransactions transactions,RoutineReadRepository reads,TransactionRetries retries) { this.transactions=transactions;this.reads=reads;this.retries=retries; }
    public SubscriptionDto.Created create(Actor actor,SubscriptionDto.RoutineInput input,String key) { return retries.call(() -> transactions.create(actor,input,key)); }
    public SubscriptionDto.State availability(Actor actor,String id,boolean enabled) { return retries.call(() -> transactions.availability(actor,id,enabled)); }
    public SubscriptionDto.Preview preview(Actor actor,SubscriptionDto.MonthlyInput input) { return retries.call(() -> transactions.preview(actor,input)); }
    @Transactional(readOnly=true) public SubscriptionDto.Routine detail(Actor actor,String id) { return reads.detail(actor.id(),UUID.fromString(id)); }
    @Transactional(readOnly=true) public PageDto<SubscriptionDto.Routine> mine(Actor actor,Pagination page) { return PageDto.from(reads.mine(actor.id(),page.limit()+1,page.offset()),page.limit(),page.offset()); }
    @Transactional(readOnly=true) public PageDto<SubscriptionDto.Routine> search(Actor actor,RoutineQueries.Search query) { return PageDto.from(reads.search(actor.id(),query),query.page().limit(),query.page().offset()); }
}
