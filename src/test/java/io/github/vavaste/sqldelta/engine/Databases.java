package io.github.vavaste.sqldelta.engine;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypesScanner;
import org.testcontainers.postgresql.PostgreSQLContainer;

import io.github.vavaste.sqldelta.EngineProtocol;

/** A test database: Hibernate's own schema tool on it, plain SQL, and Engine requests and responses. */
record Databases(String url, String user, String password) {

    static final String MODELS = "io.github.vavaste.sqldelta.engine.testmodel.";

    // one container for every test class, never stopped here: Testcontainers removes it when the JVM exits
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(System.getProperty("sqldelta.test.postgres.image", "postgres:18.3-trixie"));
    private static final AtomicInteger POSTGRES_DATABASES = new AtomicInteger();

    /** A new database on the PostgreSQL of the tests, which starts on first use. */
    static synchronized Databases newPostgres() throws SQLException {
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();
        }
        String name = "db_" + POSTGRES_DATABASES.incrementAndGet();
        new Databases(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .execute(List.of("create database " + name));
        String url = POSTGRES.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + name + "$1");
        return new Databases(url, POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /** Runs Hibernate's schema tool (create or validate) with the naming strategies Spring Boot would use. */
    void hibernate(String action, String entityPackage) {
        boolean boot4 = loadable("org.springframework.boot.hibernate.SpringImplicitNamingStrategy");
        Map<String, Object> settings = new HashMap<>();
        settings.put("jakarta.persistence.jdbc.url", url);
        settings.put("jakarta.persistence.jdbc.user", user);
        settings.put("jakarta.persistence.jdbc.password", password);
        settings.put("hibernate.hbm2ddl.auto", action);
        settings.put("hibernate.hbm2ddl.create_namespaces", "true");
        settings.put("hibernate.physical_naming_strategy", boot4
                ? "org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl"
                : "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy");
        settings.put("hibernate.implicit_naming_strategy", boot4
                ? "org.springframework.boot.hibernate.SpringImplicitNamingStrategy"
                : "org.springframework.boot.orm.jpa.hibernate.SpringImplicitNamingStrategy");
        StandardServiceRegistry registry = new StandardServiceRegistryBuilder().applySettings(settings).build();
        try {
            MetadataSources sources = new MetadataSources(registry);
            new PersistenceManagedTypesScanner(new DefaultResourceLoader()).scan(MODELS + entityPackage)
                    .getManagedClassNames().forEach(sources::addAnnotatedClassName);
            try (SessionFactory ignored = sources.buildMetadata().buildSessionFactory()) {
                // building the session factory runs the schema tool
            }
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    void execute(List<String> statements) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, user, password);
             java.sql.Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    Map<String, Object> diff(String entityPackage) {
        return Engine.run(request(entityPackage), null);
    }

    Map<String, Object> request(String entityPackage) {
        Map<String, Object> request = new HashMap<>();
        request.put(EngineProtocol.URL, url);
        request.put(EngineProtocol.USERNAME, user);
        request.put(EngineProtocol.PASSWORD, password);
        request.put(EngineProtocol.PACKAGES, List.of(MODELS + entityPackage));
        return request;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> statements(Map<String, Object> response) {
        if (response.containsKey(EngineProtocol.ERROR)) {
            throw new AssertionError("The engine failed: " + error(response).get(EngineProtocol.MESSAGE)
                    + "\n" + error(response).get(EngineProtocol.DETAIL));
        }
        return (List<Map<String, Object>>) response.get(EngineProtocol.STATEMENTS);
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> notes(Map<String, Object> response) {
        // fails on an engine error
        statements(response);
        return (List<Map<String, Object>>) response.get(EngineProtocol.NOTES);
    }

    static List<String> sql(Map<String, Object> response) {
        return statements(response).stream().map(statement -> (String) statement.get(EngineProtocol.SQL)).toList();
    }

    static List<String> messages(Map<String, Object> response) {
        return notes(response).stream().map(note -> (String) note.get(EngineProtocol.MESSAGE)).toList();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> error(Map<String, Object> response) {
        return (Map<String, Object>) response.get(EngineProtocol.ERROR);
    }

    @SuppressWarnings("unchecked")
    static Map<String, String> info(Map<String, Object> response) {
        return (Map<String, String>) response.get(EngineProtocol.INFO);
    }

    private static boolean loadable(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
