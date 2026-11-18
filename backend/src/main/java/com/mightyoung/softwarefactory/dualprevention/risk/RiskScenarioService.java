package com.mightyoung.softwarefactory.dualprevention.risk;

import com.mightyoung.softwarefactory.auditops.BusinessAuditAppend;
import com.mightyoung.softwarefactory.auditops.JooqBusinessAuditAppend;
import com.mightyoung.softwarefactory.authorization.AuthorizationWriteGuard;
import com.mightyoung.softwarefactory.authorization.EntityActionAuthorization;
import com.mightyoung.softwarefactory.directory.DirectoryReferenceReadPort;
import com.mightyoung.softwarefactory.identity.CapturedHumanRequest;
import com.mightyoung.softwarefactory.identity.HumanSessionAuthorityTransaction;
import com.mightyoung.softwarefactory.support.G014JdbcTransactionManager;
import com.mightyoung.softwarefactory.support.G014SqlProfile;
import com.mightyoung.softwarefactory.support.TransactionSqlSealGate;
import java.util.*;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import static com.mightyoung.softwarefactory.authorization.RegisteredEntityAction.*;
import static com.mightyoung.softwarefactory.dualprevention.risk.RiskScenarioContracts.*;
import static com.mightyoung.softwarefactory.dualprevention.risk.RiskScenarioCommandValidator.*;

/** Explicit private command graph. A contract ID is not proof that its real durable consumer is registered. */
public final class RiskScenarioService {
    public static final String REQUIRED_LISTENER_ID="risk-scenario-draft-change";
    public static final String AUDIT_EFFECT=RiskScenarioCommandValidator.AUDIT_EFFECT;
    public static final String PUBLICATION_EFFECT=RiskScenarioCommandValidator.PUBLICATION_EFFECT;
    private final RiskScenarioRepository repository;
    private final EntityActionAuthorization actions;
    private final HumanSessionAuthorityTransaction authority;
    private final AuthorizationWriteGuard guard;
    private final DirectoryReferenceReadPort directory;
    private final RiskScenarioCommandValidator validator;
    private final TransactionSqlSealGate gate;
    private final TransactionTemplate transaction;
    /** Transport uses replay to choose create201/new versus200/replay; no extra wire field is implied. */
    public record Outcome(CommandResult result,boolean replay,byte[] responseBody){
        public Outcome{Objects.requireNonNull(result);Objects.requireNonNull(responseBody);if(responseBody.length>ResponseBudget.COMMAND.bytes())throw new Failure(503);responseBody=responseBody.clone();}
        @Override public byte[] responseBody(){return responseBody.clone();}
    }

