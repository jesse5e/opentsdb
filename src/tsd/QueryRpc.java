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
package net.opentsdb.tsd;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import org.jboss.netty.handler.codec.http.HttpMethod;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.stumbleupon.async.Callback;
import com.stumbleupon.async.Deferred;

import net.opentsdb.core.BadTimeout;
import net.opentsdb.core.DataPoints;
import net.opentsdb.core.Query;
import net.opentsdb.core.RateOptions;
import net.opentsdb.core.TSDB;
import net.opentsdb.core.TSQuery;
import net.opentsdb.core.TSSubQuery;
import net.opentsdb.core.Tags;
import net.opentsdb.meta.Annotation;

/**
 * Handles queries for timeseries datapoints. Each request is parsed into a
 * TSQuery object, the values given validated, and if all tests pass, the
 * query is converted into TsdbQueries and each one is executed to fetch the
 * data. The resulting DataPoints[] are then passed to serializers for 
 * formatting.
 * <p>
 * Some private methods are included for parsing query string data into a 
 * TSQuery object.
 * @since 2.0
 */
final class QueryRpc implements HttpRpc {
  private static final Logger LOG = LoggerFactory.getLogger(QueryRpc.class);
  
  /**
   * Implements the /api/query endpoint to fetch data from OpenTSDB.
   * @param tsdb The TSDB to use for fetching data
   * @param query The HTTP query for parsing and responding
   */
  @Override
  public void execute(final TSDB tsdb, final HttpQuery query) 
    throws IOException {
    
    // only accept GET/POST
    if (query.method() != HttpMethod.GET && query.method() != HttpMethod.POST) {
      throw new BadRequestException(HttpResponseStatus.METHOD_NOT_ALLOWED, 
          "Method not allowed", "The HTTP method [" + query.method().getName() +
          "] is not permitted for this endpoint");
    }
    
    final TSQuery data_query;
    if (query.method() == HttpMethod.POST) {
      switch (query.apiVersion()) {
      case 0:
      case 1:
        data_query = query.serializer().parseQueryV1();
        break;
      default: 
        throw new BadRequestException(HttpResponseStatus.NOT_IMPLEMENTED, 
            "Requested API version not implemented", "Version " + 
            query.apiVersion() + " is not implemented");
      }
    } else {
      data_query = this.parseQuery(tsdb, query);
    }
    
    // validate and then compile the queries
    try {
      LOG.debug(data_query.toString());
      data_query.validateAndSetQuery();
    } catch (Exception e) {
      throw new BadRequestException(HttpResponseStatus.BAD_REQUEST, 
          e.getMessage(), data_query.toString(), e);
    }
    
    Query[] tsdbqueries = data_query.buildQueries(tsdb);
    final int nqueries = tsdbqueries.length;
    final ArrayList<DataPoints[]> results = 
      new ArrayList<DataPoints[]>(nqueries);
    final ArrayList<Deferred<DataPoints[]>> deferreds =
      new ArrayList<Deferred<DataPoints[]>>(nqueries);
    
    for (int i = 0; i < nqueries; i++) {
      deferreds.add(tsdbqueries[i].runAsync());
    }

    /**
    * After all of the queries have run, we get the results in the order given
    * and add dump the results in an array
    */
    class QueriesCB implements Callback<Object, ArrayList<DataPoints[]>> {
      public Object call(final ArrayList<DataPoints[]> query_results) 
        throws Exception {
        results.addAll(query_results);
        return null;
      }
    }
    
    // if the user wants global annotations, we need to scan and fetch
    // TODO(cl) need to async this at some point. It's not super straight
    // forward as we can't just add it to the "deferreds" queue since the types
    // are different.
    List<Annotation> globals = null;
    if (!data_query.getNoAnnotations() && data_query.getGlobalAnnotations()) {
      try {
        globals = BadTimeout.minutes(Annotation.getGlobalAnnotations(tsdb,
            data_query.startTime() / 1000, data_query.endTime() / 1000));
      } catch (Exception e) {
        throw new RuntimeException("Shouldn't be here", e);
      }
    }
    
    try {
      BadTimeout.hour(Deferred.groupInOrder(deferreds).
          addCallback(new QueriesCB()));
    } catch (Exception e) {
      throw new RuntimeException("Shouldn't be here", e);
    }
    
    switch (query.apiVersion()) {
    case 0:
    case 1:
      query.sendReply(query.serializer().formatQueryV1(data_query, results, 
          globals));
      break;
    default: 
      throw new BadRequestException(HttpResponseStatus.NOT_IMPLEMENTED, 
          "Requested API version not implemented", "Version " + 
          query.apiVersion() + " is not implemented");
    }
  }

