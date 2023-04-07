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

package alluxio.dora.hadoop;

import alluxio.dora.client.file.URIStatus;

import org.apache.hadoop.fs.FSDataInputStream;

import java.io.IOException;

/**
 * Interface to wrap open method of file system.
 */
public interface HadoopFileOpener {
  /**
   * Opens an FSDataInputStream at the indicated Path.
   * @param uriStatus the file to open
   * @return a Hadoop input stream
   */
  FSDataInputStream open(URIStatus uriStatus) throws IOException;
}