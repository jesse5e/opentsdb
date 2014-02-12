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
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

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

  static final String NO_CACHE = "nocache";
  private static final String REFRESH_CACHE = "refreshcache";

  private static final int ONE_DAY_IN_SECONDS = 86400;
  private static final int ONE_WEEK_IN_SECONDS = 86400 * 7;
  private static final int HUNDRED_DAYS_IN_SECONDS = 86400 * 100;
  /** The size of the in-memory cache of entry files. */
  private static final long MEM_CACHE_SIZE = 100000;

  /** Sequence number for each entry. */
  private final AtomicLong sequence;
  /** System call utility. */
  private final Util util;
  /** Directory to store cache entry and data files. */
  private final String cacheDir;
  /** In memory cache for entry files. */
  private final Cache<String, Entry> entries;

  public QueryResultFileCache(final TSDB tsdb) {
    this(tsdb.getConfig().getString("tsd.http.cachedir"), new Util(),
         System.currentTimeMillis(), MEM_CACHE_SIZE);
  }

  public QueryResultFileCache(final String cacheDir, Util util,
                              long startSequence, long memCacheSize) {
    this.util = util;
    this.cacheDir = cacheDir;
    sequence = new AtomicLong(startSequence);
    entries = CacheBuilder.newBuilder()
        .maximumSize(memCacheSize)
        .build();
  }

  /** Creates and returns a new key builder. */
  public KeyBuilder newKeyBuilder(){
    return new KeyBuilder();
  }

  /**
   * Creates a new entry.
   *
   * @param key The key of the new entry
   * @param suffix The suffix of the new entry
   * @param cacheTtlSecs The Time-To-Live of the new entry
   * @return A new cache entry
   */
  public Entry createEntry(Key key, String suffix, int cacheTtlSecs) {
    long expirationMillis = util.currentTimeMillis() +
                            TimeUnit.SECONDS.toMillis(cacheTtlSecs);
    return Entry.create(key, expirationMillis, cacheDir, suffix,
                        sequence.incrementAndGet(), util);
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
    entry.write();
    // Caches the new entry.
    entries.put(entry.key.key, entry);
  }

  /**
   * Returns the entry that has the cached file path if exists.
   *
   * @param wantedKey The key of a cache entry.
   * @return Cached entry if exists. Otherwise, null.
   */
  @Nullable
  public Entry getIfPresent(final Key wantedKey) {
    try {
      // TODO: Use get-and-loader and catch the exception for not existing
      // key.
      Entry entry = entries.getIfPresent(wantedKey.key);
      if (entry == null) {
        entry = Entry.loadEntryIfPresent(wantedKey, cacheDir, util);
        if (entry != null) {
          // Caches the loaded entry.
          entries.put(entry.getKey().key, entry);
        }
      }
      return entry;
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
      logInfo(query, String.format("Cached file @%s cannot be read: mtime=%d.",
                                   cachedfile.getPath(), mtime));
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

  /** @return true if cached query result should be used. */
  static boolean shouldUseCache(HttpQuery query) {
    // NOT (NO_CACHE or REFRESH_CACHE).
    return !(query.hasQueryStringParam(NO_CACHE) ||
        query.hasQueryStringParam(REFRESH_CACHE));
  }

  /** @return true if query result cache should be updated. */
  static boolean shouldUpdateCache(HttpQuery query) {
    // Updates cached query results unless the query has NO_CACHE.
    return !query.hasQueryStringParam(NO_CACHE);
  }

  /** Key for query results. */
  static class Key {

    /** A string key. */
    private final String key;

    @VisibleForTesting
    Key(String key) {
      this.key = key;
    }

    /** Returns the key as a string. */
    String getKeyForTesting() {
      return key;
    }

    /** Writes this key to a file. */
    private void write(PrintWriter writer) {
      writer.println(key);
    }

    /** Reads a key form the given lines. */
    private static Key read(Iterator<String> lines) throws IOException {
      try {
        return new Key(lines.next());
      } catch (NoSuchElementException e) {
        throw new IOException("Corrupted key file");
      }
    }

    @Override
    public boolean equals(Object other) {
      if (other instanceof Key) {
        return key.equals(((Key)other).key);
      }
      return false;
    }

    @Override
    public int hashCode() {
      return key.hashCode();
    }
  }

  /** Builds a Key instance with various parameters. */
  static class KeyBuilder {

    private static final String[] DEFAULT_PARAMS_TO_IGNORE = new String[] {
      "ignore", "traceid", REFRESH_CACHE
    };

    private String cacheType = "data";
    @Nullable private HttpQuery query = null;
    private long startSecs = 0;
    private long endSecs = 0;
    private String suffix = "dat";
    private final List<String> parametersToIgnore = Lists.newArrayList();

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
      String key = String.format("%s-%d-%d-%08x-%s", cacheType, startSecs,
                                  endSecs, queryHash(), suffix);
      return new Key(key);
    }

    /** Calculates a simple hash from the given query. */
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
      for (String param: DEFAULT_PARAMS_TO_IGNORE) {
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
    /** The file path where this entry should be stored. */
    private final String entryFilePath;
    /** base path of the data file and other temporary files. */
    private final String basepath;
    /** The path of query result file at the cache directory. */
    private final String dataFilePath;
    /** System call utility to help unit tests. */
    private final Util util;

    /**
     * Creates a cache entry.
     *
     * @param key A key
     * @param expirationTimeMillis Wall-clock time for the entry to expire
     * @param cacheDir Directory to store files.
     * @param suffix Data file suffix
     * @param tempSequence Sequence number to avoid collision among same
     * queries in flight.
     * @param util System call utility for tests
     */
    @VisibleForTesting
    static Entry create(final Key key, final long expirationTimeMillis,
                        final String cacheDir, final String suffix,
                        final long tempSequence, final Util util) {
      String temppath = new File(cacheDir, key.key).getAbsolutePath();
      String entryFilePath = temppath + ".entry";
      // sequence number is to avoid collision from multiple same in-flight
      // queries.
      String basepath = String.format("%s-%d", temppath, tempSequence);
      // NOTE: {@link GraphHandler} class expects the suffix should match
      // with the file type and the file name should be basepath + "." + suffix.
      String dataFilePath = String.format("%s.%s", basepath, suffix);
      return new Entry(key, expirationTimeMillis, entryFilePath, basepath,
                       dataFilePath, util);
    }

    @VisibleForTesting
    Entry(final Key key, final long expirationTimeMillis,
          final String entryFilePath, final String basepath,
          final String dataFilePath, final Util util) {
      this.key = key;
      this.expirationTimeMillis = expirationTimeMillis;
      this.entryFilePath = entryFilePath;
      this.basepath = basepath;
      this.dataFilePath = dataFilePath;
      this.util = util;
    }

    Key getKey() {
      return key;
    }

    long getExpirationTimeMillis() {
      return expirationTimeMillis;
    }

    boolean isExpired() {
      return expirationTimeMillis < util.currentTimeMillis();
    }

    /** Returns the path of a file to store this entry. */
    String getEntryFilePath() {
      return entryFilePath;
    }

    /**
     * Returns the path of query result file at the cache directory. This path
     * is basepath + "." + suffix.
     */
    String getDataFilePath() {
      return dataFilePath;
    }

    /** Returns the base path of data and files at the cache directory. */
    String getBasepath() {
      return basepath;
    }

    /** Writes this entry to a file. */
    private void write() throws FileNotFoundException {
      // TODO: Add additional information to check corruption to retrieve data.
      PrintWriter writer = null;
      try {
        writer = util.newPrintWriter(entryFilePath);
        key.write(writer);
        write(writer);
      } finally {
        if (writer != null) {
          writer.close();
        }
      }
    }

    /** Writes this entry to a file. */
    private void write(PrintWriter writer) {
      // TODO: Use serialization.
      writer.println(expirationTimeMillis);
      writer.println(entryFilePath);
      writer.println(basepath);
      writer.println(dataFilePath);
    }

    /**
     * Reads an entry from the given file and returns it.
     *
     * @param key A key
     * @param cacheDir Directory to store files.
     * @param util System call utility for tests
     * @return An entry if presents. Otherwise null.
     * @throws IOException If there is an error while reading the file.
     */
    @Nullable
    private static Entry loadEntryIfPresent(final Key key,
                                            final String cacheDir,
                                            final Util util)
                                                throws IOException {
      String temppath = new File(cacheDir, key.key).getAbsolutePath();
      String entryFilePath = temppath + ".entry";
      if (util.newFile(entryFilePath).exists()) {
        List<String> lines = util.readAndCloseFile(entryFilePath);
        return Entry.read(lines.iterator(), util);
      }
      return null;
    }

    /** Reads a key form the given lines. */
    private static Entry read(Iterator<String> lines, Util util)
        throws IOException {
      try {
        // TODO: Use serialization.
        Key key = Key.read(lines);
        long expirationTimeMillis = Long.parseLong(lines.next());
        String[] fields = new String[3];
        fields[0] = lines.next();
        fields[1] = lines.next();
        fields[2] = lines.next();
        return new Entry(key, expirationTimeMillis, fields[0], fields[1],
                         fields[2], util);
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
