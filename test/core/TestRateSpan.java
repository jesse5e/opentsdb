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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import java.util.NoSuchElementException;

import org.junit.Before;
import org.junit.Test;


/**
 * Tests {@link RateSpan}.
 */
public class TestRateSpan {

  private static final DataPoint[] DATA_POINTS = new DataPoint[] {
    MutableDataPoint.ofDoubleValue(1356998400000L, 40.0),
    MutableDataPoint.ofLongValue(1356998400000L + 2000000, 50),
    MutableDataPoint.ofLongValue(1357002000000L, 40),
    MutableDataPoint.ofDoubleValue(1357002000000L + 5000, 50.0),
    MutableDataPoint.ofLongValue(1357005600000L, 40),
    MutableDataPoint.ofDoubleValue(1357005600000L + 2000000, 50.0)
  };

  private static final DataPoint[] RATE_DATA_POINTS = new DataPoint[] {
    MutableDataPoint.ofDoubleValue(1356998400000L + 2000000, 10.0 / 2000.0),
    MutableDataPoint.ofDoubleValue(1357002000000L,
                                   -10.0 / (1357002000L - 1356998400L - 2000)),
    MutableDataPoint.ofDoubleValue(1357002000000L + 5000, 10.0 / 5.0),
    MutableDataPoint.ofDoubleValue(1357005600000L,
                                   -10.0 / (1357005600L - 1357002005L)),
    MutableDataPoint.ofDoubleValue(1357005600000L + 2000000, 10.0 / 2000.0)
  };

  private static final DataPoint[] RATES_AFTER_SEEK = new DataPoint[] {
    RATE_DATA_POINTS[2], RATE_DATA_POINTS[3], RATE_DATA_POINTS[4]
  };

  private static final long COUNTER_MAX = 70;
  private static final DataPoint[] RATES_FOR_COUNTER = new DataPoint[] {
    MutableDataPoint.ofDoubleValue(1356998400000L + 2000000, 10.0 / 2000.0),
    MutableDataPoint.ofDoubleValue(1357002000000L, (40.0 + 20) / 1600.0),
    MutableDataPoint.ofDoubleValue(1357002000000L + 5000, 10.0 / 5.0),
    MutableDataPoint.ofDoubleValue(1357005600000L, (40.0 + 20) / 3595),
    MutableDataPoint.ofDoubleValue(1357005600000L + 2000000, 10.0 / 2000.0)
  };

  private SeekableView source;
  private RateOptions options;

  @Before
  public void setup() {
    source = spy(SeekableViewsForTest.fromArray(DATA_POINTS));
    options = new RateOptions();
  }

  @Test
  public void testRateSpan() {
    RateSpan rateSpan = new RateSpan(source, options);
    verify(source, never()).next();
    assertTrue(rateSpan.hasNext());
    DataPoint dp = rateSpan.next();
    assertFalse(dp.isInteger());
    assertEquals(1356998400000L + 2000000, dp.timestamp());
    assertEquals(10.0 / 2000.0, dp.doubleValue(), 0);
  }

  @Test
  public void testNext_iterateAll() {
    RateSpan rateSpan = new RateSpan(source, options);
    for (DataPoint rate : RATE_DATA_POINTS) {
      assertTrue(rateSpan.hasNext());
      assertTrue(rateSpan.hasNext());
      DataPoint dp = rateSpan.next();
      String msg = String.format("expected rate = '%s' ", rate);
      assertFalse(msg, dp.isInteger());
      assertEquals(msg, rate.timestamp(), dp.timestamp());
      assertEquals(msg, rate.doubleValue(), dp.doubleValue(), 0.0000001);
    }
    assertFalse(rateSpan.hasNext());
    assertFalse(rateSpan.hasNext());
  }

  @Test(expected = UnsupportedOperationException.class)
  public void testRemove() {
    RateSpan rateSpan = new RateSpan(source, options);
    rateSpan.remove();
  }

  @Test(expected = NoSuchElementException.class)
  public void testNext_noMoreData() {
    source = SeekableViewsForTest.fromArray(new DataPoint[] {
        MutableDataPoint.ofLongValue(1356998400000L, 40)
    });
    RateSpan rateSpan = new RateSpan(source, options);
    assertFalse(rateSpan.hasNext());
    rateSpan.next();
  }

  @Test
  public void testSeek() {
    RateSpan rateSpan = new RateSpan(source, options);
    rateSpan.seek(1357002000000L);
    verify(source, never()).next();
    for (DataPoint rate : RATES_AFTER_SEEK) {
      assertTrue(rateSpan.hasNext());
      assertTrue(rateSpan.hasNext());
      DataPoint dp = rateSpan.next();
      String msg = String.format("expected rate = '%s' ", rate);
      assertFalse(msg, dp.isInteger());
      assertEquals(msg, rate.timestamp(), dp.timestamp());
      assertEquals(msg, rate.doubleValue(), dp.doubleValue(), 0.0000001);
    }
    assertFalse(rateSpan.hasNext());
    assertFalse(rateSpan.hasNext());
  }

