package com.unicarona.user.repository;

import com.unicarona.user.entity.User;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmailAndDeletedAtIsNull(String email);
    @Query("select u from User u where u.id=:id and u.deletedAt is null")
    Optional<User> findLiveById(UUID id);
}
