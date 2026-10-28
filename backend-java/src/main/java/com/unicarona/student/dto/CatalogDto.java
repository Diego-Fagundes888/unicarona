package com.unicarona.student.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.UUID;

public record CatalogDto(List<UniversityItem> universities, List<CourseItem> courses, List<RegionItem> regions) {
    public record UniversityItem(UUID id, String slug, String name, String city, @JsonProperty("state_code") String stateCode) {}
    public record CourseItem(UUID id, String slug, String name, @JsonProperty("university_id") UUID universityId) {}
    public record RegionItem(UUID id, String slug, String name, @JsonProperty("state_code") String stateCode) {}
}
