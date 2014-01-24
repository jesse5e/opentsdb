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

  private static final long BASE_TIME = 1356998400000L;
  private static final DataPoint[] DATA_POINTS_1 = new DataPoint[] {
    MutableDataPoint.ofLongValue(BASE_TIME, 40),
    MutableDataPoint.ofLongValue(BASE_TIME + 10000, 50),
    MutableDataPoint.ofLongValue(BASE_TIME + 30000, 70)
  };
  private static final DataPoint[] DATA_POINTS_2 = new DataPoint[] {
    MutableDataPoint.ofLongValue(BASE_TIME + 10000, 40),
    MutableDataPoint.ofLongValue(BASE_TIME + 20000, 50)
  };
  final DataPoint[] DATA_5SEC = new DataPoint[] {
      MutableDataPoint.ofDoubleValue(BASE_TIME + 00000L, 1),
      MutableDataPoint.ofDoubleValue(BASE_TIME + 07000L, 1),
      MutableDataPoint.ofDoubleValue(BASE_TIME + 10000L, 1),
      MutableDataPoint.ofDoubleValue(BASE_TIME + 15000L, 1),
      MutableDataPoint.ofDoubleValue(BASE_TIME + 20000L, 1),
      MutableDataPoint.ofDoubleValue(BASE_TIME + 25000L, 1),
      MutableDataPoint.ofDoubleValue(BASE_TIME + 30000L, 1),
      MutableDataPoint.ofDoubleValue(BASE_TIME + 35000L, 1),
      MutableDataPoint.ofDoubleValue(BASE_TIME + 40000L, 1),
      MutableDataPoint.ofDoubleValue(BASE_TIME + 45000L, 1),
      MutableDataPoint.ofDoubleValue(BASE_TIME + 50000L, 1)
  };
  private static final Aggregator AVG = Aggregators.get("avg");;
  private static final Aggregator SUM = Aggregators.get("sum");;

  private SeekableView[] iterators;
  private long startTimeMs;
  private long endTimeMs;
  private boolean rate;
  private Aggregator aggregator;
  private long interpolationTimeLimitMillis;
  private Interpolation interpolation;


  @Before
  public void setUp() {
    startTimeMs = BASE_TIME;
    endTimeMs = BASE_TIME + 100000;
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
        startTimeMs, endTimeMs, aggregator, interpolation,
        interpolationTimeLimitMillis, rate);
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
        startTimeMs, endTimeMs, aggregator, interpolation,
        interpolationTimeLimitMillis, rate);
    DataPoint[] expectedDataPoints = new DataPoint[] {
        MutableDataPoint.ofLongValue(BASE_TIME, 40),
        MutableDataPoint.ofLongValue(BASE_TIME + 10000, 50 + 40),
        // 60 is the interpolated value.
        MutableDataPoint.ofLongValue(BASE_TIME + 20000, 50 + 60),
        MutableDataPoint.ofLongValue(BASE_TIME + 30000, 70)
      };
    for (DataPoint expected: expectedDataPoints) {
      assertTrue(sgai.hasNext());
      DataPoint dp = sgai.next();
      assertEquals(expected.timestamp(), dp.timestamp());
      assertEquals(expected.longValue(), dp.longValue());
    }
    assertFalse(sgai.hasNext());
  }

  @Test
  public void testSpanGroup_manySpans() {
    iterators = new SeekableView[] {
        new Downsampler(SeekableViewsForTest.fromArray(DATA_5SEC), 10000, AVG),
        new Downsampler(SeekableViewsForTest.fromArray(DATA_5SEC), 10000, AVG),
        new Downsampler(SeekableViewsForTest.fromArray(DATA_5SEC), 10000, AVG),
        new Downsampler(SeekableViewsForTest.fromArray(DATA_5SEC), 10000, AVG),
        new Downsampler(SeekableViewsForTest.fromArray(DATA_5SEC), 10000, AVG),
        new Downsampler(SeekableViewsForTest.fromArray(DATA_5SEC), 10000, AVG),
        new Downsampler(SeekableViewsForTest.fromArray(DATA_5SEC), 10000, AVG)
    };
    // Starting at 01 second causes abandoning the data of the first 10 seconds
    // by the first round of ten-second downsampling.
    startTimeMs = BASE_TIME + 1000L;
    endTimeMs = BASE_TIME + 100000;
    SpanGroup.AggregationIter sgai = new SpanGroup.AggregationIter(iterators,
        startTimeMs, endTimeMs, aggregator, interpolation,
        interpolationTimeLimitMillis, rate);
    DataPoint[] expectedDataPoints = new DataPoint[] {
        MutableDataPoint.ofDoubleValue(BASE_TIME + 10000L, 7),
        MutableDataPoint.ofDoubleValue(BASE_TIME + 20000L, 7),
        MutableDataPoint.ofDoubleValue(BASE_TIME + 30000L, 7),
        MutableDataPoint.ofDoubleValue(BASE_TIME + 40000L, 7),
        MutableDataPoint.ofDoubleValue(BASE_TIME + 50000L, 7),
      };
    for (DataPoint expected: expectedDataPoints) {
      assertTrue(sgai.hasNext());
      DataPoint dp = sgai.next();
      assertEquals(expected.timestamp(), dp.timestamp());
      assertEquals(expected.doubleValue(), dp.doubleValue(), 0);
    }
    assertFalse(sgai.hasNext());
  }

  @Test
  public void testSpanGroup_secondRoundDownsampling() {
    iterators = new SeekableView[] {
        new Downsampler(SeekableViewsForTest.fromArray(DATA_5SEC), 10000, AVG),
        new Downsampler(SeekableViewsForTest.fromArray(DATA_5SEC), 10000, AVG),
        new Downsampler(SeekableViewsForTest.fromArray(DATA_5SEC), 10000, AVG),
        new Downsampler(SeekableViewsForTest.fromArray(DATA_5SEC), 10000, AVG),
        new Downsampler(SeekableViewsForTest.fromArray(DATA_5SEC), 10000, AVG),
        new Downsampler(SeekableViewsForTest.fromArray(DATA_5SEC), 10000, AVG),
        new Downsampler(SeekableViewsForTest.fromArray(DATA_5SEC), 10000, AVG)
    };
    startTimeMs = BASE_TIME + 01000L;
    endTimeMs = BASE_TIME + 100000;
    SpanGroup.AggregationIter sgai = new SpanGroup.AggregationIter(iterators,
        startTimeMs, endTimeMs, aggregator, interpolation,
        interpolationTimeLimitMillis, rate);
    Downsampler downsampler = new Downsampler(sgai, 15000, SUM);
    // See the expectedDataPoints at testSpanGroup_manySpans for the output of
    // Aggregator.
    DataPoint[] expectedDataPoints = new DataPoint[] {
        // Aggregator output timestamp: BASE_TIME + 10000
        MutableDataPoint.ofDoubleValue(BASE_TIME + 00000L, 7),
        // Aggregator output timestamp: BASE_TIME + 20000
        MutableDataPoint.ofDoubleValue(BASE_TIME + 15000L, 7),
        // Aggregator output timestamps: BASE_TIME + 30000, BASE_TIME + 40000
        MutableDataPoint.ofDoubleValue(BASE_TIME + 30000L, 14),
        // Aggregator output timestamp: BASE_TIME + 50000
        MutableDataPoint.ofDoubleValue(BASE_TIME + 45000L, 7)
      };
    for (DataPoint expected: expectedDataPoints) {
      assertTrue(downsampler.hasNext());
      DataPoint dp = downsampler.next();
      assertEquals(expected.timestamp(), dp.timestamp());
      assertEquals(String.format("timestamp = %d", dp.timestamp()),
                   expected.doubleValue(), dp.doubleValue(), 0);
    }
    assertFalse(downsampler.hasNext());
  }

  @Test
  public void testSpanGroup_buggySpan() {
    iterators = new SeekableView[] {
        new BuggySeekableView(DATA_POINTS_1)
    };
    SpanGroup.AggregationIter sgai = new SpanGroup.AggregationIter(iterators,
        BASE_TIME + 00000L, endTimeMs, aggregator, interpolation,
        interpolationTimeLimitMillis, rate);
    assertTrue(sgai.hasNext());
    iterators[0] = new BuggySeekableView(DATA_POINTS_1);
    SpanGroup.AggregationIter buggyItr = new SpanGroup.AggregationIter(
        iterators, BASE_TIME + 01000L, endTimeMs, aggregator, interpolation,
        interpolationTimeLimitMillis, rate);
    assertFalse(buggyItr.hasNext());
  }

  @Test
  public void testSpanGroup_emptySpan() {
    final DataPoint[] emptyDataPoints = new DataPoint[] {
    };
    iterators = new SeekableView[] {
        SeekableViewsForTest.fromArray(emptyDataPoints),
        SeekableViewsForTest.fromArray(DATA_POINTS_1),
    };
    SpanGroup.AggregationIter sgai = new SpanGroup.AggregationIter(iterators,
        BASE_TIME + 00000L, endTimeMs, aggregator, interpolation,
        interpolationTimeLimitMillis, rate);
    for (DataPoint expected: DATA_POINTS_1) {
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

  /** Iterates with buggy seek method. */
  private static class BuggySeekableView implements SeekableView {

    private final DataPoint[] dataPoints;
    private int index = 0;

    BuggySeekableView(final DataPoint[] dataPoints) {
      this.dataPoints = dataPoints;
    }

    @Override
    public boolean hasNext() {
      return dataPoints.length > index;
    }

    @Override
    public DataPoint next() {
      return dataPoints[index++];
    }

    @Override
    public void remove() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void seek(long timestamp) {
      index = 0;
    }
  }
}