    /** Called only by the reviewed module-private composition root using its exact collaborators and catalog.
     * The root must fail closed until the unique actual persistent consumer and complete security/framework SQL exist. */
    public static RiskScenarioService compose(G014JdbcTransactionManager manager,EntityActionAuthorization actions,
            HumanSessionAuthorityTransaction authority,AuthorizationWriteGuard guard,DirectoryReferenceReadPort directory,
            BusinessAuditAppend audit,RiskScenarioEventPublisher publisher,ObjectMapper json,String listenerId){
        return new RiskScenarioService(manager,new RiskScenarioRepository(manager.commandDataSource(),actions),actions,authority,guard,directory,audit,publisher,json,listenerId);
    }
    RiskScenarioService(G014JdbcTransactionManager manager,RiskScenarioRepository repository,EntityActionAuthorization actions,
            HumanSessionAuthorityTransaction authority,AuthorizationWriteGuard guard,DirectoryReferenceReadPort directory,
            BusinessAuditAppend audit,RiskScenarioEventPublisher publisher,ObjectMapper json,String listenerId){
        this.repository=Objects.requireNonNull(repository);this.actions=Objects.requireNonNull(actions);this.authority=Objects.requireNonNull(authority);
        this.guard=Objects.requireNonNull(guard);this.directory=Objects.requireNonNull(directory);
        validator=new RiskScenarioCommandValidator(manager.commandDataSource(),repository,directory,audit,publisher,json,listenerId);
        gate=new TransactionSqlSealGate(G014SqlProfile.RISK_SCENARIO,manager,authority,actions,Map.of(RISK_SCENARIO_CREATE,validator,RISK_SCENARIO_UPDATE,validator));
        transaction=new TransactionTemplate(manager);transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);transaction.setTimeout(10);
    }
    /** Own nineteen business operations plus the exact sixteen-name constraint flush, not the full graph catalog. */
    public static List<TransactionSqlSealGate.SqlTemplate> businessSqlTemplates(){return RiskScenarioSql.templates();}
    public static TransactionSqlSealGate.SqlTemplate auditSqlTemplate(){
        return new TransactionSqlSealGate.SqlTemplate(JooqBusinessAuditAppend.commandInsertSql(),11,false,AUDIT_EFFECT,null,G014SqlProfile.RISK_SCENARIO);
    }
    public Outcome create(CapturedHumanRequest request,UUID key,UUID requestId,UUID trace,Create input){
        validator.rejectReentrant();
        return run(request,key,requestId,trace,new Intent(RISK_SCENARIO_CREATE,null,Objects.requireNonNull(input),null));
    }
    public Outcome update(CapturedHumanRequest request,UUID key,UUID requestId,UUID trace,UUID id,Update input){
        validator.rejectReentrant();
        return run(request,key,requestId,trace,new Intent(RISK_SCENARIO_UPDATE,Objects.requireNonNull(id),null,Objects.requireNonNull(input)));
    }
    private record Preliminary(RiskScenarioRepository.Receipt receipt,RiskScenarioRepository.Discovery discovery) {}
    private Outcome run(CapturedHumanRequest request,UUID key,UUID requestId,UUID trace,Intent intent){
        Objects.requireNonNull(request);Objects.requireNonNull(key);Objects.requireNonNull(requestId);Objects.requireNonNull(trace);
        try(var invocation=validator.begin()){
            return invocation.call(()->{
                if(TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isSynchronizationActive())throw new Failure(503);
                request.requireCurrent();var context=request.context();String fingerprint=fingerprint(context,intent);
                return transaction.execute(status->invocation.call(()->{
                    repository.setTenant(context.tenantId());guard.forBusinessCommand(context.tenantId());
                    repository.lockReceipt(context.tenantId(),context.actorId(),intent.action(),key);
                    var write=new HumanSessionAuthorityTransaction.WriteScope(request,HumanSessionAuthorityTransaction.Guard.FINDING_COMMAND,List.of(context.tenantId()));
                    var preliminary=invocation.call(()->authority.withGuarded(write,boundary->{
                        var scope=actions.requireFinalCommand(write,boundary,Set.of(intent.action())).require(intent.action());
                        if(intent.creating()){
                            if(!scope.organizationIds().contains(intent.create().organizationId()))throw new Failure(403);
                        }else repository.currentParent(scope,intent.target()).orElseThrow(()->new Failure(404));
                        var receipt=repository.receipt(scope,key).orElse(null);
                        if(receipt!=null)return new Preliminary(receipt,null);
                        return new Preliminary(null,intent.creating()?null:repository.discover(scope,intent.target()));
                    }));
                    RiskScenarioCommandValidator.Handle handle;
                    if(preliminary.receipt()!=null){
                        handle=invocation.call(()->authority.withGuarded(write,boundary->{
                            var scope=actions.requireFinalCommand(write,boundary,Set.of(intent.action())).require(intent.action());
                            var receipt=repository.receipt(scope,key).orElseThrow(()->new Failure(503));
                            if(!receipt.equals(preliminary.receipt()))throw new Failure(503);
                            var root=repository.currentParent(scope,receipt.scenarioId()).orElseThrow(()->new Failure(404));
                            var replay=validator.issueReplay(invocation,intent,context,key,fingerprint,requestId,trace,receipt,root,scope);
                            gate.bindCommand(replay.commandPlan(),write);return replay;
                        }));
                    }else{
                        if(!intent.creating()&&preliminary.discovery().root().version()!=intent.expectedVersion())throw new Failure(409);
                        if(!intent.creating()&&preliminary.discovery().links().isEmpty())throw new Failure(503);
                        UUID commandId=UUID.randomUUID(),id=intent.creating()?UUID.randomUUID():intent.target(),auditId=UUID.randomUUID();
                        var old=preliminary.discovery()==null?List.<RiskScenarioLockPlan.ActiveLink>of():preliminary.discovery().links();
                        var existing=new HashSet<UUID>();old.forEach(link->existing.add(link.hazardId()));
                        var newIds=new LinkedHashMap<UUID,UUID>();for(UUID hazard:intent.hazards())if(!existing.contains(hazard))newIds.put(hazard,UUID.randomUUID());
                        var locks=new RiskScenarioLockPlan(context.tenantId(),id,commandId,intent.creating(),intent.expectedVersion(),old,intent.hazards(),newIds);
                        // This callback's authority cut authorizes acquisition, not the post-lock NEW mutation.
                        var locked=invocation.call(()->authority.withGuarded(write,boundary->{
                            var parent=actions.requireFinalCommand(write,boundary,Set.of(intent.action())).require(intent.action());
                            return repository.lockAndVerify(parent,locks,preliminary.discovery(),intent.creating()?intent.create().code():null);
                        }));
                        handle=invocation.call(()->authority.withGuarded(write,boundary->{
                            var scopes=actions.requireFinalCommand(write,boundary,Set.of(intent.action(),HAZARD_SOURCE_READ));
                            var parent=scopes.require(intent.action());
                            UUID organization=intent.creating()?intent.create().organizationId():locked.root().organizationId();
                            if(!parent.organizationIds().contains(organization))throw new Failure(intent.creating()?403:404);
                            if(directory.findOrganizationAt(context.tenantId(),organization,scopes.observedAt()).isEmpty())throw new Failure(intent.creating()?403:404);
                            var references=new LinkedHashMap<UUID,RiskScenarioRepository.HazardReference>();
                            var targets=scopes.require(HAZARD_SOURCE_READ);
                            for(UUID hazard:locks.unionHazards)references.put(hazard,repository.hazardReference(targets,hazard).orElseThrow(()->new Failure(404)));
                            var fresh=validator.issueNew(invocation,intent,context,key,fingerprint,requestId,trace,locks,locked,references,parent,auditId);
                            gate.bindCommand(fresh.commandPlan(),write);
                            for(var statement:fresh.beforeAudit())repository.apply(parent,statement);
                            validator.appendAudit(fresh);
                            repository.apply(parent,fresh.receiptStatement());
                            validator.publish(fresh);return fresh;
                        }));
                    }
                    gate.finalizeCommand();return new Outcome(handle.result(),handle.replay(),handle.responseBody());
                }));
            });
        }
    }
}
