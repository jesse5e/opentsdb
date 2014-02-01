// This file is part of OpenTSDB.
// Copyright (C) 2014  The OpenTSDB Authors.
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

import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.stumbleupon.async.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.stumbleupon.async.Deferred;

/**
 * Forces timeouts for calls to {@link Deferred#joinUninterruptibly},
 * then prints stacks of all threads for debugging. This is not usual timeout
 * class. Any timeout means the system is unstable and required a restart.
 */
public class BadTimeout {

  private static final Logger LOG = LoggerFactory.getLogger(BadTimeout.class);

  private static final long ONE_MINUTE_MILLIS = TimeUnit.MINUTES.toMillis(1);

  /** Gives up all timeouts if the system is unstable. */
  private static final AtomicBoolean systemUnstable = new AtomicBoolean(false);

  /**
   * Waits for the given deferred results for an hour if the system is stable.
   * Then flags the system unstable if there is a timeout. Timeout could
   * happen before one hour if the system is unstable.
   *
   * @param deferred A deferred result object
   * @return result with the given type.
   * @throws Exception if the deferred result is an exception, this exception
   * will be thrown.
   */
  public static <T> T hour(Deferred<T> deferred) throws Exception {
    return minutes(deferred, 60);
  }

  /**
   * Waits for the given deferred results for ten minutes if the system is
   * stable. Then flags the system unstable if there is a timeout. Timeout could
   * happen before ten minutes if the system is unstable.
   *
   * @param deferred A deferred result object
   * @return result with the given type.
   * @throws Exception if the deferred result is an exception, this exception
   * will be thrown.
   */
  public static <T> T minutes(Deferred<T> deferred) throws Exception {
    return minutes(deferred, 10);
  }

  /**
   * The system is considered as unstable if there have been any bad timeouts
   * because they must not happen for a healthy server.
   *
   * @return true if system is unstable.
   */
  public static boolean isSystemUnstable() {
    return systemUnstable.get();
  }

  /**
   * Waits for the given deferred results for the specified minutes.
   * Then flags the system unstable if there is a timeout. Timeout could
   * happen before the specified timeout if the system is unstable.
   *
   * @param deferred A deferred result object
   * @param timeoutMins timeout in minutes.
   * @return result with the given type.
   * @throws Exception if the deferred result is an exception, this exception
   * will be thrown.
   */
  private static <T> T minutes(Deferred<T> deferred, final int timeoutMins)
      throws Exception {
    try {
      for (int count = 0; count < timeoutMins; ++count) {
        try {
          return deferred.joinUninterruptibly(ONE_MINUTE_MILLIS);
        } catch (TimeoutException unused) {
          // Checks if the systems is stable every minute, and gives up if
          // it is unstable even before the given timeout.
          if (isSystemUnstable()) {
            throw new BadTimeoutException(deferred, count * ONE_MINUTE_MILLIS);
          }
        }
      }
      // Timeout could happen only if the system is unstable.
      systemUnstable.set(true);
      logStackTraces();
      throw new BadTimeoutException(deferred, timeoutMins * ONE_MINUTE_MILLIS);
    } catch (Exception e) {
      throw e;
    }
  }

  /** Logs stack traces of all threads. */
  private static void logStackTraces() {
    for (Thread th: Thread.getAllStackTraces().keySet()) {
      LOG.info(th.toString() + ": " + Arrays.toString(th.getStackTrace()));
    }
  }

  /**
   * Exception thrown when there's a timeout.
   */
  public static final class BadTimeoutException extends RuntimeException {

    private static final long serialVersionUID = 139837652736206699L;

    /**
     * Package-private constructor.
     * @param d The Deferred on which we timed out.
     * @param timeout The original timeout in milliseconds.
     */
    <T> BadTimeoutException(final Deferred<T> d, final long timeoutMs) {
      super("Timed out after " + timeoutMs + "ms when joining " + d);
    }
  }
}
