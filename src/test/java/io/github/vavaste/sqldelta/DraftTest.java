package io.github.vavaste.sqldelta;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.vavaste.sqldelta.RecordingLog.Verbosity;

class DraftTest {

    private static final Instant GENERATED = Instant.parse("2026-11-18T10:15:30.123Z");
    private static final String URL = "jdbc:postgresql://localhost:5432/shop";

    private static final Map<String, Object> CREATE = statement("CREATE_TABLES", "SAFE",
            "CREATE TABLE audit_event (id BIGINT NOT NULL, PRIMARY KEY (id))", null);
    private static final Map<String, Object> NOT_NULL = statement("ALTER_COLUMNS", "REVIEW",
            "ALTER TABLE users ALTER COLUMN surname SET NOT NULL", "fails if existing rows have no surname");
    private static final Map<String, Object> DROP = statement("DROP_COLUMNS", "DATA_LOSS",
            "ALTER TABLE users\n    DROP COLUMN legacy_code", "drops the column and its values");

    @Test
    void rendersTheHeaderTheNotesAndOneBlockPerStatementWithDropsCommentedOut() {
        String draft = draft(false, List.of(CREATE, NOT_NULL, DROP)).render("0.1.0", GENERATED, URL);

        assertThat(draft).isEqualTo("""
                -- sql-delta 0.1.0 draft migration, generated 2026-11-18T10:15:30Z
                -- database: PostgreSQL 18.3, schema public, jdbc:postgresql://localhost:5432/shop
                -- entities: 12 in com.example.shop (Hibernate 7.2.25.Final)
                -- naming: CamelCaseToUnderscoresNamingStrategy, SpringImplicitNamingStrategy
                -- engine: liquibase-core 4.33.0
                -- Review every statement before using it. [SAFE] cannot fail on existing rows and loses nothing; [REVIEW] may fail or
                -- lock on existing rows; [DATA LOSS] destroys data and is commented out unless includeDrops=true.
                -- Not in this draft: renames (they appear as add plus drop), data backfills, defaults for new NOT NULL columns.
                -- Notes:
                -- WARNING users: index ix_users_legacy exists only in the database
                -- INFO check constraints compared on 3 tables
                -- second line

                -- [SAFE]
                CREATE TABLE audit_event (id BIGINT NOT NULL, PRIMARY KEY (id));

                -- [REVIEW] fails if existing rows have no surname
                ALTER TABLE users ALTER COLUMN surname SET NOT NULL;

                -- [DATA LOSS] drops the column and its values
                -- ALTER TABLE users
                --     DROP COLUMN legacy_code;
                """);
    }

    @Test
    void includeDropsWritesDataLossStatementsAsSql() {
        String draft = draft(true, List.of(DROP)).render("0.1.0", GENERATED, URL);

        assertThat(draft).endsWith("""
                -- [DATA LOSS] drops the column and its values
                ALTER TABLE users
                    DROP COLUMN legacy_code;
                """);
    }

    @Test
    void printsTheCountsOneLinePerStatementAndTheNotes() {
        String longSql = "CREATE INDEX ix_long ON audit_event (" + "a, ".repeat(60) + "b)";
        RecordingLog log = new RecordingLog(Verbosity.NORMAL);

        draft(false, List.of(CREATE, NOT_NULL, DROP, statement("ADD_CONSTRAINTS_AND_INDEXES", "SAFE", longSql, null)))
                .print(log);

        assertThat(log.lines).containsExactly(
                "INFO 4 statements: 2 SAFE, 1 REVIEW, 1 DATA LOSS (commented out)",
                "INFO   [SAFE] CREATE TABLE audit_event (id BIGINT NOT NULL, PRIMARY KEY (id))",
                "INFO   [REVIEW] ALTER TABLE users ALTER COLUMN surname SET NOT NULL",
                "INFO   [DATA LOSS] -- ALTER TABLE users DROP COLUMN legacy_code",
                "INFO   [SAFE] " + longSql.substring(0, 157) + "...",
                "WARN users: index ix_users_legacy exists only in the database",
                "INFO check constraints compared on 3 tables\nsecond line");
    }

    @Test
    @SuppressWarnings("unchecked")
    void printsNoDifferencesWithTheEntityCountInTheRightNumber() {
        Map<String, Object> response = response(List.of(), List.of());
        RecordingLog log = new RecordingLog(Verbosity.NORMAL);

        ((Map<String, String>) response.get(EngineProtocol.INFO)).put(EngineProtocol.ENTITIES, "1");
        new Draft(response, false).print(log);

        assertThat(log.lines)
                .containsExactly("INFO No differences: 1 entity in com.example.shop, PostgreSQL 18.3, schema public");
    }

    @Test
    void aDraftWithOnlyCommentedOutDropsHasNothingToRun() {
        assertThat(draft(false, List.of(DROP)).hasExecutableStatements()).isFalse();
        assertThat(draft(true, List.of(DROP)).hasExecutableStatements()).isTrue();
        assertThat(draft(false, List.of(CREATE, DROP)).hasExecutableStatements()).isTrue();
    }

    private static Draft draft(boolean includeDrops, List<Map<String, Object>> statements) {
        return new Draft(response(statements, List.of(
                Map.of(EngineProtocol.WARNING, true, EngineProtocol.TABLE, "users",
                        EngineProtocol.MESSAGE, "index ix_users_legacy exists only in the database"),
                Map.of(EngineProtocol.WARNING, false,
                        EngineProtocol.MESSAGE, "check constraints compared on 3 tables\nsecond line"))),
                includeDrops);
    }

    private static Map<String, Object> response(List<Map<String, Object>> statements, List<Map<String, Object>> notes) {
        Map<String, String> info = new HashMap<>(Map.of(
                EngineProtocol.DATABASE, "PostgreSQL 18.3",
                EngineProtocol.COMPARED_SCHEMA, "public",
                EngineProtocol.ENTITIES, "12",
                EngineProtocol.ENTITY_PACKAGES, "com.example.shop",
                EngineProtocol.HIBERNATE_VERSION, "7.2.25.Final",
                EngineProtocol.LIQUIBASE_VERSION, "4.33.0",
                EngineProtocol.NAMING, "CamelCaseToUnderscoresNamingStrategy, SpringImplicitNamingStrategy"));
        return new HashMap<>(Map.of(EngineProtocol.STATEMENTS, statements, EngineProtocol.NOTES, notes,
                EngineProtocol.INFO, info));
    }

    private static Map<String, Object> statement(String phase, String classification, String sql, String reason) {
        Map<String, Object> statement = new HashMap<>(Map.of(EngineProtocol.PHASE, phase,
                EngineProtocol.CLASSIFICATION, classification, EngineProtocol.SQL, sql));
        if (reason != null) {
            statement.put(EngineProtocol.REASON, reason);
        }
        return statement;
    }
}
