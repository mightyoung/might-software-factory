package com.mightyoung.softwarefactory;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

class CompatibilityClasspathTest {

    @Test
    void selectedFrameworksAndPostgresInfrastructureArePresentOnTheClasspath() {
        assertThatCode(() -> {
            Class.forName("org.springframework.boot.SpringApplication");
            Class.forName("org.springframework.modulith.Modulithic");
            Class.forName("org.flowable.engine.ProcessEngine");
            Class.forName("org.jooq.DSLContext");
            Class.forName("org.flywaydb.core.Flyway");
            Class.forName("org.springframework.session.jdbc.JdbcIndexedSessionRepository");
            Class.forName("org.postgresql.Driver");
        }).doesNotThrowAnyException();
    }
}
