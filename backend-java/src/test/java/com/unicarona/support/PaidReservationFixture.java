package com.unicarona.support;

import com.unicarona.security.CryptoService;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Historical synthetic paid contract, constructed under all deferred money guards. */
public final class PaidReservationFixture {
    private PaidReservationFixture(){}
    public record Paid(UUID id,UUID ride,UUID request,UUID payment,UUID parcel,UUID passenger,UUID driver,UUID university,String pin,String cipher,String hash){
        @Override public String toString(){return "SyntheticPaidReservation[credentials redacted]";}
    }
    public static Paid create(JdbcTemplate sql,int dayOffset,String zone){
        return new TransactionTemplate(new DataSourceTransactionManager(sql.getDataSource())).execute(ignored->{
            UUID passenger=user(sql),driver=user(sql);sql.update("INSERT INTO driver_profiles(user_id,status,approved_at) VALUES(?,'approved',now())",driver);
            UUID university=sql.queryForObject("INSERT INTO universities(slug,name,city,state_code,time_zone) VALUES(?,'Synthetic PIN University','Gama','DF',?) RETURNING id",UUID.class,"pin-"+UUID.randomUUID(),zone);
            LocalDate day=sql.queryForObject("SELECT (clock_timestamp() AT TIME ZONE ?)::date+?",LocalDate.class,zone,dayOffset);
            Timestamp created=sql.queryForObject("SELECT (?::date::timestamp AT TIME ZONE ?)-interval '1 day'",Timestamp.class,day,zone);
            UUID vehicle=sql.queryForObject("INSERT INTO vehicles(owner_id,model,color,plate,capacity,status) VALUES(?,'Synthetic','Black','ABC1D23',4,'approved') RETURNING id",UUID.class,driver);
            UUID route=sql.queryForObject("INSERT INTO ride_routes(university_id,driver_id,region,meeting,distance_m) VALUES(?,?,'Synthetic region','Synthetic meeting',10000) RETURNING id",UUID.class,university,driver);
            UUID pricing=sql.queryForObject("INSERT INTO pricing_versions(university_id,fuel_cents,efficiency,wear_cents,reference_passengers,commission_bps,discount_bps) VALUES(?,600,10,10,3,1000,0) RETURNING id",UUID.class,university);
            UUID ride=sql.queryForObject("INSERT INTO rides(university_id,route_id,driver_id,vehicle_id,direction,ride_date,departure_time,capacity,price_cents,commission_bps,pricing_version_id,created_at) VALUES(?,?,?,?,'ida',?,'18:30',4,500,1000,?,?) RETURNING id",UUID.class,university,route,driver,vehicle,day,pricing,created);
            UUID request=sql.queryForObject("INSERT INTO ride_requests(passenger_id,driver_id,ride_id,type,status,expires_at,created_at) VALUES(?,?,?,'avulsa','accepted',?::timestamptz+interval '6 hours',?) RETURNING id",UUID.class,passenger,driver,ride,created,created);
            sql.update("INSERT INTO request_lines(request_id,ride_id,cents,commission_bps) VALUES(?,?,500,1000)",request,ride);
            UUID payment=sql.queryForObject("INSERT INTO payments(request_id,passenger_id,amount_cents,provider,status,expires_at,created_at) VALUES(?,?,500,'development','awaiting',?::timestamptz+interval '6 hours',?) RETURNING id",UUID.class,request,passenger,created,created);
            sql.update("UPDATE payments SET status='pending',external_cents=500 WHERE id=?",payment);sql.update("UPDATE ride_requests SET status='confirmed' WHERE id=?",request);
            var crypto=new CryptoService();String pin="7316",cipher=crypto.encrypt(pin,AuthTestSettings.KEY),hash=crypto.hmac(pin,AuthTestSettings.PEPPER);
            UUID reservation=sql.queryForObject("INSERT INTO ride_reservations(request_id,ride_id,passenger_id,driver_id,payment_id,status,pin_ciphertext,pin_hash,price_cents,created_at) VALUES(?,?,?,?,?,'confirmed',?,?,500,?) RETURNING id",UUID.class,request,ride,passenger,driver,payment,cipher,hash,created);
            UUID parcel=sql.queryForObject("INSERT INTO parcels(reservation_id,payment_id,passenger_id,driver_id,cents,commission_bps,status,created_at) VALUES(?,?,?,?,500,1000,'future',?) RETURNING id",UUID.class,reservation,payment,passenger,driver,created);
            UUID clearing=sql.queryForObject("INSERT INTO ledger_accounts(kind) VALUES('provider_clearing') ON CONFLICT(owner_id,kind,currency) DO UPDATE SET kind=excluded.kind RETURNING id",UUID.class);
            UUID future=sql.queryForObject("INSERT INTO ledger_accounts(owner_id,kind) VALUES(?,'future') RETURNING id",UUID.class,passenger);
            UUID receipt=sql.queryForObject("INSERT INTO ledger_transactions(event_key,kind,payment_id,reason,body_hash,created_at) VALUES(?,'receipt',?,'Synthetic historical payment',?,?) RETURNING id",UUID.class,"payment:"+payment+":confirmed",payment,"a".repeat(64),created);
            sql.update("INSERT INTO ledger_entries(transaction_id,account_id,cents,created_at) VALUES(?,?,-500,?),(?,?,500,?)",receipt,clearing,created,receipt,future,created);
            sql.update("UPDATE payments SET status='approved',paid_at=? WHERE id=?",created,payment);
            return new Paid(reservation,ride,request,payment,parcel,passenger,driver,university,pin,cipher,hash);
        });
    }
    private static UUID user(JdbcTemplate sql){return sql.queryForObject("INSERT INTO users(email,password_hash,name,birth_date,email_verified_at) VALUES(?,'synthetic-not-a-login','Synthetic PIN owner','2000-01-01',now()) RETURNING id",UUID.class,UUID.randomUUID()+"@example.test");}
}
