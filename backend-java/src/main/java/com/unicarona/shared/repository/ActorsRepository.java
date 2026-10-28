package com.unicarona.shared.repository;

import com.unicarona.shared.ApiException;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ActorsRepository {
    public record Person(UUID id,String role,String accountStatus,boolean emailVerified,UUID universityId,UUID classId,UUID courseId,String region,String driverStatus) {}
    private final JdbcTemplate sql;
    public ActorsRepository(JdbcTemplate sql) { this.sql=sql; }
    public List<Person> lockUsers(Collection<UUID> ids) {
        // Historical financial operations still lock a tombstone after profile erasure.
        // New contracts separately require active(), and authentication excludes deleted accounts.
        var unique=ids.stream().distinct().toList();
        if(unique.isEmpty()) return List.of();
        sql.execute("SET LOCAL lock_timeout='3s'");
        var placeholders=String.join(",",java.util.Collections.nCopies(unique.size(),"?"));
        var persons=sql.query("""
            SELECT u.id,u.role,u.account_status,u.email_verified_at,s.university_id,s.class_id,s.course_id,s.region_label,
            coalesce(d.status,'not_requested') AS driver_status FROM users u
            LEFT JOIN student_profiles s ON s.user_id=u.id LEFT JOIN driver_profiles d ON d.user_id=u.id
            WHERE u.id IN (
            """+placeholders+") ORDER BY u.id FOR UPDATE OF u",(r,n) -> new Person(
                r.getObject("id",UUID.class),r.getString("role"),r.getString("account_status"),r.getTimestamp("email_verified_at")!=null,
                r.getObject("university_id",UUID.class),r.getObject("class_id",UUID.class),r.getObject("course_id",UUID.class),r.getString("region_label"),r.getString("driver_status")),unique.toArray());
        ApiException.require(persons.size()==unique.size(),"USER_NOT_FOUND","Perfil não encontrado.",404); return persons;
    }
    public Person lockUser(UUID id) { return lockUsers(List.of(id)).getFirst(); }
    public static void active(Person user) {
        ApiException.require("active".equals(user.accountStatus()),"ACCOUNT_RESTRICTED","Conta indisponível para novas combinações.",403);
        ApiException.require(user.emailVerified(),"EMAIL_UNVERIFIED","Confirme o e-mail antes de continuar.",403);
    }
    public static void driver(Person user) {
        active(user); ApiException.require("approved".equals(user.driverStatus()),"DRIVER_NOT_APPROVED","Motorista e veículo precisam de aprovação.",403);
    }
    public static void existingDriver(Person user){
        if(java.util.Set.of("closing","deactivated").contains(user.accountStatus())) ApiException.require(user.emailVerified()&&"approved".equals(user.driverStatus()),"DRIVER_NOT_APPROVED","Motorista e veículo precisam de aprovação.",403);
        else driver(user);
    }
    public static boolean sameClass(Person a,Person b) { return a.classId()!=null && a.classId().equals(b.classId()) && java.util.Objects.equals(a.universityId(),b.universityId()); }
    public void notBlocked(UUID a,UUID b) {
        ApiException.require(!Boolean.TRUE.equals(sql.queryForObject("SELECT EXISTS(SELECT 1 FROM user_blocks WHERE active AND ((by_id=? AND other_id=?) OR (by_id=? AND other_id=?)))",Boolean.class,a,b,b,a)),"COMBINATION_UNAVAILABLE","Esta combinação não está disponível.",409);
    }
}
