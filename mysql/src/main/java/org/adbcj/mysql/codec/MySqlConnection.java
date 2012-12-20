package org.adbcj.mysql.codec;

import org.adbcj.*;
import org.adbcj.mysql.netty.MysqlConnectionManager;
import org.adbcj.support.DefaultDbFuture;
import org.adbcj.support.DefaultResultEventsHandler;
import org.adbcj.support.DefaultResultSet;
import org.jboss.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.Set;

public class MySqlConnection implements Connection {

    private static final Logger logger = LoggerFactory.getLogger(MySqlConnection.class);

    private final int maxQueueSize;
    private final MysqlConnectionManager connectionManager;
    private final Channel channel;

    protected final int id;

    private final MysqlCharacterSet charset = MysqlCharacterSet.UTF8_UNICODE_CI;

    private final ArrayDeque<MySqlRequest> requestQueue;

    private final Object lock = new Object();
    private volatile DefaultDbFuture<Void> closeFuture;

    public MySqlConnection(int maxQueueSize, MysqlConnectionManager connectionManager, Channel channel) {
        this.maxQueueSize = maxQueueSize;
        this.connectionManager = connectionManager;
        this.channel = channel;
        this.id = connectionManager.nextId();
        connectionManager.addConnection(this);

        synchronized (lock) {
            requestQueue = new ArrayDeque<MySqlRequest>(maxQueueSize + 1);
        }
    }

    public ConnectionManager getConnectionManager() {
        return connectionManager;
    }

    public synchronized DbFuture<Void> close() throws DbException {
        return close(CloseMode.CLOSE_GRACEFULLY);
    }


    @Override
    public void beginTransaction() {
        throw new Error("Not implemented yet: TODO");  //TODO: Implement
    }

    @Override
    public DbSessionFuture<Void> commit() {
        throw new Error("Not implemented yet: TODO");  //TODO: Implement
    }

    @Override
    public DbSessionFuture<Void> rollback() {
        throw new Error("Not implemented yet: TODO");  //TODO: Implement
    }

    @Override
    public boolean isInTransaction() {
        throw new Error("Not implemented yet: TODO");  //TODO: Implement
    }

    @Override
    public DbSessionFuture<ResultSet> executeQuery(String sql) {
        DefaultResultSet rs = new DefaultResultSet();
        DefaultResultEventsHandler handler = new DefaultResultEventsHandler();
        return (DbSessionFuture) executeQuery(sql, handler, rs);
    }

    @Override
    public <T> DbSessionFuture<T> executeQuery(String sql, ResultHandler<T> eventHandler, T accumulator) {
        return (DbSessionFuture) queRequest(MySqlRequests.executeQuery(sql,
                eventHandler,
                accumulator,
                this)).getFuture();
    }

    @Override
    public DbSessionFuture<Result> executeUpdate(String sql) {
        return (DbSessionFuture) queRequest(MySqlRequests.executeUpdate(sql,
                this)).getFuture();
    }

    @Override
    public DbSessionFuture<PreparedQuery> prepareQuery(String sql) {
        return (DbSessionFuture) queRequest(MySqlRequests.prepareQuery(sql,
                this)).getFuture();
    }

    @Override
    public DbSessionFuture<PreparedUpdate> prepareUpdate(String sql) {
        throw new Error("Not implemented yet: TODO");  //TODO: Implement
    }

    @Override
    public DbFuture<Void> close(CloseMode closeMode) throws DbException {
        synchronized (lock) {
            if (null == closeFuture) {
                final MySqlRequest closeRequest = MySqlRequests.createCloseRequest(this);
                closeFuture = closeRequest.getFuture();
                forceQueRequest(closeRequest);
                return closeFuture;
            } else {
                return closeFuture;
            }
        }
    }

    @Override
    public boolean isClosed() throws DbException {
        return closeFuture != null;
    }

    @Override
    public boolean isOpen() throws DbException {
        throw new Error("Not implemented yet: TODO");  //TODO: Implement
    }


    private static final Set<ClientCapabilities> CLIENT_CAPABILITIES = EnumSet.of(
            ClientCapabilities.LONG_PASSWORD,
            ClientCapabilities.FOUND_ROWS,
            ClientCapabilities.LONG_COLUMN_FLAG,
            ClientCapabilities.CONNECT_WITH_DB,
            ClientCapabilities.LOCAL_FILES,
            ClientCapabilities.PROTOCOL_4_1,
            ClientCapabilities.TRANSACTIONS,
            ClientCapabilities.SECURE_AUTHENTICATION);

    public Set<ClientCapabilities> getClientCapabilities() {
        return CLIENT_CAPABILITIES;
    }

    private static final Set<ExtendedClientCapabilities> EXTENDED_CLIENT_CAPABILITIES = EnumSet.of(
            ExtendedClientCapabilities.MULTI_RESULTS
    );

    public Set<ExtendedClientCapabilities> getExtendedClientCapabilities() {
        return EXTENDED_CLIENT_CAPABILITIES;
    }

    public MySqlRequest queRequest(MySqlRequest request) {
        synchronized (lock) {

            int requestsPending = requestQueue.size();
            if (requestsPending > maxQueueSize) {
                throw new DbException("To many pending requests. The current maximum is " + maxQueueSize + "." +
                        "Ensure that your not overloading the database with requests. " +
                        "Also check the " + StandardProperties.MAX_QUEUE_LENGTH + " property");
            }
            return forceQueRequest(request);
        }
    }

    public MySqlRequest forceQueRequest(MySqlRequest request) {
        synchronized (lock) {
            requestQueue.add(request);
            channel.write(request.getRequest());
            return request;
        }
    }

    public void tryCompleteClose() {
        synchronized (lock) {
            if (null != closeFuture) {
                closeFuture.trySetResult(null);
            }
        }
    }

    public MySqlRequest dequeRequest() {
            synchronized (lock){
                final MySqlRequest request = requestQueue.poll();
                if(logger.isDebugEnabled()){
                    logger.debug("Dequeued request: {}",request);
                }
                if(request.getRequest().wasCancelled()){
                    if(logger.isDebugEnabled()){
                        logger.debug("Request has been cancelled: {}",request);
                    }
                    return dequeRequest();
                }
                return request;
        }
    }

    public Object lock() {
        return lock;
    }
}
