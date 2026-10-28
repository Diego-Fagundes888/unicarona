package com.unicarona.student.repository;

import com.unicarona.student.entity.Region;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RegionRepository extends JpaRepository<Region, UUID> {
    List<Region> findAllByOrderByNameAsc();
}
