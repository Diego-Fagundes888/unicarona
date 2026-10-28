package com.unicarona.admin.service;

import com.unicarona.admin.dto.*;
import com.unicarona.admin.repository.AdminCatalogRepository;
import com.unicarona.ride.repository.RideRulesRepository;
import com.unicarona.security.Actor;
import com.unicarona.shared.*;
import com.unicarona.shared.repository.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminCatalogTransactions {
    private final AdminCatalogRepository repository;private final RideRulesRepository prices;private final IdempotencyExecutor keys;private final AuditRepository audit;
    public AdminCatalogTransactions(AdminCatalogRepository repository,RideRulesRepository prices,IdempotencyExecutor keys,AuditRepository audit) { this.repository=repository;this.prices=prices;this.keys=keys;this.audit=audit; }
    @Transactional public AdminCatalogDto.Pricing pricing(Actor actor,AdminCatalogDto.PricingInput input,String key) {
        return keys.execute(actor.id(),key,"admin:pricing",input,AdminCatalogDto.Pricing.class,() -> {
            UUID university=UUID.fromString(input.universityId());repository.university(university,true);var previous=prices.currentPricing(university);ApiException.require(previous.id().equals(UUID.fromString(input.previousVersionId())),"PRICING_VERSION_CONFLICT","Os parâmetros mudaram. Recarregue antes de salvar.",409);
            var result=repository.createPricing(actor.id(),input,previous.params().discountBps());audit.record(actor.id(),"pricing_version_created","pricing",result.id(),Map.of("universityId",input.universityId(),"previousVersionId",previous.id(),"reason",input.reason()));return result;
        });
    }
    @Transactional public AdminCatalogDto.Calendar calendar(Actor actor,AdminCatalogDto.CalendarInput input,String key) {
        return keys.execute(actor.id(),key,"admin:calendar:add",input.fingerprint(),AdminCatalogDto.Calendar.class,() -> {
            String zone=repository.university(UUID.fromString(input.universityId()),true);ApiException.require(input.date().compareTo(repository.today(zone))>=0,"DATE_PAST","Cadastre uma data de hoje em diante.",422);UUID classId=input instanceof AdminCatalogDto.ClassExclusion c?repository.classId(c):null;UUID id=repository.createCalendar(actor.id(),input,classId);
            var meta=new LinkedHashMap<String,Object>();meta.put("universityId",input.universityId());meta.put("date",input.date());meta.put("classId",classId);meta.put("title",input.title());audit.record(actor.id(),"calendar_exclusion_created","calendar",id,meta);return repository.calendar(id);
        });
    }
    @Transactional public AdminCatalogDto.Deactivated deactivate(Actor actor,String id,String reason,String key) {
        var body=new LinkedHashMap<String,Object>();body.put("id",id);body.put("reason",reason);return keys.execute(actor.id(),key,"admin:calendar:deactivate",body,AdminCatalogDto.Deactivated.class,() -> {
            UUID event=UUID.fromString(id);repository.university(repository.calendarUniversity(event),true);repository.deactivate(event,actor.id());audit.record(actor.id(),"calendar_exclusion_deactivated","calendar",event,Map.of("reason",reason));return new AdminCatalogDto.Deactivated(id,false);
        });
    }
}
