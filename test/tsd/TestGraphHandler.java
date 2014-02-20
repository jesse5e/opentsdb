// This file is part of OpenTSDB.
// Copyright (C) 2011-2012  The OpenTSDB Authors.
//
// This program is free software: you can redistribute it and/or modify it
// under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 2.1 of the License, or (at your
// option) any later version.  This program is distributed in the hope that it
// will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty
// of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser
// General Public License for more details.  You should have received a copy
// of the GNU Lesser General Public License along with this program.  If not,
// see <http://www.gnu.org/licenses/>.
package net.opentsdb.tsd;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.powermock.api.mockito.PowerMockito.mock;
import static org.powermock.api.mockito.PowerMockito.spy;

import java.util.concurrent.ThreadPoolExecutor;

import net.opentsdb.uid.NoSuchUniqueName;

import net.opentsdb.core.DataPoints;
import net.opentsdb.core.Query;
import net.opentsdb.core.TSQuery;
import net.opentsdb.core.TSDB;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;


@RunWith(PowerMockRunner.class)
@PrepareForTest({TSDB.class, TSQuery.class, QueryResultFileCache.class, QueryRpc.class})
public final class TestGraphHandler {

  final private QueryResultFileCache mock_query_cache = mock(QueryResultFileCache.class);
  final private ThreadPoolExecutor mock_gnu_plot = mock(ThreadPoolExecutor.class);
  private TSDB tsdb = null;

  @Before
  public void before() {
    tsdb = NettyMocks.getMockedHTTPTSDB();
  }

  @Test
  public void execute() {
    GraphHandler handler = new GraphHandler(mock_query_cache, mock_gnu_plot);
    HttpQuery query = NettyMocks.getQuery(tsdb,
      "/api/query?start=1h-ago&m=sum:sys.cpu.0&json&nocache");
    TSQuery spy_ts_query = spy(QueryRpc.parseQuery(query));
    PowerMockito.mockStatic(QueryRpc.class);
    when(QueryRpc.parseQuery(query)).thenReturn(spy_ts_query);
    Query mock_query = mock(Query.class);
    when(mock_query.getStartTime()).thenReturn(0L);
    when(mock_query.getEndTime()).thenReturn(2000L);
    when(mock_query.run()).thenReturn(new DataPoints[]{});
    Query[] mock_queries = new Query[] { mock_query };
    doReturn(mock_queries).when(spy_ts_query).buildQueries(tsdb);
    when(mock_query_cache.newKeyBuilder()).thenCallRealMethod();

    handler.execute(tsdb, query);
    assertTrue(query.response().toString().contains("HTTP/1.1 202 Accepted"));
  }

  @Test (expected = BadRequestException.class)
  public void executeBadTags() {
    GraphHandler handler = new GraphHandler(mock_query_cache, mock_gnu_plot);
    HttpQuery query = NettyMocks.getQuery(tsdb,
      "/api/query?start=1h-ago&m=sum:sys.cpu.0&json&nocache{tag=foo}");
    TSQuery spy_ts_query = spy(QueryRpc.parseQuery(query));
    PowerMockito.mockStatic(QueryRpc.class);
    when(QueryRpc.parseQuery(query)).thenReturn(spy_ts_query);
    doThrow(new NoSuchUniqueName("bad tag value", "foo")).when(spy_ts_query).buildQueries(tsdb);

    handler.execute(tsdb, query);
  }
}
