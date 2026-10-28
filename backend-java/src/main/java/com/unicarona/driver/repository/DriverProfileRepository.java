package com.unicarona.driver.repository;

import com.unicarona.driver.entity.DriverProfile;
import java.util.UUID;
import org.springframework.data.jpa.repository.*;

public interface DriverProfileRepository extends JpaRepository<DriverProfile,UUID> {
    @Modifying(clearAutomatically=true)
    @Query(value="INSERT INTO driver_profiles(user_id,status) VALUES(:user,'pending') ON CONFLICT(user_id) DO UPDATE SET status='pending',updated_at=now()",nativeQuery=true)
    int pending(UUID user);
    @Modifying(clearAutomatically=true)
    @Query(value="UPDATE driver_profiles SET status=:status,approved_at=CASE WHEN :approve THEN now() ELSE NULL END,updated_at=now() WHERE user_id=:user",nativeQuery=true)
    int decide(UUID user,String status,boolean approve);
}
