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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import net.opentsdb.core.Aggregators.Interpolation;
import net.opentsdb.utils.DateTime;
import org.junit.Before;
import org.junit.Test;


/** Tests {@link SpanGroup.AggregationIter}. */
public class TestSpanGroupAggregationIter {

  private static final DataPoint[] DATA_POINTS_1 = new DataPoint[] {
    MutableDataPoint.ofLongValue(1356998400000L, 40),
    MutableDataPoint.ofLongValue(1356998400000L + 10000, 50),
    MutableDataPoint.ofLongValue(1356998400000L + 30000, 70)
  };
  private static final DataPoint[] DATA_POINTS_2 = new DataPoint[] {
    MutableDataPoint.ofLongValue(1356998400000L + 10000, 40),
    MutableDataPoint.ofLongValue(1356998400000L + 20000, 50)
  };

  private SeekableView[] iterators;
  private long start_time;
  private long end_time;
  private boolean rate;
  private Aggregator aggregator;
  private long interpolationTimeLimitMillis;
  private Interpolation interpolation;


  @Before
  public void setUp() {
    start_time = 1356998400L * 1000;
    end_time = 1356998500L * 1000;
    rate = false;
    aggregator = Aggregators.SUM;
    interpolationTimeLimitMillis = DateTime.parseDuration("1h");
    interpolation = Interpolation.LERP;
  }

  @Test
  public void testSpanGroup_singleSpan() {
    iterators = new SeekableView[] {
        SeekableViewsForTest.fromArray(DATA_POINTS_1)
    };
    SpanGroup.AggregationIter sgai = new SpanGroup.AggregationIter(iterators,
        start_time, end_time, aggregator, interpolation, interpolationTimeLimitMillis, rate);
    for (DataPoint expected: DATA_POINTS_1) {
      assertTrue(sgai.hasNext());
      DataPoint dp = sgai.next();
      assertEquals(expected.timestamp(), dp.timestamp());
      assertEquals(expected.longValue(), dp.longValue());
    }
    assertFalse(sgai.hasNext());
  }

  @Test
  public void testSpanGroup_doubleSpans() {
    iterators = new SeekableView[] {
        SeekableViewsForTest.fromArray(DATA_POINTS_1),
        SeekableViewsForTest.fromArray(DATA_POINTS_2),
    };
    SpanGroup.AggregationIter sgai = new SpanGroup.AggregationIter(iterators,
        start_time, end_time, aggregator, interpolation, interpolationTimeLimitMillis, rate);
    DataPoint[] expectedDataPoints = new DataPoint[] {
        MutableDataPoint.ofLongValue(1356998400000L, 40),
        MutableDataPoint.ofLongValue(1356998400000L + 10000, 50 + 40),
        // 60 is the interpolated value.
        MutableDataPoint.ofLongValue(1356998400000L + 20000, 50 + 60),
        MutableDataPoint.ofLongValue(1356998400000L + 30000, 70)
      };
    for (DataPoint expected: expectedDataPoints) {
      assertTrue(sgai.hasNext());
      DataPoint dp = sgai.next();
      assertEquals(expected.timestamp(), dp.timestamp());
      assertEquals(expected.longValue(), dp.longValue());
    }
    assertFalse(sgai.hasNext());
  }

  private SeekableView[] createSeekableViews(int numViews,
                                             final long startTimeMillis,
                                             final long endTimeMillis,
                                             final int numPointsPerSpan) {
    SeekableView[] views = new SeekableView[numViews];
    final long samplePeriodMillis = 5000;
    final long incrementMillis =
        (endTimeMillis - startTimeMillis) / numViews;
    long currentTime = startTimeMillis;
    for (int i = 0; i < numViews; ++i) {
      final SeekableView view = SeekableViewsForTest.generator(
          currentTime, samplePeriodMillis, numPointsPerSpan, true);
      views[i] = new Downsampler(view, (int)DateTime.parseDuration("10s"),
                                 Aggregators.AVG);
      currentTime += incrementMillis;
    }
    assertEquals(numViews, views.length);
    return views;
  }

  private void testAggregatingMultiSpans(int numViews) {
    // Microbenchmark to measure the performance of AggregationIter.
    iterators = createSeekableViews(numViews, 1356990000000L, 1356993600000L,
                                    100);
    SpanGroup.AggregationIter sgai = new SpanGroup.AggregationIter(iterators,
        1356990000000L, 1356993600000L, aggregator, interpolation,
        interpolationTimeLimitMillis, rate);
    final long startTimeNano = System.nanoTime();
    long totalDataPoints = 0;
    long timestampCheckSum = 0;
    double valueCheckSum = 0;
    while (sgai.hasNext()) {
      DataPoint dp = sgai.next();
      ++totalDataPoints;
      timestampCheckSum += dp.timestamp();
      valueCheckSum += dp.doubleValue();
    }
    final long finishTimeNano = System.nanoTime();
    final double elapsed = (finishTimeNano - startTimeNano) / 1000000000.0;
    System.out.println(String.format("%f seconds, %d data points, (%d, %g)" +
                                     "for %d views",
                                     elapsed, totalDataPoints,
                                     timestampCheckSum, valueCheckSum,
                                     numViews));
    assertTrue(elapsed < 10.0);
  }

  @Test
  public void testAggregating10000Spans() {
    testAggregatingMultiSpans(10000);
  }

  @Test
  public void testAggregating500000Spans() {
    testAggregatingMultiSpans(500000);
  }
}
