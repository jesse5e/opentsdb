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
package net.opentsdb.tsd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.Mockito.spy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonGenerationException;
import com.fasterxml.jackson.core.JsonGenerator;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.stumbleupon.async.Deferred;

import net.opentsdb.core.DataPoint;
import net.opentsdb.core.DataPoints;
import net.opentsdb.core.MutableDataPoint;
import net.opentsdb.core.SeekableView;
import net.opentsdb.core.SeekableViewsForTest;
import net.opentsdb.core.TSDB;
import net.opentsdb.core.TSQuery;
import net.opentsdb.core.TestTSSubQuery;
import net.opentsdb.meta.Annotation;
import net.opentsdb.tsd.HttpJsonSerializer.ForTesting;
import net.opentsdb.utils.Config;
import net.opentsdb.utils.JSON;

import org.jboss.netty.buffer.ChannelBuffer;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

/**
 * Unit tests for the JSON serializer.
 * <b>Note:</b> Tests for the default error handlers are in the TestHttpQuery
 * class
 */
@RunWith(PowerMockRunner.class)
@PrepareForTest({TSDB.class, Config.class, HttpQuery.class})
public final class TestHttpJsonSerializer {

  private static final long BASE_TIME = 1356998400000L;
  private static final DataPoint[] DATA_POINTS = new DataPoint[] {
    MutableDataPoint.ofLongValue(BASE_TIME, 40),
    MutableDataPoint.ofLongValue(BASE_TIME + 2000000, 50),
    MutableDataPoint.ofLongValue(BASE_TIME + 3600000, 40),
    MutableDataPoint.ofLongValue(BASE_TIME + 3700000, 30)
  };

  private TSDB tsdb = null;

  @Before
  public void before() throws Exception {
    tsdb = NettyMocks.getMockedHTTPTSDB();
  }
  
  @Test
  public void constructorDefault() {
    assertNotNull(new HttpJsonSerializer());
  }
  
  @Test
  public void constructorQuery() {
    HttpQuery query = NettyMocks.getQuery(tsdb, "");
    assertNotNull(new HttpJsonSerializer(query));
  }
  
  @Test
  public void shutdown() {
    assertNotNull(new HttpJsonSerializer().shutdown());
  }
  
  @Test
  public void version() {
    assertEquals("2.0.0", new HttpJsonSerializer().version());
  }
  
  @Test
  public void shortName() {
    assertEquals("json", new HttpJsonSerializer().shortName());
  }
  
  @Test
  public void requestContentType() {
    HttpJsonSerializer serdes = new HttpJsonSerializer();
    assertEquals("application/json", serdes.requestContentType());
  }
  
  @Test
  public void responseContentType() {
    HttpJsonSerializer serdes = new HttpJsonSerializer();
    assertEquals("application/json; charset=UTF-8", serdes.responseContentType());
  }
  
  @Test
  public void parseSuggestV1() throws Exception {
    HttpQuery query = NettyMocks.postQuery(tsdb, "", 
        "{\"type\":\"metrics\",\"q\":\"\"}", "");
    HttpJsonSerializer serdes = new HttpJsonSerializer(query);
    HashMap<String, String> map = serdes.parseSuggestV1();
    assertNotNull(map);
    assertEquals("metrics", map.get("type"));
  }
  
  @Test (expected = BadRequestException.class)
  public void parseSuggestV1NoContent() throws Exception {
    HttpQuery query = NettyMocks.postQuery(tsdb, "", 
        null, "");
    HttpJsonSerializer serdes = new HttpJsonSerializer(query);
    serdes.parseSuggestV1();
  }
  
  @Test (expected = BadRequestException.class)
  public void parseSuggestV1EmptyContent() throws Exception {
    HttpQuery query = NettyMocks.postQuery(tsdb, "", 
        "", "");
    HttpJsonSerializer serdes = new HttpJsonSerializer(query);
    serdes.parseSuggestV1();
  }
  
  @Test (expected = BadRequestException.class)
  public void parseSuggestV1NotJSON() throws Exception {
    HttpQuery query = NettyMocks.postQuery(tsdb, "", 
        "This is unparsable", "");
    HttpJsonSerializer serdes = new HttpJsonSerializer(query);
    serdes.parseSuggestV1();
  }
  
