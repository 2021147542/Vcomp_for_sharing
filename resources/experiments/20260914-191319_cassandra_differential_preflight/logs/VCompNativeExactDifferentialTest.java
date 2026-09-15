/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.cassandra.db.compaction.vcomp;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import org.junit.Test;

import org.apache.cassandra.cql3.CQLTester;
import org.apache.cassandra.db.ColumnFamilyStore;
import org.apache.cassandra.db.compaction.AbstractCompactionStrategy;
import org.apache.cassandra.db.compaction.CompactionController;
import org.apache.cassandra.db.compaction.CompactionIterator;
import org.apache.cassandra.db.compaction.OperationType;
import org.apache.cassandra.db.compaction.ShardManagerNoDisks;
import org.apache.cassandra.db.compaction.unified.ShardedCompactionWriter;
import org.apache.cassandra.db.lifecycle.LifecycleTransaction;
import org.apache.cassandra.db.rows.Row;
import org.apache.cassandra.db.rows.UnfilteredRowIterator;
import org.apache.cassandra.io.sstable.ISSTableScanner;
import org.apache.cassandra.io.sstable.format.SSTableReader;
import org.apache.cassandra.utils.ByteBufferUtil;
import org.apache.cassandra.utils.FBUtilities;
import org.apache.cassandra.utils.JsonUtils;
import org.apache.cassandra.utils.TimeUUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Bounded diagnostic only. Exact rows never enter the production VComp path.
 * Native reconciliation is CompactionIterator; the reference is an independent
 * explicit timestamp maximum over the same physical input rows. Neither side
 * uses the other's output to choose shard boundaries. This deliberately forces
 * one selected job; picker scheduling, byte prediction and materialization are
 * NOT validated by this test. No TTL, deletions, null cells or timestamp ties
 * with different values are admitted by the reference's restricted schema.
 */
public class VCompNativeExactDifferentialTest extends CQLTester
{
    private static final long SEED = 20260909L;
    private static final int DOMAIN = 4096;
    private static final int SHARDS = 4;
    private static final int ROW_LIMIT = 16384;
    private final VCompOrderedPartitionLayout layout = new VCompOrderedPartitionLayout(DOMAIN, 64);
    private final DeterministicFixedWidthKeyCodec codec = new DeterministicFixedWidthKeyCodec(24);

