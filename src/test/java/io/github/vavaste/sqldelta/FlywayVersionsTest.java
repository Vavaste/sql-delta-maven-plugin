package io.github.vavaste.sqldelta;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.maven.plugin.MojoExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class FlywayVersionsTest {

    @TempDir
    Path directory;

    @ParameterizedTest
    @CsvSource({
            "V1__init.sql, 1",
            "V1_2__add_notes.sql, 1.2",
            "V2.10.3__x.sql, 2.10.3",
            "V5__upper_case_suffix.SQL, 5",
            "V20261118101530__stamp.sql, 20261118101530"
    })
    void readsTheVersionOfAVersionedMigration(String fileName, String version) {
        assertThat(FlywayVersions.version(Path.of(fileName))).isEqualTo(version);
    }

    @ParameterizedTest
    @ValueSource(strings = {"R__views.sql", "U1__undo.sql", "V1_init.sql", "v1__x.sql", "V1__x.txt", "Va__x.sql"})
    void ignoresEveryOtherFile(String fileName) {
        assertThat(FlywayVersions.version(Path.of(fileName))).isNull();
    }

    @ParameterizedTest
    @CsvSource({"1.10, 1.9, 1", "10, 9.99, 1", "1, 1.0, 0", "1.0.1, 1, 1", "2, 10, -1", "007, 7, 0"})
    void comparesVersionsPartByPartAsNumbers(String first, String second, int sign) {
        assertThat(Integer.signum(FlywayVersions.compare(first, second))).isEqualTo(sign);
    }

    @Test
    void findsTheNewestMigrationInSubdirectoriesWhateverTheSuffixCase() throws IOException, MojoExecutionException {
        Files.createDirectories(directory.resolve("older"));
        for (String file : new String[] {"V1_1__a.sql", "older/V2.0.1__b.sql", "R__views.sql", "V5__c.SQL"}) {
            Files.writeString(directory.resolve(file), "");
        }

        Path newest = FlywayVersions.newest(directory);

        assertThat(newest.getFileName()).hasToString("V5__c.SQL");
    }

    @Test
    void anEmptyOrMissingDirectoryHasNoMigration() throws MojoExecutionException {
        assertThat(FlywayVersions.newest(directory)).isNull();
        assertThat(FlywayVersions.newest(directory.resolve("missing"))).isNull();
    }

    // an empty value is null: no migration file, or no Flyway history
    @ParameterizedTest
    @CsvSource({"3.2, , 4", "9, , 10", "2, 3, 4", ", 1, 2", "3, 2, 4", ", , 1"})
    void theNextVersionIsTheFirstPartOfTheLargerVersionPlusOne(String newest, String applied, String next) {
        assertThat(FlywayVersions.next(newest, applied)).isEqualTo(next);
    }
}
