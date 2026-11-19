package io.github.vavaste.sqldelta.engine;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import liquibase.CatalogAndSchema;
import liquibase.change.AddColumnConfig;
import liquibase.change.Change;
import liquibase.change.ChangeWithColumns;
import liquibase.change.ColumnConfig;
import liquibase.change.core.AddColumnChange;
import liquibase.change.core.AddForeignKeyConstraintChange;
import liquibase.change.core.AddPrimaryKeyChange;
import liquibase.change.core.AddUniqueConstraintChange;
import liquibase.change.core.AlterSequenceChange;
import liquibase.change.core.CreateIndexChange;
import liquibase.change.core.CreateTableChange;
import liquibase.change.core.DropColumnChange;
import liquibase.change.core.DropForeignKeyConstraintChange;
import liquibase.change.core.DropIndexChange;
import liquibase.change.core.DropNotNullConstraintChange;
import liquibase.change.core.DropPrimaryKeyChange;
import liquibase.change.core.DropTableChange;
import liquibase.change.core.DropUniqueConstraintChange;
import liquibase.change.core.ModifyDataTypeChange;
import liquibase.change.core.SetColumnRemarksChange;
import liquibase.change.core.SetTableRemarksChange;
import liquibase.database.Database;
import liquibase.database.core.H2Database;
import liquibase.database.jvm.JdbcConnection;
import liquibase.diff.DiffGeneratorFactory;
import liquibase.diff.DiffResult;
import liquibase.diff.Difference;
import liquibase.diff.ObjectDifferences;
import liquibase.diff.compare.CompareControl;
import liquibase.diff.output.DiffOutputControl;
import liquibase.diff.output.changelog.DiffToChangeLog;
import liquibase.snapshot.DatabaseSnapshot;
import liquibase.snapshot.SnapshotControl;
import liquibase.snapshot.SnapshotGeneratorFactory;
import liquibase.statement.DatabaseFunction;
import liquibase.structure.DatabaseObject;
import liquibase.structure.core.Column;
import liquibase.structure.core.ForeignKey;
import liquibase.structure.core.ForeignKeyConstraintType;
import liquibase.structure.core.Index;
import liquibase.structure.core.PrimaryKey;
import liquibase.structure.core.Table;
import liquibase.structure.core.UniqueConstraint;
import liquibase.util.SqlUtil;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.model.relational.Namespace;
import org.hibernate.boot.model.relational.Sequence;
import org.hibernate.boot.spi.MetadataImplementor;
import org.hibernate.mapping.UniqueKey;

/**
 * Compares the entities with the live database and drafts the statements and notes that align it.
 */
final class Diff {

    private static final List<String> BOOKKEEPING_TABLES =
            List.of("flyway_schema_history", "databasechangelog", "databasechangeloglock");
    // ignore views cause hibernat does not map them and would be dropped
    private static final String COMPARED_TYPES =
            "tables,columns,primaryKeys,foreignKeys,uniqueConstraints,indexes,sequences";
    private static final Map<OnDeleteAction, ForeignKeyConstraintType> DELETE_RULES = Map.of(
            OnDeleteAction.CASCADE, ForeignKeyConstraintType.importedKeyCascade,
            OnDeleteAction.SET_NULL, ForeignKeyConstraintType.importedKeySetNull,
            OnDeleteAction.SET_DEFAULT, ForeignKeyConstraintType.importedKeySetDefault,
            OnDeleteAction.RESTRICT, ForeignKeyConstraintType.importedKeyRestrict);
    // the default of a columnDefinition such as "varchar(20) default 'new' not null unique", without what follows it
    private static final Pattern DEFAULT_IN_DEFINITION =
            Pattern.compile("(?is).*?\\sdefault\\s+(.*?)(?:\\s+(?:not\\s+null|unique|check|references)\\b.*)?\\s*");
    private static final Pattern NOT_NULL = Pattern.compile("(?i)\\s+not\\s+null\\b");

    final List<Statement> statements = new ArrayList<>();
    final List<Note> notes = new ArrayList<>();

