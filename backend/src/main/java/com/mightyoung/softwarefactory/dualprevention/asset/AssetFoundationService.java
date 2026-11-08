package com.mightyoung.softwarefactory.dualprevention.asset;

import com.mightyoung.softwarefactory.auditops.*;
import com.mightyoung.softwarefactory.authorization.*;
import com.mightyoung.softwarefactory.directory.DirectoryReferenceReadPort;
import com.mightyoung.softwarefactory.dualprevention.hazard.HazardSourceContracts;
import com.mightyoung.softwarefactory.identity.*;
import com.mightyoung.softwarefactory.support.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import static com.mightyoung.softwarefactory.authorization.RegisteredEntityAction.*;
import static com.mightyoung.softwarefactory.dualprevention.asset.AssetContracts.*;
import static com.mightyoung.softwarefactory.auditops.BusinessAuditRecord.FieldCategory.*;

/** Concrete command owner. Plan issuance, SQL effects and final validation share a single private lifetime. */
public final class AssetFoundationService implements TransactionSqlSealGate.RegisteredCommandValidator {
    private final AssetFoundationRepository repository;
    private final EntityActionAuthorization actions;
    private final HumanSessionAuthorityTransaction authority;
    private final AuthorizationWriteGuard guard;
    private final DirectoryReferenceReadPort directory;
    private final BusinessAuditAppend audit;
    private final AssetFoundationEventPublisher publisher;
    private final TransactionTemplate transaction;
    private final TransactionSqlSealGate gate;
    private final ObjectMapper json;
    private final ThreadLocal<Plan> live=new ThreadLocal<>();
    /** Exact real Modulith listener ID is source configuration, not a transport field. */
    private final String listenerId;
    private final AssetFoundationBindingCommands bindings;

    public AssetFoundationService(G014JdbcTransactionManager manager,AssetFoundationRepository repository,
            EntityActionAuthorization actions,HumanSessionAuthorityTransaction authority,AuthorizationWriteGuard guard,
            DirectoryReferenceReadPort directory,BusinessAuditAppend audit,AssetFoundationEventPublisher publisher,
            ObjectMapper json,String listenerId) {
        this.repository=Objects.requireNonNull(repository);this.actions=Objects.requireNonNull(actions);this.authority=Objects.requireNonNull(authority);
        this.guard=Objects.requireNonNull(guard);this.directory=Objects.requireNonNull(directory);this.audit=Objects.requireNonNull(audit);
        this.publisher=Objects.requireNonNull(publisher);this.json=Objects.requireNonNull(json);this.listenerId=Objects.requireNonNull(listenerId);
        if(listenerId.isBlank())throw new IllegalArgumentException("Persistent listener required");
        transaction=new TransactionTemplate(manager);transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(10);
        var validators=new EnumMap<RegisteredEntityAction,TransactionSqlSealGate.RegisteredCommandValidator>(RegisteredEntityAction.class);
        for(var action:List.of(WORKSHOP_CREATE,WORKSHOP_UPDATE,WORKSHOP_ARCHIVE,PRODUCTION_LINE_CREATE,PRODUCTION_LINE_UPDATE,PRODUCTION_LINE_ARCHIVE,PROCESS_STAGE_CREATE,PROCESS_STAGE_UPDATE,PROCESS_STAGE_ARCHIVE,EQUIPMENT_CREATE,EQUIPMENT_UPDATE,EQUIPMENT_ARCHIVE,WORK_UNIT_CREATE,WORK_UNIT_UPDATE,WORK_UNIT_ARCHIVE,HAZARD_SOURCE_CREATE,HAZARD_SOURCE_UPDATE))validators.put(action,this);
        bindings=new AssetFoundationBindingCommands(manager,repository,actions,authority,guard,directory,audit,publisher,json,listenerId);
        for(var action:bindings.registeredActions())validators.put(action,bindings);
        gate=new TransactionSqlSealGate(manager,authority,actions,validators);bindings.attach(gate);
    }
    public static List<TransactionSqlSealGate.SqlTemplate> businessSqlTemplates() { return AssetFoundationSql.templates(); }

