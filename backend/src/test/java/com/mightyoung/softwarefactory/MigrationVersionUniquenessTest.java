package com.mightyoung.softwarefactory;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Two lines once shipped different V19-V21 files; a duplicated version must fail the build, not a database. */
class MigrationVersionUniquenessTest {
    @Test
    void everyMigrationVersionIsUsedOnce() throws IOException {
        try (var files = Files.list(Path.of("src/main/resources/db/migration"))) {
            List<String> versions = files.map(file -> file.getFileName().toString())
                    .filter(name -> name.startsWith("V") && name.endsWith(".sql"))
                    .map(name -> name.substring(1, name.indexOf("__")).replace('_', '.')).toList();
            assertThat(versions).isNotEmpty().doesNotHaveDuplicates();
        }
    }
}
