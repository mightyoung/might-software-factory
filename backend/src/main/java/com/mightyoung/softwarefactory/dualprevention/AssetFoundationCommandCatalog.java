package com.mightyoung.softwarefactory.dualprevention;

import com.mightyoung.softwarefactory.support.TransactionSqlSealGate.SqlTemplate;
import java.util.List;

/** Immutable construction data; this descriptor grants no connection or transaction authority. */
public record AssetFoundationCommandCatalog(List<SqlTemplate> templates) {
    public AssetFoundationCommandCatalog { templates=List.copyOf(templates); }
}
