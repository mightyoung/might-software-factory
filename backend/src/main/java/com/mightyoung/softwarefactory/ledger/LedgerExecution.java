package com.mightyoung.softwarefactory.ledger;

import com.mightyoung.softwarefactory.authorization.ApplicationActionAuthorization;
import com.mightyoung.softwarefactory.authorization.ApplicationActionAuthorization.Decision;
import javax.sql.DataSource;
import java.sql.Connection;
import org.jooq.DSLContext;

/** Issued by the runtime inside its borrowed callback. Policies cannot construct or retain usable executions. */
public final class LedgerExecution {
    private final ApplicationActionAuthorization authority;
    private final Decision decision;
    private final DataSource source;
    private final Connection connection;
    private final DSLContext dsl;
    LedgerExecution(ApplicationActionAuthorization authority, Decision decision, DataSource source, Connection connection, DSLContext dsl) {
        this.authority=authority; this.decision=decision; this.source=source; this.connection=connection; this.dsl=dsl;
    }
    public Decision decision() { requireCurrent(); return decision; }
    public DSLContext dsl() { requireCurrent(); return dsl; }
    public void requireCurrent() { authority.requireSqlConnection(decision,source,connection); }
}
