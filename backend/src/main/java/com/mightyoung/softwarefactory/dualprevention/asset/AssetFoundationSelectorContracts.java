package com.mightyoung.softwarefactory.dualprevention.asset;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mightyoung.softwarefactory.authorization.RegisteredEntityAction;
import com.mightyoung.softwarefactory.directory.DirectoryReferenceContracts;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Closed selector inputs and wire values. A selected ID never grants an action. */
public final class AssetFoundationSelectorContracts {
    private AssetFoundationSelectorContracts() { }

    public record Request(String query, UUID afterId, int limit, UUID organizationId) {
        public Request {
            Objects.requireNonNull(query);
            if (query.codePointCount(0, query.length()) > 100 || limit < 1 || limit > 50) invalid();
            unicode(query);
        }
        DirectoryReferenceContracts.Query directoryQuery() {
            return new DirectoryReferenceContracts.Query(query, afterId, limit);
        }
    }
    public record Item(UUID id, String code, String name) {
        public Item { Objects.requireNonNull(id); Objects.requireNonNull(code); Objects.requireNonNull(name); }
    }
    public record Page(List<Item> items, @JsonInclude(JsonInclude.Include.NON_NULL) UUID nextAfterId) {
        public Page {
            items = List.copyOf(items);
            if (items.size() > 50 || new HashSet<>(items.stream().map(Item::id).toList()).size() != items.size()
                    || nextAfterId != null && (items.isEmpty() || !nextAfterId.equals(items.getLast().id()))) invalid();
        }
        static Page directory(DirectoryReferenceContracts.Page page) {
            return new Page(page.items().stream().map(i -> new Item(i.id(), i.code(), i.name())).toList(), page.nextAfterId());
        }
    }

    enum Candidate {
        WORKSHOP("workshop", RegisteredEntityAction.WORKSHOP_READ),
        LINE("production_line", RegisteredEntityAction.PRODUCTION_LINE_READ),
        STAGE("process_stage", RegisteredEntityAction.PROCESS_STAGE_READ),
        EQUIPMENT("equipment", RegisteredEntityAction.EQUIPMENT_READ),
        WORK_UNIT("work_unit", RegisteredEntityAction.WORK_UNIT_READ);
        final String table;
        final RegisteredEntityAction readAction;
        Candidate(String table, RegisteredEntityAction readAction) { this.table = table; this.readAction = readAction; }
    }
    enum Selector {
        WORKSHOP_ORGANIZATION(RegisteredEntityAction.WORKSHOP_CREATE),
        LINE_ORGANIZATION(RegisteredEntityAction.PRODUCTION_LINE_CREATE),
        STAGE_ORGANIZATION(RegisteredEntityAction.PROCESS_STAGE_CREATE),
        EQUIPMENT_ORGANIZATION(RegisteredEntityAction.EQUIPMENT_CREATE),
        WORK_UNIT_ORGANIZATION(RegisteredEntityAction.WORK_UNIT_CREATE),
        HAZARD_ORGANIZATION(RegisteredEntityAction.HAZARD_SOURCE_CREATE),
        LINE_WORKSHOP(RegisteredEntityAction.PRODUCTION_LINE_CREATE, RegisteredEntityAction.WORKSHOP_READ, Candidate.WORKSHOP),
        STAGE_LINE(RegisteredEntityAction.PROCESS_STAGE_CREATE, RegisteredEntityAction.PRODUCTION_LINE_READ, Candidate.LINE),
        HAZARD_WORK_UNIT(RegisteredEntityAction.HAZARD_SOURCE_CREATE, RegisteredEntityAction.WORK_UNIT_READ, Candidate.WORK_UNIT),
        WORK_UNIT_WORKSHOP(RegisteredEntityAction.WORK_UNIT_CREATE, RegisteredEntityAction.WORKSHOP_READ, Candidate.WORKSHOP),
        WORK_UNIT_LINE(RegisteredEntityAction.WORK_UNIT_CREATE, RegisteredEntityAction.PRODUCTION_LINE_READ, Candidate.LINE),
        WORK_UNIT_STAGE(RegisteredEntityAction.WORK_UNIT_CREATE, RegisteredEntityAction.PROCESS_STAGE_READ, Candidate.STAGE),
        WORK_UNIT_EQUIPMENT(RegisteredEntityAction.WORK_UNIT_CREATE, RegisteredEntityAction.EQUIPMENT_READ, Candidate.EQUIPMENT),
        BIND_WORKSHOP(RegisteredEntityAction.LINE_WORKSHOP_BIND, RegisteredEntityAction.WORKSHOP_READ, Candidate.WORKSHOP),
        BIND_EQUIPMENT(RegisteredEntityAction.STAGE_EQUIPMENT_BIND, RegisteredEntityAction.EQUIPMENT_READ, Candidate.EQUIPMENT),
        BIND_ORGANIZATION(RegisteredEntityAction.RESPONSIBILITY_BINDING_BIND),
        BIND_POSITION(RegisteredEntityAction.RESPONSIBILITY_BINDING_BIND);

        final RegisteredEntityAction owner;
        final RegisteredEntityAction referencedRead;
        final Candidate candidate;
        Selector(RegisteredEntityAction owner) { this(owner, null, null); }
        Selector(RegisteredEntityAction owner, RegisteredEntityAction referencedRead, Candidate candidate) {
            this.owner = owner; this.referencedRead = referencedRead; this.candidate = candidate;
        }
        boolean anchoredCreate() { return candidate != null && owner.action().equals("create"); }
        boolean bind() { return owner.action().equals("bind"); }
    }

    static void unicode(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == 0 || Character.isLowSurrogate(c)) invalid();
            if (Character.isHighSurrogate(c)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) invalid();
            }
        }
    }
    static void invalid() { throw new AssetFoundationFailure(400); }
}
