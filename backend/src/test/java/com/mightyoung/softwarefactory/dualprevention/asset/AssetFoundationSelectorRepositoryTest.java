package com.mightyoung.softwarefactory.dualprevention.asset;

import com.mightyoung.softwarefactory.authorization.EntityActionAuthorization.ActionScope;
import com.mightyoung.softwarefactory.authorization.RegisteredEntityAction;
import com.mightyoung.softwarefactory.identity.CurrentTenantContext;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.mightyoung.softwarefactory.dualprevention.asset.AssetFoundationSelectorContracts.*;

/** Exercises actual typed jOOQ rendering/binding, not PostgreSQL RLS or collation. */
class AssetFoundationSelectorRepositoryTest {
    private final AssetFoundationRepository owner = mock(AssetFoundationRepository.class);
    private final AssetFoundationSelectorRepository repository = new AssetFoundationSelectorRepository(owner);
    private final CurrentTenantContext context = new CurrentTenantContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, 2, 3);
    @Test void independentCandidateScopeEscapedLiteralAndCursorPrecedeLimitWithoutInMemoryScopeFiltering() {
        UUID allowed = UUID.randomUUID(), excluded = UUID.randomUUID(), after = UUID.randomUUID();
        var scope = scope(RegisteredEntityAction.WORKSHOP_READ, allowed);
        var sql = new AtomicReference<String>(); var bindings = new AtomicReference<List<Object>>();
        var ids = List.of(UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("80000000-0000-0000-0000-000000000001"), UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"));
        when(owner.connection()).thenReturn(new MockConnection(execution -> {
            sql.set(execution.sql()); bindings.set(java.util.Arrays.asList(execution.bindings()));
            var db = DSL.using(SQLDialect.POSTGRES);
            var id = DSL.field("id", UUID.class); var code = DSL.field("code", String.class); var name = DSL.field("name", String.class);
            var rows = db.newResult(id, code, name);
            for (int i = 0; i < ids.size(); i++) rows.add(db.newRecord(id, code, name).values(ids.get(i), "W" + i, "Name"));
            return new MockResult[] {new MockResult(rows.size(), rows)};
        }));
        var result = repository.candidates(Candidate.WORKSHOP, scope, new Request("a%_\\", after, 2, null), excluded);
        assertThat(result.items().stream().map(Item::id)).containsExactly(ids.get(0), ids.get(1));
        assertThat(result.nextAfterId()).isEqualTo(ids.get(1));
        assertThat(sql.get()).contains("\"tenant_id\"", "\"organization_id\"", "\"lifecycle\"", "order by \"id\" asc", "fetch next ? rows only");
        assertThat(sql.get().indexOf("\"organization_id\"")).isLessThan(sql.get().indexOf("fetch next"));
        assertThat(bindings.get()).contains(context.tenantId(), allowed, excluded, after, "ACTIVE", "%a\\%\\_\\\\%", 3L);
        assertThat(sql.get()).doesNotContain("line_workshop", "stage_equipment", "work_unit");
        verify(owner).require(scope);
    }
    @Test void parentUsesBindScopeAndActiveActualAnchorWithoutDemandingParentRead() {
        UUID org = UUID.randomUUID(), id = UUID.randomUUID(), primary = UUID.randomUUID();
        var scope = scope(RegisteredEntityAction.LINE_WORKSHOP_BIND, org);
        var sql = new AtomicReference<String>();
        when(owner.connection()).thenReturn(new MockConnection(execution -> {
            sql.set(execution.sql()); var db = DSL.using(SQLDialect.POSTGRES);
            var identifier = DSL.field("id", UUID.class); var organization = DSL.field("organization_id", UUID.class); var workshop = DSL.field("workshop_id", UUID.class);
            var rows = db.newResult(identifier, organization, workshop);
            rows.add(db.newRecord(identifier, organization, workshop).values(id, org, primary));
            return new MockResult[] {new MockResult(1, rows)};
        }));
        assertThat(repository.parent(Candidate.LINE, scope, id).primaryWorkshopId()).isEqualTo(primary);
        assertThat(sql.get()).contains("\"production_line\"", "\"organization_id\"", "\"lifecycle\"");
        verify(owner).require(scope);
    }
    @Test void wrongFixedActionIsRejectedBeforeAnySqlConnectionAccess() {
        var scope = scope(RegisteredEntityAction.EQUIPMENT_READ, UUID.randomUUID());
        assertThatThrownBy(() -> repository.candidates(Candidate.WORKSHOP, scope, new Request("", null, 20, null), null))
                .isInstanceOf(AssetFoundationFailure.class);
        verifyNoInteractions(owner);
    }
    private ActionScope scope(RegisteredEntityAction action, UUID organization) {
        var scope = mock(ActionScope.class); when(scope.action()).thenReturn(action); when(scope.context()).thenReturn(context);
        when(scope.organizationIds()).thenReturn(Set.of(organization)); return scope;
    }
}
