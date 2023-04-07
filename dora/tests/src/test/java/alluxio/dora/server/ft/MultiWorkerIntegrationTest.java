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

package alluxio.dora.server.ft;

import static org.junit.Assert.assertEquals;

import alluxio.dora.AlluxioURI;
import alluxio.dora.Constants;
import alluxio.dora.client.WriteType;
import alluxio.dora.client.block.BlockStoreClient;
import alluxio.dora.client.block.BlockWorkerInfo;
import alluxio.dora.client.block.policy.BlockLocationPolicy;
import alluxio.dora.client.block.policy.options.GetWorkerOptions;
import alluxio.dora.client.file.FileInStream;
import alluxio.dora.client.file.FileSystem;
import alluxio.dora.client.file.FileSystemContext;
import alluxio.dora.client.file.FileSystemTestUtils;
import alluxio.dora.client.file.URIStatus;
import alluxio.dora.client.file.options.InStreamOptions;
import alluxio.dora.client.file.options.OutStreamOptions;
import alluxio.dora.conf.AlluxioConfiguration;
import alluxio.dora.conf.Configuration;
import alluxio.dora.conf.PropertyKey;
import alluxio.dora.grpc.CreateFilePOptions;
import alluxio.dora.grpc.OpenFilePOptions;
import alluxio.dora.grpc.WritePType;
import alluxio.dora.testutils.BaseIntegrationTest;
import alluxio.dora.testutils.LocalAlluxioClusterResource;
import alluxio.dora.util.io.BufferUtils;
import alluxio.dora.wire.BlockInfo;
import alluxio.dora.wire.FileBlockInfo;
import alluxio.dora.wire.WorkerNetAddress;

import com.google.common.io.ByteStreams;
import org.apache.commons.io.IOUtils;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Optional;
import java.util.stream.StreamSupport;

/**
 * Tests a cluster containing multiple workers.
 */
public final class MultiWorkerIntegrationTest extends BaseIntegrationTest {
  private static final int NUM_WORKERS = 4;
  private static final int WORKER_MEMORY_SIZE_BYTES = Constants.MB;
  private static final int BLOCK_SIZE_BYTES = WORKER_MEMORY_SIZE_BYTES / 2;

  public static class FindFirstBlockLocationPolicy implements BlockLocationPolicy {
    // Set this prior to sending the create request to FSM.
    private static WorkerNetAddress sWorkerAddress;

    public FindFirstBlockLocationPolicy(AlluxioConfiguration ignored) {}

    @Override
    public Optional<WorkerNetAddress> getWorker(GetWorkerOptions options) {
      return StreamSupport.stream(options.getBlockWorkerInfos().spliterator(), false)
          .filter(x -> x.getNetAddress().equals(sWorkerAddress)).findFirst()
          .map(BlockWorkerInfo::getNetAddress);
    }
  }

  @Rule
  public LocalAlluxioClusterResource mResource =
      new LocalAlluxioClusterResource.Builder()
          .setProperty(PropertyKey.WORKER_RAMDISK_SIZE, WORKER_MEMORY_SIZE_BYTES)
          .setProperty(PropertyKey.USER_BLOCK_SIZE_BYTES_DEFAULT, BLOCK_SIZE_BYTES)
          .setProperty(PropertyKey.USER_FILE_BUFFER_BYTES, BLOCK_SIZE_BYTES)
          .setNumWorkers(NUM_WORKERS)
          .build();

  @Test
  @LocalAlluxioClusterResource.Config(confParams = {
      PropertyKey.Name.USER_BLOCK_WRITE_LOCATION_POLICY,
      "alluxio.dora.policy.block.client.RoundRobinPolicy",
      })
  public void writeLargeFile() throws Exception {
    int fileSize = NUM_WORKERS * WORKER_MEMORY_SIZE_BYTES;
    AlluxioURI file = new AlluxioURI("/test");

    FileSystem fs = mResource.get().getClient();
    FileSystemTestUtils.createByteFile(fs, file.getPath(), fileSize,
        CreateFilePOptions.newBuilder().setWriteType(WritePType.MUST_CACHE).build());
    URIStatus status = fs.getStatus(file);
    assertEquals(100, status.getInAlluxioPercentage());
    try (FileInStream inStream = fs.openFile(file)) {
      assertEquals(fileSize, IOUtils.toByteArray(inStream).length);
    }
  }

  @Test
  @LocalAlluxioClusterResource.Config(confParams = {PropertyKey.Name.USER_SHORT_CIRCUIT_ENABLED,
      "false", PropertyKey.Name.USER_BLOCK_SIZE_BYTES_DEFAULT, "16MB",
      PropertyKey.Name.USER_STREAMING_READER_CHUNK_SIZE_BYTES, "64KB",
      PropertyKey.Name.USER_BLOCK_READ_RETRY_MAX_DURATION, "1s",
      PropertyKey.Name.WORKER_RAMDISK_SIZE, "1GB"})
  public void readRecoverFromLostWorker() throws Exception {
    int offset = 17 * Constants.MB;
    int length = 33 * Constants.MB;
    int total = offset + length;
    // creates a test file on one worker
    AlluxioURI filePath = new AlluxioURI("/test");
    createFileOnWorker(total, filePath, mResource.get().getWorkerAddress());
    FileSystem fs = mResource.get().getClient();
    try (FileInStream in = fs.openFile(filePath, OpenFilePOptions.getDefaultInstance())) {
      byte[] buf = new byte[total];
      int size = in.read(buf, 0, offset);
      replicateFileBlocks(filePath);
      mResource.get().getWorkerProcess().stop();
      size += in.read(buf, offset, length);

      Assert.assertEquals(total, size);
      Assert.assertTrue(BufferUtils.equalIncreasingByteArray(offset, size, buf));
    }
  }