  /**
   * Parses a query string legacy style query from the URI
   * @param tsdb The TSDB we belong to
   * @param query The HTTP Query for parsing
   * @return A TSQuery if parsing was successful
   * @throws BadRequestException if parsing was unsuccessful
   */
  private TSQuery parseQuery(final TSDB tsdb, final HttpQuery query) {
    final TSQuery data_query = new TSQuery();
    
    data_query.setStart(query.getRequiredQueryStringParam("start"));
    data_query.setEnd(query.getQueryStringParam("end"));
    
    if (query.hasQueryStringParam("padding")) {
      data_query.setPadding(true);
    }
    
    if (query.hasQueryStringParam("no_annotations")) {
      data_query.setNoAnnotations(true);
    }
    
    if (query.hasQueryStringParam("global_annotations")) {
      data_query.setGlobalAnnotations(true);
    }
    
    if (query.hasQueryStringParam("show_tsuids")) {
      data_query.setShowTSUIDs(true);
    }
    
    if (query.hasQueryStringParam("ms")) {
      data_query.setMsResolution(true);
    }
    
    // handle tsuid queries first
    if (query.hasQueryStringParam("tsuid")) {
      final List<String> tsuids = query.getQueryStringParams("tsuid");     
      for (String q : tsuids) {
        this.parseTsuidTypeSubQuery(q, data_query);
      }
    }
    
    if (query.hasQueryStringParam("m")) {
      final List<String> legacy_queries = query.getQueryStringParams("m");      
      for (String q : legacy_queries) {
        this.parseMTypeSubQuery(q, data_query);
      }
    }
    
    if (data_query.getQueries() == null || data_query.getQueries().size() < 1) {
      throw new BadRequestException("Missing sub queries");
    }
    return data_query;
  }

  /**
   * Parses aggregator query parameters and constructs a {@link TSSubQuery}.
   * @param tokens An array of tokens.
   * @return A TSSubQuery instance
   * @throws BadRequestException if there is no tokens or any unknown token.
   */
  static private TSSubQuery parseAggregatorParam(final String[] tokens) {
    // Syntax = agg:[iw-interval:][interval-agg:][rate:][ext-nnn.mmm:]
    // where the parts in square brackets `[' .. `]' are optional.
    // agg is the name of an aggregation function. See {@link Aggregators}.
    // iw-interval is a time window of interpolation. See {@link TSSubQuery}.
    // interval-agg is a downsample interval and a downsample function.
    // rate is a flag to enable change rate calculation of time series data.
    // ext is to specify amount of time to extend HBase time query time range.
    //    nnn is amount of time to make HBase query time range begin earlier
    //        by that much. (e.g, "10s", "10m", "3h")
    //    mmm is amount of time to make HBase query time range end later
    //        by that much. (e.g, "10s", "10m", "3h")
    //    '.' is the separator of nnn and mmm.
    if (tokens.length == 0) {
      throw new BadRequestException("Not enough parameters for aggregator");
    }
    final TSSubQuery subQuery = new TSSubQuery();
    subQuery.setAggregator(tokens[0]);
    // Parse out the rate and downsampler.
    for (String token: Arrays.copyOfRange(tokens, 1, tokens.length)) {
      if (token.toLowerCase().startsWith("rate")) {
        subQuery.setRate(true);
        if (token.indexOf("{") >= 0) {
          subQuery.setRateOptions(QueryRpc.parseRateOptions(true, token));
        }
      } else if (token.toLowerCase().startsWith(
          TSSubQuery.PREFIX_INTERPOLATION_WINDOW)) {
        subQuery.setInterpolationWindowOption(token);
      } else if (token.toLowerCase().startsWith(
          TSSubQuery.PREFIX_HBASE_TIME_EXTENSION)) {
        subQuery.setHbaseTimeExtension(token);
      } else if (Character.isDigit(token.charAt(0))) {
        subQuery.setDownsample(token);
      } else {
        throw new BadRequestException(
            String.format("Unknown parameter '%s' for aggregator", token));
      }
    }
    return subQuery;
  }

