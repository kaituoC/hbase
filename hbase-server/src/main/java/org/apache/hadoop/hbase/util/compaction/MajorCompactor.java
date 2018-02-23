/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hbase.util.compaction;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HBaseInterfaceAudience;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HRegionLocation;
import org.apache.hadoop.hbase.NotServingRegionException;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.CompactionState;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.ConnectionFactory;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hbase.thirdparty.com.google.common.annotations.VisibleForTesting;
import org.apache.yetus.audience.InterfaceAudience;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.hbase.thirdparty.com.google.common.base.Joiner;
import org.apache.hbase.thirdparty.com.google.common.base.Splitter;
import org.apache.hbase.thirdparty.com.google.common.collect.Iterables;
import org.apache.hbase.thirdparty.com.google.common.collect.Lists;
import org.apache.hbase.thirdparty.com.google.common.collect.Sets;

@InterfaceAudience.LimitedPrivate(HBaseInterfaceAudience.TOOLS)
public class MajorCompactor {

  private static final Logger LOG = LoggerFactory.getLogger(MajorCompactor.class);
  private static final Set<MajorCompactionRequest> ERRORS = ConcurrentHashMap.newKeySet();

  private final ClusterCompactionQueues clusterCompactionQueues;
  private final long timestamp;
  private final Set<String> storesToCompact;
  private final ExecutorService executor;
  private final long sleepForMs;
  private final Connection connection;
  private final TableName tableName;

  public MajorCompactor(Configuration conf, TableName tableName, Set<String> storesToCompact,
      int concurrency, long timestamp, long sleepForMs) throws IOException {
    this.connection = ConnectionFactory.createConnection(conf);
    this.tableName = tableName;
    this.timestamp = timestamp;
    this.storesToCompact = storesToCompact;
    this.executor = Executors.newFixedThreadPool(concurrency);
    this.clusterCompactionQueues = new ClusterCompactionQueues(concurrency);
    this.sleepForMs = sleepForMs;
  }

  public void compactAllRegions() throws Exception {
    List<Future<?>> futures = Lists.newArrayList();
    while (clusterCompactionQueues.hasWorkItems() || !futuresComplete(futures)) {
      while (clusterCompactionQueues.atCapacity()) {
        LOG.debug("Waiting for servers to complete Compactions");
        Thread.sleep(sleepForMs);
      }
      Optional<ServerName> serverToProcess =
          clusterCompactionQueues.getLargestQueueFromServersNotCompacting();
      if (serverToProcess.isPresent() && clusterCompactionQueues.hasWorkItems()) {
        ServerName serverName = serverToProcess.get();
        // check to see if the region has moved... if so we have to enqueue it again with
        // the proper serverName
        MajorCompactionRequest request = clusterCompactionQueues.reserveForCompaction(serverName);

        ServerName currentServer = connection.getRegionLocator(tableName)
            .getRegionLocation(request.getRegion().getStartKey()).getServerName();

        if (!currentServer.equals(serverName)) {
          // add it back to the queue with the correct server it should be picked up in the future.
          LOG.info("Server changed for region: " + request.getRegion().getEncodedName() + " from: "
              + serverName + " to: " + currentServer + " re-queuing request");
          clusterCompactionQueues.addToCompactionQueue(currentServer, request);
          clusterCompactionQueues.releaseCompaction(serverName);
        } else {
          LOG.info("Firing off compaction request for server: " + serverName + ", " + request
              + " total queue size left: " + clusterCompactionQueues
              .getCompactionRequestsLeftToFinish());
          futures.add(executor.submit(new Compact(serverName, request)));
        }
      } else {
        // haven't assigned anything so we sleep.
        Thread.sleep(sleepForMs);
      }
    }
    LOG.info("All compactions have completed");
  }

  private boolean futuresComplete(List<Future<?>> futures) {
    futures.removeIf(Future::isDone);
    return futures.isEmpty();
  }

  public void shutdown() throws Exception {
    executor.shutdown();
    executor.awaitTermination(Long.MAX_VALUE, TimeUnit.MILLISECONDS);
    if (!ERRORS.isEmpty()) {
      StringBuilder builder =
          new StringBuilder().append("Major compaction failed, there were: ").append(ERRORS.size())
              .append(" regions / stores that failed compacting\n")
              .append("Failed compaction requests\n").append("--------------------------\n")
              .append(Joiner.on("\n").join(ERRORS));
      LOG.error(builder.toString());
    }
    if (connection != null) {
      connection.close();
    }
    LOG.info("All regions major compacted successfully");
  }

