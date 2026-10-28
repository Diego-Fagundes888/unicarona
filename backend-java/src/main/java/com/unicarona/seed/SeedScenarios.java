package com.unicarona.seed;

import com.unicarona.payment.dto.PaymentDto;
import com.unicarona.payment.provider.WebhookAuthentication;
import com.unicarona.payment.service.PaymentService;
import com.unicarona.reservation.dto.RequestDto;
import com.unicarona.reservation.service.RequestService;
import com.unicarona.ride.dto.RideDto;
import com.unicarona.ride.service.RideService;
import com.unicarona.security.Actor;
import com.unicarona.subscription.dto.SubscriptionDto;
import com.unicarona.subscription.service.RoutineService;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Synthetic paid scenarios go through the same services and signed receipts as the API. */
@Service
public class SeedScenarios {
    private final SeedSettings settings;private final SeedService seed;private final JdbcTemplate sql;private final RideService rides;private final RoutineService routines;private final RequestService requests;private final PaymentService payments;private final WebhookAuthentication signing;
    public SeedScenarios(SeedSettings settings,SeedService seed,JdbcTemplate sql,RideService rides,RoutineService routines,RequestService requests,PaymentService payments,WebhookAuthentication signing) { this.settings=settings;this.seed=seed;this.sql=sql;this.rides=rides;this.routines=routines;this.requests=requests;this.payments=payments;this.signing=signing; }
    @Transactional(propagation=Propagation.NEVER) public void run() {
        settings.scenariosAllowed();seed.run();var driver=actor("carolina@example.com");var ana=actor("ana@example.com");var lucas=actor("lucas@example.com");
        String tomorrow=sql.queryForObject("SELECT ((now() AT TIME ZONE un.time_zone)::date+1)::text FROM student_profiles s JOIN universities un ON un.id=s.university_id WHERE s.user_id=?",String.class,driver.id());
        var offer=rides.create(driver,new RideDto.Create("Lago Azul","Praça de encontro — exemplo sintético","ida",tomorrow,"18:30",3,20d,null,false,"Dados sintéticos de desenvolvimento; esta oferta não representa uma viagem real."),"seed-offer-"+tomorrow);
        requests.create(ana,new RequestDto.Avulsa("avulsa",offer.id().toString()),"seed-avulsa-ana-"+tomorrow);var avulsa=requests.create(lucas,new RequestDto.Avulsa("avulsa",offer.id().toString()),"seed-avulsa-lucas-"+tomorrow);acceptAndPay(driver,lucas,avulsa.id());
        var days=List.of(1,2,3,4,5);var routine=routines.create(driver,new SubscriptionDto.RoutineInput("Lago Azul","Praça de encontro — exemplo sintético","ambos","08:00","17:00",days,3,20d,null,false,"Rotina sintética para desenvolvimento; não representa uma oferta real."),"seed-routine-lagoazul-v1");
        LocalDate start=LocalDate.parse(tomorrow);while(Set.of(DayOfWeek.SATURDAY,DayOfWeek.SUNDAY).contains(start.getDayOfWeek())) start=start.plusDays(1);String month=start.toString().substring(0,7);
        var existing=sql.query("SELECT id FROM ride_requests WHERE routine_id=? AND passenger_id=? AND type='mensal' AND preview->>'startDate' LIKE ? ORDER BY created_at,id LIMIT 1",(r,n) -> r.getObject(1,UUID.class),routine.id(),ana.id(),month+"%");
        UUID monthly=existing.isEmpty()?requests.create(ana,new RequestDto.Mensal("mensal",routine.id().toString(),start.toString(),"ambos",days,null),"seed-monthly-ana-"+month).id():existing.getFirst();acceptAndPay(driver,ana,monthly);
    }
    private Actor actor(String email) {
        return sql.queryForObject("SELECT id,role,account_status,email_verified_at FROM users WHERE email=?",(r,n) -> new Actor(r.getObject("id",UUID.class),r.getString("role"),r.getString("account_status"),UUID.randomUUID(),UUID.randomUUID(),"",r.getTimestamp("email_verified_at")!=null,Set.of("ROLE_PASSENGER")),email);
    }
    private void acceptAndPay(Actor driver,Actor passenger,UUID id) {
        String state=sql.queryForObject("SELECT status FROM ride_requests WHERE id=?",String.class,id);if(!Set.of("pending","accepted").contains(state)) return;
        if("pending".equals(state)) requests.respond(driver,id.toString(),true,"seed-accept-"+id);
        var payment=sql.queryForMap("SELECT id,status FROM payments WHERE request_id=?",id);if(!Set.of("awaiting","pending").contains(payment.get("status"))) return;String paymentId=payment.get("id").toString();
        var checkout=payments.checkout(passenger,paymentId,new PaymentDto.CheckoutInput("pix",false,payments.summary(passenger,paymentId).tripReview()),"seed-checkout-"+paymentId);if(checkout.confirmed()) return;
        var event=new PaymentDto.Event("seed_payment_"+paymentId,paymentId,checkout.checkout().reference(),checkout.externalCents(),"approved");String timestamp=Long.toString(Instant.now().getEpochSecond());payments.receive(event,timestamp,signing.signature("",timestamp,event));
    }
}
