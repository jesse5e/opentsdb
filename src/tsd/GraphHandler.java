// This file is part of OpenTSDB.
// Copyright (C) 2010-2012  The OpenTSDB Authors.
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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.common.annotations.VisibleForTesting;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;

import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.opentsdb.core.DataPoint;
import net.opentsdb.core.DataPoints;
import net.opentsdb.core.Query;
import net.opentsdb.core.TSDB;
import net.opentsdb.core.TSQuery;
import net.opentsdb.graph.Plot;
import net.opentsdb.stats.Histogram;
import net.opentsdb.stats.StatsCollector;
import net.opentsdb.tsd.QueryResultFileCache.Entry;
import net.opentsdb.tsd.QueryResultFileCache.KeyBuilder;
import net.opentsdb.utils.DateTime;
import net.opentsdb.utils.JSON;

/**
 * Stateless handler of HTTP graph requests (the {@code /q} endpoint).
 */
final class GraphHandler implements HttpRpc {

  private static final Logger LOG =
    LoggerFactory.getLogger(GraphHandler.class);

  private static final boolean IS_WINDOWS = 
    System.getProperty("os.name").contains("Windows");
  
  /** Number of times we had to do all the work up to running Gnuplot. */
  private static final AtomicInteger graphs_generated
    = new AtomicInteger();
  /** Number of times a graph request was served from disk, no work needed. */
  private static final AtomicInteger graphs_diskcache_hit
    = new AtomicInteger();

  /** Keep track of the latency of graphing requests. */
  private static final Histogram graphlatency =
    new Histogram(16000, (short) 2, 100);

  /** Keep track of the latency (in ms) introduced by running Gnuplot. */
  private static final Histogram gnuplotlatency =
    new Histogram(16000, (short) 2, 100);

  /** Executor to run Gnuplot in separate bounded thread pool. */
  private final ThreadPoolExecutor gnuplot;

  /** Caches results of queries. */
  private final QueryResultFileCache queryCache;

  public GraphHandler(QueryResultFileCache queryCache) {
    this(queryCache, newThreadPoolExecutor());
  }

  /** Creates thread pool to execute gnuplot. */
  private static ThreadPoolExecutor newThreadPoolExecutor() {
    // Gnuplot is mostly CPU bound and does only a little bit of IO at the
    // beginning to read the input data and at the end to write its output.
    // We want to avoid running too many Gnuplot instances concurrently as
    // it can steal a significant number of CPU cycles from us.  Instead, we
    // allow only one per core, and we nice it (the nicing is done in the
    // shell script we use to start Gnuplot).  Similarly, the queue we use
    // is sized so as to have a fixed backlog per core.
    final int ncores = Runtime.getRuntime().availableProcessors();
    return new ThreadPoolExecutor(
      ncores, ncores,  // Thread pool of a fixed size.
      /* 5m = */ 300000, MILLISECONDS,        // How long to keep idle threads.
      new ArrayBlockingQueue<Runnable>(20 * ncores),  // XXX Don't hardcode?
      thread_factory);
    // ArrayBlockingQueue does not scale as much as LinkedBlockingQueue in terms
    // of throughput but we don't need high throughput here.  We use ABQ instead
    // of LBQ because it creates far fewer references.
  }

  @VisibleForTesting
  GraphHandler(final QueryResultFileCache queryCache,
               final ThreadPoolExecutor gnuplot) {
    this.queryCache = queryCache;
    this.gnuplot = gnuplot;
  }

  public void execute(final TSDB tsdb, final HttpQuery query) {
    if (!query.hasQueryStringParam("json")
        && !query.hasQueryStringParam("png")
        && !query.hasQueryStringParam("ascii")) {
      String uri = query.request().getUri();
      if (uri.length() < 4) {  // Shouldn't happen...
        uri = "/";             // But just in case, redirect.
      } else {
        uri = "/#" + uri.substring(3);  // Remove "/q?"
      }
      query.redirect(uri);
      return;
    }
    try {
      doGraph(tsdb, query);
    } catch (IOException e) {
      query.internalError(e);
    } catch (IllegalArgumentException e) {
      query.badRequest(e.getMessage());
    }
  }

