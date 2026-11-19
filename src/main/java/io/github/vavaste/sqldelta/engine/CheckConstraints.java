package io.github.vavaste.sqldelta.engine;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.hibernate.boot.Metadata;
import org.hibernate.boot.model.relational.Namespace;
import org.hibernate.mapping.CheckConstraint;
import org.hibernate.mapping.Column;
import org.hibernate.mapping.Table;

import io.github.vavaste.sqldelta.engine.Statement.Classification;
import io.github.vavaste.sqldelta.engine.Statement.Phase;

final class CheckConstraints {

    record Result(List<Statement> statements, List<Note> notes) {
    }

    /** A value list, or the range min to max when values is null. the name is null when unnamed. */
    record Check(String name, String condition, String column, SortedSet<String> values, long min, long max) {

        boolean isRange() {
            return values == null;
        }

        boolean acceptsAllOf(Check other) {
            return isRange() ? min <= other.min && other.max <= max : values.containsAll(other.values);
        }

        boolean sameValues(Check other) {
            return isRange() == other.isRange() && acceptsAllOf(other) && other.acceptsAllOf(this);
        }
    }

    // Value lists as Hibernate writes them (the second only in a @Check), then as PostgreSQL (varchar, char or text
    // column, one value) and H2 (several values, one value) print them back:
    //   status in ('A','B')
    //   status = 'A'
    //   CHECK (((status)::text = ANY ((ARRAY['A'::character varying, 'B'::character varying])::text[])))
    //   CHECK ((active = ANY (ARRAY['N'::bpchar, 'Y'::bpchar])))
    //   CHECK (((status)::text = 'A'::text))
    //   "STATUS" IN('A', 'B')
    //   "STATUS" = 'A'
    private static final List<Pattern> LISTS = List.of(
            Pattern.compile("(\\w+) in \\((.*)\\)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(\\w+) = ('.*')"),
            Pattern.compile("CHECK \\(\\(\\((\\w+)\\)::text = ANY \\(\\(ARRAY\\[(.*)]\\)::text\\[]\\)\\)\\)"),
            Pattern.compile("CHECK \\(\\((\\w+) = ANY \\(ARRAY\\[(.*)]\\)\\)\\)"),
            Pattern.compile("CHECK \\(\\(\\((\\w+)\\)::text = (.*)::text\\)\\)"),
            Pattern.compile("\"(\\w+)\" IN\\((.*)\\)"),
            Pattern.compile("\"(\\w+)\" = (.*)"));

    // Ranges (ORDINAL enums) as Hibernate, PostgreSQL and H2 write them:
    //   status between 0 and 2
    //   CHECK (((status >= 0) AND (status <= 2)))
    //   "STATUS" BETWEEN 0 AND 2
    private static final List<Pattern> RANGES = List.of(
            Pattern.compile("(\\w+) between (\\d{1,18}) and (\\d{1,18})", Pattern.CASE_INSENSITIVE),
            Pattern.compile("CHECK \\(\\(\\((\\w+) >= (\\d{1,18})\\) AND \\(\\1 <= (\\d{1,18})\\)\\)\\)"),
            Pattern.compile("\"(\\w+)\" BETWEEN (\\d{1,18}) AND (\\d{1,18})"));

    // One literal of a list and the comma before it: 'it''s', 'A'::character varying, or H2's U&'caf\00e9' for
    // text that is not ASCII, where \00e9 is a UTF-16 unit, \+01f600 a code point and \\ a backslash.
    private static final Pattern LITERAL =
            Pattern.compile("\\G(?:, ?)?(U&)?'((?:[^']|'')*)'(?:::(?:character varying|bpchar|text))?");
    private static final Pattern UNICODE_ESCAPE = Pattern.compile("\\\\(\\\\|\\+\\p{XDigit}{6}|\\p{XDigit}{4})");

    private static final String POSTGRESQL_CONSTRAINTS = """
            select r.relname, c.conname, case when c.contype = 'c' then pg_get_constraintdef(c.oid) end
              from pg_constraint c
              join pg_namespace n on n.oid = c.connamespace
              left join pg_class r on r.oid = c.conrelid
             where n.nspname = ?""";

    private static final String H2_CONSTRAINTS = """
            select tc.table_name, tc.constraint_name, cc.check_clause
              from information_schema.table_constraints tc
              left join information_schema.check_constraints cc
                on cc.constraint_schema = tc.constraint_schema and cc.constraint_name = tc.constraint_name
             where tc.constraint_schema = ?""";

    private static final String ORDINAL_REASON = "ORDINAL values are positions: inserting, removing or reordering "
            + "enum constants anywhere but at the end changes the meaning of the numbers already stored";

    private final Map<String, Set<String>> columnsByTable = new HashMap<>();
    private final Map<String, List<Check>> checksByTable = new HashMap<>();
    private final Set<String> takenNames = new HashSet<>();
    private final List<Statement> statements = new ArrayList<>();
    private final List<Note> notes = new ArrayList<>();