    public CommandResult createWorkshop(CapturedHumanRequest request,UUID key,UUID trace,WorkshopCreate command) {
        return run(request,key,trace,new Mutation(WORKSHOP_CREATE,Root.WORKSHOP,null,command.organizationId(),command.code(),0,
                nullable(command.code(),command.name(),command.organizationId(),command.boundaryDescription()),
                nullable(command.code(),command.name(),command.boundaryDescription(),Lifecycle.ACTIVE),List.of(),Set.of(IDENTITY,NAME,DESCRIPTION)));
    }
    public CommandResult updateWorkshop(CapturedHumanRequest request,UUID key,UUID trace,UUID id,WorkshopUpdate command) {
        return run(request,key,trace,new Mutation(WORKSHOP_UPDATE,Root.WORKSHOP,id,null,null,command.expectedVersion(),
                nullable(command.expectedVersion(),command.name(),command.boundaryDescription()),nullable(command.name(),command.boundaryDescription(),Lifecycle.ACTIVE),List.of(),Set.of(NAME,DESCRIPTION)));
    }
    public CommandResult archiveWorkshop(CapturedHumanRequest request,UUID key,UUID trace,UUID id,Archive command) {
        return run(request,key,trace,new Mutation(WORKSHOP_ARCHIVE,Root.WORKSHOP,id,null,null,command.expectedVersion(),
                nullable(command.expectedVersion()),List.of(),List.of(),Set.of(LIFECYCLE)));
    }
    public CommandResult createProductionLine(CapturedHumanRequest request,UUID key,UUID trace,ProductionLineCreate command) {
        return run(request,key,trace,new Mutation(PRODUCTION_LINE_CREATE,Root.LINE,null,command.organizationId(),command.code(),0,
                nullable(command.code(),command.name(),command.organizationId(),command.workshopId(),command.description()),nullable(command.code(),command.name(),command.workshopId(),command.description(),Lifecycle.ACTIVE),List.of(new Reference(Root.WORKSHOP,command.workshopId())),Set.of(IDENTITY,NAME,DESCRIPTION,RELATIONSHIP)));
    }
    public CommandResult updateProductionLine(CapturedHumanRequest request,UUID key,UUID trace,UUID id,ProductionLineUpdate command) {
        return run(request,key,trace,new Mutation(PRODUCTION_LINE_UPDATE,Root.LINE,id,null,null,command.expectedVersion(),
                nullable(command.expectedVersion(),command.name(),command.description()),nullable(command.name(),command.description(),Lifecycle.ACTIVE),List.of(),Set.of(NAME,DESCRIPTION)));
    }
    public CommandResult archiveProductionLine(CapturedHumanRequest request,UUID key,UUID trace,UUID id,Archive command) {
        return run(request,key,trace,new Mutation(PRODUCTION_LINE_ARCHIVE,Root.LINE,id,null,null,command.expectedVersion(),
                nullable(command.expectedVersion()),List.of(),List.of(),Set.of(LIFECYCLE)));
    }
    public CommandResult createProcessStage(CapturedHumanRequest request,UUID key,UUID trace,ProcessStageCreate command) {
        return run(request,key,trace,new Mutation(PROCESS_STAGE_CREATE,Root.STAGE,null,command.organizationId(),command.code(),0,
                nullable(command.code(),command.name(),command.organizationId(),command.lineId(),(long)command.ordinal(),command.description()),nullable(command.code(),command.name(),command.lineId(),command.ordinal(),command.description(),Lifecycle.ACTIVE),List.of(new Reference(Root.LINE,command.lineId())),Set.of(IDENTITY,NAME,DESCRIPTION,RELATIONSHIP,ORDERING)));
    }
    public CommandResult updateProcessStage(CapturedHumanRequest request,UUID key,UUID trace,UUID id,ProcessStageUpdate command) {
        return run(request,key,trace,new Mutation(PROCESS_STAGE_UPDATE,Root.STAGE,id,null,null,command.expectedVersion(),
                nullable(command.expectedVersion(),command.name(),(long)command.ordinal(),command.description()),nullable(command.name(),command.ordinal(),command.description(),Lifecycle.ACTIVE),List.of(),Set.of(NAME,DESCRIPTION,ORDERING)));
    }
    public CommandResult archiveProcessStage(CapturedHumanRequest request,UUID key,UUID trace,UUID id,Archive command) {
        return run(request,key,trace,new Mutation(PROCESS_STAGE_ARCHIVE,Root.STAGE,id,null,null,command.expectedVersion(),
                nullable(command.expectedVersion()),List.of(),List.of(),Set.of(LIFECYCLE)));
    }
    public CommandResult createEquipment(CapturedHumanRequest request,UUID key,UUID trace,EquipmentCreate command) {
        return run(request,key,trace,new Mutation(EQUIPMENT_CREATE,Root.EQUIPMENT,null,command.organizationId(),command.code(),0,
                nullable(command.code(),command.name(),command.organizationId(),command.typeDescription(),command.locationDescription(),command.operationalState()),nullable(command.code(),command.name(),command.typeDescription(),command.locationDescription(),command.operationalState(),Lifecycle.ACTIVE),List.of(),Set.of(IDENTITY,NAME,DESCRIPTION,OBSERVATION)));
    }
    public CommandResult updateEquipment(CapturedHumanRequest request,UUID key,UUID trace,UUID id,EquipmentUpdate command) {
        return run(request,key,trace,new Mutation(EQUIPMENT_UPDATE,Root.EQUIPMENT,id,null,null,command.expectedVersion(),
                nullable(command.expectedVersion(),command.name(),command.typeDescription(),command.locationDescription(),command.operationalState()),nullable(command.name(),command.typeDescription(),command.locationDescription(),command.operationalState(),Lifecycle.ACTIVE),List.of(),Set.of(NAME,DESCRIPTION,OBSERVATION)));
    }
    public CommandResult archiveEquipment(CapturedHumanRequest request,UUID key,UUID trace,UUID id,Archive command) {
        return run(request,key,trace,new Mutation(EQUIPMENT_ARCHIVE,Root.EQUIPMENT,id,null,null,command.expectedVersion(),
                nullable(command.expectedVersion()),List.of(),List.of(),Set.of(LIFECYCLE)));
    }
    public CommandResult createWorkUnit(CapturedHumanRequest request,UUID key,UUID trace,WorkUnitCreate command) {
        List<Reference> references=switch(command.kind()) {
            case STANDALONE->List.of();case WORKSHOP->List.of(new Reference(Root.WORKSHOP,command.workshopId()));
            case LINE->List.of(new Reference(Root.LINE,command.lineId()));case STAGE->List.of(new Reference(Root.STAGE,command.stageId()));
            case EQUIPMENT->List.of(new Reference(Root.EQUIPMENT,command.equipmentId())); };
        return run(request,key,trace,new Mutation(WORK_UNIT_CREATE,Root.WORK_UNIT,null,command.organizationId(),command.code(),0,
                nullable(command.code(),command.name(),command.organizationId(),command.kind(),command.workshopId(),command.lineId(),command.stageId(),command.equipmentId(),command.boundaryDescription()),
                nullable(command.code(),command.name(),command.kind(),command.workshopId(),command.lineId(),command.stageId(),command.equipmentId(),command.boundaryDescription(),Lifecycle.ACTIVE),references,Set.of(IDENTITY,NAME,DESCRIPTION,RELATIONSHIP)));
    }
    public CommandResult updateWorkUnit(CapturedHumanRequest request,UUID key,UUID trace,UUID id,WorkUnitUpdate command) {
        return run(request,key,trace,new Mutation(WORK_UNIT_UPDATE,Root.WORK_UNIT,id,null,null,command.expectedVersion(),
                nullable(command.expectedVersion(),command.name(),command.boundaryDescription()),nullable(command.name(),command.boundaryDescription(),Lifecycle.ACTIVE),List.of(),Set.of(NAME,DESCRIPTION)));
    }
    public CommandResult archiveWorkUnit(CapturedHumanRequest request,UUID key,UUID trace,UUID id,Archive command) {
        return run(request,key,trace,new Mutation(WORK_UNIT_ARCHIVE,Root.WORK_UNIT,id,null,null,command.expectedVersion(),
                nullable(command.expectedVersion()),List.of(),List.of(),Set.of(LIFECYCLE)));
    }
    public CommandResult createHazard(CapturedHumanRequest request,UUID key,UUID trace,HazardSourceContracts.Create command) {
        return run(request,key,trace,new Mutation(HAZARD_SOURCE_CREATE,Root.HAZARD,null,command.organizationId(),command.code(),0,
                nullable(command.code(),command.organizationId(),command.workUnitId(),command.description(),command.identifiedAt(),command.existenceState()),
                nullable(command.code(),command.workUnitId(),command.description(),command.identifiedAt(),command.existenceState(),null,null),
                List.of(new Reference(Root.WORK_UNIT,command.workUnitId())),Set.of(IDENTITY,DESCRIPTION,OBSERVATION,RELATIONSHIP)));
    }
    public CommandResult updateHazard(CapturedHumanRequest request,UUID key,UUID trace,UUID id,HazardSourceContracts.Update command) {
        return run(request,key,trace,new Mutation(HAZARD_SOURCE_UPDATE,Root.HAZARD,id,null,null,command.expectedVersion(),
                nullable(command.expectedVersion(),command.description(),command.identifiedAt(),command.existenceState()),
                nullable(command.description(),command.identifiedAt(),command.existenceState()),List.of(),Set.of(DESCRIPTION,OBSERVATION)));
    }

