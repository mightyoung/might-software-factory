package com.mightyoung.softwarefactory.dualprevention.asset;

import com.mightyoung.softwarefactory.authorization.EntityActionAuthorization.ActionScope;
import com.mightyoung.softwarefactory.authorization.RegisteredEntityAction;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.SQLDialect;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;
import static com.mightyoung.softwarefactory.dualprevention.asset.AssetFoundationSelectorContracts.*;

/** Finite asset candidates, borrowing the existing read graph's exact holder. */
public final class AssetFoundationSelectorRepository {
    private static final Field<UUID> ID = DSL.field(DSL.name("id"), UUID.class);
    private static final Field<UUID> TENANT = DSL.field(DSL.name("tenant_id"), UUID.class);
    private static final Field<UUID> ORGANIZATION = DSL.field(DSL.name("organization_id"), UUID.class);
    private static final Field<String> CODE = DSL.field(DSL.name("code"), String.class);
    private static final Field<String> NAME = DSL.field(DSL.name("name"), String.class);
    private static final Field<String> LIFECYCLE = DSL.field(DSL.name("lifecycle"), String.class);
    private final AssetFoundationRepository owner;

    public AssetFoundationSelectorRepository(AssetFoundationRepository owner) { this.owner = Objects.requireNonNull(owner); }
    record Parent(UUID id, UUID organizationId, UUID primaryWorkshopId) { }

    Parent parent(Candidate root, ActionScope scope, UUID id) {
        RegisteredEntityAction required = switch (root) {
            case LINE -> RegisteredEntityAction.LINE_WORKSHOP_BIND;
            case STAGE -> RegisteredEntityAction.STAGE_EQUIPMENT_BIND;
            case WORK_UNIT -> RegisteredEntityAction.RESPONSIBILITY_BINDING_BIND;
            default -> throw new AssetFoundationFailure(503);
        };
        if (scope.action() != required) throw new AssetFoundationFailure(503);
        DSLContext db = database(scope);
        Field<UUID> primary = root == Candidate.LINE ? DSL.field(DSL.name("workshop_id"), UUID.class) : DSL.val((UUID) null).as("workshop_id");
        return db.select(ID, ORGANIZATION, primary).from(DSL.table(DSL.name("public", root.table)))
                .where(scoped(scope).and(ID.eq(id)).and(LIFECYCLE.eq("ACTIVE")))
                .fetchOptional(r -> new Parent(r.get(ID), r.get(ORGANIZATION), r.get(primary)))
                .orElseThrow(() -> new AssetFoundationFailure(404));
    }

    Page candidates(Candidate root, ActionScope scope, Request request, UUID excluded) {
        if (scope.action() != root.readAction) throw new AssetFoundationFailure(503);
        DSLContext db = database(scope);
        Condition condition = scoped(scope).and(LIFECYCLE.eq("ACTIVE"));
        if (excluded != null) condition = condition.and(ID.ne(excluded));
        if (request.afterId() != null) condition = condition.and(ID.gt(request.afterId()));
        String pattern = "%" + request.query().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        condition = condition.and(CODE.likeIgnoreCase(pattern, '\\').or(NAME.likeIgnoreCase(pattern, '\\')));
        List<Item> rows = db.select(ID, CODE, NAME).from(DSL.table(DSL.name("public", root.table)))
                .where(condition).orderBy(ID.asc()).limit(request.limit() + 1)
                .fetch(r -> new Item(r.get(ID), r.get(CODE), r.get(NAME)));
        boolean more = rows.size() > request.limit();
        var items = List.copyOf(rows.subList(0, Math.min(rows.size(), request.limit())));
        return new Page(items, more ? items.getLast().id() : null);
    }

    private static Condition scoped(ActionScope scope) {
        return TENANT.eq(scope.context().tenantId()).and(ORGANIZATION.in(scope.organizationIds()));
    }
    private DSLContext database(ActionScope scope) {
        owner.require(scope);
        return DSL.using(owner.connection(), SQLDialect.POSTGRES, new Settings()
                .withExecuteLogging(false).withFetchWarnings(false).withBindOffsetDateTimeType(true));
    }
}
