package com.mightyoung.softwarefactory.files;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Platform contracts run even where the real adapter's content tests cannot run. */
class LocalPrivateFileStoragePlatformTest {

    private static final byte[] SYNTHETIC_TRUST_KEY =
            "synthetic-platform-only-trust-key-32".getBytes(StandardCharsets.UTF_8);

    @TempDir Path storageRoot;

    @Test
    void constructorRequiresTheSecureFilesystemCapability() throws Exception {
        preparePrivateRoot();

        if (SecureLocalStorageTestSupport.supportsSecureStorage(storageRoot)) {
            try (var adapter = new LocalPrivateFileStorageAdapter(storageRoot, SYNTHETIC_TRUST_KEY,
                    "platform-verifier", "platform-key", "content-v1", 1024L)) {
                assertThat(adapter).isNotNull();
            }
        } else {
            assertThatThrownBy(() -> new LocalPrivateFileStorageAdapter(storageRoot, SYNTHETIC_TRUST_KEY,
                    "platform-verifier", "platform-key", "content-v1", 1024L))
                    .isInstanceOf(FileEvidenceTrustUnavailableException.class);
        }
        assertReceiptRegistryEmpty();
    }

    @Test
    void explicitConfigurationFailsClosedWhenTheSecureFilesystemCapabilityIsUnavailable() throws Exception {
        preparePrivateRoot();
        boolean supported = SecureLocalStorageTestSupport.supportsSecureStorage(storageRoot);

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(FileEvidenceVerificationAutoConfiguration.class))
                .withPropertyValues(
                        "software-factory.files.local.root=" + storageRoot,
                        "software-factory.files.local.receipt-hmac-key-base64="
                                + Base64.getEncoder().encodeToString(SYNTHETIC_TRUST_KEY),
                        "software-factory.files.local.receipt-key-id=platform-key")
                .run(context -> {
                    if (supported) {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBeansOfType(LocalPrivateFileStorageAdapter.class)).hasSize(1);
                    } else {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasRootCauseInstanceOf(FileEvidenceTrustUnavailableException.class);
                    }
                });
        assertReceiptRegistryEmpty();
    }

    private void preparePrivateRoot() throws Exception {
        if (storageRoot.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Set<PosixFilePermission> privatePermissions = Set.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
            Files.setPosixFilePermissions(storageRoot, privatePermissions);
            Files.createDirectory(storageRoot.resolve(".receipt-registry"),
                    PosixFilePermissions.asFileAttribute(privatePermissions));
        } else {
            Files.createDirectory(storageRoot.resolve(".receipt-registry"));
        }
    }

    private void assertReceiptRegistryEmpty() throws Exception {
        try (var files = Files.list(storageRoot.resolve(".receipt-registry"))) {
            assertThat(files).isEmpty();
        }
    }
}
