package com.unicarona.student.repository;

import com.unicarona.student.entity.StudentProfile;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.*;

public interface StudentProfileRepository extends JpaRepository<StudentProfile,UUID> {
    @Modifying(clearAutomatically=true)
    @Query(value="UPDATE student_profiles SET notices=:notices,withdrawal_cadence=:withdrawal,updated_at=now() WHERE user_id=:user",nativeQuery=true)
    int updateSettings(UUID user,boolean notices,String withdrawal);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select p from StudentProfile p where p.userId=:user")
    Optional<StudentProfile> lockProfile(UUID user);
    @Modifying(clearAutomatically=true)
    @Query(value="UPDATE student_profiles SET verification_status=:status,verification_source=:source,verified_at=CASE WHEN :status='verified' THEN now() ELSE NULL END WHERE user_id=:user",nativeQuery=true)
    int updateVerification(UUID user,String status,String source);
}
