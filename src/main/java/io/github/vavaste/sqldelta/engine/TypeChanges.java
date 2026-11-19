package io.github.vavaste.sqldelta.engine;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import liquibase.change.core.ModifyDataTypeChange;
import liquibase.database.Database;
import liquibase.database.core.H2Database;
import liquibase.structure.core.DataType;
import org.hibernate.boot.Metadata;
import org.hibernate.dialect.Dialect;
import org.hibernate.mapping.Column;
import org.hibernate.type.SqlTypes;

import io.github.vavaste.sqldelta.engine.Statement.Classification;
import io.github.vavaste.sqldelta.engine.Statement.Phase;

/**
 * Compares the type of every mapped column with the database by Hibernate's own rules.
 *  It replaces Liquibase's type changes, whose USING casts cut values short on PostgreSQL instead of failing.
 */
final class TypeChanges {

    private static final Pattern SIZE = Pattern.compile("\\(\\s*(\\d+)\\s*(?:,\\s*(\\d+)\\s*)?\\)");
    private static final List<String> SIZED_TYPES = List.of("varchar", "char", "numeric");

    private final Metadata metadata;
    private final Dialect dialect;
    private final Database target;
    private final Method typeCode;

    TypeChanges(Metadata metadata, Database target) {
        this.metadata = metadata;
        this.dialect = metadata.getDatabase().getDialect();
        this.target = target;
        // getSqlTypeCode takes a Mapping in Hibernate 6 and a MappingContext in Hibernate 7; the metadata is both
        this.typeCode = Arrays.stream(Column.class.getMethods())
                .filter(method -> method.getName().equals("getSqlTypeCode") && method.getParameterCount() == 1
                        && method.getParameterTypes()[0].isInstance(metadata))
                .findFirst().orElseThrow();
    }

    /**
     * Adds what brings the column to the mapped type: a statement within a type family, and across families a note,
     * since only the developer knows how the values convert.
     */
    void compare(String table, liquibase.structure.core.Column column, Column mapped, List<Statement> statements,
                 List<Note> notes) {
        DataType actual = column.getType();
        if (matches(mapped, actual)) {
            return;
        }
        String entityType = mapped.getSqlType(metadata).toLowerCase(Locale.ROOT);
        String databaseType = canonical(actual.getTypeName());
        if (actual.getColumnSize() != null && SIZED_TYPES.contains(databaseType)) {
            databaseType += "(" + actual.getColumnSize()
                    + (databaseType.equals("numeric") ? "," + orZero(actual.getDecimalDigits()) : "") + ")";
        }
        String name = column.getName();
        if (!family(typeCode(mapped)).equals(family(databaseCode(actual)))) {
            notes.add(new Note(true, table, "Column " + name + " is " + databaseType + " in the database and "
                    + entityType + " in the entities, a conversion the draft does not guess; write it by hand, for"
                    + " example ALTER TABLE " + table + " ALTER COLUMN " + name + " TYPE " + entityType + " USING "
                    + name + "::" + entityType));
            return;
        }
        ModifyDataTypeChange change = new ModifyDataTypeChange();
        change.setTableName(table);
        change.setColumnName(name);
        change.setNewDataType(entityType);
        Matcher size = SIZE.matcher(entityType);
        boolean scaleShrinks = canonical(entityType).equals("numeric") && size.find()
                && scale(size) < orZero(actual.getDecimalDigits());
        String reason = "changes " + databaseType + " to " + entityType + (scaleShrinks
                ? ": PostgreSQL rounds existing values to the new scale" : ": existing values must fit the new type");
        for (String sql : Statements.sql(change, target)) {
            String text = sql.replaceAll("(?is)\\s+USING\\s+\\(.*\\)\\s*$", "");
            if (target instanceof H2Database) {
                // H2 drops the column default on ALTER COLUMN c <type>, and keeps it on SET DATA TYPE
                text = text.replaceFirst("(?is)^(ALTER TABLE \\S+ ALTER COLUMN \\S+) (?!SET )", "$1 SET DATA TYPE ");
            }
            statements.add(new Statement(Phase.ALTER_COLUMNS, Classification.REVIEW, text, reason));
        }
    }

