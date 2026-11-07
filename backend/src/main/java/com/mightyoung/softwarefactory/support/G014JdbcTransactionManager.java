package com.mightyoung.softwarefactory.support;

import com.mightyoung.softwarefactory.authorization.RegisteredEntityAction;
import com.mightyoung.softwarefactory.identity.HumanSessionAuthorityTransaction;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** Explicit G014 graph only: never replace the application's existing transaction manager. */
public final class G014JdbcTransactionManager extends JdbcTransactionManager {
    public enum LifecycleOutcome { COMMIT_UNKNOWN, ROLLBACK_UNKNOWN }
    /** Owner-issued unknown fact only: no original failure, command data or application exception. */
    public static final class LifecycleException extends TransactionException {
        private final LifecycleOutcome outcome;
        private LifecycleException(LifecycleOutcome outcome){
            super("G014 transaction outcome is unknown");this.outcome=Objects.requireNonNull(outcome);
        }
        public LifecycleOutcome outcome(){return outcome;}
    }
    private final G014SqlProfile profile;
    private final LeaseSource source;
    private final ThreadLocal<Frame> active=new ThreadLocal<>();
    // Deliberately retain ambiguous leases. No pool-specific eviction has been proven by this implementation.
    private final Set<Frame> retained=ConcurrentHashMap.newKeySet();
    private final Map<Object,Frame> transactions=Collections.synchronizedMap(new IdentityHashMap<>());
    private TransactionSqlSealGate registeredGate;
    public G014JdbcTransactionManager(DataSource existingPool,Collection<TransactionSqlSealGate.SqlTemplate> templates){
        this(G014SqlProfile.ASSET_FOUNDATION,existingPool,templates);
    }
    public G014JdbcTransactionManager(G014SqlProfile profile,DataSource existingPool,Collection<TransactionSqlSealGate.SqlTemplate> templates){
        this.profile=Objects.requireNonNull(profile);
        source=new LeaseSource(Objects.requireNonNull(existingPool),List.copyOf(templates));
        super.setDataSource(source);setNestedTransactionAllowed(false);setValidateExistingTransaction(true);
        setRollbackOnCommitFailure(false);
    }
    G014SqlProfile profile(){return profile;}
    /** Every G014 collaborator (including guard, G008 and jOOQ) must receive this exact source. */
    public DataSource commandDataSource(){return source;}
    @Override public void setDataSource(DataSource ignored){throw new IllegalStateException("The G014 datasource is fixed at construction");}
    public int retainedUnusableLeaseCount(){return retained.size();}
    synchronized void registerGate(TransactionSqlSealGate gate){
        if(registeredGate!=null || active.get()!=null)throw new IllegalStateException("G014 gate is already fixed");
        if(Objects.requireNonNull(gate).profile()!=profile)throw new IllegalArgumentException("Mixed G014 gate/manager profiles");
        registeredGate=gate;
    }
    @Override protected Object doGetTransaction(){
        Frame existing=active.get();if(existing!=null)throw existing.jdbc.reject();
        // APTM may suspend an existing synchronization-only scope before doBegin.
        // Reject here so even that scope's callbacks/resources remain untouched.
        if(TransactionSynchronizationManager.isSynchronizationActive())
            throw new IllegalTransactionStateException("G014 cannot enter an existing synchronization scope");
        return super.doGetTransaction();
    }
    Frame currentFrame(){
        Frame frame=active.get();
        if(frame==null)throw new IllegalStateException("G014 transaction is unavailable");
        if(frame.manager!=this || frame.thread!=Thread.currentThread() || frame.profile!=profile || frame.jdbc.profile()!=profile)throw frame.jdbc.reject();
        frame.jdbc.executions();return frame;
    }
    @Override protected void doBegin(Object transaction,TransactionDefinition definition){
        if(active.get()!=null || TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()
                || definition.isReadOnly() || definition.getIsolationLevel()!=TransactionDefinition.ISOLATION_READ_COMMITTED)
            throw new IllegalTransactionStateException("G014 requires a new READ COMMITTED command transaction");
        if(profile==G014SqlProfile.RISK_SCENARIO && registeredGate==null)
            throw new IllegalTransactionStateException("Risk scenario requires its fixed gate before borrowing");
        source.acquiring.set(true);
        try {
            super.doBegin(transaction,definition);
            Frame frame=Objects.requireNonNull(active.get());
            if(!(TransactionSynchronizationManager.getResource(source) instanceof ConnectionHolder holder))throw frame.jdbc.reject();
            frame.holder=holder;frame.jdbc.bind(source,holder);transactions.put(transaction,frame);
        }catch(RuntimeException|Error failure){
            LifecycleException unknown=null;
            Frame frame=active.get();if(frame!=null){
                try {
                    frame.jdbc.poison();unknown=rollbackAndRetainOnFailure(frame);
                    if(frame.jdbc.phase()==G014JdbcGuard.Phase.ROLLED_BACK){
                        try{frame.jdbc.cleanup();}catch(SQLException|RuntimeException|Error cleanupFailure){retained.add(frame);}
                    }
                }finally{unbind(frame);}
            }
            if(unknown!=null)throw unknown;
            throw failure;
        }finally{source.acquiring.remove();}
    }
    @Override protected Object doSuspend(Object transaction){
        Frame frame=active.get();if(frame!=null)throw frame.jdbc.reject();
        throw new IllegalTransactionStateException("G014 transaction suspension is unsupported");
    }
    @Override protected void doResume(Object transaction,Object suspendedResources){
        Frame frame=active.get();if(frame!=null)frame.jdbc.poison();
        throw new IllegalTransactionStateException("G014 transaction resumption is unsupported");
    }
    @Override protected void prepareSynchronization(DefaultTransactionStatus status,TransactionDefinition definition){
        super.prepareSynchronization(status,definition);
        Frame frame=active.get();
        if(frame!=null && status.isNewSynchronization()){
            frame.suspensionVeto=new TransactionSynchronization(){
            @Override public int getOrder(){return org.springframework.core.Ordered.HIGHEST_PRECEDENCE;}
            @Override public void suspend(){throw frame.jdbc.reject();}
            @Override public void savepoint(Object savepoint){frame.jdbc.poison();}
            @Override public void savepointRollback(Object savepoint){frame.jdbc.poison();}
            };
            TransactionSynchronizationManager.registerSynchronization(frame.suspensionVeto);
        }
    }
    /** Fail before the publication alias is bound if another callback could suspend first. */
    public void requirePublicationSynchronizationFirst(){
        Frame frame=currentFrame();
        if(!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()
                || frame.suspensionVeto==null
                || TransactionSynchronizationManager.getSynchronizations().isEmpty()
                || TransactionSynchronizationManager.getSynchronizations().getFirst()!=frame.suspensionVeto)
            throw frame.jdbc.reject();
    }
    @Override protected void doCommit(DefaultTransactionStatus status){
        Frame frame=transactions.get(status.getTransaction());
        if(frame==null)throw new UnexpectedRollbackException("Missing G014 transaction frame");
        try {
            // Spring synchronizations and TransactionExecutionListener.beforeCommit have already run.
            if(active.get()!=frame || frame.manager!=this || frame.thread!=Thread.currentThread()
                    || status.isLocalRollbackOnly() || status.isGlobalRollbackOnly() || status.hasSavepoint()
                    || frame.holder.isRollbackOnly())throw frame.jdbc.reject();
            if(registeredGate==null)throw frame.jdbc.reject();
            registeredGate.requireSeal(frame);
            frame.jdbc.checkCommit();
        }catch(RuntimeException|Error refusal){
            frame.jdbc.poison();LifecycleException unknown=rollbackAndRetainOnFailure(frame);
            if(unknown!=null)throw unknown;
            throw new UnexpectedRollbackException("G014 commit was refused",refusal);
        }
        try{frame.jdbc.commit();}
        catch(SQLException failure){throw unknown(frame,LifecycleOutcome.COMMIT_UNKNOWN);}
        catch(RuntimeException|Error failure){
            retained.add(frame);
            if(frame.jdbc.phase()==G014JdbcGuard.Phase.UNKNOWN || frame.jdbc.phase()==G014JdbcGuard.Phase.COMMIT_SENT)
                throw unknown(frame,LifecycleOutcome.COMMIT_UNKNOWN);
            throw failure;
        }
    }
    @Override protected void doRollback(DefaultTransactionStatus status){
        Frame frame=transactions.get(status.getTransaction());
        if(frame==null)throw new TransactionSystemException("Missing G014 rollback frame");
        if(frame.unknownOutcome!=null)throw unknown(frame,frame.unknownOutcome);
        try{frame.jdbc.rollback();}
        catch(SQLException failure){throw unknown(frame,LifecycleOutcome.ROLLBACK_UNKNOWN);}
        catch(RuntimeException|Error failure){
            retained.add(frame);
            if(frame.jdbc.phase()==G014JdbcGuard.Phase.UNKNOWN || frame.jdbc.phase()==G014JdbcGuard.Phase.COMMIT_SENT)
                throw unknown(frame,LifecycleOutcome.ROLLBACK_UNKNOWN);
            throw failure;
        }
        if(frame.jdbc.phase()==G014JdbcGuard.Phase.UNKNOWN || frame.jdbc.phase()==G014JdbcGuard.Phase.COMMIT_SENT)
            throw unknown(frame,LifecycleOutcome.ROLLBACK_UNKNOWN);
    }
    @Override protected void doCleanupAfterCompletion(Object transaction){
        Frame frame=transactions.remove(transaction);
        if(frame==null)return;
        LifecycleException helperUnknown=null;
        try {
            // An already emitted unknown keeps its original reason; cleanup never compensates or replaces it.
            if(frame.unknownOutcome!=null)retained.add(frame);
            else if(frame.jdbc.phase()!=G014JdbcGuard.Phase.COMMITTED && frame.jdbc.phase()!=G014JdbcGuard.Phase.ROLLED_BACK)
                helperUnknown=rollbackAndRetainOnFailure(frame);
            if(frame.unknownOutcome==null && (frame.jdbc.phase()==G014JdbcGuard.Phase.COMMITTED || frame.jdbc.phase()==G014JdbcGuard.Phase.ROLLED_BACK)){
                try{frame.jdbc.cleanup();}catch(SQLException|RuntimeException|Error failure){retained.add(frame);}
            }else retained.add(frame);
        }finally{unbind(frame);}
        if(helperUnknown!=null)throw helperUnknown;
        // Never call superclass cleanup: it resets autoCommit before proving rollback acknowledgment.
    }
    private LifecycleException rollbackAndRetainOnFailure(Frame frame){
        if(frame.unknownOutcome!=null)return unknown(frame,frame.unknownOutcome);
        try{frame.jdbc.rollback();}catch(SQLException|RuntimeException|Error failure){retained.add(frame);}
        if(frame.jdbc.phase()==G014JdbcGuard.Phase.UNKNOWN || frame.jdbc.phase()==G014JdbcGuard.Phase.COMMIT_SENT)
            return unknown(frame,LifecycleOutcome.ROLLBACK_UNKNOWN);
        return null;
    }
    private LifecycleException unknown(Frame frame,LifecycleOutcome outcome){
        if(frame.unknownOutcome==null)frame.unknownOutcome=outcome;
        retained.add(frame);return new LifecycleException(frame.unknownOutcome);
    }
    private void unbind(Frame frame){
        if(TransactionSynchronizationManager.getResource(source)==frame.holder)TransactionSynchronizationManager.unbindResourceIfPossible(source);
        if(frame.holder!=null)frame.holder.clear();active.remove();
    }
    static final class Frame {
        final G014JdbcTransactionManager manager;
        final G014SqlProfile profile;
        final Thread thread=Thread.currentThread();
        final G014JdbcGuard jdbc;
        ConnectionHolder holder;
        LifecycleOutcome unknownOutcome;
        TransactionSynchronization suspensionVeto;
        TransactionSqlSealGate.CommandPlan plan;
        TransactionSqlSealGate.RegisteredCommandValidator validator;
        HumanSessionAuthorityTransaction.WriteScope scope;
        UUID commandId,targetId;
        RegisteredEntityAction action;
        Object finalSeal;
        Frame(G014JdbcTransactionManager manager,G014JdbcGuard jdbc){this.manager=manager;this.profile=manager.profile;this.jdbc=jdbc;
            if(jdbc.profile()!=profile)throw jdbc.reject();}
    }
    private final class LeaseSource extends AbstractDataSource {
        private final DataSource pool;
        private final List<TransactionSqlSealGate.SqlTemplate> templates;
        private final ThreadLocal<Boolean> acquiring=new ThreadLocal<>();
        LeaseSource(DataSource pool,List<TransactionSqlSealGate.SqlTemplate> templates){
            this.pool=pool;this.templates=templates;
            profile.requireCatalog(templates);
            if(templates.stream().map(TransactionSqlSealGate.SqlTemplate::sql).distinct().count()!=templates.size())
                throw new IllegalArgumentException("Duplicate SQL template");
        }
        @Override public Connection getConnection()throws SQLException {
            if(!Boolean.TRUE.equals(acquiring.get()) || active.get()!=null){
                Frame frame=active.get();if(frame!=null)throw frame.jdbc.reject();
                throw new SQLException("Only the G014 manager may acquire a command lease");
            }
            var guard=new G014JdbcGuard(profile,pool.getConnection(),templates);
            active.set(new Frame(G014JdbcTransactionManager.this,guard));return guard.connection;
        }
        @Override public Connection getConnection(String username,String password)throws SQLException {throw new SQLException("Alternate credentials are unsupported");}
        @Override public <T>T unwrap(Class<T> iface)throws SQLException {if(iface==DataSource.class)return iface.cast(this);throw new SQLException("Raw datasource access is unsupported");}
        @Override public boolean isWrapperFor(Class<?> iface)throws SQLException {if(iface==DataSource.class)return true;throw new SQLException("Raw datasource access is unsupported");}
    }
}
