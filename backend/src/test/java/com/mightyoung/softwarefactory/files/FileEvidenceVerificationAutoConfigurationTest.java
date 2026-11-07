package com.mightyoung.softwarefactory.files;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Optional;
import java.util.UUID;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Set;
import java.nio.file.attribute.PosixFilePermission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileEvidenceVerificationAutoConfigurationTest {

    @TempDir Path storageRoot;

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FileEvidenceVerificationAutoConfiguration.class));

    @Test
    void registersFailClosedDefaultWhenNoVerifierIsConfigured() {
        contextRunner.run(context -> {
            assertThat(context.getBeansOfType(StorageContentVerificationPort.class)).hasSize(1);
            assertThat(context.getBean(StorageContentVerificationPort.class))
                    .isInstanceOf(UnconfiguredStorageContentVerificationAdapter.class);
            assertThat(context.getBeansOfType(VerifiedStorageVersionReadPort.class)).hasSize(1);
            assertThat(context.getBean(VerifiedStorageVersionReadPort.class))
                    .isInstanceOf(UnconfiguredVerifiedStorageVersionReadPort.class);
            assertThat(context.getBeansOfType(TrustedContentReceiptIssuer.class)).hasSize(1);
            assertThat(context.getBean(TrustedContentReceiptIssuer.class))
                    .isInstanceOf(UnconfiguredTrustedContentReceiptIssuer.class);
            assertThatThrownBy(() -> context.getBean(TrustedContentReceiptIssuer.class)
                    .verifyAndRegister(null)).isInstanceOf(FileEvidenceTrustUnavailableException.class);
        });
    }

    @Test
    void aSingleTrustedVerifierReplacesTheDefaultWithoutAmbiguousInjection() {
        contextRunner.withUserConfiguration(TrustedVerifierConfiguration.class).run(context -> {
            assertThat(context.getBeansOfType(StorageContentVerificationPort.class)).hasSize(1);
            assertThat(context.getBean(StorageContentVerificationPort.class))
                    .isInstanceOf(TestTrustedVerifier.class);
            assertThat(context.getBeansOfType(VerifiedStorageVersionReadPort.class)).hasSize(1);
            assertThat(context.getBean(VerifiedStorageVersionReadPort.class))
                    .isInstanceOf(UnconfiguredVerifiedStorageVersionReadPort.class);
        });
    }

    @Test
    void localStorageWithoutATrustKeyKeepsTheFailClosedDefaults() {
        contextRunner.withPropertyValues("software-factory.files.local.root=" + storageRoot)
                .run(context -> {
                    assertThat(context.getBean(StorageContentVerificationPort.class))
                            .isInstanceOf(UnconfiguredStorageContentVerificationAdapter.class);
                    assertThat(context.getBean(VerifiedStorageVersionReadPort.class))
                            .isInstanceOf(UnconfiguredVerifiedStorageVersionReadPort.class);
                    assertThat(context.getBean(TrustedContentReceiptIssuer.class))
                            .isInstanceOf(UnconfiguredTrustedContentReceiptIssuer.class);
                    assertThat(Files.exists(storageRoot.resolve(".receipt-registry"))).isFalse();
                });
    }

    @Test
    void configuredLocalStorageProvidesAllThreeTrustedPortsOnASupportedFilesystem() throws Exception {
        SecureLocalStorageTestSupport.requireOrAssumeSecureStorage(storageRoot);
        String encodedKey = Base64.getEncoder().encodeToString("synthetic-test-key-material-32-bytes".getBytes());
        Set<PosixFilePermission> privateDirectoryPermissions = Set.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
        Files.setPosixFilePermissions(storageRoot, privateDirectoryPermissions);
        Files.createDirectory(storageRoot.resolve(".receipt-registry"),
                java.nio.file.attribute.PosixFilePermissions.asFileAttribute(privateDirectoryPermissions));
        contextRunner.withPropertyValues(
                "software-factory.files.local.root=" + storageRoot,
                "software-factory.files.local.receipt-hmac-key-base64=" + encodedKey,
                "software-factory.files.local.receipt-key-id=synthetic-test-key")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    LocalPrivateFileStorageAdapter adapter = context.getBean(LocalPrivateFileStorageAdapter.class);
                    assertThat(context.getBean(StorageContentVerificationPort.class)).isSameAs(adapter);
                    assertThat(context.getBean(VerifiedStorageVersionReadPort.class)).isSameAs(adapter);
                    assertThat(context.getBean(TrustedContentReceiptIssuer.class)).isSameAs(adapter);
                });
    }

    @Test
    void publishedV2ReadsAreAvailableWithoutEnablingUploadReadiness() {
        var sql=org.mockito.Mockito.mock(org.jooq.DSLContext.class);
        var source=org.mockito.Mockito.mock(javax.sql.DataSource.class);
        contextRunner.withBean(org.jooq.DSLContext.class,()->sql)
                .withBean(javax.sql.DataSource.class,()->source)
                .withBean(JooqFileIngestionLedger.class,()->new JooqFileIngestionLedger(sql,source,IngestionProofCodec.unavailable()))
                .withPropertyValues(
                        "software-factory.files.ingestion.storage.quarantine-root="+storageRoot.resolve("quarantine"),
                        "software-factory.files.ingestion.storage.immutable-root="+storageRoot.resolve("immutable"),
                        "software-factory.files.ingestion.storage.receipt-root="+storageRoot.resolve("receipts"),
                        "software-factory.files.ingestion.storage.root-generation="+"a".repeat(64))
                .run(context->{
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(StorageContentVerificationPort.class)).isInstanceOf(PublishedIngestionContentReader.class);
                    assertThat(context.getBean(VerifiedStorageVersionReadPort.class)).isSameAs(context.getBean(StorageContentVerificationPort.class));
                    assertThat(context.getBean(UploadProducerReadiness.class)).isInstanceOf(UnconfiguredUploadProducerReadiness.class);
                    assertThat(context.getBean(TrustedContentReceiptIssuer.class)).isInstanceOf(UnconfiguredTrustedContentReceiptIssuer.class);
                    assertThatThrownBy(()->context.getBean(StorageContentVerificationPort.class).findByReceiptId(java.util.UUID.randomUUID(),java.util.UUID.randomUUID()))
                            .isInstanceOf(FileEvidenceTrustUnavailableException.class);
                });
    }

    @Test
    void mixedV1AndV2ConfigurationKeepsLegacyIssuerAndChoosesCompositeReads() throws Exception {
        SecureLocalStorageTestSupport.requireOrAssumeSecureStorage(storageRoot);
        Files.setPosixFilePermissions(storageRoot,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        Files.createDirectory(storageRoot.resolve(".receipt-registry"),java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")));
        var sql=org.mockito.Mockito.mock(org.jooq.DSLContext.class);
        var source=org.mockito.Mockito.mock(javax.sql.DataSource.class);
        contextRunner.withBean(org.jooq.DSLContext.class,()->sql)
                .withBean(javax.sql.DataSource.class,()->source)
                .withBean(JooqFileIngestionLedger.class,()->new JooqFileIngestionLedger(sql,source,IngestionProofCodec.unavailable()))
                .withPropertyValues(
                        "software-factory.files.local.root="+storageRoot,
                        "software-factory.files.local.receipt-hmac-key-base64="+Base64.getEncoder().encodeToString(new byte[32]),
                        "software-factory.files.local.receipt-key-id=legacy-key",
                        "software-factory.files.ingestion.storage.quarantine-root="+storageRoot.resolve("quarantine"),
                        "software-factory.files.ingestion.storage.immutable-root="+storageRoot.resolve("immutable"),
                        "software-factory.files.ingestion.storage.receipt-root="+storageRoot.resolve("receipts"),
                        "software-factory.files.ingestion.storage.root-generation="+"a".repeat(64))
                .run(context->{
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(StorageContentVerificationPort.class)).hasSize(2);
                    assertThat(context.getBean(StorageContentVerificationPort.class)).isInstanceOf(PublishedIngestionContentReader.class);
                    assertThat(context.getBean(VerifiedStorageVersionReadPort.class)).isInstanceOf(PublishedIngestionContentReader.class);
                    assertThat(context.getBean(TrustedContentReceiptIssuer.class)).isSameAs(context.getBean(LocalPrivateFileStorageAdapter.class));
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
