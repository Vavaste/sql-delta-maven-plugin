package io.github.vavaste.sqldelta;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.maven.plugin.logging.Log;

import io.github.vavaste.sqldelta.engine.Note;
import io.github.vavaste.sqldelta.engine.Statement;
import io.github.vavaste.sqldelta.engine.Statement.Classification;

/**
 * The engine's answer for one run, read from the response map: printed to the console, and rendered as the draft
 * file, which is a commented header, the notes, then one block per statement.
 */
final class Draft {

    private static final int MAX_CONSOLE_STATEMENT = 160;

    private static final String LEGEND = """
            -- Review every statement before using it. [SAFE] cannot fail on existing rows and loses nothing; [REVIEW] may fail or
            -- lock on existing rows; [DATA LOSS] destroys data and is commented out unless includeDrops=true.
            -- Not in this draft: renames (they appear as add plus drop), data backfills, defaults for new NOT NULL columns.
            """;

    final List<Statement> statements = new ArrayList<>();
    final List<Note> notes = new ArrayList<>();
    final Map<String, String> info;
    private final boolean includeDrops;

    @SuppressWarnings("unchecked")
    Draft(Map<String, Object> response, boolean includeDrops) {
        for (Object item : (List<Object>) response.getOrDefault(EngineProtocol.STATEMENTS, List.of())) {
            Map<String, Object> statement = (Map<String, Object>) item;
            // PHASE only completes the record: the engine sends the statements already in execution order
            statements.add(new Statement(
                    Statement.Phase.valueOf((String) statement.get(EngineProtocol.PHASE)),
                    Classification.valueOf((String) statement.get(EngineProtocol.CLASSIFICATION)),
                    (String) statement.get(EngineProtocol.SQL),
                    (String) statement.get(EngineProtocol.REASON)));
        }
        for (Object item : (List<Object>) response.getOrDefault(EngineProtocol.NOTES, List.of())) {
            Map<String, Object> note = (Map<String, Object>) item;
            notes.add(new Note(Boolean.TRUE.equals(note.get(EngineProtocol.WARNING)),
                    (String) note.get(EngineProtocol.TABLE), (String) note.get(EngineProtocol.MESSAGE)));
        }
        this.info = (Map<String, String>) response.getOrDefault(EngineProtocol.INFO, Map.of());
        this.includeDrops = includeDrops;
    }

    boolean isEmpty() {
        return statements.isEmpty() && notes.isEmpty();
    }

    boolean hasExecutableStatements() {
        return statements.stream().anyMatch(statement -> !isCommentedOut(statement));
    }

    void print(Log log) {
        if (statements.isEmpty()) {
            String entities = info(EngineProtocol.ENTITIES);
            log.info("No differences: " + entities + ("1".equals(entities) ? " entity in " : " entities in ")
                    + info(EngineProtocol.ENTITY_PACKAGES) + ", " + info(EngineProtocol.DATABASE) + ", schema "
                    + info(EngineProtocol.COMPARED_SCHEMA));
        } else {
            log.info(counts());
        }
        for (Statement statement : statements) {
            log.info(consoleLine(statement));
        }
        for (Note note : notes) {
            if (note.warning()) {
                log.warn(text(note));
            } else {
                log.info(text(note));
            }
        }
    }

    String render(String pluginVersion, Instant generated, String sanitizedUrl) {
        StringBuilder out = new StringBuilder();
        out.append("-- sql-delta ").append(pluginVersion).append(" draft migration, generated ")
                .append(generated.truncatedTo(ChronoUnit.SECONDS)).append('\n');
        out.append("-- database: ").append(info(EngineProtocol.DATABASE)).append(", schema ")
                .append(info(EngineProtocol.COMPARED_SCHEMA)).append(", ").append(sanitizedUrl).append('\n');
        out.append("-- entities: ").append(info(EngineProtocol.ENTITIES)).append(" in ")
                .append(info(EngineProtocol.ENTITY_PACKAGES)).append(" (Hibernate ")
                .append(info(EngineProtocol.HIBERNATE_VERSION)).append(")\n");
        out.append("-- naming: ").append(info(EngineProtocol.NAMING)).append('\n');
        out.append("-- engine: liquibase-core ").append(info(EngineProtocol.LIQUIBASE_VERSION)).append('\n');
        out.append(LEGEND);
        if (!notes.isEmpty()) {
            out.append("-- Notes:\n");
            for (Note note : notes) {
                comment(out, (note.warning() ? "WARNING " : "INFO ") + text(note));
            }
        }
        for (Statement statement : statements) {
            out.append("\n-- [").append(label(statement.classification())).append(']');
            if (statement.reason() != null && !statement.reason().isBlank()) {
                out.append(' ').append(statement.reason().strip().replaceAll("\\s*\\R\\s*", " "));
            }
            out.append('\n');
            String sql = statement.sql().strip() + ";";
            if (isCommentedOut(statement)) {
                comment(out, sql);
            } else {
                out.append(sql).append('\n');
            }
        }
        return out.toString();
    }

    static String count(int count, String singular, String plural) {
        return count + " " + (count == 1 ? singular : plural);
    }

    private String counts() {
        List<String> parts = new ArrayList<>();
        for (Classification classification : Classification.values()) {
            long count = statements.stream().filter(statement -> statement.classification() == classification).count();
            if (count > 0) {
                String suffix = classification == Classification.DATA_LOSS && !includeDrops ? " (commented out)" : "";
                parts.add(count + " " + label(classification) + suffix);
            }
        }
        return count(statements.size(), "statement", "statements") + ": " + String.join(", ", parts);
    }

    private String consoleLine(Statement statement) {
        String sql = statement.sql().strip().replaceAll("\\s+", " ");
        if (sql.length() > MAX_CONSOLE_STATEMENT) {
            sql = sql.substring(0, MAX_CONSOLE_STATEMENT - 3) + "...";
        }
        return "  [" + label(statement.classification()) + "] " + (isCommentedOut(statement) ? "-- " : "") + sql;
    }

    private boolean isCommentedOut(Statement statement) {
        return statement.classification() == Classification.DATA_LOSS && !includeDrops;
    }

    private String info(String key) {
        return info.getOrDefault(key, "?");
    }

    private static String label(Classification classification) {
        return classification.name().replace('_', ' ');
    }

    private static String text(Note note) {
        return note.table() == null ? note.message() : note.table() + ": " + note.message();
    }

    private static void comment(StringBuilder out, String text) {
        for (String line : text.split("\\R", -1)) {
            out.append(line.isEmpty() ? "--" : "-- " + line).append('\n');
        }
    }
}
