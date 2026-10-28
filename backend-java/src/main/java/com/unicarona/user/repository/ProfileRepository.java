package com.unicarona.user.repository;

import com.unicarona.user.dto.ProfileDto;
import com.unicarona.shared.ApiException;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ProfileRepository {
    private final JdbcTemplate sql;
    public ProfileRepository(JdbcTemplate sql) { this.sql = sql; }
    public ProfileDto find(UUID user) {
        var profiles = sql.query("""
            SELECT u.id,u.name,u.email,u.birth_date::text,u.phone,u.role,u.account_status,u.email_verified_at,u.photo_file_id,
            s.university_id,s.course_id,s.class_id,s.period_status,s.period_number,s.shift,s.region_id,s.region_label,
            s.verification_status,s.notices,s.withdrawal_cadence,c.name AS course,cl.label AS class_label,un.name AS university,
            coalesce(d.status,'not_requested') AS driver_status
            FROM users u LEFT JOIN student_profiles s ON s.user_id=u.id LEFT JOIN courses c ON c.id=s.course_id
            LEFT JOIN classes cl ON cl.id=s.class_id LEFT JOIN universities un ON un.id=s.university_id
            LEFT JOIN driver_profiles d ON d.user_id=u.id WHERE u.id=? AND u.deleted_at IS NULL
            """, (r, n) -> new ProfileDto(r.getObject("id", UUID.class), r.getString("name"), r.getString("email"), r.getString("birth_date"),
                r.getString("phone") == null ? "" : r.getString("phone"), r.getString("role"), r.getString("account_status"),
                r.getTimestamp("email_verified_at") != null, r.getObject("university_id", UUID.class), r.getString("university"),
                r.getObject("course_id", UUID.class), r.getString("course"), r.getString("period_status"), r.getObject("period_number", Integer.class),
                r.getString("class_label") == null ? "" : r.getString("class_label"), r.getObject("class_id", UUID.class), r.getString("shift"),
                r.getObject("region_id", UUID.class), r.getString("region_label"), r.getString("verification_status"), r.getString("driver_status"),
                r.getObject("photo_file_id") == null ? "" : "/api/v1/files/" + r.getObject("photo_file_id"),
                new ProfileDto.Settings(r.getObject("notices", Boolean.class), r.getString("withdrawal_cadence"))), user);
        if (profiles.isEmpty()) throw new ApiException("USER_NOT_FOUND", "Conta não encontrada.", 404);
        return profiles.getFirst();
    }
}