    @Test
    public void compareSameNativeInputsAgainstExactRowsAndApproximation() throws Throwable
    {
        createTable("CREATE TABLE %s (partition_id text, ck blob, value blob, PRIMARY KEY ((partition_id), ck))"
                    + " WITH compression = {'enabled':'false'}"
                    + " AND compaction = {'class':'UnifiedCompactionStrategy','scaling_parameters':'T4'}");
        ColumnFamilyStore cfs = getCurrentColumnFamilyStore();
        cfs.disableAutoCompaction();
        Random random = new Random(SEED);
        long[] timestamps = { 10300, 10100, 10400, 10200 };
        for (long timestamp : timestamps)
        {
            for (int i = 0; i < 2048; i++)
            {
                long key = random.nextInt(DOMAIN);
                execute("INSERT INTO %s (partition_id, ck, value) VALUES (?, ?, ?) USING TIMESTAMP ?",
                        layout.partitionFor(key).key(), codec.decode(key),
                        ByteBuffer.wrap(VCompCqlSstableMaterializer.valueFor(key, 43)), timestamp);
            }
            cfs.forceBlockingFlush(ColumnFamilyStore.FlushReason.UNIT_TESTS);
        }
        List<SSTableReader> inputs = new ArrayList<>(cfs.getLiveSSTables());
        // Stable fixture IDs depend on unique flush timestamps, never generated UUID/path.
        inputs.sort(Comparator.comparingLong(SSTableReader::getMaxTimestamp));
        assertEquals(4, inputs.size());
        List<Map<String, Object>> inputEvidence = new ArrayList<>();
        TreeMap<Long, List<Object>> exact = new TreeMap<>();
        List<VCompPipeline.VirtualSortedRun> virtualInputs = new ArrayList<>();
        List<VCompPipeline.VirtualSortedRun> exactFitInputs = new ArrayList<>();
        List<Map<String, Object>> flushDiagnostics = new ArrayList<>();
        for (int index = 0; index < inputs.size(); index++)
        {
            SSTableReader reader = inputs.get(index);
            List<List<Object>> rows = rows(reader);
            inputEvidence.add(metadata("input-" + index, reader, rows));
            for (List<Object> row : rows)
            {
                long key = (Long) row.get(1);
                List<Object> previous = exact.get(key);
                if (previous == null || (Long) row.get(3) > (Long) previous.get(3))
                    exact.put(key, row);
                else if (row.get(3).equals(previous.get(3)))
                    assertEquals("unsupported equal-timestamp conflicting values", previous, row);
            }
            long[] keys = rows.stream().mapToLong(r -> (Long) r.get(1)).toArray();
            Arrays.sort(keys);
            long maxTimestamp = rows.stream().mapToLong(r -> (Long) r.get(3)).max().getAsLong();
            virtualInputs.add(new DefaultFlushVirtualizer().virtualize(
                new VCompPipeline.FlushBatch("input-" + index, keys, reader.onDiskLength(), maxTimestamp)));
        }
        for (int index = 0; index < inputs.size(); index++)
        {
            List<List<Object>> physical = rows(inputs.get(index));
            long[] keys = physical.stream().mapToLong(r -> (Long) r.get(1)).toArray();
            VCompPipeline.VirtualSSTable descriptor = virtualInputs.get(index).sstables().get(0);
            flushDiagnostics.add(keyComparison("flush-" + index, physical, virtualInputs.get(index)));
            exactFitInputs.add(new DefaultFlushVirtualizer(0, VCompKmvSketch.DEFAULT_SAMPLES,
                                                          VCompKmvSketch.DEFAULT_RANGE_BUCKETS).virtualize(
                new VCompPipeline.FlushBatch("exact-fit-" + index, keys, inputs.get(index).onDiskLength(), descriptor.maximumTimestamp())));
        }
        List<List<List<Object>>> predictedShards = shard(new ArrayList<>(exact.values()));
        Map<String, Object> trace = object("schema_version", 1, "diagnostic_only", true, "seed", SEED,
                                       "fixture", object("domain", DOMAIN, "partitions", 64, "flushes", 4,
                                                      "attempted_inserts_per_flush", 2048, "forced_shards", SHARDS,
                                                      "key_bytes", 24, "value_bytes", 43,
                                                      "plr_error", DefaultFlushVirtualizer.DEFAULT_MODEL_ERROR,
                                                      "kmv_samples", VCompKmvSketch.DEFAULT_SAMPLES,
                                                      "kmv_range_buckets", VCompKmvSketch.DEFAULT_RANGE_BUCKETS,
                                                      "timestamp_order", timestamps,
                                                      "scope", "insert-only, no TTL/tombstones; globally unique scalar ck maps to one partition"));
        Map<String, Object> stages = new LinkedHashMap<>();
        Map<String, Object> job = object("job_id", 1, "inputs", inputEvidence, "stages", stages);
        trace.put("jobs", Arrays.asList(job));
        stages.put("picker", object("status", "not_checked", "reason", "same explicit four native SST inputs; native event scheduling not replayed"));
        // Capture predictions before invoking the native compaction.
        List<Map<String, Object>> predictedOutputs = new ArrayList<>();
        for (List<List<Object>> partitionRows : predictedShards)
            predictedOutputs.add(rowMetadata(partitionRows));
        LifecycleTransaction txn = cfs.getTracker().tryModify(inputs, OperationType.COMPACTION);
        assertTrue(txn != null);
        long now = FBUtilities.nowInSeconds();
        try (LifecycleTransaction transaction = txn;
             ShardedCompactionWriter writer = new ShardedCompactionWriter(cfs, cfs.getDirectories(), transaction,
                     transaction.originals(), false,
                     new ShardManagerNoDisks(ColumnFamilyStore.fullWeightedRange(-1, cfs.getPartitioner())).boundaries(SHARDS)))
        {
            try (AbstractCompactionStrategy.ScannerList scanners = cfs.getCompactionStrategyManager().getScanners(transaction.originals());
                 CompactionController controller = new CompactionController(cfs, transaction.originals(), cfs.gcBefore(now));
                 CompactionIterator iterator = new CompactionIterator(OperationType.COMPACTION, scanners.scanners,
                         controller, now, TimeUUID.minAtUnixMillis(System.currentTimeMillis())))
            {
                while (iterator.hasNext())
                    writer.append(iterator.next());
            }
            writer.finish();
        }
        List<SSTableReader> outputs = new ArrayList<>(cfs.getLiveSSTables());
        outputs.sort(Comparator.comparing(SSTableReader::getFirst));
        List<List<Object>> nativeRows = new ArrayList<>();
        List<Map<String, Object>> nativeOutputs = new ArrayList<>();
        List<Map<String, Object>> outputEvidence = new ArrayList<>();
        for (int index = 0; index < outputs.size(); index++)
        {
            List<List<Object>> rows = rows(outputs.get(index));
            nativeRows.addAll(rows);
            nativeOutputs.add(rowMetadata(rows));
            outputEvidence.add(metadata("output-" + index, outputs.get(index), rows));
        }
        nativeRows.sort(Comparator.comparingLong(r -> (Long) r.get(1)));
        job.put("native_outputs", outputEvidence);
        stages.put("merge", object("status", "checked", "native", rowMetadata(nativeRows),
                                "exact", rowMetadata(new ArrayList<>(exact.values()))));
        stages.put("split", object("status", "checked", "native", object("output_count", outputs.size(), "outputs", nativeOutputs),
                                "exact", object("output_count", predictedOutputs.size(), "outputs", predictedOutputs)));
        stages.put("physical_bytes", object("status", "not_checked", "reason", "actual Data.db and onDiskLength recorded; an exact serialization byte predictor is not implemented"));
        stages.put("materialization", object("status", "not_checked", "reason", "native compaction output scanned directly; no CQL writer round trip"));
        if (!nativeRows.equals(new ArrayList<>(exact.values())) || !nativeOutputs.equals(predictedOutputs))
        {
            trace.put("stopped_at", "first native/exact merge or split divergence; approximate stage not run");
            write(trace);
            return;
        }
        DefaultVirtualCompaction compactor = new DefaultVirtualCompaction();
        VCompPipeline.VirtualCompactionPlan plan = new VCompPipeline.VirtualCompactionPlan("same-native-inputs", virtualInputs, 1);
        VCompKmvSketch sketch = compactor.mergeAndDeduplicate(plan);
        long estimate = compactor.estimateUniqueKeys(plan, sketch);
        VCompLearnedModel model = compactor.merge(plan, estimate);
        VCompPipeline.VirtualSortedRun approximate = compactor.split(plan, model, sketch, estimate);
        List<List<Object>> approximateRows = new ArrayList<>();
        for (VCompPipeline.VirtualSSTable output : approximate.sstables())
        {
            VCompMaterializedKeyIterator keys = new VCompMaterializedKeyIterator(output);
            while (keys.hasNext())
            {
                long key = keys.nextLong();
                approximateRows.add(Arrays.asList(layout.partitionFor(key).key(), key, output.maximumTimestamp(),
                                                   output.maximumTimestamp(), ByteBufferUtil.bytesToHex(ByteBuffer.wrap(
                                                   VCompCqlSstableMaterializer.valueFor(key, 43)))));
                assertTrue("bounded diagnostic row limit", approximateRows.size() <= ROW_LIMIT);
            }
        }
        approximateRows.sort(Comparator.comparingLong(r -> (Long) r.get(1)));
        stages.put("approximation", object("status", "checked", "native", rowMetadata(nativeRows),
                "approximate", rowMetadata(approximateRows), "note",
                "Production PLR/KMV merge and materialized-key iterator on identical native inputs, unsplit union; not the production picker or shard splitter. Per-output max timestamp is the production representation."));
        Map<String, Object> decomposition = object("scope", "same first job only; exact-count/error-zero lanes are diagnostic interventions, not production changes",
                                                  "flush_reconstruction", flushDiagnostics,
                                                  "native_union_count", exact.size(),
                                                  "global_kmv", sketchEvidence(sketch),
                                                  "global_kmv_estimate", estimate);
        List<Map<String, Object>> lanes = new ArrayList<>();
        lanes.add(keyComparison("production-default", nativeRows, approximate));
        VCompLearnedModel countOnly = compactor.merge(plan, exact.size());
        lanes.add(keyComparison("exact-global-count-only", nativeRows,
                                compactor.split(plan, countOnly, sketch, exact.size())));
        VCompPipeline.VirtualCompactionPlan exactFitPlan = new VCompPipeline.VirtualCompactionPlan("exact-input-fit", exactFitInputs, 1);
        VCompLearnedModel exactFitModel = compactor.merge(exactFitPlan, exact.size());
        lanes.add(keyComparison("exact-global-count-and-zero-error-input-fit", nativeRows,
                                compactor.split(exactFitPlan, exactFitModel, sketch, exact.size())));
        long[] exactKeys = exact.keySet().stream().mapToLong(Long::longValue).toArray();
        for (double error : new double[] { 8.0, 0.0 })
        {
            VCompPipeline.VirtualSortedRun directlyFitted = new DefaultFlushVirtualizer(error,
                VCompKmvSketch.DEFAULT_SAMPLES, VCompKmvSketch.DEFAULT_RANGE_BUCKETS).virtualize(
                new VCompPipeline.FlushBatch("exact-union-fit", exactKeys, 1, 10400));
            lanes.add(keyComparison("direct-exact-union-fit-error-" + error, nativeRows, directlyFitted));
        }
        decomposition.put("lanes", lanes);
        trace.put("first_job_decomposition", decomposition);
        trace.put("stopped_at", nativeRows.equals(approximateRows) ? "bounded fixture complete" : "first approximate output divergence; no later jobs executed");
        write(trace);
    }

