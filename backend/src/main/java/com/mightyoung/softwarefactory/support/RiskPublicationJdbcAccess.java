package com.mightyoung.softwarefactory.support;

import java.sql.Connection;
import java.util.function.Supplier;

/** Closed execution authorities; neither exports a connection or transaction manager. */
sealed interface RiskPublicationJdbcAccess permits RiskJdbcTransactionOwner, G014RiskPublicationJdbcAccess {
    boolean hasActiveTransaction();
    <T> T operation(RiskPublicationSqlCatalog.Operation operation, Supplier<T> body);
    void requireOperation(RiskPublicationSqlCatalog.Operation operation);
    void requireConnection(Connection connection);
    Object operationEpoch();
    void requireOperationEpoch(Object epoch);
    void poisonIfActive();
}