  /** Builds TSDB queries from TSQuery. */
  private Query[] buildQueries(final TSDB tsdb, final HttpQuery query) {
    TSQuery tsQuery = QueryRpc.parseQuery(query);
    if (tsQuery.getEnd() == null || tsQuery.getEnd().isEmpty()) {
      // NOTE: Set the end of the query time range with now when no end time
      // is specified. BTW, "0s-ago" is not supported by the DataTime class.
      tsQuery.setEnd("1s-ago");
    }
    try {
      // validate and then compile the queries
      LOG.debug(tsQuery.toString());
      tsQuery.validateAndSetQuery();
    } catch (Exception e) {
      throw new BadRequestException(HttpResponseStatus.BAD_REQUEST,
          e.getMessage(), tsQuery.toString(), e);
    }
    return tsQuery.buildQueries(tsdb);
  }

  private void doGraph(final TSDB tsdb, final HttpQuery query)
    throws IOException {
    Query[] tsdbqueries = buildQueries(tsdb, query);
    // NOTE: Every query entry in tsdbqueries has the same time range.
    // temp fixup to seconds from ms until the rest of TSDB supports ms
    final long startSecs = TimeUnit.MILLISECONDS.toSeconds(
        tsdbqueries[0].getStartTime());
    final long endSecs = TimeUnit.MILLISECONDS.toSeconds(
        tsdbqueries[0].getEndTime());
    final CacheEntries cacheEntries = new CacheEntries(queryCache, query,
        startSecs, endSecs);
    final boolean nocache = query.hasQueryStringParam("nocache");
    if (!nocache && isDiskCacheHit(query, cacheEntries)) {
      return;
    }
    List<String> options;

    options = query.getQueryStringParams("o");
    if (options == null) {
      options = new ArrayList<String>(tsdbqueries.length);
      for (int i = 0; i < tsdbqueries.length; i++) {
        options.add("");
      }
    } else if (options.size() != tsdbqueries.length) {
      throw new BadRequestException(options.size() + " `o' parameters, but "
        + tsdbqueries.length + " `m' parameters.");
    }
    for (final Query tsdbquery : tsdbqueries) {
      try {
        tsdbquery.setStartTime(startSecs);
      } catch (IllegalArgumentException e) {
        throw new BadRequestException("start time: " + e.getMessage());
      }
      try {
        tsdbquery.setEndTime(endSecs);
      } catch (IllegalArgumentException e) {
        throw new BadRequestException("end time: " + e.getMessage());
      }
    }
    final Plot plot = new Plot(startSecs, endSecs,
          DateTime.timezones.get(query.getQueryStringParam("tz")));
    setPlotDimensions(query, plot);
    setPlotParams(query, plot);
    final int nqueries = tsdbqueries.length;
    @SuppressWarnings("unchecked")
    final HashSet<String>[] aggregated_tags = new HashSet[nqueries];
    int npoints = 0;
    for (int i = 0; i < nqueries; i++) {
      try {  // execute the TSDB query!
        // XXX This is slow and will block Netty.  TODO(tsuna): Don't block.
        // TODO(tsuna): Optimization: run each query in parallel.
        final DataPoints[] series = tsdbqueries[i].run();
        for (final DataPoints datapoints : series) {
          plot.add(datapoints, options.get(i));
          aggregated_tags[i] = new HashSet<String>();
          aggregated_tags[i].addAll(datapoints.getAggregatedTags());
          npoints += datapoints.aggregatedSize();
        }
      } catch (RuntimeException e) {
        logInfo(query, "Query failed (stack trace coming): "
                + tsdbqueries[i]);
        throw e;
      }
      tsdbqueries[i] = null;  // free()
    }
    tsdbqueries = null;  // free()

    if (query.hasQueryStringParam("ascii")) {
      respondAsciiQuery(query, cacheEntries, plot);
      return;
    }

    try {
      gnuplot.execute(new RunGnuplot(query, queryCache, cacheEntries, plot,
                                     aggregated_tags, npoints));
    } catch (RejectedExecutionException e) {
      query.internalError(new Exception("Too many requests pending,"
                                        + " please try again later", e));
    }
  }

  // Runs Gnuplot in a subprocess to generate the graph.
  private static final class RunGnuplot implements Runnable {

    private final HttpQuery query;
    private final QueryResultFileCache queryCache;
    private final CacheEntries cacheEntries;
    private final Plot plot;
    private final HashSet<String>[] aggregated_tags;
    private final int npoints;

