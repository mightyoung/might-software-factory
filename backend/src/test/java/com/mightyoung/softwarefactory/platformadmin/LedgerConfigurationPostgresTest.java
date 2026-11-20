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
    @Test void historicalDefinitionCanBeSavedAsAnIndependentUnpublishedLedger() {
        String targetKey = key + "_copy";
        Field quantity = new Field("quantity", "数量", "number", true);
        Field note = new Field("note", "备注", "text", false);
        var historical = new Definition("原台账", List.of(quantity, note), List.of("note", "quantity"), List.of("quantity"));
        var latest = new Definition("源台账新版", List.of(note, new Field("quantity", "新数量", "number", true)),
                List.of("quantity", "note"), List.of("note"));
        tx.execute(status -> repository.save(tenant, actor, key, new SaveRequest(UUID.randomUUID(), 0L, historical)));
        tx.execute(status -> repository.publish(tenant, actor, key, new PublishRequest(1L, 1L, null)));
        tx.execute(status -> repository.save(tenant, actor, key, new SaveRequest(UUID.randomUUID(), 1L, latest)));
        var originalHistory = repository.versions(tenant, key);
        assertThat(originalHistory).extracting(Snapshot::version).containsExactly(2L, 1L);
        assertThat(originalHistory.getFirst().definition()).isEqualTo(latest);
        assertThat(originalHistory.getFirst().publishedVersion()).isEqualTo(1L);

        var selected = originalHistory.getLast().definition();
        // The copy UI submits the historical definition as a normal new-key save, not a same-ledger restore.
        var copied = new Definition("独立副本", selected.fields(), selected.listFields(), selected.queryFields());
        var saveCopy = new SaveRequest(UUID.randomUUID(), 0L, copied);
        var created = tx.execute(status -> repository.save(tenant, actor, targetKey, saveCopy));
        assertThat(created.key()).isEqualTo(targetKey);
        assertThat(created.version()).isEqualTo(1L);
        assertThat(created.definition()).isEqualTo(new Definition("独立副本", historical.fields(),
                historical.listFields(), historical.queryFields()));
        assertThat(created.publishedVersion()).isNull();
        assertThat(created.sourceVersion()).isNull();
        var replayed = tx.execute(status -> repository.save(tenant, actor, targetKey, saveCopy));
        assertThat(replayed).isEqualTo(created);
        assertThat(repository.versions(tenant, targetKey)).containsExactly(created);
        assertThat(repository.versions(tenant, key)).isEqualTo(originalHistory);

        var revisedCopy = new Definition("副本新版", copied.fields(), copied.listFields(), copied.queryFields());
        tx.execute(status -> repository.save(tenant, actor, targetKey, new SaveRequest(UUID.randomUUID(), 1L, revisedCopy)));
        var targetHistory = repository.versions(tenant, targetKey);
        assertThat(targetHistory).extracting(Snapshot::version).containsExactly(2L, 1L);
        assertThat(targetHistory.getFirst().publishedVersion()).isNull();
        assertThat(targetHistory.getLast()).isEqualTo(created);
        assertThat(repository.versions(tenant, key)).isEqualTo(originalHistory);
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

}
