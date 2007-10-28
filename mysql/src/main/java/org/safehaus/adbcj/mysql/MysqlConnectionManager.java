/*
 *   Copyright (c) 2007 Mike Heath.  All rights reserved.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *
 */
package org.safehaus.adbcj.mysql;

import java.net.InetSocketAddress;
import java.util.Properties;
import java.util.concurrent.ExecutorService;

import org.apache.mina.common.ConnectFuture;
import org.apache.mina.common.DefaultIoFilterChainBuilder;
import org.apache.mina.common.IoFuture;
import org.apache.mina.common.IoFutureListener;
import org.apache.mina.filter.codec.ProtocolCodecFilter;
import org.apache.mina.filter.codec.demux.DemuxingProtocolCodecFactory;
import org.apache.mina.filter.codec.demux.MessageDecoder;
import org.apache.mina.filter.codec.demux.MessageDecoderFactory;
import org.apache.mina.transport.socket.nio.NioSocketConnector;
import org.safehaus.adbcj.Connection;
import org.safehaus.adbcj.ConnectionManager;
import org.safehaus.adbcj.DbException;
import org.safehaus.adbcj.DbFuture;
import org.safehaus.adbcj.DbListener;
import org.safehaus.adbcj.Result;
import org.safehaus.adbcj.support.DefaultDbFuture;
import org.safehaus.adbcj.support.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MysqlConnectionManager implements ConnectionManager {
	
	public static final String CODEC_NAME = MysqlConnectionManager.class.getName() + ".codec";

	private final Logger logger = LoggerFactory.getLogger(MysqlConnectionManager.class);

	private final NioSocketConnector socketConnector;
	
	private final InetSocketAddress address;
	
	private final LoginCredentials credentials;
	
	private DbFuture<Void> closeFuture = null;
	
	public MysqlConnectionManager(String host, int port, String username, String password, String schema, ExecutorService executorService, Properties properties) {
		int processorCount = Runtime.getRuntime().availableProcessors();
		socketConnector = new NioSocketConnector(processorCount, executorService);
		//socketConnector.setWorkerTimeout(5); // TODO Make MINA worker timeout configurable in MysqlConnectionManager
		socketConnector.getSessionConfig().setTcpNoDelay(true);
		DefaultIoFilterChainBuilder filterChain = socketConnector.getFilterChain();
		
		DemuxingProtocolCodecFactory codecFactory = new DemuxingProtocolCodecFactory();
		codecFactory.register(new MessageDecoderFactory() {
			public MessageDecoder getDecoder() throws Exception {
				return new MysqlMessageDecoder();
			}
		});
		codecFactory.register(new LoginRequestEncoder());
		codecFactory.register(new CommandRequestEncoder());

		filterChain.addLast(CODEC_NAME, new ProtocolCodecFilter(codecFactory));
		
		socketConnector.setHandler(new MysqlIoHandler());
		
		address = new InetSocketAddress(host, port);
		
		this.credentials = new LoginCredentials(username, password, schema);
	}
	
	public synchronized DbFuture<Void> close(boolean immediate) throws DbException {
		if (isClosed()) {
			return closeFuture;
		}
		
		if (immediate) {
			socketConnector.close();
			DefaultDbFuture<Void> future = new DefaultDbFuture<Void>();
			future.setDone();
			closeFuture = future;
			return closeFuture;
		} else {
			// TODO In MysqlConnectionManager.close() implemented deferred close
			throw new IllegalStateException("Deferred close not yet implemented");
		}
	}

	public synchronized boolean isClosed() {
		return closeFuture != null;
	}
	
	public DbFuture<Connection> connect() {
		if (isClosed()) {
			throw new DbException("Connection manager closed");
		}
		final ConnectFuture connectFuture = socketConnector.connect(address);
		
		final DefaultDbFuture<Connection> dbConnectFuture = new DefaultDbFuture<Connection>() {
			@Override
			protected boolean doCancel(boolean mayInterruptIfRunning) {
				logger.trace("Cancelling connect");
				connectFuture.cancel();
				if (connectFuture.isCanceled()) {
					logger.trace("Canceled connect");
					connectFuture.cancel();
					return true;
				}
				logger.trace("Did not cancel connect");
				return false;
			}
		};
		
		connectFuture.addListener(new IoFutureListener() {
			public void operationComplete(IoFuture future) {
				logger.trace("Completed connection to {}", MysqlConnectionManager.this);
				
				final MysqlConnection connection = new MysqlConnection(MysqlConnectionManager.this, future.getSession(), credentials);
				IoSessionUtil.setMysqlConnection(future.getSession(), connection);
				
				connection.enqueueRequest(new Request<Result>() {
					public void execute(DefaultDbFuture<Result> future) {
						dbConnectFuture.setValue(connection);
					}
				}).addListener(new DbListener<Result>() {
					public void onCompletion(DbFuture<Result> future) {
						try {
							future.get();
							dbConnectFuture.setValue(connection);
						} catch (DbException e) {
							dbConnectFuture.setException(e);
						} catch (InterruptedException e) {
							dbConnectFuture.setException(new DbException(e));
						} finally {
							dbConnectFuture.setDone();
						}
					}
				});
				
			}
		});
		
		return dbConnectFuture;
	}
	
	@Override
	public String toString() {
		return String.format("%s: mysql://%s:%d/%s (user: %s)", getClass().getName(), address.getHostName(), address.getPort(), credentials.getDatabase(), credentials.getUserName());
	}

}