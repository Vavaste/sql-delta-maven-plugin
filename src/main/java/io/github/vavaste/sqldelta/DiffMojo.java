package io.github.vavaste.sqldelta;

import java.io.File;
import java.nio.file.Path;
import java.util.regex.Pattern;

import javax.inject.Inject;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.eclipse.aether.RepositorySystem;

/**
 * Writes the draft migration that brings the database in line with the entities, either to outputFile or to the next Flyway migration in migrationsDirectory
 */
@Mojo(name = "diff", requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME, threadSafe = true)
@Execute(phase = LifecyclePhase.PROCESS_CLASSES)
public final class DiffMojo extends AbstractSqlDeltaMojo {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]+");

    /** Where the draft goes, unless it becomes a Flyway migration. */
    @Parameter(property = "sqldelta.outputFile", defaultValue = "${project.build.directory}/sql-delta/migration.sql")
    private File outputFile;
    /**
     * Directory of the Flyway migrations, scanned with its subdirectories for {@code V<version>__*.sql}. A missing
     * directory means no migration yet.
     */
    @Parameter(property = "sqldelta.migrationsDirectory")
    private File migrationsDirectory;
    /** The draft becomes {@code V<next>__<name>.sql} in migrationsDirectory. Letters, digits and underscores. */
    @Parameter(property = "sqldelta.name")
    private String name;

    @Inject
    public DiffMojo(RepositorySystem repositorySystem) {
        super(repositorySystem);
    }

    @Override
    void finish(Draft draft, String text) throws MojoExecutionException, MojoFailureException {
        if (name != null && migrationsDirectory == null) {
            getLog().warn("The name parameter is ignored without migrationsDirectory");
        }
        if (draft.isEmpty()) {
            deleteStale(outputFile);
            return;
        }
        Path migration = migrationsDirectory == null ? null : nextMigration(draft);
        if (migration != null) {
            deleteStale(outputFile);
        }
        Path file = migration != null ? migration : outputFile.toPath();
        write(file, text, migration != null);
        getLog().info("Draft written to " + file);
    }

    private Path nextMigration(Draft draft) throws MojoExecutionException, MojoFailureException {
        if (name != null && !NAME.matcher(name).matches()) {
            throw new MojoExecutionException("The name '" + name + "' may only contain letters, digits and"
                    + " underscores: use for example -Dsqldelta.name=add_order_notes");
        }
        if (!draft.hasExecutableStatements()) {
            getLog().info("The draft has no statement to run, so it goes to " + outputFile
                    + " instead of a new migration");
            return null;
        }
        Path directory = migrationsDirectory.toPath();
        Path newest = FlywayVersions.newest(directory);
        String newestVersion = newest == null ? null : FlywayVersions.version(newest);
        String applied = draft.info.get(EngineProtocol.FLYWAY_VERSION);
        String next = FlywayVersions.next(newestVersion, applied);
        if (name == null) {
            getLog().info("Add -Dsqldelta.name=<description> to write the draft to " + directory + " as V" + next
                    + "__<description>.sql");
            return null;
        }
        if (newest != null && applied != null && FlywayVersions.compare(newestVersion, applied) > 0) {
            throw new MojoFailureException(newest.getFileName() + " is not applied to the database yet (it is at"
                    + " version " + applied + "): apply it or delete it before drafting the next migration");
        }
        return directory.resolve("V" + next + "__" + name + ".sql");
    }
}
