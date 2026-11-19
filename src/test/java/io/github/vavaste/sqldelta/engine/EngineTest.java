package io.github.vavaste.sqldelta.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.UUID;

import org.hibernate.Version;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import io.github.vavaste.sqldelta.EngineProtocol;

/** Engine.run end to end on H2: the round trip from an empty database, the request handling and the errors. */
class EngineTest {

    private static final String STORE = Databases.MODELS + "store.v2";
    private static final String CONNECTION_HINT = "Check the url and username parameters, and the password in the"
            + " SQLDELTA_PASSWORD environment variable or the password parameter.";

    @Test
    void aDraftFromAnEmptyDatabaseCreatesTheSchemaHibernateValidates() throws SQLException {
        Databases database = newDatabase();

        Map<String, Object> draft = database.diff("store.v2");
        database.execute(Databases.sql(draft));

        // the columnDefinition of status is "varchar(20) default 'new' not null"
        assertThat(Databases.sql(draft)).anyMatch(sql -> sql.startsWith("CREATE TABLE customer")
                && sql.contains(" status VARCHAR(20) DEFAULT 'new' NOT NULL,"));
        assertThat(Databases.messages(draft)).isEmpty();
        assertThat(Databases.sql(database.diff("store.v2"))).isEmpty();
        database.hibernate("validate", "store.v2");
    }

    @Test
    void theDraftKeepsTheDeleteRuleOfACompositeForeignKeyAndANotNullBeforeADefault() throws SQLException {
        Databases database = newDatabase();

        List<String> draft = Databases.sql(database.diff("edges"));
        database.execute(draft);

        // Hibernate orders the columns of the composite key, and of the foreign keys to it, by type
        assertThat(draft).anyMatch(sql -> sql.endsWith(
                "FOREIGN KEY (parent_number, parent_code) REFERENCES parent (number, code) ON DELETE CASCADE"));
        // the columnDefinition of state is "varchar(20) not null default 'new'"
        assertThat(draft).anyMatch(sql -> sql.contains(" state VARCHAR(20) DEFAULT 'new' NOT NULL,"));
        assertThat(Databases.sql(database.diff("edges"))).isEmpty();
    }

    @Test
    void aRunPrintsNothingAndLogsThroughTheGivenLoggers() {
        Databases database = newDatabase();
        // slf4j prints its one-time 'no providers' warning now, before stderr is captured
        LoggerFactory.getILoggerFactory();
        List<String> logged = new ArrayList<>();
        ByteArrayOutputStream console = new ByteArrayOutputStream();
        PrintStream out = System.out;
        PrintStream err = System.err;
        System.setOut(new PrintStream(console, true));
        System.setErr(new PrintStream(console, true));
        try {
            Databases.statements(Engine.run(database.request("store.v2"),
                    name -> new Recording(name, System.Logger.Level.TRACE, logged)));
        } finally {
            System.setOut(out);
            System.setErr(err);
        }

        assertThat(console.toString()).isEmpty();
        assertThat(logged).anyMatch(line -> line.startsWith("org.hibernate"));
    }

    @Test
    void theEntitiesAreFoundInTheClassesDirectoryWhenNoPackageIsGiven(@TempDir Path classes) throws Exception {
        Path models = Path.of(EngineTest.class.getResource("testmodel").toURI());
        Path copy = classes.resolve(STORE.replace('.', '/'));
        Files.createDirectories(copy);
        try (var files = Files.list(models.resolve("store/v2"))) {
            for (Path file : files.toList()) {
                Files.copy(file, copy.resolve(file.getFileName()));
            }
        }
        Map<String, Object> request = newDatabase().request("store.v2");
        request.remove(EngineProtocol.PACKAGES);
        request.put(EngineProtocol.CLASSES_DIRECTORY, classes.toString());

        Map<String, Object> response = Engine.run(request, null);

        assertThat(Databases.info(response)).containsEntry(EngineProtocol.ENTITY_PACKAGES, STORE)
                .containsEntry(EngineProtocol.ENTITIES, "3");
    }

    @Test
    void aClassesDirectoryWithoutEntitiesIsAConfigurationError(@TempDir Path classes) {
        Map<String, Object> request = newDatabase().request("store.v2");
        request.remove(EngineProtocol.PACKAGES);
        request.put(EngineProtocol.CLASSES_DIRECTORY, classes.toString());

        Map<String, Object> error = Databases.error(Engine.run(request, null));

        assertThat((String) error.get(EngineProtocol.MESSAGE)).contains("No JPA entity was found in " + classes)
                .contains("set the packages parameter");
    }

    @Test
    void aPackageWithoutEntitiesIsAConfigurationError() {
        Map<String, Object> error = Databases.error(newDatabase().diff("nothing.here"));

        assertThat((String) error.get(EngineProtocol.MESSAGE)).startsWith("No JPA entity was found in ")
                .endsWith("nothing.here; check the packages parameter.");
    }

