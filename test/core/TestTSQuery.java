// This file is part of OpenTSDB.
// Copyright (C) 2013  The OpenTSDB Authors.
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
package net.opentsdb.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import java.io.IOException;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

@RunWith(PowerMockRunner.class)
@PrepareForTest({ TSQuery.class, TSDB.class })
public final class TestTSQuery {

  @Test
  public void constructor() {
    assertNotNull(new TSQuery());
  }

  @Test
  public void validate() {
    TSQuery q = this.getMetricForValidate();
    q.validateAndSetQuery();
    assertEquals(1356998400000L, q.startTime());
    assertEquals(1356998460000L, q.endTime());
    assertEquals("sys.cpu.0", q.getQueries().get(0).getMetric());
    assertEquals("*", q.getQueries().get(0).getTags().get("host"));
    assertEquals("lga", q.getQueries().get(0).getTags().get("dc"));
    assertEquals(Aggregators.SUM, q.getQueries().get(0).aggregator());
    assertEquals(Aggregators.AVG, q.getQueries().get(0).downsampler());
    assertEquals(300000, q.getQueries().get(0).downsampleInterval());
  }
  
  @Test (expected = IllegalArgumentException.class)
  public void validateNullStart() {
    TSQuery q = this.getMetricForValidate();
    q.setStart(null);
    q.validateAndSetQuery();
  }
  
  @Test (expected = IllegalArgumentException.class)
  public void validateEmptyStart() {
    TSQuery q = this.getMetricForValidate();
    q.setStart("");
    q.validateAndSetQuery();
  }
  
  @Test (expected = IllegalArgumentException.class)
  public void validateInvalidStart() {
    TSQuery q = this.getMetricForValidate();
    q.setStart("Not a timestamp at all");
    q.validateAndSetQuery();
  }
  
  @Test
  public void validateNullEnd() {
    PowerMockito.mockStatic(System.class);
    when(System.currentTimeMillis()).thenReturn(1357300800000L);
    TSQuery q = this.getMetricForValidate();
    q.setEnd(null);
    q.validateAndSetQuery();
    assertEquals(1357300800000L, q.endTime());
  }
  
  @Test
  public void validateEmptyEnd() {    
    PowerMockito.mockStatic(System.class);
    when(System.currentTimeMillis()).thenReturn(1357300800000L);
    TSQuery q = this.getMetricForValidate();
    q.setEnd("");
    q.validateAndSetQuery();
    assertEquals(1357300800000L, q.endTime());
  }
  
  @Test (expected = IllegalArgumentException.class)
  public void validateNullQueries() {
    final TSQuery query = new TSQuery();
    query.setStart("1356998400");
    query.setEnd("1356998460");
    query.validateAndSetQuery();
  }

  @Test
  public void testBuildQueries() throws IOException {
    TSQuery q = this.getMetricForValidate();
    TSSubQuery sub = q.getQueries().get(0);
    q.validateAndSetQuery();
    TSDB mockTsdb = PowerMockito.mock(TSDB.class);
    Query mockQuery = mock(Query.class);
    when(mockTsdb.newQuery()).thenReturn(mockQuery);
    Query[] returnedQueries = q.buildQueries(mockTsdb);
    assertEquals(1, returnedQueries.length);
    assertSame(mockQuery, returnedQueries[0]);
    verify(mockQuery).setStartTime(1356998400000L);
    verify(mockQuery).setEndTime(1356998460000L);
    verify(mockQuery).downsample(300000, sub.downsampler());
    verify(mockQuery).setTimeSeries("sys.cpu.0", sub.getTags(),
                                    sub.aggregator(), sub.getRate());
    verify(mockQuery).setInterpolationTimeLimit(Const.MAX_TIMESPAN_MS);
    verify(mockQuery).setHbaseTimeStartExtensionMillis(-1);
    verify(mockQuery).setHbaseTimeEndExtensionMillis(-1);
  }

  @Test
  public void testBuildQueries__interpolationTimeLimit() throws IOException {
    TSQuery q = this.getMetricForValidate();
    TSSubQuery sub = q.getQueries().get(0);
    sub.setInterpolationTimeLimit("itl-7m");
    q.validateAndSetQuery();
    TSDB mockTsdb = PowerMockito.mock(TSDB.class);
    Query mockQuery = mock(Query.class);
    when(mockTsdb.newQuery()).thenReturn(mockQuery);
    Query[] returnedQueries = q.buildQueries(mockTsdb);
    assertEquals(1, returnedQueries.length);
    assertSame(mockQuery, returnedQueries[0]);
    verify(mockQuery).setStartTime(1356998400000L);
    verify(mockQuery).setEndTime(1356998460000L);
    verify(mockQuery).downsample(300000, sub.downsampler());
    verify(mockQuery).setTimeSeries("sys.cpu.0", sub.getTags(),
                                    sub.aggregator(), sub.getRate());
    verify(mockQuery).setInterpolationTimeLimit(420000);
    verify(mockQuery).setHbaseTimeStartExtensionMillis(-1);
    verify(mockQuery).setHbaseTimeEndExtensionMillis(-1);
  }

  @Test
  public void testBuildQueries__hbaseTimeExtension() throws IOException {
    TSQuery q = this.getMetricForValidate();
    TSSubQuery sub = q.getQueries().get(0);
    sub.setInterpolationTimeLimit("itl-7m");
    sub.setHbaseTimeExtension("ext-3m.5s");
    q.validateAndSetQuery();
    TSDB mockTsdb = PowerMockito.mock(TSDB.class);
    Query mockQuery = mock(Query.class);
    when(mockTsdb.newQuery()).thenReturn(mockQuery);
    Query[] returnedQueries = q.buildQueries(mockTsdb);
    assertEquals(1, returnedQueries.length);
    assertSame(mockQuery, returnedQueries[0]);
    verify(mockQuery).setStartTime(1356998400000L);
    verify(mockQuery).setEndTime(1356998460000L);
    verify(mockQuery).downsample(300000, sub.downsampler());
    verify(mockQuery).setTimeSeries("sys.cpu.0", sub.getTags(),
                                    sub.aggregator(), sub.getRate());
    verify(mockQuery).setInterpolationTimeLimit(420000);
    verify(mockQuery).setHbaseTimeStartExtensionMillis(180000);
    verify(mockQuery).setHbaseTimeEndExtensionMillis(5000);
  }

  private TSQuery getMetricForValidate() {
    final TSQuery query = new TSQuery();
    query.setStart("1356998400");
    query.setEnd("1356998460");
    query.addSubQuery(TestTSSubQuery.getMetricForValidate());
    return query;
  }
}
