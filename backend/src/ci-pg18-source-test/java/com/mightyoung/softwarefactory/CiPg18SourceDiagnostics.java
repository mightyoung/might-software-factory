package com.mightyoung.softwarefactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Source-test diagnostics only: no SQL text, bindings, exception messages, stack or identity values. */
final class CiPg18SourceDiagnostics {
    static final Set<String> CASES=Set.of("actualCreateCommitsSourceAndNativePublicationTogether",
            "sameKeyReplayAddsNoEffectsAndChangedPayloadConflicts","latePublisherFailureRollsBackAllSourceEffects");
    enum Operation { PREPARE, EXECUTE_QUERY, EXECUTE_UPDATE, EXECUTE, RESULT_READ, COMMIT, ROLLBACK, CLOSE }
    private final Map<String,Object> completed=new TreeMap<>();
    private String name,phase;
    private boolean observing;
    private int entered,returned;
    private Integer lastIssuerStatus;
    private CiPg18SourceFixture.Metrics before;
    private Map<String,Object> lastSuccess,firstFailure;

    void begin(String value,CiPg18SourceFixture.Metrics counts) {
        if(!CASES.contains(value)||name!=null||completed.containsKey(value))throw rejected();
        name=value;phase="GRAPH_STARTUP";observing=true;entered=0;returned=0;before=counts;
        lastSuccess=null;firstFailure=null;lastIssuerStatus=null;
    }
    void graphReady(){phase="GRAPH_READY";observing=false;}
    void enter(){if(name==null||observing||entered>=16)throw rejected();entered++;phase="ISSUER_ENTERED";observing=true;}
    void exit(boolean success,Throwable failure){
        if(success)returned++;
        lastIssuerStatus=failure instanceof com.mightyoung.softwarefactory.dualprevention.risk.RiskScenarioContracts.Failure risk?risk.status():null;
        phase=success?"ISSUER_RETURNED":"ISSUER_FAILED";observing=false;
    }
    void success(Operation operation,String sql){if(observing&&(sql!=null||lastSuccess==null))lastSuccess=operation(operation,sql,null,false);}
    void failure(Operation operation,String sql,Throwable failure){
        if(observing&&firstFailure==null)firstFailure=operation(operation,sql,sqlState(failure),true);
    }
    void finish(Throwable failure,CiPg18SourceFixture.Metrics after) {
        if(name==null)throw rejected();
        var counts=Map.of("borrows",bounded(after.borrows()-before.borrows()),"commits",bounded(after.commits()-before.commits()),
                "rollbacks",bounded(after.rollbacks()-before.rollbacks()),"publicationInserts",bounded(after.publicationInserts()-before.publicationInserts()));
        Map<String,Object> row=new TreeMap<>();
        row.put("name",name);row.put("status",failure==null?"PASSED":"FAILED");row.put("phase",phase);
        row.put("issuerEntered",entered);row.put("issuerReturned",returned);row.put("counts",counts);
        row.put("lastIssuerStatus",lastIssuerStatus);
        row.put("failureKind",failure==null?null:failure instanceof AssertionError?"ASSERTION":
                failure instanceof com.mightyoung.softwarefactory.dualprevention.risk.RiskScenarioContracts.Failure?"RISK_COMMAND":"UNAVAILABLE");
        row.put("lastSuccess",lastSuccess);row.put("firstDriverFailure",firstFailure);
        completed.put(name,row);name=null;observing=false;
    }
    Map<String,Object> snapshot(){return Map.of("schemaVersion",1,"database","msf_ci_risk_source","cases",List.copyOf(completed.values()));}
    private static Map<String,Object> operation(Operation operation,String sql,String state,boolean failed) {
        Map<String,Object> result=new TreeMap<>();result.put("operation",operation.name());result.put("sqlSha256",hash(sql));
        if(failed)result.put("sqlState",state);
        return result;
    }
    private static String hash(String sql) {
        if(sql==null)return null;
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sql.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException failure){throw rejected();}
    }
    private static String sqlState(Throwable failure) {
        for(int index=0;index<8&&failure!=null;index++,failure=failure.getCause())
            if(failure instanceof SQLException sql&&sql.getSQLState()!=null&&sql.getSQLState().matches("[0-9A-Z]{5}"))return sql.getSQLState();
        return null;
    }
    private static int bounded(int value){if(value<0||value>1000)throw rejected();return value;}
    private static IllegalStateException rejected(){return new IllegalStateException("Source diagnostic contract rejected");}
}
