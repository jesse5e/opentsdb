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
 * Iterator that downsamples the data using an {@link Aggregator}.
 */
public class Downsampler implements SeekableView {

  /** Function to use for downsampling. */
  private final Aggregator downsampler;

  /** Iterator to iterate the values of the current interval. */
  private final ValuesInInterval intervalIter;

  // NOTE: Uses MutableDoubleDataPoint to reduce memory allocation burden.
  /** The current downsample result. */
  private MutableDoubleDataPoint dataPoint = new MutableDoubleDataPoint();

  /**
   * Ctor.
   * @param source The iterator to access the underlying data.
   * @param interval The interval in seconds wanted between each data point.
   * @param downsampler The downsampling function to use.
   */
  Downsampler(final SeekableView source,
              final int interval,
              final Aggregator downsampler) {
    this.intervalIter = new ValuesInInterval(source, interval);
    this.downsampler = downsampler;
  }

  /** Downsamples for the current interval.
   *
   * @return A {@link DataPoint} as a downsample result.
   */
  private DataPoint downsample() {
    double downsampleResult = downsampler.runDouble(intervalIter);
    dataPoint.reset(intervalIter.getIntervalTimestamp(), downsampleResult);
    intervalIter.moveToNextInterval();
    return dataPoint;
  }

  // ------------------ //
  // Iterator interface //
  // ------------------ //

  public boolean hasNext() {
    return intervalIter.hasNextValue();
  }

  public DataPoint next() {
    if (hasNext()) {
      return downsample();
    }
    throw new NoSuchElementException("no more data points in " + this);
  }

  public void remove() {
    throw new UnsupportedOperationException();
  }

  // ---------------------- //
  // SeekableView interface //
  // ---------------------- //

  public void seek(final long timestamp) {
    intervalIter.seekInterval(timestamp);
  }

  @Override
  public String toString() {
    final StringBuilder buf = new StringBuilder();
    buf.append("Span.DownsamplingIterator: ")
       .append("interval=").append(intervalIter.interval)
       .append(", downsampler=").append(downsampler)
       .append(", current data=(").append(dataPoint)
       .append("), intervalIter=").append(intervalIter);
   return buf.toString();
  }

  /**
   * Iterates source values for an interval.
   */
  private static class ValuesInInterval implements Aggregator.Doubles {

    /** The iterator of original source values. */
    private final SeekableView source;

    /** The sampling interval in milliseconds. */
    private final int interval;

    /** The end of the current interval. */
    private long timestampEndInterval;

    /** True if the last value was successfully extracted from the source. */
    private boolean hasNextValueFromSource = false;

    /** The last data point extracted from the source. */
    private MutableDoubleDataPoint nextDataPoint = new MutableDoubleDataPoint();

    /** True if it is initialized for iterating intervals. */
    private boolean initialized = false;

    /**
     * Constructs a ValuesInInterval instance.
     *
     * @param source The iterator to access the underlying data.
     * @param interval Downsampling interval.
     */
    ValuesInInterval(SeekableView source, int interval) {
      this.source = source;
      this.interval = interval;
      this.timestampEndInterval = interval;
    }

    /** Initializes to iterate intervals. */
    private void initializeIfNotDone() {
      // NOTE: Delay initialization is required to not access any data point
      // from source until a user requests it explicitly to avoid the severe
      // performance penalty by accessing the first data of a span.
      if (!initialized) {
        initialized = true;
        moveToNextValue();
        resetEndOfInterval();
      }
    }

    /** Extracts the next value from the source. */
    private void moveToNextValue() {
      if (source.hasNext()) {
        hasNextValueFromSource = true;
        nextDataPoint.reset(source.next());
      } else {
        hasNextValueFromSource = false;
      }
    }

    /** Resets the current interval with the interval of the next timestamp. */
    private void resetEndOfInterval() {
      if (hasNextValueFromSource) {
        long timestamp = nextDataPoint.timestamp();
        timestampEndInterval = calculateIntervalEndTimestamp(timestamp);
      }
    }

    /** Moves to the next available interval. */
    void moveToNextInterval() {
      initializeIfNotDone();
      resetEndOfInterval();
    }

    /** Advances the interval iterator to the given timestamp. */
    void seekInterval(long timestamp) {
      // To make sure that the interval of the given timestamp is fully filled,
      // rounds up the seeking timestamp by the interval.
      source.seek(roundUpTimestamp(timestamp));
      initialized = false;
    }

    /** Returns the representative timestamp of the current interval. */
    private long getIntervalTimestamp() {
      return calculateRepresentativeTimestamp(timestampEndInterval - interval);
    }

    /** Returns the end of the interval of the given timestamp. */
    private long calculateIntervalEndTimestamp(long timestamp) {
      return alignTimestamp(timestamp) + interval;
    }

    /** Returns the timestamp of the interval of the given timestamp. */
    private long calculateRepresentativeTimestamp(long timestamp) {
      // NOTE: It is well-known practice taking the start time of
      // a downsample interval as a representative timestamp of it. It also
      // provides the correct context for seek.
      return alignTimestamp(timestamp);
    }

    /**
     * Rounds up the given timestamp.
     * @param timestamp Timestamp to round up
     * @return The smallest timestamp that is a multiple of the interval
     * and is greater than or equal to the given timestamp.
     */
    private long roundUpTimestamp(long timestamp) {
      return alignTimestamp(timestamp + interval - 1);
    }

    /** Returns timestamp aligned by interval. */
    private long alignTimestamp(long timestamp) {
      return timestamp - (timestamp % interval);
    }

    // ---------------------- //
    // Doubles interface //
    // ---------------------- //

    @Override
    public boolean hasNextValue() {
      initializeIfNotDone();
      return hasNextValueFromSource &&
          nextDataPoint.timestamp() < timestampEndInterval;
    }

    @Override
    public double nextDoubleValue() {
      if (hasNextValue()) {
        double value = nextDataPoint.toDouble();
        moveToNextValue();
        return value;
      }
      throw new NoSuchElementException("no more values in interval of "
          + timestampEndInterval);
    }

    @Override
    public String toString() {
      final StringBuilder buf = new StringBuilder();
      buf.append("ValuesInInterval: interval: ")
         .append("interval=").append(interval)
         .append(", timestampEndInterval=").append(timestampEndInterval)
         .append(", hasNextValueFromSource=").append(hasNextValueFromSource);
      if (hasNextValueFromSource) {
        buf.append(", nextValue=(").append(nextDataPoint).append(')');
      }
      buf.append(", source=").append(source);
      return buf.toString();
    }
  }
}
