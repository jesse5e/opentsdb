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

import java.io.IOException;

import com.google.common.annotations.VisibleForTesting;
import com.stumbleupon.async.Deferred;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.jboss.netty.channel.Channel;

import net.opentsdb.core.BadTimeout;
import net.opentsdb.utils.Config;
import net.opentsdb.core.TSDB;

/**
 * Restarts OpenTSDB server by executing the configured script.
 */
class Restarter implements HttpRpc, TelnetRpc {

  private static final Logger LOG = LoggerFactory.getLogger(Restarter.class);

  /** System call utilities. */
  private final Util util;

  /** True to enable the restart endpoint. */
  private final Boolean enable_restart_endpoint;

  /** Path to the restart script. */
  private final String script;

  Restarter(final Config config) {
    this(config, new Util());
  }

  @VisibleForTesting
  Restarter(final Config config, final Util util) {
    this.util = util;
    enable_restart_endpoint = config.enable_restart_endpoint();
    script = config.get_restart_script();
  }

  @Override
  public Deferred<Object> execute(TSDB tsdb, Channel chan, String[] command) {
    if (enable_restart_endpoint) {
      chan.write("TSD Restarting now.\n");
      restart();
    } else {
      chan.write("TSD Restarting is disabled.\n");
    }
    return new Deferred<Object>();
  }

  @Override
  public void execute(TSDB tsdb, HttpQuery query) throws IOException {
    if (enable_restart_endpoint) {
      query.sendReply(HttpQuery.makePage("TSD Restarting.", "Restarting",
                                         "TSD Restarting now."));
      restart();
    } else {
      query.sendReply(HttpQuery.makePage("TSD Not Restarting.", "Not Restarting",
                                         "TSD Restarting is disabled."));
    }
  }

  /** Restarts OpenTSDB server by executing the configured script. */
  void restartIfUnstable() {
    if (BadTimeout.isSystemUnstable()) {
      restart();
    }
  }

  private void restart() {
    LOG.error(String.format("System is unstable. Restarting via %s.", script));
    try {
      util.exec(script);
    } catch (IOException unused) {
      // Exits if we failed to restart. The exit code doesn't have any
      // significant meaning other than indicating an error.
      util.exit(3);
    }
  }

  /** System call utility for unit tests. */
  @VisibleForTesting
  static class Util {

    void exec(final String command) throws IOException {
      Runtime.getRuntime().exec(command);
    }

    void exit(int status) {
      System.exit(status);
    }
  }
}
