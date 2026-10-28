package com.unicarona.student;

import com.unicarona.student.entity.Course;
import com.unicarona.student.entity.Region;
import com.unicarona.student.entity.University;
import com.unicarona.student.repository.CourseRepository;
import com.unicarona.student.repository.RegionRepository;
import com.unicarona.student.repository.UniversityRepository;
import com.unicarona.student.service.CatalogService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CatalogServiceTest {
    @Test void catalogReturnsDetachedDtosWithHistoricalFieldNames() {
        var universities = mock(UniversityRepository.class); var courses = mock(CourseRepository.class); var regions = mock(RegionRepository.class);
        var university = mock(University.class); var course = mock(Course.class); var region = mock(Region.class);
        UUID id = UUID.randomUUID();
        when(university.getId()).thenReturn(id); when(university.getName()).thenReturn("UNICEPLAC"); when(university.getStateCode()).thenReturn("DF");
        when(course.getUniversityId()).thenReturn(id); when(course.getName()).thenReturn("Medicina"); when(region.getName()).thenReturn("Gama");
        when(universities.findByActiveTrueOrderByNameAsc()).thenReturn(List.of(university));
        when(courses.findAvailable()).thenReturn(List.of(course)); when(regions.findAllByOrderByNameAsc()).thenReturn(List.of(region));
        var dto = new CatalogService(universities, courses, regions).catalog();
        assertEquals("UNICEPLAC", dto.universities().getFirst().name()); assertEquals(id, dto.courses().getFirst().universityId());
        assertEquals("Gama", dto.regions().getFirst().name());
        verify(universities).findByActiveTrueOrderByNameAsc(); verify(courses).findAvailable(); verify(regions).findAllByOrderByNameAsc();
        verifyNoMoreInteractions(universities, courses, regions);
    }
}