    /** True when Hibernate accepts the database type for the column and the sizes agree. */
    boolean matches(Column mapped, DataType actual) {
        int code = typeCode(mapped);
        // nothing to compare: no JDBC type, or an array or a named enum type, which are out of scope
        if (actual.getDataTypeId() == null || code == SqlTypes.ARRAY || code == SqlTypes.NAMED_ENUM
                || code == SqlTypes.NAMED_ORDINAL_ENUM) {
            return true;
        }
        String entityType = mapped.getSqlType(metadata).toLowerCase(Locale.ROOT);
        String entityName = canonical(entityType);
        String databaseName = canonical(actual.getTypeName());
        if (entityName.equals(databaseName)) {
            Matcher size = SIZE.matcher(entityType);
            if (!SIZED_TYPES.contains(entityName) || !size.find() || actual.getColumnSize() == null) {
                return true;
            }
            boolean sameLength = Integer.parseInt(size.group(1)) == actual.getColumnSize();
            boolean sameScale = !entityName.equals("numeric") || scale(size) == orZero(actual.getDecimalDigits());
            return sameLength && sameScale;
        }
        // Hibernate's validator compares names up to the first parenthesis: timestamp(6) with time zone fits timestamp
        return canonical(entityType.replaceFirst("\\(.*", "")).equals(databaseName)
                || dialect.equivalentTypes(code, actual.getDataTypeId())
                || dialect.equivalentTypes(code, databaseCode(actual));
    }

    /** Returns the family of a type code: text, integer, decimal, float. */
    private static String family(int code) {
        if (SqlTypes.isCharacterOrClobType(code) || SqlTypes.isEnumType(code)) {
            return "text";
        }
        if (SqlTypes.isIntegral(code)) {
            return "integer";
        }
        if (SqlTypes.isNumericOrDecimal(code)) {
            return "decimal";
        }
        return SqlTypes.isFloatOrRealOrDouble(code) ? "float" : "type " + code;
    }

    private int databaseCode(DataType actual) {
        try {
            return dialect.resolveSqlTypeDescriptor(actual.getTypeName(), actual.getDataTypeId(),
                    orZero(actual.getColumnSize()), orZero(actual.getDecimalDigits()),
                    metadata.getDatabase().getTypeConfiguration().getJdbcTypeRegistry()).getDefaultSqlTypeCode();
        } catch (RuntimeException e) {
            return actual.getDataTypeId();
        }
    }

    private int typeCode(Column mapped) {
        try {
            return (Integer) typeCode.invoke(mapped, metadata);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot read the type of column " + mapped.getName(), e);
        }
    }

    /** Returns the canonical name of a type: varchar, integer, etc. */
    static String canonical(String type) {
        String name = type == null ? "" : type.toLowerCase(Locale.ROOT).replace("\"", "")
                .replaceAll("\\(.*?\\)", "").replaceAll("\\s+", " ").trim();
        return switch (name) {
            case "int8" -> "bigint";
            case "int4", "int" -> "integer";
            case "int2" -> "smallint";
            case "bool" -> "boolean";
            case "character varying" -> "varchar";
            case "bpchar", "character" -> "char";
            case "timestamptz" -> "timestamp with time zone";
            case "timestamp without time zone" -> "timestamp";
            case "timetz" -> "time with time zone";
            case "time without time zone" -> "time";
            case "float8", "double", "float" -> "double precision";
            case "float4" -> "real";
            case "decimal" -> "numeric";
            case "character large object" -> "clob";
            case "binary large object" -> "blob";
            case "binary varying" -> "varbinary";
            default -> name;
        };
    }

    private static int scale(Matcher size) {
        return size.group(2) == null ? 0 : Integer.parseInt(size.group(2));
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }
}