  @Test
  @LocalAlluxioClusterResource.Config(confParams = {PropertyKey.Name.USER_SHORT_CIRCUIT_ENABLED,
      "false", PropertyKey.Name.USER_BLOCK_SIZE_BYTES_DEFAULT, "4MB",
      PropertyKey.Name.USER_STREAMING_READER_CHUNK_SIZE_BYTES, "64KB",
      PropertyKey.Name.USER_BLOCK_READ_RETRY_MAX_DURATION, "1s",
      PropertyKey.Name.WORKER_RAMDISK_SIZE, "1GB"})
  public void readOneRecoverFromLostWorker() throws Exception {
    int offset = Constants.MB;
    int length = 5 * Constants.MB;
    int total = offset + length;
    // creates a test file on one worker
    AlluxioURI filePath = new AlluxioURI("/test");
    FileSystem fs = mResource.get().getClient();
    createFileOnWorker(total, filePath, mResource.get().getWorkerAddress());
    try (FileInStream in = fs.openFile(filePath, OpenFilePOptions.getDefaultInstance())) {
      byte[] buf = new byte[total];
      int size = in.read(buf, 0, offset);
      replicateFileBlocks(filePath);
      mResource.get().getWorkerProcess().stop();
      for (int i = 0; i < length; i++) {
        int result = in.read();
        Assert.assertEquals(result, (i + size) & 0xff);
      }
    }
  }

  @Test
  @LocalAlluxioClusterResource.Config(confParams = {PropertyKey.Name.USER_SHORT_CIRCUIT_ENABLED,
      "false", PropertyKey.Name.USER_BLOCK_SIZE_BYTES_DEFAULT, "4MB",
      PropertyKey.Name.USER_STREAMING_READER_CHUNK_SIZE_BYTES, "64KB",
      PropertyKey.Name.USER_BLOCK_READ_RETRY_MAX_DURATION, "1s",
      PropertyKey.Name.WORKER_RAMDISK_SIZE, "1GB"})
  public void positionReadRecoverFromLostWorker() throws Exception {
    int offset = Constants.MB;
    int length = 7 * Constants.MB;
    int total = offset + length;
    // creates a test file on one worker
    AlluxioURI filePath = new AlluxioURI("/test");
    FileSystem fs = mResource.get().getClient();
    createFileOnWorker(total, filePath, mResource.get().getWorkerAddress());
    try (FileInStream in = fs.openFile(filePath, OpenFilePOptions.getDefaultInstance())) {
      byte[] buf = new byte[length];
      replicateFileBlocks(filePath);
      mResource.get().getWorkerProcess().stop();
      int size = in.positionedRead(offset, buf, 0, length);

      Assert.assertEquals(length, size);
      Assert.assertTrue(BufferUtils.equalIncreasingByteArray(offset, size, buf));
    }
  }

  private void createFileOnWorker(int total, AlluxioURI filePath, WorkerNetAddress address)
      throws IOException {
    FindFirstBlockLocationPolicy.sWorkerAddress = address;
    Class<?> previousPolicy = Configuration.getClass(
        PropertyKey.USER_BLOCK_WRITE_LOCATION_POLICY);
    // This only works because the client instance hasn't been created yet.
    Configuration.set(PropertyKey.USER_BLOCK_WRITE_LOCATION_POLICY,
        FindFirstBlockLocationPolicy.class.getName());
    FileSystemTestUtils.createByteFile(mResource.get().getClient(), filePath,
        CreateFilePOptions.newBuilder().setWriteType(WritePType.MUST_CACHE).build(),
        total);
    Configuration.set(PropertyKey.USER_BLOCK_WRITE_LOCATION_POLICY, previousPolicy);
  }

  private void replicateFileBlocks(AlluxioURI filePath) throws Exception {
    FileSystemContext fsContext = FileSystemContext.create(Configuration.global());
    BlockStoreClient store = BlockStoreClient.create(fsContext);
    URIStatus status =  mResource.get().getClient().getStatus(filePath);
    List<FileBlockInfo> blocks = status.getFileBlockInfos();
    List<BlockWorkerInfo> workers = fsContext.getCachedWorkers();

    for (FileBlockInfo block : blocks) {
      BlockInfo blockInfo = block.getBlockInfo();
      WorkerNetAddress src = blockInfo.getLocations().get(0).getWorkerAddress();
      WorkerNetAddress dest = workers.stream()
          .filter(candidate -> !candidate.getNetAddress().equals(src))
          .findFirst()
          .orElseThrow(() -> new IllegalStateException("Expected worker"))
          .getNetAddress();
      try (OutputStream outStream = store.getOutStream(blockInfo.getBlockId(),
          blockInfo.getLength(), dest, OutStreamOptions.defaults(fsContext)
              .setBlockSizeBytes(8 * Constants.MB).setWriteType(WriteType.MUST_CACHE))) {
        try (InputStream inStream = store.getInStream(blockInfo.getBlockId(),
            new InStreamOptions(status, Configuration.global()))) {
          ByteStreams.copy(inStream, outStream);
        }
      }
    }
  }
}