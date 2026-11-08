package com.mightyoung.softwarefactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.AbstractDataSource;
import tools.jackson.databind.json.JsonMapper;

/** One pre-composed canonical wrapper; faults only after an actual selected completion UPDATE. */
final class CiPg18RecoveryAckProbe extends AbstractDataSource {
    private final DataSource delegate;
    private final CiPg18RecoveryWorkerTarget target;
    final AtomicInteger completionRows=new AtomicInteger(),ackCommits=new AtomicInteger(),ackRollbacks=new AtomicInteger();
    final AtomicBoolean faultReached=new AtomicBoolean(),unknownThrown=new AtomicBoolean();
    CiPg18RecoveryAckProbe(DataSource delegate,CiPg18RecoveryWorkerTarget target){this.delegate=delegate;this.target=target;}
    @Override public Connection getConnection()throws SQLException{
        Connection raw=delegate.getConnection();boolean[] ack={false};
        return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
            if(method.getName().equals("toString"))return "RecoveryCanonicalConnection[REDACTED]";
            try{
                Object result=method.invoke(raw,args);
                if(method.getName().equals("prepareStatement")&&args!=null&&args.length>0&&args[0] instanceof String sql
                        &&result instanceof PreparedStatement statement)return statement(statement,sql,ack);
                if(method.getName().equals("commit")&&ack[0]){
                    ackCommits.incrementAndGet();
                    if(target.fault()==CiPg18RecoveryWorkerTarget.Fault.ACK_COMMIT_UNKNOWN&&faultReached.compareAndSet(false,true)){
                        unknownThrown.set(true);throw new SQLException("Recovery ACK outcome unavailable","08006");
                    }
                }
                if(method.getName().equals("rollback")&&ack[0])ackRollbacks.incrementAndGet();
                return result;
            }catch(InvocationTargetException failure){throw failure.getTargetException();}
        });
    }
    private PreparedStatement statement(PreparedStatement raw,String sql,boolean[] ack){
        Map<Integer,Object> bindings=new HashMap<>();
        String normalized=sql.replace("\"","").replaceAll("\\s+"," ").trim().toUpperCase(Locale.ROOT).replace("PUBLIC.","");
        boolean byEvent=normalized.equals("UPDATE EVENT_PUBLICATION SET COMPLETION_DATE = ? WHERE LISTENER_ID = ? AND COMPLETION_DATE IS NULL AND SERIALIZED_EVENT = ?");
        boolean byId=normalized.equals("UPDATE EVENT_PUBLICATION SET COMPLETION_DATE = ? WHERE ID = ?");
        return (PreparedStatement)Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),new Class<?>[]{PreparedStatement.class},(proxy,method,args)->{
            if(method.getName().equals("toString"))return "RecoveryCompletionStatement[REDACTED]";
            if(method.getName().startsWith("set")&&args!=null&&args.length>=2&&args[0] instanceof Integer index)bindings.put(index,args[1]);
            try{
                Object result=method.invoke(raw,args);
                if(method.getName().equals("executeUpdate")&&Integer.valueOf(1).equals(result)&&(byEvent||byId)&&!target.producer()){
                    boolean selected=byEvent?selectedEvent(bindings):target.publicationId().equals(bindings.get(2));
                    if(selected){
                        ack[0]=true;completionRows.incrementAndGet();
                        if(target.fault()==CiPg18RecoveryWorkerTarget.Fault.ACK_UPDATE_ROLLBACK&&faultReached.compareAndSet(false,true))
                            throw new SQLException("Recovery completion write unavailable","08006");
                    }
                }else if(method.getName().equals("executeUpdate")&&Integer.valueOf(1).equals(result)&&(byEvent||byId)&&target.producer()){
                    ack[0]=true;completionRows.incrementAndGet();
                }
                return result;
            }catch(InvocationTargetException failure){throw failure.getTargetException();}
        });
    }
    private boolean selectedEvent(Map<Integer,Object> bindings){
        try{return "risk-scenario-draft-change".equals(bindings.get(2))&&bindings.get(3) instanceof String value
                &&target.sourceCommandId().toString().equals(JsonMapper.builder().build().readTree(value).get("commandId").stringValue());}
        catch(Exception failure){return false;}
    }
    @Override public Connection getConnection(String user,String password)throws SQLException{throw new SQLException("Fixed recovery identity","08004");}
    @Override public String toString(){return "RecoveryAckProbe[REDACTED]";}
}
