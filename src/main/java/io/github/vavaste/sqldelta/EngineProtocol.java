package io.github.vavaste.sqldelta;

/**
 * Keys of the two maps the Maven side and the engine exchange. The engine runs in its own class loader, so only JDK
 * types cross: String, Boolean, List and Map. The Maven side calls, by reflection,
 * {@code io.github.vavaste.sqldelta.engine.Engine.run(Map<String, Object> request,
 * Function<String, System.Logger> loggers)}. The engine never throws: a failure comes back under {@link #ERROR}.
 */
public final class EngineProtocol {

    public static final String ENGINE_CLASS = "io.github.vavaste.sqldelta.engine.Engine";

    public static final String URL = "url";
    public static final String USERNAME = "username";
    public static final String PASSWORD = "password";
    /** Schema to compare; absent means the connection's default schema. */
    public static final String SCHEMA = "schema";
    /** List of String; empty means: detect them from the classes in CLASSES_DIRECTORY. */
    public static final String PACKAGES = "packages";
    /** The project's compiled classes, where the entities are looked for when PACKAGES is empty. */
    public static final String CLASSES_DIRECTORY = "classesDirectory";
    public static final String DIALECT = "dialect";
    public static final String PHYSICAL_NAMING_STRATEGY = "physicalNamingStrategy";
    public static final String IMPLICIT_NAMING_STRATEGY = "implicitNamingStrategy";
    /** List of String: table and sequence name patterns left out, besides flyway_schema_history and Liquibase's. */
    public static final String EXCLUDES = "excludes";

    // response
    /** List of Map, in execution order: SQL, PHASE, CLASSIFICATION, REASON. */
    public static final String STATEMENTS = "statements";
    /** List of Map: WARNING (Boolean), TABLE, MESSAGE. */
    public static final String NOTES = "notes";
    public static final String INFO = "info";
    public static final String ERROR = "error";

    public static final String SQL = "sql";
    public static final String PHASE = "phase";
    public static final String CLASSIFICATION = "classification";
    public static final String REASON = "reason";
    public static final String TABLE = "table";
    public static final String WARNING = "warning";
    public static final String MESSAGE = "message";
    public static final String DETAIL = "detail";

    // INFO keys, all String values
    public static final String DATABASE = "database";
    public static final String COMPARED_SCHEMA = "comparedSchema";
    public static final String ENTITIES = "entities";
    public static final String ENTITY_PACKAGES = "entityPackages";
    public static final String HIBERNATE_VERSION = "hibernateVersion";
    public static final String LIQUIBASE_VERSION = "liquibaseVersion";
    public static final String NAMING = "naming";
    /** The latest successful versioned migration in flyway_schema_history, by installed_rank; absent without one. */
    public static final String FLYWAY_VERSION = "flywayVersion";

    private EngineProtocol() {
    }
}
