package io.github.vavaste.sqldelta.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static io.github.vavaste.sqldelta.EngineProtocol.CLASSIFICATION;
import static io.github.vavaste.sqldelta.EngineProtocol.SQL;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.vavaste.sqldelta.EngineProtocol;

/**
 * The engine on PostgreSQL, mostly whole round trips: Hibernate creates the database from one model, the engine diffs
 * it against another, the draft is applied, a second diff finds nothing and Hibernate validates the schema.
 */
class PostgresScenarioTest {

    @Test
    void everyKindOfChangeOnTheStore() throws SQLException {
        Map<String, Object> draft = roundTrip("store.v1", "store.v2",
                List.of("create table legacy_audit (id bigint primary key, customer_id bigint references customer(id),"
                                + " code varchar(10) unique)",
                        "create index idx_legacy_code on legacy_audit(code)",
                        // the tables drop in name order: its foreign key must go before legacy_audit
                        "create table legacy_audit_line (id bigint primary key,"
                                + " audit_id bigint references legacy_audit)"),
                List.of("alter table customer alter column rating type integer using rating::integer"));

        for (String fragment : List.of("CREATE TABLE coupon", "ADD nickname", "legacy_code DROP NOT NULL",
                "coupon ADD CONSTRAINT", "ADD number", "legacy_audit_line DROP CONSTRAINT", "points SET DEFAULT 0")) {
            assertThat(classificationsOf(draft, fragment)).as(fragment).contains("SAFE");
        }
        for (String fragment : List.of("CREATE SEQUENCE", "ADD tier INTEGER NOT NULL", "city SET NOT NULL",
                "name TYPE VARCHAR(40)", "city TYPE VARCHAR(500)", "balance TYPE NUMERIC(12, 2)", "points TYPE BIGINT",
                "purchase_order ADD CONSTRAINT", "ADD CONSTRAINT uk_purchase_order_number UNIQUE",
                "CREATE INDEX idx_customer_name", "CREATE INDEX idx_customer_email")) {
            assertThat(classificationsOf(draft, fragment)).as(fragment).contains("REVIEW");
        }
        for (String fragment : List.of("DROP COLUMN legacy_code", "DROP TABLE legacy_audit",
                "DROP TABLE legacy_audit_line")) {
            assertThat(classificationsOf(draft, fragment)).as(fragment).contains("DATA_LOSS");
        }
        for (String fragment : List.of("USING", "rating", "status", "legacy_audit DROP", "idx_legacy")) {
            assertThat(classificationsOf(draft, fragment)).as(fragment).isEmpty();
        }
        List<String> messages = Databases.messages(draft);
        assertThat(messages).hasSize(4);
        for (String note : List.of("Column rating is varchar(255) in the database and integer in the entities",
                "RENAME COLUMN legacy_code TO nickname", "DROP INDEX idx_customer_city",
                "for its delete rule: ON DELETE NO ACTION in the database, CASCADE in the entities.")) {
            assertThat(messages).as(note).anyMatch(message -> message.contains(note));
        }
    }

    @Test
    void aSchemaHibernateCreatedFromTheSameModelGivesAnEmptyDraft() throws SQLException {
        // comments are not compared
        Map<String, Object> draft = roundTrip("store.v2", "store.v2", List.of("comment on table customer is 'Buyers'",
                "comment on column customer.name is 'Shown on invoices'"), List.of());

        assertThat(Databases.sql(draft)).isEmpty();
        assertThat(Databases.messages(draft)).isEmpty();
    }

    @Test
    void entitiesMappedToAnotherSchemaAreOnlyNoted() throws SQLException {
        // account is compared; both ledger tables are not
        Map<String, Object> draft = roundTrip("schemas", "schemas", List.of(
                "alter table account add column extra varchar(10)",
                "alter table ledger add column legacy_total numeric not null"), List.of());

        assertThat(Databases.sql(draft)).containsExactly("ALTER TABLE account DROP COLUMN extra");
        assertThat(Databases.messages(draft)).containsExactly(
                "1 entity is mapped to schema billing and not compared.",
                "Table ledger is mapped to this schema and to another one; tables are matched by name, so it is not"
                        + " compared.");
    }

