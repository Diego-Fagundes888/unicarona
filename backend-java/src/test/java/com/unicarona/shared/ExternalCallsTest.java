package com.unicarona.shared;

import java.io.IOException;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExternalCallsTest {
    @Test void timeoutReturnsAControlledErrorAndInterruptsTheProvider() throws Exception {
        var calls=new ExternalCalls();var interrupted=new CountDownLatch(1);
        try {
            var error=assertThrows(ApiException.class,() -> calls.call(100,"PROVIDER_TIMEOUT","Synthetic provider unavailable",() -> {
                try {new CountDownLatch(1).await();return "never";}catch(InterruptedException stopped){interrupted.countDown();throw stopped;}
            }));assertEquals(503,error.status());assertEquals("PROVIDER_TIMEOUT",error.code());assertTrue(interrupted.await(2,TimeUnit.SECONDS));
        } finally { calls.close(); }
    }
    @Test void domainErrorsArePreservedAndOtherFailuresAreSanitized() {
        var calls=new ExternalCalls();
        try {
            assertEquals("ready",calls.call(1000,"PROVIDER_TIMEOUT","Synthetic unavailable",() -> "ready"));
            var domain=new ApiException("PROVIDER_UNAVAILABLE","Synthetic unavailable",503);
            assertSame(domain,assertThrows(ApiException.class,() -> calls.call(1000,"UNEXPECTED","Safe message",() -> {throw domain;})));
            var error=assertThrows(ApiException.class,() -> calls.call(1000,"PROVIDER_TIMEOUT","Safe message",() -> {throw new IOException("Synthetic sensitive provider response");}));
            assertEquals("Safe message",error.getMessage());assertEquals("PROVIDER_TIMEOUT",error.code());
        } finally { calls.close(); }
    }
}
