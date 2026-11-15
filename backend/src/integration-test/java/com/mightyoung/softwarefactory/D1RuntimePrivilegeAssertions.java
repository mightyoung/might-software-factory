package com.mightyoung.softwarefactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;

/** D1's reviewed additions; the D0 profile continues to reject privileges on these tables. */
final class D1RuntimePrivilegeAssertions {
    static Map<String,D0RuntimePrivilegeAssertions.Rights> additionalTables() {
        var tables = new LinkedHashMap<String,D0RuntimePrivilegeAssertions.Rights>();
        tables.put("tool_custody_scope", D0RuntimePrivilegeAssertions.rights(true,false,false,false,Set.of()));
        tables.put("tool_custodian", D0RuntimePrivilegeAssertions.rights(true,false,false,false,Set.of()));
        var tool = D0RuntimePrivilegeAssertions.rights(true,false,false,false,
                Set.of("name","model","description","version","updated_at","updated_by_actor_id"));
        tool.columns().get("INSERT").addAll(Set.of("tenant_id","id","version","created_at","updated_at",
                "created_by_actor_id","updated_by_actor_id","code","name","model","description",
                "custody_scope_id","organization_id","item_state"));
        tables.put("tool", tool);
        tables.put("ledger_record_revision", D0RuntimePrivilegeAssertions.rights(true,true,false,false,Set.of()));
        tables.put("ledger_command_receipt", D0RuntimePrivilegeAssertions.rights(true,true,false,false,Set.of()));
        return tables;
    }

    static void assertApplication(JdbcTemplate jdbc) {
        D0RuntimePrivilegeAssertions.assertApplication(jdbc, additionalTables(), additionalReadColumns());
    }

    static Map<String,Set<String>> additionalReadColumns() {
        // D1's current-human consumer positively reads all control columns, including the activation role.
        return Map.of("session_authority_activation",Set.of("activated_by_database_role"));
    }
}
