package com.unicarona.student.controller;

import com.unicarona.student.dto.CatalogDto;
import com.unicarona.student.service.CatalogService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CatalogController {
    private final CatalogService service;
    public CatalogController(CatalogService service) { this.service = service; }
    @GetMapping("/api/v1/catalogs")
    @Operation(summary = "Faculdades, cursos e regiões disponíveis")
    public CatalogDto catalog() { return service.catalog(); }
}
