package com.unicarona.occurrence.service;

import com.unicarona.occurrence.dto.SafetyDto;
import com.unicarona.occurrence.repository.SafetyReadRepository;
import com.unicarona.security.Actor;
import com.unicarona.shared.TransactionRetries;
import com.unicarona.shared.dto.PageDto;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SafetyService {
    private final SafetyTransactions mutations;
    private final SafetyReadRepository reads;
    private final TransactionRetries retries;
    public SafetyService(SafetyTransactions mutations,SafetyReadRepository reads,TransactionRetries retries) { this.mutations=mutations;this.reads=reads;this.retries=retries; }
    public SafetyDto.Status report(Actor actor,SafetyDto.Report input,String key) { return retries.call(() -> mutations.report(actor,input,key)); }
    public SafetyDto.Status block(Actor actor,SafetyDto.Block input,String key) { return report(actor,new SafetyDto.Report(input.otherId(),null,input.reason(),false,true),key); }
    public SafetyDto.Removed unblock(Actor actor,String id) { return retries.call(() -> mutations.unblock(actor,id)); }
    public SafetyDto.Reviewed review(Actor actor,String id,SafetyDto.Review input,String key) { return retries.call(() -> mutations.review(actor,id,input,key)); }
    @Transactional(readOnly=true) public PageDto<SafetyDto.Item> list(Actor actor,boolean admin,int limit,int offset) { if(admin) SafetyTransactions.admin(actor);return reads.list(actor.id(),admin,limit,offset); }
    @Transactional(readOnly=true) public PageDto<SafetyDto.PersonalBlock> blocks(Actor actor,int limit,int offset) { return reads.blocks(actor.id(),limit,offset); }
    @Transactional(readOnly=true) public SafetyDto.Detail detail(Actor actor,String id,int limit,int offset) { SafetyTransactions.admin(actor);return reads.detail(UUID.fromString(id),limit,offset); }
}
