package io.github.vavaste.sqldelta;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.Exclusion;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;

public abstract class AbstractSqlDeltaMojo extends AbstractMojo {

    private final RepositorySystem repositorySystem;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;
    @Parameter(defaultValue = "${plugin}", readonly = true, required = true)
    private PluginDescriptor plugin;

    /** JDBC URL of the database to compare the entities with. Required, except in the modules the goal skips. */
    @Parameter(property = "sqldelta.url")
    private String url;
    /** Database user. */
    @Parameter(property = "sqldelta.username")
    private String username;
    /**
     * Database password. Prefer the SQLDELTA_PASSWORD environment variable, which keeps it out of the POM and the
     * command line; a mvn -X log contains it either way.
     */
    @Parameter(property = "sqldelta.password")
    private String password;
    /** Schema to compare, which must exist. Default: the connection's. The statements do not name it. */
    @Parameter(property = "sqldelta.schema")
    private String schema;
    /** Packages that hold the entities, subpackages included. Default: detected in the module's compiled classes. */
    @Parameter(property = "sqldelta.packages")
    private List<String> packages;
    /**
     * Physical naming strategy class. Default: the one of the Spring Boot on the class path, else Hibernate's;
     * spring.jpa.hibernate.naming.* is not read.
     */
    @Parameter(property = "sqldelta.physicalNamingStrategy")
    private String physicalNamingStrategy;
    /**
     * Implicit naming strategy class. Default: the one of the Spring Boot on the class path, else Hibernate's;
     * spring.jpa.hibernate.naming.* is not read.
     */
    @Parameter(property = "sqldelta.implicitNamingStrategy")
    private String implicitNamingStrategy;
    /** Hibernate dialect class. Default: detected from the connection. */
    @Parameter(property = "sqldelta.dialect")
    private String dialect;
    /**
     * Regular expressions of table and sequence names to leave out, besides flyway_schema_history and the Liquibase
     * tables.
     */
    @Parameter(property = "sqldelta.excludes")
    private List<String> excludes;
    /** Write the DATA LOSS statements as SQL instead of commenting them out. */
    @Parameter(property = "sqldelta.includeDrops", defaultValue = "false")
    private boolean includeDrops;
    /** liquibase-core version the engine runs with. 4.33.0 is the last Apache-2.0 release; 5.0.0 is refused. */
    @Parameter(property = "sqldelta.liquibaseVersion", defaultValue = "4.33.0")
    private String liquibaseVersion;
    /** Version of liquibase-hibernate6 (Hibernate 6.6) or liquibase-hibernate7 (Hibernate 7). */
    @Parameter(property = "sqldelta.liquibaseHibernateVersion", defaultValue = "5.0.4")
    private String liquibaseHibernateVersion;
    /** Skip the goal. */
    @Parameter(property = "sqldelta.skip", defaultValue = "false")
    private boolean skip;

    AbstractSqlDeltaMojo(RepositorySystem repositorySystem) {
        this.repositorySystem = repositorySystem;
    }

