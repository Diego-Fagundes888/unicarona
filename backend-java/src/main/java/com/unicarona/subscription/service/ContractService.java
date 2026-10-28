package com.unicarona.subscription.service;

import com.unicarona.security.Actor;
import com.unicarona.shared.*;
import com.unicarona.shared.dto.PageDto;
import com.unicarona.subscription.dto.ContractDto;
import com.unicarona.subscription.repository.*;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ContractService {
    private final ContractReadRepository reads;
    private final WaitlistRepository waitlist;
    private final ContractTransactions transactions;
    private final TransactionRetries retries;
    public ContractService(ContractReadRepository reads,WaitlistRepository waitlist,ContractTransactions transactions,TransactionRetries retries) { this.reads=reads;this.waitlist=waitlist;this.transactions=transactions;this.retries=retries; }
    @Transactional(readOnly=true) public PageDto<ContractDto.Item> list(Actor actor,String side,int limit,int offset) { return PageDto.from(reads.list(actor.id(),side,limit+1,offset,null),limit,offset); }
    @Transactional(readOnly=true) public ContractDto.Item detail(Actor actor,String id) {
        return reads.list(actor.id(),"passenger",2,0,UUID.fromString(id)).stream().findFirst().orElseThrow(() -> new ApiException("SUBSCRIPTION_NOT_FOUND","Mensalidade não encontrada.",404));
    }
    public ContractDto.Status cancelRenewal(Actor actor,String id,String key) { return retries.call(() -> transactions.cancelRenewal(actor,id,key)); }
    @Transactional(readOnly=true) public ContractDto.Rights rights(Actor actor,String id) { return new ContractDto.Rights(reads.openRights(actor.id(),UUID.fromString(id))); }
    public ContractDto.Status remove(Actor actor,String id,String reason,String key) { return retries.call(() -> transactions.remove(actor,id,reason,key)); }
    public ContractDto.Status wait(Actor actor,String routine,String key) { return retries.call(() -> transactions.wait(actor,routine,key)); }
    @Transactional(readOnly=true) public ContractDto.Interests interests(Actor actor) { return new ContractDto.Interests(waitlist.interests(actor.id())); }
    @Transactional public ContractDto.Status leave(Actor actor,String id) {
        waitlist.leave(actor.id(),UUID.fromString(id)).orElseThrow(() -> new ApiException("WAITLIST_NOT_FOUND","Interesse não encontrado.",404));return new ContractDto.Status(id,"cancelled");
    }
}