  @Test
  public void testMoveToNextRate_duplicatedTimestamp() {
    source = SeekableViewsForTest.fromArray(new DataPoint[] {
        MutableDataPoint.ofLongValue(1356998400000L, 40),
        MutableDataPoint.ofLongValue(1356998400000L + 2000000, 50),
        MutableDataPoint.ofLongValue(1356998400000L + 2000000, 50)
    });
    RateSpan rateSpan = new RateSpan(source, options);
    assertTrue(rateSpan.hasNext());
    DataPoint dp = rateSpan.next();
    assertFalse(dp.isInteger());
    assertEquals(1356998400000L + 2000000, dp.timestamp());
    assertEquals(10.0 / 2000.0, dp.doubleValue(), 0);
    assertTrue(rateSpan.hasNext());
    // Repeats the previous rate if the next data point is actually older
    // than the previous one.
    dp = rateSpan.next();
    assertFalse(dp.isInteger());
    assertEquals(1356998400000L + 2000000, dp.timestamp());
    assertEquals(10.0 / 2000.0, dp.doubleValue(), 0);
    assertFalse(rateSpan.hasNext());
  }

  @Test
  public void testCalculateDelta_bigLongValues() {
    source = SeekableViewsForTest.fromArray(new DataPoint[] {
        MutableDataPoint.ofLongValue(1356998400000L, Long.MAX_VALUE - 100),
        MutableDataPoint.ofLongValue(1356998500000L, Long.MAX_VALUE - 20)
    });
    RateSpan rateSpan = new RateSpan(source, options);
    assertTrue(rateSpan.hasNext());
    DataPoint dp = rateSpan.next();
    assertFalse(dp.isInteger());
    assertEquals(1356998500000L, dp.timestamp());
    assertEquals(0.8, dp.doubleValue(), 0);
    assertFalse(rateSpan.hasNext());
  }

  @Test
  public void testNext_counter() {
    options = new RateOptions(true, COUNTER_MAX,
                              RateOptions.DEFAULT_RESET_VALUE);
    RateSpan rateSpan = new RateSpan(source, options);
    verify(source, never()).next();
    for (DataPoint rate : RATES_FOR_COUNTER) {
      assertTrue(rateSpan.hasNext());
      assertTrue(rateSpan.hasNext());
      DataPoint dp = rateSpan.next();
      String msg = String.format("expected rate = '%s' ", rate);
      assertFalse(msg, dp.isInteger());
      assertEquals(msg, rate.timestamp(), dp.timestamp());
      assertEquals(msg, rate.doubleValue(), dp.doubleValue(), 0.0000001);
    }
    assertFalse(rateSpan.hasNext());
    assertFalse(rateSpan.hasNext());
  }

  @Test
  public void testNext_counterLongMax() {
    options = new RateOptions(true, Long.MAX_VALUE, 0);
    source = SeekableViewsForTest.fromArray(new DataPoint[] {
        MutableDataPoint.ofLongValue(1356998430000L, Long.MAX_VALUE - 55),
        MutableDataPoint.ofLongValue(1356998460000L, Long.MAX_VALUE - 25),
        MutableDataPoint.ofLongValue(1356998490000L, 5),
    });
    DataPoint[] rates = new DataPoint[] {
        MutableDataPoint.ofDoubleValue(1356998460000L, 1),
        MutableDataPoint.ofDoubleValue(1356998490000L, 1)
    };
    RateSpan rateSpan = new RateSpan(source, options);
    for (DataPoint rate : rates) {
      assertTrue(rateSpan.hasNext());
      DataPoint dp = rateSpan.next();
      String msg = String.format("expected rate = '%s' ", rate);
      assertFalse(msg, dp.isInteger());
      assertEquals(msg, rate.timestamp(), dp.timestamp());
      assertEquals(msg, rate.doubleValue(), dp.doubleValue(), 0.0000001);
    }
    assertFalse(rateSpan.hasNext());
  }

  @Test
  public void testNext_counterWithResetValue() {
    final long RESET_VALUE = 1;
    source = SeekableViewsForTest.fromArray(new DataPoint[] {
        MutableDataPoint.ofLongValue(1356998400000L, 40),
        MutableDataPoint.ofLongValue(1356998401000L, 50),
        MutableDataPoint.ofLongValue(1356998402000L, 40)
    });
    DataPoint[] rates = new DataPoint[] {
        MutableDataPoint.ofDoubleValue(1356998401000L, 10),
        // Not 60 because the change is too big compared to the reset value.
        MutableDataPoint.ofDoubleValue(1356998402000L, 0)
    };
    options = new RateOptions(true, COUNTER_MAX, RESET_VALUE);
    RateSpan rateSpan = new RateSpan(source, options);
    for (DataPoint rate : rates) {
      assertTrue(rateSpan.hasNext());
      assertTrue(rateSpan.hasNext());
      DataPoint dp = rateSpan.next();
      String msg = String.format("expected rate = '%s' ", rate);
      assertFalse(msg, dp.isInteger());
      assertEquals(msg, rate.timestamp(), dp.timestamp());
      assertEquals(msg, rate.doubleValue(), dp.doubleValue(), 0.0000001);
    }
    assertFalse(rateSpan.hasNext());
    assertFalse(rateSpan.hasNext());
  }
}
