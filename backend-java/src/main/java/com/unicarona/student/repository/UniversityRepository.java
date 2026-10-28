package com.unicarona.student.repository;

import com.unicarona.student.entity.University;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UniversityRepository extends JpaRepository<University, UUID> {
    List<University> findByActiveTrueOrderByNameAsc();
}
