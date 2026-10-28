package com.unicarona.privacy.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.unicarona.shared.ApiException;
import java.io.IOException;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;

/** The commit-gap bridge. Typed retention snapshots and replay are supplied by the recovery layer.
 * It does not infer that an unresolved preparation was approved or rolled back. */
@Service
public class PrivacyHoldControlTransactions {
    public record Marker(long sequence,UUID operation,boolean pending){}
    private static final class Work {Supplier<JsonNode> snapshot;PrivacyHoldControlJournal.Prepared prepared;boolean confirmed;}
    private final JdbcTemplate sql;private final PrivacyHoldControlJournal journal;private final PrivacyReadiness readiness;private final TransactionTemplate isolated;
    public PrivacyHoldControlTransactions(JdbcTemplate sql,PrivacyHoldControlJournal journal,PrivacyReadiness readiness,PlatformTransactionManager manager){this.sql=sql;this.journal=journal;this.readiness=readiness;isolated=new TransactionTemplate(manager);isolated.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);isolated.setTimeout(10);}
    public Marker marker(){return sql.queryForObject("SELECT sequence,operation_id,needs_confirmation FROM privacy_hold_control_state WHERE singleton",(r,n)->new Marker(r.getLong(1),r.getObject(2,UUID.class),r.getBoolean(3)));}
    private boolean matches(Marker marker,PrivacyBackupCatalog.ControlHead head){return head!=null&&marker.sequence()==head.sequence()&&head.operation().equals(marker.operation());}
    /** Destructive maintenance needs the actual independent checkpoint, not only process readiness. */
    void requireCurrentForMaintenance()throws IOException {
        PrivacyRetentionLock.acquireForRecovery(sql);if(!journal.enabled()){requireNoEstablishedControl();return;}
        try{
            var marker=marker();var pending=journal.pending();if(pending.isPresent()){
                if(!marker.pending()||!matches(marker,pending.get().head()))throw new IOException("Preservation preparation has no matching committed database marker.");journal.resolve(new PrivacyHoldControlJournal.Prepared(pending.get().head()),true);
            }
            var current=journal.current();if(current.isEmpty()?marker.sequence()!=0||marker.pending():!matches(marker,current.get().head()))throw new IOException("Independent preservation control needs recovery before maintenance.");
            if(marker.pending()){sql.execute("SET LOCAL unicarona.privacy_hold_control_confirmed='on'");sql.update("UPDATE privacy_hold_control_state SET needs_confirmation=false WHERE singleton");}
        }
        catch(IOException unavailable){readiness.begin();throw unavailable;}
    }
    /** Call after mutations, inside the shared retention transaction; multiple calls coalesce. */
    public void stage(Supplier<JsonNode> snapshot){
        if(!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.isSynchronizationActive())throw new IllegalStateException("Hold control preparation requires a synchronized transaction.");
        if(!journal.enabled()){requireNoEstablishedControl();return;}PrivacyRetentionLock.acquire(sql);
        Work existing=(Work)TransactionSynchronizationManager.getResource(this);if(existing!=null){existing.snapshot=snapshot;return;}
        var work=new Work();work.snapshot=snapshot;TransactionSynchronizationManager.bindResource(this,work);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
            @Override public int getOrder(){return Integer.MAX_VALUE-100;}
            @Override public void beforeCommit(boolean readOnly){
                if(readOnly)throw new IllegalStateException("Read-only transactions cannot prepare hold control.");
                try{
                    Marker before=marker();var current=journal.current();if(before.pending()||(current.isEmpty()?before.sequence()!=0:!matches(before,current.get().head())))throw new IOException("Database and independent hold checkpoint need recovery before mutation.");
                    JsonNode next=work.snapshot.get();if(current.isPresent()&&current.get().state().equals(next)&&!journal.currentNeedsRekey())return;
                    work.prepared=journal.prepare(next);var target=work.prepared.target();
                    int changed=sql.update("UPDATE privacy_hold_control_state SET sequence=?,operation_id=?,needs_confirmation=true WHERE singleton AND sequence=? AND NOT needs_confirmation",target.sequence(),target.operation(),before.sequence());if(changed!=1)throw new IOException("Hold control checkpoint changed during preparation.");
                }catch(IOException unavailable){readiness.begin();throw unavailable();}
            }
            @Override public void afterCommit(){
                if(work.prepared==null)return;
                try{journal.resolve(work.prepared,true);confirm(work.prepared.target());work.confirmed=true;}
                catch(Exception unavailable){readiness.begin();throw unavailable();}
            }
            @Override public void afterCompletion(int status){
                try{
                    if(work.prepared!=null&&status==STATUS_ROLLED_BACK)journal.resolve(work.prepared,false);
                    else if(work.prepared!=null&&!work.confirmed)readiness.begin();
                }catch(Exception unresolved){readiness.begin();org.slf4j.LoggerFactory.getLogger(PrivacyHoldControlTransactions.class).warn("event=privacy_hold_control_pending code=CONTROL_OUTCOME_RETRY");}
                finally{TransactionSynchronizationManager.unbindResourceIfPossible(PrivacyHoldControlTransactions.this);}
            }
        });
    }
    private void confirm(PrivacyBackupCatalog.ControlHead target){isolated.executeWithoutResult(ignored->{PrivacyRetentionLock.acquireForRecovery(sql);Marker marker=marker();if(!matches(marker,target))throw unavailable();sql.execute("SET LOCAL unicarona.privacy_hold_control_confirmed='on'");sql.update("UPDATE privacy_hold_control_state SET needs_confirmation=false WHERE singleton AND sequence=? AND operation_id=?",target.sequence(),target.operation());});}
    /** Only finalizes a prepared snapshot when this actual database contains its committed marker.
     * An older backup requires typed replay; absence of a marker never proves rollback. */
    public void confirmKnownCommittedOutcome(){
        recoverCommitted(null);
    }
    @FunctionalInterface interface Replay {void apply(com.fasterxml.jackson.databind.JsonNode state)throws IOException;}
    /** Internal recovery hook: the caller supplies the closed, typed evidence replayer. */
    void recoverCommitted(Replay replay){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Hold outcome recovery must start outside business transactions.");
        if(!journal.enabled()){requireNoEstablishedControl();return;}isolated.executeWithoutResult(ignored->{
            PrivacyRetentionLock.acquireForRecovery(sql);Marker marker=marker();
            try{
                var pending=journal.pending();if(pending.isPresent()){
                    var target=pending.get().head();if(!marker.pending()||!matches(marker,target))throw new IOException("Hold preparation outcome cannot be proven from this database.");journal.resolve(new PrivacyHoldControlJournal.Prepared(target),true);
                }
                var current=journal.current();if(current.isEmpty()){if(marker.sequence()!=0||marker.pending())throw new IOException("Missing independent hold checkpoint.");return;}
                if(!matches(marker,current.get().head())){
                    if(replay==null||marker.pending()||marker.sequence()>=current.get().head().sequence())throw new IOException("Database requires typed hold-control replay before opening.");
                    replay.apply(current.get().state());var target=current.get().head();sql.execute("SET LOCAL unicarona.privacy_hold_control_replay='on'");sql.update("UPDATE privacy_hold_control_state SET sequence=?,operation_id=?,needs_confirmation=false WHERE singleton",target.sequence(),target.operation());return;
                }
                if(marker.pending()){sql.execute("SET LOCAL unicarona.privacy_hold_control_confirmed='on'");sql.update("UPDATE privacy_hold_control_state SET needs_confirmation=false WHERE singleton");}
            }catch(IOException unavailable){readiness.begin();throw unavailable();}
        });
    }
    private void requireNoEstablishedControl(){Marker marker=marker();if(marker.sequence()!=0||marker.pending()){readiness.begin();throw unavailable();}}
    private static ApiException unavailable(){return new ApiException("PRIVACY_CONTROL_UNAVAILABLE","Não foi possível confirmar o controle de preservação. A recuperação precisa ser concluída antes de continuar.",503);}
}
