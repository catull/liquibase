package liquibase.statement;

import lombok.Getter;
import lombok.Setter;

public abstract class AbstractSqlStatement implements SqlStatement {

    private boolean continueOnError;

    @Getter
    @Setter
    protected String prologue;

    @Getter
    @Setter
    protected String epilogue;

    @Override
    public boolean skipOnUnsupported() {
        return false;
    }

    @Override
    public boolean continueOnError() {
        return continueOnError;
    }

    public void setContinueOnError(boolean continueOnError) {
        this.continueOnError = continueOnError;
    }

}
