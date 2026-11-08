package com.mightyoung.softwarefactory.dualprevention;

import com.mightyoung.softwarefactory.dualprevention.risk.RiskScenarioService;
import com.mightyoung.softwarefactory.support.TransactionSqlSealGate.SqlTemplate;
import java.util.List;

/** Module-root construction API: finite SQL descriptors only, never business services or command authority. */
public final class RiskScenarioSqlTemplates {
    private RiskScenarioSqlTemplates() { }
    public static List<SqlTemplate> commandTemplates() { return RiskScenarioService.businessSqlTemplates(); }
    public static SqlTemplate auditTemplate() { return RiskScenarioService.auditSqlTemplate(); }
    public static String publicationEffect() { return RiskScenarioService.PUBLICATION_EFFECT; }
}
