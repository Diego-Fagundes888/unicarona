package com.unicarona.ride.repository;

import com.unicarona.ride.entity.RideRoute;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RideRouteRepository extends JpaRepository<RideRoute,UUID> {}
