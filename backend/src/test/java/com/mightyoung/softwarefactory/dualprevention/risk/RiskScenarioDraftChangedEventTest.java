package com.mightyoung.softwarefactory.dualprevention.risk;

import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RiskScenarioDraftChangedEventTest {
    private static final UUID TENANT = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID COMMAND = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final UUID RESOURCE = UUID.fromString("00000000-0000-4000-8000-000000000003");
    private static final UUID TRACE = UUID.fromString("00000000-0000-4000-8000-000000000004");
    private static final Instant TIME = Instant.parse("2026-11-07T00:00:00Z");

    @Test void wireContainsOnlyTheRevisionReferenceAndCanRoundTrip() throws Exception {
        var mapper = JsonMapper.builder().build();
        var event = event("risk_scenario", "risk_scenario.create", 1, "CREATE");
        var encoded = mapper.writeValueAsBytes(event);
        var fields = mapper.readValue(encoded, Map.class).keySet();
        assertThat(fields).isEqualTo(Set.of("tenantId", "commandId", "resourceType", "resourceId",
                "actionCode", "version", "occurredAt", "traceId", "changeKind"));
        assertThat(mapper.readValue(encoded, RiskScenarioDraftChangedEvent.class)).isEqualTo(event);
        assertThat(encoded.length).isLessThan(1024);
    }

    @Test void updateReferencesItsNewRevisionWithoutClaimingAnAssessment() {
        var event = event("risk_scenario", "risk_scenario.update", 2, "UPDATE");
        assertThat(event.version()).isEqualTo(2);
        assertThat(event.actionCode()).isEqualTo("risk_scenario.update");
    }

    @Test void refusesOtherDomainsActionsAndInconsistentRevisionKinds() {
        assertThatIllegalArgumentException().isThrownBy(() -> event("hazard_source", "risk_scenario.create", 1, "CREATE"));
        assertThatIllegalArgumentException().isThrownBy(() -> event("risk_scenario", "risk_scenario.publish", 2, "UPDATE"));
        assertThatIllegalArgumentException().isThrownBy(() -> event("risk_scenario", "risk_scenario.create", 2, "CREATE"));
        assertThatIllegalArgumentException().isThrownBy(() -> event("risk_scenario", "risk_scenario.update", 1, "UPDATE"));
        assertThatIllegalArgumentException().isThrownBy(() -> event("risk_scenario", "risk_scenario.update", 2, "CREATE"));
        assertThatIllegalArgumentException().isThrownBy(() -> event("risk_scenario", "risk_scenario.update", 9007199254740992L, "UPDATE"));
    }

    @Test void refusesMissingCorrelationAndTime() {
        assertThatNullPointerException().isThrownBy(() -> new RiskScenarioDraftChangedEvent(
                null, COMMAND, "risk_scenario", RESOURCE, "risk_scenario.create", 1, TIME, TRACE, "CREATE"));
        assertThatNullPointerException().isThrownBy(() -> new RiskScenarioDraftChangedEvent(
                TENANT, COMMAND, "risk_scenario", RESOURCE, "risk_scenario.create", 1, null, TRACE, "CREATE"));
        assertThatNullPointerException().isThrownBy(() -> new RiskScenarioDraftChangedEvent(
                TENANT, COMMAND, "risk_scenario", RESOURCE, "risk_scenario.create", 1, TIME, null, "CREATE"));
    }

    @Test void eventTimePreservesTheFiniteMicrosecondDatabaseCut() {
        for (String accepted : Set.of("0001-01-01T00:00:00Z", "9999-12-31T23:59:59.999999Z")) {
            var time = Instant.parse(accepted);
            assertThat(new RiskScenarioDraftChangedEvent(TENANT, COMMAND, "risk_scenario", RESOURCE,
                    "risk_scenario.create", 1, time, TRACE, "CREATE").occurredAt()).isEqualTo(time);
        }
        for (String rejected : Set.of("2026-11-07T00:00:00.000000001Z", "0000-12-31T23:59:59Z", "+10000-01-01T00:00:00Z")) {
            assertThatIllegalArgumentException().isThrownBy(() -> new RiskScenarioDraftChangedEvent(
                    TENANT, COMMAND, "risk_scenario", RESOURCE, "risk_scenario.create", 1,
                    Instant.parse(rejected), TRACE, "CREATE"));
        }
    }

    private static RiskScenarioDraftChangedEvent event(String type, String action, long version, String change) {
        return new RiskScenarioDraftChangedEvent(TENANT, COMMAND, type, RESOURCE, action, version, TIME, TRACE, change);
    }
}
