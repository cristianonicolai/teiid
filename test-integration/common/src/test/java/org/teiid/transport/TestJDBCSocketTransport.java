/*
 * JBoss, Home of Professional Open Source.
 * See the COPYRIGHT.txt file distributed with this work for information
 * regarding copyright ownership.  Some portions may be licensed
 * to Red Hat, Inc. under one or more contributor license agreements.
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 * 02110-1301 USA.
 */

package org.teiid.transport;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.NotSerializableException;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.Arrays;
import java.util.Properties;

import org.jboss.netty.channel.ChannelEvent;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.teiid.common.buffer.BufferManagerFactory;
import org.teiid.common.buffer.StorageManager;
import org.teiid.core.types.ArrayImpl;
import org.teiid.core.types.BlobType;
import org.teiid.core.util.ObjectConverterUtil;
import org.teiid.core.util.UnitTestUtil;
import org.teiid.dqp.internal.datamgr.ConnectorManagerRepository;
import org.teiid.dqp.service.AutoGenDataService;
import org.teiid.jdbc.ConnectionImpl;
import org.teiid.jdbc.ConnectionProfile;
import org.teiid.jdbc.FakeServer;
import org.teiid.jdbc.TeiidDriver;
import org.teiid.jdbc.TeiidSQLException;
import org.teiid.jdbc.TestMMDatabaseMetaData;
import org.teiid.net.CommunicationException;
import org.teiid.net.ConnectionException;
import org.teiid.net.TeiidURL;
import org.teiid.net.socket.SocketServerConnectionFactory;
import org.teiid.runtime.EmbeddedConfiguration;
import org.teiid.runtime.HardCodedExecutionFactory;
import org.teiid.translator.TranslatorException;
import org.teiid.translator.ws.BinaryWSProcedureExecution;

@SuppressWarnings("nls")
public class TestJDBCSocketTransport {

	private static final int MAX_MESSAGE = 100000;
	static InetSocketAddress addr;
	static SocketListener jdbcTransport;
	static FakeServer server;
	static int delay;
	
	@BeforeClass public static void oneTimeSetup() throws Exception {
		SocketConfiguration config = new SocketConfiguration();
		config.setSSLConfiguration(new SSLConfiguration());
		addr = new InetSocketAddress(0);
		config.setBindAddress(addr.getHostName());
		config.setPortNumber(0);
		
		EmbeddedConfiguration dqpConfig = new EmbeddedConfiguration();
		dqpConfig.setMaxActivePlans(2);
		server = new FakeServer(false);
		server.start(dqpConfig, false);
		server.deployVDB("parts", UnitTestUtil.getTestDataPath() + "/PartsSupplier.vdb");
		
		jdbcTransport = new SocketListener(addr, config, server.getClientServiceRegistry(), BufferManagerFactory.getStandaloneBufferManager()) {
			@Override
			protected SSLAwareChannelHandler createChannelPipelineFactory(
					SSLConfiguration config, StorageManager storageManager) {
				SSLAwareChannelHandler result = new SSLAwareChannelHandler(this, config, Thread.currentThread().getContextClassLoader(), storageManager) {
					@Override
					public void handleDownstream(ChannelHandlerContext ctx,
							ChannelEvent e) throws Exception {
						if (delay > 0) {
							Thread.sleep(delay);
						}
						super.handleDownstream(ctx, e);
					}
				};
				result.setMaxMessageSize(MAX_MESSAGE);
				return result;
			}
		};
	}
	
	@AfterClass public static void oneTimeTearDown() throws Exception {
		if (jdbcTransport != null) {
			jdbcTransport.stop();
		}
		server.stop();
	}
	
	Connection conn;
	
	@Before public void setUp() throws Exception {
		Properties p = new Properties();
		p.setProperty("user", "testuser");
		p.setProperty("password", "testpassword");
		conn = TeiidDriver.getInstance().connect("jdbc:teiid:parts@mm://"+addr.getHostName()+":" +jdbcTransport.getPort(), p);
	}
	
	@After public void tearDown() throws Exception {
		if (conn != null) {
			conn.close();
		}
	}
	