    private final Database reference;
    private final Database target;
    private final Metadata metadata;
    private final String schema;
    private final List<Pattern> excludes = new ArrayList<>();
    private final Set<String> otherSchemaNames = new HashSet<>();
    private final Map<String, org.hibernate.mapping.Column> mappedColumns = new HashMap<>();
    private final Set<String> columnsWithDefinition = new HashSet<>();
    private final Map<String, ForeignKeyConstraintType> mappedDeleteRules = new HashMap<>();
    private final Set<String> explicitUniqueNames = new HashSet<>();
    private final Map<String, Column> targetColumns = new HashMap<>();
    private final List<Change> changes = new ArrayList<>();
    private DatabaseSnapshot referenceSnapshot;
    private DatabaseSnapshot targetSnapshot;
    private DiffResult result;

    Diff(Database reference, Database target, Metadata metadata, String schema, List<String> excludes) {
        this.reference = reference;
        this.target = target;
        this.metadata = metadata;
        this.schema = schema;
        BOOKKEEPING_TABLES.forEach(
                table -> this.excludes.add(Pattern.compile(Pattern.quote(table), Pattern.CASE_INSENSITIVE)));
        excludes.forEach(pattern -> this.excludes.add(Pattern.compile(pattern, Pattern.CASE_INSENSITIVE)));
    }

    void run() throws Exception {
        // Hibernate's own create orders the columns, the metadata keeps mapping order otherwise composite keys would differ
        if (metadata instanceof MetadataImplementor implementor) {
            implementor.orderColumns(true);
        }
        readMapping();
        Connection connection = target.getConnection().getUnderlyingConnection();
        excludeViews(connection);
        List<Change> generated = compareSnapshots();
        Set<String> droppedTables = new HashSet<>();
        generated.stream().filter(DropTableChange.class::isInstance).forEach(drop -> droppedTables.add(tableOf(drop)));
        generated.forEach(diff -> correctAndKeep(diff, droppedTables));
        recreateForeignKeysWithAnotherDeleteRule();
        recreatePlainIndexesBehindDroppedConstraints();
        dropNotNullOfDroppedColumns();
        writeStatements();
        TypeChanges types = new TypeChanges(metadata, target);
        for (Column column : sorted(targetSnapshot.get(Column.class), Diff::key)) {
            String table = column.getRelation().getName();
            org.hibernate.mapping.Column mapped = mappedColumns.get(key(column));
            if (mapped == null || excluded(table)) {
                continue;
            }
            // Liquibase himself never turns an existing column into an identity column
            if (mapped.isIdentity() && !column.isAutoIncrement()) {
                notes.add(new Note(true, table, "Column " + column.getName() + " is an identity column in the"
                        + " entities but not in the database, so inserts from the entities fail; make it one by hand,"
                        + " for example ALTER TABLE " + table + " ALTER COLUMN " + column.getName()
                        + (target instanceof H2Database ? " SET GENERATED BY DEFAULT RESTART WITH <highest id + 1>"
                                : " ADD GENERATED BY DEFAULT AS IDENTITY (START WITH <highest id + 1>)")));
            }
            if (!columnsWithDefinition.contains(key(column))) {
                types.compare(table, column, mapped, statements, notes);
            }
        }
        CheckConstraints.Result checks = CheckConstraints.compare(metadata, connection, schema, this::excluded);
        statements.addAll(checks.statements());
        notes.addAll(checks.notes());
        noteUnmappedIndexes();
        noteSuspectedRenames(types);
        statements.sort(Comparator.comparing(Statement::phase));
        // Still don't know why but liquibase-hibernate generates every default change twice
        Set<String> seen = new HashSet<>();
        statements.removeIf(statement -> !seen.add(statement.sql()));
        refuseToDropEverything();
    }

