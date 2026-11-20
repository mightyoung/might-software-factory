package com.mightyoung.softwarefactory.platformadmin;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import static com.mightyoung.softwarefactory.platformadmin.LedgerConfigurationModels.*;
import static com.mightyoung.softwarefactory.platformadmin.LedgerConfigurationException.Reason.*;

@Repository
public final class JooqLedgerConfigurationRepository implements LedgerConfigurationRepository {
    private static final String SNAPSHOT_COLUMNS = "s.configuration_key,s.version,c.published_version,s.definition::text,s.saved_at,s.source_version,s.actor_member_id";
    private final DSLContext dsl;
    private final ObjectMapper json;
    private final TransactionTemplate read;

    public JooqLedgerConfigurationRepository(DSLContext dsl, ObjectMapper json, PlatformTransactionManager manager) {
        this.dsl = dsl;
        this.json = json;
        read = new TransactionTemplate(manager);
        read.setReadOnly(true);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    @Override public List<Item> list(UUID tenant) {
        return reading(tenant, () -> dsl.fetch("select " + SNAPSHOT_COLUMNS
                + " from public.ledger_configuration c join public.ledger_configuration_snapshot s"
                + " on s.tenant_id=c.tenant_id and s.configuration_key=c.configuration_key and s.version=c.latest_version"
                + " where c.tenant_id=? order by c.configuration_key", tenant).map(row -> {
                    Snapshot s = snapshot(row);
                    return new Item(s.key(), s.version(), s.publishedVersion(), s.definition(), s.savedAt(), s.sourceVersion(), s.savedBy());
                }));
    }
    @Override public List<Snapshot> versions(UUID tenant, String key) {
        return reading(tenant, () -> {
            List<Snapshot> results = dsl.fetch("select " + SNAPSHOT_COLUMNS
                    + " from public.ledger_configuration c join public.ledger_configuration_snapshot s"
                    + " on s.tenant_id=c.tenant_id and s.configuration_key=c.configuration_key"
                    + " where c.tenant_id=? and c.configuration_key=? order by s.version desc", tenant, key).map(this::snapshot);
            if (results.isEmpty()) throw new LedgerConfigurationException(NOT_FOUND);
            return results;
        });
    }
    @Override public Snapshot save(UUID tenant, UUID actor, String key, SaveRequest request) {
        scope(tenant);
        dsl.execute("insert into public.ledger_configuration(tenant_id,configuration_key) values (?,?) on conflict do nothing", tenant, key);
        Record head = head(tenant, key);
        String payload = json.writeValueAsString(request);
        Snapshot replay = replay(tenant, key, request.requestId(), payload);
        if (replay != null) return replay;
        expected(head, request.expectedVersion());
        return append(tenant, actor, key, request.expectedVersion() + 1, null, request.requestId(), payload, request.definition());
    }
    @Override public Snapshot restore(UUID tenant, UUID actor, String key, RestoreRequest request) {
        scope(tenant);
        Record head = head(tenant, key);
        String payload = json.writeValueAsString(request);
        Snapshot replay = replay(tenant, key, request.requestId(), payload);
        if (replay != null) return replay;
        expected(head, request.expectedVersion());
        Snapshot source = load(tenant, key, request.sourceVersion());
        return append(tenant, actor, key, request.expectedVersion() + 1, request.sourceVersion(), request.requestId(), payload, source.definition());
    }
    @Override public Snapshot publish(UUID tenant, UUID actor, String key, PublishRequest request) {
        scope(tenant);
        Record head = head(tenant, key);
        expected(head, request.expectedVersion());
        Snapshot selected = load(tenant, key, request.version());
        Long current = head.get("published_version", Long.class);
        // An identical publish retried after a lost response has no additional effect.
        if (Objects.equals(current, request.version())) return selected;
        if (!Objects.equals(current, request.expectedPublishedVersion())) throw new LedgerConfigurationException(VERSION_CONFLICT);
        LedgerConfigurationService.validDefinition(selected.definition(), true);
        dsl.execute("insert into public.ledger_configuration_publication(tenant_id,configuration_key,id,version,actor_member_id) values (?,?,?,?,?)",
                tenant, key, UUID.randomUUID(), request.version(), actor);
        dsl.execute("update public.ledger_configuration set published_version=? where tenant_id=? and configuration_key=?", request.version(), tenant, key);
        return load(tenant, key, request.version());
    }
    private Snapshot append(UUID tenant, UUID actor, String key, long version, Long source, UUID requestId, String payload, Definition definition) {
        dsl.execute("insert into public.ledger_configuration_snapshot(tenant_id,configuration_key,version,source_version,request_id,request_payload,definition,actor_member_id)"
                + " values (?,?,?,?,?,cast(? as jsonb),cast(? as jsonb),?)", tenant, key, version, source, requestId, payload, json.writeValueAsString(definition), actor);
        dsl.execute("update public.ledger_configuration set latest_version=? where tenant_id=? and configuration_key=?", version, tenant, key);
        return load(tenant, key, version);
    }
    private Snapshot replay(UUID tenant, String key, UUID request, String payload) {
        Record row = dsl.fetchOne("select version,request_payload=cast(? as jsonb) as matches from public.ledger_configuration_snapshot"
                + " where tenant_id=? and configuration_key=? and request_id=?", payload, tenant, key, request);
        if (row == null) return null;
        if (!Boolean.TRUE.equals(row.get("matches", Boolean.class))) throw new LedgerConfigurationException(REQUEST_CONFLICT);
        return load(tenant, key, row.get("version", Long.class));
    }
    private Record head(UUID tenant, String key) {
        Record head = dsl.fetchOne("select latest_version,published_version from public.ledger_configuration where tenant_id=? and configuration_key=? for update", tenant, key);
        if (head == null) throw new LedgerConfigurationException(NOT_FOUND);
        return head;
    }
    private static void expected(Record head, long version) {
        if (head.get("latest_version", Long.class) != version) throw new LedgerConfigurationException(VERSION_CONFLICT);
    }
    private Snapshot load(UUID tenant, String key, long version) {
        Record row = dsl.fetchOne("select " + SNAPSHOT_COLUMNS + " from public.ledger_configuration c join public.ledger_configuration_snapshot s"
                + " on s.tenant_id=c.tenant_id and s.configuration_key=c.configuration_key where c.tenant_id=? and c.configuration_key=? and s.version=?", tenant, key, version);
        if (row == null) throw new LedgerConfigurationException(NOT_FOUND);
        return snapshot(row);
    }
    private Snapshot snapshot(Record row) {
        return new Snapshot(row.get(0, String.class), row.get(1, Long.class), row.get(2, Long.class),
                json.readValue(row.get(3, String.class), Definition.class), row.get(4, OffsetDateTime.class).toInstant(), row.get(5, Long.class), row.get(6, UUID.class));
    }
    private <T> T reading(UUID tenant, Supplier<T> work) {
        return read.execute(status -> { scope(tenant); return work.get(); });
    }
    private void scope(UUID tenant) {
        String applied = (String) dsl.fetchValue("select set_config('app.tenant_id', ?, true)", tenant.toString());
        if (!tenant.toString().equals(applied)) throw new IllegalStateException("Unable to apply tenant context");
    }
}
