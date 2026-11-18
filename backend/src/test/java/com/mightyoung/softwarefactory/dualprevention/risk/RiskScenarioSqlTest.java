package com.mightyoung.softwarefactory.dualprevention.risk;

import com.mightyoung.softwarefactory.support.G014SqlProfile;
import com.mightyoung.softwarefactory.support.TransactionSqlSealGate;
import java.lang.reflect.Modifier;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Finite catalog and projection guards; these do not parse or execute PostgreSQL. */
class RiskScenarioSqlTest {
    @Test void everyBusinessTemplateHasExactBindMetadataAndRiskIdentity(){
        assertThat(RiskScenarioSql.values()).hasSize(19);
        for(var sql:RiskScenarioSql.values()){
            var template=sql.template();assertThat(template.profile()).isSameAs(G014SqlProfile.RISK_SCENARIO);
            assertThat(template.parameterCount()).isEqualTo(sql.binds.size());
            assertThat(sql.sql.chars().filter(c->c=='?').count()).isEqualTo(sql.binds.size());
            assertThat(template.finalRead()).isEqualTo(sql.phase==RiskScenarioSql.Phase.FINAL_READ);
            assertThat(template.effect()).isEqualTo(sql.effect);
            if(sql.effect!=null)assertThat(sql.phase).isEqualTo(RiskScenarioSql.Phase.WRITE);
        }
        assertThat(RiskScenarioSql.templates()).hasSize(20).doesNotHaveDuplicates();
        assertThat(RiskScenarioSql.templates().getLast()).isEqualTo(TransactionSqlSealGate.SqlTemplate.deferredConstraintsImmediate(G014SqlProfile.RISK_SCENARIO));
    }
    @Test void physicalRowsAndEntireMutationPlanUseTheCandidateFiniteHelpers(){
        assertThat(RiskScenarioSql.ROW_LOCK.sql).contains("g014r_lock_row(?,cast(? as uuid),cast(? as uuid))");
        assertThat(RiskScenarioSql.MUTATION_LOCK.sql).contains("g014r_lock_mutation_keys(","cast(? as uuid[])","cast(? as bigint[])");
        assertThat(RiskScenarioSql.MUTATION_PLAN.sql).contains("family,canonical_bytes","order by family,canonical_bytes");
        assertThat(RiskScenarioSql.NATURAL_LOCK.sql).contains("g014r_lock_natural_key(");
        assertThat(RiskScenarioSql.RECEIPT_LOCK.sql).contains("g014r_lock_receipt_key(");
    }
    @Test void rootCasAndCreationHaveActualSqlOrganizationScope(){
        assertThat(RiskScenarioSql.ROOT_INSERT.sql).contains("join public.org_unit o","o.active and o.id=any(i.organizations)",
                "i.actor_id,i.member_id,i.occurred_at,i.actor_id,i.member_id,i.occurred_at,i.command_id");
        assertThat(RiskScenarioSql.ROOT_UPDATE.sql).contains("version=?","where tenant_id=? and id=? and version=? and organization_id=any(cast(? as uuid[]))");
        assertThat(RiskScenarioSql.ROOT_CURRENT.sql).contains("s.tenant_id=? and s.id=? and s.organization_id=any(cast(? as uuid[]))");
        assertThat(RiskScenarioSql.ROOT_UPDATE.sql).doesNotContain("set code=","organization_id=?");
    }
    @Test void activeDiscoveryRetainsFullStableLinkIdentityWithoutLoadingDescriptions(){
        assertThat(RiskScenarioSql.ACTIVE_LINKS.sql).contains("l.id","l.scenario_id","l.hazard_id","l.hazard_version_at_link","l.version",
                "l.created_by_actor_id","l.created_by_member_id","l.last_scenario_version","l.last_command_id","l.last_actor_id","l.last_member_id","l.last_occurred_at",
                "s.organization_id=any(cast(? as uuid[])) and l.ended_at is null order by l.id limit 101");
        assertThat(RiskScenarioSql.ACTIVE_LINKS.sql).doesNotContain("full_description","snapshot_description");
    }
    @Test void referenceReadHasHazardScopeAndCurrentImmutableRevisionEquality(){
        assertThat(RiskScenarioSql.HAZARD_REFERENCE.sql).contains("h.organization_id=any(cast(? as uuid[]))",
                "r.entity_id=h.id and r.version=h.version","row(h.code,h.description,h.existence_state) is not distinct from row(r.snapshot_code,r.snapshot_description,r.snapshot_existence_state)",
                "sha256(convert_to(h.description,'UTF8'))");
        assertThat(RiskScenarioSql.HAZARD_REFERENCE.columns).extracting(RiskScenarioSql.Column::name).containsExactly(
                "id","organization_id","version","code","description_sha256","existence_state");
    }
    @Test void snapshotsAlwaysCopyFullHistoricalDescriptionFromTheLockedObservedRevision(){
        assertThat(RiskScenarioSql.SNAPSHOT_INSERT.sql).contains("insert into public.risk_scenario_revision_hazard",
                "r.snapshot_description","left(r.snapshot_description,200)","char_length(r.snapshot_description)>200",
                "l.hazard_version_at_link,h.version","r.entity_id=h.id and r.version=h.version",
                "l.hazard_version_at_link=? and h.version=? and s.organization_id=any(cast(? as uuid[]))");
        assertThat(RiskScenarioSql.SNAPSHOT_INSERT.binds).extracting(RiskScenarioSql.Bind::name).doesNotContain("full_description","snapshot_code","existence");
        assertThat(RiskScenarioSql.SNAPSHOT_INDEX.sql).contains("sha256(convert_to(q.full_description,'UTF8'))","limit 101");
    }
    @Test void rootAndLinkRevisionsCopyCurrentRowsAndEndCannotRefreshAtLink(){
        assertThat(RiskScenarioSql.ROOT_REVISION.sql).contains("select s.tenant_id,s.id","s.last_command_id=?","s.organization_id=any(cast(? as uuid[]))");
        assertThat(RiskScenarioSql.LINK_REVISION.sql).contains("l.last_scenario_version,l.last_command_id,l.last_actor_id,l.last_member_id,l.last_occurred_at",
                "l.version=? and l.last_command_id=?");
        assertThat(RiskScenarioSql.LINK_END.sql).contains("version=2,ended_at=i.occurred_at","l.hazard_version_at_link=? and l.version=1 and l.ended_at is null")
                .doesNotContain("set hazard_version_at_link=");
    }
    @Test void receiptIsSqlScopedAndTheCatalogDoesNotClaimAuditOrPublicationEffects(){
        assertThat(RiskScenarioSql.RECEIPT_INSERT.sql).contains("s.organization_id=any(i.organizations)","s.last_command_id","i.audit_event_id,i.trace_id");
        assertThat(RiskScenarioSql.RECEIPT_FIND.sql).contains("r.actor_id=? and r.action_code=? and r.idempotency_key=?",
                "s.organization_id=any(cast(? as uuid[]))");
        for(var sql:RiskScenarioSql.values())assertThat(sql.sql).doesNotContain("insert into public.audit_event","insert into event_publication","delete from","update public.hazard_source","set constraints all");
        assertThat(Modifier.isPublic(RiskScenarioSql.class.getModifiers())).isFalse();
    }
    @Test void incorrectBindingCountAndJavaTypesCannotReachTheDriver(){
        assertThatThrownBy(()->RiskScenarioSql.ROW_LOCK.requireBindings(List.of(60,UUID.randomUUID()))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->RiskScenarioSql.ROW_LOCK.requireBindings(List.of(60L,UUID.randomUUID(),UUID.randomUUID()))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->RiskScenarioSql.ROOT_CURRENT.requireBindings(List.of("tenant",UUID.randomUUID(),"{}"))).isInstanceOf(IllegalArgumentException.class);
    }
}