    private Map<String, Object> sketchEvidence(VCompKmvSketch sketch)
    {
        BigInteger unsigned = new BigInteger(Long.toUnsignedString(sketch.thetaHash()));
        double theta = unsigned.doubleValue() / Math.scalb(1.0, 64);
        return object("sample_count", sketch.samples().size(), "complete", sketch.isComplete(),
                      "theta_unsigned", unsigned.toString(), "theta_probability", theta,
                      "sample_count_over_theta", sketch.samples().size() / theta);
    }

    private Map<String, Object> keyComparison(String label, List<List<Object>> reference,
                                              VCompPipeline.VirtualSortedRun run)
    {
        java.util.TreeSet<Long> expected = new java.util.TreeSet<>();
        Map<Long, Long> versions = new TreeMap<>();
        for (List<Object> row : reference)
        {
            expected.add((Long) row.get(1));
            versions.put((Long) row.get(1), (Long) row.get(3));
        }
        java.util.TreeSet<Long> emitted = new java.util.TreeSet<>();
        long descriptorCount = 0;
        long timestampDifferences = 0;
        List<Map<String, Object>> descriptors = new ArrayList<>();
        for (VCompPipeline.VirtualSSTable descriptor : run.sstables())
        {
            descriptorCount += descriptor.estimatedUniqueKeys();
            descriptors.add(object("estimated_count", descriptor.estimatedUniqueKeys(),
                                   "segments", descriptor.model().segments().size(),
                                   "maximum_timestamp", descriptor.maximumTimestamp(),
                                   "global_sketch", sketchEvidence(descriptor.sketch()),
                                   "complete_range_sketches", descriptor.rangeSketches().stream().filter(r -> r.sketch().isComplete()).count(),
                                   "range_sketches", descriptor.rangeSketches().size()));
            VCompMaterializedKeyIterator iterator = new VCompMaterializedKeyIterator(descriptor);
            while (iterator.hasNext())
            {
                long key = iterator.nextLong();
                assertTrue("bounded key iterator", emitted.size() < ROW_LIMIT);
                assertTrue("unexpected duplicate output key", emitted.add(key));
                if (versions.containsKey(key) && versions.get(key) != descriptor.maximumTimestamp())
                    timestampDifferences++;
            }
        }
        java.util.TreeSet<Long> missing = new java.util.TreeSet<>(expected);
        missing.removeAll(emitted);
        java.util.TreeSet<Long> extra = new java.util.TreeSet<>(emitted);
        extra.removeAll(expected);
        return object("lane", label, "native_count", expected.size(), "descriptor_count", descriptorCount,
                      "emitted_count", emitted.size(), "count_lost_at_inverse", descriptorCount - emitted.size(),
                      "missing_keys", missing.size(), "extra_keys", extra.size(),
                      "common_keys", expected.size() - missing.size(), "timestamp_differences", timestampDifferences,
                      "first_missing_keys", new ArrayList<>(missing).subList(0, Math.min(8, missing.size())),
                      "first_extra_keys", new ArrayList<>(extra).subList(0, Math.min(8, extra.size())),
                      "descriptors", descriptors);
    }

