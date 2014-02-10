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

import static org.hamcrest.CoreMatchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.google.common.collect.Maps;
import com.google.common.collect.Lists;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.List;

import net.opentsdb.core.TSDB;
import net.opentsdb.tsd.QueryResultFileCache.Entry;
import net.opentsdb.tsd.QueryResultFileCache.Key;
import net.opentsdb.tsd.QueryResultFileCache.KeyBuilder;
import net.opentsdb.utils.Config;

import org.jboss.netty.channel.Channel;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

/**
 * Tests {@link QueryResultFileCache}.
 */
@RunWith(PowerMockRunner.class)
@PrepareForTest({QueryResultFileCache.class, HttpQuery.class, TSDB.class})
public class TestQueryResultFileCache {

  private static final String CACHE_DIR = "/foo/barcache";
  private static final long START_SEQUENCE = 17;
  private static final int TIME_107_SECS = 107;
  private static final long TIME_12345678_MILLIS = 12345678L;
  private static final int TIME_100000_SECS = 100000;
  private static final long MEM_CACHE_SIZE = 10;

  private QueryResultFileCache.Util mockUtil;
  private PrintWriter mockPrintWriter;
  private HttpQuery mockHttpQuery;
  private QueryResultFileCache cache;
  private Channel mockChannel;

  @Before
  public void setUp() throws IOException {
    mockUtil = Mockito.mock(QueryResultFileCache.Util.class);
    mockPrintWriter = Mockito.mock(PrintWriter.class);
    mockHttpQuery = PowerMockito.mock(HttpQuery.class);
    mockChannel = Mockito.mock(Channel.class);
    PowerMockito.when(mockHttpQuery.channel()).thenReturn(mockChannel);
    cache = new QueryResultFileCache(CACHE_DIR, mockUtil, START_SEQUENCE,
                                     MEM_CACHE_SIZE);
  }

  @Test
  public void testQueryResultFileCache_TSDB() throws IOException {
    Config config = new Config(false);
    config.overrideConfig("tsd.http.cachedir", CACHE_DIR);
    TSDB mockTsdb = PowerMockito.mock(TSDB.class);
    PowerMockito.when(mockTsdb.getConfig()).thenReturn(config);
    QueryResultFileCache cache = new QueryResultFileCache(mockTsdb);
    assertEquals(CACHE_DIR, cache.visibleForTesting().getCacheDir());
  }

  @Test
  public void testQueryResultFileCache_StringUtil() {
    assertEquals(CACHE_DIR, cache.visibleForTesting().getCacheDir());
  }

  @Test
  public void testNewKeyBuilder() {
    Key key1 = cache.newKeyBuilder().setStartTime(111).build();
    Key key2 = cache.newKeyBuilder().setStartTime(111).build();
    assertEquals(key1, key2);
    assertEquals(key1.hashCode(), key2.hashCode());
    Key key3 = cache.newKeyBuilder().setStartTime(111).setEndTime(123).build();
    assertFalse(key1.equals(key3));
  }

  @Test
  public void testCreateEntry() {
    Key key = new Key("foo_key");
    Mockito.when(mockUtil.currentTimeMillis()).thenReturn(TIME_12345678_MILLIS);
    Entry entry = cache.createEntry(key, "ffx", TIME_107_SECS);
    assertEquals(key, entry.getKey());
    assertEquals(CACHE_DIR + "/foo_key.entry", entry.getEntryFilePath());
    assertEquals(CACHE_DIR + "/foo_key-" + (START_SEQUENCE + 1),
                 entry.getBasepath());
    assertEquals(entry.getBasepath() + ".ffx", entry.getDataFilePath());
    assertEquals(12345678L + 107 * 1000, entry.getExpirationTimeMillis());
  }

  @Test
  public void testEntry_expirationTime() {
    Key key = cache.newKeyBuilder().build();
    Mockito.when(mockUtil.currentTimeMillis()).thenReturn(TIME_12345678_MILLIS);
    Entry entry = cache.createEntry(key, "ffx", TIME_107_SECS);
    assertEquals(12345678L + 107 * 1000, entry.getExpirationTimeMillis());
    Mockito.when(mockUtil.currentTimeMillis()).thenReturn(12345678L + 100000);
    assertFalse(entry.isExpired());
    Mockito.when(mockUtil.currentTimeMillis()).thenReturn(12345678L + 200000);
    assertTrue(entry.isExpired());
  }

