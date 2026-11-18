package com.mightyoung.softwarefactory.identity;

import org.jooq.SQLDialect;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import javax.sql.DataSource;
import java.util.Objects;
import static com.mightyoung.softwarefactory.identity.SessionSensitiveContracts.Enforcement;

/** Explicit construction only until the mandatory protected-consumer wiring is complete. */
public final class JooqSessionAuthorityControlReader implements SessionAuthorityControlReader {
    private final DataSource source;
    private final SessionAuthorityControlTransaction transactions;
    private final SessionSensitiveKeyProvider keys;
    public JooqSessionAuthorityControlReader(DataSource source,SessionAuthorityControlTransaction transactions,SessionSensitiveKeyProvider keys){
        this.source=Objects.requireNonNull(source);this.transactions=Objects.requireNonNull(transactions);this.keys=Objects.requireNonNull(keys);
    }
    @Override public Enforcement read(){
        return transactions.readControl(boundary->{
            var connection=DataSourceUtils.getConnection(source);
            try {
                transactions.requireControl(boundary,source,connection);
                var db=DSL.using(connection,SQLDialect.POSTGRES,new Settings().withExecuteLogging(false));
                Enforcement mode=SessionAuthoritySql.control(db);
                if(mode==Enforcement.ENFORCING)SessionAuthoritySql.requireRetainedKeys(db,Objects.requireNonNull(keys.requireRetainedKeys()));
                transactions.requireControl(boundary,source,connection);return mode;
            }catch(RuntimeException failure){
                if(TransactionSynchronizationManager.getResource(source) instanceof ConnectionHolder holder)holder.setRollbackOnly();
                throw SessionSensitiveException.unavailable();
            }finally{DataSourceUtils.releaseConnection(connection,source);}
        });
    }
}
