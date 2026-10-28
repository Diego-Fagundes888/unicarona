package com.unicarona.shared;

import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import static org.junit.jupiter.api.Assertions.*;

class TransactionRetriesTest {
    @Test void retriesDeadlocksAndSerializationFailuresWithACompleteNewInvocation() {
        for(String state:new String[]{"40001","40P01"}) {
            var calls=new AtomicInteger();
            assertEquals("committed",new TransactionRetries().call(() -> {
                if(calls.incrementAndGet()<3) throw new DataAccessResourceFailureException("synthetic",new SQLException("synthetic",state));
                return "committed";
            }));
            assertEquals(3,calls.get());
        }
    }
    @Test void stopsAfterFourAttemptsAndDoesNotRetryLockTimeoutOrBusinessErrors() {
        for(String state:new String[]{"40P01","55P03"}) {
            var calls=new AtomicInteger();
            assertThrows(DataAccessResourceFailureException.class,() -> new TransactionRetries().run(() -> {
                calls.incrementAndGet(); throw new DataAccessResourceFailureException("synthetic",new SQLException("synthetic",state));
            }));
            assertEquals("40P01".equals(state)?4:1,calls.get());
        }
        var business=new ApiException("SYNTHETIC","Synthetic",409);
        assertSame(business,assertThrows(ApiException.class,() -> new TransactionRetries().run(() -> { throw business; })));
    }
}
