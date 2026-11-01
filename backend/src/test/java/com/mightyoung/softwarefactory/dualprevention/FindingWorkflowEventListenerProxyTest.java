package com.mightyoung.softwarefactory.dualprevention;

import com.mightyoung.softwarefactory.dualprevention.FindingWorkflowEventListener;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import static org.assertj.core.api.Assertions.assertThat;

class FindingWorkflowEventListenerProxyTest {

    @Test
    void modulithListenerCanBeCreatedWithTheConfiguredProxyPolicy() {
        try (var context = new AnnotationConfigApplicationContext(ListenerProxyConfiguration.class)) {
            var listener = context.getBean(FindingWorkflowEventListener.class);

            assertThat(AopUtils.isAopProxy(listener)).isTrue();
            assertThat(AopUtils.isCglibProxy(listener)).isTrue();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAsync(proxyTargetClass = false)
    @EnableTransactionManagement(proxyTargetClass = false)
    static class ListenerProxyConfiguration {

        @Bean
        FindingWorkflowEventListener findingWorkflowEventListener() {
            return new FindingWorkflowEventListener();
        }
    }
}
