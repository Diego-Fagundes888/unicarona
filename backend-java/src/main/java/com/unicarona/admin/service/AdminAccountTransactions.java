package com.unicarona.admin.service;

import com.unicarona.admin.dto.AdminAccountDto;
import com.unicarona.admin.repository.AdminAccountRepository;
import com.unicarona.notification.service.NotificationService;
import com.unicarona.occurrence.entity.Occurrence;
import com.unicarona.occurrence.repository.OccurrenceRepository;
import com.unicarona.payment.repository.ParcelRepository;
import com.unicarona.payment.service.ParcelService;
import com.unicarona.security.Actor;
import com.unicarona.shared.*;
import com.unicarona.shared.repository.*;
import com.unicarona.subscription.repository.SubscriptionRepository;
import com.unicarona.subscription.service.FutureContractClosure;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminAccountTransactions {
    private final AdminAccountRepository reads;private final ActorsRepository users;private final SubscriptionRepository contracts;private final FutureContractClosure closure;private final ParcelService money;private final ParcelRepository parcels;private final OccurrenceRepository cases;private final NotificationService notices;private final AuditRepository audit;private final IdempotencyExecutor keys;
    public AdminAccountTransactions(AdminAccountRepository reads,ActorsRepository users,SubscriptionRepository contracts,FutureContractClosure closure,ParcelService money,ParcelRepository parcels,OccurrenceRepository cases,NotificationService notices,AuditRepository audit,IdempotencyExecutor keys) { this.reads=reads;this.users=users;this.contracts=contracts;this.closure=closure;this.money=money;this.parcels=parcels;this.cases=cases;this.notices=notices;this.audit=audit;this.keys=keys; }
    @Transactional public AdminAccountDto.Reviewed review(Actor actor,String id,AdminAccountDto.Review input,String key) {
        var body=new LinkedHashMap<String,Object>();body.put("id",id);body.put("status",input.status());body.put("expectedStatus",input.expectedStatus());body.put("reason",input.reason());return keys.execute(actor.id(),key,"admin:account:review",body,AdminAccountDto.Reviewed.class,() -> {
            UUID targetId=UUID.fromString(id);var ids=new ArrayList<UUID>();ids.add(targetId);reads.participants(targetId).forEach(p -> { ids.add(p.driver());ids.add(p.passenger()); });var people=users.lockUsers(ids);var target=people.stream().filter(p -> p.id().equals(targetId)).findFirst().orElseThrow();
            ApiException.require("user".equals(target.role()) && Set.of("active","suspended","closing","deactivated").contains(target.accountStatus()),"ACCOUNT_UNAVAILABLE","Selecione uma conta de estudante.",404);ApiException.require(target.accountStatus().equals(input.expectedStatus()),"ACCOUNT_VERSION_CONFLICT","A situação da conta mudou. Recarregue antes de decidir.",409);ApiException.require(!target.accountStatus().equals(input.status()),"ACCOUNT_STATE","A conta já está nessa situação.",409);
            ApiException.require(!"active".equals(input.status())||!"deactivated".equals(target.accountStatus()),"ACCOUNT_SELF_REACTIVATION_REQUIRED","A desativação temporária deve ser revertida pelo próprio titular.",409);
            ApiException.require(!"active".equals(input.status())||!reads.closurePending(targetId),"CLOSURE_PENDING","O titular precisa cancelar o encerramento antes da restauração de acesso.",409);
            ApiException.require(!"active".equals(input.status())||reads.adult(targetId),"UNDERAGE_ACCOUNT_RESTRICTED","O serviço exige 18 anos completos. Trate a correção e os direitos do titular sem restaurar novas combinações para menor de idade.",409);
            var locked=people.stream().map(ActorsRepository.Person::id).collect(Collectors.toSet());if(reads.participants(targetId).stream().anyMatch(p -> !locked.contains(p.driver()) || !locked.contains(p.passenger()))) throw new ConcurrencyFailureException("Account participants changed before locking",new SQLException("Retry with current participants","40001"));
            String resultingStatus="active".equals(input.status())&&reads.voluntarilyDeactivated(targetId)?"deactivated":input.status();
            reads.status(targetId,resultingStatus);var affected=new LinkedHashSet<UUID>();UUID caseId=null;
            if("suspended".equals(input.status())) {
                var contractIds=reads.contracts(targetId);reads.lockPlanned(targetId);for(UUID contract:contractIds) affected.addAll(closure.close(contracts.findById(contract).orElseThrow(),actor.id(),"Suspensão preventiva: parcelas futuras em análise separada.",FutureContractClosure.Outcome.BLOCKED));
                var pending=reads.pending(targetId);var pendingMoney=reads.parcels(pending).stream().collect(Collectors.toMap(AdminAccountRepository.Money::reservation,p -> p));
                for(var reservation:pending) { var parcel=pendingMoney.get(reservation.id());if("future".equals(parcel.status())) { money.block(parcel.id(),"Suspensão preventiva: trecho não iniciado em análise.");affected.add(parcel.id()); }reads.reviewReservation(reservation.id());notices.notify(reservation.passenger(),"Trecho em análise","A combinação está indisponível. A parcela permanece em análise financeira separada.","reservation",reservation.id()); }
                parcels.flush();reads.cancelOffers(targetId);for(var request:reads.cancelUnpaid(targetId)) for(UUID user:List.of(request.driver(),request.passenger())) notices.notify(user,"Solicitação encerrada","Esta combinação está indisponível. Nenhuma nova reserva foi confirmada.","requests",request.id());
                if(!affected.isEmpty()) { var report=cases.saveAndFlush(Occurrence.financialReview(actor.id(),targetId,"Suspensão preventiva: análise financeira dos trechos não iniciados."));caseId=report.getId();for(UUID parcel:affected) cases.link(caseId,parcel); }
            }
            var meta=new LinkedHashMap<String,Object>();meta.put("previousStatus",input.expectedStatus());meta.put("status",resultingStatus);meta.put("reason",input.reason());meta.put("financialCaseId",caseId);meta.put("affectedParcels",affected.size());audit.record(actor.id(),"account_reviewed","user",targetId,meta);notices.notify(targetId,"suspended".equals(resultingStatus)?"Conta em revisão":"deactivated".equals(resultingStatus)?"Restrição revista; conta continua desativada por você":"Acesso restaurado",input.reason(),"safety",targetId);return new AdminAccountDto.Reviewed(id,resultingStatus,affected.size(),caseId);
        });
    }
    /** Narrow internal capability: a password-confirmed self-correction can restrict only its own service eligibility. */
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public AdminAccountDto.Reviewed restrictForAgeCorrection(Actor actor,String expectedStatus,java.time.LocalDate proposed,String key){
        ApiException.require("user".equals(actor.role())&&!com.unicarona.auth.service.AuthService.isAdult(proposed,java.time.LocalDate.now(java.time.ZoneId.of("America/Sao_Paulo"))),"FORBIDDEN","Operação de elegibilidade indisponível.",403);
        return review(actor,actor.id().toString(),new AdminAccountDto.Review("suspended",expectedStatus,"Correção de elegibilidade: o serviço exige 18 anos completos. Valores e trechos não iniciados seguem análise separada."),key);
    }
}
