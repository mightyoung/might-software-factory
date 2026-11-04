package com.mightyoung.softwarefactory.dualprevention;

import com.mightyoung.softwarefactory.dualprevention.asset.AssetFoundationService;
import com.mightyoung.softwarefactory.support.TransactionSqlSealGate.SqlTemplate;
import java.util.List;

/** Narrow module-root API for the source-owned finite command SQL catalog; exposes no business services or DTOs. */
public final class AssetFoundationSqlTemplates {
    private AssetFoundationSqlTemplates() {}
    public static List<SqlTemplate> commandTemplates() { return AssetFoundationService.businessSqlTemplates(); }
}
