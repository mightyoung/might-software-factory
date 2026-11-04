package com.mightyoung.softwarefactory;

import com.mightyoung.softwarefactory.dualprevention.AssetFoundationCommandCatalog;
import org.jooq.SQLDialect;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.modulith.events.core.EventPublicationRepository;
import org.springframework.modulith.events.jdbc.JdbcRepositorySettings;

@Configuration(proxyBeanMethods=false)
class AssetFoundationRuntimeBootstrapConfiguration {
    @Bean
    AssetFoundationCommandCatalog assetFoundationCommandCatalog(EventPublicationRepository publications,
            JdbcRepositorySettings settings) {
        var renderer=DSL.using(SQLDialect.POSTGRES,new Settings().withExecuteLogging(false).withFetchWarnings(false));
        return new AssetFoundationCommandCatalog(AssetFoundationSqlCatalog.bootstrapTemplates(renderer,publications,settings));
    }
}
