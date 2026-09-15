/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.cassandra.db.compaction.vcomp;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.junit.Test;

import org.apache.cassandra.db.rows.EncodingStats;
import org.apache.cassandra.db.rows.Row;
import org.apache.cassandra.db.rows.UnfilteredRowIterator;
import org.apache.cassandra.io.sstable.CQLSSTableWriter;
import org.apache.cassandra.io.sstable.Descriptor;
import org.apache.cassandra.io.sstable.ISSTableScanner;
import org.apache.cassandra.io.sstable.format.SSTableReader;
import org.apache.cassandra.schema.Schema;
import org.apache.cassandra.utils.ByteBufferUtil;
import org.apache.cassandra.utils.JsonUtils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Exact-key timestamp ablation only; neither native compaction nor production cardinality is tested. */
public class VCompTimestampMaterializationDiagnosticTest
{
    private static final long SEED = 20260909L;
    private static final int WRITES = 4096;
    private final VCompOrderedPartitionLayout layout = new VCompOrderedPartitionLayout(WRITES, 64);
    private final DeterministicFixedWidthKeyCodec codec = new DeterministicFixedWidthKeyCodec(24);

    @Test
    public void measureCalibrationAgainstOneRealDomainFlush() throws Exception
    {
        Path root = Paths.get(System.getProperty("vcomp.timestamp.output")).resolve("calibration-one-flush");
        Files.createDirectories(root);
        long domain = 104857600L;
        int partitions = 10000;
        int writes = 65536;
        String keyspace = "vcomp_size_diagnostic";
        String table = "kv";
        String schema = "CREATE TABLE " + keyspace + '.' + table
                        + " (partition_id text, ck blob, value blob, PRIMARY KEY ((partition_id), ck))"
                        + " WITH compression = {'enabled': 'false'}";
        String insert = "INSERT INTO " + keyspace + '.' + table
                        + " (partition_id, ck, value) VALUES (?, ?, ?) USING TIMESTAMP ?";
        VCompOrderedPartitionLayout partitionLayout = new VCompOrderedPartitionLayout(domain, partitions);
        VCompCqlSstableMaterializer materializer = new VCompCqlSstableMaterializer(
            root, schema, insert, keyspace, table, partitionLayout, 64, 1024, codec, 1000);
        VCompSSTSizeModel size = materializer.calibrateSizeModel(4096, 8192);
        VCompPipeline.FlushBatch flush = new SyntheticVCompLoadSource(writes, writes, domain, 1024, SEED)
                                        .flushBatches().iterator().next();
        List<Map<String, Object>> outputs = new ArrayList<>();
        // Both lanes use the same fixed first flush. Error zero is a diagnostic control only.
        for (double error : new double[] { 0.0, DefaultFlushVirtualizer.DEFAULT_MODEL_ERROR })
        {
            VCompPipeline.FlushBatch named = new VCompPipeline.FlushBatch("error-" + error,
                flush.keyCoordinates(), flush.logicalBytes(), flush.maximumTimestamp());
            VCompPipeline.VirtualSortedRun run = new DefaultFlushVirtualizer(error, 512, 8, size).virtualize(named);
            VCompPipeline.MaterializedState state = materializer.materialize(
                new VCompPipeline.FrozenLayout(Collections.singletonList(run)));
            Map<String, Object> measured = sizeEvidence(Paths.get(state.sstableIds().get(0)), keyspace, table);
            measured.put("model_error", error);
            measured.put("predicted_data_db_bytes", size.estimate(flush.keyCoordinates().length));
            measured.put("descriptor_bytes", run.sstables().get(0).estimatedBytes());
            measured.put("control", error == 0 ? "exact-key diagnostic" : "production-default PLR keys");
            assertEquals(flush.keyCoordinates().length, ((Number) measured.get("rows")).intValue());
            if (error == 0)
            {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                for (long key : flush.keyCoordinates())
                    digest.update(ByteBuffer.allocate(8).putLong(key).array());
                assertEquals("zero-error key control must reproduce the exact input keys",
                             ByteBufferUtil.bytesToHex(ByteBuffer.wrap(digest.digest())), measured.get("keys_sha256"));
            }
            outputs.add(measured);
        }
        List<Map<String, Object>> calibration = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root.resolve(".vcomp-calibration")))
        {
            for (Path path : (Iterable<Path>) files.filter(p -> p.toString().endsWith("-Data.db"))::iterator)
                calibration.add(sizeEvidence(path.getParent(), keyspace, table));
        }
        Map<String, Object> report = object("schema_version", 1, "diagnostic_only", true, "seed", SEED,
            "domain", domain, "partition_layout_count", partitions, "attempted_writes", writes,
            "flush_unique_keys", flush.keyCoordinates().length, "compression", false,
            "calibration", calibration, "outputs", outputs,
            "scope", "Only first 64MiB logical flush in 100GiB domain, offline CQL writer. No native flush, UCS scheduling, or benchmark",
            "timestamp", "All output rows use descriptor ordinal 65536, matching production materializer");
        Files.write(root.resolve("calibration-one-flush.json"),
                    JsonUtils.JSON_OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(report));
    }

    private Map<String, Object> sizeEvidence(Path directory, String keyspace, String table) throws Exception
    {
        Path data;
        try (Stream<Path> files = Files.walk(directory))
        {
            data = files.filter(path -> path.toString().endsWith("-Data.db")).findFirst().get();
        }
        SSTableReader reader = SSTableReader.open(null,
            Descriptor.fromFile(new org.apache.cassandra.io.util.File(data)),
            Schema.instance.getTableMetadataRef(keyspace, table));
        long rowCount = 0;
        long partitionCount = 0;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try
        {
            try (ISSTableScanner scanner = reader.getScanner())
            {
                while (scanner.hasNext())
                {
                    try (UnfilteredRowIterator partition = scanner.next())
                    {
                        partitionCount++;
                        while (partition.hasNext())
                        {
                            Row row = (Row) partition.next();
                            digest.update(ByteBuffer.allocate(8).putLong(codec.encode(row.clustering())).array());
                            rowCount++;
                        }
                    }
                }
            }
        }
        finally
        {
            reader.selfRef().release();
        }
        return object("rows", rowCount, "partitions", partitionCount, "data_db_bytes", Files.size(data),
                      "keys_sha256", ByteBufferUtil.bytesToHex(ByteBuffer.wrap(digest.digest())), "data_file", data.toString());
    }

    @Test
    public void isolateDescriptorTimestampAndPhysicalWriter() throws Exception
    {
        Path root = Paths.get(System.getProperty("vcomp.timestamp.output"));
        Files.createDirectories(root);
        TreeMap<Long, Long> versions = new TreeMap<>();
        SplittableRandom random = new SplittableRandom(SEED);
        for (long ordinal = 0; ordinal < WRITES; ordinal++)
            versions.put(random.nextLong(WRITES), ordinal + 1);
        List<Map<String, Object>> cases = new ArrayList<>();
        for (int valueBytes : new int[] { 43, 1000 })
        {
            String keyspace = "vcomp_timestamp_diagnostic";
            String table = "kv" + valueBytes;
            String schema = "CREATE TABLE " + keyspace + '.' + table
                            + " (partition_id text, ck blob, value blob, PRIMARY KEY ((partition_id), ck))"
                            + " WITH compression = {'enabled': 'false'}";
            String insert = "INSERT INTO " + keyspace + '.' + table
                            + " (partition_id, ck, value) VALUES (?, ?, ?) USING TIMESTAMP ?";
            Path exact = root.resolve("exact").resolve(keyspace).resolve(table + "-00000000000000000000000000000001");
            Path flat = root.resolve("flat-cql").resolve(keyspace).resolve(table + "-00000000000000000000000000000002");
            writeCql(exact, schema, insert, versions, valueBytes, false);
            writeCql(flat, schema, insert, versions, valueBytes, true);
            long[] keys = versions.keySet().stream().mapToLong(Long::longValue).toArray();
            // Error zero deliberately isolates timestamps from PLR approximation.
            // This test-only control is not the production default model.
            VCompPipeline.VirtualSortedRun run = new DefaultFlushVirtualizer(0, 512, 8).virtualize(
                new VCompPipeline.FlushBatch("controlled-exact-keys", keys,
                                            (long) keys.length * (24 + valueBytes), WRITES));
            VCompCqlSstableMaterializer materializer = new VCompCqlSstableMaterializer(
                root.resolve(table + "-production-materializer"), schema, insert, keyspace, table,
                layout, 64, 24 + valueBytes, codec, valueBytes);
            VCompPipeline.MaterializedState state = materializer.materialize(
                new VCompPipeline.FrozenLayout(Collections.singletonList(run)));
            Map<String, Object> exactEvidence = scan(exact, keyspace, table);
            Map<String, Object> flatEvidence = scan(flat, keyspace, table);
            Map<String, Object> materialized = scan(Paths.get(state.sstableIds().get(0)), keyspace, table);
            assertEquals(versions.size(), exactEvidence.get("row_count"));
            for (Object recorded : (List<?>) exactEvidence.get("rows"))
            {
                List<?> row = (List<?>) recorded;
                long key = (Long) row.get(0);
                assertEquals(versions.get(key), row.get(1));
                assertEquals(versions.get(key), row.get(2));
                assertEquals(ByteBufferUtil.bytesToHex(ByteBuffer.wrap(
                    VCompCqlSstableMaterializer.valueFor(key, valueBytes))), row.get(3));
            }
            assertEquals(versions.size(), state.materializedKeys());
            assertEquals("equal exact keys and uniform timestamps must roundtrip identically",
                         flatEvidence.get("rows"), materialized.get("rows"));
            assertEquals("same timestamp/value rows have same Data.db size in this fixture",
                         flatEvidence.get("data_db_bytes"), materialized.get("data_db_bytes"));
            long differences = versions.values().stream().filter(timestamp -> timestamp != WRITES).count();
            cases.add(object("value_bytes", valueBytes, "key_bytes", 24,
                             "exact", exactEvidence, "flattened_cql", flatEvidence,
                             "production_materializer", materialized,
                             "timestamp_differences", differences,
                             "data_db_delta_bytes", (Long) materialized.get("data_db_bytes") - (Long) exactEvidence.get("data_db_bytes")));
        }
        Map<String, Object> report = object("schema_version", 1, "diagnostic_only", true, "seed", SEED,
            "writes", WRITES, "domain", WRITES, "partitions", 64, "unique_keys", versions.size(),
            "compression", false, "cases", cases,
            "scope", "One-flush timestamp ablation, exact key control with PLR error 0; production writer roundtrip, not native flush/compaction or latency benchmark",
            "first_information_loss", "SyntheticVCompLoadSource sorts/deduplicates keys and retains generated ordinal only as FlushBatch.maximumTimestamp",
            "limitations", Arrays.asList("Physical result applies to fixed uncompressed fixture only",
                                         "Default PLR/KMV approximation intentionally excluded",
                                         "Values are key-deterministic; timestamp inequality alone does not imply changed current read value",
                                         "Actual campaign timestamps/coverage and compressed/native writers require separate comparison"));
        Files.write(root.resolve("timestamp-materialization.json"),
                    JsonUtils.JSON_OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(report));
    }

    private void writeCql(Path path, String schema, String insert, TreeMap<Long, Long> versions,
                          int valueBytes, boolean flatten) throws Exception
    {
        Files.createDirectories(path);
        long minimum = flatten ? WRITES : Collections.min(versions.values());
        EncodingStats stats = new EncodingStats(minimum, EncodingStats.NO_STATS.minLocalDeletionTime,
                                                EncodingStats.NO_STATS.minTTL);
        try (CQLSSTableWriter writer = CQLSSTableWriter.builder().inDirectory(path.toString())
                .forTable(schema).using(insert).withEncodingStats(stats).withEstimatedPartitionCount(64).sorted().build())
        {
            for (Map.Entry<Long, Long> row : versions.entrySet())
                writer.addRow(layout.partitionFor(row.getKey()).key(), codec.decode(row.getKey()),
                              ByteBuffer.wrap(VCompCqlSstableMaterializer.valueFor(row.getKey(), valueBytes)),
                              flatten ? (long) WRITES : row.getValue());
        }
    }

    private Map<String, Object> scan(Path directory, String keyspace, String table) throws Exception
    {
        List<Path> dataFiles = new ArrayList<>();
        try (Stream<Path> files = Files.walk(directory))
        {
            files.filter(path -> path.toString().endsWith("-Data.db")).forEach(dataFiles::add);
        }
        assertEquals(1, dataFiles.size());
        Path data = dataFiles.get(0);
        List<List<Object>> rows = new ArrayList<>();
        SSTableReader reader = SSTableReader.open(null,
            Descriptor.fromFile(new org.apache.cassandra.io.util.File(data)),
            Schema.instance.getTableMetadataRef(keyspace, table));
        long encodingMin;
        try
        {
            encodingMin = reader.header.stats().minTimestamp;
            try (ISSTableScanner scanner = reader.getScanner())
            {
                while (scanner.hasNext())
                {
                    try (UnfilteredRowIterator partition = scanner.next())
                    {
                        assertTrue(partition.partitionLevelDeletion().isLive());
                        while (partition.hasNext())
                        {
                            Row row = (Row) partition.next();
                            long key = codec.encode(row.clustering());
                            assertEquals(layout.partitionFor(key).key(), ByteBufferUtil.string(partition.partitionKey().getKey()));
                            rows.add(Arrays.asList(key, row.primaryKeyLivenessInfo().timestamp(),
                                row.cells().iterator().next().timestamp(),
                                ByteBufferUtil.bytesToHex(row.cells().iterator().next().buffer())));
                        }
                    }
                }
            }
        }
        finally
        {
            reader.selfRef().release();
        }
        return object("row_count", rows.size(), "rows", rows, "data_db_bytes", Files.size(data),
                      "encoding_min_timestamp", encodingMin, "data_file", data.toString());
    }

    private static Map<String, Object> object(Object... pairs)
    {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }
}
