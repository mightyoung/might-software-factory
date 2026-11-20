package com.mightyoung.softwarefactory.platformadmin;

import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.jooq.SQLDialect;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import java.util.concurrent.*;
import static com.mightyoung.softwarefactory.platformadmin.LedgerConfigurationModels.*;
import static com.mightyoung.softwarefactory.platformadmin.LedgerConfigurationServiceTest.complete;
import static org.assertj.core.api.Assertions.*;

/** Runs only against an explicitly supplied isolated, migrated PostgreSQL database. */
@EnabledIfEnvironmentVariable(named = "AUTHZ_CONFIG_PG_URL", matches = ".+")
class LedgerConfigurationPostgresTest {
    final UUID tenant = UUID.fromString(System.getenv("AUTHZ_CONFIG_TENANT"));
    final UUID actor = UUID.fromString(System.getenv("AUTHZ_CONFIG_MEMBER"));
    final String key = "probe_" + UUID.randomUUID().toString().replace("-", "");
    final DriverManagerDataSource source = new DriverManagerDataSource(System.getenv("AUTHZ_CONFIG_PG_URL"), System.getenv("AUTHZ_CONFIG_PG_USER"), System.getenv("AUTHZ_CONFIG_PG_PASSWORD"));
    final DataSourceTransactionManager manager = new DataSourceTransactionManager(source);
    final TransactionTemplate tx = new TransactionTemplate(manager);
    final DSLContext dsl = DSL.using(new TransactionAwareDataSourceProxy(source), SQLDialect.POSTGRES);
    final JooqLedgerConfigurationRepository repository = new JooqLedgerConfigurationRepository(dsl, JsonMapper.builder().build(), manager);

    @Test void savesReplayRestorePublishAndRlsUseRealTransactionsAndAppendOnlyHistory() {
        UUID request = UUID.randomUUID();
        SaveRequest first = new SaveRequest(request, 0L, complete());
        Snapshot one = tx.execute(status -> repository.save(tenant, actor, key, first));
        assertThat(one.version()).isEqualTo(1);
        assertThat(one.savedBy()).isEqualTo(actor);
        assertThat(tx.execute(status -> repository.save(tenant, actor, key, first)).version()).isEqualTo(1);
        assertThatThrownBy(() -> tx.execute(status -> repository.save(tenant, actor, key, new SaveRequest(request, 1L, complete())))).isInstanceOf(LedgerConfigurationException.class);
        assertThatThrownBy(() -> tx.execute(status -> repository.save(tenant, actor, key, new SaveRequest(UUID.randomUUID(), 0L, complete())))).isInstanceOf(LedgerConfigurationException.class);
        tx.execute(status -> repository.publish(tenant, actor, key, new PublishRequest(1L, 1L, null)));
        Definition changed = new Definition("采购新版", complete().fields(), complete().listFields(), complete().queryFields());
        tx.execute(status -> repository.save(tenant, actor, key, new SaveRequest(UUID.randomUUID(), 1L, changed)));
        RestoreRequest restore = new RestoreRequest(UUID.randomUUID(), 2L, 1L);
        Snapshot restored = tx.execute(status -> repository.restore(tenant, actor, key, restore));
        assertThat(restored.version()).isEqualTo(3);
        assertThat(restored.sourceVersion()).isEqualTo(1);
        assertThat(restored.publishedVersion()).isEqualTo(1);
        assertThat(restored.definition()).isEqualTo(complete());
        assertThat(tx.execute(status -> repository.restore(tenant, actor, key, restore)).version()).isEqualTo(3);
        assertThatThrownBy(() -> tx.execute(status -> repository.publish(tenant, actor, key, new PublishRequest(3L, 3L, null)))).isInstanceOf(LedgerConfigurationException.class);
        assertThat(tx.execute(status -> repository.publish(tenant, actor, key, new PublishRequest(3L, 3L, 1L))).publishedVersion()).isEqualTo(3);
        assertThat(repository.versions(tenant, key)).extracting(Snapshot::version).containsExactly(3L, 2L, 1L);
        assertThatThrownBy(() -> repository.versions(UUID.randomUUID(), key)).isInstanceOf(LedgerConfigurationException.class);
        assertThatThrownBy(() -> tx.execute(status -> {
            dsl.fetchValue("select set_config('app.tenant_id',?,true)", tenant.toString());
            return dsl.execute("update public.ledger_configuration_snapshot set definition='{}'::jsonb where tenant_id=? and configuration_key=?", tenant, key);
        })).isInstanceOf(org.jooq.exception.DataAccessException.class);
        assertThatThrownBy(() -> tx.execute(status -> {
            dsl.fetchValue("select set_config('app.tenant_id',?,true)", UUID.randomUUID().toString());
            return dsl.execute("insert into public.ledger_configuration(tenant_id,configuration_key) values (?,?)", tenant, "cross_tenant");
        })).isInstanceOf(org.jooq.exception.DataAccessException.class);
        assertThat(repository.versions(tenant, key).getFirst().definition()).isEqualTo(complete());
    }
    @Test void twoEditorsCompeteForOneExpectedVersion() throws Exception {
        tx.execute(status -> repository.save(tenant, actor, key, new SaveRequest(UUID.randomUUID(), 0L, complete())));
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> save = () -> {
                start.await();
                try {
                    tx.execute(status -> repository.save(tenant, actor, key, new SaveRequest(UUID.randomUUID(), 1L, complete())));
                    return true;
                } catch (LedgerConfigurationException conflict) {
                    assertThat(conflict.reason()).isEqualTo(LedgerConfigurationException.Reason.VERSION_CONFLICT);
                    return false;
                }
            };
            Future<Boolean> first = workers.submit(save);
            Future<Boolean> second = workers.submit(save);
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(repository.versions(tenant, key)).extracting(Snapshot::version).containsExactly(2L, 1L);
    }

