package com.mightyoung.softwarefactory.workflow;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class FindingReviewDefinitionConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class);
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(FindingReviewDefinitionProperties.class)
    static class Config { }

    @Test void defaultsAreDisabledWithNoPins() {
        runner.run(context -> {
            var properties = context.getBean(FindingReviewDefinitionProperties.class);
            assertThat(properties.initiationEnabled()).isFalse();
            assertThat(properties.tenantPins()).isEmpty();
        });
    }
    @Test void bindsExactTenantPinAndRejectsMalformedPin() {
        String prefix = "software-factory.workflow.finding-review.tenant-pins."
                + UUID.randomUUID() + ".";
        runner.withPropertyValues(prefix + "definition-id=exact-id", prefix + "resource-sha256=" + "a".repeat(64))
                .run(context -> assertThat(context.getBean(FindingReviewDefinitionProperties.class).tenantPins())
                        .hasSize(1));
        runner.withPropertyValues(prefix + "definition-id=exact-id", prefix + "resource-sha256=BAD")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("software-factory.workflow.finding-review.tenant-pins.*.definition-id=wildcard")
                .run(context -> assertThat(context).hasFailed());
    }
    @Test void rejectsMissingDigestZeroTenantAndOverLimitPins() {
        assertThatThrownBy(() -> new FindingReviewDefinitionProperties.Pin("id", null))
                .isInstanceOf(IllegalArgumentException.class);
        var pin = new FindingReviewDefinitionProperties.Pin("id", "a".repeat(64));
        assertThatThrownBy(() -> new FindingReviewDefinitionProperties(true, Map.of(new UUID(0, 0), pin)))
                .isInstanceOf(IllegalArgumentException.class);
        var pins = new HashMap<UUID, FindingReviewDefinitionProperties.Pin>();
        for (int i = 1; i <= 257; i++) pins.put(new UUID(0, i), pin);
        assertThatThrownBy(() -> new FindingReviewDefinitionProperties(true, pins))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void mainApplicationConfigExplicitlyDisablesStartupDeploymentAndInitiation() {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        var properties = yaml.getObject();
        assertThat(properties.getProperty("flowable.check-process-definitions")).isEqualTo("false");
        assertThat(properties.getProperty("flowable.database-schema-update")).isEqualTo("false");
        assertThat(properties.getProperty("software-factory.workflow.finding-review.initiation-enabled"))
                .isEqualTo("false");
    }
}
