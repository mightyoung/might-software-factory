package com.mightyoung.softwarefactory.authorization;

import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.DefaultTransactionStatus;

/**
 * Identity transaction owner that removes driver failure details before Spring logs commit
 * failures. It preserves the canonical datasource and normal Spring transaction lifecycle.
 */
public final class SessionSensitiveJdbcTransactionManager extends JdbcTransactionManager {
    public SessionSensitiveJdbcTransactionManager(DataSource source) { super(source); }

    @Override protected RuntimeException translateException(String task,SQLException failure) {
        // JdbcTransactionManager's translator and its resulting cause chain otherwise retain
        // driver messages before the outer identity service can redact them.
        return super.translateException("Sensitive JDBC transaction operation",safeSqlException(failure));
    }

    @Override protected void doCommit(DefaultTransactionStatus status) {
        try { super.doCommit(status); }
        catch(RuntimeException failure) { throw safeRuntime(failure,"Sensitive transaction commit outcome unavailable"); }
        catch(Error failure) { throw new AssertionError("Sensitive transaction commit outcome unavailable"); }
    }

    @Override protected void doRollback(DefaultTransactionStatus status) {
        try { super.doRollback(status); }
        catch(RuntimeException failure) { throw safeRuntime(failure,"Sensitive transaction rollback outcome unavailable"); }
        catch(Error failure) { throw new AssertionError("Sensitive transaction rollback outcome unavailable"); }
    }

    private static RuntimeException safeRuntime(RuntimeException failure,String message) {
        if(failure instanceof UnexpectedRollbackException)return new UnexpectedRollbackException(message);
        return failure instanceof TransactionException?new TransactionSystemException(message):new IllegalStateException(message);
    }

    /** Preserve JDBC's standard classification inputs, never its message, cause or driver type. */
    private static SQLException safeSqlException(SQLException failure) {
        SQLException clean=safeSqlNode(failure);
        // Spring's default SQL-state translator consults the first next exception when the
        // main state is absent; its BatchUpdateException path also uses that next state.
        SQLException next=failure.getNextException();
        if(next!=null)clean.setNextException(safeSqlNode(next));
        return clean;
    }

    private static SQLException safeSqlNode(SQLException failure) {
        String state=failure.getSQLState();
        if(state!=null&&!state.matches("[A-Z0-9]{5}"))state=null;
        int code=failure.getErrorCode();String message="Sensitive JDBC transaction operation unavailable";
        if(failure instanceof java.sql.BatchUpdateException)return new java.sql.BatchUpdateException(message,state,code,new int[0]);
        if(failure instanceof java.sql.SQLTransientConnectionException)return new java.sql.SQLTransientConnectionException(message,state,code);
        if(failure instanceof java.sql.SQLTransactionRollbackException)return new java.sql.SQLTransactionRollbackException(message,state,code);
        if(failure instanceof java.sql.SQLTimeoutException)return new java.sql.SQLTimeoutException(message,state,code);
        if(failure instanceof java.sql.SQLTransientException)return new java.sql.SQLTransientException(message,state,code);
        if(failure instanceof java.sql.SQLDataException)return new java.sql.SQLDataException(message,state,code);
        if(failure instanceof java.sql.SQLFeatureNotSupportedException)return new java.sql.SQLFeatureNotSupportedException(message,state,code);
        if(failure instanceof java.sql.SQLIntegrityConstraintViolationException)return new java.sql.SQLIntegrityConstraintViolationException(message,state,code);
        if(failure instanceof java.sql.SQLInvalidAuthorizationSpecException)return new java.sql.SQLInvalidAuthorizationSpecException(message,state,code);
        if(failure instanceof java.sql.SQLNonTransientConnectionException)return new java.sql.SQLNonTransientConnectionException(message,state,code);
        if(failure instanceof java.sql.SQLSyntaxErrorException)return new java.sql.SQLSyntaxErrorException(message,state,code);
        if(failure instanceof java.sql.SQLNonTransientException)return new java.sql.SQLNonTransientException(message,state,code);
        if(failure instanceof java.sql.SQLRecoverableException)return new java.sql.SQLRecoverableException(message,state,code);
        return new SQLException(message,state,code);
    }
}
