package io.github.vavaste.sqldelta;

import java.io.File;

import javax.inject.Inject;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.eclipse.aether.RepositorySystem;

/**
 * Fails the build when the database differs from the entities
 * */
@Mojo(name = "check", defaultPhase = LifecyclePhase.VERIFY,
        requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME, threadSafe = true)
public final class CheckMojo extends AbstractSqlDeltaMojo {

    /** Where the statements that would be needed are written. */
    @Parameter(property = "sqldelta.outputFile", defaultValue = "${project.build.directory}/sql-delta/check.sql")
    private File outputFile;

    @Inject
    public CheckMojo(RepositorySystem repositorySystem) {
        super(repositorySystem);
    }

    @Override
    void finish(Draft draft, String text) throws MojoExecutionException, MojoFailureException {
        if (draft.isEmpty()) {
            deleteStale(outputFile);
            return;
        }
        write(outputFile.toPath(), text, false);
        getLog().info("Report written to " + outputFile);
        if (!draft.statements.isEmpty()) {
            throw new MojoFailureException("The database differs from the entities: "
                    + Draft.count(draft.statements.size(), "statement", "statements") + ", see " + outputFile);
        }
    }
}
