package com.mightyoung.softwarefactory.identity;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Instant;
import java.util.function.Function;

/** A standalone, non-HUMAN read boundary for persisted activation preflight only. */
public interface SessionAuthorityControlTransaction {
    interface Boundary { Instant observedAt(); }
    <T>T readControl(Function<Boundary,T> work);
    void requireControl(Boundary boundary,DataSource source,Connection connection);
}
