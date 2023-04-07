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

package alluxio.dora.master.file.meta;

import alluxio.dora.conf.Configuration;
import alluxio.dora.conf.PropertyKey;

import alluxio.dora.master.file.meta.AsyncUfsAbsentPathCache;
import alluxio.dora.master.file.meta.NoopUfsAbsentPathCache;
import alluxio.dora.master.file.meta.UfsAbsentPathCache;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

import java.time.Clock;

/**
 * Unit tests for {@link UfsAbsentPathCache}.
 */
public class UfsAbsentPathCacheTest {
  /**
   * Resets the configuration.
   */
  @After
  public void after() throws Exception {
    Configuration.reloadProperties();
  }

  @Test
  public void defaultAsyncPathThreads() throws Exception {
    UfsAbsentPathCache cache = UfsAbsentPathCache.Factory.create(null, Clock.systemUTC());
    Assert.assertTrue(cache instanceof AsyncUfsAbsentPathCache);
  }

  @Test
  public void noAsyncPathThreads() throws Exception {
    Configuration.set(PropertyKey.MASTER_UFS_PATH_CACHE_THREADS, 0);
    UfsAbsentPathCache cache = UfsAbsentPathCache.Factory.create(null, Clock.systemUTC());
    Assert.assertTrue(cache instanceof NoopUfsAbsentPathCache);
  }

  @Test
  public void negativeAsyncPathThreads() throws Exception {
    Configuration.set(PropertyKey.MASTER_UFS_PATH_CACHE_THREADS, -1);
    UfsAbsentPathCache cache = UfsAbsentPathCache.Factory.create(null, Clock.systemUTC());
    Assert.assertTrue(cache instanceof NoopUfsAbsentPathCache);
  }
}