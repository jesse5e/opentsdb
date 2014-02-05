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
package net.opentsdb.tsd;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.collect.Lists;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nullable;

import net.opentsdb.core.Const;
import net.opentsdb.core.TSDB;
import net.opentsdb.utils.DateTime;

import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stupidly simple HTTP query cache.
 * - Key is generated via start and end time and a simple hash of a query URL.
 * - Key has a key file path, a data file path, and a file basepath.
 * - Key file path is composed of "cache dir" + "hash key" + ".key".
 * - The file basepath is the key file path + a sequence number to avoid
 *   collisions among same queries in flight.
 * - Data file path is the base path + "." + suffix.
 * - Key is stored along with the expiration time of the server side cache.
 * - Each cache entry has both client cache TTL and server cache TTL.
 */
final class QueryResultFileCache {

  private static final Logger LOG =
    LoggerFactory.getLogger(QueryResultFileCache.class);
  private static final int ONE_DAY_IN_SECONDS = 86400;
  private static final int ONE_WEEK_IN_SECONDS = 86400 * 7;
  private static final int HUNDRED_DAYS_IN_SECONDS = 86400 * 100;

  /** System call utility. */
  private final AtomicLong sequence;
  private final Util util;
  private final String cacheDir;

  public QueryResultFileCache(final TSDB tsdb) {
    this(tsdb.getConfig().getString("tsd.http.cachedir"), new Util(),
         System.currentTimeMillis());
  }

  public QueryResultFileCache(final String cacheDir, Util util,
                              long startSequence) {
    this.util = util;
    this.cacheDir = cacheDir;
    sequence = new AtomicLong(startSequence);
  }

  /** Creates and returns a new key builder. */
  public KeyBuilder newKeyBuilder(){
    return new KeyBuilder(cacheDir, sequence);
  }

  public Entry createEntry(Key key, int cacheTtlSecs) {
    long expirationMillis = util.currentTimeMillis() +
                            TimeUnit.SECONDS.toMillis(cacheTtlSecs);
    return new Entry(key, expirationMillis, util);
  }

  /**
   * Caches the query result files of the given key,
   *
   * @param entry Entry to cache.
   * @throws IOException if the key file cannot be created.
   */
  public synchronized void put(final Entry entry) throws IOException {
    // TODO: Use the FileLock instead of this synchronization.
    // TODO: Add additional information to check corruption to retrieve data.
    PrintWriter writer = null;
    try {
      writer = util.newPrintWriter(entry.getKey().getKeyFilepath());
      entry.write(writer);
    } finally {
      if (writer != null) {
        writer.close();
      }
    }
  }

  /**
   * Returns the entry that has the cached file path if exists.
   *
   * @param wantedKey The key of a cache entry.
   * @return Cached entry if exists. Otherwise, null.
   */
  @Nullable
  public Entry get(final Key wantedKey) {
    try {
      // TODO: Cache popular keys in memory by Feb. 28, 2014.
      File keyFile = util.newFile(wantedKey.getKeyFilepath());
      if (keyFile.exists()) {
        List<String> lines = util.readAndCloseFile(wantedKey.getKeyFilepath());
        return Entry.read(lines.iterator(), util);
      }
    } catch (IOException e) {
      LOG.warn(e.getMessage(), e);
    }
    return null;
  }

  /**
   * Returns whether or not the given cache file can be used or is stale.
   *
   * @param query The query to serve.
   * @param entry The cache entry associated with the given file.
   * @param cachedfile The file to check for staleness.
   */
  public boolean staleCacheFile(final HttpQuery query,
                                final Entry entry,
                                final File cachedfile) {
    // TODO: Add validation of the cached file.
    final long mtime = TimeUnit.MILLISECONDS.toSeconds(cachedfile.lastModified());
    if (mtime <= 0) {
      return true;  // File doesn't exist, or can't be read.
    }
    if (entry.isExpired()) {
      logInfo(query, String.format("Cached file @%s has been expired at %d, " +
                                   "and needs to be regenerated.",
                                   cachedfile.getPath(),
                                   entry.getExpirationTimeMillis()));
      return true;
    }
    return false;
  }

