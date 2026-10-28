package com.unicarona.reservation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unicarona.notification.service.NotificationService;
import com.unicarona.payment.repository.PaymentRepository;
import com.unicarona.payment.service.AcceptedPayment;
import com.unicarona.reservation.config.ReservationSettings;
import com.unicarona.reservation.dto.RequestDto;
import com.unicarona.reservation.entity.*;
import com.unicarona.reservation.repository.*;
import com.unicarona.security.Actor;
import com.unicarona.privacy.service.LegalDocumentService;
import com.unicarona.shared.*;
import com.unicarona.shared.repository.*;
import com.unicarona.subscription.dto.SubscriptionDto;
import com.unicarona.subscription.service.MonthlyPreview;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RequestTransactions {
    private final ActorsRepository actors;
    private final LegalDocumentService legal;
    private final RequestRulesRepository rules;
    private final RideRequestRepository requests;
    private final RequestLineRepository requestLines;
    private final PaymentRepository payments;
    private final AcceptedPayment acceptedPayment;
    private final RequestLocks locks;
    private final MonthlyPreview monthly;
    private final IdempotencyExecutor idempotency;
    private final ReservationSettings settings;
    private final NotificationService notifications;
    private final AuditRepository audit;
    private final ObjectMapper json;
    public RequestTransactions(ActorsRepository actors,RequestRulesRepository rules,RideRequestRepository requests,RequestLineRepository requestLines,PaymentRepository payments,AcceptedPayment acceptedPayment,RequestLocks locks,MonthlyPreview monthly,IdempotencyExecutor idempotency,ReservationSettings settings,NotificationService notifications,AuditRepository audit,ObjectMapper json,LegalDocumentService legal) {
        this.actors=actors;this.legal=legal;this.rules=rules;this.requests=requests;this.requestLines=requestLines;this.payments=payments;this.acceptedPayment=acceptedPayment;this.locks=locks;this.monthly=monthly;this.idempotency=idempotency;this.settings=settings;this.notifications=notifications;this.audit=audit;this.json=json;
    }
    private record Frozen(UUID ride,int cents,int commissionBps) {}
    @Transactional public RequestDto.Created create(Actor actor,RequestDto.Create input,String key) {
        return idempotency.execute(actor.id(),key,"request:create",input,RequestDto.Created.class,() -> {
            UUID owner,rideId=null,routineId=null,previous=null;boolean automatic;SubscriptionDto.Preview preview=null;List<Frozen> lines;
            if(input instanceof RequestDto.Avulsa avulsa) {
                UUID id=UUID.fromString(avulsa.rideId());owner=rules.rideDriver(id);
                var persons=actors.lockUsers(List.of(actor.id(),owner));persons.forEach(ActorsRepository::active);
                UUID driver=owner;var driverPerson=persons.stream().filter(p -> p.id().equals(driver)).findFirst().orElseThrow();var passenger=persons.stream().filter(p -> p.id().equals(actor.id())).findFirst().orElseThrow();ActorsRepository.driver(driverPerson);
                ApiException.require(!actor.id().equals(owner) && Objects.equals(passenger.universityId(),driverPerson.universityId()),"RIDE_UNAVAILABLE","Carona indisponível.",409);actors.notBlocked(actor.id(),owner);
                var ride=rules.lockRide(id);ApiException.require(rules.linesAvailable(List.of(ride),actor.id(),null),"RIDE_FULL","Esta carona não tem vaga disponível.",409);
                owner=ride.driver();automatic=ride.automatic();rideId=ride.id();lines=List.of(new Frozen(ride.id(),ride.priceCents(),ride.commissionBps()));
            } else if(input instanceof RequestDto.Mensal mensal) {
                preview=monthly.calculate(actor.id(),mensal.monthly(),true);
                ApiException.require(!preview.lines().isEmpty() && preview.conflicts().isEmpty(),"MONTHLY_CONFLICT","Nem todos os trechos do mensal estão disponíveis.",409);
                owner=preview.driverId();routineId=UUID.fromString(mensal.routineId());previous=mensal.previousContractId()==null?null:UUID.fromString(mensal.previousContractId());automatic=false;
                lines=preview.lines().stream().map(l -> new Frozen(l.rideId(),Math.toIntExact(l.cents()),Math.toIntExact(Math.round(l.commission()*10000)))).toList();
            } else throw new IllegalStateException("Unknown request input.");
            legal.requireCurrent(actor.id(),false);legal.requireCounterparty(owner,true);
            var request=requests.saveAndFlush(new RideRequest(actor.id(),owner,rideId,routineId,previous,input.type(),automatic,preview==null?null:json.valueToTree(preview),rules.requestExpiry(settings.requestHours())));
            requestLines.saveAllAndFlush(lines.stream().map(l -> new RequestLine(request.getId(),l.ride(),l.cents(),l.commissionBps())).toList());
            UUID payment=automatic?acceptedPayment.create(request):null;
            notifications.notify(owner,"Nova solicitação",automatic?"Solicitação aceita automaticamente; aguardando pagamento.":"Confira a solicitação recebida.","requests",request.getId());
            audit.record(actor.id(),"request_created","request",request.getId(),Map.of("type",input.type()));return new RequestDto.Created(request.getId(),request.getStatus(),payment);
        });
    }
    @Transactional public RequestDto.Responded respond(Actor actor,String id,boolean accept,String key) {
        var body=new LinkedHashMap<String,Object>();body.put("id",id);body.put("accept",accept);
        return idempotency.execute(actor.id(),key,"request:respond",body,RequestDto.Responded.class,() -> {
            var locked=locks.lock(UUID.fromString(id),actor.id(),RequestRulesRepository.Side.DRIVER);var request=locked.request();var persons=locked.persons();
            ActorsRepository.driver(persons.stream().filter(p -> p.id().equals(actor.id())).findFirst().orElseThrow());ActorsRepository.active(persons.stream().filter(p -> !p.id().equals(actor.id())).findFirst().orElseThrow());actors.notBlocked(request.getPassengerId(),request.getDriverId());
            ApiException.require("pending".equals(request.getStatus()) && rules.valid(request.getId()),"REQUEST_STATE","Solicitação expirada ou já respondida.",409);
            if(accept && "mensal".equals(request.getType())) ApiException.require(ActorsRepository.sameClass(persons.getFirst(),persons.getLast()) && persons.getFirst().classId().toString().equals(request.getPreview().path("classId").asText()),"CLASS_MISMATCH","A combinação acadêmica mudou. Revise o mensal.",422);
            if(accept) ApiException.require(rules.linesAvailable(rules.lockedLines(request.getId()).stream().map(RequestRulesRepository.LockedLine::ride).toList(),request.getPassengerId(),request.getPreviousSubscriptionId()),"RIDE_FULL","Nem todos os trechos têm vagas.",409);
            request.respond(accept);requests.flush();UUID payment=accept?acceptedPayment.create(request):null;
            notifications.notify(request.getPassengerId(),accept?"Solicitação aceita":"Solicitação recusada",accept?"Faça o pagamento para confirmar. A vaga será verificada novamente.":"Escolha outra oferta.","requests",request.getId());
            audit.record(actor.id(),accept?"request_accepted":"request_rejected","request",request.getId(),Map.of());return new RequestDto.Responded(id,request.getStatus(),payment);
        });
    }
    @Transactional public RequestDto.Cancelled cancel(Actor actor,String id,String key) {
        return idempotency.execute(actor.id(),key,"request:cancel",Map.of("id",id),RequestDto.Cancelled.class,() -> {
            var request=locks.lock(UUID.fromString(id),actor.id(),RequestRulesRepository.Side.PARTICIPANT).request();
            ApiException.require(Set.of("pending","accepted").contains(request.getStatus()),"REQUEST_STATE","Use o cancelamento da reserva confirmada.",409);
            request.cancel();payments.expireForRequest(request.getId());
            notifications.notify(actor.id().equals(request.getDriverId())?request.getPassengerId():request.getDriverId(),"Solicitação cancelada","O pedido foi encerrado.","requests",request.getId());audit.record(actor.id(),"request_cancelled","request",request.getId(),Map.of());return new RequestDto.Cancelled(id,"cancelled");
        });
    }
}
