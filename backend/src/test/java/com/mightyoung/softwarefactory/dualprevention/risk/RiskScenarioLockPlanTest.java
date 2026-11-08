package com.mightyoung.softwarefactory.dualprevention.risk;

import com.mightyoung.softwarefactory.authorization.RegisteredEntityAction;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static com.mightyoung.softwarefactory.dualprevention.risk.RiskScenarioLockPlan.*;

/** Pure planning proofs; database equality still needs the repository's actual PG vector comparison. */
class RiskScenarioLockPlanTest {
    private static final UUID TENANT=id(1),SCENARIO=id(2),COMMAND=id(3),ACTOR=id(4),MEMBER=id(5);
    private static final Instant TIME=Instant.parse("2026-11-07T00:00:00Z");
    static UUID id(long n){return new UUID(0,n);}
    private static ActiveLink old(UUID link,UUID hazard,long atLink){return new ActiveLink(TENANT,link,SCENARIO,hazard,atLink,1,TIME,ACTOR,MEMBER,1,id(6),ACTOR,MEMBER,TIME);}
    @Test void unionContainsRemovedHazardsAndOnlyActualChangesHaveRevisionKeys(){
        var retained=old(id(20),id(30),7);var removed=old(id(21),id(31),3);
        var plan=new RiskScenarioLockPlan(TENANT,SCENARIO,COMMAND,false,4,List.of(removed,retained),List.of(id(32),id(30)),Map.of(id(32),id(22)));
        assertThat(plan.unionHazards).containsExactly(id(30),id(31),id(32));
        assertThat(plan.desiredHazards).containsExactly(id(30),id(32));
        assertThat(plan.changes).containsExactly(new Change(id(21),id(31),2,ChangeKind.END),new Change(id(22),id(32),1,ChangeKind.NEW));
        assertThat(plan.newVersion).isEqualTo(5);assertThat(plan.mutationKeys).hasSize(9);
        assertThat(plan.oldLinks.getFirst().hazardVersionAtLink()).isEqualTo(7);
        assertThat(plan.changes).noneMatch(c->c.id().equals(retained.id()));
    }
    @Test void rowOrderUsesGlobalKindsAndUnsignedUuidNetworkBytes(){
        UUID low=UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff"),high=UUID.fromString("80000000-0000-0000-0000-000000000000");
        var plan=new RiskScenarioLockPlan(TENANT,SCENARIO,COMMAND,false,1,List.of(old(high,high,1),old(low,low,1)),List.of(high,low),Map.of());
        assertThat(plan.rows).containsExactly(new RowLock(RowKind.HAZARD_SOURCE,low),new RowLock(RowKind.HAZARD_SOURCE,high),
                new RowLock(RowKind.RISK_SCENARIO,SCENARIO),new RowLock(RowKind.RISK_HAZARD,low),new RowLock(RowKind.RISK_HAZARD,high));
        assertThat(Arrays.stream(RowKind.values()).map(k->k.number)).containsExactly(60,100,110);
        assertThat(plan.mutationKeys).isSorted();
    }
    @Test void createDoesNotAttemptToLockAnAbsentParentOrNewLinks(){
        var plan=new RiskScenarioLockPlan(TENANT,SCENARIO,COMMAND,true,0,List.of(),List.of(id(9)),Map.of(id(9),id(10)));
        assertThat(plan.rows).containsExactly(new RowLock(RowKind.HAZARD_SOURCE,id(9)));
        assertThat(plan.mutationKeys).hasSize(6);assertThat(plan.newVersion).isOne();
        assertThat(plan.databasePlanBindings()).containsExactly(TENANT,SCENARIO,1L,COMMAND,"{"+id(10)+"}","{1}","{"+id(9)+"}");
    }
    @Test void equalCardinalityReplacementAndVersionOrAnchorChangesAreNotStable(){
        var original=old(id(20),id(30),1);var plan=new RiskScenarioLockPlan(TENANT,SCENARIO,COMMAND,false,2,List.of(original),List.of(id(30)),Map.of());
        assertThatCode(()->plan.requireStable(2,List.of(original))).doesNotThrowAnyException();
        assertThatThrownBy(()->plan.requireStable(3,List.of(original))).isInstanceOf(RiskScenarioContracts.Failure.class);
        assertThatThrownBy(()->plan.requireStable(2,List.of(old(id(21),id(30),1)))).isInstanceOf(RiskScenarioContracts.Failure.class);
        assertThatThrownBy(()->plan.requireStable(2,List.of(old(id(20),id(31),1)))).isInstanceOf(RiskScenarioContracts.Failure.class);
        assertThatThrownBy(()->plan.requireStable(2,List.of(old(id(20),id(30),2)))).isInstanceOf(RiskScenarioContracts.Failure.class);
    }
    @Test void unchangedSetDoesNotCreateLinkMutationsAndRejoiningUsesANewId(){
        var original=old(id(20),id(30),3);
        var unchanged=new RiskScenarioLockPlan(TENANT,SCENARIO,COMMAND,false,4,List.of(original),List.of(id(30)),Map.of());
        assertThat(unchanged.changes).isEmpty();assertThat(unchanged.mutationKeys).hasSize(4);
        var rejoined=new RiskScenarioLockPlan(TENANT,SCENARIO,COMMAND,false,4,List.of(old(id(22),id(40),1)),List.of(id(30),id(40)),Map.of(id(30),id(21)));
        assertThat(rejoined.changes).containsExactly(new Change(id(21),id(30),1,ChangeKind.NEW));
    }
    @Test void rejectsIncompleteIdsCrossTenantOldRowsDuplicatesAndUnsafeVersions(){
        assertThatThrownBy(()->new RiskScenarioLockPlan(TENANT,SCENARIO,COMMAND,true,0,List.of(),List.of(id(9)),Map.of())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->new RiskScenarioLockPlan(TENANT,SCENARIO,COMMAND,false,1,List.of(old(id(20),id(30),1)),List.of(id(31)),Map.of(id(31),id(20)))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->new RiskScenarioLockPlan(TENANT,SCENARIO,COMMAND,false,RiskScenarioContracts.MAX_VERSION,List.of(old(id(20),id(30),1)),List.of(id(30)),Map.of())).isInstanceOf(RiskScenarioContracts.Failure.class);
        var foreign=new ActiveLink(id(99),id(20),SCENARIO,id(30),1,1,TIME,ACTOR,MEMBER,1,id(6),ACTOR,MEMBER,TIME);
        assertThatThrownBy(()->new RiskScenarioLockPlan(TENANT,SCENARIO,COMMAND,false,1,List.of(foreign),List.of(id(30)),Map.of())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->new RiskScenarioLockPlan(TENANT,SCENARIO,COMMAND,false,1,List.of(old(id(20),id(30),1),old(id(21),id(30),1)),List.of(id(30)),Map.of())).isInstanceOf(RuntimeException.class);
    }
    @Test void inputsAndCanonicalKeyBytesCannotBeMutatedAfterPlanning(){
        var desired=new ArrayList<>(List.of(id(9)));var ids=new HashMap<>(Map.of(id(9),id(10)));
        var plan=new RiskScenarioLockPlan(TENANT,SCENARIO,COMMAND,true,0,List.of(),desired,ids);
        desired.clear();ids.clear();assertThat(plan.desiredHazards).containsExactly(id(9));assertThat(plan.changes).hasSize(1);
        var first=plan.mutationKeys.getFirst();var saved=first.canonicalBytes();var escaped=first.canonicalBytes();escaped[0]=99;
        assertThat(first.canonicalBytes()).containsExactly(saved);
        assertThatThrownBy(()->plan.rows.clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void naturalAndReceiptFramesRetainUnambiguousLengthTagsAndFixedActions(){
        String natural=HexFormat.of().formatHex(RiskScenarioLockPlan.naturalKey(TENANT,"R-1"));
        assertThat(natural).isEqualTo("010000000400000001020000000d7269736b5f7363656e6172696f0300000010000000000000000000000000000000010500000003522d31");
        var receipt=RiskScenarioLockPlan.receiptKey(TENANT,ACTOR,RegisteredEntityAction.RISK_SCENARIO_CREATE,id(7));
        assertThat(HexFormat.of().formatHex(receipt)).contains("7269736b5f7363656e6172696f5f72656365697074","08000000147269736b5f7363656e6172696f2e637265617465");
        assertThatThrownBy(()->RiskScenarioLockPlan.receiptKey(TENANT,ACTOR,RegisteredEntityAction.HAZARD_SOURCE_CREATE,id(7))).isInstanceOf(RiskScenarioContracts.Failure.class);
    }
}
