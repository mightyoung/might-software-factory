package com.mightyoung.softwarefactory.dualprevention.asset;

import com.mightyoung.softwarefactory.auditops.*;
import com.mightyoung.softwarefactory.authorization.*;
import com.mightyoung.softwarefactory.directory.DirectoryReferenceReadPort;
import com.mightyoung.softwarefactory.identity.*;
import com.mightyoung.softwarefactory.support.*;
import java.time.*;
import java.util.*;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import static com.mightyoung.softwarefactory.authorization.RegisteredEntityAction.*;
import static com.mightyoung.softwarefactory.dualprevention.asset.AssetContracts.*;

/** Six fixed relationship commands. The foundation service registers this owner in its one construction-time gate. */
final class AssetFoundationBindingCommands implements TransactionSqlSealGate.RegisteredCommandValidator {
    enum Kind {
        LINE_WORKSHOP("line_workshop",PRODUCTION_LINE_READ,LINE_WORKSHOP_BIND,LINE_WORKSHOP_END,10,20,
                AssetFoundationSql.PRODUCTION_LINE_LOCK,AssetFoundationSql.PRODUCTION_LINE_CURRENT,WORKSHOP_READ,AssetFoundationSql.WORKSHOP_LOCK,AssetFoundationSql.WORKSHOP_CURRENT),
        STAGE_EQUIPMENT("stage_equipment",PROCESS_STAGE_READ,STAGE_EQUIPMENT_BIND,STAGE_EQUIPMENT_END,30,40,
                AssetFoundationSql.PROCESS_STAGE_LOCK,AssetFoundationSql.PROCESS_STAGE_CURRENT,EQUIPMENT_READ,AssetFoundationSql.EQUIPMENT_LOCK,AssetFoundationSql.EQUIPMENT_CURRENT),
        RESPONSIBILITY("responsibility_binding",WORK_UNIT_READ,RESPONSIBILITY_BINDING_BIND,RESPONSIBILITY_BINDING_END,50,0,
                AssetFoundationSql.WORK_UNIT_LOCK,AssetFoundationSql.WORK_UNIT_CURRENT,null,null,null);
        final String table;final RegisteredEntityAction parentRead,bind,end,counterpartRead;
        final int parentKind,counterpartKind;final AssetFoundationSql parentLock,parentCurrent,counterpartLock,counterpartCurrent;
        final AssetFoundationSql peek,lock,current,insert,endSql,revision,receipt;
        Kind(String table,RegisteredEntityAction parentRead,RegisteredEntityAction bind,RegisteredEntityAction end,int parentKind,int counterpartKind,
             AssetFoundationSql parentLock,AssetFoundationSql parentCurrent,RegisteredEntityAction counterpartRead,AssetFoundationSql counterpartLock,AssetFoundationSql counterpartCurrent) {
            this.table=table;this.parentRead=parentRead;this.bind=bind;this.end=end;this.parentKind=parentKind;this.counterpartKind=counterpartKind;
            this.parentLock=parentLock;this.parentCurrent=parentCurrent;this.counterpartRead=counterpartRead;this.counterpartLock=counterpartLock;this.counterpartCurrent=counterpartCurrent;
            String prefix=table.toUpperCase(Locale.ROOT);
            peek=AssetFoundationSql.valueOf(prefix+"_PEEK");lock=AssetFoundationSql.valueOf(prefix+"_LOCK");current=AssetFoundationSql.valueOf(prefix+"_CURRENT");
            insert=AssetFoundationSql.valueOf(prefix+"_INSERT");endSql=AssetFoundationSql.valueOf(prefix+"_END");revision=AssetFoundationSql.valueOf(prefix+"_REVISION");receipt=AssetFoundationSql.valueOf(prefix+"_RECEIPT");
        }
    }
    private record Input(Kind kind,UUID parent,UUID target,long parentVersion,long bindingVersion,UUID counterpart,UUID position,
                         Duty duty,Instant from,Instant to,Instant endAt,List<Object> fields) {
        boolean bind() { return target==null; }
        RegisteredEntityAction action() { return bind()?kind.bind:kind.end; }
    }
    private final AssetFoundationRepository repository;
    private final EntityActionAuthorization actions;
    private final HumanSessionAuthorityTransaction authority;
    private final AuthorizationWriteGuard security;
    private final DirectoryReferenceReadPort directory;
    private final BusinessAuditAppend audit;
    private final AssetFoundationEventPublisher events;
    private final ObjectMapper json;
    private final String listener;
    private final TransactionTemplate transaction;
    private final ThreadLocal<Plan> live=new ThreadLocal<>();
    private TransactionSqlSealGate gate;

