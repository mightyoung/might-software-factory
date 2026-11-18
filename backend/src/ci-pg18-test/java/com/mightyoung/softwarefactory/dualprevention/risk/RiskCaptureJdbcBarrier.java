package com.mightyoung.softwarefactory.dualprevention.risk;

import java.lang.reflect.*;
import java.sql.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.ConnectionProxy;

/** Test-only timing around real delegated JDBC. It never manufactures rows, conflicts or transaction outcomes. */
final class RiskCaptureJdbcBarrier extends AbstractDataSource implements AutoCloseable {
    private static final long HOLD_MILLIS=1200;
    private static final long OBSERVE_MILLIS=800;
    private final DataSource delegate;
    private final boolean rollbackWinner;
    private final AtomicInteger connections=new AtomicInteger(),winnerPid=new AtomicInteger(),waiterPid=new AtomicInteger();
    private final AtomicInteger winnerCommitCalls=new AtomicInteger(),winnerRollbackCalls=new AtomicInteger();
    private final AtomicBoolean winnerCommitAcknowledged=new AtomicBoolean(),winnerRollbackAcknowledged=new AtomicBoolean(),waiterCommitAcknowledged=new AtomicBoolean();
    private final CountDownLatch winnerInserted=new CountDownLatch(1),waiterEnteredInsert=new CountDownLatch(1),release=new CountDownLatch(1);
    private volatile long observationDeadline;