    public CommandResult bindLineWorkshop(CapturedHumanRequest request,UUID key,UUID trace,UUID parent,LineWorkshopBind command) { return bindings.bindLine(request,key,trace,parent,command); }
    public CommandResult endLineWorkshop(CapturedHumanRequest request,UUID key,UUID trace,UUID parent,UUID id,EndBinding command) { return bindings.end(request,key,trace,AssetFoundationBindingCommands.Kind.LINE_WORKSHOP,parent,id,command); }
    public CommandResult bindStageEquipment(CapturedHumanRequest request,UUID key,UUID trace,UUID parent,StageEquipmentBind command) { return bindings.bindStage(request,key,trace,parent,command); }
    public CommandResult endStageEquipment(CapturedHumanRequest request,UUID key,UUID trace,UUID parent,UUID id,EndBinding command) { return bindings.end(request,key,trace,AssetFoundationBindingCommands.Kind.STAGE_EQUIPMENT,parent,id,command); }
    public CommandResult bindResponsibility(CapturedHumanRequest request,UUID key,UUID trace,UUID parent,ResponsibilityBind command) { return bindings.bindResponsibility(request,key,trace,parent,command); }
    public CommandResult endResponsibility(CapturedHumanRequest request,UUID key,UUID trace,UUID parent,UUID id,EndBinding command) { return bindings.end(request,key,trace,AssetFoundationBindingCommands.Kind.RESPONSIBILITY,parent,id,command); }