  /**
   * Decides how long we're going to allow the client to cache our response.
   * <p>
   * Based on the query, we'll decide whether or not we want to allow the
   * client to cache our response and for how long.
   * @param query The query to serve.
   * @param startTimeSecs The start time on the query (32-bit unsigned int, secs).
   * @param endTimeSecs The end time on the query (32-bit unsigned int, seconds).
   * @param nowSecs The current time (32-bit unsigned int, seconds).
   * @return A positive integer, in seconds.
   */
  static int clientCacheTtl(final HttpQuery query,
                                   final long startTimeSecs,
                                   final long endTimeSecs,
                                   final long nowSecs) {
    // If the end time is in the future (1), make the graph uncacheable.
    // Otherwise, if the end time is far enough in the past (2) such that
    // no TSD can still be writing to rows for that time span and it's not
    // specified in a relative fashion (3) (e.g. "1d-ago"), make the graph
    // cacheable for a day since it's very unlikely that any data will change
    // for this time span.
    // Otherwise (4), allow the client to cache the graph for ~0.1% of the
    // time span covered by the request e.g., for 1h of data, it's OK to
    // serve something 3s stale, for 1d of data, 84s stale.
    if (endTimeSecs > nowSecs) {                            // (1)
      return 0;
    } else if (endTimeSecs < nowSecs - Const.MAX_TIMESPAN_SECS   // (2)
               && !DateTime.isRelativeDate(
                   query.getQueryStringParam("start"))    // (3)
               && !DateTime.isRelativeDate(
                   query.getQueryStringParam("end"))) {
      return ONE_DAY_IN_SECONDS;
    } else {                                         // (4)
      return (int) (endTimeSecs - startTimeSecs) >> 10;
    }
  }

  /**
   * Decides how long a server can cache query results.
   *
   * @param query The query to serve.
   * @param startTimeSecs The start time on the query (32-bit unsigned int, secs).
   * @param endTimeSecs The end time on the query (32-bit unsigned int, seconds).
   * @param nowSecs The current time (32-bit unsigned int, seconds).
   * @return A positive integer, in seconds.
   */
  static int serverCacheTtl(final HttpQuery query,
                                   final long startTimeSecs,
                                   final long endTimeSecs,
                                   final long nowSecs) {
    if (endTimeSecs < nowSecs - ONE_WEEK_IN_SECONDS) {
      // Results for old time range can be cached very long time.
      return HUNDRED_DAYS_IN_SECONDS;
    } else {
      // Results for recent time range follows client cache TTL.
      return clientCacheTtl(query, startTimeSecs, endTimeSecs, nowSecs);
    }
  }

  /** Key for query results. */
  public static class Key {

    /** The file path where this key is stored. */
    private final String keyFilepath;
    /** base path of the data file and other temporary files. */
    private final String tempFileBase;
    // NOTE: Separating suffix is required to work with {@link GraphHandler}
    // class. It expects the suffix should match with the file type and
    // it also expects the file name should be tempFileBase + "." + suffix.
    /** The extension of query result file. */
    private final String suffix;

    @VisibleForTesting
    Key(String keyFilepath, String tempFileBase, String suffix) {
      this.keyFilepath = keyFilepath;
      this.tempFileBase = tempFileBase;
      this.suffix = suffix;
    }

    /** Returns the path of a key entry file at the cache directory. */
    public String getKeyFilepath() {
      return keyFilepath;
    }

    /** Returns the base path of data and files at the cache directory. */
    public String getBasepath() {
      return tempFileBase;
    }

    /** Returns the path of query result file at the cache directory. */
    public String getDataFilePath() {
      return tempFileBase + "." + suffix;
    }

    /** Creates a new Key instance with the given suffix. */
    public Key newKeyWithNewSuffix(String suffix) {
      // NOTE: {@link GraphHandler} needs this function.
      return new Key(keyFilepath, tempFileBase, suffix);
    }

    /** Writes this key to a file. */
    private void write(PrintWriter writer) {
      // TODO: Use serialization.
      writer.println(keyFilepath);
      writer.println(tempFileBase);
      writer.println(suffix);
    }

    /** Reads a key form the given lines. */
    private static Key read(Iterator<String> lines) throws IOException {
      try {
        // TODO: Use serialization.
        String[] fields = new String[3];
        fields[0] = lines.next();
        fields[1] = lines.next();
        fields[2] = lines.next();
        return new Key(fields[0], fields[1], fields[2]);
      } catch (NoSuchElementException e) {
        throw new IOException("Corrupted key file");
      }
    }
  }

  /** Builds a Key instance with various parameters. */
  static class KeyBuilder {

    private final AtomicLong sequence;
    private final String cacheDir;
    private String cacheType = "data";
    @Nullable private HttpQuery query = null;
    private long startSecs = 0;
    private long endSecs = 0;
    private String suffix = "dat";
    private final List<String> parametersToIgnore = Lists.newArrayList();