    private List<Change> compareSnapshots() throws Exception {
        CatalogAndSchema targetSchema = new CatalogAndSchema(target.getDefaultCatalogName(), schema);
        referenceSnapshot = SnapshotGeneratorFactory.getInstance().createSnapshot(reference.getDefaultSchema(),
                reference, new SnapshotControl(reference, COMPARED_TYPES));
        targetSnapshot = SnapshotGeneratorFactory.getInstance().createSnapshot(targetSchema, target,
                new SnapshotControl(target, COMPARED_TYPES));
        targetSnapshot.get(Column.class).forEach(column -> targetColumns.put(key(column), column));
        correctReference();
        CompareControl control = new CompareControl(new CompareControl.SchemaComparison[] {
                new CompareControl.SchemaComparison(reference.getDefaultSchema(), targetSchema)}, COMPARED_TYPES);
        result = DiffGeneratorFactory.getInstance().compare(referenceSnapshot, targetSnapshot, control);
        DiffToChangeLog changeLog = new DiffToChangeLog(result, new DiffOutputControl(false, false, false,
                control.getSchemaComparisons()));
        changeLog.setChangeSetAuthor("sql-delta");
        List<Change> generated = new ArrayList<>();
        changeLog.generateChangeSets().forEach(changeSet -> generated.addAll(changeSet.getChanges()));
        return generated;
    }

    /** What the reference snapshot loses of Hibernate's mapping, the names of other schemas, and the notes on them. */
    private void readMapping() {
        List<String> comparedTables = new ArrayList<>();
        for (Namespace namespace : metadata.getDatabase().getNamespaces()) {
            // getSchema() is deprecated in 7.x, but schema() does not exist from 6.6
            if (namespace.getName().getSchema() != null && isOtherSchema(namespace.getName().getSchema().getText())) {
                namespace.getTables().forEach(table -> otherSchemaNames.add(lower(table.getName())));
                for (Sequence sequence : namespace.getSequences()) {
                    otherSchemaNames.add(lower(sequence.getName().getSequenceName().getText()));
                }
                continue;
            }
            for (org.hibernate.mapping.Table table : namespace.getTables()) {
                comparedTables.add(table.getName());
                for (org.hibernate.mapping.Column column : table.getColumns()) {
                    mappedColumns.put(key(table.getName(), column.getName()), column);
                    if (column.getSqlType() != null) {
                        columnsWithDefinition.add(key(table.getName(), column.getName()));
                    }
                }
                // deprecated in 7.x, but getForeignKeyCollection() does not exist from 6.6
                for (org.hibernate.mapping.ForeignKey foreignKey : table.getForeignKeys().values()) {
                    if (foreignKey.isCreationEnabled() && foreignKey.getReferencedTable() != null) {
                        OnDeleteAction action = foreignKey.getOnDeleteAction();
                        mappedDeleteRules.put(foreignKeyKey(table.getName(), names(foreignKey.getColumns(),
                                org.hibernate.mapping.Column::getName), foreignKey.getReferencedTable().getName()),
                                action == null ? null : DELETE_RULES.get(action));
                    }
                }
                for (UniqueKey uniqueKey : table.getUniqueKeys().values()) {
                    if (uniqueKey.isNameExplicit()) {
                        explicitUniqueNames.add(lower(uniqueKey.getName()));
                    }
                }
            }
        }
        Map<String, Integer> entitiesBySchema = new TreeMap<>();
        metadata.getEntityBindings().stream().map(entity -> entity.getTable().getSchema()).filter(this::isOtherSchema)
                .forEach(other -> entitiesBySchema.merge(other, 1, Integer::sum));
        entitiesBySchema.forEach((other, count) -> notes.add(new Note(false, null, count + (count == 1 ? " entity is"
                : " entities are") + " mapped to schema " + other + " and not compared.")));
        for (String table : comparedTables) {
            if (otherSchemaNames.contains(lower(table))) {
                notes.add(new Note(true, table, "Table " + table + " is mapped to this schema and to another one;"
                        + " tables are matched by name, so it is not compared."));
            }
        }
    }

    private void excludeViews(Connection connection) throws SQLException {
        try (ResultSet views = connection.getMetaData().getTables(null, schema, "%",
                new String[] {"VIEW", "MATERIALIZED VIEW"})) {
            while (views.next()) {
                if (schema.equals(views.getString("TABLE_SCHEM"))) {
                    excludes.add(Pattern.compile(Pattern.quote(views.getString("TABLE_NAME")),
                            Pattern.CASE_INSENSITIVE));
                }
            }
        }
    }