  @Test
  public void testPut() throws IOException {
    Key key = cache.newKeyBuilder().setSuffix("ffx").build();
    Mockito.when(mockUtil.currentTimeMillis()).thenReturn(TIME_12345678_MILLIS);
    Entry entry = cache.createEntry(key, "ffx", TIME_107_SECS);
    Mockito.when(mockUtil.newPrintWriter(entry.getEntryFilePath()))
        .thenReturn(mockPrintWriter);
    cache.put(entry);
    verify(mockUtil).newPrintWriter(entry.getEntryFilePath());
    verify(mockPrintWriter).println(key.getKeyForTesting());
    verify(mockPrintWriter).println(12452678L);
    verify(mockPrintWriter).println(entry.getEntryFilePath());
    verify(mockPrintWriter).println(entry.getDataFilePath());
    verify(mockPrintWriter).println(entry.getBasepath());
  }

  @Test
  public void testGetIfPresent_noCachedKey() {
    Key wantedKey = new Key("foo_key");
    File mockFile = Mockito.mock(File.class);
    Mockito.when(mockFile.exists()).thenReturn(false);
    Mockito.when(mockUtil.newFile(CACHE_DIR + "/foo_key.entry"))
        .thenReturn(mockFile);
    Entry cachedEntry = cache.getIfPresent(wantedKey);
    verify(mockUtil).newFile(CACHE_DIR + "/foo_key.entry");
    assertNull(cachedEntry);
  }

  @Test
  public void testGetIfPresent_diskCachedKey() throws IOException {
    Key wantedKey = new Key("foo_key");
    File mockFile = Mockito.mock(File.class);
    Mockito.when(mockFile.exists()).thenReturn(true);
    Mockito.when(mockUtil.newFile(CACHE_DIR + "/foo_key.entry"))
        .thenReturn(mockFile);
    List<String> lines = Lists.newArrayList("foo_key", "543219", "foo_key.entry",
                                            "foo_key-11", "foo_key-11.ffx");
    Mockito.when(mockUtil.readAndCloseFile(CACHE_DIR + "/foo_key.entry"))
        .thenReturn(lines);
    Entry cachedEntry = cache.getIfPresent(wantedKey);
    assertNotNull(cachedEntry);
    assertEquals(wantedKey, cachedEntry.getKey());
    assertEquals("foo_key.entry", cachedEntry.getEntryFilePath());
    assertEquals("foo_key-11", cachedEntry.getBasepath());
    assertEquals("foo_key-11.ffx", cachedEntry.getDataFilePath());
    assertEquals(543219L, cachedEntry.getExpirationTimeMillis());
  }

  @Test
  public void testPut_cacheNewEntry() throws IOException {
    Key key = new Key("foo_key");
    Mockito.when(mockUtil.currentTimeMillis()).thenReturn(TIME_12345678_MILLIS);
    Entry entry = cache.createEntry(key, "ffx", TIME_107_SECS);
    Mockito.when(mockUtil.newPrintWriter(CACHE_DIR + "/foo_key.entry"))
        .thenReturn(mockPrintWriter);
    cache.put(entry);
    Key sameKey = new Key("foo_key");
    Entry memCachedEntry = cache.getIfPresent(sameKey);
    assertEquals(entry, memCachedEntry);
    // It should not be loaded from disk.
    verify(mockUtil, never()).readAndCloseFile(anyString());
  }

  @Test
  public void testGetIfPresent_cacheLoadedKey() throws IOException {
    Key wantedKey = new Key("foo_key");
    File mockFile = Mockito.mock(File.class);
    Mockito.when(mockFile.exists()).thenReturn(true);
    Mockito.when(mockUtil.newFile(CACHE_DIR + "/foo_key.entry"))
        .thenReturn(mockFile);
    List<String> lines = Lists.newArrayList("foo_key", "543219", "foo_key.entry",
                                            "foo_key-11", "foo_key-11.ffx");
    Mockito.when(mockUtil.readAndCloseFile(CACHE_DIR + "/foo_key.entry"))
        .thenReturn(lines);
    Entry diskCachedEntry = cache.getIfPresent(wantedKey);
    assertNotNull(diskCachedEntry);
    verify(mockUtil).readAndCloseFile(anyString());
    Key sameKey = new Key("foo_key");
    Entry memCachedEntry = cache.getIfPresent(sameKey);
    assertEquals(diskCachedEntry, memCachedEntry);
    // It should not be loaded from disk again.
    verify(mockUtil, times(1)).readAndCloseFile(anyString());
  }

