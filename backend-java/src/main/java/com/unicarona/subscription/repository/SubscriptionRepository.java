package com.unicarona.subscription.repository;
import com.unicarona.subscription.entity.Subscription;
import java.util.UUID;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.Optional;
public interface SubscriptionRepository extends JpaRepository<Subscription,UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select s from Subscription s where s.id=:id")
    Optional<Subscription> lockById(@Param("id") UUID id);
    @Modifying @Query(value="UPDATE subscriptions SET status='renewed',renew=false,updated_at=now() WHERE id=:id",nativeQuery=true)
    int renewed(@Param("id") UUID id);
    @Modifying @Query(value="UPDATE subscriptions SET status=:status,renew=false,updated_at=now() WHERE id=:id",nativeQuery=true)
    int ended(@Param("id") UUID id,@Param("status") String status);
}