    private void correctReference() {
        for (Column column : referenceSnapshot.get(Column.class)) {
            org.hibernate.mapping.Column mapped = mappedColumns.get(key(column));
            Column existing = targetColumns.get(key(column));
            if (columnsWithDefinition.contains(key(column)) && existing != null) {
                // the extension reads "varchar(20) default 'new' not null" as the default: it would never converge
                column.setType(existing.getType());
                column.setDefaultValue(existing.getDefaultValue());
                column.setNullable(existing.isNullable());
            } else if (columnsWithDefinition.contains(key(column))) {
                // a new column: the extension would create it nullable with the default 'new' not null, and leaves a
                // not null written before the default in the type name
                Matcher defaultValue = DEFAULT_IN_DEFINITION.matcher(mapped.getSqlType());
                if (defaultValue.matches()) {
                    column.setDefaultValue(SqlUtil.parseValue(reference, defaultValue.group(1), column.getType()));
                }
                if (NOT_NULL.matcher(mapped.getSqlType()).find()) {
                    column.setNullable(false);
                    column.getType().setTypeName(NOT_NULL.matcher(column.getType().getTypeName()).replaceAll(""));
                }
            } else if (existing != null && column.getDefaultValue() instanceof DatabaseFunction
                    && existing.getDefaultValue() instanceof DatabaseFunction) {
                column.setDefaultValue(existing.getDefaultValue());
            } else if (mapped != null && mapped.getDefaultValue() == null
                    && column.getDefaultValue() instanceof DatabaseFunction function
                    && lower(function.getValue()).startsWith("nextval(")) {
                column.setDefaultValue(null);
            }
        }
        // the extension never reads @OnDelete: new foreign keys would lose their ON DELETE clause
        for (ForeignKey foreignKey : referenceSnapshot.get(ForeignKey.class)) {
            if (mappedDeleteRules.containsKey(foreignKeyKey(foreignKey))) {
                foreignKey.setDeleteRule(mappedDeleteRules.get(foreignKeyKey(foreignKey)));
            }
        }
    }

    private void correctAndKeep(Change change, Set<String> droppedTables) {
        boolean excludedObject = excluded(Statements.table(change))
                || excluded(Statements.parameter(change, "sequenceName"))
                || excluded(Statements.parameter(change, "referencedTableName"));
        boolean toDroppedTable = change instanceof DropForeignKeyConstraintChange drop
                && droppedTables.contains(referencedTable(drop));
        boolean goesWithDroppedTable = droppedTables.contains(tableOf(change)) && !toDroppedTable
                && (change instanceof DropForeignKeyConstraintChange || change instanceof DropIndexChange
                || change instanceof DropUniqueConstraintChange || change instanceof DropPrimaryKeyChange);
        // exclude comments
        boolean notCompared = change instanceof SetTableRemarksChange || change instanceof SetColumnRemarksChange
                || (change instanceof AlterSequenceChange && target instanceof H2Database);
        if (excludedObject || goesWithDroppedTable || notCompared || change instanceof ModifyDataTypeChange
                || isNoise(change)) {
            return;
        }
        // the database names primary keys and unique constraints, as with Hibernate's DDL;
        if (change instanceof AddPrimaryKeyChange add) {
            add.setConstraintName(null);
        } else if (change instanceof AddUniqueConstraintChange unique
                && !explicitUniqueNames.contains(lower(unique.getConstraintName()))) {
            unique.setConstraintName(null);
        } else if (change instanceof ChangeWithColumns<?> withColumns) {
            withColumns.getColumns().stream().map(ColumnConfig::getConstraints).filter(Objects::nonNull)
                    .forEach(constraints -> constraints.setPrimaryKeyName(null));
        }
        changes.add(change);
    }