    abstract void finish(Draft draft, String text) throws MojoExecutionException, MojoFailureException;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("Skipped because sqldelta.skip is true");
            return;
        }
        if ("pom".equals(project.getPackaging())) {
            getLog().info("Skipped: " + project.getArtifactId() + " has packaging pom");
            return;
        }
        String extension = hibernateExtension();
        if (extension == null) {
            getLog().info("Skipped: " + project.getArtifactId() + " does not use org.hibernate.orm:hibernate-core");
            return;
        }
        if (url == null || !url.strip().startsWith("jdbc:")) {
            throw new MojoExecutionException("The url parameter must be the JDBC URL of the database: set <url> in"
                    + " the plugin configuration or pass -Dsqldelta.url=jdbc:...");
        }
        if (liquibaseVersion.strip().equals("5.0.0")) {
            throw new MojoExecutionException("liquibase-core 5.0.0 is refused because its license metadata is wrong:"
                    + " use 4.33.0, the default, or a later 5.x release");
        }
        String resolvedPassword = password == null || password.isEmpty()
                ? System.getenv("SQLDELTA_PASSWORD") : password;
        MavenLog loggers = new MavenLog(getLog(), url.strip(), resolvedPassword);
        List<File> classpath = engineClasspath(extension);

        List<String> entityPackages = nonBlank(packages);
        getLog().info("Comparing the entities in "
                + (entityPackages.isEmpty() ? "detected packages" : String.join(", ", entityPackages))
                + " with " + JdbcUrls.sanitize(url));
        Map<String, Object> response =
                EngineLauncher.run(classpath, request(resolvedPassword, entityPackages), loggers);
        if (response.get(EngineProtocol.ERROR) != null) {
            throw engineError((Map<?, ?>) response.get(EngineProtocol.ERROR), loggers);
        }
        Draft draft = new Draft(response, includeDrops);
        draft.print(getLog());
        finish(draft, draft.render(plugin.getVersion(), Instant.now(), JdbcUrls.sanitize(url)));
    }

    /** liquibase-hibernate6 or liquibase-hibernate7 for the project's Hibernate, or null without Hibernate. */
    private String hibernateExtension() throws MojoExecutionException {
        String version = null;
        for (Artifact artifact : projectArtifacts()) {
            String id = artifact.getGroupId() + ":" + artifact.getArtifactId();
            // hibernate-core and hibernate-core-jakarta 5.x
            if (id.startsWith("org.hibernate:hibernate-core")) {
                throw new MojoExecutionException(
                        "Hibernate 5 is not supported: sql-delta needs Hibernate ORM 6.6 or 7.x");
            }
            if (id.equals("org.hibernate.orm:hibernate-core")) {
                version = artifact.getVersion();
            }
        }
        if (version == null) {
            return null;
        }
        if (version.startsWith("6.6.")) {
            return "liquibase-hibernate6";
        }
        if (version.startsWith("7.")) {
            return "liquibase-hibernate7";
        }
        throw new MojoExecutionException("Hibernate ORM " + version + " is not supported: sql-delta needs"
                + " Hibernate ORM 6.6 or 7.x, which Spring Boot 3.4, 3.5 and 4.x use");
    }

    /**
     * The plugin jar, Liquibase, the project's classes and its dependencies. The project's own Liquibase is left out
     * for the version the engine needs, and so is an slf4j-api 1.x: the engine's slf4j provider needs slf4j 2.
     */
    private List<File> engineClasspath(String extension) throws MojoExecutionException {
        List<Artifact> artifacts = projectArtifacts();
        boolean projectHasSlf4j2 = artifacts.stream()
                .anyMatch(artifact -> isSlf4jApi(artifact) && !artifact.getVersion().startsWith("1."));
        List<File> files = new ArrayList<>();
        files.add(plugin.getPluginArtifact().getFile());
        files.addAll(resolveLiquibase(extension, !projectHasSlf4j2));
        files.add(new File(project.getBuild().getOutputDirectory()));
        for (Artifact artifact : artifacts) {
            boolean replaced = artifact.getGroupId().startsWith("org.liquibase")
                    || (isSlf4jApi(artifact) && !projectHasSlf4j2);
            if (!replaced) {
                files.add(artifact.getFile());
            }
        }
        getLog().debug("Engine class path: " + files);
        return files;
    }

    /**
     * From the plugin repositories, as dependencies of an anonymous root: the resolver drops optional dependencies
     * only below the first level, so liquibase-core as the root would bring its optional picocli.
     */
    private List<File> resolveLiquibase(String extension, boolean withSlf4j) throws MojoExecutionException {
        List<Dependency> dependencies = new ArrayList<>();
        dependencies.add(new Dependency(new DefaultArtifact("org.liquibase:liquibase-core:" + liquibaseVersion.strip()),
                "runtime"));
        // never with its dependencies: they would bring a second hibernate-core next to the project's
        dependencies.add(new Dependency(new DefaultArtifact("org.liquibase.ext:" + extension + ":"
                + liquibaseHibernateVersion.strip()), "runtime", false, List.of(new Exclusion("*", "*", "*", "*"))));
        if (withSlf4j) {
            dependencies.add(new Dependency(new DefaultArtifact("org.slf4j:slf4j-api:2.0.17"), "runtime"));
        }
        List<RemoteRepository> repositories = project.getRemotePluginRepositories();
        DependencyRequest request = new DependencyRequest(new CollectRequest(dependencies, null, repositories), null);
        try {
            return repositorySystem.resolveDependencies(session.getRepositorySession(), request).getArtifactResults()
                    .stream().map(result -> result.getArtifact().getFile()).toList();
        } catch (DependencyResolutionException e) {
            throw new MojoExecutionException("Cannot resolve " + dependencies + " from the plugin repositories "
                    + repositories + ": check the versions and that a repository is reachable. " + e.getMessage(), e);
        }
    }

    private Map<String, Object> request(String resolvedPassword, List<String> entityPackages) {
        Map<String, Object> request = new HashMap<>();
        request.put(EngineProtocol.URL, url.strip());
        putIfSet(request, EngineProtocol.USERNAME, username);
        if (resolvedPassword != null) {
            request.put(EngineProtocol.PASSWORD, resolvedPassword);
        }
        putIfSet(request, EngineProtocol.SCHEMA, schema);
        request.put(EngineProtocol.PACKAGES, entityPackages);
        request.put(EngineProtocol.CLASSES_DIRECTORY, project.getBuild().getOutputDirectory());
        putIfSet(request, EngineProtocol.DIALECT, dialect);
        putIfSet(request, EngineProtocol.PHYSICAL_NAMING_STRATEGY, physicalNamingStrategy);
        putIfSet(request, EngineProtocol.IMPLICIT_NAMING_STRATEGY, implicitNamingStrategy);
        request.put(EngineProtocol.EXCLUDES, nonBlank(excludes));
        return request;
    }

    private MojoExecutionException engineError(Map<?, ?> error, MavenLog loggers) {
        if (error.get(EngineProtocol.DETAIL) != null) {
            getLog().debug(loggers.redact(error.get(EngineProtocol.DETAIL).toString()));
        }
        return new MojoExecutionException(loggers.redact(String.valueOf(error.get(EngineProtocol.MESSAGE))));
    }

    void write(Path file, String text, boolean createNew) throws MojoExecutionException {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            if (createNew) {
                Files.writeString(file, text, StandardOpenOption.CREATE_NEW);
            } else {
                Files.writeString(file, text);
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Cannot write " + file + ": " + e.getMessage(), e);
        }
    }

    /** A file left by an earlier run would look current. Only a file inside the build directory is deleted. */
    void deleteStale(File file) throws MojoExecutionException {
        Path stale = file.toPath().toAbsolutePath().normalize();
        if (stale.startsWith(Path.of(project.getBuild().getDirectory()).toAbsolutePath().normalize())) {
            try {
                Files.deleteIfExists(stale);
            } catch (IOException e) {
                throw new MojoExecutionException("Cannot delete the stale file " + stale + ": " + e.getMessage(), e);
            }
        }
    }

    private List<Artifact> projectArtifacts() {
        // Maven filters them by the goal's resolution scope
        return project.getArtifacts().stream()
                .filter(artifact -> artifact.getFile() != null && artifact.getArtifactHandler().isAddedToClasspath())
                .toList();
    }

    private static boolean isSlf4jApi(Artifact artifact) {
        return artifact.getGroupId().equals("org.slf4j") && artifact.getArtifactId().equals("slf4j-api");
    }

    private static List<String> nonBlank(List<String> values) {
        return values == null ? List.of()
                : values.stream().filter(value -> value != null && !value.isBlank()).map(String::strip).toList();
    }

    private static void putIfSet(Map<String, Object> request, String key, String value) {
        if (value != null && !value.isBlank()) {
            request.put(key, value.strip());
        }
    }
}
