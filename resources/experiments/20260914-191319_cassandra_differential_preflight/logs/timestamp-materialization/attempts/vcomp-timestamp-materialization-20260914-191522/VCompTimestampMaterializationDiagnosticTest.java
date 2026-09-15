/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.cassandra.db.compaction.vcomp;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
            Path exact = root.resolve(table + "-exact");
            Path flat = root.resolve(table + "-flat-cql");
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