    /**
     * The extension invents backing indexes for primary keys and unique constraints
     */
    private boolean isNoise(Change change) {
        if (change instanceof CreateIndexChange index) {
            Stream<Index> backingIndexes = Stream.concat(
                    referenceSnapshot.get(PrimaryKey.class).stream().map(PrimaryKey::getBackingIndex),
                    referenceSnapshot.get(UniqueConstraint.class).stream().map(UniqueConstraint::getBackingIndex));
            return backingIndexes.anyMatch(backing -> backing != null
                    && lower(backing.getName()).equals(lower(index.getIndexName())));
        }
        if (change instanceof AddPrimaryKeyChange || change instanceof DropPrimaryKeyChange) {
            for (Map.Entry<PrimaryKey, ObjectDifferences> changed
                    : result.getChangedObjects(PrimaryKey.class).entrySet()) {
                Difference columns = changed.getValue().getDifference("columns");
                if (columns != null && tableOf(change).equals(lower(changed.getKey().getTable().getName()))
                        && columnSet(columns.getReferenceValue()).equals(columnSet(columns.getComparedValue()))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The extension ignores ON DELETE differences: such a foreign key is dropped and added again under its name. */
    private void recreateForeignKeysWithAnotherDeleteRule() {
        Set<String> added = new HashSet<>();
        for (AddForeignKeyConstraintChange add : changesOf(AddForeignKeyConstraintChange.class)) {
            added.add(foreignKeyKey(add.getBaseTableName(), List.of(add.getBaseColumnNames().split("\\s*,\\s*")),
                    add.getReferencedTableName()));
        }
        for (ForeignKey foreignKey : sorted(targetSnapshot.get(ForeignKey.class), ForeignKey::getName)) {
            String key = foreignKeyKey(foreignKey);
            if (excluded(foreignKey.getForeignKeyTable().getName()) || added.contains(key)
                    || !mappedDeleteRules.containsKey(key)
                    || sameEffect(mappedDeleteRules.get(key), foreignKey.getDeleteRule())) {
                continue;
            }
            DropForeignKeyConstraintChange drop = new DropForeignKeyConstraintChange();
            drop.setBaseTableName(foreignKey.getForeignKeyTable().getName());
            drop.setConstraintName(foreignKey.getName());
            AddForeignKeyConstraintChange add = new AddForeignKeyConstraintChange();
            add.setBaseTableName(foreignKey.getForeignKeyTable().getName());
            add.setConstraintName(foreignKey.getName());
            add.setBaseColumnNames(String.join(",", names(foreignKey.getForeignKeyColumns(), Column::getName)));
            add.setReferencedTableName(foreignKey.getPrimaryKeyTable().getName());
            add.setReferencedColumnNames(String.join(",", names(foreignKey.getPrimaryKeyColumns(), Column::getName)));
            add.setOnDelete(mappedDeleteRules.get(key));
            changes.addAll(List.of(drop, add));
            notes.add(new Note(false, drop.getBaseTableName(), "Foreign key " + foreignKey.getName() + " is dropped"
                    + " and added again for its delete rule: ON DELETE " + onDelete(foreignKey.getDeleteRule())
                    + " in the database, " + onDelete(mappedDeleteRules.get(key)) + " in the entities."));
        }
    }

    /**
     * Liquibase matches a plain index of the entities with the index behind a unique constraint of the same name, so
     * it drops the constraint, which takes the index with it, and creates nothing.
     */
    private void recreatePlainIndexesBehindDroppedConstraints() {
        Set<String> dropped = new HashSet<>();
        changesOf(DropUniqueConstraintChange.class).forEach(drop -> dropped.add(lower(drop.getConstraintName())));
        changesOf(CreateIndexChange.class).forEach(create -> dropped.remove(lower(create.getIndexName())));
        for (Index index : sorted(referenceSnapshot.get(Index.class), Index::getName)) {
            if (dropped.contains(lower(index.getName())) && !Boolean.TRUE.equals(index.isUnique())) {
                CreateIndexChange create = new CreateIndexChange();
                create.setTableName(index.getRelation().getName());
                create.setIndexName(index.getName());
                index.getColumns().forEach(column -> create.addColumn(new AddColumnConfig(column)));
                changes.add(create);
            }
        }
    }

    private void dropNotNullOfDroppedColumns() {
        for (Column column : droppedColumns()) {
            if (Boolean.FALSE.equals(column.isNullable()) && column.getDefaultValue() == null
                    && !column.isAutoIncrement()) {
                DropNotNullConstraintChange dropNotNull = new DropNotNullConstraintChange();
                dropNotNull.setTableName(column.getRelation().getName());
                dropNotNull.setColumnName(column.getName());
                dropNotNull.setColumnDataType(column.getType().toString());
                changes.add(dropNotNull);
            }
        }
    }

    private void writeStatements() {
        Set<String> createdTables = tablesOf(CreateTableChange.class);
        boolean schemaHasTables = !targetTables().isEmpty();
        for (Change change : changes) {
            statements.addAll(Statements.of(change, target, createdTables, schemaHasTables));
        }
    }

    private void noteUnmappedIndexes() {
        Set<String> constraints = new HashSet<>();
        targetSnapshot.get(PrimaryKey.class).forEach(primaryKey -> constraints.add(lower(primaryKey.getName())));
        targetSnapshot.get(UniqueConstraint.class).forEach(unique -> constraints.add(lower(unique.getName())));
        targetSnapshot.get(ForeignKey.class).forEach(foreignKey -> constraints.add(lower(foreignKey.getName())));
        List<String> droppedColumns = names(droppedColumns(), Diff::key);
        for (Index index : sorted(result.getUnexpectedObjects(Index.class), Index::getName)) {
            String table = index.getRelation().getName();
            String name = lower(index.getName());
            boolean goesAway = excluded(table) || tablesOf(DropTableChange.class).contains(lower(table))
                    || index.getColumns().stream()
                            .anyMatch(column -> droppedColumns.contains(key(table, column.getName())));
            boolean behindConstraint = !index.getAssociatedWith().isEmpty() || constraints.contains(name)
                    || constraints.stream().anyMatch(constraint -> name.startsWith(constraint + "_index_"));
            if (!goesAway && !behindConstraint) {
                notes.add(new Note(Boolean.TRUE.equals(index.isUnique()), table, "Index " + index.getName() + " ("
                        + String.join(", ", names(index.getColumns(), Column::getName)) + ") exists only in the"
                        + " database and is not dropped; to drop it: DROP INDEX " + index.getName()));
            }
        }
    }

    /** A renamed column comes out as a dropped and an added one: suggested when the pair has the same type. */
    private void noteSuspectedRenames(TypeChanges types) {
        List<Column> dropped = droppedColumns();
        List<String> added = new ArrayList<>();
        for (AddColumnChange add : changesOf(AddColumnChange.class)) {
            add.getColumns().forEach(column -> added.add(key(add.getTableName(), column.getName())));
        }
        for (Column column : dropped) {
            String table = column.getRelation().getName();
            List<String> sameType = added.stream().filter(key -> key.startsWith(lower(table) + "|")
                    && types.matches(mappedColumns.get(key), column.getType())).toList();
            if (sameType.size() != 1) {
                continue;
            }
            org.hibernate.mapping.Column newColumn = mappedColumns.get(sameType.get(0));
            long droppedOfThatType = dropped.stream().filter(other -> other.getRelation() == column.getRelation()
                    && types.matches(newColumn, other.getType())).count();
            if (droppedOfThatType == 1) {
                String newName = newColumn.getName();
                notes.add(new Note(true, table, "Column " + column.getName() + " is dropped and " + newName + " added"
                        + " with the same type; if this is a rename, replace both with ALTER TABLE " + table
                        + " RENAME COLUMN " + column.getName() + " TO " + newName + " to keep the values."));
            }
        }
    }

    private void refuseToDropEverything() {
        List<String> tables = targetTables();
        if (!tables.isEmpty() && tablesOf(CreateTableChange.class).isEmpty()
                && tablesOf(DropTableChange.class).containsAll(names(tables, Diff::lower))) {
            throw new Engine.ConfigurationError("Every table of schema " + schema
                    + " would be dropped and none created; check packages and the naming strategies.");
        }
    }

    private boolean excluded(String name) {
        return name != null && (otherSchemaNames.contains(lower(name))
                || excludes.stream().anyMatch(pattern -> pattern.matcher(name).matches()));
    }

    private boolean isOtherSchema(String name) {
        return name != null && !name.replace("\"", "").replace("`", "").equalsIgnoreCase(schema);
    }

    private List<String> targetTables() {
        return targetSnapshot.get(Table.class).stream().map(Table::getName).filter(name -> !excluded(name)).toList();
    }

    private List<Column> droppedColumns() {
        return changesOf(DropColumnChange.class).stream().map(drop -> key(drop.getTableName(), drop.getColumnName()))
                .filter(targetColumns::containsKey).map(targetColumns::get).toList();
    }

    private Set<String> tablesOf(Class<? extends Change> type) {
        return changesOf(type).stream().map(Diff::tableOf).collect(Collectors.toSet());
    }

    private <T extends Change> List<T> changesOf(Class<T> type) {
        return changes.stream().filter(type::isInstance).map(type::cast).toList();
    }

    /** The lower-case name of the table that the dropped foreign key references, or "" when it is not found. */
    private String referencedTable(DropForeignKeyConstraintChange drop) {
        return targetSnapshot.get(ForeignKey.class).stream()
                .filter(foreignKey -> foreignKey.getName().equalsIgnoreCase(drop.getConstraintName())
                        && foreignKey.getForeignKeyTable().getName().equalsIgnoreCase(drop.getBaseTableName()))
                .map(foreignKey -> lower(foreignKey.getPrimaryKeyTable().getName())).findFirst().orElse("");
    }

    private static boolean sameEffect(ForeignKeyConstraintType mapped, ForeignKeyConstraintType actual) {
        List<ForeignKeyConstraintType> refusing = Arrays.asList(null, ForeignKeyConstraintType.importedKeyNoAction,
                ForeignKeyConstraintType.importedKeyRestrict);
        return mapped == actual || (refusing.contains(mapped) && refusing.contains(actual));
    }

    /** The ON DELETE clause of a delete rule, NO ACTION when there is none. */
    private static String onDelete(ForeignKeyConstraintType rule) {
        return DELETE_RULES.entrySet().stream().filter(entry -> entry.getValue() == rule)
                .map(entry -> entry.getKey().name().replace('_', ' ')).findFirst().orElse("NO ACTION");
    }

    /** The value of a primary key's columns difference: a list of columns, or their names joined by commas. */
    private static Set<String> columnSet(Object columns) {
        Set<String> names = new HashSet<>();
        if (columns instanceof Collection<?> list) {
            list.forEach(column -> names.add(
                    lower(column instanceof Column named ? named.getName() : String.valueOf(column))));
        } else if (columns != null) {
            Arrays.stream(String.valueOf(columns).split(",")).forEach(name -> names.add(lower(name.trim())));
        }
        return names;
    }

    /** Foreign keys match by table, columns and referenced table: migrations often name them differently. */
    private static String foreignKeyKey(ForeignKey foreignKey) {
        return foreignKeyKey(foreignKey.getForeignKeyTable().getName(),
                names(foreignKey.getForeignKeyColumns(), Column::getName), foreignKey.getPrimaryKeyTable().getName());
    }

    private static String foreignKeyKey(String table, List<String> columns, String referencedTable) {
        return lower(table) + "|" + lower(String.join(",", columns)) + "->" + lower(referencedTable);
    }

    private static String tableOf(Change change) {
        return lower(Statements.table(change));
    }

    private static String key(Column column) {
        return key(column.getRelation().getName(), column.getName());
    }

    private static String key(String table, String column) {
        return lower(table) + "|" + lower(column);
    }

    private static <T> List<String> names(Collection<T> objects, Function<T, String> name) {
        return objects.stream().map(name).toList();
    }

    /** Snapshots are sets: sorting keeps the draft the same from one run to the next. */
    private static <T extends DatabaseObject> List<T> sorted(Collection<T> objects, Function<T, String> name) {
        return objects.stream().sorted(Comparator.comparing(object -> lower(name.apply(object)))).toList();
    }

    private static String lower(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }
}
