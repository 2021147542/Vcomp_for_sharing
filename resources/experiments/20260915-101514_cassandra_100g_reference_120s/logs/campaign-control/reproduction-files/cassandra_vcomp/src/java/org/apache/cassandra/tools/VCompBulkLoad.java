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
package org.apache.cassandra.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.stream.Stream;

import org.apache.cassandra.db.compaction.vcomp.DeterministicFixedWidthKeyCodec;
import org.apache.cassandra.db.compaction.vcomp.SyntheticVCompLoadSource;
import org.apache.cassandra.db.compaction.vcomp.VCompCqlSstableMaterializer;
import org.apache.cassandra.db.compaction.vcomp.VCompFilesystemVerifier;
import org.apache.cassandra.db.compaction.vcomp.VCompNodetoolInstaller;
import org.apache.cassandra.db.compaction.vcomp.VCompOrderedPartitionLayout;
import org.apache.cassandra.db.compaction.vcomp.VCompPipeline;
import org.apache.cassandra.db.compaction.vcomp.VCompSSTSizeModel;
import org.apache.cassandra.db.compaction.vcomp.VCompUcsPlanner;
import org.apache.cassandra.io.util.File;
import org.apache.cassandra.utils.Clock;

/** Command-line entry point for the ordered-partition VComp experiment. */
public final class VCompBulkLoad
{
    private VCompBulkLoad()
    {
    }

