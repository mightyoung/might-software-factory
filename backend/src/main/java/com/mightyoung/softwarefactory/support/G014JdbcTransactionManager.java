package com.mightyoung.softwarefactory.support;

import com.mightyoung.softwarefactory.authorization.RegisteredEntityAction;
import com.mightyoung.softwarefactory.identity.HumanSessionAuthorityTransaction;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** Explicit G014 graph only: never replace the application's existing transaction manager. */
public final class G014JdbcTransactionManager extends JdbcTransactionManager {
    public enum LifecycleOutcome { COMMIT_UNKNOWN, ROLLBACK_UNKNOWN, COMMITTED_CLEANUP_FAILED }
    /** Owner-issued lifecycle fact only: no original failure, command data or application exception. */
    public static final class LifecycleException extends TransactionException {
        private final LifecycleOutcome outcome;
        private LifecycleException(LifecycleOutcome outcome){
            super(outcome==LifecycleOutcome.COMMITTED_CLEANUP_FAILED
                    ?"G014 committed transaction cleanup failed":"G014 transaction outcome is unknown");
            this.outcome=Objects.requireNonNull(outcome);
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
    private RiskPublicationBinding riskPublicationBinding;
    private boolean commandStarted;
    private final ThreadLocal<RiskPublicationWitness> riskPublication=new ThreadLocal<>();
    private record RiskPublicationBinding(DataSource alias,ApplicationEventPublisher publisher) {}
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
    /** Configuration only, after service/gate construction and before any transaction attempt. */
    public synchronized void configureRiskPublication(DataSource alias,ApplicationEventPublisher publisher){
        if(profile!=G014SqlProfile.RISK_SCENARIO || registeredGate==null || commandStarted
                || riskPublicationBinding!=null || active.get()!=null || !transactions.isEmpty()
                || alias==null || alias==source || publisher==null
                || TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()
                || TransactionSynchronizationManager.hasResource(source)
                || TransactionSynchronizationManager.hasResource(alias))
            throw new IllegalStateException("G014 publication configuration is unavailable");
        registeredGate.requireRiskPublicationConfiguration();
        riskPublicationBinding=new RiskPublicationBinding(alias,publisher);
    }
    /** The only runtime publication action. The caller supplies no callback, alias or permit. */
    public void publishRiskEvent(Object event){
        Frame frame=currentFrame();
        RiskPublicationBinding binding=riskPublicationBinding;
        try {
            if(profile!=G014SqlProfile.RISK_SCENARIO || binding==null || event==null || riskPublication.get()!=null
                    || frame.jdbc.phase()!=G014JdbcGuard.Phase.STAGING || frame.jdbc.poisoned()
                    || frame.holder.isRollbackOnly() || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                    || TransactionSynchronizationManager.hasResource(binding.alias))throw frame.jdbc.reject();
            registeredGate.requirePublication(frame,event);
            requirePublicationSynchronizationFirst();
        }catch(RuntimeException|Error failure){frame.jdbc.poison();throw publicationUnavailable();}
        RiskPublicationWitness witness=new RiskPublicationWitness(this,frame,binding,event);
        boolean bodyFailed=false;
        try {
            TransactionSynchronizationManager.bindResource(binding.alias,frame.holder);
            witness.publicationAliasBound=true;
            riskPublication.set(witness);
            witness.requireLive();binding.publisher.publishEvent(event);witness.requireLive();
        }catch(Throwable failure){bodyFailed=true;witness.reject();throw publicationUnavailable();}
        finally {
            boolean valid=false;
            try { valid=witness.detach(); }
            catch(Throwable cleanupFailure){frame.jdbc.poison();}
            finally { if(riskPublication.get()==witness)riskPublication.remove(); }
            if(!valid && !bodyFailed)throw publicationUnavailable();
        }
    }
    RiskPublicationWitness currentRiskPublicationWitness(DataSource expectedAlias,Object event){
        RiskPublicationWitness witness=riskPublication.get();
        if(witness==null)return null;
        witness.requireLive();
        if(witness.binding.alias!=expectedAlias || witness.event!=event)throw witness.reject();
        return witness;
    }
    /** A rejected native route must invalidate its source even before it has issued a child frame. */
    void rejectRiskPublicationIfActive(){
        RiskPublicationWitness witness=riskPublication.get();
        if(witness!=null)throw witness.reject();
    }
    private static IllegalStateException publicationUnavailable(){return new IllegalStateException("G014 publication boundary is unavailable");}
    /** Package-private observation capability; only this manager constructs it inside the real issuer window. */
    static final class RiskPublicationWitness {
        private final G014JdbcTransactionManager manager;
        private final Frame frame;
        private final RiskPublicationBinding binding;
        private final Object event,transaction,plan,scope,validator;
        private final ConnectionHolder holder;
        private final Thread thread=Thread.currentThread();
        private final Map<DataSource,ConnectionHolder> aliases=new IdentityHashMap<>();
        private volatile boolean closed,rejected;
        private boolean nativePublicationClaimed,publicationAliasBound;
        private RiskPublicationWitness(G014JdbcTransactionManager manager,Frame frame,RiskPublicationBinding binding,Object event){
            this.manager=manager;this.frame=frame;this.binding=binding;this.event=event;
            transaction=frame.transactionIdentity;plan=frame.plan;scope=frame.scope;validator=frame.validator;holder=frame.holder;
        }
        void requireLive(){
            try { requireLiveState(); }
            catch(Throwable failure){throw reject();}
        }
        private void requireLiveState(){
            if(closed || rejected || !publicationAliasBound || thread!=Thread.currentThread() || manager.riskPublication.get()!=this
                    || manager.riskPublicationBinding!=binding || manager.active.get()!=frame
                    || manager.transactions.get(transaction)!=frame || frame.transactionIdentity!=transaction
                    || frame.manager!=manager || frame.profile!=G014SqlProfile.RISK_SCENARIO
                    || frame.plan!=plan || frame.scope!=scope || frame.validator!=validator || frame.holder!=holder
                    || frame.jdbc.phase()!=G014JdbcGuard.Phase.STAGING || frame.jdbc.poisoned()
                    || TransactionSynchronizationManager.getResource(manager.source)!=holder
                    || TransactionSynchronizationManager.getResource(binding.alias)!=holder
                    || holder.getConnection()!=frame.jdbc.connection || !holder.isSynchronizedWithTransaction()
                    || holder.isRollbackOnly() || !TransactionSynchronizationManager.isActualTransactionActive()
                    || !TransactionSynchronizationManager.isSynchronizationActive()
                    || TransactionSynchronizationManager.isCurrentTransactionReadOnly())throw reject();
            for(var alias:aliases.entrySet())if(TransactionSynchronizationManager.getResource(alias.getKey())!=alias.getValue())throw reject();
            try { manager.requirePublicationSynchronizationFirst();manager.registeredGate.requirePublication(frame,event); }
            catch(RuntimeException|Error failure){throw reject();}
        }
        void claimNativePublication(){requireLive();if(nativePublicationClaimed)throw reject();nativePublicationClaimed=true;}
        void requireConnection(Connection connection){requireLive();if(connection!=frame.jdbc.connection)throw reject();}
        void bindTemplateAlias(DataSource alias){
            requireLive();
            if(alias==null || aliases.containsKey(alias) || TransactionSynchronizationManager.hasResource(alias))throw reject();
            TransactionSynchronizationManager.bindResource(alias,holder);aliases.put(alias,holder);
        }
        void releaseTemplateAlias(DataSource alias){
            requireLive();
            if(!aliases.containsKey(alias) || TransactionSynchronizationManager.getResource(alias)!=holder)throw reject();
            TransactionSynchronizationManager.unbindResource(alias);aliases.remove(alias);
        }
        IllegalStateException reject(){rejected=true;frame.jdbc.poison();return publicationUnavailable();}
        private boolean detach(){
            boolean valid=publicationAliasBound && !rejected && !closed && thread==Thread.currentThread()
                    && TransactionSynchronizationManager.getResource(manager.source)==holder
                    && TransactionSynchronizationManager.getResource(binding.alias)==holder;
            closed=true;
            for(var alias:aliases.entrySet()){
                if(TransactionSynchronizationManager.getResource(alias.getKey())==holder)TransactionSynchronizationManager.unbindResource(alias.getKey());
                else valid=false;
            }
            aliases.clear();
            if(publicationAliasBound){
                if(TransactionSynchronizationManager.getResource(binding.alias)==holder)TransactionSynchronizationManager.unbindResource(binding.alias);
                else valid=false;
            }
            if(!valid)frame.jdbc.poison();return valid;
        }
        @Override public String toString(){return "G014RiskPublicationWitness[redacted]";}
    }
    @Override protected Object doGetTransaction(){
        synchronized(this){commandStarted=true;}
        Frame existing=active.get();if(existing!=null)throw existing.jdbc.reject();
        // APTM may suspend an existing synchronization-only scope before doBegin.
        // Reject here so even that scope's callbacks/resources remain untouched.
        if(TransactionSynchronizationManager.isSynchronizationActive())
            throw new IllegalTransactionStateException("G014 cannot enter an existing synchronization scope");
        if(TransactionSynchronizationManager.hasResource(source) || TransactionSynchronizationManager.hasResource(source.pool))
            throw new IllegalTransactionStateException("G014 cannot enter a prebound connection scope");
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
            frame.holder=holder;frame.transactionIdentity=transaction;frame.jdbc.bind(source,holder);transactions.put(transaction,frame);
        }catch(RuntimeException|Error failure){
            LifecycleException unknown=null;boolean baselineUnverified=false;
            Frame frame=active.get();if(frame!=null){
                try {
                    frame.jdbc.poison();
                    if(!frame.jdbc.baselineVerified()){
                        baselineUnverified=true;retained.add(frame); // No mutation or rollback was sent.
                    }else{
                        unknown=rollbackAndRetainOnFailure(frame);
                        if(frame.jdbc.phase()==G014JdbcGuard.Phase.ROLLED_BACK){
                            try{frame.jdbc.cleanup();}
                            catch(SQLException|RuntimeException|Error cleanupFailure){
                                retained.add(frame);logger.warn("G014 acknowledged transaction cleanup did not complete");
                            }
                        }
                    }
                }finally{unbind(frame);}
            }
            if(unknown!=null)throw unknown;
            if(baselineUnverified)throw new CannotCreateTransactionException("G014 connection baseline is unavailable");
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
        LifecycleException helperUnknown=null;boolean committedCleanupFailed=false;
        try {
            // An already emitted unknown keeps its original reason; cleanup never compensates or replaces it.
            if(frame.unknownOutcome!=null || !frame.jdbc.baselineVerified())retained.add(frame);
            else if(frame.jdbc.phase()!=G014JdbcGuard.Phase.COMMITTED && frame.jdbc.phase()!=G014JdbcGuard.Phase.ROLLED_BACK)
                helperUnknown=rollbackAndRetainOnFailure(frame);
            if(frame.unknownOutcome==null && frame.jdbc.baselineVerified()
                    && (frame.jdbc.phase()==G014JdbcGuard.Phase.COMMITTED || frame.jdbc.phase()==G014JdbcGuard.Phase.ROLLED_BACK)){
                try{frame.jdbc.cleanup();}
                catch(SQLException|RuntimeException|Error failure){
                    retained.add(frame);logger.warn("G014 acknowledged transaction cleanup did not complete");
                    committedCleanupFailed=frame.jdbc.phase()==G014JdbcGuard.Phase.COMMITTED;
                }
            }else retained.add(frame);
        }finally{unbind(frame);}
        if(helperUnknown!=null)throw helperUnknown;
        if(committedCleanupFailed)throw new LifecycleException(LifecycleOutcome.COMMITTED_CLEANUP_FAILED);
        // Never call superclass cleanup: it resets autoCommit before proving rollback acknowledgment.
    }
    private LifecycleException rollbackAndRetainOnFailure(Frame frame){
        if(frame.unknownOutcome!=null)return unknown(frame,frame.unknownOutcome);
        if(!frame.jdbc.baselineVerified()){retained.add(frame);return null;}
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
        private Object transactionIdentity;
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
            active.set(new Frame(G014JdbcTransactionManager.this,guard));
            guard.captureBaseline();guard.establishWriteMode();return guard.connection;
        }
        @Override public Connection getConnection(String username,String password)throws SQLException {throw new SQLException("Alternate credentials are unsupported");}
        @Override public <T>T unwrap(Class<T> iface)throws SQLException {if(iface==DataSource.class)return iface.cast(this);throw new SQLException("Raw datasource access is unsupported");}
        @Override public boolean isWrapperFor(Class<?> iface)throws SQLException {if(iface==DataSource.class)return true;throw new SQLException("Raw datasource access is unsupported");}
    }
}
