package io.github.vavaste.sqldelta.engine;

/**
 * One SQL statement of the draft.
 *
 * @param phase          where it goes in the execution order
 * @param classification how dangerous it is for a table that already holds rows
 * @param sql            one statement, no trailing semicolon
 * @param reason         why it is REVIEW or DATA_LOSS, or null
 */
public record Statement(Phase phase, Classification classification, String sql, String reason) {

    /** Execution order */
    public enum Phase {
        DROP_FOREIGN_KEYS,
        DROP_CHECKS,
        DROP_CONSTRAINTS_AND_INDEXES,
        SEQUENCES,
        CREATE_TABLES,
        ALTER_COLUMNS,
        DROP_COLUMNS,
        DROP_TABLES,
        DROP_SEQUENCES,
        ADD_CONSTRAINTS_AND_INDEXES,
        ADD_CHECKS,
        ADD_FOREIGN_KEYS
    }

    public enum Classification {
        /** Cannot fail because of existing rows and loses nothing. */
        SAFE,
        /** May fail or lock on existing rows. */
        REVIEW,
        /** Destroys data. */
        DATA_LOSS
    }
}