    private List<List<Object>> rows(SSTableReader reader) throws Exception
    {
        List<List<Object>> result = new ArrayList<>();
        try (ISSTableScanner scanner = reader.getScanner())
        {
            while (scanner.hasNext())
            {
                try (UnfilteredRowIterator partition = scanner.next())
                {
                    assertTrue(partition.partitionLevelDeletion().isLive());
                    assertTrue(partition.staticRow().isEmpty());
                    String partitionKey = ByteBufferUtil.string(partition.partitionKey().getKey());
                    while (partition.hasNext())
                    {
                        Object item = partition.next();
                        assertTrue("range tombstones are outside this reference", item instanceof Row);
                        Row row = (Row) item;
                        assertTrue(row.deletion().isLive());
                        assertFalse(row.primaryKeyLivenessInfo().isExpiring());
                        org.apache.cassandra.db.rows.Cell<?> cell = row.cells().iterator().next();
                        assertFalse(cell.isTombstone());
                        assertFalse(cell.isExpiring());
                        assertEquals("reference admits full INSERT versions only", row.primaryKeyLivenessInfo().timestamp(), cell.timestamp());
                        long key = codec.encode(row.clustering());
                        assertEquals(layout.partitionFor(key).key(), partitionKey);
                        result.add(Arrays.asList(partitionKey, key, row.primaryKeyLivenessInfo().timestamp(),
                                                 cell.timestamp(), ByteBufferUtil.bytesToHex(cell.buffer())));
                        assertTrue("bounded diagnostic row limit", result.size() <= ROW_LIMIT);
                    }
                }
            }
        }
        result.sort(Comparator.comparingLong(r -> (Long) r.get(1)));
        return result;
    }