  /**
   * Parses a query string "m=..." type query and adds it to the TSQuery.
   * This will generate a TSSubQuery and add it to the TSQuery if successful
   * @param query_string The value of the m query string parameter, i.e. what
   * comes after the equals sign
   * @param data_query The query we're building
   * @throws BadRequestException if we are unable to parse the query or it is
   * missing components
   */
  private void parseMTypeSubQuery(final String query_string, 
      TSQuery data_query) {
    if (query_string == null || query_string.isEmpty()) {
      throw new BadRequestException("The query string was empty");
    }
    
    // m is of the following forms:
    // Aggreagator_parameters:metric[{tag=value,...}]
    // See parseAggregatorParam for Aggreagator_parameters.
    final String[] parts = Tags.splitString(query_string, ':');
    int i = parts.length;
    if (i < 2 || i > 6) {
      throw new BadRequestException("Invalid parameter m=" + query_string + " ("
          + (i < 2 ? "not enough" : "too many") + " :-separated parts)");
    }
    final TSSubQuery subQuery = parseAggregatorParam(
        Arrays.copyOfRange(parts, 0, parts.length - 1));
    // Copies the last part (the metric name and tags).
    HashMap<String, String> tags = new HashMap<String, String>();
    subQuery.setMetric(Tags.parseWithMetric(parts[parts.length - 1], tags));
    subQuery.setTags(tags);
    data_query.addSubQuery(subQuery);
  }

  /**
   * Parses a "tsuid=..." type query and adds it to the TSQuery.
   * This will generate a TSSubQuery and add it to the TSQuery if successful
   * @param query_string The value of the m query string parameter, i.e. what
   * comes after the equals sign
   * @param data_query The query we're building
   * @throws BadRequestException if we are unable to parse the query or it is
   * missing components
   */
  private void parseTsuidTypeSubQuery(final String query_string, 
      TSQuery data_query) {
    if (query_string == null || query_string.isEmpty()) {
      throw new BadRequestException("The tsuid query string was empty");
    }

    // tsuid queries are of the following forms:
    // Aggreagator_parameters:tsuid[,s]
    // See parseAggregatorParam for Aggreagator_parameters.
    final String[] parts = Tags.splitString(query_string, ':');
    int i = parts.length;
    if (i < 2 || i > 6) {
      throw new BadRequestException("Invalid parameter tsuid=" +
          query_string + " (" + (i < 2 ? "not enough" : "too many") +
          " :-separated parts)");
    }
    final TSSubQuery subQuery = parseAggregatorParam(
        Arrays.copyOfRange(parts, 0, parts.length - 1));
    // Copies the last part (tsuids).
    subQuery.setTsuids(Arrays.asList(parts[parts.length - 1].split(",")));
    data_query.addSubQuery(subQuery);
  }
  
  /**
   * Parses the "rate" section of the query string and returns an instance
   * of the RateOptions class that contains the values found.
   * <p/>
   * The format of the rate specification is rate[{counter[,#[,#]]}].
   * @param rate If true, then the query is set as a rate query and the rate
   * specification will be parsed. If false, a default RateOptions instance
   * will be returned and largely ignored by the rest of the processing
   * @param spec The part of the query string that pertains to the rate
   * @return An initialized RateOptions instance based on the specification
   * @throws BadRequestException if the parameter is malformed
   * @since 2.0
   */
   static final public RateOptions parseRateOptions(final boolean rate,
       final String spec) {
     if (!rate || spec.length() == 4) {
       return new RateOptions(false, Long.MAX_VALUE,
           RateOptions.DEFAULT_RESET_VALUE);
     }

     if (spec.length() < 6) {
       throw new BadRequestException("Invalid rate options specification: "
           + spec);
     }

     String[] parts = Tags
         .splitString(spec.substring(5, spec.length() - 1), ',');
     if (parts.length < 1 || parts.length > 3) {
       throw new BadRequestException(
           "Incorrect number of values in rate options specification, must be " +
           "counter[,counter max value,reset value], recieved: "
               + parts.length + " parts");
     }

     final boolean counter = "counter".equals(parts[0]);
     try {
       final long max = (parts.length >= 2 && parts[1].length() > 0 ? Long
           .parseLong(parts[1]) : Long.MAX_VALUE);
       try {
         final long reset = (parts.length >= 3 && parts[2].length() > 0 ? Long
             .parseLong(parts[2]) : RateOptions.DEFAULT_RESET_VALUE);
         return new RateOptions(counter, max, reset);
       } catch (NumberFormatException e) {
         throw new BadRequestException(
             "Reset value of counter was not a number, received '" + parts[2]
                 + "'");
       }
     } catch (NumberFormatException e) {
       throw new BadRequestException(
           "Max value of counter was not a number, received '" + parts[1] + "'");
     }
   }
}