  @Test
  public void formatSuggestV1() throws Exception {
    HttpQuery query = NettyMocks.getQuery(tsdb, "");
    HttpJsonSerializer serdes = new HttpJsonSerializer(query);
    final List<String> metrics = new ArrayList<String>();
    metrics.add("sys.cpu.0.system"); 
    ChannelBuffer cb = serdes.formatSuggestV1(metrics);
    assertNotNull(cb);
    assertEquals("[\"sys.cpu.0.system\"]", 
        cb.toString(Charset.forName("UTF-8")));
  }
  
  @Test
  public void formatSuggestV1JSONP() throws Exception {
    HttpQuery query = NettyMocks.getQuery(tsdb, "?jsonp=func");
    HttpJsonSerializer serdes = new HttpJsonSerializer(query);
    final List<String> metrics = new ArrayList<String>();
    metrics.add("sys.cpu.0.system"); 
    ChannelBuffer cb = serdes.formatSuggestV1(metrics);
    assertNotNull(cb);
    assertEquals("func([\"sys.cpu.0.system\"])", 
        cb.toString(Charset.forName("UTF-8")));
  }
  
  @Test (expected = IllegalArgumentException.class)
  public void formatSuggestV1Null() throws Exception {
    HttpQuery query = NettyMocks.getQuery(tsdb, "");
    HttpJsonSerializer serdes = new HttpJsonSerializer(query);
    serdes.formatSuggestV1(null);
  }
  
  @Test
  public void formatSerializersV1() throws Exception {
    HttpQuery.initializeSerializerMaps(tsdb);
    HttpQuery query = NettyMocks.getQuery(tsdb, "");
    HttpJsonSerializer serdes = new HttpJsonSerializer(query);
    assertEquals("[{\"formatters\":",
        serdes.formatSerializersV1().toString(Charset.forName("UTF-8"))
        .substring(0, 15));
  }

  @Test
  public void testWriteJsonDataPoints_map() throws JsonGenerationException,
                                                   IOException {
    SeekableView source = spy(SeekableViewsForTest.fromArray(DATA_POINTS));
    TSQuery data_query = getTestDataQuery();
    DataPoint firstDp = source.next();
    SeekableView dpIterator = source;
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    JsonGenerator json = JSON.getFactory().createGenerator(output);
    boolean as_arrays = false;
    ForTesting testing = new HttpJsonSerializer.ForTesting();
    testing.writeJsonDataPoints(json, data_query, firstDp, dpIterator,
                                as_arrays);
    json.close();
    String expected = "{\"1356998400\":40.0,\"1357000400\":50.0," +
                      "\"1357002000\":40.0}";
    assertEquals(expected, output.toString());
  }

  @Test
  public void testWriteJsonDataPoints_array() throws JsonGenerationException,
                                                     IOException {
    SeekableView source = spy(SeekableViewsForTest.fromArray(DATA_POINTS));
    TSQuery data_query = getTestDataQuery();
    DataPoint firstDp = source.next();
    SeekableView dpIterator = source;
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    JsonGenerator json = JSON.getFactory().createGenerator(output);
    boolean as_arrays = true;
    ForTesting testing = new HttpJsonSerializer.ForTesting();
    testing.writeJsonDataPoints(json, data_query, firstDp, dpIterator,
                                as_arrays);
    json.close();
    String expected = "[1356998400,40.0,1357000400,50.0,1357002000,40.0]";
    assertEquals(expected, output.toString());
  }

  @Test
  public void testFormatQueryV1_map() throws IOException {
    SeekableView source = spy(SeekableViewsForTest.fromArray(DATA_POINTS));
    TSQuery data_query = getTestDataQuery();
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    JsonGenerator json = JSON.getFactory().createGenerator(output);
    ForTesting testing = new HttpJsonSerializer.ForTesting();
    HttpQuery query = NettyMocks.getQuery(tsdb,
                                          "?start=1356998400&end=1357002000");
    List<DataPoints[]> results = Lists.newArrayList();
    DataPoints[] mockDataPointsArray = new DataPoints[] {
        createMockDataPoints("first", source, 4)
    };
    results.add(mockDataPointsArray );
    List<Annotation> globals = null;
    testing.internalFormatQueryV1(json, query, data_query, results, globals);
    json.close();
    String expected =
        "[{\"metric\":\"first\",\"tags\":{\"first.TagKey\":" +
        "\"first.TagValue\"},\"aggregateTags\":[\"first.AggTags\"]," +
        "\"dps\":{\"1356998400\":40.0,\"1357000400\":50.0,\"1357002000\":40.0}}]";
    assertEquals(expected, output.toString());
  }

