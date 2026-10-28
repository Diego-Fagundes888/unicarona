package com.unicarona.reservation.service;

import com.unicarona.reservation.dto.*;
import com.unicarona.reservation.repository.RequestReadRepository;
import com.unicarona.security.Actor;
import com.unicarona.shared.TransactionRetries;
import com.unicarona.shared.dto.PageDto;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RequestService {
    private final RequestTransactions transactions;
    private final RequestReadRepository reads;
    private final TransactionRetries retries;
    public RequestService(RequestTransactions transactions,RequestReadRepository reads,TransactionRetries retries) { this.transactions=transactions;this.reads=reads;this.retries=retries; }
    public RequestDto.Created create(Actor actor,RequestDto.Create input,String key) { return retries.call(() -> transactions.create(actor,input,key)); }
    public RequestDto.Responded respond(Actor actor,String id,boolean accept,String key) { return retries.call(() -> transactions.respond(actor,id,accept,key)); }
    public RequestDto.Cancelled cancel(Actor actor,String id,String key) { return retries.call(() -> transactions.cancel(actor,id,key)); }
    @Transactional(readOnly=true) public PageDto<RequestDto.Item> list(Actor actor,RequestQueries query) { return PageDto.from(reads.list(actor.id(),query),query.page().limit(),query.page().offset()); }
    @Transactional(readOnly=true) public RequestDto.Detail detail(Actor actor,String id) { return reads.detail(actor.id(),UUID.fromString(id)); }
}
