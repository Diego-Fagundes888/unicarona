package com.unicarona.student.service;

import com.unicarona.security.Actor;
import com.unicarona.shared.ApiException;
import com.unicarona.shared.TransactionRetries;
import com.unicarona.shared.dto.PageDto;
import com.unicarona.student.dto.IdentityDto;
import com.unicarona.student.repository.AcademicQueueRepository;
import com.unicarona.user.repository.ProfileRepository;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

@Service
public class IdentityService {
    private final IdentityTransactions transactions;
    private final ProfileRepository profiles;
    private final AcademicVerificationProvider provider;
    private final AcademicQueueRepository queue;
    private final TransactionRetries retries;
    public IdentityService(IdentityTransactions transactions,ProfileRepository profiles,AcademicVerificationProvider provider,AcademicQueueRepository queue,TransactionRetries retries) {
        this.transactions=transactions;this.profiles=profiles;this.provider=provider;this.queue=queue;this.retries=retries;
    }
    public IdentityDto.Settings settings(Actor actor,IdentityDto.Settings input) { return retries.call(() -> transactions.settings(actor,input)); }
    public IdentityDto.Verification requestVerification(Actor actor) {
        var profile=profiles.find(actor.id()); ApiException.require(profile.universityId()!=null,"PROFILE_UNAVAILABLE","Complete o perfil acadêmico.",422);
        // A future institutional adapter runs without a transaction/row locks held.
        var result=provider.verify(actor.id(),profile.universityId()); return retries.call(() -> transactions.applyVerification(actor,profile,result));
    }
    @PreAuthorize("hasRole('ADMIN')") public IdentityDto.Decision review(Actor actor,UUID user,IdentityDto.Review input) { return retries.call(() -> transactions.review(actor,user,input)); }
    @PreAuthorize("hasRole('ADMIN')") public PageDto<IdentityDto.QueueItem> queue(int limit,int offset) { return PageDto.from(queue.pending(limit+1,offset),limit,offset); }
}
