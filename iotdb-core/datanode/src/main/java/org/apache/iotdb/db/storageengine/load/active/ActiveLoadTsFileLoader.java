/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iotdb.db.storageengine.load.active;

import org.apache.iotdb.common.rpc.thrift.TSStatus;
import org.apache.iotdb.commons.concurrent.IoTThreadFactory;
import org.apache.iotdb.commons.concurrent.ThreadName;
import org.apache.iotdb.commons.concurrent.threadpool.WrappedThreadPoolExecutor;
import org.apache.iotdb.db.auth.AuthorityChecker;
import org.apache.iotdb.db.conf.IoTDBDescriptor;
import org.apache.iotdb.db.protocol.session.SessionManager;
import org.apache.iotdb.db.queryengine.common.SessionInfo;
import org.apache.iotdb.db.queryengine.plan.Coordinator;
import org.apache.iotdb.db.queryengine.plan.analyze.ClusterPartitionFetcher;
import org.apache.iotdb.db.queryengine.plan.analyze.schema.ClusterSchemaFetcher;
import org.apache.iotdb.db.queryengine.plan.statement.Statement;
import org.apache.iotdb.db.queryengine.plan.statement.crud.LoadTsFileStatement;
import org.apache.iotdb.db.queryengine.plan.statement.pipe.PipeEnrichedStatement;
import org.apache.iotdb.db.storageengine.load.metrics.ActiveLoadingFilesMetricsSet;
import org.apache.iotdb.rpc.TSStatusCode;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.tsfile.utils.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

public class ActiveLoadTsFileLoader {

  private static final Logger LOGGER = LoggerFactory.getLogger(ActiveLoadTsFileLoader.class);

  private static final int MAX_PENDING_SIZE = 1000;
  private final ActiveLoadPendingQueue pendingQueue = new ActiveLoadPendingQueue();

  private final AtomicReference<WrappedThreadPoolExecutor> activeLoadExecutor =
      new AtomicReference<>();
  private final AtomicReference<String> failDir = new AtomicReference<>();

  public int getCurrentAllowedPendingSize() {
    return MAX_PENDING_SIZE - pendingQueue.size();
  }

  public void tryTriggerTsFileLoad(String absolutePath, boolean isGeneratedByPipe) {
    if (pendingQueue.enqueue(absolutePath, isGeneratedByPipe)) {
      initFailDirIfNecessary();
      adjustExecutorIfNecessary();
    }
  }

  private void initFailDirIfNecessary() {
    if (failDir.get() == null) {
      synchronized (failDir) {
        if (failDir.get() == null) {
          final File failDirFile =
              new File(IoTDBDescriptor.getInstance().getConfig().getLoadActiveListeningFailDir());
          try {
            FileUtils.forceMkdir(failDirFile);
          } catch (final IOException e) {
            LOGGER.warn(
                "Error occurred during creating fail directory {} for active load.",
                failDirFile,
                e);
          }
          failDir.set(IoTDBDescriptor.getInstance().getConfig().getLoadActiveListeningFailDir());
        }
      }
    }
  }

  private void adjustExecutorIfNecessary() {
    if (activeLoadExecutor.get() == null) {
      synchronized (activeLoadExecutor) {
        if (activeLoadExecutor.get() == null) {
          activeLoadExecutor.set(
              new WrappedThreadPoolExecutor(
                  IoTDBDescriptor.getInstance().getConfig().getLoadActiveListeningMaxThreadNum(),
                  IoTDBDescriptor.getInstance().getConfig().getLoadActiveListeningMaxThreadNum(),
                  0L,
                  TimeUnit.SECONDS,
                  new LinkedBlockingQueue<>(),
                  new IoTThreadFactory(ThreadName.ACTIVE_LOAD_TSFILE_LOADER.name()),
                  ThreadName.ACTIVE_LOAD_TSFILE_LOADER.name()));
        }
      }
    }

    final int targetCorePoolSize =
        Math.min(
            pendingQueue.size(),
            IoTDBDescriptor.getInstance().getConfig().getLoadActiveListeningMaxThreadNum());

    if (activeLoadExecutor.get().getCorePoolSize() != targetCorePoolSize) {
      activeLoadExecutor.get().setCorePoolSize(targetCorePoolSize);
    }

    // calculate how many threads need to be loaded
    final int threadsToBeAdded =
        Math.max(targetCorePoolSize - activeLoadExecutor.get().getActiveCount(), 0);
    for (int i = 0; i < threadsToBeAdded; i++) {
      activeLoadExecutor.get().execute(this::tryLoadPendingTsFiles);
    }
  }