  @Test
  public void testGetIfPresent_missForDifferentKey() throws IOException {
    Key wantedKey = new Key("foo_key");
    Mockito.when(mockUtil.currentTimeMillis()).thenReturn(TIME_12345678_MILLIS);
    Entry entry = cache.createEntry(wantedKey, "ffx", TIME_107_SECS);
    Mockito.when(mockUtil.newPrintWriter(CACHE_DIR + "/foo_key.entry"))
        .thenReturn(mockPrintWriter);
    cache.put(entry);
    // A same key hits the cache.
    Key sameKey = new Key("foo_key");
    assertEquals(entry, cache.getIfPresent(sameKey));
    Key otherKey = new Key("bar_key");
    File mockFile = Mockito.mock(File.class);
    Mockito.when(mockFile.exists()).thenReturn(false);
    Mockito.when(mockUtil.newFile(CACHE_DIR + "/bar_key.entry"))
        .thenReturn(mockFile);
    // A different key misses the cache.
    Entry otherEntry = cache.getIfPresent(otherKey);
    verify(mockUtil).newFile(CACHE_DIR + "/bar_key.entry");
    assertNull(otherEntry);
  }

  @Test
  public void testStaleCacheFile_goodEntry() {
    Key key = cache.newKeyBuilder().build();
    Mockito.when(mockUtil.currentTimeMillis()).thenReturn(2000000L * 1000);
    Entry entry = cache.createEntry(key, "ffx", TIME_100000_SECS);
    File cachedfile = Mockito.mock(File.class);
    Mockito.when(cachedfile.lastModified()).thenReturn(2004000L * 1000);
    Mockito.when(cachedfile.getPath()).thenReturn("cached_file");
    Mockito.when(mockUtil.currentTimeMillis()).thenReturn(2008000L * 1000);
    assertFalse(cache.staleCacheFile(mockHttpQuery, entry, cachedfile));
  }

  @Test
  public void testStaleCacheFile_noCachedFile() {
    Key key = cache.newKeyBuilder().build();
    Mockito.when(mockUtil.currentTimeMillis()).thenReturn(2000000L * 1000);
    Entry entry = cache.createEntry(key, "ffx", TIME_100000_SECS);
    File cachedfile = Mockito.mock(File.class);
    Mockito.when(cachedfile.lastModified()).thenReturn(0L);
    Mockito.when(cachedfile.getPath()).thenReturn("cached_file");
    Mockito.when(mockUtil.currentTimeMillis()).thenReturn(2008000L * 1000);
    assertTrue(cache.staleCacheFile(mockHttpQuery, entry, cachedfile));
  }

  @Test
  public void testStaleCacheFile_expiredEntry() {
    Key key = cache.newKeyBuilder().build();
    Mockito.when(mockUtil.currentTimeMillis()).thenReturn(1000000L * 1000);
    Entry entry = cache.createEntry(key, "ffx", TIME_100000_SECS);
    File cachedfile = Mockito.mock(File.class);
    Mockito.when(cachedfile.lastModified()).thenReturn(2000000L * 1000);
    Mockito.when(cachedfile.getPath()).thenReturn("cached_file");
    Mockito.when(mockUtil.currentTimeMillis()).thenReturn(2008000L * 1000);
    assertTrue(cache.staleCacheFile(mockHttpQuery, entry, cachedfile));
  }

  private int clientCacheTtl(final HttpQuery query,
      final long startTimeSecs,
      final long endTimeSecs,
      final long nowSecs) {
    return QueryResultFileCache.clientCacheTtl(query, startTimeSecs,
                                               endTimeSecs, nowSecs);
  }

  @Test
  public void testClientCacheTtl_defaultTTL() {
    Mockito.when(mockHttpQuery.getQueryStringParam("start"))
        .thenReturn("1000000");
    Mockito.when(mockHttpQuery.getQueryStringParam("end"))
        .thenReturn("2000000");
    int ttlSecs = clientCacheTtl(mockHttpQuery, 1000000, 2000000, 3000000);
    assertEquals(86400, ttlSecs);
  }

  @Test
  public void testClientCacheTtl_futureQuery() {
    int ttlSecs = clientCacheTtl(mockHttpQuery, 1000000, 2000000, 1500000);
    assertEquals(0, ttlSecs);
  }

  @Test
  public void testClientCacheTtl_closeToNow() {
    int ttlSecs = clientCacheTtl(mockHttpQuery, 1000000, 2000000, 2001000);
    assertEquals((2000000 - 1000000) >> 10, ttlSecs);
  }