    static Result compare(Metadata metadata, Connection connection, String schema, Predicate<String> excluded)
            throws SQLException {
        String product = connection.getMetaData().getDatabaseProductName();
        if (!product.equals("PostgreSQL") && !product.equals("H2")) {
            return new Result(List.of(), List.of(new Note(false, null, "Check constraints are compared only on "
                    + "PostgreSQL and H2, so they were not compared on " + product + ".")));
        }
        CheckConstraints comparison = new CheckConstraints(connection, schema, product.equals("PostgreSQL"));
        for (Namespace namespace : metadata.getDatabase().getNamespaces()) {
            for (Table table : namespace.getTables()) {
                if (table.isPhysicalTable() && !table.isView() && !excluded.test(table.getName())
                        && (table.getSchema() == null || table.getSchema().equalsIgnoreCase(schema))) {
                    comparison.compareTable(table);
                }
            }
        }
        return new Result(comparison.statements, comparison.notes);
    }

    /** The check when the condition is a value list or a range on one column in a rendering above, else null. */
    static Check parse(String name, String condition) {
        String text = condition.strip();
        for (Pattern pattern : RANGES) {
            Matcher matcher = pattern.matcher(text);
            if (matcher.matches()) {
                return new Check(name, condition, matcher.group(1), null,
                        Long.parseLong(matcher.group(2)), Long.parseLong(matcher.group(3)));
            }
        }
        for (Pattern pattern : LISTS) {
            Matcher matcher = pattern.matcher(text);
            if (matcher.matches()) {
                SortedSet<String> values = literals(matcher.group(2));
                return values == null ? null : new Check(name, condition, matcher.group(1), values, 0, 0);
            }
        }
        return null;
    }

    private static SortedSet<String> literals(String list) {
        SortedSet<String> values = new TreeSet<>();
        Matcher matcher = LITERAL.matcher(list);
        int end = 0;
        while (matcher.find()) {
            String value = matcher.group(2).replace("''", "'");
            values.add(matcher.group(1) == null ? value : UNICODE_ESCAPE.matcher(value)
                    .replaceAll(escape -> Matcher.quoteReplacement(unescape(escape.group(1)))));
            end = matcher.end();
        }
        return end == list.length() && !values.isEmpty() ? values : null;
    }

    private static String unescape(String escape) {
        return escape.equals("\\") ? "\\" : Character.toString(Integer.parseInt(escape.replace("+", ""), 16));
    }