  @Test
  public void testFormatQueryV1_arrays() throws IOException {
    SeekableView source = spy(SeekableViewsForTest.fromArray(DATA_POINTS));
    TSQuery data_query = getTestDataQuery();
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    JsonGenerator json = JSON.getFactory().createGenerator(output);
    ForTesting testing = new HttpJsonSerializer.ForTesting();
    HttpQuery query = NettyMocks.getQuery(tsdb,
        "?start=1356998400&end=1357002000&arrays");
    List<DataPoints[]> results = Lists.newArrayList();
    DataPoints[] mockDataPointsArray = new DataPoints[] {
        createMockDataPoints("first", source, 4)
    };
    results.add(mockDataPointsArray );
    List<Annotation> globals = null;
    testing.internalFormatQueryV1(json, query, data_query, results, globals);
    json.close();
    String expected =
        "[{\"metric\":\"first\",\"tags\":{\"first.TagKey\":" +
        "\"first.TagValue\"},\"aggregateTags\":[\"first.AggTags\"]," +
        "\"dps\":[1356998400,40.0,1357000400,50.0,1357002000,40.0]}]";
    assertEquals(expected, output.toString());
  }

  @Test
  public void testFormatQueryV1_ignoreEmptyTimeSeries() throws IOException {
    SeekableView source = spy(SeekableViewsForTest.fromArray(
        new DataPoint[] {
            MutableDataPoint.ofLongValue(BASE_TIME + 30000, 40),
        }));
    SeekableView emptySource = spy(SeekableViewsForTest.fromArray(
        new DataPoint[] {
            MutableDataPoint.ofLongValue(BASE_TIME - 20000, 40),
        }));
    TSQuery data_query = getTestDataQuery();
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    JsonGenerator json = JSON.getFactory().createGenerator(output);
    ForTesting testing = new HttpJsonSerializer.ForTesting();
    HttpQuery query = NettyMocks.getQuery(tsdb,
                                          "?start=1356998400&end=1357002000");
    List<DataPoints[]> results = Lists.newArrayList();
    DataPoints[] mockDataPointsArray = new DataPoints[] {
        createMockDataPoints("empty", emptySource, 1),
        createMockDataPoints("first", source, 1)
    };
    results.add(mockDataPointsArray );
    List<Annotation> globals = null;
    testing.internalFormatQueryV1(json, query, data_query, results, globals);
    json.close();
    String expected =
        "[{\"metric\":\"first\",\"tags\":{\"first.TagKey\":" +
        "\"first.TagValue\"},\"aggregateTags\":[\"first.AggTags\"]," +
        "\"dps\":{\"1356998430\":40.0}}]";
    assertEquals(expected, output.toString());
  }

  private TSQuery getTestDataQuery() {
    final TSQuery query = new TSQuery();
    query.setStart(Long.toString(1356998400L));
    query.setEnd(Long.toString(1356998400L + 3600));
    query.addSubQuery(TestTSSubQuery.getMetricForValidate());
    query.validateAndSetQuery();
    return query;
  }

  private DataPoints createMockDataPoints(final String metricName,
                                          final SeekableView iterator,
                                          final int size) {
    return new DataPoints() {

      @Override
      public String metricName() {
        return metricName;
      }

      @Override
      public Deferred<String> metricNameAsync() {
        return null;
      }

      @Override
      public Map<String, String> getTags() {
        Map<String, String> tags = Maps.newTreeMap();
        tags.put(metricName + ".TagKey", metricName + ".TagValue");
        return tags ;
      }

      @Override
      public Deferred<Map<String, String>> getTagsAsync() {
        return null;
      }

      @Override
      public List<String> getAggregatedTags() {
        return Lists.newArrayList(metricName + ".AggTags");
      }

      @Override
      public Deferred<List<String>> getAggregatedTagsAsync() {
        return null;
      }

      @Override
      public List<String> getTSUIDs() {
        return null;
      }

      @Override
      public List<Annotation> getAnnotations() {
        return null;
      }

      @Override
      public int size() {
        return size;
      }

      @Override
      public int aggregatedSize() {
        return size;
      }

      @Override
      public SeekableView iterator() {
        return iterator;
      }

      @Override
      public long timestamp(int i) {
        throw new UnsupportedOperationException();
      }

      @Override
      public boolean isInteger(int i) {
        throw new UnsupportedOperationException();
      }

      @Override
      public long longValue(int i) {
        throw new UnsupportedOperationException();
      }

      @Override
      public double doubleValue(int i) {
        throw new UnsupportedOperationException();
      }
    };
  }
}
