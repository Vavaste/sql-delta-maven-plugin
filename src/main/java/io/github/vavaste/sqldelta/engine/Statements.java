package io.github.vavaste.sqldelta.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import liquibase.Scope;
import liquibase.change.AddColumnConfig;
import liquibase.change.Change;
import liquibase.change.ChangeFactory;
import liquibase.change.ChangeParameterMetaData;
import liquibase.change.ConstraintsConfig;
import liquibase.change.core.AddColumnChange;
import liquibase.change.core.AddForeignKeyConstraintChange;
import liquibase.change.core.AddNotNullConstraintChange;
import liquibase.change.core.AddPrimaryKeyChange;
import liquibase.change.core.AddUniqueConstraintChange;
import liquibase.change.core.AlterSequenceChange;
import liquibase.change.core.CreateIndexChange;
import liquibase.change.core.CreateSequenceChange;
import liquibase.change.core.CreateTableChange;
import liquibase.change.core.DropColumnChange;
import liquibase.change.core.DropForeignKeyConstraintChange;
import liquibase.change.core.DropIndexChange;
import liquibase.change.core.DropPrimaryKeyChange;
import liquibase.change.core.DropSequenceChange;
import liquibase.change.core.DropTableChange;
import liquibase.change.core.DropUniqueConstraintChange;
import liquibase.database.Database;
import liquibase.sql.Sql;
import liquibase.sqlgenerator.SqlGeneratorFactory;

import io.github.vavaste.sqldelta.engine.Statement.Classification;
import io.github.vavaste.sqldelta.engine.Statement.Phase;

/**
 * Turns one Liquibase change into Statements with a one-line reason for destructive changes, and a classification: SAFE, REVIEW or DATA_LOSS.
 */
final class Statements {

    private static final Map<Class<? extends Change>, Phase> PHASES = Map.ofEntries(
            Map.entry(DropForeignKeyConstraintChange.class, Phase.DROP_FOREIGN_KEYS),
            Map.entry(DropUniqueConstraintChange.class, Phase.DROP_CONSTRAINTS_AND_INDEXES),
            Map.entry(DropIndexChange.class, Phase.DROP_CONSTRAINTS_AND_INDEXES),
            Map.entry(DropPrimaryKeyChange.class, Phase.DROP_CONSTRAINTS_AND_INDEXES),
            Map.entry(CreateSequenceChange.class, Phase.SEQUENCES),
            Map.entry(AlterSequenceChange.class, Phase.SEQUENCES),
            Map.entry(CreateTableChange.class, Phase.CREATE_TABLES),
            Map.entry(DropColumnChange.class, Phase.DROP_COLUMNS),
            Map.entry(DropTableChange.class, Phase.DROP_TABLES),
            Map.entry(DropSequenceChange.class, Phase.DROP_SEQUENCES),
            Map.entry(AddPrimaryKeyChange.class, Phase.ADD_CONSTRAINTS_AND_INDEXES),
            Map.entry(AddUniqueConstraintChange.class, Phase.ADD_CONSTRAINTS_AND_INDEXES),
            Map.entry(CreateIndexChange.class, Phase.ADD_CONSTRAINTS_AND_INDEXES),
            Map.entry(AddForeignKeyConstraintChange.class, Phase.ADD_FOREIGN_KEYS));

    private Statements() {
    }

    /** createdTables holds the lower-case names of the tables the draft creates, which are empty when it runs. */
    static List<Statement> of(Change change, Database target, Set<String> createdTables, boolean schemaHasTables) {
        String reason = reason(change, createdTables, schemaHasTables);
        boolean dataLoss = change instanceof DropTableChange || change instanceof DropColumnChange
                || change instanceof DropSequenceChange;
        Classification classification = dataLoss ? Classification.DATA_LOSS
                : reason == null ? Classification.SAFE : Classification.REVIEW;
        Phase phase = PHASES.getOrDefault(change.getClass(), Phase.ALTER_COLUMNS);
        List<Statement> statements = new ArrayList<>();
        for (String sql : sql(change, target)) {
            statements.add(new Statement(phase, classification, sql, reason));
        }
        return statements;
    }

    static List<String> sql(Change change, Database target) {
        List<String> statements = new ArrayList<>();
        for (Sql sql : SqlGeneratorFactory.getInstance().generateSql(change.generateStatements(target), target)) {
            String text = sql.toSql().replaceAll("[;\\s]+$", "").strip();
            if (change instanceof CreateTableChange create) {
                String invented = target.escapeConstraintName(target.generatePrimaryKeyName(create.getTableName()));
                text = text.replace(" CONSTRAINT " + invented + " PRIMARY KEY", " PRIMARY KEY"); //grr
            }
            statements.add(text);
        }
        return statements;
    }

    private static String reason(Change change, Set<String> createdTables, boolean schemaHasTables) {
        String table = table(change);
        if (change instanceof DropTableChange) {
            return "drops table " + table + " and its rows";
        }
        if (change instanceof DropColumnChange drop) {
            return "drops column " + drop.getColumnName() + " of " + table + " and its values";
        }
        if (change instanceof DropSequenceChange drop) {
            return "drops sequence " + drop.getSequenceName() + " and its current value";
        }
        if (change instanceof AddNotNullConstraintChange notNull) {
            return "fails if " + notNull.getColumnName() + " is NULL in existing rows";
        }
        if (change instanceof AddColumnChange add) {
            for (AddColumnConfig column : add.getColumns()) {
                ConstraintsConfig constraints = column.getConstraints();
                boolean notNull = constraints != null && Boolean.FALSE.equals(constraints.isNullable());
                if (notNull && column.getDefaultValueObject() == null
                        && !Boolean.TRUE.equals(column.isAutoIncrement())) {
                    return "adds NOT NULL column " + column.getName()
                            + " without a default: fails if the table has rows";
                }
            }
            return null;
        }
        if (change instanceof CreateSequenceChange && schemaHasTables) {
            return "the new sequence starts at its first value; if it feeds an existing table, move it past the"
                    + " highest id with setval";
        }
        if (table == null || createdTables.contains(table.toLowerCase(Locale.ROOT))) {
            return null;
        }
        if (change instanceof AddUniqueConstraintChange unique) {
            return "fails if existing rows hold duplicates of (" + unique.getColumnNames() + ")";
        }
        if (change instanceof AddPrimaryKeyChange primaryKey) {
            return "fails if existing rows hold duplicates or NULL in (" + primaryKey.getColumnNames() + ")";
        }
        if (change instanceof AddForeignKeyConstraintChange foreignKey) {
            return "fails if existing rows reference missing rows of " + foreignKey.getReferencedTableName();
        }
        if (change instanceof CreateIndexChange) {
            return "blocks writes to " + table + " while the index builds";
        }
        return null;
    }

    static String table(Change change) {
        String table = parameter(change, "tableName");
        return table != null ? table : parameter(change, "baseTableName");
    }

    static String parameter(Change change, String name) {
        ChangeParameterMetaData parameter = Scope.getCurrentScope().getSingleton(ChangeFactory.class)
                .getChangeMetaData(change).getParameters().get(name);
        return parameter != null && parameter.getCurrentValue(change) instanceof String value ? value : null;
    }
}
