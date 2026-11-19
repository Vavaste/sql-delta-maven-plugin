# Contributing

## Layout

`io.github.vavaste.sqldelta` is the Maven side, in the plugin's class realm:

- `DiffMojo`, `CheckMojo`: the two goals, each deciding what to do with the draft.
- `AbstractSqlDeltaMojo`: the shared parameters, the project checks, the engine class path and the run.
- `EngineLauncher`: the isolated class loader, with the platform loader as parent, and the engine thread.
- `EngineProtocol`: the keys of the request and response maps; only JDK types cross the class loaders.
- `MavenLog`: the engine's loggers over the Maven log; library chatter goes to debug, the url and password are redacted.
- `Draft`: reads the engine's response, prints it and renders the file.
- `FlywayVersions`: the versions in the migrations directory and the next one.
- `JdbcUrls`: a JDBC URL without credentials or parameters.

`io.github.vavaste.sqldelta.engine` runs in the isolated class loader with the project's Hibernate, Spring and JDBC
driver, and never sees Maven:

- `Engine`: the entry point: entity packages, dialect, naming strategies, error messages, cleanup.
- `Diff`: the Liquibase snapshots and comparison, the corrections before and after it, the notes.
- `Statements`: a Liquibase change as SQL, with its phase, classification and reason.
- `TypeChanges`: column types compared by Hibernate's rules, without `USING` casts.
- `CheckConstraints`: enum value lists and ORDINAL ranges, read from the Hibernate, PostgreSQL and H2 renderings.
- `EngineLogging`, `EngineSlf4jProvider`: java.util.logging, Liquibase and slf4j output sent to the Maven side.
- `Statement`, `Note`: the records of the result.

The engine compiles against Liquibase, Hibernate and slf4j in `provided` scope: the plugin jar contains none of them.

## Build and test

Use the wrapper. The Testcontainers tests need Docker.

| Command | What it runs |
|---|---|
| `./mvnw verify` | Unit tests on H2 and PostgreSQL 18, with Hibernate 7. |
| `./mvnw verify -Phibernate6` | The engine compiled and unit tested on Spring Boot 3.5 and Hibernate 6.6. |
| `./mvnw verify -Dsqldelta.test.postgres.image=postgres:15` | The unit tests on another PostgreSQL. |
| `./mvnw verify -Dliquibase.version=5.0.4` | The engine compiled and unit tested with another liquibase-core. |

An engine change passes both `./mvnw verify` and `./mvnw verify -Phibernate6`. Builds that share one
`~/.m2` at the same time need `-Daether.syncContext.named.factory=file-lock -Daether.syncContext.named.nameMapper=file-gav`.

The engine tests that need a database use H2 in memory or one PostgreSQL container, which `Databases.newPostgres()`
starts on first use and which gives each test a database of its own. Their entity models are in `engine/testmodel`,
one package per model: `store` and `checks` in a v1 and a v2, `schemas` and `edges`. The entities there import
`jakarta.persistence.*`; every other file imports class by class. `PostgresScenarioTest` runs whole
round trips: Hibernate creates the database from one model, the draft for the same or the next model is applied, and a
second diff finds nothing.

## Trying a change on a project

The goals themselves (`DiffMojo`, `CheckMojo`, `AbstractSqlDeltaMojo`, `EngineLauncher`) have no automated test: try
a change on a real Spring project. `./mvnw install -DskipTests` puts the snapshot in `~/.m2`; then, in the project,
run `mvn io.github.vavaste:sql-delta-maven-plugin:<version>:diff -Dsqldelta.url=...` against a throwaway database,
apply the draft, and check that `sql-delta:check` finds nothing.

## Releasing

Push a tag `v<version>`, for example `v0.1.0`. The release workflow sets that version with `versions:set` (nothing is
committed) and runs `./mvnw -Prelease deploy`: sources and javadoc jars, signatures by the Bouncy Castle signer, and
the upload to Maven Central through the Central Portal. It needs the secrets `MAVEN_CENTRAL_USERNAME` and
`MAVEN_CENTRAL_PASSWORD` (a Portal user token), `MAVEN_GPG_KEY` (the armored private key) and `MAVEN_GPG_PASSPHRASE`.
Then move `main` to the next snapshot: `./mvnw versions:set -DnewVersion=0.2.0-SNAPSHOT -DgenerateBackupPoms=false`.

To check the release build without signing or publishing: `./mvnw -Prelease -DskipTests -Dgpg.skip package`.