    public RunGnuplot(final HttpQuery query,
                      final QueryResultFileCache queryCache,
                      final CacheEntries cacheEntries,
                      final Plot plot,
                      final HashSet<String>[] aggregated_tags,
                      final int npoints) {
      this.query = query;
      this.queryCache = queryCache;
      this.cacheEntries = cacheEntries;
      this.plot = plot;
      this.aggregated_tags = aggregated_tags;
      this.npoints = npoints;
    }

    public void run() {
      try {
        execute();
      } catch (BadRequestException e) {
        query.badRequest(e.getMessage());
      } catch (GnuplotException e) {
        query.badRequest("<pre>" + e.getMessage() + "</pre>");
      } catch (RuntimeException e) {
        query.internalError(e);
      } catch (IOException e) {
        query.internalError(e);
      }
    }

    private void execute() throws IOException {
      // runGnuplot plots all data into a file named basepath + ".png".
      final String basepath = cacheEntries.png.getBasepath();
      final int nplotted = runGnuplot(query, basepath, plot);
      if (query.hasQueryStringParam("json")) {
        final HashMap<String, Object> results = new HashMap<String, Object>();
        results.put("plotted", nplotted);
        results.put("points", npoints);
        // 1.0 returned an empty inner array if the 1st hashset was null, to do
        // the same we need to fudge it with an empty set
        if (aggregated_tags != null && aggregated_tags.length > 0 &&
            aggregated_tags[0] == null) {
          aggregated_tags[0] = new HashSet<String>();
        }
        results.put("etags", aggregated_tags);
        results.put("timing", query.processingTimeMillis());
        query.sendReply(JSON.serializeToBytes(results));
        writeFile(query, cacheEntries.json.getDataFilePath(),
                  JSON.serializeToBytes(results));
        queryCache.put(cacheEntries.json);
        // JSON and PNG files should be cached together.
        queryCache.put(cacheEntries.png);
      } else if (query.hasQueryStringParam("png")) {
        query.sendFile(cacheEntries.png.getDataFilePath(),
                       cacheEntries.clientCacheTtlSecs);
        queryCache.put(cacheEntries.png);
      } else {
        query.internalError(new Exception("Should never be here!"));
      }

      // TODO(tsuna): Expire old files from the on-disk cache.
      graphlatency.add(query.processingTimeMillis());
      graphs_generated.incrementAndGet();
    }

  }

  /** Shuts down the thread pool used to run Gnuplot.  */
  public void shutdown() {
    gnuplot.shutdown();
  }

  /**
   * Collects the stats and metrics tracked by this instance.
   * @param collector The collector to use.
   */
  public static void collectStats(final StatsCollector collector) {
    collector.record("http.latency", graphlatency, "type=graph");
    collector.record("http.latency", gnuplotlatency, "type=gnuplot");
    collector.record("http.graph.requests", graphs_diskcache_hit, "cache=disk");
    collector.record("http.graph.requests", graphs_generated, "cache=miss");
  }