  @VisibleForTesting void initializeWorkQueues() throws IOException {
    if (storesToCompact.isEmpty()) {
      connection.getTable(tableName).getDescriptor().getColumnFamilyNames()
          .forEach(a -> storesToCompact.add(Bytes.toString(a)));
      LOG.info("No family specified, will execute for all families");
    }
    LOG.info(
        "Initializing compaction queues for table:  " + tableName + " with cf: " + storesToCompact);
    List<HRegionLocation> regionLocations =
        connection.getRegionLocator(tableName).getAllRegionLocations();
    for (HRegionLocation location : regionLocations) {
      Optional<MajorCompactionRequest> request = MajorCompactionRequest
          .newRequest(connection.getConfiguration(), location.getRegion(), storesToCompact,
              timestamp);
      request.ifPresent(majorCompactionRequest -> clusterCompactionQueues
          .addToCompactionQueue(location.getServerName(), majorCompactionRequest));
    }
  }

  class Compact implements Runnable {

    private final ServerName serverName;
    private final MajorCompactionRequest request;

    Compact(ServerName serverName, MajorCompactionRequest request) {
      this.serverName = serverName;
      this.request = request;
    }

    @Override public void run() {
      try {
        compactAndWait(request);
      } catch (NotServingRegionException e) {
        // this region has split or merged
        LOG.warn("Region is invalid, requesting updated regions", e);
        // lets updated the cluster compaction queues with these newly created regions.
        addNewRegions();
      } catch (Exception e) {
        LOG.warn("Error compacting:", e);
      } finally {
        clusterCompactionQueues.releaseCompaction(serverName);
      }
    }

    void compactAndWait(MajorCompactionRequest request) throws Exception {
      Admin admin = connection.getAdmin();
      try {
        // only make the request if the region is not already major compacting
        if (!isCompacting(request)) {
          Set<String> stores = request.getStoresRequiringCompaction(storesToCompact);
          if (!stores.isEmpty()) {
            request.setStores(stores);
            for (String store : request.getStores()) {
              admin.majorCompactRegion(request.getRegion().getEncodedNameAsBytes(),
                  Bytes.toBytes(store));
            }
          }
        }
        while (isCompacting(request)) {
          Thread.sleep(sleepForMs);
          LOG.debug("Waiting for compaction to complete for region: " + request.getRegion()
              .getEncodedName());
        }
      } finally {
        // Make sure to wait for the CompactedFileDischarger chore to do its work
        int waitForArchive = connection.getConfiguration()
            .getInt("hbase.hfile.compaction.discharger.interval", 2 * 60 * 1000);
        Thread.sleep(waitForArchive);
        // check if compaction completed successfully, otherwise put that request back in the
        // proper queue
        Set<String> storesRequiringCompaction =
            request.getStoresRequiringCompaction(storesToCompact);
        if (!storesRequiringCompaction.isEmpty()) {
          // this happens, when a region server is marked as dead, flushes a store file and
          // the new regionserver doesn't pick it up because its accounted for in the WAL replay,
          // thus you have more store files on the filesystem than the regionserver knows about.
          boolean regionHasNotMoved = connection.getRegionLocator(tableName)
              .getRegionLocation(request.getRegion().getStartKey()).getServerName()
              .equals(serverName);
          if (regionHasNotMoved) {
            LOG.error("Not all store files were compacted, this may be due to the regionserver not "
                + "being aware of all store files.  Will not reattempt compacting, " + request);
            ERRORS.add(request);
          } else {
            request.setStores(storesRequiringCompaction);
            clusterCompactionQueues.addToCompactionQueue(serverName, request);
            LOG.info("Compaction failed for the following stores: " + storesRequiringCompaction
                + " region: " + request.getRegion().getEncodedName());
          }
        } else {
          LOG.info("Compaction complete for region: " + request.getRegion().getEncodedName()
              + " -> cf(s): " + request.getStores());
        }
      }
    }
  }

