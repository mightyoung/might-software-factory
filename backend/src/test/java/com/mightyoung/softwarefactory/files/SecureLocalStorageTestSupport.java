package com.mightyoung.softwarefactory.files;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Filesystem capability checks for tests that exercise the real private-storage adapter. */
public final class SecureLocalStorageTestSupport {

    private static final String REQUIRE_SECURE_STORAGE = "software-factory.test.require-secure-storage";
    private static final String UNSUPPORTED_MESSAGE =
            "Real private-storage tests require POSIX attributes and SecureDirectoryStream; "
                    + "this filesystem/JDK does not provide them";

    private SecureLocalStorageTestSupport() { }

    public static boolean supportsSecureStorage(Path storageRoot) throws IOException {
        if (!storageRoot.getFileSystem().supportedFileAttributeViews().contains("posix")) return false;
        // The production adapter pins every component starting at the filesystem root.
        try (var directory = Files.newDirectoryStream(storageRoot.toRealPath().getRoot())) {
            return directory instanceof SecureDirectoryStream<?>;
        }
    }

    public static void requireOrAssumeSecureStorage(Path storageRoot) throws IOException {
        boolean supported = supportsSecureStorage(storageRoot);
        if (Boolean.getBoolean(REQUIRE_SECURE_STORAGE)) {
            assertTrue(supported, UNSUPPORTED_MESSAGE);
        } else {
            assumeTrue(supported, UNSUPPORTED_MESSAGE);
        }
    }
}