  /**
   * Checks whether or not it's possible to re-serve this query from disk.
   * @param query The query to serve.
   * @param cacheEntries Cache entries for different file formats
   * @return {@code true} if this request was served from disk (in which
   * case processing can stop here), {@code false} otherwise (in which case
   * the query needs to be processed).
   */
  private boolean isDiskCacheHit(final HttpQuery query,
                                 final CacheEntries cacheEntries)
                                 throws IOException {
    final Entry cachedEntry;
    if (query.hasQueryStringParam("ascii")) {
      cachedEntry = queryCache.getIfPresent(cacheEntries.ascii.getKey());
    } else {
      cachedEntry = queryCache.getIfPresent(cacheEntries.png.getKey());
    }
    if (cachedEntry != null) {
      final String cachepath = cachedEntry.getDataFilePath();
      final File cachedfile = new File(cachepath);
      if (cachedfile.exists()) {
        final long bytes = cachedfile.length();
        if (bytes < 21) {  // Minimum possible size for a PNG: 21 bytes.
                           // For .txt files, <21 bytes is almost impossible.
          logWarn(query, "Cached " + cachepath + " is too small ("
                  + bytes + " bytes) to be valid.  Ignoring it.");
          return false;
        }
        if (queryCache.staleCacheFile(query, cachedEntry, cachedfile)) {
          return false;
        }
        if (query.hasQueryStringParam("json")) {
          HashMap<String, Object> map = loadCachedJson(query, cacheEntries);
          if (map == null) {
            map = new HashMap<String, Object>();
          }
          map.put("timing", query.processingTimeMillis());
          map.put("cachehit", "disk");
          query.sendReply(JSON.serializeToBytes(map));
        } else if (query.hasQueryStringParam("png")
                   || query.hasQueryStringParam("ascii")) {
          query.sendFile(cachepath, cacheEntries.clientCacheTtlSecs);
        } else {
          query.sendReply(HttpQuery.makePage("TSDB Query", "Your graph is ready",
              "<img src=\"" + query.request().getUri() + "&amp;png\"/><br/>"
              + "<small>(served from disk cache)</small>"));
        }
        graphs_diskcache_hit.incrementAndGet();
        return true;
      }
    }
    // We didn't find an image.  Do a negative cache check.  If we've seen
    // this query before but there was no result, we at least wrote the JSON.
    final HashMap<String, Object> map = loadCachedJson(query, cacheEntries);
    // If we don't have a JSON file it's a complete cache miss.  If we have
    // one, and it says 0 data points were plotted, it's a negative cache hit.
    if (map == null || !map.containsKey("plotted") || 
        ((Integer)map.get("plotted")) == 0) {
      return false;
    }
    if (query.hasQueryStringParam("json")) {
      map.put("timing", query.processingTimeMillis());
      map.put("cachehit", "disk");
      query.sendReply(JSON.serializeToBytes(map));
    } else if (query.hasQueryStringParam("png")) {
      query.sendReply(" ");  // Send back an empty response...
    } else {
        query.sendReply(HttpQuery.makePage("TSDB Query", "No results",
            "Sorry, your query didn't return anything.<br/>"
            + "<small>(served from disk cache)</small>"));
    }
    graphs_diskcache_hit.incrementAndGet();
    return true;
  }

  /**
   * Writes the given byte array into a file.
   * This function logs an error but doesn't throw if it fails.
   * @param query The query being handled (for logging purposes).
   * @param path The path to write to.
   * @param contents The contents to write into the file.
   */
  private static void writeFile(final HttpQuery query,
                                final String path,
                                final byte[] contents) {
    try {
      final FileOutputStream out = new FileOutputStream(path);
      try {
        out.write(contents);
      } finally {
        out.close();
      }
    } catch (FileNotFoundException e) {
      logError(query, "Failed to create file " + path, e);
    } catch (IOException e) {
      logError(query, "Failed to write file " + path, e);
    }
  }

  /**
   * Reads a file into a byte array.
   * @param query The query being handled (for logging purposes).
   * @param file The file to read.
   * @param max_length The maximum number of bytes to read from the file.
   * @return {@code null} if the file doesn't exist or is empty or couldn't be
   * read, otherwise a byte array of up to {@code max_length} bytes.
   */
  private static byte[] readFile(final HttpQuery query,
                                 final File file,
                                 final int max_length) {
    final int length = (int) file.length();
    if (length <= 0) {
      return null;
    }
    FileInputStream in;
    try {
      in = new FileInputStream(file.getPath());
    } catch (FileNotFoundException e) {
      return null;
    }
    try {
      final byte[] buf = new byte[Math.min(length, max_length)];
      final int read = in.read(buf);
      if (read != buf.length) {
        logError(query, "When reading " + file + ": read only "
                 + read + " bytes instead of " + buf.length);
        return null;
      }
      return buf;
    } catch (IOException e) {
      logError(query, "Error while reading " + file, e);
      return null;
    } finally {
      try {
        in.close();
      } catch (IOException e) {
        logError(query, "Error while closing " + file, e);
      }
    }
  }

  /**
   * Attempts to read the cached {@code .json} file for this query.
   * @param query The query to serve.
   * @param cacheEntries Cache entries for different file formats
   * @return {@code null} in case no file was found, or the contents of the
   * file if it was found.
   * @throws IOException If the file cannot be loaded
   * @throws JsonMappingException If the JSON cannot be parsed to a HashMap
   * @throws JsonParseException If the JSON is improperly formatted
   */
  @SuppressWarnings("unchecked")
  private HashMap<String, Object> loadCachedJson(final HttpQuery query,
                                       final CacheEntries cacheEntries)
                                       throws JsonParseException,
                                       JsonMappingException, IOException {
    Entry entry = queryCache.getIfPresent(cacheEntries.json.getKey());
    if (entry == null) {
      // No cached json.
      return null;
    }
    File json_cache = new File(entry.getDataFilePath());
    if (queryCache.staleCacheFile(query, cacheEntries.json, json_cache)) {
      return null;
    }
    final byte[] json = readFile(query, json_cache, 4096);
    if (json == null) {
      return null;
    }
    json_cache = null;
    
    return (HashMap<String, Object>) JSON.parseToObject(json, HashMap.class);
  }

