package liquibase.command.core

import liquibase.Scope
import liquibase.command.util.CommandUtil
import liquibase.extension.testing.testsystem.DatabaseTestSystem
import liquibase.extension.testing.testsystem.TestSystemFactory
import liquibase.extension.testing.testsystem.spock.LiquibaseIntegrationTest
import liquibase.util.FileUtil

import spock.lang.Shared
import spock.lang.Specification

@LiquibaseIntegrationTest
class GenerateChangeLogOracleIntegrationTest extends Specification {
    @Shared
    private DatabaseTestSystem db = (DatabaseTestSystem) Scope.currentScope.getSingleton(TestSystemFactory).getTestSystem("oracle")

    def "Should export BLOB"() {
        given:
        db.databaseFromFactory.setLiteralStringMaxLength(10)
        CommandUtil.runUpdate(db,'changelogs/oracle/export/populate.sql')

        when:
        CommandUtil.runGenerateChangelog(db,'changelog.oracle.sql', 'data')

        then:
        def outputFile = new File('changelog.oracle.sql')
        def contents = FileUtil.getContents(outputFile)
        contents.contains("COMMENT ON TABLE SOME_VIEW IS 'THIS IS A COMMENT ON SOME_VIEW VIEW. THIS VIEW COMMENT SHOULD BE CAPTURED BY GenerateChangeLog.'")

        then:
        noExceptionThrown()

        cleanup:
        CommandUtil.runDropAll(db)
        outputFile.delete()
    }
}
