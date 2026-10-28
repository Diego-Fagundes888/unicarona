package com.unicarona.privacy;

import com.unicarona.privacy.service.*;
import com.unicarona.shared.TransactionRetries;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PrivacyUnusedFilesMaintenanceTest {
    @Test void aFailedBatchStopsInsteadOfRepeatingLockWaitsForEveryUpload(){
        var files=mock(PrivacyUnusedFiles.class);var first=new PrivacyUnusedFiles.Candidate(UUID.randomUUID(),UUID.randomUUID());var second=new PrivacyUnusedFiles.Candidate(UUID.randomUUID(),UUID.randomUUID());
        when(files.candidates(10)).thenReturn(List.of(first,second));when(files.retire(first)).thenThrow(new IllegalStateException("Synthetic lock timeout"));
        assertDoesNotThrow(()->new PrivacyUnusedFilesMaintenance(files,new TransactionRetries()).run(10,()->false));verify(files).retire(first);verify(files,never()).retire(second);
    }
    @Test void shutdownBeforeMaintenanceAvoidsEvenReadingCandidates(){
        var files=mock(PrivacyUnusedFiles.class);new PrivacyUnusedFilesMaintenance(files,new TransactionRetries()).run(10,()->true);verifyNoInteractions(files);
    }
    @Test void shutdownBetweenCandidatesDoesNotRetireTheNextUpload(){
        var files=mock(PrivacyUnusedFiles.class);var first=new PrivacyUnusedFiles.Candidate(UUID.randomUUID(),UUID.randomUUID());var second=new PrivacyUnusedFiles.Candidate(UUID.randomUUID(),UUID.randomUUID());var stopping=new AtomicBoolean();
        when(files.candidates(10)).thenReturn(List.of(first,second));when(files.retire(first)).thenAnswer(invocation->{stopping.set(true);return true;});
        new PrivacyUnusedFilesMaintenance(files,new TransactionRetries()).run(10,stopping::get);verify(files).retire(first);verify(files,never()).retire(second);
    }
}