  /** Parses the {@code wxh} query parameter to set the graph dimension. */
  static void setPlotDimensions(final HttpQuery query, final Plot plot) {
    final String wxh = query.getQueryStringParam("wxh");
    if (wxh != null && !wxh.isEmpty()) {
      final int wxhlength = wxh.length();
      if (wxhlength < 7) {  // 100x100 minimum.
        throw new BadRequestException("Parameter wxh too short: " + wxh);
      }
      final int x = wxh.indexOf('x', 3);  // Start at 2 as min size is 100x100
      if (x < 0) {
        throw new BadRequestException("Invalid wxh parameter: " + wxh);
      }
      try {
        final short width = Short.parseShort(wxh.substring(0, x));
        final short height = Short.parseShort(wxh.substring(x + 1, wxhlength));
        try {
          plot.setDimensions(width, height);
        } catch (IllegalArgumentException e) {
          throw new BadRequestException("Invalid wxh parameter: " + wxh + ", "
                                        + e.getMessage());
        }
      } catch (NumberFormatException e) {
        throw new BadRequestException("Can't parse wxh '" + wxh + "': "
                                      + e.getMessage());
      }
    }
  }

  /**
   * Formats and quotes the given string so it's a suitable Gnuplot string.
   * @param s The string to stringify.
   * @return A string suitable for use as a literal string in Gnuplot.
   */
  private static String stringify(final String s) {
    final StringBuilder buf = new StringBuilder(1 + s.length() + 1);
    buf.append('"');
    HttpQuery.escapeJson(s, buf);  // Abusing this function gets the job done.
    buf.append('"');
    return buf.toString();
  }

  /**
   * Pops out of the query string the given parameter.
   * @param querystring The query string.
   * @param param The name of the parameter to pop out.
   * @return {@code null} if the parameter wasn't passed, otherwise the
   * value of the last occurrence of the parameter.
   */
  private static String popParam(final Map<String, List<String>> querystring,
                                     final String param) {
    final List<String> params = querystring.remove(param);
    if (params == null) {
      return null;
    }
    return params.get(params.size() - 1);
  }

  /**
   * Applies the plot parameters from the query to the given plot.
   * @param query The query from which to get the query string.
   * @param plot The plot on which to apply the parameters.
   */
  static void setPlotParams(final HttpQuery query, final Plot plot) {
    final HashMap<String, String> params = new HashMap<String, String>();
    final Map<String, List<String>> querystring = query.getQueryString();
    String value;
    if ((value = popParam(querystring, "yrange")) != null) {
      params.put("yrange", value);
    }
    if ((value = popParam(querystring, "y2range")) != null) {
      params.put("y2range", value);
    }
    if ((value = popParam(querystring, "ylabel")) != null) {
      params.put("ylabel", stringify(value));
    }
    if ((value = popParam(querystring, "y2label")) != null) {
      params.put("y2label", stringify(value));
    }
    if ((value = popParam(querystring, "yformat")) != null) {
      params.put("format y", stringify(value));
    }
    if ((value = popParam(querystring, "y2format")) != null) {
      params.put("format y2", stringify(value));
    }
    if ((value = popParam(querystring, "xformat")) != null) {
      params.put("format x", stringify(value));
    }
    if ((value = popParam(querystring, "ylog")) != null) {
      params.put("logscale y", "");
    }
    if ((value = popParam(querystring, "y2log")) != null) {
      params.put("logscale y2", "");
    }
    if ((value = popParam(querystring, "key")) != null) {
      params.put("key", value);
    }
    if ((value = popParam(querystring, "title")) != null) {
      params.put("title", stringify(value));
    }
    if ((value = popParam(querystring, "bgcolor")) != null) {
      params.put("bgcolor", value);
    }
    if ((value = popParam(querystring, "fgcolor")) != null) {
      params.put("fgcolor", value);
    }
    if ((value = popParam(querystring, "smooth")) != null) {
      params.put("smooth", value);
    }
    // This must remain after the previous `if' in order to properly override
    // any previous `key' parameter if a `nokey' parameter is given.
    if ((value = popParam(querystring, "nokey")) != null) {
      params.put("key", null);
    }
    plot.setParams(params);
  }

