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

import static org.junit.Assert.assertThat;
import static org.hamcrest.CoreMatchers.containsString;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;

import org.jboss.netty.channel.Channel;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.MockitoAnnotations;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import net.opentsdb.core.BadTimeout;
import net.opentsdb.utils.Config;
import net.opentsdb.core.TSDB;
import net.opentsdb.tsd.Restarter.Util;


@RunWith(PowerMockRunner.class)
@PrepareForTest({ TSDB.class, HttpQuery.class, Channel.class, BadTimeout.class})
public class TestRestarter {

  private static final String SCRIPT = "/path/to/restart";

  private TSDB mockTsdb;
  private Config mockConfig;
  private Util mockUtil;
  private Restarter restarter;

  @Captor private ArgumentCaptor<String> stringArg;
  @Captor private ArgumentCaptor<StringBuilder> sbArg;

  @Before
  public void before() throws Exception {
    MockitoAnnotations.initMocks(this);
    mockConfig = PowerMockito.mock(Config.class);
    when(mockConfig.get_restart_script()).thenReturn(SCRIPT);
    when(mockConfig.enable_restart_endpoint()).thenReturn(false);
    mockTsdb = PowerMockito.mock(TSDB.class);
    mockUtil = PowerMockito.mock(Util.class);
    restarter = new Restarter(mockConfig, mockUtil);
  }

  @Test
  public void testExecuteTSDBChannelStringArray_disabled() throws IOException {
    Channel chan = PowerMockito.mock(Channel.class);
    String[] command = new String[] {
        "telnet", "command"
    };
    restarter.execute(mockTsdb, chan, command );
    verify(mockUtil, never()).exec(SCRIPT);
    verify(chan).write(stringArg.capture());
    assertThat(stringArg.getValue(), containsString("disabled"));
  }

  @Test
  public void testExecuteTSDBHttpQuery_disabled() throws IOException {
    HttpQuery query = PowerMockito.mock(HttpQuery.class);
    restarter.execute(mockTsdb, query);
    verify(mockUtil, never()).exec(SCRIPT);
    verify(query).sendReply(sbArg.capture());
    assertThat(sbArg.getValue().toString(), containsString("disabled"));
  }

  @Test
  public void testExecuteTSDBChannelStringArray_enabled() throws IOException {
    when(mockConfig.enable_restart_endpoint()).thenReturn(true);
    Channel chan = PowerMockito.mock(Channel.class);
    String[] command = new String[] {
        "telnet", "command"
    };
    restarter = new Restarter(mockConfig, mockUtil);
    restarter.execute(mockTsdb, chan, command );
    verify(mockUtil).exec(SCRIPT);
    verify(chan).write(stringArg.capture());
    assertThat(stringArg.getValue(), containsString("TSD Restarting now"));
  }

  @Test
  public void testExecuteTSDBHttpQuery_enabled() throws IOException {
    when(mockConfig.enable_restart_endpoint()).thenReturn(true);
    HttpQuery query = PowerMockito.mock(HttpQuery.class);
    restarter = new Restarter(mockConfig, mockUtil);
    restarter.execute(mockTsdb, query);
    verify(mockUtil).exec(SCRIPT);
    verify(query).sendReply(sbArg.capture());
    assertThat(sbArg.getValue().toString(), containsString("TSD Restarting now"));
  }

  @Test
  public void testRestartIfUnstable_stable() throws IOException {
    PowerMockito.mockStatic(BadTimeout.class);
    when(BadTimeout.isSystemUnstable()).thenReturn(false);
    // Doesn't restart when system is stable.
    verify(mockUtil, never()).exec(anyString());
  }

  @Test
  public void testRestartIfUnstable_unstable() throws IOException {
    PowerMockito.mockStatic(BadTimeout.class);
    when(BadTimeout.isSystemUnstable()).thenReturn(true);
    restarter.restartIfUnstable();
    verify(mockUtil).exec(SCRIPT);
    verify(mockUtil).exit(anyInt());
  }

  @Test
  public void testRestartIfUnstable_exception() throws IOException {
    PowerMockito.mockStatic(BadTimeout.class);
    when(BadTimeout.isSystemUnstable()).thenReturn(true);
    doThrow(new IOException("tests")).when(mockUtil).exec(anyString());
    restarter.restartIfUnstable();
    verify(mockUtil).exec(SCRIPT);
    verify(mockUtil).exit(3);
  }
}
