package com.mightyoung.softwarefactory.support;

import com.mightyoung.softwarefactory.authorization.EntityActionAuthorization;
import com.mightyoung.softwarefactory.authorization.RegisteredEntityAction;
import com.mightyoung.softwarefactory.identity.HumanSessionAuthorityTransaction;
import java.time.Instant;
import java.util.*;

/** G014-only owner of the last borrowed authority callback and its private commit seal. */
public final class TransactionSqlSealGate {
    /** Acknowledges a non-DML command, never a synthetic affected-row count. */
    public enum CommandAcknowledgment { DEFERRED_CONSTRAINTS_IMMEDIATE }
    private static final String DEFERRED_CONSTRAINTS_SQL="""
        set constraints g014_workshop_last_command_fk,
            g014_workshop_revision_receipt_fk,
            g014_receipt_workshop_result_fk,
            g014_workshop_history_complete,
            g014_production_line_last_command_fk,
            g014_production_line_revision_receipt_fk,
            g014_receipt_production_line_result_fk,
            g014_production_line_history_complete,
            g014_process_stage_last_command_fk,
            g014_process_stage_revision_receipt_fk,
            g014_receipt_process_stage_result_fk,
            g014_process_stage_history_complete,
            g014_equipment_last_command_fk,
            g014_equipment_revision_receipt_fk,
            g014_receipt_equipment_result_fk,
            g014_equipment_history_complete,
            g014_work_unit_last_command_fk,
            g014_work_unit_revision_receipt_fk,
            g014_receipt_work_unit_result_fk,
            g014_work_unit_history_complete,
            g014_hazard_source_last_command_fk,
            g014_hazard_source_revision_receipt_fk,
            g014_receipt_hazard_source_result_fk,
            g014_hazard_source_history_complete,
            g014_line_workshop_last_command_fk,
            g014_line_workshop_revision_receipt_fk,
            g014_receipt_line_workshop_result_fk,
            g014_line_workshop_history_complete,
            g014_stage_equipment_last_command_fk,
            g014_stage_equipment_revision_receipt_fk,
            g014_receipt_stage_equipment_result_fk,
            g014_stage_equipment_history_complete,
            g014_responsibility_binding_last_command_fk,
            g014_responsibility_binding_revision_receipt_fk,
            g014_receipt_responsibility_binding_result_fk,
            g014_responsibility_binding_history_complete,
            g014_line_workshop_line_label_revision_fk,
            g014_line_workshop_workshop_label_revision_fk,
            g014_stage_equipment_stage_label_revision_fk,
            g014_stage_equipment_equipment_label_revision_fk immediate
        """;
    /** Constructed once by reviewed source configuration, never registered from a request. */
    public record SqlTemplate(String sql,int parameterCount,boolean finalRead,String effect,
            CommandAcknowledgment commandAcknowledgment) {
        public SqlTemplate(String sql,int parameterCount,boolean finalRead,String effect){
            this(sql,parameterCount,finalRead,effect,null);
        }
        public SqlTemplate {
            Objects.requireNonNull(sql);
            if(sql.isBlank() || parameterCount<0 || (finalRead&&effect!=null))throw new IllegalArgumentException("Invalid finite SQL template");
            if(commandAcknowledgment!=null && (!sql.equals(DEFERRED_CONSTRAINTS_SQL)
                    || parameterCount!=0 || finalRead || effect!=null))
                throw new IllegalArgumentException("Only the fixed named constraint flush can be acknowledged");
            if(commandAcknowledgment==null && sql.stripLeading().toLowerCase(Locale.ROOT).matches("(?s)set\\s+constraints\\b.*"))
                throw new IllegalArgumentException("Constraint commands require the fixed acknowledgment template");
        }
        /** Exact V21 40-name catalog; no ALL, reset, DEFERRED or caller-provided SQL. */
        public static SqlTemplate deferredConstraintsImmediate(){
            return new SqlTemplate(DEFERRED_CONSTRAINTS_SQL,0,false,null,CommandAcknowledgment.DEFERRED_CONSTRAINTS_IMMEDIATE);
        }
    }
    /** Actual successful JDBC observation; construction is private to this package's execution guard. */
    public static final class SqlExecution {
        private final String effect;
        private final Map<Integer,Object> parameters;
        private final long affectedRows;
        private final CommandAcknowledgment commandAcknowledgment;
        SqlExecution(String effect,Map<Integer,Object> parameters,long affectedRows){
            this(effect,parameters,affectedRows,null);
        }
        SqlExecution(String effect,Map<Integer,Object> parameters,long affectedRows,CommandAcknowledgment commandAcknowledgment){
            this.effect=effect;this.parameters=copy(parameters);this.affectedRows=affectedRows;this.commandAcknowledgment=commandAcknowledgment;
        }
        public String effect(){return effect;}
        public Map<Integer,Object> parameters(){return copy(parameters);}
        /** Actual driver count; for command acknowledgments this is not a DML row count. */
        public long affectedRows(){return affectedRows;}
        public CommandAcknowledgment commandAcknowledgment(){return commandAcknowledgment;}
        private static Map<Integer,Object> copy(Map<Integer,Object> values){
            var result=new TreeMap<Integer,Object>();values.forEach((k,v)->result.put(k,v instanceof byte[] b?b.clone():v));
            return Collections.unmodifiableMap(result);
        }
        @Override public String toString(){return "G014SqlExecution[REDACTED]";}
    }
    /** Business owner implements this using an issuer-private immutable plan with locked preimages. */
    public interface CommandPlan {
        UUID commandId();
        RegisteredEntityAction action();
        UUID targetId();
    }
    /** Fixed construction-time action owner. Implementations MUST reject plans they did not issue. */
    public interface RegisteredCommandValidator {
        void requireOwnedPlan(CommandPlan plan);
        Set<RegisteredEntityAction> requiredActions(CommandPlan plan);
        /** Checks exact actual receipt/audit/publication/constraint observations and NEW/REPLAY requirements. */
        void requireStaged(CommandPlan plan,List<SqlExecution> executions);
        /** Called only inside the last real authority callback; use scopes.observedAt() for every time predicate. */
        void requireFinal(CommandPlan plan,EntityActionAuthorization.FinalActionScopes scopes);
    }
    private final G014JdbcTransactionManager manager;
    private final HumanSessionAuthorityTransaction authority;
    private final EntityActionAuthorization actions;
    private final Map<RegisteredEntityAction,RegisteredCommandValidator> validators;
    public TransactionSqlSealGate(G014JdbcTransactionManager manager,HumanSessionAuthorityTransaction authority,
            EntityActionAuthorization actions,Map<RegisteredEntityAction,RegisteredCommandValidator> validators){
        this.manager=Objects.requireNonNull(manager);this.authority=Objects.requireNonNull(authority);this.actions=Objects.requireNonNull(actions);
        this.validators=Map.copyOf(validators);
        if(this.validators.isEmpty() || this.validators.keySet().stream().anyMatch(RegisteredEntityAction::isRead))
            throw new IllegalArgumentException("Only fixed command validators may be registered");
        manager.registerGate(this);
    }
    /** Bind once before any business effect; cannot transfer this plan to another holder/transaction. */
    public void bindCommand(CommandPlan plan,HumanSessionAuthorityTransaction.WriteScope scope){
        Objects.requireNonNull(plan);Objects.requireNonNull(scope);
        var frame=manager.currentFrame();
        try {
            var validator=validators.get(plan.action());
            if(validator==null || frame.plan!=null || !frame.jdbc.executions().isEmpty()
                    || scope.guard()!=HumanSessionAuthorityTransaction.Guard.FINDING_COMMAND)throw frame.jdbc.reject();
            Objects.requireNonNull(plan.commandId());Objects.requireNonNull(plan.targetId());
            validator.requireOwnedPlan(plan);
            frame.plan=plan;frame.scope=scope;frame.validator=validator;
            frame.commandId=plan.commandId();frame.action=plan.action();frame.targetId=plan.targetId();
        }catch(RuntimeException|Error failure){frame.jdbc.poison();throw failure;}
    }
    /** No caller-supplied success callback, Instant, seal or boolean is accepted. */
    public void finalizeCommand(){
        var frame=manager.currentFrame();
        try {
            if(frame.plan==null || frame.validator!=validators.get(frame.action))throw frame.jdbc.reject();
            requirePlan(frame);
            // The staging authority callback has returned normally, including its SQL postvalidation.
            // This fixed transaction control must precede the irreversible FINAL read-only phase.
            frame.jdbc.flushDeferredConstraintsIfRegistered();
            frame.validator.requireStaged(frame.plan,frame.jdbc.executions());
            Set<RegisteredEntityAction> required=Set.copyOf(frame.validator.requiredActions(frame.plan));
            if(!required.contains(frame.action))throw frame.jdbc.reject();
            frame.jdbc.beginFinal();
            FinalObservation[] invocation=new FinalObservation[1];
            var returned=authority.withGuarded(frame.scope,boundary->{
                if(invocation[0]!=null)throw frame.jdbc.reject();
                var current=actions.requireFinalCommand(frame.scope,boundary,required);
                frame.validator.requireFinal(frame.plan,current);
                requirePlan(frame);
                invocation[0]=new FinalObservation(Objects.requireNonNull(current.observedAt()));
                return invocation[0];
            });
            if(returned==null || returned!=invocation[0])throw frame.jdbc.reject();
            // The real authority owner's postvalidation and callback closure have now returned normally.
            // No boundary getter/assertion may be reopened here.
            frame.jdbc.sealAfterNormalReturn();
            frame.finalSeal=new FinalSeal(frame,frame.plan,frame.scope,returned.observedAt);
        }catch(RuntimeException|Error failure){frame.jdbc.poison();throw failure;}
    }
    private static void requirePlan(G014JdbcTransactionManager.Frame frame){
        frame.validator.requireOwnedPlan(frame.plan);
        if(!frame.commandId.equals(frame.plan.commandId()) || frame.action!=frame.plan.action() || !frame.targetId.equals(frame.plan.targetId()))
            throw frame.jdbc.reject();
    }
    /** Memory-only check; never invokes a plan, validator, authority callback or driver getter. */
    void requireSeal(G014JdbcTransactionManager.Frame frame){
        if(!(frame.finalSeal instanceof FinalSeal seal) || seal.owner!=this || seal.frame!=frame
                || seal.plan!=frame.plan || seal.scope!=frame.scope)throw frame.jdbc.reject();
    }
    private final class FinalSeal {
        final TransactionSqlSealGate owner=TransactionSqlSealGate.this;
        final G014JdbcTransactionManager.Frame frame;
        final CommandPlan plan;
        final HumanSessionAuthorityTransaction.WriteScope scope;
        final Instant observedAt;
        private FinalSeal(G014JdbcTransactionManager.Frame frame,CommandPlan plan,HumanSessionAuthorityTransaction.WriteScope scope,Instant observedAt){
            this.frame=frame;this.plan=plan;this.scope=scope;this.observedAt=observedAt;
        }
    }
    private record FinalObservation(Instant observedAt) {}
}
