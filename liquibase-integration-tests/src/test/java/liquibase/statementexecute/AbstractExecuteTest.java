package liquibase.statementexecute;

import liquibase.CatalogAndSchema;
import liquibase.Scope;
import liquibase.changelog.ChangeLogHistoryServiceFactory;
import liquibase.database.Database;
import liquibase.database.DatabaseConnection;
import liquibase.database.DatabaseFactory;
import liquibase.database.core.MariaDBDatabase;
import liquibase.database.core.MockDatabase;
import liquibase.database.core.MySQLDatabase;
import liquibase.database.core.UnsupportedDatabase;
import liquibase.database.example.ExampleCustomDatabase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.datatype.DataTypeFactory;
import liquibase.exception.DatabaseException;
import liquibase.exception.UnexpectedLiquibaseException;
import liquibase.executor.ExecutorService;
import liquibase.extension.testing.testsystem.DatabaseTestSystem;
import liquibase.extension.testing.testsystem.TestSystemFactory;
import liquibase.extension.testing.testsystem.core.MariaDBTestSystem;
import liquibase.extension.testing.testsystem.core.MySQLTestSystem;
import liquibase.listener.SqlListener;
import liquibase.lockservice.LockServiceFactory;
import liquibase.snapshot.SnapshotGeneratorFactory;
import liquibase.sql.Sql;
import liquibase.sqlgenerator.SqlGeneratorFactory;
import liquibase.statement.SqlStatement;
import liquibase.structure.core.Schema;
import liquibase.structure.core.Table;
import liquibase.test.TestContext;
import org.junit.After;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public abstract class AbstractExecuteTest {

    protected SqlStatement statementUnderTest;
    private Set<Class<? extends Database>> testedDatabases = new HashSet<Class<? extends Database>>();

    @After
    public void reset() {
        for (Database database : TestContext.getInstance().getAllDatabases()) {
            if (database.getConnection() != null) {
                try {
                    database.rollback();
                } catch (DatabaseException e) {
                    //ok
                }
            }
        }
        testedDatabases = new HashSet<Class<? extends Database>>();
        this.statementUnderTest = null;

        SnapshotGeneratorFactory.resetAll();
    }

    protected abstract List<? extends SqlStatement> setupStatements(Database database);

    protected void testOnAll(String expectedSql) throws Exception {
        test(expectedSql, null, null);
    }

    protected void assertCorrectOnRest(String expectedSql) throws Exception {
        assertCorrect(expectedSql);
    }

    @SafeVarargs
    protected final void assertCorrect(String expectedSql, Class<? extends Database>... includeDatabases) throws
        Exception {
        assertCorrect(new String[]{expectedSql}, includeDatabases);
    }

    /**
     *
     * Make the assertion if the included database is under test
     *
     * @param   expectedSql
     * @param   includeDatabases
     * @throws  Exception
     *
     */
    @SafeVarargs
    protected final void assertCorrectUnderTest(String expectedSql, Class<? extends Database>... includeDatabases) throws
            Exception {
        List<Class<? extends Database>> includeDatabasesList = Arrays.asList(includeDatabases);
        List<DatabaseTestSystem> underTest =  Scope.getCurrentScope().getSingleton(TestSystemFactory.class).getAvailable(DatabaseTestSystem.class);
        for (DatabaseTestSystem databaseTestSystem : underTest) {
            Database database = databaseTestSystem.getDatabaseFromFactory();
            if (includeDatabasesList.contains(database.getClass())) {
                assertCorrect(expectedSql, database.getClass());
                break;
            }
        }
    }

    @SafeVarargs
    protected final void assertCorrect(String[] expectedSql, Class<? extends Database>... includeDatabases) throws Exception {
        assertNotNull("SqlStatement to test is NOT null.", statementUnderTest);

        test(expectedSql, includeDatabases, null);
    }

    @SafeVarargs
    public final void testOnAllExcept(String expectedSql, Class<? extends Database>... excludedDatabases) throws Exception {
        test(expectedSql, null, excludedDatabases);
    }

    private void test(String expectedSql, Class<? extends Database>[] includeDatabases, Class<? extends Database>[] excludeDatabases) throws Exception {
        test(new String[]{expectedSql}, includeDatabases, excludeDatabases);
    }

    private void test(String[] expectedSql, Class<? extends Database>[] includeDatabases, Class<? extends Database>[] excludeDatabases) throws Exception {

        if (expectedSql != null) {
            for (Database database : TestContext.getInstance().getAllDatabases()) {
                if (shouldTestDatabase(database, includeDatabases, excludeDatabases)) {
                    testedDatabases.add(database.getClass());

                    if (database.getConnection() != null) {
                        Scope.getCurrentScope().getSingleton(ChangeLogHistoryServiceFactory.class).getChangeLogService(database).init();
                        LockServiceFactory.getInstance().getLockService(database).init();
                    }

                    Sql[] sql = SqlGeneratorFactory.getInstance().generateSql(statementUnderTest, database);

                    assertNotNull("Null SQL for " + database, sql);
                    assertEquals("Unexpected number of  SQL statements for " + database, expectedSql.length, sql.length);

                    int index = 0;
                    for (String convertedSql : expectedSql) {
                        convertedSql = replaceEscaping(convertedSql, database);
                        convertedSql = replaceDatabaseClauses(convertedSql, database);
                        convertedSql = replaceStandardTypes(convertedSql, database);

                        assertEquals("Incorrect SQL for " + database.getClass().getName(), convertedSql.toLowerCase().trim(), sql[index].toSql().toLowerCase());
                        index++;
                    }
                }
            }
        }

        resetAvailableDatabases();
        for (DatabaseTestSystem testSystem : Scope.getCurrentScope().getSingleton(TestSystemFactory.class).getAvailable(DatabaseTestSystem.class)) {
            testSystem.start();

            Database database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(testSystem.getConnection()));

            Statement statement = ((JdbcConnection) database.getConnection()).getUnderlyingConnection().createStatement();
            if (shouldTestDatabase(database, includeDatabases, excludeDatabases)) {
                String sqlToRun = SqlGeneratorFactory.getInstance().generateSql(statementUnderTest, database)[0].toSql();
                try {
                    for (SqlListener listener : Scope.getCurrentScope().getListeners(SqlListener.class)) {
                        listener.writeSqlWillRun(sqlToRun);
                    }
                    statement.execute(sqlToRun);
                } catch (Exception e) {
                    System.out.println("Failed to execute against " + database.getShortName() + ": " + sqlToRun);
                    throw e;

                }
            }
        }
    }

    private String replaceStandardTypes(String convertedSql, Database database) {
        convertedSql = replaceType("int", convertedSql, database);
        convertedSql = replaceType("datetime", convertedSql, database);
        convertedSql = replaceType("boolean", convertedSql, database);

        convertedSql = convertedSql.replaceAll("FALSE", DataTypeFactory.getInstance().fromDescription("boolean", database).objectToSql(false, database));
        convertedSql = convertedSql.replaceAll("TRUE", DataTypeFactory.getInstance().fromDescription("boolean", database).objectToSql(true, database));
        convertedSql = convertedSql.replaceAll("NOW\\(\\)", database.getCurrentDateTimeFunction());

        return convertedSql;
    }

    private String replaceType(String type, String baseString, Database database) {
        return baseString.replaceAll(" " + type + " ", " " + DataTypeFactory.getInstance().fromDescription(type, database).toDatabaseDataType(database).toString() + " ")
                .replaceAll(" " + type + ",", " " + DataTypeFactory.getInstance().fromDescription(type, database).toDatabaseDataType(database).toString() + ",");
    }

    private String replaceDatabaseClauses(String convertedSql, Database database) {
        return convertedSql.replaceFirst("auto_increment_clause", database.getAutoIncrementClause(null, null, null, null));
    }

    private boolean shouldTestDatabase(Database database, Class<? extends Database>[] includeDatabases, Class<? extends Database>[] excludeDatabases) {
        if ((database instanceof MockDatabase) || (database instanceof ExampleCustomDatabase) || (database instanceof
            UnsupportedDatabase)) {
            return false;
        }
        if (!SqlGeneratorFactory.getInstance().supports(statementUnderTest, database)
                || SqlGeneratorFactory.getInstance().validate(statementUnderTest, database).hasErrors()) {
            return false;
        }

        boolean shouldInclude = true;
        if ((includeDatabases != null) && (includeDatabases.length > 0)) {
            shouldInclude = Arrays.asList(includeDatabases).contains(database.getClass());
        }

        boolean shouldExclude = false;
        if ((excludeDatabases != null) && (excludeDatabases.length > 0)) {
            shouldExclude = Arrays.asList(excludeDatabases).contains(database.getClass());
        }

        return !shouldExclude && shouldInclude && !testedDatabases.contains(database.getClass());


    }

    private String replaceEscaping(String expectedSql, Database database) {
        String convertedSql = expectedSql;
        int lastIndex = 0;
        while ((lastIndex = convertedSql.indexOf("[", lastIndex)) >= 0) {
            String objectName = convertedSql.substring(lastIndex + 1, convertedSql.indexOf("]", lastIndex));
            try {
                convertedSql = convertedSql.replace("[" + objectName + "]", database.escapeObjectName(objectName, Table.class));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            lastIndex++;
        }

        return convertedSql;
    }

    public void resetAvailableDatabases() throws Exception {
        for (DatabaseTestSystem testSystem : Scope.getCurrentScope().getSingleton(TestSystemFactory.class).getAvailable(DatabaseTestSystem.class)) {
            testSystem.start();
            Database database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(testSystem.getConnection()));
            DatabaseConnection connection = database.getConnection();
            Statement connectionStatement = ((JdbcConnection) connection).getUnderlyingConnection().createStatement();
            connection.commit();

            try {
                database.dropDatabaseObjects(CatalogAndSchema.DEFAULT);
                CatalogAndSchema alt = new CatalogAndSchema(testSystem.getAltCatalog(), testSystem.getAltSchema());
                database.dropDatabaseObjects(alt);
            } catch (Exception e) {
                throw new UnexpectedLiquibaseException("Error dropping objects for database "+database.getShortName(), e);
            }
            try {
                connectionStatement.executeUpdate("drop table " + database.escapeTableName(database.getLiquibaseCatalogName(), database.getLiquibaseSchemaName(), database.getDatabaseChangeLogLockTableName()));
            } catch (SQLException e) {
            }
            connection.commit();
            try {
                connectionStatement.executeUpdate("drop table " + database.escapeTableName(database.getLiquibaseCatalogName(), database.getLiquibaseSchemaName(), database.getDatabaseChangeLogTableName()));
            } catch (SQLException e) {
            }
            connection.commit();

            if (database.supports(Schema.class)) {
                try {
                database.dropDatabaseObjects(new CatalogAndSchema(null, testSystem.getAltSchema()));
                } catch (DatabaseException e) {
                    //ok
                }
                connection.commit();

                try {
                    connectionStatement.executeUpdate("drop table " + database.escapeTableName(testSystem.getAltCatalog(), testSystem.getAltSchema(), database.getDatabaseChangeLogLockTableName()));
                } catch (SQLException e) {
                    //ok
                }
                connection.commit();
                try {
                    connectionStatement.executeUpdate("drop table " + database.escapeTableName(testSystem.getAltCatalog(), testSystem.getAltSchema(), database.getDatabaseChangeLogTableName()));
                } catch (SQLException e) {
                    //ok
                }
                connection.commit();
            }

            List<? extends SqlStatement> setupStatements = setupStatements(database);
            if (setupStatements != null) {
                for (SqlStatement statement : setupStatements) {
                    Scope.getCurrentScope().getSingleton(ExecutorService.class).getExecutor("jdbc", database).execute(statement);
                }
            }
            connectionStatement.close();
        }
    }

}
