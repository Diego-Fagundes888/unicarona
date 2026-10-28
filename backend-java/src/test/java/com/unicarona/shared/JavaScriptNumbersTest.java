package com.unicarona.shared;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JavaScriptNumbersTest {
    @Test void numericFingerprintsMatchNodeForBoundariesSubnormalsAndRandomDoubles() throws Exception {
        var numbers=new ArrayList<Double>(List.of(0.0,-0.0,10.0,0.1,1e-6,1e-7,1e20,1e21,1e23,Double.MIN_VALUE,Double.MIN_NORMAL,Double.MAX_VALUE,1000000000000000128.0,90.0,-180.0,10.1235,1.2345678901234567));
        var random=new Random(20261002);
        for(int i=0;i<1200;i++) { double value=Double.longBitsToDouble(random.nextLong());if(Double.isFinite(value)) numbers.add(value); }
        var json=new ObjectMapper();
        var process=new ProcessBuilder("node","-e","const s=require('fs').readFileSync(0,'utf8');const result=JSON.parse(s).map(n=>JSON.stringify(n));process.stdout.write(JSON.stringify(result));").redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try(var input=process.getOutputStream()) { input.write(json.writeValueAsBytes(numbers)); }
        byte[] result=process.getInputStream().readAllBytes();assertTrue(process.waitFor(10,TimeUnit.SECONDS));assertEquals(0,process.exitValue());
        var expected=json.readTree(new String(result,StandardCharsets.UTF_8));
        for(int i=0;i<numbers.size();i++) assertEquals(expected.get(i).asText(),JavaScriptNumbers.format(numbers.get(i)),"Synthetic number index "+i);
    }
    @Test void nonFiniteNumbersAreRejectedAndNegativeZeroIsCanonical() {
        assertEquals("0",JavaScriptNumbers.format(-0.0));assertThrows(IllegalArgumentException.class,() -> JavaScriptNumbers.format(Double.NaN));assertThrows(IllegalArgumentException.class,() -> JavaScriptNumbers.format(Double.POSITIVE_INFINITY));
    }
}
