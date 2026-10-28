package com.unicarona.subscription.repository;
import com.unicarona.subscription.entity.SubscriptionSchedule;
import java.util.UUID;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
public interface SubscriptionScheduleRepository extends JpaRepository<SubscriptionSchedule,SubscriptionSchedule.Key> {
    @Modifying @Query(value="UPDATE subscription_schedule SET held=false WHERE subscription_id=:id AND held",nativeQuery=true)
    int releaseHolds(@Param("id") UUID id);
}
