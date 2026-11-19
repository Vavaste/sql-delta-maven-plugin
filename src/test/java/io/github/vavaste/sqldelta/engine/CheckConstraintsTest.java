package io.github.vavaste.sqldelta.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import org.hibernate.SessionFactory;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.vavaste.sqldelta.engine.CheckConstraints.Result;
import io.github.vavaste.sqldelta.engine.Statement.Phase;
import io.github.vavaste.sqldelta.engine.testmodel.checks.v1.Ticket;
import io.github.vavaste.sqldelta.engine.testmodel.checks.v2.Parcel;

/**
 * The comparison on PostgreSQL (image from the sqldelta.test.postgres.image property) and on H2, from a schema
 * Hibernate created for the v1 model to the v2 model, which changes one column per scenario.
 */
class CheckConstraintsTest {

    private static final List<Class<?>> V1 = List.of(Ticket.class);
    private static final List<Class<?>> V2 =
            List.of(io.github.vavaste.sqldelta.engine.testmodel.checks.v2.Ticket.class, Parcel.class);
    private static final Map<String, Result> V1_TO_V2 = new ConcurrentHashMap<>();

    @ParameterizedTest
    @ValueSource(strings = {"postgresql", "h2"})
    void aSchemaHibernateCreatedIsInSync(String database) throws SQLException {
        for (List<Class<?>> model : List.of(V1, V2)) {
            try (Connection connection = newDatabase(database, model)) {
                Result result = compare(database, connection, model, table -> false);
                assertThat(result.statements()).isEmpty();
                assertThat(result.notes()).isEmpty();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgresql", "h2"})
    void aConstantAddedToAStringEnumReplacesTheCheckUnderItsNameSafely(String database) {
        assertThat(replacement(database, "priority")).containsExactly(
                "SAFE ALTER TABLE ticket DROP CONSTRAINT <name>",
                "SAFE ALTER TABLE ticket ADD CONSTRAINT <name> "
                        + "CHECK (priority in ('LOW','NORMAL','URGENT','CRITICAL'))");
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgresql", "h2"})
    void aConstantRemovedFromAStringEnumNeedsAReview(String database) {
        assertThat(replacement(database, "mood")).containsExactly(
                "SAFE ALTER TABLE ticket DROP CONSTRAINT <name>",
                "REVIEW ALTER TABLE ticket ADD CONSTRAINT <name> CHECK (mood in ('HAPPY','SAD'))");
        assertThat(add(database, "mood").reason()).isEqualTo("fails if rows hold values the entities no longer "
                + "accept; find them with SELECT count(*) FROM ticket WHERE NOT (mood in ('HAPPY','SAD'))");
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgresql", "h2"})
    void aConstantAddedToAnOrdinalEnumNeedsAReviewBecauseStoredNumbersMayChangeMeaning(String database) {
        assertThat(replacement(database, "status")).containsExactly(
                "SAFE ALTER TABLE ticket DROP CONSTRAINT <name>",
                "REVIEW ALTER TABLE ticket ADD CONSTRAINT <name> CHECK (status between 0 and 2)");
        assertThat(add(database, "status").reason()).startsWith("ORDINAL values are positions").doesNotEndWith(".");
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgresql", "h2"})
    void aCheckMissingOnAnExistingColumnNeedsAReview(String database) {
        Statement add = add(database, "legacy_code");
        assertThat(add.classification() + " " + add.sql()).isEqualTo("REVIEW ALTER TABLE ticket ADD CONSTRAINT "
                + "ticket_legacy_code_check CHECK (legacy_code in ('A1','B2'))");
        assertThat(add.reason()).startsWith("fails if rows hold values the entities do not accept;");
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgresql", "h2"})
    void aNewColumnGetsItsCheckSafelyUnderThePostgresqlName(String database) {
        assertThat(sql(database)).contains(
                "SAFE ALTER TABLE ticket ADD CONSTRAINT ticket_channel_check CHECK (channel in ('EMAIL','PHONE'))");
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgresql", "h2"})
    void aNewTableGetsItsGeneratedCheckSafely(String database) {
        assertThat(sql(database)).contains(
                "SAFE ALTER TABLE parcel ADD CONSTRAINT parcel_carrier_check CHECK (carrier in ('POST','COURIER'))");
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgresql", "h2"})
    void aNewTableGetsItsCustomChecksAsWritten(String database) {
        assertThat(sql(database)).contains(
                "SAFE ALTER TABLE parcel ADD CONSTRAINT parcel_weight_check CHECK (weight >= 0)",
                "SAFE ALTER TABLE parcel ADD CONSTRAINT parcel_light_or_courier "
                        + "CHECK (weight < 100 or carrier = 'COURIER')");
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgresql", "h2"})
    void columnsWhoseChecksDidNotChangeAreLeftAlone(String database) {
        // grade is NOT NULL, status_code starts with the name of the changed status column
        for (String column : List.of("grade", "status_code", "active", "lonely")) {
            assertThat(sql(database)).noneMatch(sql -> sql.contains("(" + column + " "));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgresql", "h2"})
    void aSwitchBetweenStringAndOrdinalIsAWarningWithoutStatements(String database) {
        assertThat(sql(database)).noneMatch(sql -> sql.contains("(kind "));
        assertThat(v1ToV2(database).notes()).anySatisfy(note -> {
            assertThat(note.warning()).isTrue();
            assertThat(note.message()).startsWith("ticket.kind switches between a value list and a range");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgresql", "h2"})
    void aCheckNoEntityWantsAnyMoreIsReportedWithItsDropStatement(String database) {
        assertThat(sql(database)).noneMatch(sql -> sql.contains("motto"));
        assertThat(v1ToV2(database).notes()).anySatisfy(note -> {
            assertThat(note.warning()).isFalse();
            assertThat(note.message()).matches("No entity wants check (\\w+) on ticket.motto any more; if it is "
                    + "obsolete, drop it with ALTER TABLE ticket DROP CONSTRAINT \\1");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgresql", "h2"})
    void theStatementsConvergeOnceApplied(String database) throws SQLException {
        try (Connection connection = newDatabase(database, V1)) {
            Result first = compare(database, connection, V2, table -> false);
            List<String> script = new ArrayList<>(sql(first, Phase.DROP_CHECKS));
            // what the Liquibase part of the draft does in between
            script.add("CREATE TABLE parcel (id bigint PRIMARY KEY, carrier varchar(255), weight int)");
            script.add("ALTER TABLE ticket ADD COLUMN channel varchar(255)");
            script.addAll(sql(first, Phase.ADD_CHECKS));
            try (java.sql.Statement jdbc = connection.createStatement()) {
                for (String sql : script) {
                    jdbc.execute(sql);
                }
            }
            Result again = compare(database, connection, V2, table -> false);
            assertThat(again.statements()).isEmpty();
            assertThat(again.notes()).isEqualTo(first.notes());
        }
    }

    @Test
    void tableNamesAreUnqualifiedLikeInTheRestOfTheDraft() throws SQLException {
        try (Connection connection = newDatabase("h2", V1)) {
            connection.createStatement().execute("create schema other");
            // the compared schema is not the connection's
            connection.setSchema("OTHER");
            Result result = compare("h2", connection, V2, table -> false);
            assertThat(result.statements()).isNotEmpty().noneMatch(s -> (s.sql() + s.reason()).contains("PUBLIC."));
            assertThat(result.notes()).isNotEmpty().noneMatch(note -> note.message().contains("PUBLIC."));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgresql", "h2"})
    void excludedTablesAreNotCompared(String database) throws SQLException {
        try (Connection connection = newDatabase(database, V1)) {
            Result result = compare(database, connection, V2, table -> true);
            assertThat(result.statements()).isEmpty();
            assertThat(result.notes()).isEmpty();
        }
    }

    @Test
    void otherDatabasesGetOneNoteAndNoQuery() throws SQLException {
        DatabaseMetaData metaData = answering(DatabaseMetaData.class, "getDatabaseProductName", "Oracle");
        Connection connection = answering(Connection.class, "getMetaData", metaData);
        Metadata metadata = metadata("postgresql", V2, null);
        Result result = CheckConstraints.compare(metadata, connection, "public", table -> false);
        assertThat(result.statements()).isEmpty();
        assertThat(result.notes()).containsExactly(new Note(false, null,
                "Check constraints are compared only on PostgreSQL and H2, so they were not compared on Oracle."));
    }

    /** The drop and the add of a replaced check, with its name, the same in both, written as {@code <name>}. */
    private static List<String> replacement(String database, String column) {
        // the name in ALTER TABLE t ADD CONSTRAINT <name> CHECK (...)
        String name = " " + add(database, column).sql().split(" ")[5] + " ";
        return v1ToV2(database).statements().stream()
                .filter(s -> (s.sql() + " ").contains(name))
                .map(s -> s.classification() + " " + (s.sql() + " ").replace(name, " <name> ").strip())
                .toList();
    }

    private static Statement add(String database, String column) {
        return v1ToV2(database).statements().stream()
                .filter(s -> s.phase() == Phase.ADD_CHECKS && s.sql().contains("CHECK (" + column + " "))
                .findFirst().orElseThrow();
    }

    private static List<String> sql(String database) {
        return v1ToV2(database).statements().stream().map(s -> s.classification() + " " + s.sql()).toList();
    }

    private static List<String> sql(Result result, Phase phase) {
        return result.statements().stream().filter(s -> s.phase() == phase).map(Statement::sql).toList();
    }

    private static Result v1ToV2(String database) {
        return V1_TO_V2.computeIfAbsent(database, key -> {
            try (Connection connection = newDatabase(database, V1)) {
                return compare(database, connection, V2, table -> false);
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private static Result compare(String database, Connection connection, List<Class<?>> model,
                                  Predicate<String> excluded) throws SQLException {
        String schema = database.equals("h2") ? "PUBLIC" : "public";
        return CheckConstraints.compare(metadata(database, model, null), connection, schema, excluded);
    }

    /** A new database with the tables Hibernate creates for the model. */
    private static Connection newDatabase(String database, List<Class<?>> model) throws SQLException {
        Databases target = database.equals("postgresql") ? Databases.newPostgres()
                : new Databases("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        try (SessionFactory ignored = metadata(database, model, target).buildSessionFactory()) {
            // building the session factory creates the tables (hbm2ddl.auto=create)
        }
        return DriverManager.getConnection(target.url(), target.user(), target.password());
    }

    /** The metadata of a model; with a database it also creates the tables when a session factory is built. */
    private static Metadata metadata(String database, List<Class<?>> model, Databases target) {
        StandardServiceRegistryBuilder registry = new StandardServiceRegistryBuilder().applySetting("hibernate.dialect",
                database.equals("h2") ? "org.hibernate.dialect.H2Dialect" : "org.hibernate.dialect.PostgreSQLDialect");
        if (target == null) {
            registry.applySetting("hibernate.boot.allow_jdbc_metadata_access", "false");
        } else {
            registry.applySetting("hibernate.connection.url", target.url())
                    .applySetting("hibernate.connection.username", target.user())
                    .applySetting("hibernate.connection.password", target.password())
                    .applySetting("hibernate.hbm2ddl.auto", "create");
        }
        MetadataSources sources = new MetadataSources(registry.build());
        model.forEach(sources::addAnnotatedClass);
        return sources.buildMetadata();
    }

    /** A JDBC object that answers one method and fails on any other, to prove nothing else is called. */
    private static <T> T answering(Class<T> type, String method, Object answer) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, called, args) -> {
            if (!called.getName().equals(method)) {
                throw new UnsupportedOperationException(called.getName());
            }
            return answer;
        }));
    }
}
