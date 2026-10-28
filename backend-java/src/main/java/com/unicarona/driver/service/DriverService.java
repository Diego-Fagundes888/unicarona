package com.unicarona.driver.service;

import com.unicarona.driver.dto.DriverDto;
import com.unicarona.driver.repository.DriverRepository;
import com.unicarona.security.Actor;
import com.unicarona.shared.TransactionRetries;
import com.unicarona.shared.dto.PageDto;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

@Service
public class DriverService {
    private final DriverTransactions transactions;
    private final DriverRepository drivers;
    private final TransactionRetries retries;
    public DriverService(DriverTransactions transactions,DriverRepository drivers,TransactionRetries retries) { this.transactions=transactions;this.drivers=drivers;this.retries=retries; }
    public DriverDto.Status apply(Actor actor,DriverDto.Apply input,String key) { return retries.call(() -> transactions.apply(actor,input,key)); }
    public PageDto<DriverDto.Application> applications(Actor actor,int limit,int offset,String status) {
        return PageDto.from(drivers.applications(actor.id(),"admin".equals(actor.role()),limit+1,offset,status),limit,offset);
    }
    @PreAuthorize("hasRole('ADMIN')") public DriverDto.Status review(Actor actor,String id,DriverDto.Review input,String key) { return retries.call(() -> transactions.review(actor,id,input,key)); }
}
