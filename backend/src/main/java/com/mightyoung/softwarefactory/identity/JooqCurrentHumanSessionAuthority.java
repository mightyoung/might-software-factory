package com.mightyoung.softwarefactory.identity;

import org.jooq.SQLDialect;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Consumer;
import static com.mightyoung.softwarefactory.identity.SessionSensitiveContracts.Enforcement;

/** No bean/flag fallback: every invocation positively reads the persisted singleton. */
public final class JooqCurrentHumanSessionAuthority implements CurrentHumanSessionAuthority {
    private final DataSource source;
    private final HumanSessionAuthorityTransaction callers;
    private final SessionSensitiveKeyProvider keys;
    public JooqCurrentHumanSessionAuthority(DataSource source,HumanSessionAuthorityTransaction callers,SessionSensitiveKeyProvider keys){
        this.source=Objects.requireNonNull(source);this.callers=Objects.requireNonNull(callers);this.keys=Objects.requireNonNull(keys);
    }
    @Override public Enforcement requireGuardedCurrent(HumanSessionAuthorityTransaction.WriteScope scope,HumanSessionAuthorityTransaction.GuardedBoundary boundary){
        return check(scope.request(),boundary.observedAt(),connection->callers.requireGuarded(scope,boundary,source,connection));
    }
    @Override public Enforcement requireReadSnapshotCurrent(CapturedHumanRequest request,HumanSessionAuthorityTransaction.ReadBoundary boundary){
        return check(request,boundary.observedAt(),connection->callers.requireRead(request,boundary,source,connection));
    }
    private Enforcement check(CapturedHumanRequest request,Instant observedAt,Consumer<Connection> assertion){
        Connection connection=DataSourceUtils.getConnection(source);
        try {
            assertion.accept(connection);
            var db=DSL.using(connection,SQLDialect.POSTGRES,new Settings().withExecuteLogging(false));
            Enforcement mode=SessionAuthoritySql.control(db);
            if(mode==Enforcement.ENFORCING)SessionAuthoritySql.current(db,request,observedAt,Objects.requireNonNull(keys.requireRetainedKeys()));
            assertion.accept(connection);return mode;
        }catch(RuntimeException failure){
            if(TransactionSynchronizationManager.getResource(source) instanceof ConnectionHolder holder)holder.setRollbackOnly();
            throw SessionSensitiveException.unavailable();
        }finally{DataSourceUtils.releaseConnection(connection,source);}
    }
}