    private CommandResult run(CapturedHumanRequest request,UUID key,UUID trace,Mutation mutation) {
        Objects.requireNonNull(request);Objects.requireNonNull(key);Objects.requireNonNull(trace);request.requireCurrent();
        if(live.get()!=null||TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isSynchronizationActive())throw new AssetFoundationFailure(503);
        var context=request.context();
        String fingerprint=AssetFoundationFingerprint.command(context,mutation.action,mutation.target,mutation.fingerprintFields);
        try {
            return transaction.execute(status->{
                repository.setTenant(context.tenantId());guard.forBusinessCommand(context.tenantId());
                repository.lock(AssetFoundationSql.RECEIPT_LOCK,List.of(context.tenantId(),context.actorId(),mutation.action.permissionCode(),key));
                var write=new HumanSessionAuthorityTransaction.WriteScope(request,HumanSessionAuthorityTransaction.Guard.FINDING_COMMAND,List.of(context.tenantId()));
                // This real preliminary assertion precedes receipt reads; its scopes expire before lock planning.
                // Its cut never substitutes for the post-lock staging cut or the final common cut.
                var receipt=authority.withGuarded(write,boundary->{
                    var scope=actions.requireFinalCommand(write,boundary,Set.of(mutation.action)).require(mutation.action);
                    if(mutation.create()) {
                        if(!scope.organizationIds().contains(mutation.organization))throw new AssetFoundationFailure(403);
                    } else {
                        repository.current(mutation.root.current,scope,mutation.target).orElseThrow(()->new AssetFoundationFailure(404));
                    }
                    // SQL independently scopes the receipt's actual typed result root before returning its payload.
                    return repository.receipt(scope,key).orElse(null);
                });
                UUID id=receipt!=null?receipt.id():mutation.target==null?UUID.randomUUID():mutation.target;
                UUID commandId=receipt==null?UUID.randomUUID():receipt.commandId();
                long plannedVersion=receipt!=null?receipt.version():mutation.create()?1:nextVersion(mutation.expectedVersion);
                var lockedRows=new HashMap<Reference,AssetFoundationRepository.Row>();
                if(receipt==null) {
                    if(mutation.create())repository.lock(mutation.root.natural,mutation.root==Root.STAGE?
                            nullable(context.tenantId(),mutation.code,mutation.references.getFirst().id):nullable(context.tenantId(),mutation.code));
                    repository.lock(AssetFoundationSql.MUTATION_LOCK,nullable(mutation.root.table,context.tenantId(),id,plannedVersion,commandId));
                    var rows=new TreeSet<Reference>(Comparator.<Reference>comparingInt(r->r.root.order).thenComparing(Reference::id,AssetContracts::compareUuid));
                    rows.addAll(mutation.references);
                    if(!mutation.create())rows.add(new Reference(mutation.root,id));
                    for(var row:rows)lockedRows.put(row,repository.row(row.root.lock,context.tenantId(),row.id).orElseThrow(()->new AssetFoundationFailure(404)));
                }
                AssetFoundationRepository.Row locked=receipt!=null?repository.row(mutation.root.lock,context.tenantId(),id).orElseThrow(()->new AssetFoundationFailure(404)):
                        mutation.create()?null:lockedRows.get(new Reference(mutation.root,id));
                CommandResult result=authority.withGuarded(write,boundary->{
                    var required=EnumSet.of(mutation.action);
                    // REPLAY never requires obsolete reference eligibility or the old source READ permission.
                    if(receipt==null)mutation.references.forEach(r->required.add(r.root.read));
                    var scopes=actions.requireFinalCommand(write,boundary,required);
                    var scope=scopes.require(mutation.action);
                    UUID organization=locked==null?mutation.organization:locked.organizationId();
                    if(!scope.organizationIds().contains(organization))throw new AssetFoundationFailure(mutation.create()?403:404);
                    Instant t0=stageInstant(scopes.observedAt());
                    if(receipt!=null) {
                        if(!receipt.accountId().equals(context.accountId())||!receipt.memberId().equals(context.memberId())||!receipt.fingerprint().equals(fingerprint)
                                ||!receipt.resourceType().equals(mutation.root.table)||mutation.target!=null&&!receipt.id().equals(mutation.target))throw new AssetFoundationFailure(409);
                        Plan plan=new Plan(mutation,context,key,fingerprint,organization,receipt.result(),null,null,List.of(),true,required);
                        live.set(plan);gate.bindCommand(plan,write);return receipt.result();
                    }
                    if(!mutation.create()) {
                        if(locked.version()!=mutation.expectedVersion)throw new AssetFoundationFailure(409);
                        if(mutation.root!=Root.HAZARD&&!repository.active(mutation.root.current,scope,id))throw new AssetFoundationFailure(409);
                    }
                    if(directory.findOrganizationAt(context.tenantId(),organization,t0).isEmpty())throw new AssetFoundationFailure(403);
                    for(var reference:mutation.references)if(!repository.active(reference.root.current,scopes.require(reference.root.read),reference.id))throw new AssetFoundationFailure(404);
                    long version=plannedVersion;
                    var resultValue=new CommandResult(commandId,mutation.root.table,id,version,t0);
                    var auditRecord=new BusinessAuditRecord(UUID.randomUUID(),commandId,id,BusinessAuditRecord.ChangeKind.valueOf(mutation.action.action().toUpperCase(Locale.ROOT)),mutation.categories,trace);
                    var event=new AssetFoundationChangedEvent(context.tenantId(),commandId,mutation.root.table,id,mutation.action.permissionCode(),version,t0,trace,auditRecord.changeKind().name());
                    var statements=statements(mutation,scope,context,key,fingerprint,organization,resultValue,auditRecord);
                    Plan plan=new Plan(mutation,context,key,fingerprint,organization,resultValue,auditRecord,event,statements,false,required);
                    live.set(plan);gate.bindCommand(plan,write);
                    repository.execute(scope,statements.get(0));
                    repository.execute(scope,statements.get(1));
                    var auditReceipt=audit.appendSuccess(scope,auditRecord);
                    if(!audit.requireMatches(auditReceipt,scope,auditRecord).equals(auditRecord.auditId()))throw new AssetFoundationFailure(503);
                    repository.execute(scope,statements.get(2));
                    publisher.publish(event);
                    return resultValue;
                });
                gate.finalizeCommand();return result;
            });
        } catch(org.springframework.transaction.TransactionException failure) {
            if(com.mightyoung.softwarefactory.dualprevention.AssetFoundationCapabilityUnavailable.causedBy(failure))throw new AssetFoundationFailure(503,failure);
            throw failure;
        } finally { live.remove(); }
    }
    private List<AssetFoundationRepository.Statement> statements(Mutation mutation,EntityActionAuthorization.ActionScope scope,
            CurrentTenantContext context,UUID key,String fingerprint,UUID organization,CommandResult result,BusinessAuditRecord auditRecord) {
        var bindings=new ArrayList<Object>();
        if(mutation.create()) {
            bindings.addAll(nullable(context.tenantId(),result.id(),organization,result.version(),result.occurredAt(),result.occurredAt(),context.actorId(),context.actorId(),result.commandId()));
            bindings.addAll(mutation.values);
        } else {
            if(mutation.action==WORKSHOP_ARCHIVE) {
                var old=repository.workshop(scope,result.id());bindings.addAll(nullable(old.name(),old.boundaryDescription(),Lifecycle.ARCHIVED));
            } else if(mutation.action==PRODUCTION_LINE_ARCHIVE) {
                var old=repository.productionLine(scope,result.id());bindings.addAll(nullable(old.name(),old.description(),Lifecycle.ARCHIVED));
            } else if(mutation.action==PROCESS_STAGE_ARCHIVE) {
                var old=repository.processStage(scope,result.id());bindings.addAll(nullable(old.name(),old.ordinal(),old.description(),Lifecycle.ARCHIVED));
            } else if(mutation.action==EQUIPMENT_ARCHIVE) {
                var old=repository.equipment(scope,result.id());bindings.addAll(nullable(old.name(),old.typeDescription(),old.locationDescription(),old.operationalState(),Lifecycle.ARCHIVED));
            } else if(mutation.action==WORK_UNIT_ARCHIVE) {
                var old=repository.workUnit(scope,result.id());bindings.addAll(nullable(old.name(),old.boundaryDescription(),Lifecycle.ARCHIVED));
            } else bindings.addAll(mutation.values);
            bindings.addAll(nullable(result.version(),result.occurredAt(),context.actorId(),result.commandId(),context.tenantId(),result.id(),mutation.expectedVersion));
        }
        var revision=new AssetFoundationRepository.Statement(mutation.root.revision,nullable(auditRecord.changeKind().name(),context.tenantId(),result.id(),result.version(),result.commandId()));
        var receipt=new AssetFoundationRepository.Statement(mutation.root.receipt,nullable(context.tenantId(),result.commandId(),context.actorId(),context.accountId(),context.memberId(),
                mutation.action.permissionCode(),key,1,fingerprint,result.resourceType(),result.version(),result.occurredAt(),auditRecord.auditId(),result.occurredAt(),result.id(),auditRecord.changeKind().name(),auditRecord.traceId()));
        return List.of(new AssetFoundationRepository.Statement(mutation.create()?mutation.root.insert:mutation.root.update,bindings),revision,receipt);
    }

