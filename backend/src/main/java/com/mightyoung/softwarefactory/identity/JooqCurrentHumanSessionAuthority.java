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
    private final boolean sealedCommandSql;
    public JooqCurrentHumanSessionAuthority(DataSource source,HumanSessionAuthorityTransaction callers,SessionSensitiveKeyProvider keys){
        this(source,callers,keys,false);
    }
    /** The dedicated G014 graph uses typed result shapes and disables optional warning fetching. */
    public JooqCurrentHumanSessionAuthority(DataSource source,HumanSessionAuthorityTransaction callers,SessionSensitiveKeyProvider keys,boolean sealedCommandSql){
        this.source=Objects.requireNonNull(source);this.callers=Objects.requireNonNull(callers);this.keys=Objects.requireNonNull(keys);
        this.sealedCommandSql=sealedCommandSql;
    }
    @Override public Enforcement requireGuardedCurrent(HumanSessionAuthorityTransaction.WriteScope scope,HumanSessionAuthorityTransaction.GuardedBoundary boundary){
        return requireGuardedCheckedCurrent(scope,boundary).enforcement();
    }
    @Override public Enforcement requireReadSnapshotCurrent(CapturedHumanRequest request,HumanSessionAuthorityTransaction.ReadBoundary boundary){
        return requireReadCheckedCurrent(request,boundary).enforcement();
    }
    @Override public CheckedHumanSessionAuthority requireGuardedCheckedCurrent(HumanSessionAuthorityTransaction.WriteScope scope,
            HumanSessionAuthorityTransaction.GuardedBoundary boundary){
        Instant observedAt=boundary.observedAt();
        Enforcement mode=check(scope.request(),observedAt,connection->callers.requireGuarded(scope,boundary,source,connection));
        return checked(scope,boundary,mode,observedAt,connection->callers.requireGuarded(scope,boundary,source,connection));
    }
    @Override public CheckedHumanSessionAuthority requireReadCheckedCurrent(CapturedHumanRequest request,
            HumanSessionAuthorityTransaction.ReadBoundary boundary){
        Instant observedAt=boundary.observedAt();
        Enforcement mode=check(request,observedAt,connection->callers.requireRead(request,boundary,source,connection));
        return checked(request,boundary,mode,observedAt,connection->callers.requireRead(request,boundary,source,connection));
    }
    private CheckedHumanSessionAuthority checked(Object scope,Object boundary,Enforcement mode,Instant time,Consumer<Connection> assertion){
        if(!(TransactionSynchronizationManager.getResource(source) instanceof ConnectionHolder holder)) throw SessionSensitiveException.unavailable();
        Connection original=holder.getConnection();Thread owner=Thread.currentThread();
        return new CheckedHumanSessionAuthority(scope,boundary,mode,time,()->{
            if(owner!=Thread.currentThread() || TransactionSynchronizationManager.getResource(source)!=holder
                    || holder.getConnection()!=original || holder.isRollbackOnly()){
                holder.setRollbackOnly();throw SessionSensitiveException.unavailable();
            }
            assertion.accept(original);
        });
    }
    private Enforcement check(CapturedHumanRequest request,Instant observedAt,Consumer<Connection> assertion){
        Connection connection=DataSourceUtils.getConnection(source);
        try {
            assertion.accept(connection);
            var settings=new Settings().withExecuteLogging(false);
            if(sealedCommandSql)settings.withFetchWarnings(false);
            var db=DSL.using(connection,SQLDialect.POSTGRES,settings);
            Enforcement mode=SessionAuthoritySql.control(db,sealedCommandSql);
            if(mode==Enforcement.ENFORCING)SessionAuthoritySql.current(db,request,observedAt,Objects.requireNonNull(keys.requireRetainedKeys()),sealedCommandSql);
            assertion.accept(connection);return mode;
        }catch(RuntimeException failure){
            if(TransactionSynchronizationManager.getResource(source) instanceof ConnectionHolder holder)holder.setRollbackOnly();
            throw SessionSensitiveException.unavailable();
        }finally{DataSourceUtils.releaseConnection(connection,source);}
    }
}
