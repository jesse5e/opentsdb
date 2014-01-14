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


/**
 * A mutable {@link DataPoint} that stores a value and a timestamp.
 */
public final class MutableDataPoint implements DataPoint {

  // NOTE: Fields are not final to make an instance available to store a new
  // pair of a timestamp and a value to reduce memory burden.
  /** The timestamp of the value. */
  private long timestamp = Long.MAX_VALUE;
  /** True if the value is stored as a long. */
  private boolean isInteger = true;
  /** A double value. */
  private double doubleValue = 0;
  /** A long value. */
  private long longValue = 0;

  /**
   * Resets with a new pair of a timestamp and a double value.
   *
   * @param timestamp A timestamp.
   * @param value A double value.
   */
  public void resetWithDoubleValue(long timestamp, double value) {
    this.timestamp = timestamp;
    this.isInteger = false;
    this.doubleValue = value;
  }

  /**
   * Resets with a new pair of a timestamp and a long value.
   *
   * @param timestamp A timestamp.
   * @param value A double value.
   */
  public void resetWithLongValue(long timestamp, long value) {
    this.timestamp = timestamp;
    this.isInteger = true;
    this.longValue = value;
  }

  /**
   * Resets with a new data point.
   *
   * @param dp A new data point to store.
   */
  public void reset(DataPoint dp) {
    this.timestamp = dp.timestamp();
    this.isInteger = dp.isInteger();
    if (isInteger) {
      this.longValue = dp.longValue();
    } else {
      this.doubleValue = dp.doubleValue();
    }
  }

  /**
   * Resets with a new pair of a timestamp and a double value.
   *
   * @param timestamp A timestamp.
   * @param value A double value.
   */
  public static MutableDataPoint ofDoubleValue(long timestamp, double value) {
    MutableDataPoint dp = new MutableDataPoint();
    dp.resetWithDoubleValue(timestamp, value);
    return dp;
  }

  /**
   * Resets with a new pair of a timestamp and a long value.
   *
   * @param timestamp A timestamp.
   * @param value A double value.
   */
  public static MutableDataPoint ofLongValue(long timestamp, long value) {
    MutableDataPoint dp = new MutableDataPoint();
    dp.resetWithLongValue(timestamp, value);
    return dp;
  }

  @Override
  public long timestamp() {
    return timestamp;
  }

  @Override
  public boolean isInteger() {
    return isInteger;
  }

  @Override
  public long longValue() {
    if (isInteger) {
      return longValue;
    }
    throw new ClassCastException("Not a long in " + toString());
  }

  @Override
  public double doubleValue() {
    if (!isInteger) {
      return doubleValue;
    }
    throw new ClassCastException("Not a double in " + toString());
  }

  @Override
  public double toDouble() {
    if (isInteger) {
      return longValue;
    }
    return doubleValue;
  }

  @Override
  public String toString() {
    return String.format("timestamp = %d, isInteger=%s, long:%d, " +
                         "double:value = %g", timestamp, isInteger, longValue,
                         doubleValue);
  }
}