    private KeyBuilder(String cacheDir, AtomicLong sequence) {
      this.cacheDir = cacheDir;
      this.sequence = sequence;
    }

    public KeyBuilder setCacheType(String cacheType) {
      this.cacheType = cacheType;
      return this;
    }

    public KeyBuilder setQuery(HttpQuery query) {
      this.query = query;
      return this;
    }

    public KeyBuilder setStartTime(long startSecs) {
      this.startSecs = startSecs;
      return this;
    }

    public KeyBuilder setEndTime(long endSecs) {
      this.endSecs = endSecs;
      return this;
    }

    public KeyBuilder setSuffix(String suffix) {
      this.suffix = suffix;
      return this;
    }

    /**
     * While computing hash for a query, the given query parameter should not
     * be considered.
     *
     * @param queryParam query parameter to ignore
     */
    public KeyBuilder addQueryParameterToIgnore(String queryParam) {
      parametersToIgnore.add(queryParam);
      return this;
    }

    /** Builds a new Key instance. */
    public Key build() {
      String base = String.format("%s-%d-%d-%08x", cacheType, startSecs,
                                  endSecs, queryHash());
      // sequence number is to avoid collision from multiple same in-flight
      // queries.
      String tempFileBase = String.format("%s-%d", base,
                                          sequence.incrementAndGet());
      String keyFile = String.format("%s.%s.key", base, suffix);
      return new Key(new File(cacheDir, keyFile).getAbsolutePath(),
                     new File(cacheDir, tempFileBase).getAbsolutePath(),
                     suffix);
    }

    /** Caculates a simple hash from the given query. */
    private int queryHash() {
      if (query == null) {
        return 0;
      }
      final Map<String, List<String>> q = query.getQueryString();
      // Super cheap caching mechanism: hash the query string.
      final HashMap<String, List<String>> qs =
        new HashMap<String, List<String>>(q);
      for (String param: parametersToIgnore) {
        qs.remove(param);
      }
      return qs.hashCode();
    }
  }

  /** Cache entry with a key and an expiration time. */
  static class Entry {

    /** The key of this cache entry. */
    private final Key key;
    /** Expiration wall-clock time in milliseconds. */
    private final long expirationTimeMillis;
    /** System call utility to help unit tests. */
    private final Util util;

    public Entry(final Key key, final long expirationTimeMillis, Util util) {
      this.key = key;
      this.expirationTimeMillis = expirationTimeMillis;
      this.util = util;
    }

    public Key getKey() {
      return key;
    }

    public long getExpirationTimeMillis() {
      return expirationTimeMillis;
    }

    public boolean isExpired() {
      return expirationTimeMillis < util.currentTimeMillis();
    }

    /** Writes this entry to a file. */
    private void write(PrintWriter writer) {
      // TODO: Use serialization.
      key.write(writer);
      writer.println(expirationTimeMillis);
    }

    /** Reads a key form the given lines. */
    private static Entry read(Iterator<String> lines, Util util)
        throws IOException {
      try {
        // TODO: Use serialization.
        Key key = Key.read(lines);
        long expirationTimeMillis = Long.parseLong(lines.next());
        return new Entry(key, expirationTimeMillis, util);
      } catch (NoSuchElementException e) {
        throw new IOException("Corrupted key file");
      }
    }
  }

  // ---------------- //
  // Logging helpers. //
  // ---------------- //

  private static void logInfo(final HttpQuery query, final String msg) {
    LOG.info(query.channel().toString() + ' ' + msg);
  }

  // ------------------ //
  // Unit Test helpers. //
  // ------------------ //

  ForTesting visibleForTesting() {
    return new ForTesting();
  }

  /**
   * Unit test helper class.
   */
  class ForTesting {

    String getCacheDir() {
      return cacheDir;
    }
  }

  /** System call helper functions. */
  static class Util {

    File newFile(String filename) {
      return new File(filename);
    }

    PrintWriter newPrintWriter(String filename) throws FileNotFoundException {
      return new PrintWriter(filename);
    }

    @SuppressWarnings("unchecked")
    List<String> readAndCloseFile(String filepath) throws IOException {
      InputStream is = new FileInputStream(new File(filepath));
      try {
        return IOUtils.readLines(is, Charsets.UTF_8.name());
      } finally {
        IOUtils.closeQuietly(is);
      }
    }

    long currentTimeMillis() {
      return System.currentTimeMillis();
    }
  }
}
