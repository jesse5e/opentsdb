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

import java.util.NoSuchElementException;


/**
 * Iterator that generates rates from a sequence of adjacent data points.
 */
public class RateSpan implements SeekableView {

  /** A sequence of data points to compute rates. */
  private final SeekableView source;
  /** Options for calculating rates. */
  private final RateOptions options;
  /** The raw data point of the current timestamp. */
  private final MutableDataPoint currentData = new MutableDataPoint();
  /** The raw data point of the previous timestamp. */
  private final MutableDataPoint prevData = new MutableDataPoint();
  /** The current rate that has been returned to a caller by {@link #next}. */
  private final MutableDoubleDataPoint currentRate =
      new MutableDoubleDataPoint();
  /** True if it is initialized for iterating rates of changes. */
  private boolean initialized = false;

  /**
   * Constructs a {@link RateSpan} instance.
   * @param source The iterator to access the underlying data.
   * @param options Options for calculating rates.
   */
  RateSpan(final SeekableView source, final RateOptions options) {
    this.source = source;
    this.options = options;
  }

  // ------------------ //
  // Iterator interface //
  // ------------------ //

  @Override
  public boolean hasNext() {
    initializeIfNotDone();
    return source.hasNext();
  }

  @Override
  public DataPoint next() {
    initializeIfNotDone();
    moveToNextRate();
    return currentRate;
  }

  @Override
  public void remove() {
    throw new UnsupportedOperationException();
  }

  // ---------------------- //
  // SeekableView interface //
  // ---------------------- //

  @Override
  public void seek(long timestamp) {
    source.seek(timestamp);
    initialized = false;
  }

  // ---------------------- //
  // Private methods        //
  // ---------------------- //

  /** Initializes to iterate intervals. */
  private void initializeIfNotDone() {
    // NOTE: Delay initialization is required to not access any data point
    // from source until a user requests it explicitly to avoid the severe
    // performance penalty by accessing the first data of a span.
    if (!initialized) {
      initialized = true;
      setFirstData();
    }
  }

  /**
   * Sets the first current data to calculates the first rate for the
   * following data.
   */
  private void setFirstData() {
    if (source.hasNext()) {
      currentData.reset(source.next());
    }
  }

  /**
   * Move to the next valid rate.
   * @throws NoSuchElementException if there is no more data.
   */
  private void moveToNextRate() {
    if (source.hasNext()) {
      prevData.reset(currentData);
      currentData.reset(source.next());
      long t0 = prevData.timestamp();
      long t1 = currentData.timestamp();
      if (t1 > t0) {
        currentRate.reset(currentData.timestamp(), calculateRate());
      } else {
        // Just use the existing one.
      }
    } else {
      throw new NoSuchElementException("no more values for " + toString());
    }
  }

  /**
   * Adjusts a counter rate considering a roll over.
   * @param timeDeltaSeconds time delta in seconds
   * @param delta delta of values
   * @return Adjusted rate
   */
  private double adjustCounterRateForRollOver(final double timeDeltaSeconds,
                                              final double delta) {
    // Assumes the count was reset if the calculated rate is larger than
    // the reset value, then returns 0 for the rate.
    final double r = delta / timeDeltaSeconds;
    if (options.getResetValue() > RateOptions.DEFAULT_RESET_VALUE
        && r > options.getResetValue()) {
      return 0.0;
    }
    return r;
  }

  /**
   * Calculates the difference of the previous and current values.
   * @return a delta
   */
  private double calculateDelta() {
    if (prevData.isInteger() && currentData.isInteger()) {
      // NOTE: Calculates in the long type to avoid precision loss
      // while converting long values to double values if both values are long.
      // NOTE: Ignores the integer overflow.
      return currentData.longValue() - prevData.longValue();
    }
    return  currentData.toDouble() - prevData.toDouble();
  }

  /**
   * Adjusts a negative delta of a counter assuming there was a roll over
   * in the current data value.
   * @return a delta
   */
  private double adjustNegativeCounterDelta() {
    // NOTE: Assumes a roll over of a counter if we found that a counter value
    // was decreased while calculating a rate of changes for a counter.
    if (prevData.isInteger() && currentData.isInteger()) {
      // NOTE: Calculates in the long type to avoid precision loss
      // while converting long values to double values if both values are long.
      return options.getCounterMax() - prevData.longValue() +
          currentData.longValue();
    }
    return options.getCounterMax() - prevData.toDouble() +
        currentData.toDouble();
  }

  /**
   * Calculates the rate between previous and current data points.
   */
  private double calculateRate() {
    final long t0 = prevData.timestamp();
    final long t1 = currentData.timestamp();
    // TODO: for backwards compatibility we'll convert the ms to seconds
    // but in the future we should add a ratems flag that will calculate
    // the rate as is.
    final double timeDeltaSeconds = ((double)(t1 - t0) / 1000.0);
    double difference = calculateDelta();
    if (options.isCounter() && difference < 0) {
      difference = adjustNegativeCounterDelta();
      return adjustCounterRateForRollOver(timeDeltaSeconds, difference);
    } else {
      return difference / timeDeltaSeconds;
    }
  }

  @Override
  public String toString() {
    final StringBuilder buf = new StringBuilder();
    buf.append("RateSpan: ")
       .append(", options=") .append(options)
       .append(", currentData=[") .append(currentData)
       .append("], prevData=[") .append(prevData)
       .append(", currentRate=[") .append(currentRate)
       .append("], source=[") .append(source).append("]");
    return buf.toString();
  }
}
