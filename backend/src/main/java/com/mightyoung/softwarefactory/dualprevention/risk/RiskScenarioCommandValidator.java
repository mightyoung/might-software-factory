package com.mightyoung.softwarefactory.dualprevention.risk;

import com.mightyoung.softwarefactory.auditops.BusinessAuditAppend;
import com.mightyoung.softwarefactory.auditops.BusinessAuditRecord;
import com.mightyoung.softwarefactory.authorization.EntityActionAuthorization;
import com.mightyoung.softwarefactory.authorization.EntityActionAuthorization.ActionScope;
import com.mightyoung.softwarefactory.authorization.RegisteredEntityAction;
import com.mightyoung.softwarefactory.directory.DirectoryReferenceReadPort;
import com.mightyoung.softwarefactory.identity.CurrentTenantContext;
import com.mightyoung.softwarefactory.support.G014SqlProfile;
import com.mightyoung.softwarefactory.support.TransactionSqlSealGate;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.ObjectMapper;
import static com.mightyoung.softwarefactory.auditops.BusinessAuditRecord.FieldCategory.*;
import static com.mightyoung.softwarefactory.authorization.RegisteredEntityAction.*;
import static com.mightyoung.softwarefactory.dualprevention.risk.RiskScenarioContracts.*;
import static com.mightyoung.softwarefactory.dualprevention.risk.RiskScenarioLockPlan.*;