    @Test void workflowSnapshotsReplayPublishAndRestoreWithoutChangingLegacyReceipts() {
        UUID legacyRequest = UUID.randomUUID();
        String legacyPayload = "{\"requestId\":\"" + legacyRequest + "\",\"expectedVersion\":0,\"definition\":"
                + JsonMapper.builder().build().writeValueAsString(complete()) + "}";
        tx.execute(status -> {
            dsl.fetchValue("select set_config('app.tenant_id',?,true)", tenant.toString());
            dsl.execute("insert into public.ledger_configuration(tenant_id,configuration_key,latest_version) values (?,?,1)", tenant, key);
            dsl.execute("insert into public.ledger_configuration_snapshot(tenant_id,configuration_key,version,request_id,request_payload,definition,actor_member_id)"
                    + " values (?,?,1,?,cast(? as jsonb),cast(? as jsonb),?)", tenant, key, legacyRequest, legacyPayload,
                    JsonMapper.builder().build().writeValueAsString(complete()), actor);
            return null;
        });
        assertThat(tx.execute(status -> repository.save(tenant, actor, key, new SaveRequest(legacyRequest, 0L, complete()))).version()).isEqualTo(1);
        Definition graph = LedgerConfigurationWorkflowTest.withWorkflow(LedgerConfigurationWorkflowTest.linear());
        SaveRequest graphSave = new SaveRequest(UUID.randomUUID(), 1L, graph);
        Snapshot two = tx.execute(status -> repository.save(tenant, actor, key, graphSave));
        assertThat(two.definition()).isEqualTo(graph);
        assertThat(tx.execute(status -> repository.save(tenant, actor, key, graphSave)).version()).isEqualTo(2);
        assertThat(tx.execute(status -> repository.publish(tenant, actor, key, new PublishRequest(2L, 2L, null))).publishedVersion()).isEqualTo(2);
        Definition disconnected = LedgerConfigurationWorkflowTest.withWorkflow(new Workflow(graph.workflow().nodes(), List.of()));
        tx.execute(status -> repository.save(tenant, actor, key, new SaveRequest(UUID.randomUUID(), 2L, disconnected)));
        assertThatThrownBy(() -> tx.execute(status -> repository.publish(tenant, actor, key, new PublishRequest(3L, 3L, 2L))))
                .isInstanceOf(IllegalArgumentException.class);
        Snapshot restoredGraph = tx.execute(status -> repository.restore(tenant, actor, key, new RestoreRequest(UUID.randomUUID(), 3L, 2L)));
        assertThat(restoredGraph.definition()).isEqualTo(graph);
        assertThat(restoredGraph.publishedVersion()).isEqualTo(2);
        Snapshot restoredLegacy = tx.execute(status -> repository.restore(tenant, actor, key, new RestoreRequest(UUID.randomUUID(), 4L, 1L)));
        assertThat(restoredLegacy.definition().workflow()).isNull();
        assertThat(repository.versions(tenant, key)).extracting(Snapshot::version).containsExactly(5L, 4L, 3L, 2L, 1L);
    }

}