	@Test public void testSelect() throws Exception {
		Statement s = conn.createStatement();
		assertTrue(s.execute("select * from tables order by name"));
		TestMMDatabaseMetaData.compareResultSet(s.getResultSet());
	}
	
	@Test public void testLobStreaming() throws Exception {
		Statement s = conn.createStatement();
		assertTrue(s.execute("select xmlelement(name \"root\") from tables"));
		s.getResultSet().next();
		assertEquals("<root></root>", s.getResultSet().getString(1));
	}
	
	@Test public void testLobStreaming1() throws Exception {
		Statement s = conn.createStatement();
		assertTrue(s.execute("select cast('' as clob) from tables"));
		s.getResultSet().next();
		assertEquals("", s.getResultSet().getString(1));
	}
	
	@Test public void testVarbinary() throws Exception {
		Statement s = conn.createStatement();
		assertTrue(s.execute("select X'aab1'"));
		s.getResultSet().next();
		byte[] bytes = s.getResultSet().getBytes(1);
		assertArrayEquals(new byte[] {(byte)0xaa, (byte)0xb1}, bytes);
		assertArrayEquals(bytes, s.getResultSet().getBlob(1).getBytes(1, 2));
	}
	
	@Test public void testVarbinaryPrepared() throws Exception {
		PreparedStatement s = conn.prepareStatement("select cast(? as varbinary)");
		s.setBytes(1, "hello".getBytes());
		assertTrue(s.execute());
		s.getResultSet().next();
		byte[] bytes = s.getResultSet().getBytes(1);
		assertEquals("hello", new String(bytes));
	}
	
	@Test public void testLargeVarbinaryPrepared() throws Exception {
		PreparedStatement s = conn.prepareStatement("select cast(? as varbinary)");
		s.setBytes(1, new byte[1 << 16]);
		assertTrue(s.execute());
		s.getResultSet().next();
		byte[] bytes = s.getResultSet().getBytes(1);
		assertArrayEquals(new byte[1 << 16], bytes);
	}
	
