package com.unicarona.ride.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.unicarona.payment.service.PricingCalculator;
import com.unicarona.ride.dto.RideDto;
import com.unicarona.ride.entity.*;
import com.unicarona.ride.repository.*;
import com.unicarona.security.Actor;
import com.unicarona.privacy.service.LegalDocumentService;
import com.unicarona.shared.*;
import com.unicarona.shared.repository.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RideTransactions {
    private final ActorsRepository actors;
    private final LegalDocumentService legal;
    private final RideRepository rides;
    private final RideRouteRepository routes;
    private final RideRulesRepository rules;
    private final PricingCalculator pricing;
    private final IdempotencyExecutor idempotency;
    private final AuditRepository audit;
    public RideTransactions(ActorsRepository actors,RideRepository rides,RideRouteRepository routes,RideRulesRepository rules,PricingCalculator pricing,IdempotencyExecutor idempotency,AuditRepository audit,LegalDocumentService legal) {
        this.actors=actors;this.legal=legal;this.rides=rides;this.routes=routes;this.rules=rules;this.pricing=pricing;this.idempotency=idempotency;this.audit=audit;
    }
    @Transactional public RideDto.Created create(Actor actor,RideDto.Create input,String key) {
        return idempotency.execute(actor.id(),key,"ride:create",input,RideDto.Created.class,() -> {
            var person=actors.lockUser(actor.id());ActorsRepository.driver(person);legal.requireCurrent(actor.id(),true);
            var vehicle=rules.currentVehicle(actor.id()).orElseThrow(() -> new ApiException("VEHICLE_NOT_APPROVED","Cadastre um veículo aprovado.",403));
            ApiException.require(input.capacity()<=vehicle.capacity(),"INVALID_CAPACITY","As vagas excedem a capacidade do veículo.",422);
            var moment=rules.moment(person.universityId(),input.date(),input.time()).orElse(null);
            ApiException.require(moment!=null && moment.future(),"DATE_PAST","Escolha uma data e um horário futuros.",422);
            ApiException.require(!input.region().toLowerCase(Locale.forLanguageTag("pt-BR")).equals(moment.name().toLowerCase(Locale.forLanguageTag("pt-BR"))),"INVALID_ROUTE","Informe um local diferente da faculdade.",422);
            var p=rules.currentPricing(person.universityId());int distance=distance(input.distance()),toll=toll(input.toll());
            int price=Math.toIntExact(pricing.quote(distance,toll,input.capacity(),p.params()));
            var route=routes.saveAndFlush(new RideRoute(person.universityId(),actor.id(),distance,input));
            var ride=rides.saveAndFlush(new Ride(person.universityId(),route.getId(),actor.id(),vehicle.id(),input,p.id(),price,p.params().commissionBps(),toll));
            audit.record(actor.id(),"ride_published","ride",ride.getId(),Map.of("priceCents",price));return new RideDto.Created(ride.getId(),price);
        });
    }
    @Transactional public RideDto.Edited edit(Actor actor,String id,RideDto.Edit input,String key) {
        var hash=new LinkedHashMap<String,Object>();hash.put("id",id);
        // Flatten normalized DTO fields in the same order as the existing API; omit absent optionals.
        hash.put("capacity",input.capacity());hash.put("distance",new Numeric(input.distance()));if(input.toll()!=null) hash.put("toll",new Toll(input.toll()));
        hash.put("automatic",input.automatic());if(input.notes()!=null) hash.put("notes",input.notes());if(input.version()!=null) hash.put("version",new Numeric(input.version()));
        return idempotency.execute(actor.id(),key,"ride:edit",hash,RideDto.Edited.class,() -> {
            ActorsRepository.driver(actors.lockUser(actor.id()));legal.requireCurrent(actor.id(),true);
            var ride=rides.lockOwn(UUID.fromString(id),actor.id()).orElseThrow(() -> new ApiException("RIDE_NOT_FOUND","Carona não encontrada.",404));
            ApiException.require("planned".equals(ride.getStatus()),"INVALID_STATE","Edite somente ofertas que ainda não começaram.",409);
            ApiException.require(rules.detailsCurrent(ride.getId()),"RIDE_DETAILS_EXPIRED","Os detalhes desta oferta antiga venceram. Publique uma nova carona.",409);
            ApiException.require(input.version()==null || input.version()==ride.getVersion(),"VERSION_CONFLICT","A oferta foi alterada. Atualize a tela.",409);
            var vehicle=rules.approvedVehicle(ride.getVehicleId());
            ApiException.require(vehicle.isPresent() && input.capacity()<=vehicle.get().capacity(),"INVALID_CAPACITY","Vagas excedem o veículo.",422);
            ApiException.require(input.capacity()>=ride.getCapacity()-rules.seats(ride.getId(),null),"RIDE_FULL","Não é possível retirar vagas confirmadas.",409);
            var p=rules.currentPricing(ride.getUniversityId());int distance=distance(input.distance()),toll=toll(input.toll()),price=Math.toIntExact(pricing.quote(distance,toll,input.capacity(),p.params()));
            var oldRoute=routes.findById(ride.getRouteId()).orElseThrow(() -> new IllegalStateException("Ride route missing."));
            var route=routes.saveAndFlush(oldRoute.copyForDistance(distance));ride.edit(route.getId(),input,p.id(),price,p.params().commissionBps(),toll);rides.flush();
            audit.record(actor.id(),"ride_edited","ride",ride.getId(),Map.of("priceCents",price,"reservedPricesPreserved",true));return new RideDto.Edited(id,price,ride.getVersion());
        });
    }
    private int distance(double value) { return Math.toIntExact(Math.round(value*1000)); }
    private int toll(JsonNode value) { return Math.toIntExact(pricing.inputCents(value==null?"0":value.isTextual()?value.textValue():JavaScriptNumbers.format(value.doubleValue()))); }
    public record Numeric(@com.fasterxml.jackson.annotation.JsonValue @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=JavaScriptNumbers.DoubleSerializer.class) Double value) {}
    public record Toll(@com.fasterxml.jackson.annotation.JsonValue @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=JavaScriptNumbers.ScalarSerializer.class) JsonNode value) {}
}
