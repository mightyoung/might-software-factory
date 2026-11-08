package com.mightyoung.softwarefactory.authorization;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import static org.assertj.core.api.Assertions.*;

/** Real Spring manager and real H2 commit; the injected error occurs after the physical commit. */
class SessionSensitiveJdbcTransactionManagerTest {
    private static final String CANARY="PRIVATE_SESSION_COMMIT_FAILURE";
    enum Fault { SQL,SQL_UNKNOWN,SQL_NULL,SQL_SUBCLASS,SQL_NEXT,SQL_BATCH,SQL_INTEGRITY,RUNTIME,ERROR }

    @ParameterizedTest @EnumSource(Fault.class)
    void committedDriverFailureCannotReachFrameworkLogs(Fault fault)throws Exception {
        assertPrivateDriverFailure(fault,true);
    }

    @ParameterizedTest @EnumSource(Fault.class)
    void rollbackDriverFailureCannotReachFrameworkLogs(Fault fault)throws Exception {
        assertPrivateDriverFailure(fault,false);
    }

    private void assertPrivateDriverFailure(Fault fault,boolean commit)throws Exception {
        for(Level level:new Level[]{Level.INFO,Level.DEBUG}) {
            String url="jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1";
            var fired=new AtomicBoolean();var commits=new AtomicInteger();
            DataSource source=new AbstractDataSource(){
                @Override public Connection getConnection()throws SQLException {
                    Connection physical=DriverManager.getConnection(url);
                    return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                        if(method.getName().equals("toString"))return "Sensitive regression connection";
                        Object result;
                        try{result=method.invoke(physical,args);}catch(InvocationTargetException failure){throw failure.getCause();}
                        if(method.getName().equals(commit?"commit":"rollback")) {
                            commits.incrementAndGet();
                            if(fired.compareAndSet(false,true)) {
                                throw driverFault(fault);
                            }
                        }
                        return result;
                    });
                }
                @Override public Connection getConnection(String user,String password)throws SQLException {throw new SQLException("Fixed test source");}
            };
            var db=new JdbcTemplate(source);db.execute("create table proof_probe(id integer primary key)");
            Logger logger=(Logger)LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
            Level previous=logger.getLevel();var appender=new ListAppender<ILoggingEvent>();appender.start();logger.addAppender(appender);logger.setLevel(level);
            Throwable failure;
            try {
                var template=new TransactionTemplate(manager(source));
                failure=catchThrowable(()->template.executeWithoutResult(status->{
                    db.update("insert into proof_probe values(1)");
                    if(!commit)throw new IllegalStateException("Safe application refusal");
                }));
            } finally {logger.detachAppender(appender);logger.setLevel(previous);appender.stop();}
            try {
                assertThat(failure!=null).as("uncertain commit propagates to caller").isTrue();
                assertThat(commits).hasValue(1);
                assertThat(db.queryForObject("select count(*) from proof_probe",Integer.class)).isEqualTo(commit?1:0);
                StringBuilder messages=new StringBuilder();
                for(var event:appender.list){messages.append(event.getFormattedMessage());for(var error=event.getThrowableProxy();error!=null;error=error.getCause())messages.append(error.getMessage());}
                assertThat(messages.indexOf(CANARY)<0).as("framework diagnostics contain no driver canary").isTrue();
                assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
            } finally {db.execute("shutdown");}
        }
    }

    @ParameterizedTest @EnumSource(Fault.class)
    void sanitizedCommitKeepsOriginalSpringOutcomeAndCleanupBranch(Fault fault)throws Exception {
        Trace original=commitTrace(fault,false),safe=commitTrace(fault,true);
        assertThat(safe).isEqualTo(original);
        assertThat(safe.commits()).isEqualTo(1);
        assertThat(safe.rows()).isEqualTo(1);
        assertThat(safe.closes()).isEqualTo(1);
    }

    @Test void publicIssuerFactoryOwnsItsTransactionAndAuditBoundary() {
        var methods=java.util.Arrays.stream(com.mightyoung.softwarefactory.ReauthIssuerFactory.class.getDeclaredMethods())
                .filter(method->method.getName().equals("create")&&java.lang.reflect.Modifier.isPublic(method.getModifiers())).toList();
        assertThat(methods).hasSize(1);
        assertThat(methods.getFirst().getParameterTypes()).doesNotContain(
                com.mightyoung.softwarefactory.identity.SessionSensitiveSecurityTransaction.class,
                com.mightyoung.softwarefactory.identity.SessionSensitiveAudit.class);
    }

    private record Trace(int commits,int rollbacks,int closes,int completion,int rows,boolean autoCommit,boolean readOnly,int isolation) {}
    private Trace commitTrace(Fault fault,boolean safe)throws Exception {
        String url="jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1";
        var commits=new AtomicInteger();var rollbacks=new AtomicInteger();var closes=new AtomicInteger();var completion=new AtomicInteger(-1);
        boolean[] reset={false,true};int[] isolation={-1};
        DataSource source=new AbstractDataSource(){
            @Override public Connection getConnection()throws SQLException {
                Connection physical=DriverManager.getConnection(url);boolean[] owned={false};
                return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                    if(method.getName().equals("toString"))return "Outcome regression connection";
                    if(method.getName().equals("close")&&owned[0]){closes.incrementAndGet();reset[0]=physical.getAutoCommit();reset[1]=physical.isReadOnly();isolation[0]=physical.getTransactionIsolation();}
                    Object result;try{result=method.invoke(physical,args);}catch(InvocationTargetException failure){throw failure.getCause();}
                    if(method.getName().equals("setAutoCommit")&&Boolean.FALSE.equals(args[0]))owned[0]=true;
                    if(method.getName().equals("rollback")&&owned[0])rollbacks.incrementAndGet();
                    if(method.getName().equals("commit")&&owned[0]){
                        commits.incrementAndGet();
                        throw driverFault(fault);
                    }
                    return result;
                });
            }
            @Override public Connection getConnection(String user,String password)throws SQLException {throw new SQLException("Fixed test source");}
        };
        var db=new JdbcTemplate(source);db.execute("create table proof_probe(id integer primary key)");
        try {
            var tx=new TransactionTemplate(safe?manager(source):new JdbcTransactionManager(source));
            Throwable failure=catchThrowable(()->tx.executeWithoutResult(status->{
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCompletion(int value){completion.set(value);}});
                db.update("insert into proof_probe values(1)");
            }));
            assertThat(failure!=null).isTrue();
            assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
            return new Trace(commits.get(),rollbacks.get(),closes.get(),completion.get(),db.queryForObject("select count(*) from proof_probe",Integer.class),reset[0],reset[1],isolation[0]);
        } finally {db.execute("shutdown");}
    }

    private Throwable driverFault(Fault fault){return switch(fault){
        case SQL->new SQLException(CANARY,"08006");
        case SQL_UNKNOWN->new SQLException(CANARY,"ZZ999");
        case SQL_NULL->new SQLException(CANARY,(String)null);
        case SQL_SUBCLASS->new java.sql.SQLTransientConnectionException(CANARY,(String)null);
        case SQL_NEXT->{var failure=new SQLException(CANARY,(String)null);failure.setNextException(new SQLException(CANARY,"08006"));yield failure;}
        case SQL_BATCH->{var failure=new java.sql.BatchUpdateException(CANARY,null,0,new int[0]);failure.setNextException(new SQLException(CANARY,"08006"));yield failure;}
        case SQL_INTEGRITY->new SQLException(CANARY,"23514");
        case RUNTIME->new IllegalStateException(CANARY);
        case ERROR->new AssertionError(CANARY);
    };}
    private JdbcTransactionManager manager(DataSource source){return new SessionSensitiveJdbcTransactionManager(source);}
}
