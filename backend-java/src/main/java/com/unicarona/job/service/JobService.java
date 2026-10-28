package com.unicarona.job.service;

import com.unicarona.job.dto.JobDto;
import com.unicarona.job.repository.JobRepository;
import com.unicarona.security.Actor;
import com.unicarona.shared.TransactionRetries;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JobService {
    private final JobRepository jobs;
    private final JobTransactions transactions;
    private final TransactionRetries retries;
    public JobService(JobRepository jobs,JobTransactions transactions,TransactionRetries retries) { this.jobs=jobs;this.transactions=transactions;this.retries=retries; }
    @Transactional(readOnly=true) public JobDto.Page list(Actor actor,String status,int limit,int offset) {
        JobTransactions.requireAdmin(actor);var rows=jobs.list(status,limit+1,offset);
        return new JobDto.Page(jobs.summary(),java.util.List.copyOf(rows.subList(0,Math.min(rows.size(),limit))),rows.size()>limit?offset+limit:null);
    }
    public JobDto.Retried retry(Actor actor,String id,JobDto.Retry input,String key) { return retries.call(() -> transactions.retry(actor,id,input,key)); }
}
