package com.mightyoung.softwarefactory;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

@TestConfiguration(proxyBeanMethods = false)
class P0FailingProjectionDelegateConfiguration {

    @Bean(name = "p0FailingProjectionDelegate")
    P0FailingProjectionDelegate p0FailingProjectionDelegate(JdbcTemplate jdbc) {
        return new P0FailingProjectionDelegate(jdbc);
    }
}