  private boolean isCompacting(MajorCompactionRequest request) throws Exception {
    CompactionState compactionState = connection.getAdmin()
        .getCompactionStateForRegion(request.getRegion().getEncodedNameAsBytes());
    return compactionState.equals(CompactionState.MAJOR) || compactionState
        .equals(CompactionState.MAJOR_AND_MINOR);
  }

  private void addNewRegions() {
    try {
      List<HRegionLocation> locations =
          connection.getRegionLocator(tableName).getAllRegionLocations();
      for (HRegionLocation location : locations) {
        if (location.getRegion().getRegionId() > timestamp) {
          Optional<MajorCompactionRequest> compactionRequest = MajorCompactionRequest
              .newRequest(connection.getConfiguration(), location.getRegion(), storesToCompact,
                  timestamp);
          compactionRequest.ifPresent(request -> clusterCompactionQueues
              .addToCompactionQueue(location.getServerName(), request));
        }
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public static void main(String[] args) throws Exception {
    Options options = new Options();
    options.addOption(
        Option.builder("table")
            .required()
            .desc("table name")
            .hasArg()
            .build()
    );
    options.addOption(
        Option.builder("cf")
            .optionalArg(true)
            .desc("column families: comma separated eg: a,b,c")
            .hasArg()
            .build()
    );
    options.addOption(
        Option.builder("servers")
            .required()
            .desc("Concurrent servers compacting")
            .hasArg()
            .build()
    );
    options.addOption(
        Option.builder("minModTime").
            desc("Compact if store files have modification time < minModTime")
            .hasArg()
            .build()
    );
    options.addOption(
        Option.builder("zk")
            .optionalArg(true)
            .desc("zk quorum")
            .hasArg()
            .build()
    );
    options.addOption(
        Option.builder("rootDir")
            .optionalArg(true)
            .desc("hbase.rootDir")
            .hasArg()
            .build()
    );
    options.addOption(
        Option.builder("sleep")
            .desc("Time to sleepForMs (ms) for checking compaction status per region and available "
                + "work queues: default 30s")
            .hasArg()
            .build()
    );
    options.addOption(
        Option.builder("retries")
        .desc("Max # of retries for a compaction request," + " defaults to 3")
            .hasArg()
            .build()
    );
    options.addOption(
        Option.builder("dryRun")
            .desc("Dry run, will just output a list of regions that require compaction based on "
            + "parameters passed")
            .hasArg(false)
            .build()
    );

    final CommandLineParser cmdLineParser = new DefaultParser();
    CommandLine commandLine = null;
    try {
      commandLine = cmdLineParser.parse(options, args);
    } catch (ParseException parseException) {
      System.out.println(
          "ERROR: Unable to parse command-line arguments " + Arrays.toString(args) + " due to: "
              + parseException);
      printUsage(options);

    }
    String tableName = commandLine.getOptionValue("table");
    String cf = commandLine.getOptionValue("cf", null);
    Set<String> families = Sets.newHashSet();
    if (cf != null) {
      Iterables.addAll(families, Splitter.on(",").split(cf));
    }


    Configuration configuration = HBaseConfiguration.create();
    int concurrency = Integer.parseInt(commandLine.getOptionValue("servers"));
    long minModTime = Long.parseLong(
        commandLine.getOptionValue("minModTime", String.valueOf(System.currentTimeMillis())));
    String quorum =
        commandLine.getOptionValue("zk", configuration.get(HConstants.ZOOKEEPER_QUORUM));
    String rootDir = commandLine.getOptionValue("rootDir", configuration.get(HConstants.HBASE_DIR));
    long sleep = Long.valueOf(commandLine.getOptionValue("sleep", Long.toString(30000)));

    configuration.set(HConstants.HBASE_DIR, rootDir);
    configuration.set(HConstants.ZOOKEEPER_QUORUM, quorum);

    MajorCompactor compactor =
        new MajorCompactor(configuration, TableName.valueOf(tableName), families, concurrency,
            minModTime, sleep);

    compactor.initializeWorkQueues();
    if (!commandLine.hasOption("dryRun")) {
      compactor.compactAllRegions();
    }
    compactor.shutdown();
  }

  private static void printUsage(final Options options) {
    String header = "\nUsage instructions\n\n";
    String footer = "\n";
    HelpFormatter formatter = new HelpFormatter();
    formatter.printHelp(MajorCompactor.class.getSimpleName(), header, options, footer, true);
  }

}
