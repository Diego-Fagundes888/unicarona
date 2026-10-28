package com.unicarona.privacy.service;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.*;

/** Retriable coordinator. Original instruction dates stay immutable; a proof is published only
 * after a separately committed database verification, never during an uncommitted erasure. */
@Service
public class PrivacySuppressionCompletions {
    private final PrivacyErasureJournal journal;private final PrivacySuppressionConfirmationTransactions transactions;private UUID accountAfter,fileAfter;
    public PrivacySuppressionCompletions(PrivacyErasureJournal journal,PrivacySuppressionConfirmationTransactions transactions){this.journal=journal;this.transactions=transactions;}
    private void run(Runnable action){try{action.run();}catch(Exception unavailable){org.slf4j.LoggerFactory.getLogger(getClass()).warn("event=suppression_confirmation_pending code=SUPPRESSION_PROOF_RETRY");}}
    @FunctionalInterface private interface Check {void run()throws IOException;}
    private Runnable check(Check check){return ()->{try{check.run();}catch(IOException unavailable){throw new java.io.UncheckedIOException(unavailable);}};}
    public void account(PrivacyErasureJournal.Entry entry){run(check(()->transactions.account(entry)));}
    public void account(UUID closure){run(check(()->{for(var entry:journal.entries())if(entry.closure().equals(closure)){transactions.account(entry);return;}}));}
    private void committed(Check action){if(!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.isSynchronizationActive())throw new IllegalStateException("Suppression completion requires an active transaction.");TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){run(check(action));}});}
    public void fileAfterCommit(PrivacyErasureJournal.FileEntry entry){committed(()->transactions.file(entry));}
    public synchronized void confirmBatch(int batch)throws IOException {
        if(batch<1||batch>10000)throw new IllegalArgumentException("Invalid suppression confirmation batch.");
        var accounts=journal.entries().stream().filter(e->e.confirmedAt()==null&&(accountAfter==null||e.closure().toString().compareTo(accountAfter.toString())>0)).sorted(Comparator.comparing(e->e.closure().toString())).limit(batch).toList();if(accounts.isEmpty())accountAfter=null;
        for(var entry:accounts){transactions.account(entry);accountAfter=entry.closure();}
        var files=journal.fileEntries().stream().filter(e->e.confirmedAt()==null&&(fileAfter==null||e.file().toString().compareTo(fileAfter.toString())>0)).sorted(Comparator.comparing(e->e.file().toString())).limit(batch).toList();if(files.isEmpty())fileAfter=null;
        for(var entry:files){transactions.file(entry);fileAfter=entry.file();}
    }
    public Instant latestVerifiedHorizon()throws IOException {
        Instant latest=Instant.EPOCH;for(var e:journal.entries())if(e.confirmedAt()!=null&&e.confirmedAt().isAfter(latest))latest=e.confirmedAt();for(var e:journal.fileEntries())if(e.confirmedAt()!=null&&e.confirmedAt().isAfter(latest))latest=e.confirmedAt();for(var e:journal.orphanEntries())if(e.confirmedAbsentAt()!=null&&e.confirmedAbsentAt().isAfter(latest))latest=e.confirmedAbsentAt();return latest;
    }
    public int retire(int batch)throws IOException {return transactions.retire(batch);}
}