  /**
   * Runs Gnuplot in a subprocess to generate the graph.
   * <strong>This function will block</strong> while Gnuplot is running.
   * @param query The query being handled (for logging purposes).
   * @param basepath The base path used for the Gnuplot files.
   * @param plot The plot object to generate Gnuplot's input files.
   * @return The number of points plotted by Gnuplot (0 or more).
   * @throws IOException if the Gnuplot files can't be written, or
   * the Gnuplot subprocess fails to start, or we can't read the
   * graph from the file it produces, or if we have been interrupted.
   * @throws GnuplotException if Gnuplot returns non-zero.
   */
  static int runGnuplot(final HttpQuery query,
                        final String basepath,
                        final Plot plot) throws IOException {
    final int nplotted = plot.dumpToFiles(basepath);
    final long start_time = System.nanoTime();
    final Process gnuplot = new ProcessBuilder(GNUPLOT,
      basepath + ".out", basepath + ".err", basepath + ".gnuplot").start();
    final int rv;
    try {
      rv = gnuplot.waitFor();  // Couldn't find how to do this asynchronously.
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();  // Restore the interrupted status.
      throw new IOException("interrupted", e);  // I hate checked exceptions.
    } finally {
      // We need to always destroy() the Process, otherwise we "leak" file
      // descriptors and pipes.  Unless I'm blind, this isn't actually
      // documented in the Javadoc of the !@#$%^ JDK, and in Java 6 there's no
      // way to ask the stupid-ass ProcessBuilder to not create fucking pipes.
      // I think when the GC kicks in the JVM may run some kind of a finalizer
      // that closes the pipes, because I've never seen this issue on long
      // running TSDs, except where ulimit -n was low (the default, 1024).
      gnuplot.destroy();
    }
    gnuplotlatency.add((int) ((System.nanoTime() - start_time) / 1000000));
    if (rv != 0) {
      final byte[] stderr = readFile(query, new File(basepath + ".err"),
                                     4096);
      // Sometimes Gnuplot will error out but still create the file.
      new File(basepath + ".png").delete();
      if (stderr == null) {
        throw new GnuplotException(rv);
      }
      throw new GnuplotException(new String(stderr));
    }
    // Remove the files for stderr/stdout if they're empty.
    deleteFileIfEmpty(basepath + ".out");
    deleteFileIfEmpty(basepath + ".err");
    return nplotted;
  }

  private static void deleteFileIfEmpty(final String path) {
    final File file = new File(path);
    if (file.length() <= 0) {
      file.delete();
    }
  }

  /**
   * Respond to a query that wants the output in ASCII.
   * <p>
   * When a query specifies the "ascii" query string parameter, we send the
   * data points back to the client in plain text instead of sending a PNG.
   * @param query The query we're currently serving.
   * @param cacheEntries Cache entries for different file formats
   * @param plot The plot object to generate Gnuplot's input files.
   */
  private void respondAsciiQuery(final HttpQuery query,
                                 final CacheEntries cacheEntries,
                                 final Plot plot) {
    final String path = cacheEntries.ascii.getDataFilePath();
    PrintWriter asciifile;
    try {
      asciifile = new PrintWriter(path);
    } catch (IOException e) {
      query.internalError(e);
      return;
    }
    try {
      final StringBuilder tagbuf = new StringBuilder();
      for (final DataPoints dp : plot.getDataPoints()) {
        final String metric = dp.metricName();
        tagbuf.setLength(0);
        for (final Map.Entry<String, String> tag : dp.getTags().entrySet()) {
          tagbuf.append(' ').append(tag.getKey())
            .append('=').append(tag.getValue());
        }
        for (final DataPoint d : dp) {
          asciifile.print(metric);
          asciifile.print(' ');
          asciifile.print((d.timestamp() / 1000));
          asciifile.print(' ');
          if (d.isInteger()) {
            asciifile.print(d.longValue());
          } else {
            final double value = d.doubleValue();
            if (value != value || Double.isInfinite(value)) {
              throw new IllegalStateException("NaN or Infinity:" + value
                + " d=" + d + ", query=" + query);
            }
            asciifile.print(value);
          }
          asciifile.print(tagbuf);
          asciifile.print('\n');
        }
      }
    } finally {
      asciifile.close();
    }
    try {
      query.sendFile(path, cacheEntries.clientCacheTtlSecs);
      queryCache.put(cacheEntries.ascii);
    } catch (IOException e) {
      query.internalError(e);
    }
  }

