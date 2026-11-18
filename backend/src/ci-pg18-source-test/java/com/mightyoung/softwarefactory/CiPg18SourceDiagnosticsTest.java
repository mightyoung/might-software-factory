package com.mightyoung.softwarefactory;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CiPg18SourceDiagnosticsTest {
    private static final String CASE="actualCreateCommitsSourceAndNativePublicationTogether";
    private static CiPg18SourceFixture.Metrics counts(int value){return new CiPg18SourceFixture.Metrics(value,value,value,value);}
    @SuppressWarnings("unchecked")
    private static Map<String,Object> row(CiPg18SourceDiagnostics value){return (Map<String,Object>)((List<?>)value.snapshot().get("cases")).getFirst();}

    @Test void firstDriverFailureRetainsOnlyDigestAndUppercaseAlphanumericState() throws Exception {
        var value=new CiPg18SourceDiagnostics();value.begin(CASE,counts(0));value.graphReady();value.enter();
        value.success(CiPg18SourceDiagnostics.Operation.EXECUTE_QUERY,"select PRIVATE_SOURCE_SENTINEL");
        value.failure(CiPg18SourceDiagnostics.Operation.EXECUTE_UPDATE,"insert PRIVATE_SOURCE_SENTINEL",new SQLException("PRIVATE_SOURCE_SENTINEL","XX000",new IllegalStateException("PRIVATE_SOURCE_SENTINEL")));
        value.failure(CiPg18SourceDiagnostics.Operation.ROLLBACK,null,new SQLException("PRIVATE_SOURCE_SENTINEL","25P02"));
        value.exit(false,new com.mightyoung.softwarefactory.dualprevention.risk.RiskScenarioContracts.Failure(503));
        value.finish(new AssertionError("PRIVATE_SOURCE_SENTINEL"),counts(1));
        var json=tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(value.snapshot());
        assertThat(json).doesNotContain("PRIVATE_SOURCE_SENTINEL","SQLException","IllegalStateException","select ","insert ","25P02");
        assertThat(json).contains("XX000","ISSUER_FAILED","ASSERTION","lastIssuerStatus\":503");
        assertThat(((Map<?,?>)row(value).get("firstDriverFailure")).get("operation")).isEqualTo("EXECUTE_UPDATE");
    }
    @Test void malformedDriverStateIsUnavailableRatherThanCopied() {
        for(String state:new String[]{"xX000","SECRET","1234",null}) {
            var value=new CiPg18SourceDiagnostics();value.begin(CASE,counts(0));value.graphReady();value.enter();
            value.failure(CiPg18SourceDiagnostics.Operation.EXECUTE,null,new SQLException("private",state));
            value.exit(false,null);value.finish(new IllegalStateException("private"),counts(0));
            assertThat(((Map<?,?>)row(value).get("firstDriverFailure")).get("sqlState")).isNull();
        }
    }
    @Test void cleanupCannotOverwriteTheIssuerLastSuccessfulSqlOperation() {
        var value=new CiPg18SourceDiagnostics();value.begin(CASE,counts(0));value.graphReady();value.enter();
        value.success(CiPg18SourceDiagnostics.Operation.EXECUTE_QUERY,"select fixed");
        value.success(CiPg18SourceDiagnostics.Operation.ROLLBACK,null);value.exit(false,null);
        value.success(CiPg18SourceDiagnostics.Operation.EXECUTE_QUERY,"cleanup query");
        value.finish(new IllegalStateException(),counts(0));
        assertThat(((Map<?,?>)row(value).get("lastSuccess")).get("operation")).isEqualTo("EXECUTE_QUERY");
        assertThat(((Map<?,?>)row(value).get("lastSuccess")).get("sqlSha256"))
                .isEqualTo("04a1919e96ba7e15e94116ea10cb99456a99326f38d2eed95c605492aa005b5d");
    }
    @Test void graphFailureReportsNoInventedIssuerOrReturnedResult() {
        var value=new CiPg18SourceDiagnostics();value.begin(CASE,counts(0));value.finish(new IllegalStateException("private"),counts(0));
        assertThat(row(value)).containsEntry("phase","GRAPH_STARTUP").containsEntry("issuerEntered",0)
                .containsEntry("issuerReturned",0).containsEntry("status","FAILED");
    }
    @Test void expectedIssuerFailureCanBeObservedWithoutTurningTheTestIntoASuccessClaim() {
        var value=new CiPg18SourceDiagnostics();value.begin(CASE,counts(3));value.graphReady();value.enter();
        value.exit(true,null);value.enter();value.exit(false,new com.mightyoung.softwarefactory.dualprevention.risk.RiskScenarioContracts.Failure(409));
        value.finish(null,counts(4));
        assertThat(row(value)).containsEntry("issuerEntered",2).containsEntry("issuerReturned",1)
                .containsEntry("status","PASSED").containsEntry("lastIssuerStatus",409);
        assertThat(((Map<?,?>)row(value).get("counts")).get("borrows")).isEqualTo(1);
    }
    @Test void unknownDuplicateAndUnboundedCaseFactsAreRefused() {
        var value=new CiPg18SourceDiagnostics();
        assertThatThrownBy(()->value.begin("PRIVATE_CASE",counts(0))).hasMessage("Source diagnostic contract rejected");
        value.begin(CASE,counts(0));
        assertThatThrownBy(()->value.begin(CASE,counts(0))).hasMessage("Source diagnostic contract rejected");
        assertThatThrownBy(()->value.finish(null,counts(1001))).hasMessage("Source diagnostic contract rejected");
        value.finish(null,counts(0));
        assertThatThrownBy(()->value.begin(CASE,counts(0))).hasMessage("Source diagnostic contract rejected");
    }
}
