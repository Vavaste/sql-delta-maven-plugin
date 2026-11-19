package io.github.vavaste.sqldelta;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.apache.maven.plugin.MojoExecutionException;

/**
 * Reads the versions of the Flyway migrations in a directory, to name the next one V<next>__<name>.sql. Versions
 * compare part by part as numbers
 */
final class FlywayVersions {

    private static final Pattern VERSIONED = Pattern.compile("V(\\d+(?:[._]\\d+)*)__.*\\.(?i)sql");

    static Path newest(Path directory) throws MojoExecutionException {
        if (Files.notExists(directory)) {
            return null;
        }
        try (Stream<Path> files = Files.walk(directory)) {
            return files.filter(file -> version(file) != null)
                    .max((first, second) -> compare(version(first), version(second)))
                    .orElse(null);
        } catch (IOException e) {
            throw new MojoExecutionException("Cannot read the migrations directory " + directory + " (" + e
                    + "): check the migrationsDirectory parameter", e);
        }
    }

    static String version(Path file) {
        Matcher matcher = VERSIONED.matcher(file.getFileName().toString());
        return matcher.matches() ? matcher.group(1).replace('_', '.') : null;
    }

    static String next(String newest, String applied) {
        String larger = newest == null || (applied != null && compare(applied, newest) > 0) ? applied : newest;
        return larger == null ? "1" : new BigInteger(larger.split("[._]")[0]).add(BigInteger.ONE).toString();
    }

    /** Negative, zero or positive as the first version is older than, equal to or newer than the second. */
    static int compare(String first, String second) {
        String[] left = first.split("[._]");
        String[] right = second.split("[._]");
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            BigInteger a = i < left.length ? new BigInteger(left[i]) : BigInteger.ZERO;
            BigInteger b = i < right.length ? new BigInteger(right[i]) : BigInteger.ZERO;
            if (a.compareTo(b) != 0) {
                return a.compareTo(b);
            }
        }
        return 0;
    }
}