  private static final PlotThdFactory thread_factory = new PlotThdFactory();

  private static final class PlotThdFactory implements ThreadFactory {
    private final AtomicInteger id = new AtomicInteger(0);

    public Thread newThread(final Runnable r) {
      return new Thread(r, "Gnuplot #" + id.incrementAndGet());
    }
  }

  /** Name of the wrapper script we use to execute Gnuplot.  */
  private static final String WRAPPER = 
    IS_WINDOWS ? "mygnuplot.bat" : "mygnuplot.sh";
  
  /** Path to the wrapper script.  */
  private static final String GNUPLOT;
  static {
    GNUPLOT = findGnuplotHelperScript();
  }

  /**
   * Iterate through the class path and look for the Gnuplot helper script.
   * @return The path to the wrapper script.
   */
  private static String findGnuplotHelperScript() {
    final URL url = GraphHandler.class.getClassLoader().getResource(WRAPPER);
    if (url == null) {
      throw new RuntimeException("Couldn't find " + WRAPPER + " on the"
        + " CLASSPATH: " + System.getProperty("java.class.path"));
    }
    final String path = url.getFile();
    LOG.debug("Using Gnuplot wrapper at {}", path);
    final File file = new File(path);
    final String error;
    if (!file.exists()) {
      error = "non-existent";
    } else if (!file.canExecute()) {
      error = "non-executable";
    } else if (!file.canRead()) {
      error = "unreadable";
    } else {
      return path;
    }
    throw new RuntimeException("The " + WRAPPER + " found on the"
      + " CLASSPATH (" + path + ") is a " + error + " file...  WTF?"
      + "  CLASSPATH=" + System.getProperty("java.class.path"));
  }

  /** Plain Old Data structure of cache entries of different formats. */
  private static final class CacheEntries {

    /** The current wall-clock time in seconds. */
    private final long nowSecs;
    /** The maximum time in which results cached by a client are valid. */
    private final int clientCacheTtlSecs;
    /** The maximum time in which results cached by a server are valid. */
    private final int serverCacheTtlSecs;
    /** PNG file format cache entry. */
    private final Entry png;
    /** JSON file format cache entry. */
    private final Entry json;
    /** Text file format cache entry. */
    private final Entry ascii;

    CacheEntries(final QueryResultFileCache queryCache, final HttpQuery query,
              final long startSecs, final long endSecs) {
      KeyBuilder builder = queryCache.newKeyBuilder()
          .setCacheType("graph")
          .setQuery(query)
          .setStartTime(startSecs)
          .setEndTime(endSecs)
          .addQueryParameterToIgnore("ignore")
          .addQueryParameterToIgnore("png")
          .addQueryParameterToIgnore("json")
          .addQueryParameterToIgnore("ascii");
      nowSecs = System.currentTimeMillis() / 1000;
      clientCacheTtlSecs = QueryResultFileCache.clientCacheTtl(
          query, startSecs, endSecs, nowSecs);
      serverCacheTtlSecs = QueryResultFileCache.serverCacheTtl(
          query, startSecs, endSecs, nowSecs);
      png = queryCache.createEntry(builder.setSuffix("png").build(), "png",
                                   serverCacheTtlSecs);
      json = queryCache.createEntry(builder.setSuffix("json").build(), "json",
                                    serverCacheTtlSecs);
      ascii = queryCache.createEntry(builder.setSuffix("txt").build(), "txt",
                                     serverCacheTtlSecs);
    }
  }

  // ---------------- //
  // Logging helpers. //
  // ---------------- //

  static void logInfo(final HttpQuery query, final String msg) {
    LOG.info(query.channel().toString() + ' ' + msg);
  }

  static void logWarn(final HttpQuery query, final String msg) {
    LOG.warn(query.channel().toString() + ' ' + msg);
  }

  static void logError(final HttpQuery query, final String msg) {
    LOG.error(query.channel().toString() + ' ' + msg);
  }

  static void logError(final HttpQuery query, final String msg,
                       final Throwable e) {
    LOG.error(query.channel().toString() + ' ' + msg, e);
  }

}
