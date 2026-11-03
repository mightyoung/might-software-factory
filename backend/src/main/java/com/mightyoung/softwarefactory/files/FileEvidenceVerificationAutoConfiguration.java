package com.mightyoung.softwarefactory.files;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.annotation.Value;

import java.nio.file.Path;
import java.util.Base64;

/** Supplies a denying verifier only when no trusted storage verifier has been configured. */
@AutoConfiguration
@Import(FileIngestionConfiguration.class)
public class FileEvidenceVerificationAutoConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "software-factory.files.local", name = {
            "root", "receipt-hmac-key-base64", "receipt-key-id"
    })
    @ConditionalOnMissingBean({StorageContentVerificationPort.class, VerifiedStorageVersionReadPort.class,
            TrustedContentReceiptIssuer.class})
    LocalPrivateFileStorageAdapter localPrivateFileStorageAdapter(
            @Value("${software-factory.files.local.root}") Path root,
            @Value("${software-factory.files.local.receipt-hmac-key-base64}") String encodedKey,
            @Value("${software-factory.files.local.verifier-id:local-private-filesystem}") String verifierId,
            @Value("${software-factory.files.local.receipt-key-id}") String receiptKeyId,
            @Value("${software-factory.files.local.policy-version:local-content-v1}") String policyVersion,
            @Value("${software-factory.files.local.max-bytes:67108864}") long maxBytes) {
        byte[] key;
        try {
            key = Base64.getDecoder().decode(encodedKey);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("The local file trust configuration is invalid");
        }
        return new LocalPrivateFileStorageAdapter(root, key, verifierId, receiptKeyId, policyVersion, maxBytes);
    }

    @Bean
    @ConditionalOnMissingBean(UploadProducerReadiness.class)
    UploadProducerReadiness unconfiguredUploadProducerReadiness() {
        return new UnconfiguredUploadProducerReadiness();
    }

    @Bean
    @ConditionalOnMissingBean(StorageContentVerificationPort.class)
    StorageContentVerificationPort unconfiguredStorageContentVerificationAdapter() {
        return new UnconfiguredStorageContentVerificationAdapter();
    }

    @Bean
    @ConditionalOnMissingBean(VerifiedStorageVersionReadPort.class)
    VerifiedStorageVersionReadPort unconfiguredVerifiedStorageVersionReadPort() {
        return new UnconfiguredVerifiedStorageVersionReadPort();
    }

    @Bean
    @ConditionalOnMissingBean(TrustedContentReceiptIssuer.class)
    TrustedContentReceiptIssuer unconfiguredTrustedContentReceiptIssuer() {
        return new UnconfiguredTrustedContentReceiptIssuer();
    }
}
