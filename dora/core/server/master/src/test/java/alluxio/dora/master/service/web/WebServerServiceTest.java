/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.dora.master.service.web;

import alluxio.dora.conf.Configuration;
import alluxio.dora.conf.PropertyKey;
import alluxio.dora.master.AlluxioMasterProcess;
import alluxio.dora.master.PortReservationRule;
import alluxio.dora.master.service.web.AlwaysOnWebServerService;
import alluxio.dora.master.service.web.PrimaryOnlyWebServerService;
import alluxio.dora.master.service.web.WebServerService;
import alluxio.dora.util.CommonUtils;
import alluxio.dora.util.WaitForOptions;
import alluxio.dora.util.network.NetworkAddressUtils;
import alluxio.dora.web.MasterWebServer;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Tests for the different implementations of WebServerSimpleService.
 */
public class WebServerServiceTest {
  @Rule
  public PortReservationRule mPort = new PortReservationRule();

  private InetSocketAddress mWebAddress;
  private AlluxioMasterProcess mMasterProcess;

  @Before
  public void setUp() {
    mMasterProcess = Mockito.mock(AlluxioMasterProcess.class);
    mWebAddress = new InetSocketAddress(mPort.getPort());
    Mockito.when(mMasterProcess.createWebServer()).thenAnswer(mock -> new MasterWebServer(
        NetworkAddressUtils.ServiceType.MASTER_WEB.getServiceName(), mWebAddress, mMasterProcess));
  }

  @Test
  public void primaryOnlyTest() {
    Configuration.set(PropertyKey.STANDBY_MASTER_WEB_ENABLED, false);
    WebServerService webService =
        WebServerService.Factory.create(mWebAddress, mMasterProcess);
    Assert.assertTrue(webService instanceof PrimaryOnlyWebServerService);
    Assert.assertTrue(waitForFree());

    Assert.assertFalse(webService.isServing());
    webService.start();
    // after start and before stop the web port is always bound as either the web server or the
    // rejecting server is bound to is (depending on whether it is in PRIMARY or STANDBY state)
    Assert.assertTrue(isBound());
    Assert.assertFalse(webService.isServing());
    for (int i = 0; i < 5; i++) {
      webService.promote();
      Assert.assertTrue(webService.isServing());
      Assert.assertTrue(isBound());
      webService.demote();
      Assert.assertTrue(isBound());
      Assert.assertFalse(webService.isServing());
    }
    webService.stop();
    Assert.assertFalse(webService.isServing());
    Assert.assertFalse(isBound());
  }

  @Test
  public void doubleStartRejectingServer() {
    Configuration.set(PropertyKey.STANDBY_MASTER_WEB_ENABLED, false);
    WebServerService webService =
        WebServerService.Factory.create(mWebAddress, mMasterProcess);
    Assert.assertTrue(webService instanceof PrimaryOnlyWebServerService);

    webService.start();
    Assert.assertThrows("rejecting server must not be running",
        IllegalStateException.class, webService::start);
  }

  @Test
  public void alwaysOnTest() {
    Configuration.set(PropertyKey.STANDBY_MASTER_WEB_ENABLED, true);
    WebServerService webService =
        WebServerService.Factory.create(mWebAddress, mMasterProcess);
    Assert.assertTrue(webService instanceof AlwaysOnWebServerService);
    Assert.assertTrue(waitForFree());

    Assert.assertFalse(webService.isServing());
    webService.start();
    Assert.assertTrue(webService.isServing());
    Assert.assertTrue(isBound());
    for (int i = 0; i < 5; i++) {
      webService.promote();
      Assert.assertTrue(webService.isServing());
      Assert.assertTrue(isBound());
      webService.demote();
      Assert.assertTrue(webService.isServing());
      Assert.assertTrue(isBound());
    }
    webService.stop();
    Assert.assertTrue(waitForFree());
    Assert.assertFalse(webService.isServing());
  }

  @Test
  public void doubleStartWebServer() {
    Configuration.set(PropertyKey.STANDBY_MASTER_WEB_ENABLED, true);
    WebServerService webService =
        WebServerService.Factory.create(mWebAddress, mMasterProcess);
    Assert.assertTrue(webService instanceof AlwaysOnWebServerService);

    webService.start();
    Assert.assertThrows("web server must not already exist",
        IllegalStateException.class, webService::start);
  }

  private boolean isBound() {
    try (Socket socket = new Socket(mWebAddress.getAddress(), mWebAddress.getPort())) {
      return true;
    } catch (ConnectException e) {
      return false;
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private boolean waitForFree() {
    try {
      CommonUtils.waitFor("wait for socket to be free", () -> !isBound(),
          WaitForOptions.defaults().setTimeoutMs(1_000).setInterval(10));
      return true;
    } catch (Exception e) {
      return false;
    }
  }
}