  private void tryLoadPendingTsFiles() {
    while (true) {
      final Optional<Pair<String, Boolean>> filePair = tryGetNextPendingFile();
      if (!filePair.isPresent()) {
        return;
      }

      try {
        final TSStatus result = loadTsFile(filePair.get());
        if (result.getCode() == TSStatusCode.SUCCESS_STATUS.getStatusCode()
            || result.getCode() == TSStatusCode.REDIRECTION_RECOMMEND.getStatusCode()) {
          LOGGER.info(
              "Successfully auto load tsfile {} (isGeneratedByPipe = {})",
              filePair.get().getLeft(),
              filePair.get().getRight());
        } else {
          handleLoadFailure(filePair.get(), result);
        }
      } catch (final FileNotFoundException e) {
        handleFileNotFoundException(filePair.get());
      } catch (final Exception e) {
        handleOtherException(filePair.get(), e);
      } finally {
        pendingQueue.removeFromLoading(filePair.get().getLeft());
      }
    }
  }

  private Optional<Pair<String, Boolean>> tryGetNextPendingFile() {
    final long maxRetryTimes =
        Math.max(
            1,
            IoTDBDescriptor.getInstance().getConfig().getLoadActiveListeningCheckIntervalSeconds()
                << 1);
    long currentRetryTimes = 0;

    while (true) {
      final Pair<String, Boolean> filePair = pendingQueue.dequeueFromPending();
      if (Objects.nonNull(filePair)) {
        return Optional.of(filePair);
      }

      LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(1));

      if (currentRetryTimes++ >= maxRetryTimes) {
        return Optional.empty();
      }
    }
  }

  private TSStatus loadTsFile(final Pair<String, Boolean> filePair) throws FileNotFoundException {
    final LoadTsFileStatement statement = new LoadTsFileStatement(filePair.getLeft());
    statement.setDeleteAfterLoad(true);
    statement.setVerifySchema(true);
    statement.setAutoCreateDatabase(false);
    return executeStatement(filePair.getRight() ? new PipeEnrichedStatement(statement) : statement);
  }

  private TSStatus executeStatement(final Statement statement) {
    return Coordinator.getInstance()
        .executeForTreeModel(
            statement,
            SessionManager.getInstance().requestQueryId(),
            new SessionInfo(0, AuthorityChecker.SUPER_USER, ZoneId.systemDefault()),
            "",
            ClusterPartitionFetcher.getInstance(),
            ClusterSchemaFetcher.getInstance(),
            IoTDBDescriptor.getInstance().getConfig().getQueryTimeoutThreshold())
        .status;
  }

  private void handleLoadFailure(final Pair<String, Boolean> filePair, final TSStatus status) {
    LOGGER.warn(
        "Failed to auto load tsfile {} (isGeneratedByPipe = {}), status: {}. File will be moved to fail directory.",
        filePair.getLeft(),
        filePair.getRight(),
        status);
    removeFileAndResourceAndModsToFailDir(filePair.getLeft());
  }

  private void handleFileNotFoundException(final Pair<String, Boolean> filePair) {
    LOGGER.warn(
        "Failed to auto load tsfile {} (isGeneratedByPipe = {}) due to file not found, will skip this file.",
        filePair.getLeft(),
        filePair.getRight());
    removeFileAndResourceAndModsToFailDir(filePair.getLeft());
  }

  private void handleOtherException(final Pair<String, Boolean> filePair, final Exception e) {
    if (e.getMessage() != null && e.getMessage().contains("memory")) {
      LOGGER.info(
          "Rejecting auto load tsfile {} (isGeneratedByPipe = {}) due to memory constraints, will retry later.",
          filePair.getLeft(),
          filePair.getRight());
    } else {
      LOGGER.warn(
          "Failed to auto load tsfile {} (isGeneratedByPipe = {}) because of an unexpected exception. File will be moved to fail directory.",
          filePair.getLeft(),
          filePair.getRight(),
          e);
      removeFileAndResourceAndModsToFailDir(filePair.getLeft());
    }
  }

  private void removeFileAndResourceAndModsToFailDir(final String filePath) {
    removeToFailDir(filePath);
    removeToFailDir(filePath + ".resource");
    removeToFailDir(filePath + ".mods");
  }

  private void removeToFailDir(final String filePath) {
    final File sourceFile = new File(filePath);
    // prevent the resource or mods not exist
    if (!sourceFile.exists()) {
      return;
    }

    final File targetDir = new File(failDir.get());
    try {
      moveFileWithMD5Check(sourceFile, targetDir);
    } catch (final IOException e) {
      LOGGER.warn("Error occurred during moving file {} to fail directory.", filePath, e);
    }
  }

  private static void moveFileWithMD5Check(final File sourceFile, final File targetDir)
      throws IOException {
    final String sourceFileName = sourceFile.getName();
    final File targetFile = new File(targetDir, sourceFileName);

    if (targetFile.exists()) {
      if (haveSameMD5(sourceFile, targetFile)) {
        FileUtils.forceDelete(sourceFile);
        LOGGER.info(
            "Deleted the file {} because it already exists in the fail directory: {}",
            sourceFile.getName(),
            targetDir.getAbsolutePath());
      } else {
        renameWithMD5(sourceFile, targetDir);
        LOGGER.info(
            "Renamed file {} to {} because it already exists in the fail directory: {}",
            sourceFile.getName(),
            targetFile.getName(),
            targetDir.getAbsolutePath());
      }
    } else {
      FileUtils.moveFileToDirectory(sourceFile, targetDir, true);
      LOGGER.info(
          "Moved file {} to fail directory {}.", sourceFile.getName(), targetDir.getAbsolutePath());
    }
  }

  private static boolean haveSameMD5(final File file1, final File file2) {
    try (final InputStream is1 = Files.newInputStream(file1.toPath());
        final InputStream is2 = Files.newInputStream(file2.toPath())) {
      return DigestUtils.md5Hex(is1).equals(DigestUtils.md5Hex(is2));
    } catch (final Exception e) {
      return false;
    }
  }

  private static void renameWithMD5(File sourceFile, File targetDir) throws IOException {
    try (final InputStream is = Files.newInputStream(sourceFile.toPath())) {
      final String sourceFileBaseName = FilenameUtils.getBaseName(sourceFile.getName());
      final String sourceFileExtension = FilenameUtils.getExtension(sourceFile.getName());
      final String sourceFileMD5 = DigestUtils.md5Hex(is);

      final String targetFileName =
          sourceFileBaseName + "-" + sourceFileMD5.substring(0, 16) + "." + sourceFileExtension;
      final File targetFile = new File(targetDir, targetFileName);

      FileUtils.moveFile(sourceFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  // Metrics
  public long countAndReportFailedFileNumber() {
    final long[] fileCount = {0};
    try {
      initFailDirIfNecessary();
      Files.walkFileTree(
          new File(failDir.get()).toPath(),
          new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
              fileCount[0]++;
              return FileVisitResult.CONTINUE;
            }
          });
      ActiveLoadingFilesMetricsSet.getInstance().recordFailedFileCounter(fileCount[0]);
    } catch (final IOException e) {
      LOGGER.warn("Failed to count failed files in fail directory.", e);
    }
    return fileCount[0];
  }
}
