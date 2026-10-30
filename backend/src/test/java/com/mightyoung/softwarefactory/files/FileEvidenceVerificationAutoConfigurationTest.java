package com.mightyoung.softwarefactory.files;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FileEvidenceVerificationAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FileEvidenceVerificationAutoConfiguration.class));

    @Test
    void registersFailClosedDefaultWhenNoVerifierIsConfigured() {
        contextRunner.run(context -> {
            assertThat(context.getBeansOfType(StorageContentVerificationPort.class)).hasSize(1);
            assertThat(context.getBean(StorageContentVerificationPort.class))
                    .isInstanceOf(UnconfiguredStorageContentVerificationAdapter.class);
        });
    }

    @Test
    void aSingleTrustedVerifierReplacesTheDefaultWithoutAmbiguousInjection() {
        contextRunner.withUserConfiguration(TrustedVerifierConfiguration.class).run(context -> {
            assertThat(context.getBeansOfType(StorageContentVerificationPort.class)).hasSize(1);
            assertThat(context.getBean(StorageContentVerificationPort.class))
                    .isInstanceOf(TestTrustedVerifier.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class TrustedVerifierConfiguration {
        @Bean
        StorageContentVerificationPort testTrustedVerifier() {
            return new TestTrustedVerifier();
        }
    }

    static class TestTrustedVerifier implements StorageContentVerificationPort {
        @Override
        public Optional<ContentVerificationReceipt> findVerifiedContent(EvidenceFileUse use, FileObjectMetadata file) {
            return Optional.empty();
        }

        @Override
        public Optional<ContentVerificationReceipt> findByReceiptId(UUID tenantId, UUID receiptId) {
            return Optional.empty();
        }
    }
}