    @Test
    void identityColumnsAndViewsGetNoStatementThatFails() throws SQLException {
        Map<String, Object> draft = roundTrip("edges", "edges", List.of(
                        // an identity id in the entities, a plain column in the database
                        "alter table widget alter column id drop identity",
                        // an identity column no entity maps any more, which cannot be made nullable
                        "alter table widget add column old_id bigint generated by default as identity",
                        // an entity mapped onto a view
                        "drop table widget_summary",
                        "create view widget_summary as select id, name from widget"),
                List.of("alter table widget alter column id add generated by default as identity"));

        assertThat(Databases.sql(draft)).containsExactly("ALTER TABLE widget DROP COLUMN old_id");
        assertThat(Databases.messages(draft)).singleElement().asString()
                .startsWith("Column id is an identity column in the entities but not in the database")
                .endsWith("ALTER TABLE widget ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY"
                        + " (START WITH <highest id + 1>)");
    }

    @Test
    void aStoreDiffReportsTheFlywayVersionAndLeavesTheHistoryTableOut() throws SQLException {
        Databases database = Databases.newPostgres();
        database.hibernate("create", "store.v2");
        // a failed migration and, applied last, a repeatable one, which has no version
        database.execute(List.of(
                "create table flyway_schema_history (installed_rank int, version varchar(50), success boolean)",
                "insert into flyway_schema_history values (1, '1', true), (2, '2', true), (3, '3', false),"
                        + " (4, null, true)"));

        Map<String, Object> draft = database.diff("store.v2");

        assertThat(Databases.sql(draft)).isEmpty();
        assertThat(Databases.info(draft)).containsEntry(EngineProtocol.FLYWAY_VERSION, "2")
                .containsEntry(EngineProtocol.COMPARED_SCHEMA, "public")
                .containsEntry(EngineProtocol.ENTITIES, "3");
        assertThat(Databases.info(draft).get(EngineProtocol.DATABASE)).startsWith("PostgreSQL ");
    }

    @Test
    void aShorterVarcharFailsOnALongerValueInsteadOfTruncatingIt() throws SQLException {
        Databases database = Databases.newPostgres();
        database.hibernate("create", "store.v1");
        String longName = "x".repeat(60);
        database.execute(List.of("insert into customer (id, name, legacy_code) values (1, '" + longName + "', 'a')"));

        String shrink = Databases.sql(database.diff("store.v2")).stream()
                .filter(sql -> sql.contains("ALTER COLUMN name TYPE")).findFirst().orElseThrow();

        assertThatThrownBy(() -> database.execute(List.of(shrink))).hasMessageContaining("too long");
        try (Connection connection = DriverManager.getConnection(database.url(), database.user(), database.password());
             ResultSet rows = connection.createStatement().executeQuery("select name from customer")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).isEqualTo(longName);
        }
    }

    /**
     * The draft from a database Hibernate created for one model, after the setup, to the other model. Before it is
     * returned, it is applied with the manual statements, which the developer writes after reading the notes; then a
     * second diff finds nothing and Hibernate validates the schema.
     */
    private static Map<String, Object> roundTrip(String from, String to, List<String> setup, List<String> manual)
            throws SQLException {
        Databases database = Databases.newPostgres();
        database.hibernate("create", from);
        database.execute(setup);
        Map<String, Object> draft = database.diff(to);
        assertThat(Databases.sql(draft)).doesNotHaveDuplicates();
        database.execute(Databases.sql(draft));
        database.execute(manual);
        assertThat(Databases.sql(database.diff(to))).isEmpty();
        database.hibernate("validate", to);
        return draft;
    }

    /** The classification of every statement whose SQL contains the fragment, ignoring case. */
    private static List<Object> classificationsOf(Map<String, Object> draft, String fragment) {
        return Databases.statements(draft).stream()
                .filter(statement -> ((String) statement.get(SQL)).toLowerCase().contains(fragment.toLowerCase()))
                .map(statement -> statement.get(CLASSIFICATION)).toList();
    }
}
