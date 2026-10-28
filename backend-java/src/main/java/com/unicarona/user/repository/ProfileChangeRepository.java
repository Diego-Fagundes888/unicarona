package com.unicarona.user.repository;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

@Repository @Transactional(propagation=Propagation.MANDATORY)
public class ProfileChangeRepository {
    public record Participants(UUID driver,UUID passenger) {}
    private final JdbcTemplate sql;
    public ProfileChangeRepository(JdbcTemplate sql) { this.sql=sql; }
    public List<Participants> participants(UUID user) {
        return sql.query("SELECT driver_id,passenger_id FROM subscriptions_with_open_rights WHERE driver_id=? OR passenger_id=?",(r,n) -> new Participants(r.getObject("driver_id",UUID.class),r.getObject("passenger_id",UUID.class)),user,user);
    }
    public List<UUID> contracts(UUID user) { return sql.query("SELECT id FROM subscriptions_with_open_rights WHERE driver_id=? OR passenger_id=? ORDER BY id FOR UPDATE",(r,n) -> r.getObject("id",UUID.class),user,user); }
    public void identity(UUID user,String name,String phone) { sql.update("UPDATE users SET name=?,phone=?,updated_at=now() WHERE id=?",name,phone==null || phone.isEmpty()?null:phone,user); }
    public void pauseIncompatible(UUID user) { sql.update("UPDATE routines SET status='paused',updated_at=now() WHERE driver_id=? AND class_id IS DISTINCT FROM (SELECT class_id FROM student_profiles WHERE user_id=?)",user,user); }
}
