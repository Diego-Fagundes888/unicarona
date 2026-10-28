package com.unicarona.job.service;

import com.unicarona.job.dto.JobDto;
import com.unicarona.job.repository.JobRepository;
import com.unicarona.security.Actor;
import com.unicarona.shared.ApiException;
import com.unicarona.shared.IdempotencyExecutor;
import com.unicarona.shared.repository.AuditRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JobTransactions {
    private final JobRepository jobs;
    private final IdempotencyExecutor idempotency;
    private final AuditRepository audit;
    public JobTransactions(JobRepository jobs,IdempotencyExecutor idempotency,AuditRepository audit) { this.jobs=jobs;this.idempotency=idempotency;this.audit=audit; }
    @Transactional public JobDto.Retried retry(Actor actor,String rawId,JobDto.Retry input,String key) {
        requireAdmin(actor);
        var fingerprint=new LinkedHashMap<String,Object>();fingerprint.put("id",rawId);fingerprint.put("expectedAttempts",input.expectedAttempts());fingerprint.put("reason",input.reason());
        return idempotency.execute(actor.id(),key,"admin:job:retry",fingerprint,JobDto.Retried.class,() -> {
            var job=jobs.lock(UUID.fromString(rawId)).orElseThrow(() -> new ApiException("JOB_NOT_FOUND","Tarefa não encontrada.",404));
            ApiException.require("dead".equals(job.status()) && job.attempts()==input.expectedAttempts(),"JOB_STATE_CONFLICT","A tarefa mudou. Recarregue antes de retomar o envio.",409);
            ApiException.require(Set.of("email","transfer","mercadopago_pix","mercadopago_card","mercadopago_debt_pix").contains(job.type()),"JOB_TYPE_UNSUPPORTED","Esta tarefa exige revisão técnica antes de retomar.",409);
            jobs.retry(job.id());
            audit.record(actor.id(),"job_retry_requested","outbox_job",job.id(),Map.of("reason",input.reason(),"previousAttempts",job.attempts(),"type",job.type()));
            return new JobDto.Retried(rawId,"pending",job.attempts());
        });
    }
    public static void requireAdmin(Actor actor) { ApiException.require("admin".equals(actor.role()),"FORBIDDEN","Acesso administrativo necessário.",403); }
}
