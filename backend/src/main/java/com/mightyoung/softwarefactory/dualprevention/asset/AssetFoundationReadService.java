package com.mightyoung.softwarefactory.dualprevention.asset;

import com.mightyoung.softwarefactory.authorization.EntityActionAuthorization;
import com.mightyoung.softwarefactory.authorization.RegisteredEntityAction;
import com.mightyoung.softwarefactory.dualprevention.hazard.HazardSourceContracts;
import com.mightyoung.softwarefactory.identity.CapturedHumanRequest;
import com.mightyoung.softwarefactory.identity.HumanSessionAuthorityTransaction;
import java.util.Objects;
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
    public AssetFoundationReadService(AssetFoundationRepository repository,HumanSessionAuthorityTransaction authority,
            EntityActionAuthorization actions,PlatformTransactionManager readManager) {
        this.repository=Objects.requireNonNull(repository);this.authority=Objects.requireNonNull(authority);this.actions=Objects.requireNonNull(actions);
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
        if(TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isSynchronizationActive())throw new AssetFoundationFailure(503);
        request.requireCurrent();
        try { return transaction.execute(status->{
            repository.setTenant(request.context().tenantId());
            return authority.withRead(request,boundary->work.apply(actions.requireRead(request,boundary,action)));
        });
        } catch(org.springframework.transaction.TransactionException failure) {
            if(com.mightyoung.softwarefactory.dualprevention.AssetFoundationCapabilityUnavailable.causedBy(failure))throw new AssetFoundationFailure(503,failure);
            throw failure;
        }
    }
}
