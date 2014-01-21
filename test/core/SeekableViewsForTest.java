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

import java.util.NoSuchElementException;
import org.junit.Test;

/** Helper class to mock SeekableView. */
public class SeekableViewsForTest {

  /**
   * Creates a {@link SeekableView} object to iterate the given data points.
   * @param dataPoints Test data.
   * @return A {@link SeekableView} object
   */
  public static SeekableView fromArray(final DataPoint[] dataPoints) {
    return new MockSeekableView(dataPoints);
  }

  /**
   * Creates a {@link SeekableView} that generates a sequence of data points.
   * @param startTime Starting timestamp
   * @param samplePeriod Average sample period of data points
   * @param numDatapoints Total number of data points to generate
   * @param isInteger True to generate a sequence of integer data points.
   * @return A {@link SeekableView} object
   */
  public static SeekableView generator(final long startTime,
                                       final long samplePeriod,
                                       final int numDatapoints,
                                       final boolean isInteger) {
    return new DataPointGenerator(startTime, samplePeriod, numDatapoints,
                                  isInteger);
  }

  /** Iterates an array of data points. */
  private static class MockSeekableView implements SeekableView {

    private final DataPoint[] dataPoints;
    private int index = 0;

    MockSeekableView(final DataPoint[] dataPoints) {
      this.dataPoints = dataPoints;
    }

    @Override
    public boolean hasNext() {
      return dataPoints.length > index;
    }

    @Override
    public DataPoint next() {
      if (hasNext()) {
        return dataPoints[index++];
      }
      throw new NoSuchElementException("no more values");
    }

    @Override
    public void remove() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void seek(long timestamp) {
      for (index = 0; index < dataPoints.length; ++index) {
        if (dataPoints[index].timestamp() >= timestamp) {
          break;
        }
      }
    }
  }

  /** Generates a sequence of data points. */
  private static class DataPointGenerator implements SeekableView {

    private final long startTimeMillis;
    private final long samplePeriodMillis;
    private final int numDatapoints;
    private final boolean isInteger;
    private final MutableDataPoint currentData = new MutableDataPoint();
    private int current = 0;

    DataPointGenerator(final long startTimeMillis, final long samplePeriodMillis,
                       final int numDatapoints, final boolean isInteger) {
      this.startTimeMillis = startTimeMillis;
      this.samplePeriodMillis = samplePeriodMillis;
      this.numDatapoints = numDatapoints;
      this.isInteger = isInteger;
      rewind();
    }

    @Override
    public boolean hasNext() {
      return current < numDatapoints;
    }

    @Override
    public DataPoint next() {
      if (hasNext()) {
        generateData();
        ++current;
        return currentData;
      }
      throw new NoSuchElementException("no more values");
    }

    @Override
    public void remove() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void seek(long timestamp) {
      rewind();
      current = (int)((timestamp -1 - startTimeMillis) / samplePeriodMillis);
      if (current < 0) {
        current = 0;
      }
      while (generateTimestamp() < timestamp) {
        ++current;
      }
    }

    private void rewind() {
      current = 0;
      generateData();
    }

    private void generateData() {
      if (isInteger) {
        currentData.resetWithLongValue(generateTimestamp(), current);
      } else {
        currentData.resetWithDoubleValue(generateTimestamp(), current);
      }
    }

    private long generateTimestamp() {
      long timestamp = startTimeMillis + samplePeriodMillis * current;
      return timestamp + (((current % 2) == 0) ? -1000 : 1000);
    }
  }

  @Test
  public void testDataPointGenerator() {
    DataPointGenerator dpg = new DataPointGenerator(100000, 10000, 5, true);
    DataPoint[] expectedDataPoints = new DataPoint[] {
        MutableDataPoint.ofLongValue(99000, 0),
        MutableDataPoint.ofLongValue(111000, 1),
        MutableDataPoint.ofLongValue(119000, 2),
        MutableDataPoint.ofLongValue(131000, 3),
        MutableDataPoint.ofLongValue(139000, 4),
    };
    for (DataPoint expected: expectedDataPoints) {
      assertTrue(dpg.hasNext());
      DataPoint dp = dpg.next();
      assertEquals(expected.timestamp(), dp.timestamp());
      assertEquals(expected.longValue(), dp.longValue());
    }
    assertFalse(dpg.hasNext());
  }

  @Test
  public void testDataPointGenerator_double() {
    DataPointGenerator dpg = new DataPointGenerator(100000, 10000, 5, false);
    DataPoint[] expectedDataPoints = new DataPoint[] {
        MutableDataPoint.ofDoubleValue(99000, 0),
        MutableDataPoint.ofDoubleValue(111000, 1),
        MutableDataPoint.ofDoubleValue(119000, 2),
        MutableDataPoint.ofDoubleValue(131000, 3),
        MutableDataPoint.ofDoubleValue(139000, 4),
    };
    for (DataPoint expected: expectedDataPoints) {
      assertTrue(dpg.hasNext());
      DataPoint dp = dpg.next();
      assertEquals(expected.timestamp(), dp.timestamp());
      assertEquals(expected.doubleValue(), dp.doubleValue(), 0);
    }
    assertFalse(dpg.hasNext());
  }

  @Test
  public void testDataPointGenerator_seek() {
    DataPointGenerator dpg = new DataPointGenerator(100000, 10000, 5, true);
    dpg.seek(119000);
    DataPoint[] expectedDataPoints = new DataPoint[] {
        MutableDataPoint.ofLongValue(119000, 2),
        MutableDataPoint.ofLongValue(131000, 3),
        MutableDataPoint.ofLongValue(139000, 4),
    };
    for (DataPoint expected: expectedDataPoints) {
      assertTrue(dpg.hasNext());
      DataPoint dp = dpg.next();
      assertEquals(expected.timestamp(), dp.timestamp());
      assertEquals(expected.longValue(), dp.longValue());
    }
    assertFalse(dpg.hasNext());
  }

  @Test
  public void testDataPointGenerator_seekToFirst() {
    DataPointGenerator dpg = new DataPointGenerator(100000, 10000, 5, true);
    dpg.seek(100000);
    DataPoint[] expectedDataPoints = new DataPoint[] {
        MutableDataPoint.ofLongValue(111000, 1),
        MutableDataPoint.ofLongValue(119000, 2),
        MutableDataPoint.ofLongValue(131000, 3),
        MutableDataPoint.ofLongValue(139000, 4),
    };
    for (DataPoint expected: expectedDataPoints) {
      assertTrue(dpg.hasNext());
      DataPoint dp = dpg.next();
      assertEquals(expected.timestamp(), dp.timestamp());
      assertEquals(expected.longValue(), dp.longValue());
    }
    assertFalse(dpg.hasNext());
  }

  @Test
  public void testDataPointGenerator_seekToSecond() {
    DataPointGenerator dpg = new DataPointGenerator(100000, 10000, 5, true);
    dpg.seek(100001);
    DataPoint[] expectedDataPoints = new DataPoint[] {
        MutableDataPoint.ofLongValue(111000, 1),
        MutableDataPoint.ofLongValue(119000, 2),
        MutableDataPoint.ofLongValue(131000, 3),
        MutableDataPoint.ofLongValue(139000, 4),
    };
    for (DataPoint expected: expectedDataPoints) {
      assertTrue(dpg.hasNext());
      DataPoint dp = dpg.next();
      assertEquals(expected.timestamp(), dp.timestamp());
      assertEquals(expected.longValue(), dp.longValue());
    }
    assertFalse(dpg.hasNext());
  }
}