    RiskCaptureJdbcBarrier(DataSource delegate,boolean rollbackWinner){this.delegate=java.util.Objects.requireNonNull(delegate);this.rollbackWinner=rollbackWinner;}
    @Override public Connection getConnection()throws SQLException{try{return wrap(delegate.getConnection());}catch(SQLException|RuntimeException rejected){throw failure();}}
    @Override public Connection getConnection(String user,String password)throws SQLException{throw failure();}
    private Connection wrap(Connection physical)throws SQLException {
        try {
            int role=connections.incrementAndGet();if(role>2)throw failure();int pid;
            try(var query=physical.prepareStatement("select pg_catalog.pg_backend_pid()")){
                query.setQueryTimeout(1);query.setMaxRows(2);
                try(var rows=query.executeQuery()){
                    if(!rows.next()||!(rows.getObject(1) instanceof Integer value)||value<1||rows.next())throw failure();pid=value;
                }
            }
            (role==1?winnerPid:waiterPid).set(pid);
            return (Connection)Proxy.newProxyInstance(ConnectionProxy.class.getClassLoader(),new Class<?>[]{ConnectionProxy.class},(proxy,method,args)->{
                if(method.getDeclaringClass()==Object.class)return objectMethod(proxy,method,args,"RiskCaptureBarrierConnection");
                if(method.getName().equals("getTargetConnection"))return physical;
                boolean commit=method.getName().equals("commit")&&method.getParameterCount()==0;
                boolean rollback=method.getName().equals("rollback")&&method.getParameterCount()==0;
                if(role==1&&commit)winnerCommitCalls.incrementAndGet();if(role==1&&rollback)winnerRollbackCalls.incrementAndGet();
                Object value=invoke(physical,method,args);
                if(role==1&&commit)winnerCommitAcknowledged.set(true);if(role==1&&rollback)winnerRollbackAcknowledged.set(true);if(role==2&&commit)waiterCommitAcknowledged.set(true);
                if(value instanceof PreparedStatement statement&&method.getName().equals("prepareStatement")&&args!=null&&args.length>0
                        &&args[0] instanceof String sql&&sql.strip().equals(RiskChangeCauseRepository.INSERT_SQL.strip()))return statement(statement,role);
                return value;
            });
        }catch(SQLException|RuntimeException failure){try{physical.close();}catch(SQLException|RuntimeException ignored){}throw failure();}
    }
    private PreparedStatement statement(PreparedStatement physical,int role){
        return (PreparedStatement)Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),new Class<?>[]{PreparedStatement.class},(proxy,method,args)->{
            if(method.getDeclaringClass()==Object.class)return objectMethod(proxy,method,args,"RiskCaptureBarrierStatement");
            if(method.getName().equals("executeQuery")&&method.getParameterCount()==0){
                if(role==2)waiterEnteredInsert.countDown();
                ResultSet rows=(ResultSet)invoke(physical,method,args);if(role==2)return rows;
                var first=new AtomicBoolean(true);
                return Proxy.newProxyInstance(ResultSet.class.getClassLoader(),new Class<?>[]{ResultSet.class},(rowProxy,rowMethod,rowArgs)->{
                    if(rowMethod.getDeclaringClass()==Object.class)return objectMethod(rowProxy,rowMethod,rowArgs,"RiskCaptureBarrierRows");
                    Object value=invoke(rows,rowMethod,rowArgs);
                    if(rowMethod.getName().equals("next")&&Boolean.TRUE.equals(value)&&first.compareAndSet(true,false)){
                        observationDeadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(OBSERVE_MILLIS);winnerInserted.countDown();
                        try{if(!release.await(HOLD_MILLIS,TimeUnit.MILLISECONDS))throw new SQLTimeoutException("CI capture barrier expired");}
                        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new SQLNonTransientException("CI capture barrier interrupted");}
                        if(rollbackWinner)throw new SQLNonTransientException("CI capture rollback injection");
                    }
                    return value;
                });
            }
            return invoke(physical,method,args);
        });
    }
    void awaitWinnerInsert()throws Exception {
        if(!winnerInserted.await(5,TimeUnit.SECONDS))throw new AssertionError("Winner did not reach a real returned INSERT row");
    }
    LockObservation awaitActualUniqueIndexBlock(Connection admin)throws Exception {
        long remaining=observationDeadline-System.nanoTime();
        if(remaining<=0||!waiterEnteredInsert.await(remaining,TimeUnit.NANOSECONDS))throw new AssertionError("Waiter did not enter the real INSERT within the observation budget");
        if(winnerPid.get()<1||waiterPid.get()<1||winnerPid.get()==waiterPid.get())throw new AssertionError("Capture tasks must use distinct physical PostgreSQL connections");
        while(System.nanoTime()<observationDeadline){
            try(var query=admin.prepareStatement("""
                    select ?=any(pg_catalog.pg_blocking_pids(pid)),wait_event_type,wait_event
                      from pg_catalog.pg_stat_activity where pid=?
                    """)){
                query.setQueryTimeout(1);query.setMaxRows(2);query.setInt(1,winnerPid.get());query.setInt(2,waiterPid.get());
                try(var rows=query.executeQuery()){
                    if(rows.next()){
                        boolean blocked=Boolean.TRUE.equals(rows.getObject(1));boolean uniqueWait="Lock".equals(rows.getString(2))&&"transactionid".equals(rows.getString(3));
                        if(rows.next())throw new AssertionError("Unexpected PostgreSQL activity multiplicity");
                        if(blocked&&uniqueWait)return new LockObservation(true,true);
                    }
                }
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("Real unique-index transaction blocking was not observed within the bounded gate");
    }
    void releaseWinner(){release.countDown();}
    int physicalConnectionCount(){return connections.get();}
    int winnerCommitCalls(){return winnerCommitCalls.get();}
    int winnerRollbackCalls(){return winnerRollbackCalls.get();}
    boolean winnerCommitted(){return winnerCommitAcknowledged.get();}
    boolean winnerRolledBack(){return winnerRollbackAcknowledged.get();}
    boolean waiterCommitted(){return waiterCommitAcknowledged.get();}
    record LockObservation(boolean blockedByWinner,boolean transactionIdWait) { }
    @Override public void close(){releaseWinner();}
    private static Object objectMethod(Object proxy,Method method,Object[] args,String name){
        return switch(method.getName()){case "toString"->name+"[redacted]";case "hashCode"->System.identityHashCode(proxy);case "equals"->proxy==args[0];default->throw new IllegalStateException("Unsupported proxy object operation");};
    }
    private static Object invoke(Object target,Method method,Object[] args)throws Throwable {
        try{return method.invoke(target,args);}
        catch(InvocationTargetException wrapped){Throwable cause=wrapped.getCause();if(cause instanceof SQLException sql)throw new SQLException("CI delegated JDBC operation failed",sql.getSQLState());throw cause;}
        catch(IllegalAccessException|IllegalArgumentException failure){throw failure();}
    }
    private static SQLException failure(){return new SQLException("CI capture barrier JDBC boundary failed");}
}
