package com.unicarona.student.service;

import com.unicarona.student.dto.CatalogDto;
import com.unicarona.student.repository.CourseRepository;
import com.unicarona.student.repository.RegionRepository;
import com.unicarona.student.repository.UniversityRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CatalogService {
    private final UniversityRepository universities;
    private final CourseRepository courses;
    private final RegionRepository regions;
    public CatalogService(UniversityRepository universities, CourseRepository courses, RegionRepository regions) {
        this.universities = universities; this.courses = courses; this.regions = regions;
    }
    @Transactional(readOnly = true)
    public CatalogDto catalog() {
        return new CatalogDto(
            universities.findByActiveTrueOrderByNameAsc().stream().map(u -> new CatalogDto.UniversityItem(u.getId(), u.getSlug(), u.getName(), u.getCity(), u.getStateCode())).toList(),
            courses.findAvailable().stream().map(c -> new CatalogDto.CourseItem(c.getId(), c.getSlug(), c.getName(), c.getUniversityId())).toList(),
            regions.findAllByOrderByNameAsc().stream().map(r -> new CatalogDto.RegionItem(r.getId(), r.getSlug(), r.getName(), r.getStateCode())).toList());
    }
}
