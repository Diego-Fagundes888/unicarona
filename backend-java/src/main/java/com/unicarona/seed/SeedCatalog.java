package com.unicarona.seed;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.*;
import com.unicarona.file.config.StorageSettings;
import com.unicarona.shared.ApiException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Reads the frontend's literal catalog arrays as data; never executes JavaScript or requires Node. */
@Component
public class SeedCatalog {
    public record Course(String id,String label) {}
    public record Region(String id,String name,String state) {}
    public record Catalog(List<Course> courses,List<Region> regions) { public Catalog { courses=List.copyOf(courses);regions=List.copyOf(regions); } }
    private final Path source;
    private final ObjectReader arrays;
    public SeedCatalog(StorageSettings storage,ObjectMapper json) { source=storage.publicRoot().resolve("selection-data.js");arrays=json.reader().with(JsonReadFeature.ALLOW_TRAILING_COMMA); }
    public Catalog load() {
        try {
            if(Files.isRegularFile(source)) return parse(Files.readString(source,StandardCharsets.UTF_8));
            try(var resource=getClass().getResourceAsStream("/static/selection-data.js")) {
                if(resource==null) throw invalid();return parse(new String(resource.readAllBytes(),StandardCharsets.UTF_8));
            }
        }catch(IOException error) { throw invalid(); }
    }
    Catalog parse(String text) {
        var courses=new ArrayList<Course>();for(var row:rows(text,"courses")) courses.add(new Course(row.get(0).asText(),row.get(1).asText()));
        var regions=new ArrayList<Region>();int index=0;for(var row:rows(text,"dfRegions")) regions.add(new Region("df-ra-%02d".formatted(++index),row.get(0).asText(),"DF"));
        index=0;for(var row:rows(text,"rideCities")) regions.add(new Region("ride-city-%02d".formatted(++index),row.get(0).asText(),row.get(1).asText()));
        if(courses.stream().map(Course::id).distinct().count()!=courses.size() || courses.stream().anyMatch(c -> !c.id().matches("[a-z0-9-]+") || c.label().isBlank()) || regions.stream().anyMatch(r -> r.name().isBlank() || !r.state().matches("[A-Z]{2}"))) throw invalid();
        return new Catalog(courses,regions);
    }
    private JsonNode rows(String text,String variable) {
        var matcher=Pattern.compile("\\bconst\\s+"+variable+"\\s*=\\s*(\\[.*?\\])\\s*\\.map",Pattern.DOTALL).matcher(text);
        if(!matcher.find()) throw invalid();
        try {
            JsonNode rows=arrays.readTree(matcher.group(1));if(!rows.isArray() || rows.isEmpty()) throw invalid();
            for(var row:rows) if(!row.isArray() || row.size()!=2 || !row.get(0).isTextual() || !row.get(1).isTextual()) throw invalid();return rows;
        }catch(IOException error) { throw invalid(); }
    }
    private ApiException invalid() { return new ApiException("SEED_CATALOG_INVALID","Catálogo de demonstração indisponível ou inválido.",500); }
}
