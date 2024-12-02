package liquibase.change.core;

import liquibase.Scope;
import liquibase.change.*;
import liquibase.database.Database;
import liquibase.database.core.InformixDatabase;
import liquibase.exception.ValidationErrors;
import liquibase.snapshot.SnapshotGeneratorFactory;
import liquibase.statement.InsertExecutablePreparedStatement;
import liquibase.statement.SequenceCurrentValueFunction;
import liquibase.statement.SequenceNextValueFunction;
import liquibase.statement.SqlStatement;
import liquibase.statement.core.InsertStatement;
import liquibase.structure.core.Column;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * Inserts data into an existing table.
 */
@DatabaseChange(name="insert", description = "Inserts data into an existing table", priority = ChangeMetaData.PRIORITY_DEFAULT, appliesTo = "table")
public class InsertDataChange extends AbstractChange implements ChangeWithColumns<ColumnConfig>, DbmsTargetedChange {

    @Setter
    private String catalogName;
    @Setter
    private String schemaName;
    @Setter
    private String tableName;
    private List<ColumnConfig> columns;
    private String dbms;
    @Setter
    private String prologue;
    @Setter
    private String epilogue;

    public InsertDataChange() {
        columns = new ArrayList<>();
    }

    @Override
    public ValidationErrors validate(Database database) {
        ValidationErrors validate = super.validate(database);
        validate.checkRequiredField("columns", columns);
        return validate;
    }

    @DatabaseChangeProperty(mustEqualExisting ="table.catalog", since = "3.0", description = "Name of the database catalog")
    public String getCatalogName() {
        return catalogName;
    }

    @DatabaseChangeProperty(mustEqualExisting ="table.schema", description = "Name of the database schema")
    public String getSchemaName() {
        return schemaName;
    }

    @DatabaseChangeProperty(mustEqualExisting = "table", description = "Name of the table to insert data into")
    public String getTableName() {
        return tableName;
    }

    @Override
    @DatabaseChangeProperty(mustEqualExisting = "table.column", description = "Data to insert into columns",
        requiredForDatabase = "all")
    public List<ColumnConfig> getColumns() {
        return columns;
    }

    @Override
    public void setColumns(List<ColumnConfig> columns) {
        this.columns = columns;
    }

    @Override
    public void addColumn(ColumnConfig column) {
        columns.add(column);
    }

    public void removeColumn(ColumnConfig column) {
        columns.remove(column);
    }

    @Override
    public SqlStatement[] generateStatements(Database database) {

        if (isNeedsPreparedStatement(database)) {
            return new SqlStatement[] {
                    new InsertExecutablePreparedStatement(database, catalogName, schemaName, tableName, columns, getChangeSet(), Scope.getCurrentScope().getResourceAccessor())
            };
        }

        InsertStatement statement = new InsertStatement(getCatalogName(), getSchemaName(), getTableName());
        for (ColumnConfig column : columns) {
            if (column.getPrologue() != null) {
                this.prologue = column.getPrologue();
            }
            if (column.getEpilogue() != null) {
                this.epilogue = column.getEpilogue();
            }
            if (prepareColumn(database, column)) continue;
            statement.addColumnValue(column.getName(), column.getValueObject());
        }
        statement.setPrologue(this.prologue);
        statement.setEpilogue(this.epilogue);
        return new SqlStatement[]{
                statement
        };
    }

    private boolean isNeedsPreparedStatement(Database database) {
        boolean needsPreparedStatement = false;
        for (ColumnConfig column : columns) {
            if (column.getValueBlobFile() != null) {
                needsPreparedStatement = true;
            }
            if (column.getValueClobFile() != null) {
                needsPreparedStatement = true;
            }
            if (LoadDataChange.LOAD_DATA_TYPE.BLOB.name().equalsIgnoreCase(column.getType())) {
                needsPreparedStatement = true;
            }
            if (LoadDataChange.LOAD_DATA_TYPE.CLOB.name().equalsIgnoreCase(column.getType())) {
                needsPreparedStatement = true;
            }

            if (!needsPreparedStatement && (database instanceof InformixDatabase)) {
                if (column.getValue() != null) {
                    try {
                        Column snapshot = SnapshotGeneratorFactory.getInstance().createSnapshot(new Column(column), database);
                        if (snapshot != null) {
                            needsPreparedStatement = true;
                        }
                    } catch (Exception ignore) { //assume it's not a clob
                    }
                }
            }
        }
        return needsPreparedStatement;
    }

    private boolean prepareColumn(Database database, ColumnConfig column) {
        if (database != null && database.supportsAutoIncrement() && (column.isAutoIncrement() != null) && column.isAutoIncrement()) {
            // skip auto increment columns as they will be generated by the database
            return true;
        }
        final Object valueObject = column.getValueObject();
        if (valueObject instanceof SequenceNextValueFunction) {
            ((SequenceNextValueFunction) valueObject).setSchemaName(this.getSchemaName());
        }
        if (valueObject instanceof SequenceCurrentValueFunction) {
            ((SequenceCurrentValueFunction) valueObject).setSchemaName(this.getSchemaName());
        }
        return false;
    }

    @Override
    public ChangeStatus checkStatus(Database database) {
        return new ChangeStatus().unknown("Cannot check insertData status");
    }

    /**
     * @see liquibase.change.Change#getConfirmationMessage()
     */
    @Override
    public String getConfirmationMessage() {
        return "New row inserted into " + getTableName();
    }

    @Override
    @DatabaseChangeProperty(since = "3.0", exampleValue = "h2, oracle",
        description = "Specifies which database type(s) a changeset is to be used for. " +
            "See valid database type names on Supported Databases docs page. Separate multiple databases with commas. " +
            "Specify that a changeset is not applicable to a particular database type by prefixing with !. " +
            "The keywords 'all' and 'none' are also available.")
    public String getDbms() {
        return dbms;
    }

    @Override
    public void setDbms(final String dbms) {
        this.dbms = dbms;
    }

    @Override
    public String getSerializedObjectNamespace() {
        return STANDARD_CHANGELOG_NAMESPACE;
    }

    @Override
    public CheckSum generateCheckSum() {
        final Database database = Scope.getCurrentScope().getDatabase();
        if (!isNeedsPreparedStatement(database)) {
            columns.forEach(column -> prepareColumn(database, column));
        }
        return super.generateCheckSum();
    }
}
