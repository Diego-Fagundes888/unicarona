package com.unicarona.notification.repository;

import com.unicarona.notification.entity.Notification;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;

public interface NotificationRepository extends JpaRepository<Notification,UUID> {
    List<Notification> findByUserIdOrderByCreatedAtDescIdAsc(UUID userId,Pageable pageable);
    long countByUserIdAndReadAtIsNull(UUID userId);
    Optional<Notification> findByIdAndUserId(UUID id,UUID userId);
    @Modifying(clearAutomatically=true)
    @Query(value="UPDATE notifications SET read_at=coalesce(read_at,now()) WHERE id=:id AND user_id=:user",nativeQuery=true)
    int markRead(UUID id,UUID user);
}