    AssetFoundationBindingCommands(G014JdbcTransactionManager manager,AssetFoundationRepository repository,EntityActionAuthorization actions,
            HumanSessionAuthorityTransaction authority,AuthorizationWriteGuard security,DirectoryReferenceReadPort directory,
            BusinessAuditAppend audit,AssetFoundationEventPublisher events,ObjectMapper json,String listener) {
        this.repository=repository;this.actions=actions;this.authority=authority;this.security=security;this.directory=directory;
        this.audit=audit;this.events=events;this.json=json;this.listener=listener;
        transaction=new TransactionTemplate(manager);transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);transaction.setTimeout(10);
    }
    void attach(TransactionSqlSealGate gate) { if(this.gate!=null)throw new IllegalStateException("Gate already attached");this.gate=Objects.requireNonNull(gate); }
    Set<RegisteredEntityAction> registeredActions() { return Set.of(LINE_WORKSHOP_BIND,LINE_WORKSHOP_END,STAGE_EQUIPMENT_BIND,STAGE_EQUIPMENT_END,RESPONSIBILITY_BINDING_BIND,RESPONSIBILITY_BINDING_END); }
    CommandResult bindLine(CapturedHumanRequest request,UUID key,UUID trace,UUID parent,LineWorkshopBind command) {
        return run(request,key,trace,new Input(Kind.LINE_WORKSHOP,parent,null,command.expectedLineVersion(),0,command.workshopId(),null,null,
                command.validFrom(),command.validTo(),null,values(command.expectedLineVersion(),command.workshopId(),command.validFrom(),command.validTo())));
    }
    CommandResult bindStage(CapturedHumanRequest request,UUID key,UUID trace,UUID parent,StageEquipmentBind command) {
        return run(request,key,trace,new Input(Kind.STAGE_EQUIPMENT,parent,null,command.expectedStageVersion(),0,command.equipmentId(),null,null,
                command.validFrom(),command.validTo(),null,values(command.expectedStageVersion(),command.equipmentId(),command.validFrom(),command.validTo())));
    }
    CommandResult bindResponsibility(CapturedHumanRequest request,UUID key,UUID trace,UUID parent,ResponsibilityBind command) {
        return run(request,key,trace,new Input(Kind.RESPONSIBILITY,parent,null,command.expectedWorkUnitVersion(),0,command.orgId(),command.positionId(),command.duty(),
                command.validFrom(),command.validTo(),null,values(command.expectedWorkUnitVersion(),command.orgId(),command.positionId(),command.duty(),command.validFrom(),command.validTo())));
    }
    CommandResult end(CapturedHumanRequest request,UUID key,UUID trace,Kind kind,UUID parent,UUID target,EndBinding command) {
        return run(request,key,trace,new Input(kind,parent,target,command.expectedParentVersion(),command.expectedBindingVersion(),null,null,null,null,null,command.endAt(),
                values(target,command.expectedParentVersion(),command.expectedBindingVersion(),command.endAt())));
    }
    private CommandResult run(CapturedHumanRequest request,UUID key,UUID trace,Input input) {
        Objects.requireNonNull(request);Objects.requireNonNull(key);Objects.requireNonNull(trace);Objects.requireNonNull(input.parent);request.requireCurrent();
        if(gate==null||live.get()!=null||TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isSynchronizationActive())throw unavailable();
        var context=request.context();var action=input.action();var kind=input.kind;
        String fingerprint=AssetFoundationFingerprint.command(context,action,input.parent,input.fields);
        try {
            return transaction.execute(status->{
                repository.setTenant(context.tenantId());security.forBusinessCommand(context.tenantId());
                repository.lock(AssetFoundationSql.RECEIPT_LOCK,List.of(context.tenantId(),context.actorId(),action.permissionCode(),key));
                var write=new HumanSessionAuthorityTransaction.WriteScope(request,HumanSessionAuthorityTransaction.Guard.FINDING_COMMAND,List.of(context.tenantId()));
                var receipt=authority.withGuarded(write,boundary->{
                    var scope=actions.requireFinalCommand(write,boundary,Set.of(action)).require(action);
                    repository.current(kind.parentCurrent,scope,input.parent).orElseThrow(()->new AssetFoundationFailure(404));
                    return repository.receipt(scope,key).orElse(null);
                });
                UUID id=receipt!=null?receipt.id():input.bind()?UUID.randomUUID():input.target;
                UUID commandId=receipt==null?UUID.randomUUID():receipt.commandId();
                long plannedVersion=receipt!=null?receipt.version():input.bind()?1:nextVersion(input.bindingVersion);
                if(receipt==null)repository.lock(AssetFoundationSql.MUTATION_LOCK,values(kind.table,context.tenantId(),id,plannedVersion,commandId));
                AssetFoundationRepository.Link preimage=receipt==null&&!input.bind()?repository.link(kind.peek,context.tenantId(),id,null):null;
                if(preimage!=null&&!input.parent.equals(preimage.parentId()))throw new AssetFoundationFailure(404);
                UUID counterpart=input.bind()?input.counterpart:preimage==null?null:preimage.counterpartId();
                var locks=new TreeSet<RowLock>(Comparator.comparingInt(RowLock::kind).thenComparing(RowLock::id,AssetContracts::compareUuid));
                var parentLock=new RowLock(kind.parentKind,input.parent,kind.parentLock);locks.add(parentLock);
                if(receipt==null&&kind.counterpartRead!=null)locks.add(new RowLock(kind.counterpartKind,counterpart,kind.counterpartLock));
                // Historical link target ranks follow the six root families; construct the complete set before acquiring any row.
                if(receipt!=null||!input.bind())locks.add(new RowLock(kind==Kind.LINE_WORKSHOP?70:kind==Kind.STAGE_EQUIPMENT?80:90,id,kind.lock));
                var rows=new HashMap<RowLock,AssetFoundationRepository.Row>();
                for(var lock:locks)rows.put(lock,repository.row(lock.sql,context.tenantId(),lock.id).orElseThrow(()->new AssetFoundationFailure(404)));
                var parent=rows.get(parentLock);
                var locked=receipt==null&&input.bind()?null:repository.link(kind.peek,context.tenantId(),id,null);
                if(locked!=null&&!input.parent.equals(locked.parentId()))throw new AssetFoundationFailure(404);
                if(receipt==null)for(var lock:locks)if(lock.kind<=50)repository.lock(AssetFoundationSql.PARENT_FENCE,values(lock.kind,context.tenantId(),lock.id,input.bind()));
                CommandResult completed=authority.withGuarded(write,boundary->{
                    var required=EnumSet.of(action);if(receipt==null&&input.bind()&&kind.counterpartRead!=null)required.add(kind.counterpartRead);
                    var scopes=actions.requireFinalCommand(write,boundary,required);var scope=scopes.require(action);
                    if(!scope.organizationIds().contains(parent.organizationId()))throw new AssetFoundationFailure(404);
                    Instant t0=stageInstant(scopes.observedAt());
                    if(receipt!=null) {
                        if(!receipt.accountId().equals(context.accountId())||!receipt.memberId().equals(context.memberId())||!receipt.fingerprint().equals(fingerprint)
                                ||!kind.table.equals(receipt.resourceType())||!input.bind()&&!input.target.equals(receipt.id()))throw new AssetFoundationFailure(409);
                        Plan plan=new Plan(input,context,parent.organizationId(),receipt.result(),null,null,List.of(),required,true,null,null);
                        live.set(plan);gate.bindCommand(plan,write);return plan.result;
                    }
                    if(parent.version()!=input.parentVersion)throw new AssetFoundationFailure(409);
                    if(input.bind()&&!repository.active(kind.parentCurrent,scope,input.parent))throw new AssetFoundationFailure(409);
                    DirectorySnapshot snapshot=null;Instant end=null;
                    if(input.bind()) {
                        if(input.to!=null&&!input.to.isAfter(t0))throw new AssetFoundationFailure(409);
                        if(kind.counterpartRead!=null&&!repository.active(kind.counterpartCurrent,scopes.require(kind.counterpartRead),counterpart))throw new AssetFoundationFailure(404);
                        if(kind==Kind.RESPONSIBILITY)snapshot=directorySnapshot(input,scope,t0);
                    } else {
                        if(locked.version()!=input.bindingVersion)throw new AssetFoundationFailure(409);
                        end=input.endAt==null?t0:input.endAt;
                        if(end.isBefore(t0)||!end.isAfter(locked.validFrom())||locked.validTo()!=null&&!end.isBefore(locked.validTo()))throw new AssetFoundationFailure(409);
                    }
                    long version=plannedVersion;
                    var result=new CommandResult(commandId,kind.table,id,version,t0);
                    var record=new BusinessAuditRecord(UUID.randomUUID(),commandId,id,input.bind()?BusinessAuditRecord.ChangeKind.BIND:BusinessAuditRecord.ChangeKind.END,
                            input.bind()?Set.of(BusinessAuditRecord.FieldCategory.PERIOD,kind==Kind.RESPONSIBILITY?BusinessAuditRecord.FieldCategory.RESPONSIBILITY:BusinessAuditRecord.FieldCategory.RELATIONSHIP):Set.of(BusinessAuditRecord.FieldCategory.PERIOD),trace);
                    var event=new AssetFoundationChangedEvent(context.tenantId(),commandId,kind.table,id,action.permissionCode(),version,t0,trace,record.changeKind().name());
                    var statements=statements(input,context,key,fingerprint,parent.organizationId(),result,record,snapshot,end);
                    Plan plan=new Plan(input,context,parent.organizationId(),result,record,event,statements,required,false,snapshot,end);
                    live.set(plan);gate.bindCommand(plan,write);
                    repository.execute(scope,statements.get(0));repository.execute(scope,statements.get(1));
                    var proof=audit.appendSuccess(scope,record);if(!record.auditId().equals(audit.requireMatches(proof,scope,record)))throw unavailable();
                    repository.execute(scope,statements.get(2));events.publish(event);
                    return result;
                });
                gate.finalizeCommand();return completed;
            });
        } catch(org.springframework.transaction.TransactionException failure) {
            if(com.mightyoung.softwarefactory.dualprevention.AssetFoundationCapabilityUnavailable.causedBy(failure))throw new AssetFoundationFailure(503,failure);
            throw failure;
        } finally { live.remove(); }
    }
    private record RowLock(int kind,UUID id,AssetFoundationSql sql) {}
    private record DirectorySnapshot(String orgCode,String orgName,String positionCode,String positionName,Instant observedAt) {}
    private DirectorySnapshot directorySnapshot(Input input,EntityActionAuthorization.ActionScope scope,Instant time) {
        if(!scope.organizationIds().contains(input.counterpart))throw new AssetFoundationFailure(403);
        var org=directory.findOrganizationAt(scope.context().tenantId(),input.counterpart,time).filter(value->value.active()&&time.equals(value.observedAt())).orElseThrow(()->new AssetFoundationFailure(404));
        var position=input.position==null?null:directory.findPositionAt(scope.context().tenantId(),input.position,time)
                .filter(value->value.active()&&time.equals(value.observedAt())&&(value.organizationId()==null||value.organizationId().equals(input.counterpart))).orElseThrow(()->new AssetFoundationFailure(404));
        return new DirectorySnapshot(org.code(),org.name(),position==null?null:position.code(),position==null?null:position.name(),time);
    }
    private List<AssetFoundationRepository.Statement> statements(Input input,CurrentTenantContext context,UUID key,String fingerprint,UUID organization,
            CommandResult result,BusinessAuditRecord audit,DirectorySnapshot snapshot,Instant end) {
        var bindings=new ArrayList<Object>();
        if(input.bind()) {
            bindings.addAll(values(context.tenantId(),result.id(),organization,1L,result.occurredAt(),result.occurredAt(),context.actorId(),context.actorId(),result.commandId(),input.parent,input.counterpart));
            if(input.kind==Kind.RESPONSIBILITY)bindings.addAll(values(input.position,input.duty));
            bindings.addAll(values(input.from,input.to,null));
            if(snapshot!=null)bindings.addAll(values(snapshot.orgCode,snapshot.orgName,snapshot.positionCode,snapshot.positionName,snapshot.observedAt));
        } else bindings.addAll(values(end,result.occurredAt(),result.version(),result.occurredAt(),context.actorId(),result.commandId(),context.tenantId(),result.id(),input.bindingVersion));
        return List.of(new AssetFoundationRepository.Statement(input.bind()?input.kind.insert:input.kind.endSql,bindings),
                new AssetFoundationRepository.Statement(input.kind.revision,values(audit.changeKind().name(),context.tenantId(),result.id(),result.version(),result.commandId())),
                new AssetFoundationRepository.Statement(input.kind.receipt,values(context.tenantId(),result.commandId(),context.actorId(),context.accountId(),context.memberId(),input.action().permissionCode(),key,1,fingerprint,input.kind.table,result.version(),result.occurredAt(),audit.auditId(),result.occurredAt(),result.id(),audit.changeKind().name(),audit.traceId())));
    }
    @Override public void requireOwnedPlan(TransactionSqlSealGate.CommandPlan candidate) {
        if(!(candidate instanceof Plan plan)||plan.owner!=this||live.get()!=plan||plan.thread!=Thread.currentThread())throw unavailable();
    }
    @Override public Set<RegisteredEntityAction> requiredActions(TransactionSqlSealGate.CommandPlan candidate) { requireOwnedPlan(candidate);return ((Plan)candidate).required; }
    @Override public void requireStaged(TransactionSqlSealGate.CommandPlan candidate,List<TransactionSqlSealGate.SqlExecution> executions) {
        requireOwnedPlan(candidate);var plan=(Plan)candidate;
        if(plan.replay) { if(executions.size()!=1)throw unavailable();AssetFoundationService.requireFlush(executions.getFirst());return; }
        if(executions.size()!=6)throw unavailable();
        AssetFoundationService.match(executions.get(0),plan.statements.get(0));AssetFoundationService.match(executions.get(1),plan.statements.get(1));
        AssetFoundationService.requireAudit(executions.get(2),plan.context,plan.input.action(),plan.result,plan.audit);
        AssetFoundationService.match(executions.get(3),plan.statements.get(2));
        AssetFoundationService.requirePublication(executions.get(4),plan.event,json,listener);AssetFoundationService.requireFlush(executions.get(5));
    }
    @Override public void requireFinal(TransactionSqlSealGate.CommandPlan candidate,EntityActionAuthorization.FinalActionScopes scopes) {
        requireOwnedPlan(candidate);var plan=(Plan)candidate;var input=plan.input;var scope=scopes.require(input.action());
        var parent=repository.current(input.kind.parentCurrent,scope,input.parent).orElseThrow(()->new AssetFoundationFailure(404));
        var current=repository.link(input.kind.current,plan.context.tenantId(),plan.result.id(),scope);
        if(!input.parent.equals(current.parentId())||!plan.organization.equals(parent.organizationId())||!plan.organization.equals(current.organizationId()))throw new AssetFoundationFailure(404);
        if(plan.replay)return;
        if(parent.version()!=input.parentVersion||current.version()!=plan.result.version())throw new AssetFoundationFailure(409);
        if(input.bind()) {
            if(!repository.active(input.kind.parentCurrent,scope,input.parent)||input.to!=null&&!input.to.isAfter(scopes.observedAt()))throw new AssetFoundationFailure(409);
            if(input.kind.counterpartRead!=null&&!repository.active(input.kind.counterpartCurrent,scopes.require(input.kind.counterpartRead),input.counterpart))throw new AssetFoundationFailure(404);
            if(input.kind==Kind.RESPONSIBILITY)directorySnapshot(input,scope,scopes.observedAt());
        } else if(!plan.end.equals(current.validTo())||!plan.result.occurredAt().equals(current.endObservedAt()))throw new AssetFoundationFailure(409);
    }
    private final class Plan implements TransactionSqlSealGate.CommandPlan {
        final AssetFoundationBindingCommands owner=AssetFoundationBindingCommands.this;final Thread thread=Thread.currentThread();
        final Input input;final CurrentTenantContext context;final UUID organization;final CommandResult result;final BusinessAuditRecord audit;
        final AssetFoundationChangedEvent event;final List<AssetFoundationRepository.Statement> statements;final Set<RegisteredEntityAction> required;
        final boolean replay;final DirectorySnapshot directory;final Instant end;
        Plan(Input input,CurrentTenantContext context,UUID organization,CommandResult result,BusinessAuditRecord audit,AssetFoundationChangedEvent event,
             List<AssetFoundationRepository.Statement> statements,Set<RegisteredEntityAction> required,boolean replay,DirectorySnapshot directory,Instant end) {
            this.input=input;this.context=context;this.organization=organization;this.result=result;this.audit=audit;this.event=event;
            this.statements=List.copyOf(statements);this.required=Set.copyOf(required);this.replay=replay;this.directory=directory;this.end=end;
        }
        public UUID commandId() { return result.commandId(); }
        public RegisteredEntityAction action() { return input.action(); }
        public UUID targetId() { return result.id(); }
    }
    private static AssetFoundationFailure unavailable() { return new AssetFoundationFailure(503); }
    private static List<Object> values(Object... values) { return Collections.unmodifiableList(Arrays.asList(values.clone())); }
}
