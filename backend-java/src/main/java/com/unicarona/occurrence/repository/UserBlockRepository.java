package com.unicarona.occurrence.repository;

import com.unicarona.occurrence.entity.UserBlock;
import java.util.UUID;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface UserBlockRepository extends JpaRepository<UserBlock,UUID> {
    @Modifying @Query(value="INSERT INTO user_blocks(by_id,other_id) VALUES(:actor,:other) ON CONFLICT(by_id,other_id) DO UPDATE SET active=true,updated_at=now()",nativeQuery=true)
    int activate(@Param("actor") UUID actor,@Param("other") UUID other);
    @Modifying @Query(value="UPDATE user_blocks SET active=false,updated_at=now() WHERE id=:id",nativeQuery=true)
    int deactivate(@Param("id") UUID id);
}
