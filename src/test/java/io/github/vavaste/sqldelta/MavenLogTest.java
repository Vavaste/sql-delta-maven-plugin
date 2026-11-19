package io.github.vavaste.sqldelta;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.System.Logger.Level;

import org.junit.jupiter.api.Test;

import io.github.vavaste.sqldelta.RecordingLog.Verbosity;

class MavenLogTest {

    private static final String OWN = "io.github.vavaste.sqldelta.engine.Diff";
    private static final String LIBRARY = "org.hibernate.orm.connections.pooling";
    private static final String URL = "jdbc:postgresql://app:hunter22@db:5432/shop?ssl=true";

    @Test
    void normalOutputShowsOwnInfoAndLibraryWarningsOnly() {
        assertThat(logEveryLevel(Verbosity.NORMAL).lines).containsExactly(
                "ERROR own error",
                "WARN own warning",
                "INFO own info",
                "ERROR library error",
                "WARN library warning");
    }

    @Test
    void debugOutputAddsLibraryInfoAndTheDemotedWarningsWithTheLoggerName() {
        assertThat(logEveryLevel(Verbosity.DEBUG).lines).containsExactly(
                "ERROR own error",
                "WARN own warning",
                "INFO own info",
                "DEBUG own debug",
                "ERROR library error",
                "WARN library warning",
                "DEBUG [" + LIBRARY + "] HHH90000021: Encountered deprecated setting",
                "DEBUG [" + LIBRARY + "] HHH90000025: PostgreSQLDialect does not need to be specified",
                "DEBUG [" + LIBRARY + "] HHH102003: No appropriate connection provider encountered",
                "DEBUG [" + LIBRARY + "] HHH000181: No appropriate connection provider encountered",
                "DEBUG [" + LIBRARY + "] library info",
                "DEBUG [" + LIBRARY + "] library debug");
    }

    @Test
    void quietOutputShowsErrorsOnly() {
        assertThat(logEveryLevel(Verbosity.QUIET).lines).containsExactly("ERROR own error", "ERROR library error");
    }

    @Test
    void replacesTheRawUrlAndThePasswordInEveryMessage() {
        RecordingLog log = new RecordingLog(Verbosity.NORMAL);
        System.Logger logger = new MavenLog(log, URL, "hunter22").apply(OWN);

        logger.log(Level.INFO, "Connecting to {0} as {1}", URL, "app");
        logger.log(Level.ERROR, "password authentication failed for hunter22", new IllegalStateException());

        assertThat(log.lines).containsExactly(
                "INFO Connecting to jdbc:postgresql://db:5432/shop as app",
                "ERROR password authentication failed for **** !IllegalStateException");
    }

    @Test
    void masksThePasswordOnlyAsAWholeWord() {
        MavenLog loggers = new MavenLog(new RecordingLog(Verbosity.NORMAL), URL, "postgres");

        assertThat(loggers.redact("Cannot connect to " + URL + ": authentication failed for user \"postgres\""))
                .isEqualTo("Cannot connect to jdbc:postgresql://db:5432/shop: authentication failed for user \"****\"");
    }

    @Test
    void leavesAPasswordOfThreeCharactersOrLessAlone() {
        MavenLog loggers = new MavenLog(new RecordingLog(Verbosity.NORMAL), "jdbc:h2:mem:shop", "sa");

        assertThat(loggers.redact("user sa on the same database")).isEqualTo("user sa on the same database");
    }

    private static RecordingLog logEveryLevel(Verbosity verbosity) {
        RecordingLog log = new RecordingLog(verbosity);
        MavenLog loggers = new MavenLog(log, URL, null);
        System.Logger own = loggers.apply(OWN);
        own.log(Level.ERROR, "own error");
        own.log(Level.WARNING, "own warning");
        own.log(Level.INFO, "own info");
        own.log(Level.DEBUG, "own debug");
        own.log(Level.TRACE, "own trace");
        System.Logger library = loggers.apply(LIBRARY);
        library.log(Level.ERROR, "library error");
        library.log(Level.WARNING, "library warning");
        library.log(Level.WARNING, "HHH90000021: Encountered deprecated setting");
        library.log(Level.WARNING, "HHH90000025: PostgreSQLDialect does not need to be specified");
        library.log(Level.WARNING, "HHH102003: No appropriate connection provider encountered");
        library.log(Level.WARNING, "HHH000181: No appropriate connection provider encountered");
        library.log(Level.INFO, "library info");
        library.log(Level.DEBUG, "library debug");
        library.log(Level.TRACE, "library trace");
        return log;
    }
}