    @Test
    void aSchemaThatDoesNotExistIsAConfigurationError() {
        Map<String, Object> request = newDatabase().request("store.v2");
        request.put(EngineProtocol.SCHEMA, "missing");

        Map<String, Object> error = Databases.error(Engine.run(request, null));

        assertThat(error).containsEntry(EngineProtocol.MESSAGE,
                "Schema missing does not exist in the database; check the schema parameter.");
    }

    @Test
    void aConverterOrListenerWithoutANoArgumentConstructorIsAConfigurationError() {
        // what Hibernate throws for a converter whose only constructor takes a Spring bean
        Exception failure = new IllegalStateException("Unable to build Hibernate SessionFactory",
                new org.hibernate.InstantiationException("Could not instantiate managed bean directly",
                        EngineTest.class, new NoSuchMethodException()));

        Map<String, Object> error = Engine.error(failure, "jdbc:h2:mem:app");

        assertThat((String) error.get(EngineProtocol.MESSAGE)).contains(EngineTest.class.getName())
                .endsWith("; sql-delta builds the entities without Spring, so converters and entity listeners need a"
                        + " constructor without arguments.");
    }

    @Test
    void aWrongPasswordIsAConnectionError() {
        Databases database = newDatabase();
        // creates the database with the right password first
        database.diff("store.v2");
        Map<String, Object> request = database.request("store.v2");
        request.put(EngineProtocol.PASSWORD, "not-the-password");

        Map<String, Object> error = Databases.error(Engine.run(request, null));

        assertThat((String) error.get(EngineProtocol.MESSAGE))
                .startsWith("Cannot connect to " + database.url() + ": Wrong user name or password")
                .endsWith(". " + CONNECTION_HINT);
    }

    @Test
    void aConnectionCauseThatEndsWithAPeriodGetsNoSecondOne() {
        SQLException failure = new SQLException("The connection attempt failed.", "08001");

        Map<String, Object> error = Engine.error(failure, "jdbc:postgresql://db/app");

        assertThat(error.get(EngineProtocol.MESSAGE)).isEqualTo("Cannot connect to jdbc:postgresql://db/app:"
                + " The connection attempt failed. " + CONNECTION_HINT);
    }

    @Test
    void anUnknownHostIsAConnectionError() {
        Databases database =
                new Databases("jdbc:postgresql://unknown-host.invalid:5432/app?connectTimeout=5", "app", "pw");

        Map<String, Object> error = Databases.error(database.diff("store.v2"));

        assertThat((String) error.get(EngineProtocol.MESSAGE)).startsWith("Cannot connect to ")
                .contains("unknown host unknown-host.invalid").endsWith(CONNECTION_HINT);
    }

    @Test
    void aDialectThatIsNotOnTheClassPathIsAConfigurationError() {
        Map<String, Object> request = newDatabase().request("store.v2");
        request.put(EngineProtocol.DIALECT, "com.example.MissingDialect");

        Map<String, Object> error = Databases.error(Engine.run(request, null));

        assertThat((String) error.get(EngineProtocol.MESSAGE))
                .startsWith("A dialect or naming strategy class cannot be used").contains("com.example.MissingDialect");
    }

    @Test
    void theNamingStrategiesAreTheOnesOfTheSpringBootOnTheClassPath() {
        Map<String, String> info = Databases.info(newDatabase().diff("store.v2"));

        assertThat(info.get(EngineProtocol.NAMING)).isEqualTo(Version.getVersionString().startsWith("7.")
                ? "PhysicalNamingStrategySnakeCaseImpl, SpringImplicitNamingStrategy (spring-boot-4)"
                : "CamelCaseToUnderscoresNamingStrategy, SpringImplicitNamingStrategy (spring-boot-3)");
    }

    @Test
    void namingStrategyParametersWinOverSpringBoot() {
        Map<String, Object> request = newDatabase().request("store.v2");
        request.put(EngineProtocol.PHYSICAL_NAMING_STRATEGY,
                "org.hibernate.boot.model.naming.PhysicalNamingStrategyStandardImpl");

        Map<String, String> info = Databases.info(Engine.run(request, null));

        assertThat(info.get(EngineProtocol.NAMING)).startsWith("PhysicalNamingStrategyStandardImpl, ")
                .endsWith(" (parameter)");
    }

    private static Databases newDatabase() {
        return new Databases("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "secret-password");
    }

    /** A System.Logger that keeps "name LEVEL message" lines at or above its threshold. */
    private record Recording(String name, Level threshold, List<String> lines) implements System.Logger {

        @Override
        public String getName() {
            return name;
        }

        @Override
        public boolean isLoggable(Level level) {
            return level.getSeverity() >= threshold.getSeverity();
        }

        @Override
        public void log(Level level, ResourceBundle bundle, String message, Throwable thrown) {
            lines.add(name + " " + level + " " + message);
        }

        @Override
        public void log(Level level, ResourceBundle bundle, String format, Object... parameters) {
            lines.add(name + " " + level + " " + format);
        }
    }
}