  @Test
  public void testClientCacheTtl_relativeStart() {
    Mockito.when(mockHttpQuery.getQueryStringParam("start"))
        .thenReturn("100d-ago");
    Mockito.when(mockHttpQuery.getQueryStringParam("end"))
        .thenReturn("2000000");
    int ttlSecs = clientCacheTtl(mockHttpQuery, 1000000, 2000000, 3000000);
    assertEquals((2000000 - 1000000) >> 10, ttlSecs);
  }

  @Test
  public void testClientCacheTtl_relativeEnd() {
    Mockito.when(mockHttpQuery.getQueryStringParam("start"))
        .thenReturn("1000000");
    Mockito.when(mockHttpQuery.getQueryStringParam("end"))
        .thenReturn("2h-ago");
    int ttlSecs = clientCacheTtl(mockHttpQuery, 1000000, 2000000, 3000000);
    assertEquals((2000000 - 1000000) >> 10, ttlSecs);
  }

  private int serverCacheTtl(final HttpQuery query,
                             final long startTimeSecs,
                             final long endTimeSecs,
                             final long nowSecs) {
    return QueryResultFileCache.serverCacheTtl(query, startTimeSecs,
                                               endTimeSecs, nowSecs);
  }

  @Test
  public void testServerCacheTtl_veryOldTimeRange() {
    int ttlSecs = serverCacheTtl(mockHttpQuery, 1000000, 2000000, 3000000);
    assertEquals(100 * 86400, ttlSecs);
  }

  @Test
  public void testServerCacheTtl_recentTimeRange() {
    Mockito.when(mockHttpQuery.getQueryStringParam("start"))
        .thenReturn("1000000");
    Mockito.when(mockHttpQuery.getQueryStringParam("end"))
        .thenReturn("2000000");
    int ttlSecs = serverCacheTtl(mockHttpQuery, 1000000, 2000000, 2004000);
    assertEquals(86400, ttlSecs);
  }

  @Test
  public void testKey_constructor() {
    Key key = new Key("foo_key");
    assertEquals("foo_key", key.getKeyForTesting());
  }

  @Test
  public void testKeyBuilder() {
    KeyBuilder keyBuilder = cache.newKeyBuilder();
    keyBuilder.setCacheType("fooCache");
    assertThat(keyBuilder.build().getKeyForTesting(), containsString("fooCache"));
    keyBuilder.setStartTime(100);
    assertThat(keyBuilder.build().getKeyForTesting(), containsString("-100"));
    keyBuilder.setEndTime(170);
    assertThat(keyBuilder.build().getKeyForTesting(), containsString("-170"));
    keyBuilder.setSuffix("ffx");
    Key key = keyBuilder.build();
    assertThat(key.getKeyForTesting(), containsString("ffx"));
  }

  @Test
  public void testKeyBuilder_query() {
    KeyBuilder keyBuilder = cache.newKeyBuilder();
    final HashMap<String, List<String>> qs = Maps.newHashMap();
    qs.put("start", Lists.newArrayList("100"));
    qs.put("end", Lists.newArrayList("170"));
    qs.put("ms", Lists.newArrayList("a", "b"));
    String queryHash = String.format("-%08x", qs.hashCode());
    Mockito.when(mockHttpQuery.getQueryString()).thenReturn(qs);
    keyBuilder.setQuery(mockHttpQuery);
    assertThat(keyBuilder.build().getKeyForTesting(), containsString(queryHash));
  }

  @Test
  public void testKeyBuilder_queryRemoveParams() {
    KeyBuilder keyBuilder = cache.newKeyBuilder();
    final HashMap<String, List<String>> qs = Maps.newHashMap();
    qs.put("start", Lists.newArrayList("100"));
    qs.put("end", Lists.newArrayList("170"));
    qs.put("ms", Lists.newArrayList("a", "b"));
    String queryHash = String.format("-%08x", qs.hashCode());
    qs.put("foo", Lists.newArrayList("baz"));
    Mockito.when(mockHttpQuery.getQueryString()).thenReturn(qs);
    keyBuilder.setQuery(mockHttpQuery);
    keyBuilder.addQueryParameterToIgnore("foo");
    // The "foo" query param should be deleted before calculating the hash code.
    assertFalse(queryHash.equals(String.format("-%08x", qs.hashCode())));
    assertThat(keyBuilder.build().getKeyForTesting(), containsString(queryHash));
  }
}
