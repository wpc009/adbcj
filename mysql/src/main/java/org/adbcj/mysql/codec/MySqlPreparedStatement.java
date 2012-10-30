package org.adbcj.mysql.codec;

import org.adbcj.*;
import org.adbcj.mysql.codec.packets.ClosePreparedStatementRequest;
import org.adbcj.mysql.codec.packets.PreparedStatementRequest;
import org.adbcj.mysql.codec.packets.StatementPreparedEOF;
import org.adbcj.support.AbstractDbSession;
import org.adbcj.support.DefaultResultEventsHandler;
import org.adbcj.support.DefaultResultSet;
import org.adbcj.support.ExpectResultRequest;

/**
 * @author roman.stoffel@gamlor.info
 * @since 11.04.12
 */
public class MySqlPreparedStatement implements PreparedQuery, PreparedUpdate {
    private final AbstractMySqlConnection connection;
    private final StatementPreparedEOF statementInfo;
    private volatile boolean isOpen = true;

    public MySqlPreparedStatement(AbstractMySqlConnection connection,
                                  StatementPreparedEOF statementInfo) {
        this.connection = connection;
        this.statementInfo = statementInfo;
    }

    @Override
    public <T> DbSessionFuture<T> executeWithCallback(ResultHandler<T> eventHandler, T accumulator, Object... params) {
        return connection.enqueueTransactionalRequest(new ExecutePrepareStatement(eventHandler, accumulator, params));
    }

    @Override
    public DbSessionFuture execute(final Object... params) {
        validateParameters(params);
        ResultHandler<DefaultResultSet> eventHandler = new DefaultResultEventsHandler();
        DefaultResultSet resultSet = new DefaultResultSet();


        return connection.enqueueTransactionalRequest(new ExecutePrepareStatement(eventHandler, resultSet, params));
    }

    private void validateParameters(Object[] params) {
        if(isClosed()){
            throw new IllegalStateException("Cannot execute closed statement");
        }
        if (params.length != statementInfo.getParametersTypes().size()) {
            throw new IllegalArgumentException("Expect " + statementInfo.getParametersTypes().size() + " paramenters " +
                    "but got " + params.length + " parameters");
        }
    }

    @Override
    public boolean isClosed() {
        return connection.isClosed() || !isOpen;
    }

    @Override
    public DbFuture<Void> close() {
        isOpen  = false;
        DbSessionFuture<Void> future = connection.enqueueTransactionalRequest(new AbstractDbSession.Request<Void>(connection) {
            @Override
            protected void execute() throws Exception {
                ClosePreparedStatementRequest request = new ClosePreparedStatementRequest(statementInfo.getHandlerId());
                connection.write(request);
                complete(null);
            }

        });
        return future;
    }

    public class ExecutePrepareStatement<T> extends ExpectResultRequest {
        private final Object[] params;

        public ExecutePrepareStatement(ResultHandler<T> eventHandler, T resultSet, Object... params) {
            super(MySqlPreparedStatement.this.connection, eventHandler, resultSet);
            this.params = params;
        }

        @Override
        public void execute() throws Exception {
            PreparedStatementRequest request = new PreparedStatementRequest(statementInfo.getHandlerId(),
                    statementInfo.getParametersTypes(), params);
            connection.write(request);
        }

        @Override
        public String toString() {
            return "Prepared statement execute";
        }
    }
}
