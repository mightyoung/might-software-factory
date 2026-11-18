package com.mightyoung.softwarefactory.support;

import com.mightyoung.softwarefactory.authorization.RegisteredEntityAction;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;

/** Closed construction-time identities; transport data cannot select a transaction profile. */
public enum G014SqlProfile {
    ASSET_FOUNDATION,
    RISK_SCENARIO;

    boolean permits(RegisteredEntityAction action) {
        Objects.requireNonNull(action);
        if (action.isRead()) return false;
        if (this == RISK_SCENARIO)
            return action.resourceType().equals("risk_scenario") && Set.of("create", "update").contains(action.action());
        return switch (action.resourceType()) {
            case "workshop", "production_line", "process_stage", "equipment", "work_unit" ->
                Set.of("create", "update", "archive").contains(action.action());
            case "hazard_source" -> Set.of("create", "update").contains(action.action());
            case "line_workshop", "stage_equipment", "responsibility_binding" ->
                Set.of("bind", "end").contains(action.action());
            default -> false;
        };
    }

    void requireCatalog(Collection<TransactionSqlSealGate.SqlTemplate> templates) {
        Objects.requireNonNull(templates);
        for (var template : templates)
            if (Objects.requireNonNull(template).profile() != this)
                throw new IllegalArgumentException("Mixed G014 SQL profiles");
        if (this == RISK_SCENARIO && !templates.contains(TransactionSqlSealGate.SqlTemplate.deferredConstraintsImmediate(this)))
            throw new IllegalArgumentException("Risk scenario requires its fixed deferred constraint acknowledgment");
    }
}
