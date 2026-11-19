# sql-delta example

A Spring Boot 4 application with one entity, `Animal`, Flyway migrations and PostgreSQL 18 in Docker. It shows the loop
the plugin is for: change an entity, preview the migration, write it as the next Flyway file, apply it.

The plugin is configured in `pom.xml` with the `sqldelta.*` properties, which point to the database of `compose.yaml`
(port 5433, database, user and password `example`). You need Java 17 or later and Docker. Run the commands from this
folder: `../mvnw` is the Maven wrapper of the repository.

1. Start the database:

   ```
   docker compose up -d
   ```

2. Start the application once. Flyway applies `V1__create_animal.sql`, Hibernate validates the entity and the
   application exits, as it has no web server:

   ```
   ../mvnw spring-boot:run
   ```

   The entity and the database now match: `../mvnw sql-delta:diff` prints `No differences`.

3. Change the entity: add a field to `src/main/java/com/example/zoo/Animal.java`.

   ```java
   private String habitat;
   ```

4. Preview the migration:

   ```
   ../mvnw sql-delta:diff
   ```

   ```
   [INFO] 1 statement: 1 SAFE
   [INFO]   [SAFE] ALTER TABLE animal ADD habitat VARCHAR(255)
   [INFO] Add -Dsqldelta.name=<description> to write the draft to .../src/main/resources/db/migration as V2__<description>.sql
   [INFO] Draft written to .../target/sql-delta/migration.sql
   ```

5. Write it as the next Flyway migration, `src/main/resources/db/migration/V2__add_animal_habitat.sql`:

   ```
   ../mvnw sql-delta:diff -Dsqldelta.name=add_animal_habitat
   ```

6. Start the application again: Flyway applies V2 and `../mvnw sql-delta:diff` is back to `No differences`.

   ```
   ../mvnw spring-boot:run
   ```

To start over, remove the field and `V2__add_animal_habitat.sql`, then `docker compose down -v`, which also deletes the
database.
