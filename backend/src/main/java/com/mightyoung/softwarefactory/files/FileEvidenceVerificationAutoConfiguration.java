package com.mightyoung.softwarefactory.files;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/** Supplies a denying verifier only when no trusted storage verifier has been configured. */
@AutoConfiguration
public class FileEvidenceVerificationAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(StorageContentVerificationPort.class)
    StorageContentVerificationPort unconfiguredStorageContentVerificationAdapter() {
        return new UnconfiguredStorageContentVerificationAdapter();
    }
}
