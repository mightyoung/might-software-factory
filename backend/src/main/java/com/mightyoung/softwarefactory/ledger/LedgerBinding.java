package com.mightyoung.softwarefactory.ledger;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.Condition;

/** Code-owned immutable registration. One bean per app/entity; duplicate registrations are refused. */
public record LedgerBinding(LedgerDefinition definition, Storage storage, ScopePolicy scopePolicy,
                            Map<String,ReferenceRegistration> referenceProviders) {
    public LedgerBinding { referenceProviders = Map.copyOf(referenceProviders); }
    /** Standard columns: tenant_id,id,version,created_at,updated_at,created_by_actor_id,updated_by_actor_id. */
    public record Storage(String table, Map<String,String> fieldColumns, Map<String,Object> createDefaults,
                          Set<String> derivedFields, Set<String> protectedFields) {
        public Storage {
            fieldColumns=Map.copyOf(fieldColumns); createDefaults=Map.copyOf(createDefaults);
            derivedFields=Set.copyOf(derivedFields); protectedFields=Set.copyOf(protectedFields);
        }
    }
    public interface ScopePolicy {
        /** Business predicate only; core separately ANDs tenant identity. */
        Condition visible(LedgerExecution execution);
        /** Validate supplied references/scope and return only declared server-derived domain fields. */
        Map<String,Object> prepareCreate(LedgerExecution execution, Map<String,Object> values);
        /** current is the locked current projection; revalidate state, scope and reference stability. */
        void requireUpdate(LedgerExecution execution, UUID id, Map<String,Object> current, Map<String,Object> proposed);
    }
    public record ReferenceRegistration(Set<LedgerDefinition.Use> allowedUses, Set<String> contextFields, ReferenceProvider provider) {
        public ReferenceRegistration { allowedUses=Set.copyOf(allowedUses); contextFields=Set.copyOf(contextFields); java.util.Objects.requireNonNull(provider); }
    }
    @FunctionalInterface public interface ReferenceProvider {
        LedgerContracts.ReferenceData query(LedgerExecution execution, LedgerContracts.ReferenceRequest request);
    }
}
