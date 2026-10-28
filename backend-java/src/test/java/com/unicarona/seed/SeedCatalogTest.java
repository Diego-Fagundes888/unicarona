package com.unicarona.seed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unicarona.file.config.StorageSettings;
import com.unicarona.shared.ApiException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class SeedCatalogTest {
    private final ObjectMapper json=new ObjectMapper();
    @TempDir Path root;
    private SeedCatalog catalog() { return new SeedCatalog(new StorageSettings(new MockEnvironment().withProperty("FRONTEND_PATH",root.toString())),json); }
    private String sample() { return "const courses=[[\"curso\",\"Análise\"],].map(x=>x);const dfRegions=[[\"Gama\",\"II\"],].map(x=>x);const rideCities=[[\"São João d'Aliança\",\"GO\"],].map(x=>x);"; }
    @Test void literalCatalogPreservesUnicodeStableIndicesAndTrailingCommasWithoutExecutingCode() {
        var value=catalog().parse("throw new Error('Must never run');"+sample());assertEquals(List.of(new SeedCatalog.Course("curso","Análise")),value.courses());assertEquals(List.of(new SeedCatalog.Region("df-ra-01","Gama","DF"),new SeedCatalog.Region("ride-city-01","São João d'Aliança","GO")),value.regions());assertThrows(UnsupportedOperationException.class,() -> value.courses().clear());
    }
    @Test void malformedExecutableOrDuplicateRowsAreRejectedAsCatalogErrors() {
        for(String input:List.of("",sample().replace("[[\"curso\",\"Análise\"],]","[]"),sample().replace("\"Análise\"","42"),sample().replace("[[\"curso\",\"Análise\"],]","[[\"curso\",\"Análise\"],[\"curso\",\"Outro\"]]"),sample().replace("\"GO\"","(()=>{throw 'executed'})()"),sample().replace("\"GO\"","\"invalid\""))) assertEquals("SEED_CATALOG_INVALID",assertThrows(ApiException.class,() -> catalog().parse(input)).code());
    }
    @Test void configuredFrontendCatalogIsReadAndInvalidFileDoesNotFallBackSilently() throws Exception {
        Files.writeString(root.resolve("selection-data.js"),sample());assertEquals("curso",catalog().load().courses().getFirst().id());Files.writeString(root.resolve("selection-data.js"),"invalid");assertEquals("SEED_CATALOG_INVALID",assertThrows(ApiException.class,() -> catalog().load()).code());
    }
    @Test void bundledFrontendCatalogLoadsWithoutAWorkingDirectoryDistFolder() {
        var value=catalog().load();assertEquals(21,value.courses().size());assertEquals(68,value.regions().size());assertTrue(value.courses().stream().anyMatch(c -> c.id().equals("medicina")));assertTrue(value.regions().stream().anyMatch(r -> r.id().equals("df-ra-02") && r.name().equals("Gama")));
    }
    @Test void javaLiteralReaderMatchesActualFrontendJavascriptCatalogFieldForField() throws Exception {
        Path source=Path.of("../dist/selection-data.js").toAbsolutePath();var value=catalog().parse(Files.readString(source));
        Path output=root.resolve("legacy-catalog.json");var process=new ProcessBuilder("node","-e","const fs=require('node:fs'),vm=require('node:vm');const scope={window:{}};vm.runInNewContext(fs.readFileSync(process.argv[1],'utf8'),scope,{timeout:1000});const x=scope.window.UniCaronaSelectionData;process.stdout.write(JSON.stringify({courses:x.courses,regions:x.regions.map(r=>({id:r.id,name:r.name,state:r.state})).sort((a,b)=>a.id.localeCompare(b.id))}));",source.toString()).redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(output.toFile()).start();
        try { assertTrue(process.waitFor(10,TimeUnit.SECONDS));assertEquals(0,process.exitValue()); }finally { if(process.isAlive()) process.destroyForcibly(); }var old=json.readTree(Files.readString(output));assertEquals(old.path("courses"),json.valueToTree(value.courses()));assertEquals(old.path("regions"),json.valueToTree(value.regions().stream().sorted(Comparator.comparing(SeedCatalog.Region::id)).toList()));
    }
}