    private List<List<List<Object>>> shard(List<List<Object>> rows)
    {
        List<List<List<Object>>> shards = new ArrayList<>();
        for (int i = 0; i < SHARDS; i++) shards.add(new ArrayList<>());
        for (List<Object> row : rows)
        {
            long token = layout.partitionFor((Long) row.get(1)).token();
            // Native ranges are (left, right]; exact integer arithmetic, independently
            // derived for four equal full-ring token intervals. No actual outputs read.
            BigInteger position = BigInteger.valueOf(token).subtract(BigInteger.valueOf(Long.MIN_VALUE));
            int index = Math.max(0, position.subtract(BigInteger.ONE).multiply(BigInteger.valueOf(SHARDS)).shiftRight(64).intValue());
            shards.get(index).add(row);
        }
        shards.removeIf(List::isEmpty);
        return shards;
    }

    private Map<String, Object> rowMetadata(List<List<Object>> rows)
    {
        return object("row_count", rows.size(), "key_min", rows.isEmpty() ? null : rows.get(0).get(1),
                   "key_max", rows.isEmpty() ? null : rows.get(rows.size() - 1).get(1),
                   "row_fields", Arrays.asList("partition", "ck", "liveness_timestamp", "cell_timestamp", "value_hex"), "rows", rows);
    }

    private Map<String, Object> metadata(String id, SSTableReader reader, List<List<Object>> rows) throws Exception
    {
        Map<String, Object> result = rowMetadata(rows);
        result.put("id", id);
        result.put("descriptor_provenance_only", reader.descriptor.toString());
        result.put("on_disk_length_bytes", reader.onDiskLength());
        result.put("data_db_bytes", Files.size(Paths.get(reader.getFilename())));
        result.put("first_partition_token", reader.getFirst().getToken().toString());
        result.put("last_partition_token", reader.getLast().getToken().toString());
        return result;
    }

    private static Map<String, Object> object(Object... pairs)
    {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }

    private static void write(Map<String, Object> trace) throws Exception
    {
        Path output = Paths.get(System.getProperty("vcomp.differential.output", "build/test/vcomp-native-exact.json"));
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.write(output, JsonUtils.JSON_OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(trace));
        System.out.println("VCOMP_DIFFERENTIAL_TRACE=" + output.toAbsolutePath());
    }
}