	@Test public void testXmlTableScrollable() throws Exception {
		Statement s = conn.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);
		assertTrue(s.execute("select * from xmltable('/root/row' passing (select xmlelement(name \"root\", xmlagg(xmlelement(name \"row\", xmlforest(t.name)) order by t.name)) from (select t.* from tables as t, columns as t1 limit 7000) as t) columns \"Name\" string) as x"));
		ResultSet rs = s.getResultSet();
		int count = 0;
		while (rs.next()) {
			count++;
		}
		assertEquals(7000, count);
		rs.beforeFirst();
		while (rs.next()) {
			count--;
		}
		assertEquals(0, count);
	}
	
	@Test public void testGeneratedKeys() throws Exception {
		Statement s = conn.createStatement();
		s.execute("create local temporary table x (y serial, z integer, primary key (y))");
		assertFalse(s.execute("insert into x (z) values (1)", Statement.RETURN_GENERATED_KEYS));
		ResultSet rs = s.getGeneratedKeys();
		rs.next();
		assertEquals(1, rs.getInt(1));
	}
	
	/**
	 * Ensures if you start more than the maxActivePlans
	 * where all the plans take up more than output buffer limit
	 * that processing still proceeds
	 * @throws Exception
	 */
	@Test public void testSimultaneousLargeSelects() throws Exception {
		for (int j = 0; j < 3; j++) {
			Statement s = conn.createStatement();
			assertTrue(s.execute("select * from columns c1, columns c2"));
		}
	}
	
	/**
	 * Tests to ensure that a SynchronousTtl/synchTimeout does
	 * not cause a cancel.
	 * TODO: had to increase the values since the test would fail on slow machine runs
	 * @throws Exception
	 */
	@Test public void testSyncTimeout() throws Exception {
		TeiidDriver td = new TeiidDriver();
		td.setSocketProfile(new ConnectionProfile() {
			
			@Override
			public ConnectionImpl connect(String url, Properties info)
					throws TeiidSQLException {
				SocketServerConnectionFactory sscf = new SocketServerConnectionFactory();
				sscf.initialize(info);
				try {
					return new ConnectionImpl(sscf.getConnection(info), info, url);
				} catch (CommunicationException e) {
					throw TeiidSQLException.create(e);
				} catch (ConnectionException e) {
					throw TeiidSQLException.create(e);
				}
			}
		});
		Properties p = new Properties();
		p.setProperty("user", "testuser");
		p.setProperty("password", "testpassword");
		ConnectorManagerRepository cmr = server.getConnectorManagerRepository();
		AutoGenDataService agds = new AutoGenDataService() {
			@Override
			public Object getConnectionFactory()
					throws TranslatorException {
				return null;
			}
		};
		agds.setSleep(2000); //wait longer than the synch ttl/soTimeout, we should still succeed
		cmr.addConnectorManager("source", agds);
		try {
			conn = td.connect("jdbc:teiid:parts@mm://"+addr.getHostName()+":" +jdbcTransport.getPort(), p);
			Statement s = conn.createStatement();
			assertTrue(s.execute("select * from parts"));
		} finally {
			server.setConnectorManagerRepository(cmr);
		}
	}
	
	@Test public void testProtocolException() throws Exception {
		Statement s = conn.createStatement();
		try {
			s.execute("select * from objecttable('teiid_context' columns teiid_row object 'teiid_row') as x");
			fail();
		} catch (SQLException e) {
			assertTrue(e.getCause() instanceof NotSerializableException);
		}
		//make sure the connection is still alive
		s.execute("select 1");
		ResultSet rs = s.getResultSet();
		rs.next();
		assertEquals(1, rs.getInt(1));
	}
	
	@Test public void testStreamingLob() throws Exception {
		HardCodedExecutionFactory ef = new HardCodedExecutionFactory();
		ef.addData("SELECT helloworld.x FROM helloworld", Arrays.asList(Arrays.asList(new BlobType(new BinaryWSProcedureExecution.StreamingBlob(new ByteArrayInputStream(new byte[100]))))));
		server.addTranslator("custom", ef);
		server.deployVDB(new ByteArrayInputStream("<vdb name=\"test\" version=\"1\"><model name=\"test\"><source name=\"test\" translator-name=\"custom\"/><metadata type=\"DDL\"><![CDATA[CREATE foreign table helloworld (x blob);]]> </metadata></model></vdb>".getBytes("UTF-8")));
		conn = TeiidDriver.getInstance().connect("jdbc:teiid:test@mm://"+addr.getHostName()+":" +jdbcTransport.getPort(), null);
		
		Statement s = conn.createStatement();
		ResultSet rs = s.executeQuery("select to_chars(x, 'UTF-8') from helloworld");
		rs.next();
		//TODO: if we use getString streaming will still fail because the string logic gets the length first
		assertEquals(100, ObjectConverterUtil.convertToCharArray(rs.getCharacterStream(1), -1).length);
	}
	
	@Test public void testArray() throws Exception {
		Statement s = conn.createStatement();
		ResultSet rs = s.executeQuery("SELECT (1, (1,2))");
		rs.next();
		assertEquals(new ArrayImpl(new Object[] {1, new Object[] {1, 2}}), rs.getArray(1));
		assertEquals("java.sql.Array", rs.getMetaData().getColumnClassName(1));
		assertEquals(Types.ARRAY, rs.getMetaData().getColumnType(1));
		assertEquals("object[]", rs.getMetaData().getColumnTypeName(1));
	}
	
	@Test public void testLargeMessage() throws Exception {
		Statement s = conn.createStatement();
		StringBuilder sb = new StringBuilder();
		sb.append("SELECT '");
		for (int i = 0; i < MAX_MESSAGE; i++) {
			sb.append('a');
		}
		sb.append('\'');
		try {
			s.executeQuery(sb.toString());
			fail();
		} catch (SQLException e) {
			
		}
		ResultSet rs = s.executeQuery("select 1");
		rs.next();
		assertEquals(1, rs.getInt(1));
	}
	
	@Test(expected=TeiidSQLException.class) public void testLoginTimeout() throws SQLException {
		Properties p = new Properties();
		p.setProperty(TeiidURL.CONNECTION.LOGIN_TIMEOUT, "1");
		delay = 1500;
		try {
			conn = TeiidDriver.getInstance().connect("jdbc:teiid:parts@mm://"+addr.getHostName()+":" +(jdbcTransport.getPort()), p);
		} finally {
			delay = 0;
		}
	}
	
}
