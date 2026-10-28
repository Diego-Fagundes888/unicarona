package com.unicarona.student.repository;

import com.unicarona.student.entity.Course;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CourseRepository extends JpaRepository<Course, UUID> {
    @Query("select c from Course c where c.active = true and c.universityId in (select u.id from University u where u.active = true) order by c.name")
    List<Course> findAvailable();
}
