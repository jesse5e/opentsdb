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

import java.util.List;

import org.junit.Before;
import org.junit.Test;

import com.google.common.collect.Lists;


/** Tests {@link Downsampler}. */
public class TestDownsampler {

  private static final DataPoint[] DATA_POINTS = new DataPoint[] {
    MutableDataPoint.ofLongValue(1356998400000L, 40),
    MutableDataPoint.ofLongValue(1356998400000L + 2000000, 50),
    MutableDataPoint.ofLongValue(1357002000000L, 40),
    MutableDataPoint.ofLongValue(1357002000000L + 5000, 50),
    MutableDataPoint.ofLongValue(1357005600000L, 40),
    MutableDataPoint.ofLongValue(1357005600000L + 2000000, 50)
  };
  private static final int THOUSAND_SECONDS = 1000000;  // in milliseconds.
  private static final Aggregator AVG = Aggregators.get("avg");;

  private SeekableView source;
  private Downsampler downsampler;

  @Before
  public void setup() {
    source = SeekableViewsForTest.fromArray(DATA_POINTS);
  }

  @Test
  public void testDownsampler() {
    downsampler = new Downsampler(source, THOUSAND_SECONDS, AVG);
    List<Double> values = Lists.newArrayList();
    List<Long> timestampsInMillis = Lists.newArrayList();
    while (downsampler.hasNext()) {
      DataPoint dp = downsampler.next();
      assertFalse(dp.isInteger());
      values.add(dp.doubleValue());
      timestampsInMillis.add(dp.timestamp());
    }

    assertEquals(5, values.size());
    assertEquals(40, values.get(0).longValue());
    assertEquals(1356998500000L, timestampsInMillis.get(0).longValue());
    assertEquals(50, values.get(1).longValue());
    assertEquals(1357000500000L, timestampsInMillis.get(1).longValue());
    assertEquals(45, values.get(2).longValue());
    assertEquals(1357002500000L, timestampsInMillis.get(2).longValue());
    assertEquals(40, values.get(3).longValue());
    assertEquals(1357005500000L, timestampsInMillis.get(3).longValue());
    assertEquals(50, values.get(4).longValue());
    assertEquals(1357007500000L, timestampsInMillis.get(4).longValue());
  }

  @Test(expected = UnsupportedOperationException.class)
  public void testRemove() {
    new Downsampler(source, THOUSAND_SECONDS, AVG).remove();
  }

  @Test
  public void testSeek() {
    downsampler = new Downsampler(source, THOUSAND_SECONDS, AVG);
    downsampler.seek(1357002000000L);
    List<Double> values = Lists.newArrayList();
    List<Long> timestampsInMillis = Lists.newArrayList();
    while (downsampler.hasNext()) {
      DataPoint dp = downsampler.next();
      assertFalse(dp.isInteger());
      values.add(dp.doubleValue());
      timestampsInMillis.add(dp.timestamp());
    }

    assertEquals(3, values.size());
    assertEquals(45, values.get(0).longValue());
    assertEquals(1357002500000L, timestampsInMillis.get(0).longValue());
    assertEquals(40, values.get(1).longValue());
    assertEquals(1357005500000L, timestampsInMillis.get(1).longValue());
    assertEquals(50, values.get(2).longValue());
    assertEquals(1357007500000L, timestampsInMillis.get(2).longValue());
  }

  @Test
  public void testToString() {
    downsampler = new Downsampler(source, THOUSAND_SECONDS, AVG);
    DataPoint dp = downsampler.next();
    System.out.println(downsampler.toString());
    assertTrue(downsampler.toString().contains(dp.toString()));
  }
}
