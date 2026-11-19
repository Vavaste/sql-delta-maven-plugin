package io.github.vavaste.sqldelta.engine;

/**
 * Something the user should know that is not a statement: an index only the database has, a suspected rename,
 * a change that needs a hand-written migration.
 *
 * @param warning true for a warning, false for information
 * @param table   the table it is about, or null
 * @param message plain text
 */
public record Note(boolean warning, String table, String message) {
}