    @Override public void requireOwnedPlan(TransactionSqlSealGate.CommandPlan candidate) {
        if(!(candidate instanceof Plan plan)||plan.owner!=this||live.get()!=plan||plan.thread!=Thread.currentThread())throw new AssetFoundationFailure(503);
    }
    @Override public Set<RegisteredEntityAction> requiredActions(TransactionSqlSealGate.CommandPlan candidate) { requireOwnedPlan(candidate);return ((Plan)candidate).required; }
    @Override public void requireStaged(TransactionSqlSealGate.CommandPlan candidate,List<TransactionSqlSealGate.SqlExecution> executions) {
        requireOwnedPlan(candidate);Plan plan=(Plan)candidate;
        if(plan.replay) { if(executions.size()!=1)throw new AssetFoundationFailure(503);requireFlush(executions.getFirst());return; }
        // Every observed effect is consumed exactly once. Extra writes, duplicated publication or unreviewed listeners fail closed.
        if(executions.size()!=6)throw new AssetFoundationFailure(503);
        match(executions.get(0),plan.statements.get(0));match(executions.get(1),plan.statements.get(1));
        requireAudit(executions.get(2),plan);match(executions.get(3),plan.statements.get(2));requirePublication(executions.get(4),plan);
        requireFlush(executions.get(5));
    }
    static void requireFlush(TransactionSqlSealGate.SqlExecution flush) {
        if(flush.commandAcknowledgment()!=TransactionSqlSealGate.CommandAcknowledgment.DEFERRED_CONSTRAINTS_IMMEDIATE||flush.effect()!=null||!flush.parameters().isEmpty()||flush.affectedRows()!=0)throw new AssetFoundationFailure(503);
    }
    static void match(TransactionSqlSealGate.SqlExecution execution,AssetFoundationRepository.Statement expected) {
        if(!expected.sql().effect.equals(execution.effect())||execution.affectedRows()!=1||execution.parameters().size()!=expected.bindings().size())throw new AssetFoundationFailure(503);
        for(int i=0;i<expected.bindings().size();i++)if(!Objects.equals(binding(expected.bindings().get(i)),execution.parameters().get(i+1)))throw new AssetFoundationFailure(503);
    }
    private static Object binding(Object value) { if(value instanceof Instant time)return time.atOffset(ZoneOffset.UTC);if(value instanceof Enum<?> e)return e.name();return value; }
    private static void requireAudit(TransactionSqlSealGate.SqlExecution execution,Plan plan) {
        requireAudit(execution,plan.context,plan.mutation.action,plan.result,plan.audit);
    }
    static void requireAudit(TransactionSqlSealGate.SqlExecution execution,CurrentTenantContext context,RegisteredEntityAction action,CommandResult result,BusinessAuditRecord record) {
        String fields=record.changedFields().stream().sorted().map(x->"\""+x.name()+"\"").collect(java.util.stream.Collectors.joining(",","[","]"));
        var expected=nullable(context.tenantId(),record.auditId(),context.actorId(),action.permissionCode(),result.resourceType(),result.id(),
                result.commandId().toString(),record.changeKind().name(),fields,record.traceId().toString(),result.occurredAt().atOffset(ZoneOffset.UTC));
        if(!"asset.audit".equals(execution.effect())||execution.affectedRows()!=1||execution.parameters().size()!=expected.size())throw new AssetFoundationFailure(503);
        for(int i=0;i<expected.size();i++)if(!Objects.equals(expected.get(i),execution.parameters().get(i+1)))throw new AssetFoundationFailure(503);
    }
    private void requirePublication(TransactionSqlSealGate.SqlExecution execution,Plan plan) {
        requirePublication(execution,plan.event,json,listenerId);
    }
    static void requirePublication(TransactionSqlSealGate.SqlExecution execution,AssetFoundationChangedEvent event,ObjectMapper json,String listenerId) {
        if(!"asset.publication".equals(execution.effect())||execution.affectedRows()!=1)throw new AssetFoundationFailure(503);
        // Exact library binding order must be supplied by the reviewed runtime catalog; no publisher-return proof.
        var values=execution.parameters();
        if(values.size()!=5||!(values.get(1) instanceof UUID)||!AssetFoundationChangedEvent.class.getName().equals(values.get(2))
                ||!listenerId.equals(values.get(3))||!(values.get(5) instanceof String serialized)
                ||!(values.get(4) instanceof Instant))throw new AssetFoundationFailure(503);
        try { if(!event.equals(json.readValue(serialized,AssetFoundationChangedEvent.class)))throw new AssetFoundationFailure(503); }
        catch(tools.jackson.core.JacksonException invalid) { throw new AssetFoundationFailure(503); }
    }
    @Override public void requireFinal(TransactionSqlSealGate.CommandPlan candidate,EntityActionAuthorization.FinalActionScopes scopes) {
        requireOwnedPlan(candidate);Plan plan=(Plan)candidate;var scope=scopes.require(plan.mutation.action);
        var current=repository.current(plan.mutation.root.current,scope,plan.result.id()).orElseThrow(()->new AssetFoundationFailure(404));
        if(!current.organizationId().equals(plan.organization))throw new AssetFoundationFailure(403);
        if(plan.replay)return;
        if(current.version()!=plan.result.version())throw new AssetFoundationFailure(409);
        if(plan.mutation.action.action().equals("archive")) {
            if(!"ARCHIVED".equals(current.lifecycle())||!repository.archiveClear(switch(plan.mutation.root) {
                case WORKSHOP->AssetFoundationSql.WORKSHOP_ARCHIVE_CLEAR;
                case LINE->AssetFoundationSql.PRODUCTION_LINE_ARCHIVE_CLEAR;
                case STAGE->AssetFoundationSql.PROCESS_STAGE_ARCHIVE_CLEAR;
                case EQUIPMENT->AssetFoundationSql.EQUIPMENT_ARCHIVE_CLEAR;
                case WORK_UNIT->AssetFoundationSql.WORK_UNIT_ARCHIVE_CLEAR;
                case HAZARD->throw new AssetFoundationFailure(503);
            },scope,plan.result.id(),scopes.observedAt()))throw new AssetFoundationFailure(409);
        }
        if(directory.findOrganizationAt(plan.context.tenantId(),plan.organization,scopes.observedAt()).isEmpty())throw new AssetFoundationFailure(403);
        for(var reference:plan.mutation.references)if(!repository.active(reference.root.current,scopes.require(reference.root.read),reference.id))throw new AssetFoundationFailure(404);
    }
    private final class Plan implements TransactionSqlSealGate.CommandPlan {
        final AssetFoundationService owner=AssetFoundationService.this;final Thread thread=Thread.currentThread();
        final Mutation mutation;final CurrentTenantContext context;final UUID key;final String fingerprint;final UUID organization;
        final CommandResult result;final BusinessAuditRecord audit;final AssetFoundationChangedEvent event;
        final List<AssetFoundationRepository.Statement> statements;final boolean replay;final Set<RegisteredEntityAction> required;
        Plan(Mutation mutation,CurrentTenantContext context,UUID key,String fingerprint,UUID organization,CommandResult result,
                BusinessAuditRecord audit,AssetFoundationChangedEvent event,List<AssetFoundationRepository.Statement> statements,boolean replay,Set<RegisteredEntityAction> required) {
            this.mutation=mutation;this.context=context;this.key=key;this.fingerprint=fingerprint;this.organization=organization;this.result=result;
            this.audit=audit;this.event=event;this.statements=List.copyOf(statements);this.replay=replay;this.required=Set.copyOf(required);
        }
        @Override public UUID commandId() { return result.commandId(); }
        @Override public RegisteredEntityAction action() { return mutation.action; }
        @Override public UUID targetId() { return result.id(); }
    }
    private record Reference(Root root,UUID id) {}
    private record Mutation(RegisteredEntityAction action,Root root,UUID target,UUID organization,String code,long expectedVersion,
                            List<Object> fingerprintFields,List<Object> values,List<Reference> references,Set<BusinessAuditRecord.FieldCategory> categories) {
        Mutation { fingerprintFields=nullable(fingerprintFields.toArray());values=nullable(values.toArray());references=List.copyOf(references);categories=Set.copyOf(categories); }
        boolean create() { return action.action().equals("create"); }
    }
    private enum Root {
        LINE("production_line",10,PRODUCTION_LINE_READ,AssetFoundationSql.PRODUCTION_LINE_LOCK,AssetFoundationSql.PRODUCTION_LINE_CURRENT,AssetFoundationSql.PRODUCTION_LINE_NATURAL,AssetFoundationSql.PRODUCTION_LINE_INSERT,AssetFoundationSql.PRODUCTION_LINE_UPDATE,AssetFoundationSql.PRODUCTION_LINE_REVISION,AssetFoundationSql.PRODUCTION_LINE_RECEIPT),
        WORKSHOP("workshop",20,WORKSHOP_READ,AssetFoundationSql.WORKSHOP_LOCK,AssetFoundationSql.WORKSHOP_CURRENT,AssetFoundationSql.WORKSHOP_NATURAL,AssetFoundationSql.WORKSHOP_INSERT,AssetFoundationSql.WORKSHOP_UPDATE,AssetFoundationSql.WORKSHOP_REVISION,AssetFoundationSql.WORKSHOP_RECEIPT),
        STAGE("process_stage",30,PROCESS_STAGE_READ,AssetFoundationSql.PROCESS_STAGE_LOCK,AssetFoundationSql.PROCESS_STAGE_CURRENT,AssetFoundationSql.PROCESS_STAGE_NATURAL,AssetFoundationSql.PROCESS_STAGE_INSERT,AssetFoundationSql.PROCESS_STAGE_UPDATE,AssetFoundationSql.PROCESS_STAGE_REVISION,AssetFoundationSql.PROCESS_STAGE_RECEIPT),
        EQUIPMENT("equipment",40,EQUIPMENT_READ,AssetFoundationSql.EQUIPMENT_LOCK,AssetFoundationSql.EQUIPMENT_CURRENT,AssetFoundationSql.EQUIPMENT_NATURAL,AssetFoundationSql.EQUIPMENT_INSERT,AssetFoundationSql.EQUIPMENT_UPDATE,AssetFoundationSql.EQUIPMENT_REVISION,AssetFoundationSql.EQUIPMENT_RECEIPT),
        WORK_UNIT("work_unit",50,WORK_UNIT_READ,AssetFoundationSql.WORK_UNIT_LOCK,AssetFoundationSql.WORK_UNIT_CURRENT,AssetFoundationSql.WORK_UNIT_NATURAL,AssetFoundationSql.WORK_UNIT_INSERT,AssetFoundationSql.WORK_UNIT_UPDATE,AssetFoundationSql.WORK_UNIT_REVISION,AssetFoundationSql.WORK_UNIT_RECEIPT),
        HAZARD("hazard_source",60,HAZARD_SOURCE_READ,AssetFoundationSql.HAZARD_SOURCE_LOCK,AssetFoundationSql.HAZARD_SOURCE_CURRENT,AssetFoundationSql.HAZARD_SOURCE_NATURAL,AssetFoundationSql.HAZARD_SOURCE_INSERT,AssetFoundationSql.HAZARD_SOURCE_UPDATE,AssetFoundationSql.HAZARD_SOURCE_REVISION,AssetFoundationSql.HAZARD_SOURCE_RECEIPT);
        final String table;final int order;final RegisteredEntityAction read;final AssetFoundationSql lock,current,natural,insert,update,revision,receipt;
        Root(String table,int order,RegisteredEntityAction read,AssetFoundationSql lock,AssetFoundationSql current,AssetFoundationSql natural,AssetFoundationSql insert,AssetFoundationSql update,AssetFoundationSql revision,AssetFoundationSql receipt) {
            this.table=table;this.order=order;this.read=read;this.lock=lock;this.current=current;this.natural=natural;this.insert=insert;this.update=update;this.revision=revision;this.receipt=receipt;
        }
    }
    private static List<Object> nullable(Object... values) { return Collections.unmodifiableList(Arrays.asList(values.clone())); }
}
