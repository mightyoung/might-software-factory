package com.mightyoung.softwarefactory.dualprevention.asset;

import com.mightyoung.softwarefactory.authorization.EntityActionAuthorization;
import com.mightyoung.softwarefactory.authorization.RegisteredEntityAction;
import com.mightyoung.softwarefactory.dualprevention.hazard.HazardSourceContracts;
import com.mightyoung.softwarefactory.identity.CapturedHumanRequest;
import com.mightyoung.softwarefactory.identity.HumanSessionAuthorityTransaction;
import java.util.Objects;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import javax.sql.DataSource;
import java.sql.Connection;
import org.springframework.jdbc.datasource.ConnectionHolder;
import com.mightyoung.softwarefactory.tenancy.TenantApplicationReadRepository;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Dedicated caller-owned RO/RR graph. Its manager is deliberately separate from the command seal manager. */
public final class AssetFoundationReadService {
    private final AssetFoundationRepository repository;
    private final HumanSessionAuthorityTransaction authority;
    private final EntityActionAuthorization actions;
    private final TransactionTemplate transaction;
    private final DataSource source;
    private final TenantApplicationReadRepository applications;
    public AssetFoundationReadService(AssetFoundationRepository repository,HumanSessionAuthorityTransaction authority,
            EntityActionAuthorization actions,PlatformTransactionManager readManager,DataSource source,
            TenantApplicationReadRepository applications) {
        this.repository=Objects.requireNonNull(repository);this.authority=Objects.requireNonNull(authority);this.actions=Objects.requireNonNull(actions);
        this.source=Objects.requireNonNull(source);this.applications=Objects.requireNonNull(applications);
        transaction=new TransactionTemplate(readManager);transaction.setReadOnly(true);transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);transaction.setTimeout(10);
    }
    public AssetContracts.Workshop workshop(CapturedHumanRequest request,UUID id) {
        return read(request,RegisteredEntityAction.WORKSHOP_READ,scope->repository.workshop(scope,id));
    }
    public AssetContracts.ProductionLine productionLine(CapturedHumanRequest request,UUID id) {
        return read(request,RegisteredEntityAction.PRODUCTION_LINE_READ,scope->repository.productionLine(scope,id));
    }
    public AssetContracts.ProcessStage processStage(CapturedHumanRequest request,UUID id) {
        return read(request,RegisteredEntityAction.PROCESS_STAGE_READ,scope->repository.processStage(scope,id));
    }
    public AssetContracts.Equipment equipment(CapturedHumanRequest request,UUID id) {
        return read(request,RegisteredEntityAction.EQUIPMENT_READ,scope->repository.equipment(scope,id));
    }
    public AssetContracts.WorkUnit workUnit(CapturedHumanRequest request,UUID id) {
        return read(request,RegisteredEntityAction.WORK_UNIT_READ,scope->repository.workUnit(scope,id));
    }
    public HazardSourceContracts.Detail hazard(CapturedHumanRequest request,UUID id) {
        return read(request,RegisteredEntityAction.HAZARD_SOURCE_READ,scope->repository.hazard(scope,id));
    }
    private <T> T read(CapturedHumanRequest request,RegisteredEntityAction action,Function<EntityActionAuthorization.ActionScope,T> work) {
        return inReadSnapshot(request,snapshot->work.apply(snapshot.authorize(action)));
    }

    <T> T inReadSnapshot(CapturedHumanRequest request,Function<ReadSnapshot,T> work) {
        Objects.requireNonNull(request);Objects.requireNonNull(work);
        if(TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isSynchronizationActive()
                ||TransactionSynchronizationManager.hasResource(source))throw new AssetFoundationFailure(503);
        request.requireCurrent();
        try { return transaction.execute(status->{
            repository.setTenant(request.context().tenantId());
            return authority.withRead(request,boundary->{
                var snapshot=new ReadSnapshot(request,boundary);
                try {
                    snapshot.requireActive();
                    T result=work.apply(snapshot);
                    snapshot.requireActive();
                    return result;
                } finally { snapshot.active=false; }
            });
        });
        } catch(org.springframework.transaction.TransactionException failure) {
            if(com.mightyoung.softwarefactory.dualprevention.AssetFoundationCapabilityUnavailable.causedBy(failure))throw new AssetFoundationFailure(503,failure);
            throw failure;
        }
    }

    /** Cannot be constructed outside this issuer, reused after callback, or moved to another thread. */
    final class ReadSnapshot {
        private final CapturedHumanRequest request;
        private final HumanSessionAuthorityTransaction.ReadBoundary boundary;
        private final Thread thread=Thread.currentThread();
        private final ConnectionHolder holder;
        private final Connection connection;
        private final Set<EntityActionAuthorization.ActionScope> issued=Collections.newSetFromMap(new IdentityHashMap<>());
        private volatile boolean active=true;
        private ReadSnapshot(CapturedHumanRequest request,HumanSessionAuthorityTransaction.ReadBoundary boundary) {
            this.request=request;this.boundary=boundary;
            if(!(TransactionSynchronizationManager.getResource(source) instanceof ConnectionHolder current))throw new AssetFoundationFailure(503);
            holder=current;connection=holder.getConnection();
        }
        private AssetFoundationFailure invalidate() {
            if(active) { active=false;holder.setRollbackOnly(); }
            return new AssetFoundationFailure(503);
        }
        void requireActive() {
            if(!active)throw new AssetFoundationFailure(503);
            try {
                if(thread!=Thread.currentThread()||TransactionSynchronizationManager.getResource(source)!=holder
                    ||holder.getConnection()!=connection||holder.isRollbackOnly()||!holder.isSynchronizedWithTransaction()
                    ||!TransactionSynchronizationManager.isActualTransactionActive()
                    ||!TransactionSynchronizationManager.isSynchronizationActive()
                    ||!TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                    ||!Integer.valueOf(TransactionDefinition.ISOLATION_REPEATABLE_READ).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()))throw new AssetFoundationFailure(503);
                request.requireCurrent();
                authority.requireRead(request,boundary,source,connection);
            } catch(RuntimeException failure) { invalidate();throw failure; }
        }
        EntityActionAuthorization.ActionScope authorize(RegisteredEntityAction action) {
            requireActive();
            if(action==null||!(action.isRead()||action.action().equals("create")||action.action().equals("bind")))throw invalidate();
            var scope=actions.requireRead(request,boundary,action);
            requireActive();issued.add(scope);return scope;
        }
        long applicationVersion(EntityActionAuthorization.ActionScope scope) {
            requireActive();
            if(!issued.contains(scope))throw invalidate();
            actions.requireCurrent(scope);repository.require(scope);
            var application=applications.findCurrentInCaller(request.context().tenantId(),"safety")
                    .filter(value->value.enabled()).orElseThrow(this::invalidate);
            requireActive();actions.requireCurrent(scope);
            return application.version();
        }
    }
}