    public static void main(String[] arguments) throws Exception
    {
        if (arguments.length != 12 && arguments.length != 13)
        {
            throw new IllegalArgumentException("usage: VCompBulkLoad <output-dir> <nodetool> <keyspace> <table> "
                                               + "<writes> <entry-bytes> <key-bytes> <value-bytes> "
                                               + "<flush-bytes> <target-sst-bytes> <partition-count> <seed> "
                                               + "[logical|calibrated]");
        }

        Path outputDirectory = new File(arguments[0]).toPath();
        Path nodetool = new File(arguments[1]).toPath();
        String keyspace = arguments[2];
        String table = arguments[3];
        validateIdentifier(keyspace, "keyspace");
        validateIdentifier(table, "table");
        long writes = Long.parseLong(arguments[4]);
        int entryBytes = Integer.parseInt(arguments[5]);
        int keyBytes = Integer.parseInt(arguments[6]);
        int valueBytes = Integer.parseInt(arguments[7]);
        long flushBytes = Long.parseLong(arguments[8]);
        long requestedTargetSSTBytes = Long.parseLong(arguments[9]);
        int partitionCount = Integer.parseInt(arguments[10]);
        long seed = Long.parseLong(arguments[11]);
        String sizeModelName = arguments.length == 13 ? arguments[12] : "calibrated";
        if (!sizeModelName.equals("logical") && !sizeModelName.equals("calibrated"))
            throw new IllegalArgumentException("size model must be logical or calibrated");
        if (keyBytes + valueBytes != entryBytes)
            throw new IllegalArgumentException("entry-bytes must equal key-bytes + value-bytes");
        DeterministicFixedWidthKeyCodec keyCodec = new DeterministicFixedWidthKeyCodec(keyBytes);
        if (flushBytes < entryBytes)
            throw new IllegalArgumentException("flush-bytes must hold at least one entry");
        if (requestedTargetSSTBytes <= 0 || requestedTargetSSTBytes % (1L << 20) != 0)
            throw new IllegalArgumentException("target-sst-bytes must be a positive whole number of MiB");
        if (requestedTargetSSTBytes / (1L << 20) > Integer.MAX_VALUE)
            throw new IllegalArgumentException("target-sst-bytes is too large");
        VCompOrderedPartitionLayout partitionLayout = new VCompOrderedPartitionLayout(writes, partitionCount);
        int targetSSTableMiB = Math.toIntExact(requestedTargetSSTBytes / (1L << 20));
        requireEmptyOutputDirectory(outputDirectory);
        if (!Files.isRegularFile(nodetool) || !Files.isExecutable(nodetool))
            throw new IllegalArgumentException("nodetool is missing or not executable: " + nodetool);
        int writesPerFlush = Math.toIntExact(flushBytes / entryBytes);

        String tableSchema = "CREATE TABLE " + keyspace + '.' + table + " ("
                             + "partition_id text, ck blob, value blob, "
                             + "PRIMARY KEY ((partition_id), ck)) "
                             + "WITH CLUSTERING ORDER BY (ck ASC) "
                             + "AND compaction = {'class': 'UnifiedCompactionStrategy', "
                             + "'enabled': 'false', 'scaling_parameters': 'T4', "
                             + "'target_sstable_size': '" + targetSSTableMiB + "MiB', "
                             + "'base_shard_count': '1', 'sstable_growth': '0.333'} "
                             + "AND compression = {'enabled': 'false'}";
        String insert = "INSERT INTO " + keyspace + '.' + table
                        + " (partition_id, ck, value) VALUES (?, ?, ?) USING TIMESTAMP ?";

        SyntheticVCompLoadSource source = new SyntheticVCompLoadSource(writes,
                                                                        writesPerFlush,
                                                                        writes,
                                                                        entryBytes,
                                                                        seed);
        VCompCqlSstableMaterializer materializer = new VCompCqlSstableMaterializer(outputDirectory,
                                                                                   tableSchema,
                                                                                   insert,
                                                                                   keyspace,
                                                                                   table,
                                                                                   partitionLayout,
                                                                                   targetSSTableMiB,
                                                                                   entryBytes,
                                                                                   keyCodec,
                                                                                   valueBytes);
        long startNanos = Clock.Global.nanoTime();
        VCompSSTSizeModel sizeModel = sizeModelName.equals("calibrated")
                                      ? materializer.calibrateSizeModel(4096, 8192)
                                      : VCompSSTSizeModel.logical(entryBytes);
        long estimatedFlushDataBytes = sizeModel.estimate(writesPerFlush);
        // Every source iterator starts at the same seed. Inspecting this first
        // buffered flush leaves the pipeline's subsequent source stream intact.
        VCompPipeline.FlushBatch firstFlush = source.flushBatches().iterator().next();
        long measuredFlushDataBytes = sizeModelName.equals("calibrated")
                                      ? materializer.measureFirstFlushDataBytes(firstFlush) : -1;
        long pickerFlushSizeBytes = VCompUcsPlanner.roundObservedFlushSize(
        measuredFlushDataBytes > 0 ? measuredFlushDataBytes : estimatedFlushDataBytes);
        System.out.printf("VCOMP_SIZING estimated_flush_bytes=%d measured_first_flush_bytes=%d "
                          + "first_flush_unique_keys=%d picker_flush_size_bytes=%d size_basis=%s "
                          + "first_flush_timestamp_encoding=flat_batch_maximum%n",
                          estimatedFlushDataBytes, measuredFlushDataBytes, firstFlush.keyCount(), pickerFlushSizeBytes,
                          sizeModelName.equals("calibrated") ? "first_source_flush_data_component" : "logical_kv");
        VCompPipeline pipeline = VCompPipeline.createDefault(flushBytes,
                                                              pickerFlushSizeBytes,
                                                              requestedTargetSSTBytes,
                                                              sizeModel,
                                                              partitionLayout,
                                                              materializer,
                                                              new VCompNodetoolInstaller(nodetool,
                                                                                         keyspace,
                                                                                         table),
                                                              new VCompFilesystemVerifier());

        VCompPipeline.Result result = pipeline.execute(new VCompPipeline.Request(
        VCompPipeline.ExecutionConstraints.orderedPartitionKeyValue(partitionCount), source));
        double seconds = (Clock.Global.nanoTime() - startNanos) / 1_000_000_000.0;

        long estimatedFinalBytes = 0;
        long maximumEstimatedSSTableBytes = 0;
        long maximumEstimatedRunBytes = 0;
        int maximumSSTablesPerRun = 0;
        for (VCompPipeline.VirtualSortedRun run : result.layout().runs())
        {
            long runBytes = 0;
            maximumSSTablesPerRun = Math.max(maximumSSTablesPerRun, run.sstables().size());
            for (VCompPipeline.VirtualSSTable sstable : run.sstables())
            {
                runBytes = saturatedAdd(runBytes, sstable.estimatedBytes());
                maximumEstimatedSSTableBytes = Math.max(maximumEstimatedSSTableBytes,
                                                        sstable.estimatedBytes());
            }
            estimatedFinalBytes = saturatedAdd(estimatedFinalBytes, runBytes);
            maximumEstimatedRunBytes = Math.max(maximumEstimatedRunBytes, runBytes);
        }
        PhysicalMetrics physical = measurePhysicalSSTables(outputDirectory);

        System.out.printf("VCOMP_RESULT seconds=%.3f writes=%d flush_bytes=%d target_sst_bytes=%d size_model=%s materialized_keys=%d logical_bytes=%d "
                          + "virtual_compactions=%d final_runs=%d final_sstables=%d "
                          + "estimated_final_bytes=%d max_estimated_sstable_bytes=%d "
                          + "max_estimated_run_bytes=%d max_sstables_per_run=%d "
                          + "physical_sst_bytes=%d max_physical_sst_bytes=%d physical_sst_count=%d%n",
                          seconds,
                          writes,
                          flushBytes,
                          requestedTargetSSTBytes,
                          sizeModelName,
                          result.materialized().materializedKeys(),
                          result.materialized().logicalBytes(),
                          result.virtualCompactionCount(),
                          result.layout().runs().size(),
                          result.materialized().sstableIds().size(),
                          estimatedFinalBytes,
                          maximumEstimatedSSTableBytes,
                          maximumEstimatedRunBytes,
                          maximumSSTablesPerRun,
                          physical.totalBytes,
                          physical.maximumBytes,
                          physical.count);
    }

