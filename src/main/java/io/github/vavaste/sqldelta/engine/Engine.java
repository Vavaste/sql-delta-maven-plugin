package io.github.vavaste.sqldelta.engine;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.File;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.net.ConnectException;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Stream;

import jakarta.persistence.Converter;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.MappedSuperclass;
import liquibase.Scope;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.ObjectQuotingStrategy;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import liquibase.structure.core.Schema;
import liquibase.ui.LoggerUIService;
import liquibase.util.LiquibaseUtil;
import org.hibernate.Version;
import org.hibernate.boot.Metadata;
import org.hibernate.dialect.Dialect;
import org.hibernate.engine.jdbc.dialect.internal.StandardDialectResolver;
import org.hibernate.engine.jdbc.dialect.spi.DatabaseMetaDataDialectResolutionInfoAdapter;

import io.github.vavaste.sqldelta.EngineProtocol;

/**
 * Entry point of the engine, called by reflection from the Maven side inside the isolated class loader. It opens the
 * live database and the entities as two Liquibase databases, runs the Diff and answers with plain JDK types only
 * (see EngineProtocol). It never throws errors: a failure comes back under ERROR.
 */
public final class Engine {

    private static final String SPRING_ORM =
            "org.springframework.orm.jpa.persistenceunit.DefaultPersistenceUnitManager";
    private static final String BOOT_4_IMPLICIT = "org.springframework.boot.hibernate.SpringImplicitNamingStrategy";
    private static final String BOOT_3_IMPLICIT =
            "org.springframework.boot.orm.jpa.hibernate.SpringImplicitNamingStrategy";
    private static final String SNAKE_CASE = "org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl";
    private static final String CAMEL_CASE = "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy";
    private static final List<Class<? extends Annotation>> JPA_ANNOTATIONS =
            List.of(Entity.class, Embeddable.class, MappedSuperclass.class, Converter.class);

    private Engine() {
    }

    public static Map<String, Object> run(Map<String, Object> request, Function<String, System.Logger> loggers) {
        ClassLoader loader = Engine.class.getClassLoader();
        try {
            EngineLogging.install(loggers);
            try (ClassLoaderResourceAccessor resources = new ClassLoaderResourceAccessor(loader)) {
                Map<String, Object> scope = Map.of(Scope.Attr.classLoader.name(), loader,
                        Scope.Attr.resourceAccessor.name(), resources,
                        Scope.Attr.logService.name(), new EngineLogging.LiquibaseLogService(),
                        Scope.Attr.ui.name(), new LoggerUIService(), "liquibase.analytics.enabled", Boolean.FALSE);
                return Scope.child(scope, () -> diff(request, loader, resources));
            }
        } catch (Throwable error) {
            return Map.of(EngineProtocol.ERROR, error(error, string(request, EngineProtocol.URL)));
        } finally {
            deregisterDrivers(loader);
            EngineLogging.restore();
        }
    }

    private static Map<String, Object> diff(Map<String, Object> request, ClassLoader loader,
                                            ClassLoaderResourceAccessor resources) throws Exception {
        if (!loadable(SPRING_ORM, loader)) {
            throw new ConfigurationError("sql-delta needs Spring ORM (spring-orm) on the project class path;"
                    + " plain Hibernate projects are not supported.");
        }
        List<String> packages = entityPackages(request, loader);
        Map<String, String> info = new LinkedHashMap<>();
        info.put(EngineProtocol.ENTITY_PACKAGES, String.join(", ", packages));
        info.put(EngineProtocol.HIBERNATE_VERSION, Version.getVersionString());
        info.put(EngineProtocol.LIQUIBASE_VERSION, LiquibaseUtil.getBuildVersion());
        Database target = null;
        Database reference = null;
        try {
            target = DatabaseFactory.getInstance().openDatabase(string(request, EngineProtocol.URL),
                    string(request, EngineProtocol.USERNAME), (String) request.get(EngineProtocol.PASSWORD),
                    null, null, null, null, resources);
            Connection connection = target.getConnection().getUnderlyingConnection();
            DatabaseMetaData metaData = connection.getMetaData();
            String requestedSchema = string(request, EngineProtocol.SCHEMA);
            if (requestedSchema != null) {
                target.setDefaultSchemaName(target.correctObjectName(requestedSchema, Schema.class));
                // MySQL and MariaDB have catalogs, not schemas: their drivers list none
                if (target.supports(Schema.class) && !schemaExists(metaData, target.getDefaultSchemaName())) {
                    throw new ConfigurationError("Schema " + requestedSchema
                            + " does not exist in the database; check the schema parameter.");
                }
            }
            String schema = target.getDefaultSchemaName();
            target.setObjectQuotingStrategy(ObjectQuotingStrategy.QUOTE_ONLY_RESERVED_WORDS);
            target.setOutputDefaultSchema(false);
            target.setOutputDefaultCatalog(false);
            info.put(EngineProtocol.DATABASE,
                    metaData.getDatabaseProductName() + " " + metaData.getDatabaseProductVersion());
            info.put(EngineProtocol.COMPARED_SCHEMA, schema);

            String referenceUrl = "hibernate:spring:" + String.join(",", packages)
                    + "?dialect=" + URLEncoder.encode(dialect(request, metaData), UTF_8)
                    + naming(request, loader, info);
            reference = DatabaseFactory.getInstance().openDatabase(referenceUrl, null, null, null, null, null, null,
                    resources);
            Metadata metadata = metadataOf(reference);
            if (metadata.getEntityBindings().isEmpty()) {
                throw new ConfigurationError("No JPA entity was found in " + String.join(", ", packages)
                        + "; check the packages parameter.");
            }
            info.put(EngineProtocol.ENTITIES, String.valueOf(metadata.getEntityBindings().size()));

            Diff diff = new Diff(reference, target, metadata, schema, strings(request, EngineProtocol.EXCLUDES));
            diff.run();
            String flywayVersion = flywayVersion(connection, schema);
            if (flywayVersion != null) {
                info.put(EngineProtocol.FLYWAY_VERSION, flywayVersion);
            }
            List<Map<String, Object>> statements = diff.statements.stream()
                    .map(statement -> map(EngineProtocol.SQL, statement.sql(),
                            EngineProtocol.PHASE, statement.phase().name(),
                            EngineProtocol.CLASSIFICATION, statement.classification().name(),
                            EngineProtocol.REASON, statement.reason()))
                    .toList();
            List<Map<String, Object>> notes = diff.notes.stream()
                    .map(note -> map(EngineProtocol.WARNING, note.warning(), EngineProtocol.TABLE, note.table(),
                            EngineProtocol.MESSAGE, note.message()))
                    .toList();
            return map(EngineProtocol.INFO, info, EngineProtocol.STATEMENTS, statements, EngineProtocol.NOTES, notes);
        } finally {
            try{
                if(reference != null) {
                    reference.close();
                }
            } catch (Exception e) {
                EngineLogging.log(Engine.class.getName(), System.Logger.Level.DEBUG, "Could not close the reference", e);
            }
        }
    }