/** Sole issuer of command plans. An invocation cannot recover after any observed refusal, even if a holder is reset. */
final class RiskScenarioCommandValidator implements TransactionSqlSealGate.ProfiledCommandValidator {
    static final String AUDIT_EFFECT="risk.audit",PUBLICATION_EFFECT="risk.publication";
    private final DataSource source;
    private final RiskScenarioRepository repository;
    private final DirectoryReferenceReadPort directory;
    private final BusinessAuditAppend audit;
    private final RiskScenarioEventPublisher publisher;
    private final ObjectMapper json;
    private final String listenerId;
    private final ThreadLocal<Invocation> live=new ThreadLocal<>();
    RiskScenarioCommandValidator(DataSource source,RiskScenarioRepository repository,DirectoryReferenceReadPort directory,
            BusinessAuditAppend audit,RiskScenarioEventPublisher publisher,ObjectMapper json,String listenerId){
        this.source=Objects.requireNonNull(source);this.repository=Objects.requireNonNull(repository);this.directory=Objects.requireNonNull(directory);
        this.audit=Objects.requireNonNull(audit);this.publisher=Objects.requireNonNull(publisher);this.json=Objects.requireNonNull(json);
        if(!RiskScenarioService.REQUIRED_LISTENER_ID.equals(listenerId))throw new IllegalArgumentException("A single reviewed risk listener contract is required");
        this.listenerId=listenerId;
    }
    @Override public G014SqlProfile profile(){return G014SqlProfile.RISK_SCENARIO;}
    record Intent(RegisteredEntityAction action,UUID target,Create create,Update update){
        Intent {
            if(action==RISK_SCENARIO_CREATE){if(target!=null||create==null||update!=null)throw new Failure(503);}
            else if(action==RISK_SCENARIO_UPDATE){if(target==null||create!=null||update==null)throw new Failure(503);}
            else throw new Failure(503);
        }
        boolean creating(){return action==RISK_SCENARIO_CREATE;}
        List<UUID> hazards(){return creating()?create.hazardIds():update.hazardIds();}
        long expectedVersion(){return creating()?0:update.expectedVersion();}
    }
    final class Invocation implements AutoCloseable {
        private final Thread thread=Thread.currentThread();
        private final AtomicBoolean valid=new AtomicBoolean(true);
        private Plan plan;
        private Invocation(){}
        <T>T call(Supplier<T> work){
            try{requireActive();T result=work.get();requireActive();return result;}
            catch(RuntimeException|Error failure){invalidate();throw failure;}
        }
        void requireActive(){if(thread!=Thread.currentThread()||live.get()!=this||!valid.get()){invalidate();throw new Failure(503);}}
        void invalidate(){valid.set(false);if(TransactionSynchronizationManager.getResource(source) instanceof ConnectionHolder holder)holder.setRollbackOnly();}
        @Override public void close(){invalidate();if(live.get()==this)live.remove();}
    }
    void rejectReentrant(){var existing=live.get();if(existing!=null){existing.invalidate();throw new Failure(503);}}
    Invocation begin(){
        var existing=live.get();if(existing!=null){existing.invalidate();throw new Failure(503);}
        var invocation=new Invocation();live.set(invocation);return invocation;
    }
    final class Handle {
        private final Plan plan;
        private Handle(Plan plan){this.plan=plan;}
        TransactionSqlSealGate.CommandPlan commandPlan(){requireOwnedPlan(plan);return plan;}
        CommandResult result(){requireOwnedPlan(plan);return plan.result;}
        boolean replay(){requireOwnedPlan(plan);return plan.replay;}
        byte[] responseBody(){requireOwnedPlan(plan);return plan.responseBody.clone();}
        List<RiskScenarioRepository.Statement> beforeAudit(){requireOwnedPlan(plan);if(plan.replay)throw fail(plan);return plan.statements.subList(0,plan.statements.size()-1);}
        RiskScenarioRepository.Statement receiptStatement(){requireOwnedPlan(plan);if(plan.replay)throw fail(plan);return plan.statements.getLast();}
    }
    Handle issueNew(Invocation invocation,Intent intent,CurrentTenantContext context,UUID key,String fingerprint,
            UUID requestId,UUID trace,RiskScenarioLockPlan locks,RiskScenarioRepository.Locked locked,
            Map<UUID,RiskScenarioRepository.HazardReference> references,ActionScope scope,UUID auditId){
        return invocation.call(()->{
            if(invocation.plan!=null||!scope.context().equals(context)||scope.action()!=intent.action()
                    ||!fingerprint.equals(fingerprint(context,intent))||!locks.tenantId.equals(context.tenantId())
                    ||!locks.desiredHazards.equals(intent.hazards())||locks.create!=intent.creating()
                    ||locks.expectedVersion!=intent.expectedVersion()||!references.keySet().equals(new HashSet<>(locks.unionHazards))
                    ||!locked.hazardVersions().keySet().equals(references.keySet()))throw new Failure(503);
            for(var entry:references.entrySet()){
                var reference=entry.getValue();if(!entry.getKey().equals(reference.id())||!Objects.equals(locked.hazardVersions().get(entry.getKey()),reference.version()))throw new Failure(409);
            }
            var old=locked.root();
            if(intent.creating()?old!=null:old==null||old.version()!=intent.expectedVersion()||!old.id().equals(intent.target()))throw new Failure(409);
            Instant t0=RiskScenarioContracts.instant(scope.observedAt());
            UUID organization=intent.creating()?intent.create().organizationId():old.organizationId();
            if(!scope.organizationIds().contains(organization))throw new Failure(intent.creating()?403:404);
            String code=intent.creating()?intent.create().code():old.code();
            var result=new CommandResult(locks.scenarioId,code,locks.newVersion,t0);
            byte[] responseBody=requireResponseBudget(result,requestId,trace);
            var categories=EnumSet.of(DESCRIPTION);if(intent.creating())categories.add(IDENTITY);if(intent.creating()||!locks.changes.isEmpty())categories.add(RELATIONSHIP);
            var record=new BusinessAuditRecord(auditId,locks.commandId,locks.scenarioId,intent.creating()?BusinessAuditRecord.ChangeKind.CREATE:BusinessAuditRecord.ChangeKind.UPDATE,categories,trace);
            var event=new RiskScenarioDraftChangedEvent(context.tenantId(),locks.commandId,ENTITY_KEY,locks.scenarioId,intent.action().permissionCode(),locks.newVersion,t0,trace,record.changeKind().name());
            var statements=new ArrayList<RiskScenarioRepository.Statement>();
            statements.add(intent.creating()?repository.createRoot(scope,locks.scenarioId,locks.commandId,intent.create()):repository.updateRoot(scope,locks.scenarioId,locks.commandId,intent.update()));
            statements.add(repository.rootRevision(scope,locks.scenarioId,locks.newVersion,locks.commandId));
            var oldByHazard=new HashMap<UUID,ActiveLink>();locks.oldLinks.forEach(l->oldByHazard.put(l.hazardId(),l));
            for(var change:locks.changes){
                statements.add(change.kind()==ChangeKind.NEW?repository.newLink(scope,locks.scenarioId,locks.newVersion,locks.commandId,change.id(),change.hazardId(),references.get(change.hazardId()).version()):
                        repository.endLink(scope,locks.scenarioId,locks.newVersion,locks.commandId,oldByHazard.get(change.hazardId())));
                statements.add(repository.linkRevision(scope,locks.scenarioId,locks.newVersion,locks.commandId,change));
            }
            var expectedLinks=new ArrayList<ActiveLink>();
            for(UUID hazard:locks.desiredHazards){
                var link=oldByHazard.get(hazard);
                if(link==null){var change=locks.changes.stream().filter(c->c.kind()==ChangeKind.NEW&&c.hazardId().equals(hazard)).findFirst().orElseThrow(()->new Failure(503));
                    link=new ActiveLink(context.tenantId(),change.id(),locks.scenarioId,hazard,references.get(hazard).version(),1,t0,context.actorId(),context.memberId(),locks.newVersion,locks.commandId,context.actorId(),context.memberId(),t0);}
                expectedLinks.add(link);
                statements.add(repository.snapshot(scope,locks.scenarioId,locks.newVersion,locks.commandId,link.id(),hazard,link.hazardVersionAtLink(),references.get(hazard).version()));
            }
            statements.add(repository.receiptInsert(scope,locks.commandId,key,fingerprint,result,auditId,trace));
            var expectedSnapshots=new ArrayList<RiskScenarioRepository.SnapshotIndex>();
            for(var link:expectedLinks){var ref=references.get(link.hazardId());expectedSnapshots.add(new RiskScenarioRepository.SnapshotIndex(link.hazardId(),link.id(),link.version(),link.hazardVersionAtLink(),ref.version(),ref.code(),ref.descriptionSha256(),ref.existence(),locks.commandId,context.actorId(),context.memberId(),t0));}
            expectedSnapshots.sort(Comparator.comparing(RiskScenarioRepository.SnapshotIndex::hazardId,RiskScenarioContracts::compareUuid));
            String description=intent.creating()?intent.create().description():intent.update().description();
            String triggering=intent.creating()?intent.create().triggeringConditions():intent.update().triggeringConditions();
            String exposure=intent.creating()?intent.create().exposureDescription():intent.update().exposureDescription();
            String consequence=intent.creating()?intent.create().consequence():intent.update().consequence();
            var expectedRoot=new RiskScenarioRepository.Root(context.tenantId(),locks.scenarioId,organization,code,locks.newVersion,description,triggering,exposure,consequence,
                    intent.creating()?context.actorId():old.createdByActorId(),intent.creating()?context.memberId():old.createdByMemberId(),intent.creating()?t0:old.createdAt(),context.actorId(),context.memberId(),t0,locks.commandId);
            var receipt=new RiskScenarioRepository.Receipt(locks.commandId,context.accountId(),context.memberId(),context.actorId(),intent.action().permissionCode(),fingerprint,
                    result.id(),result.version(),result.code(),result.occurredAt(),auditId,trace);
            var plan=new Plan(invocation,intent,context,key,fingerprint,organization,locks.commandId,result,scope,record,event,statements,
                    expectedRoot,canonicalOld(context.tenantId(),locks.scenarioId,expectedLinks),references,expectedSnapshots,receipt,false,responseBody);
            invocation.plan=plan;return new Handle(plan);
        });
    }
    Handle issueReplay(Invocation invocation,Intent intent,CurrentTenantContext context,UUID key,String fingerprint,
            UUID requestId,UUID trace,RiskScenarioRepository.Receipt receipt,RiskScenarioRepository.Root current,ActionScope scope){
        return invocation.call(()->{
            if(invocation.plan!=null||scope.action()!=intent.action()||!scope.context().equals(context)||!fingerprint.equals(fingerprint(context,intent))
                    ||!receipt.accountId().equals(context.accountId())||!receipt.memberId().equals(context.memberId())||!receipt.actorId().equals(context.actorId())
                    ||!receipt.actionCode().equals(intent.action().permissionCode())||!receipt.fingerprint().equals(fingerprint)
                    ||!receipt.scenarioId().equals(current.id())||!current.tenantId().equals(context.tenantId())||!receipt.code().equals(current.code())
                    ||!intent.creating()&&!intent.target().equals(receipt.scenarioId()))throw new Failure(409);
            if(!scope.organizationIds().contains(current.organizationId()))throw new Failure(404);
            byte[] responseBody=requireResponseBudget(receipt.result(),requestId,trace);
            var plan=new Plan(invocation,intent,context,key,fingerprint,current.organizationId(),receipt.commandId(),receipt.result(),scope,null,null,List.of(),null,List.of(),Map.of(),List.of(),receipt,true,responseBody);
            invocation.plan=plan;return new Handle(plan);
        });
    }
    void appendAudit(Handle handle){var plan=owned(handle);plan.invocation.call(()->{
        if(plan.replay||plan.auditReceipt!=null)throw fail(plan);
        var receipt=audit.appendSuccess(plan.stagingScope,plan.audit);
        if(receipt==null||!plan.audit.auditId().equals(audit.requireMatches(receipt,plan.stagingScope,plan.audit)))throw fail(plan);
        plan.auditReceipt=receipt;return null;
    });}
    void publish(Handle handle){var plan=owned(handle);plan.invocation.call(()->{
        if(plan.replay||plan.auditReceipt==null||plan.publicationReturned)throw fail(plan);
        publisher.publish(plan.event);plan.publicationReturned=true;return null;
    });}
    private Plan owned(Handle handle){Objects.requireNonNull(handle);requireOwnedPlan(handle.plan);return handle.plan;}
    @Override public void requireOwnedPlan(TransactionSqlSealGate.CommandPlan candidate){
        var invocation=live.get();
        // A leaked handle carries its original issuer lifetime across threads. Refusal must poison
        // that lifetime, not merely this thread's (possibly absent or unrelated) ThreadLocal entry.
        Plan issued=candidate instanceof Plan plan&&plan.owner==this?plan:null;
        try{
            if(issued==null||invocation==null)throw new Failure(503);
            issued.invocation.requireActive();invocation.requireActive();
            if(issued.invocation!=invocation||invocation.plan!=issued)throw new Failure(503);
        }catch(RuntimeException|Error failure){
            if(issued!=null)issued.invocation.invalidate();
            if(invocation!=null)invocation.invalidate();
            throw failure;
        }
    }
    @Override public Set<RegisteredEntityAction> requiredActions(TransactionSqlSealGate.CommandPlan candidate){
        requireOwnedPlan(candidate);var p=(Plan)candidate;return p.replay?Set.of(p.intent.action()):Set.of(p.intent.action(),HAZARD_SOURCE_READ);
    }
    @Override public void requireStaged(TransactionSqlSealGate.CommandPlan candidate,List<TransactionSqlSealGate.SqlExecution> executions){
        requireOwnedPlan(candidate);var p=(Plan)candidate;p.invocation.call(()->{
            if(p.replay){if(executions.size()!=1)throw fail(p);requireFlush(executions.getFirst());return null;}
            if(p.auditReceipt==null||!p.publicationReturned||executions.size()!=p.statements.size()+3)throw fail(p);
            int next=0;for(int i=0;i<p.statements.size()-1;i++)match(executions.get(next++),p.statements.get(i));
            requireAudit(executions.get(next++),p);match(executions.get(next++),p.statements.getLast());
            requirePublication(executions.get(next++),p);requireFlush(executions.get(next));return null;
        });
    }
    @Override public void requireFinal(TransactionSqlSealGate.CommandPlan candidate,EntityActionAuthorization.FinalActionScopes scopes){
        requireOwnedPlan(candidate);var p=(Plan)candidate;p.invocation.call(()->{
            var parent=scopes.require(p.intent.action());
            var root=repository.currentParent(parent,p.result.id()).orElseThrow(()->new Failure(404));
            if(!root.organizationId().equals(p.organization)||!parent.organizationIds().contains(p.organization))throw fail(p);
            var actualReceipt=repository.receipt(parent,p.key).orElseThrow(()->new Failure(503));
            if(!actualReceipt.equals(p.receipt))throw fail(p);
            if(p.replay)return null;
            if(!root.equals(p.expectedRoot))throw new Failure(409);
            if(directory.findOrganizationAt(p.context.tenantId(),p.organization,scopes.observedAt()).isEmpty())throw new Failure(p.intent.creating()?403:404);
            var target=scopes.require(HAZARD_SOURCE_READ);
            for(var ref:p.references.entrySet())if(!repository.hazardReference(target,ref.getKey()).orElseThrow(()->new Failure(404)).equals(ref.getValue()))throw new Failure(409);
            if(!repository.activeLinks(parent,p.result.id()).equals(p.expectedLinks)
                    ||!repository.snapshotIndex(parent,p.result.id(),p.result.version()).equals(p.expectedSnapshots))throw new Failure(409);
            return null;
        });
    }
    private final class Plan implements TransactionSqlSealGate.CommandPlan {
        final RiskScenarioCommandValidator owner=RiskScenarioCommandValidator.this;
        final Invocation invocation;final Intent intent;final CurrentTenantContext context;final UUID key;final String fingerprint;final UUID organization,command;
        final CommandResult result;final ActionScope stagingScope;final BusinessAuditRecord audit;final RiskScenarioDraftChangedEvent event;
        final List<RiskScenarioRepository.Statement> statements;final RiskScenarioRepository.Root expectedRoot;final List<ActiveLink> expectedLinks;
        final Map<UUID,RiskScenarioRepository.HazardReference> references;final List<RiskScenarioRepository.SnapshotIndex> expectedSnapshots;
        final RiskScenarioRepository.Receipt receipt;final boolean replay;final byte[] responseBody;
        BusinessAuditAppend.AuditAppendReceipt auditReceipt;boolean publicationReturned;
        Plan(Invocation invocation,Intent intent,CurrentTenantContext context,UUID key,String fingerprint,UUID organization,UUID command,CommandResult result,
                ActionScope stagingScope,BusinessAuditRecord audit,RiskScenarioDraftChangedEvent event,List<RiskScenarioRepository.Statement> statements,
                RiskScenarioRepository.Root expectedRoot,List<ActiveLink> expectedLinks,Map<UUID,RiskScenarioRepository.HazardReference> references,
                List<RiskScenarioRepository.SnapshotIndex> expectedSnapshots,RiskScenarioRepository.Receipt receipt,boolean replay,byte[] responseBody){
            this.invocation=invocation;this.intent=intent;this.context=context;this.key=key;this.fingerprint=fingerprint;this.organization=organization;this.command=command;
            this.result=result;this.stagingScope=stagingScope;this.audit=audit;this.event=event;this.statements=List.copyOf(statements);
            this.expectedRoot=expectedRoot;this.expectedLinks=List.copyOf(expectedLinks);this.references=Map.copyOf(references);
            this.expectedSnapshots=List.copyOf(expectedSnapshots);this.receipt=receipt;this.replay=replay;this.responseBody=responseBody.clone();
        }
        @Override public UUID commandId(){return command;}@Override public RegisteredEntityAction action(){return intent.action();}@Override public UUID targetId(){return result.id();}
    }
    private Failure fail(Plan plan){plan.invocation.invalidate();return new Failure(503);}
    private static byte[] requireResponseBudget(CommandResult result,UUID requestId,UUID trace){
        Objects.requireNonNull(requestId);Objects.requireNonNull(trace);
        return encodeResponse(new Response<>(result,new com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.Metadata(requestId.toString(),trace.toString(),SCHEMA_VERSION)),ResponseBudget.COMMAND);
    }
    static String fingerprint(CurrentTenantContext context,Intent intent){
        try{var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);var fields=new ArrayList<Object>();
            fields.add(1L);fields.add(context.tenantId());fields.add(context.actorId());fields.add(context.accountId());fields.add(context.memberId());fields.add(intent.action().permissionCode());fields.add(intent.target());
            if(intent.creating()){fields.add(intent.create().code());fields.add(intent.create().organizationId());fields.add(intent.create().description());fields.add(intent.create().triggeringConditions());fields.add(intent.create().exposureDescription());fields.add(intent.create().consequence());}
            else {fields.add(intent.update().expectedVersion());fields.add(intent.update().description());fields.add(intent.update().triggeringConditions());fields.add(intent.update().exposureDescription());fields.add(intent.update().consequence());}
            fields.add((long)intent.hazards().size());fields.addAll(intent.hazards());
            for(Object field:fields){if(field==null){out.writeByte(0);out.writeInt(-1);continue;}
                int type=field instanceof String?1:field instanceof UUID?2:field instanceof Long?3:0;if(type==0)throw new Failure(503);
                byte[] text=field.toString().getBytes(StandardCharsets.UTF_8);out.writeByte(type);out.writeInt(text.length);out.write(text);}
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        }catch(IOException|java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private static void match(TransactionSqlSealGate.SqlExecution actual,RiskScenarioRepository.Statement expected){
        if(actual.commandAcknowledgment()!=null||!expected.sql().effect.equals(actual.effect())||actual.affectedRows()!=1||actual.parameters().size()!=expected.bindings().size())throw new Failure(503);
        for(int i=0;i<expected.bindings().size();i++){Object value=expected.bindings().get(i);if(value instanceof Instant time)value=time.atOffset(ZoneOffset.UTC);
            if(!Objects.equals(value,actual.parameters().get(i+1)))throw new Failure(503);}
    }
    private static void requireFlush(TransactionSqlSealGate.SqlExecution actual){
        if(actual.commandAcknowledgment()!=TransactionSqlSealGate.CommandAcknowledgment.DEFERRED_CONSTRAINTS_IMMEDIATE||actual.effect()!=null||actual.affectedRows()!=0||!actual.parameters().isEmpty())throw new Failure(503);
    }
    private static void requireAudit(TransactionSqlSealGate.SqlExecution actual,Plan p){
        String categories=p.audit.changedFields().stream().sorted().map(x->"\""+x.name()+"\"").collect(java.util.stream.Collectors.joining(",","[","]"));
        var expected=List.of(p.context.tenantId(),p.audit.auditId(),p.context.actorId(),p.intent.action().permissionCode(),ENTITY_KEY,p.result.id(),
                p.command.toString(),p.audit.changeKind().name(),categories,p.audit.traceId().toString(),p.result.occurredAt().atOffset(ZoneOffset.UTC));
        if(!AUDIT_EFFECT.equals(actual.effect())||actual.commandAcknowledgment()!=null||actual.affectedRows()!=1||actual.parameters().size()!=expected.size())throw new Failure(503);
        for(int i=0;i<expected.size();i++)if(!expected.get(i).equals(actual.parameters().get(i+1)))throw new Failure(503);
    }
    private void requirePublication(TransactionSqlSealGate.SqlExecution actual,Plan p){
        var values=actual.parameters();
        if(!PUBLICATION_EFFECT.equals(actual.effect())||actual.commandAcknowledgment()!=null||actual.affectedRows()!=1||values.size()!=5
                ||!(values.get(1) instanceof UUID)||!RiskScenarioDraftChangedEvent.class.getName().equals(values.get(2))||!listenerId.equals(values.get(3))
                ||!(values.get(4) instanceof Instant)||!(values.get(5) instanceof String serialized)||serialized.getBytes(StandardCharsets.UTF_8).length>8192)throw new Failure(503);
        try(var parser=json.createParser(serialized)){
            if(parser.nextToken()!=JsonToken.START_OBJECT)throw new Failure(503);var fields=new HashSet<String>();
            while(parser.nextToken()!=JsonToken.END_OBJECT){
                if(parser.currentToken()!=JsonToken.PROPERTY_NAME||!fields.add(parser.currentName()))throw new Failure(503);
                String name=parser.currentName();var token=parser.nextToken();
                if(name.equals("version")?token!=JsonToken.VALUE_NUMBER_INT:token!=JsonToken.VALUE_STRING)throw new Failure(503);
            }
            if(parser.nextToken()!=null||!fields.equals(Set.of("tenantId","commandId","resourceType","resourceId","actionCode","version","occurredAt","traceId","changeKind"))
                    ||!json.valueToTree(p.event).equals(json.readTree(serialized))||!p.event.equals(json.readValue(serialized,RiskScenarioDraftChangedEvent.class)))throw new Failure(503);
        }catch(Exception invalid){throw new Failure(503);}
    }
}