    private static PhysicalMetrics measurePhysicalSSTables(Path root) throws IOException
    {
        PhysicalMetrics result = new PhysicalMetrics();
        Path calibrationRoot = root.resolve(".vcomp-calibration").toAbsolutePath();
        Map<String, PhysicalMetrics> byDescriptor = new HashMap<>();
        try (Stream<Path> paths = Files.walk(root))
        {
            Iterator<Path> iterator = paths.iterator();
            while (iterator.hasNext())
            {
                Path path = iterator.next();
                if (path.toAbsolutePath().startsWith(calibrationRoot))
                    continue;
                if (!Files.isRegularFile(path))
                    continue;
                String name = path.getFileName().toString();
                int componentSeparator = name.lastIndexOf('-');
                if (componentSeparator <= 0 || !name.endsWith(".db"))
                    continue;
                String descriptor = path.getParent().toString() + '/' + name.substring(0, componentSeparator);
                PhysicalMetrics metrics = byDescriptor.computeIfAbsent(descriptor, ignored -> new PhysicalMetrics());
                metrics.totalBytes = saturatedAdd(metrics.totalBytes, Files.size(path));
                if (name.endsWith("-Data.db"))
                    metrics.count = 1;
            }
        }
        for (PhysicalMetrics metrics : byDescriptor.values())
        {
            if (metrics.count == 0)
                continue;
            result.totalBytes = saturatedAdd(result.totalBytes, metrics.totalBytes);
            result.maximumBytes = Math.max(result.maximumBytes, metrics.totalBytes);
            result.count += metrics.count;
        }
        return result;
    }

    private static void requireEmptyOutputDirectory(Path outputDirectory) throws IOException
    {
        if (!Files.exists(outputDirectory))
            return;
        if (!Files.isDirectory(outputDirectory))
            throw new IllegalArgumentException("output-dir is not a directory: " + outputDirectory);
        try (Stream<Path> entries = Files.list(outputDirectory))
        {
            if (entries.findAny().isPresent())
                throw new IllegalArgumentException("output-dir must be empty: " + outputDirectory);
        }
    }

    private static void validateIdentifier(String value, String name)
    {
        if (!value.matches("[A-Za-z_][A-Za-z0-9_]*"))
            throw new IllegalArgumentException(name + " is not a safe unquoted CQL identifier: " + value);
    }

    private static long saturatedAdd(long left, long right)
    {
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }

    private static final class PhysicalMetrics
    {
        private long totalBytes;
        private long maximumBytes;
        private int count;
    }
}