    /** The packages to scan */
    private static List<String> entityPackages(Map<String, Object> request, ClassLoader loader) throws Exception {
        List<String> packages = strings(request, EngineProtocol.PACKAGES);
        if (!packages.isEmpty()) {
            return packages;
        }
        String directory = string(request, EngineProtocol.CLASSES_DIRECTORY);
        TreeSet<String> found = new TreeSet<>();
        Path root = directory == null ? null : Path.of(directory);
        if (root != null && Files.isDirectory(root)) {
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(path -> path.toString().endsWith(".class")).toList()) {
                    String className = root.relativize(file).toString().replaceFirst("\\.class$", "")
                            .replace(File.separatorChar, '.');
                    int dot = className.lastIndexOf('.');
                    if (dot > 0 && isJpaClass(className, loader)) {
                        found.add(className.substring(0, dot));
                    }
                }
            }
        }
        List<String> topMost = found.stream()
                .filter(name -> found.stream().noneMatch(parent -> name.startsWith(parent + "."))).toList();
        if (topMost.isEmpty()) {
            throw new ConfigurationError("No JPA entity was found in " + directory + "; set the packages parameter"
                    + " when the entities live in another module.");
        }
        return topMost;
    }

    private static boolean isJpaClass(String className, ClassLoader loader) {
        try {
            Class<?> type = Class.forName(className, false, loader);
            return JPA_ANNOTATIONS.stream().anyMatch(type::isAnnotationPresent);
        } catch (Throwable e) {
            return false;
        }
    }

    private static String dialect(Map<String, Object> request, DatabaseMetaData metaData) throws SQLException {
        if (string(request, EngineProtocol.DIALECT) != null) {
            return string(request, EngineProtocol.DIALECT);
        }
        Dialect resolved = new StandardDialectResolver()
                .resolveDialect(new DatabaseMetaDataDialectResolutionInfoAdapter(metaData));
        if (resolved == null) {
            throw new ConfigurationError("Hibernate has no dialect for " + metaData.getDatabaseProductName()
                    + "; set the dialect parameter.");
        }
        return resolved.getClass().getName();
    }

    private static String naming(Map<String, Object> request, ClassLoader loader, Map<String, String> info) {
        String physical = null;
        String implicit = null;
        String source = "hibernate";
        if (loadable(BOOT_4_IMPLICIT, loader)) {
            physical = loadable(SNAKE_CASE, loader) ? SNAKE_CASE : CAMEL_CASE;
            implicit = BOOT_4_IMPLICIT;
            source = "spring-boot-4";
        } else if (loadable(BOOT_3_IMPLICIT, loader)) {
            physical = CAMEL_CASE;
            implicit = BOOT_3_IMPLICIT;
            source = "spring-boot-3";
        }
        String physicalParameter = string(request, EngineProtocol.PHYSICAL_NAMING_STRATEGY);
        String implicitParameter = string(request, EngineProtocol.IMPLICIT_NAMING_STRATEGY);
        if (physicalParameter != null || implicitParameter != null) {
            physical = physicalParameter != null ? physicalParameter : physical;
            implicit = implicitParameter != null ? implicitParameter : implicit;
            source = "parameter";
        }
        info.put(EngineProtocol.NAMING, simpleName(physical, "PhysicalNamingStrategyStandardImpl") + ", "
                + simpleName(implicit, "ImplicitNamingStrategyJpaCompliantImpl") + " (" + source + ")");
        return (physical == null ? "" : "&hibernate.physical_naming_strategy=" + URLEncoder.encode(physical, UTF_8))
                + (implicit == null ? "" : "&hibernate.implicit_naming_strategy=" + URLEncoder.encode(implicit, UTF_8));
    }

    //get metadata by reflection 
    private static Metadata metadataOf(Database reference) throws Exception {
        return (Metadata) reference.getClass().getMethod("getMetadata").invoke(reference);
    }

    private static boolean schemaExists(DatabaseMetaData metaData, String schema) throws SQLException {
        try (ResultSet schemas = metaData.getSchemas()) {
            while (schemas.next()) {
                if (schemas.getString("TABLE_SCHEM").equals(schema)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static String flywayVersion(Connection connection, String schema) {
        try (java.sql.Statement query = connection.createStatement();
             ResultSet rows = query.executeQuery("select \"version\" from \"" + schema + "\".\"flyway_schema_history\""
                     + " where \"success\" = true and \"version\" is not null"
                     + " order by \"installed_rank\" desc fetch first 1 rows only")) {
            return rows.next() ? rows.getString(1) : null;
        } catch (SQLException e) {
            return null;
        }
    }

    /** The message the user sees: the engine's own checks, unusable classes, the connection, or the stack trace. */
    static Map<String, Object> error(Throwable error, String url) {
        String message = null;
        Throwable root = error;
        // the innermost cause that says what went wrong wins over its wrappers
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            root = cause;
            if (cause instanceof ConfigurationError) {
                message = cause.getMessage();
            } else if (cause instanceof ClassNotFoundException || cause instanceof ClassCastException) {
                message = "A dialect or naming strategy class cannot be used (" + firstLine(cause.getMessage())
                        + "); check the dialect and naming strategy parameters.";
            } else if (cause instanceof org.hibernate.InstantiationException) {
                message = firstLine(cause.getMessage()) + "; sql-delta builds the entities without Spring, so"
                        + " converters and entity listeners need a constructor without arguments.";
            } else if (isConnectionFailure(cause)) {
                String reason = (cause instanceof UnknownHostException ? "unknown host " : "")
                        + firstLine(cause.getMessage());
                // the Maven side redacts every message: the url becomes its sanitized form, the password ****
                message = "Cannot connect to " + url + ": " + reason + (reason.endsWith(".") ? "" : ".")
                        + " Check the url and username parameters, and the password in the SQLDELTA_PASSWORD"
                        + " environment variable or the password parameter.";
            }
        }
        if (message == null) {
            message = "The engine failed with " + root.getClass().getSimpleName() + ": " + firstLine(root.getMessage())
                    + "; the detail holds the stack trace.";
        }
        return map(EngineProtocol.MESSAGE, message, EngineProtocol.DETAIL, EngineLogging.stackTrace(error));
    }

    private static boolean isConnectionFailure(Throwable error) {
        if (error instanceof SQLException sql && sql.getSQLState() != null) {
            // 08 connection, 28 authorization, 3D unknown database
            return sql.getSQLState().startsWith("08") || sql.getSQLState().startsWith("28")
                    || sql.getSQLState().startsWith("3D");
        }
        return error instanceof UnknownHostException || error instanceof ConnectException;
    }

    /** The drivers of the JVM's own loader belong to whoever embeds the engine, as the tests do: they stay. */
    private static void deregisterDrivers(ClassLoader loader) {
        if (loader == ClassLoader.getSystemClassLoader()) {
            return;
        }
        for (Driver driver : Collections.list(DriverManager.getDrivers())) {
            try {
                if (driver.getClass().getClassLoader() == loader) {
                    DriverManager.deregisterDriver(driver);
                }
            } catch (SQLException | SecurityException e) {
                EngineLogging.log(Engine.class.getName(), System.Logger.Level.DEBUG,
                        "Could not deregister a driver", e);
            }
        }
    }

    private static boolean loadable(String className, ClassLoader loader) {
        try {
            Class.forName(className, false, loader);
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    private static String simpleName(String className, String hibernateDefault) {
        return className == null ? hibernateDefault : className.substring(className.lastIndexOf('.') + 1);
    }

    private static String firstLine(String text) {
        return text == null ? "" : text.strip().lines().findFirst().orElse("");
    }

    /** A map that, unlike Map.of, takes null values: an absent reason or table. */
    private static Map<String, Object> map(Object... keysAndValues) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }

    private static String string(Map<String, Object> request, String key) {
        return (String) request.get(key);
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Map<String, Object> request, String key) {
        return (List<String>) request.getOrDefault(key, List.of());
    }

    static final class ConfigurationError extends RuntimeException {

        ConfigurationError(String message) {
            super(message);
        }
    }
}