    private CheckConstraints(Connection connection, String schema, boolean postgresql) throws SQLException {
        try (ResultSet rs = connection.getMetaData().getColumns(null, schema, "%", "%")) {
            while (rs.next()) {
                if (schema.equals(rs.getString("TABLE_SCHEM"))) {
                    columnsByTable.computeIfAbsent(upper(rs.getString("TABLE_NAME")), table -> new HashSet<>())
                            .add(upper(rs.getString("COLUMN_NAME")));
                }
            }
        }
        // the shapes name exactly one column, so the catalog's column list of a check is not needed
        String sql = postgresql ? POSTGRESQL_CONSTRAINTS : H2_CONSTRAINTS;
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, schema);
            try (ResultSet rs = query.executeQuery()) {
                while (rs.next()) {
                    takenNames.add(upper(rs.getString(2)));
                    Check check = rs.getString(1) == null || rs.getString(3) == null ? null
                            : parse(rs.getString(2), rs.getString(3));
                    if (check != null) {
                        checksByTable.computeIfAbsent(upper(rs.getString(1)), table -> new ArrayList<>()).add(check);
                    }
                }
            }
        }
    }

    private void compareTable(Table table) {
        Map<String, List<Check>> wanted = new LinkedHashMap<>();
        for (Column column : table.getColumns()) {
            wanted.put(upper(column.getName()), new ArrayList<>());
        }
        for (Column column : table.getColumns()) {
            for (CheckConstraint constraint : column.getCheckConstraints()) {
                want(table, column.getName(), constraint, wanted);
            }
        }
        for (CheckConstraint constraint : table.getChecks()) {
            want(table, null, constraint, wanted);
        }
        for (Column column : table.getColumns()) {
            compareColumn(table, column.getName(), wanted.get(upper(column.getName())));
        }
    }

    private void want(Table table, String column, CheckConstraint constraint, Map<String, List<Check>> wanted) {
        Check check = parse(constraint.getName(), constraint.getConstraint());
        if (check != null && wanted.containsKey(upper(check.column()))) {
            wanted.get(upper(check.column())).add(check);
        } else if (!columnsByTable.containsKey(upper(table.getName()))) {
            String name = constraint.getName() != null ? constraint.getName() : newName(table.getName(), column);
            takenNames.add(upper(name));
            statements.add(statement(Phase.ADD_CHECKS, Classification.SAFE, table,
                    "ADD CONSTRAINT " + name + " CHECK (" + constraint.getConstraint() + ")", null));
        }
    }

    private void compareColumn(Table table, String column, List<Check> wanted) {
        boolean newColumn = !columnsByTable.getOrDefault(upper(table.getName()), Set.of()).contains(upper(column));
        List<Check> existing = new ArrayList<>();
        for (Check check : checksByTable.getOrDefault(upper(table.getName()), List.of())) {
            if (check.column().equalsIgnoreCase(column)) {
                existing.add(check);
            }
        }
        boolean range = !wanted.isEmpty() && wanted.get(0).isRange();
        if (!wanted.isEmpty() && !existing.isEmpty() && first(existing, c -> c.isRange() == range) == null) {
            notes.add(new Note(true, table.getName(), table.getName() + "." + column + " switches between a value "
                    + "list and a range (a STRING and an ORDINAL enum): convert the column and its data by hand, then "
                    + "replace check " + existing.get(0).name() + " with CHECK (" + wanted.get(0).condition() + ")."));
            return;
        }
        List<Check> missing = new ArrayList<>();
        for (Check check : wanted) {
            Check same = first(existing, c -> c.sameValues(check));
            if (same != null) {
                existing.remove(same);
            } else {
                missing.add(check);
            }
        }
        for (Check check : missing) {
            Check replaced = first(existing, c -> c.isRange() == check.isRange());
            existing.remove(replaced);
            add(table, check, replaced, newColumn);
        }
        // what no wanted check matched is stale
        for (Check check : existing) {
            notes.add(new Note(false, table.getName(), "No entity wants check " + check.name() + " on "
                    + table.getName() + "." + column + " any more; if it is obsolete, drop it with ALTER TABLE "
                    + table.getName() + " DROP CONSTRAINT " + check.name()));
        }
    }

    /** Adds the check of the entities, replacing the dtabase's one under its name when there is one. */
    private void add(Table table, Check check, Check replaced, boolean newColumn) {
        String name = check.name() != null ? check.name()
                : replaced != null ? replaced.name() : newName(table.getName(), check.column());
        takenNames.add(upper(name));
        String reason = null;
        if (replaced != null) {
            String drop = "DROP CONSTRAINT " + replaced.name();
            statements.add(statement(Phase.DROP_CHECKS, Classification.SAFE, table, drop, null));
            reason = check.acceptsAllOf(replaced) ? null : failure(table, check, "no longer accept");
            if (check.isRange()) {
                // a range that accepts every stored number may still give them other constants: never SAFE
                reason = reason == null ? ORDINAL_REASON : reason + "; " + ORDINAL_REASON;
            }
        } else if (!newColumn) {
            reason = failure(table, check, "do not accept");
        }
        Classification classification = reason == null ? Classification.SAFE : Classification.REVIEW;
        statements.add(statement(Phase.ADD_CHECKS, classification, table,
                "ADD CONSTRAINT " + name + " CHECK (" + check.condition() + ")", reason));
    }

    private String failure(Table table, Check check, String accept) {
        return "fails if rows hold values the entities " + accept + "; find them with SELECT count(*) FROM "
                + table.getName() + " WHERE NOT (" + check.condition() + ")";
    }

    private String newName(String table, String column) {
        for (int pass = 0; ; pass++) {
            String name = objectName(table, column, pass == 0 ? "check" : "check" + pass);
            if (takenNames.add(upper(name))) {
                return name;
            }
        }
    }

    static String objectName(String table, String column, String label) {
        byte[] first = table.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
        byte[] second = column == null ? new byte[0] : column.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
        int available = 63 - 1 - label.length() - (column == null ? 0 : 1);
        int firstLength = first.length;
        int secondLength = second.length;
        while (firstLength + secondLength > available) {
            if (firstLength > secondLength) {
                firstLength--;
            } else {
                secondLength--;
            }
        }
        String middle = column == null ? "" : clip(second, secondLength) + "_";
        return clip(first, firstLength) + "_" + middle + label;
    }

    /** Cuts at a UTF-8 character boundary */
    private static String clip(byte[] bytes, int length) {
        while (length > 0 && length < bytes.length && (bytes[length] & 0xC0) == 0x80) {
            length--;
        }
        return new String(bytes, 0, length, StandardCharsets.UTF_8);
    }

    private Statement statement(Phase phase, Classification classification, Table table, String action,
                                String reason) {
        return new Statement(phase, classification, "ALTER TABLE " + table.getName() + " " + action, reason);
    }

    private static Check first(List<Check> checks, Predicate<Check> test) {
        for (Check check : checks) {
            if (test.test(check)) {
                return check;
            }
        }
        return null;
    }

    private static String upper(String name) {
        return name.toUpperCase(Locale.ROOT);
    }
}
