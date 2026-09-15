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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.stream.Stream;

import org.junit.Test;

import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.cql3.CQLTester;
import org.apache.cassandra.db.ColumnFamilyStore;
import org.apache.cassandra.db.compaction.AbstractCompactionStrategy;
import org.apache.cassandra.db.compaction.CompactionController;
import org.apache.cassandra.db.compaction.CompactionIterator;
import org.apache.cassandra.db.compaction.OperationType;
import org.apache.cassandra.db.compaction.ShardManagerNoDisks;
import org.apache.cassandra.db.compaction.unified.ShardedCompactionWriter;
import org.apache.cassandra.db.lifecycle.LifecycleTransaction;
import org.apache.cassandra.db.rows.EncodingStats;
import org.apache.cassandra.db.rows.Row;
import org.apache.cassandra.db.rows.UnfilteredRowIterator;
import org.apache.cassandra.io.sstable.CQLSSTableWriter;
import org.apache.cassandra.io.sstable.Descriptor;
import org.apache.cassandra.io.sstable.ISSTableScanner;
import org.apache.cassandra.io.sstable.format.SSTableReader;
import org.apache.cassandra.schema.Schema;
import org.apache.cassandra.utils.ByteBufferUtil;
import org.apache.cassandra.utils.FBUtilities;
import org.apache.cassandra.utils.JsonUtils;
import org.apache.cassandra.utils.TimeUUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/** Same native output rows, separate writer and timestamp interventions. Exact keys are test-only. */
public class VCompMatchedMaterializationTest extends CQLTester
{
    private static final long SEED = 20260909L;
    private static final int DOMAIN = 8192;
    private static final int PARTITIONS = 64;
    private static final int VALUE_BYTES = 1000;
    private static final int SHARDS = 4;
    private final VCompOrderedPartitionLayout layout = new VCompOrderedPartitionLayout(DOMAIN, PARTITIONS);
    private final DeterministicFixedWidthKeyCodec codec = new DeterministicFixedWidthKeyCodec(24);

    @Test
    public void isolateMatchedFormatWriterAndTimestamp() throws Throwable
    {
        Path root = Paths.get(System.getProperty("vcomp.matched.output"));
        Files.createDirectories(root);
        String options = " WITH compression = {'enabled':'false'}"
                         + " AND compaction = {'class':'UnifiedCompactionStrategy','scaling_parameters':'T4',"
                         + "'target_sstable_size':'64MiB','base_shard_count':'1','sstable_growth':'0.333'}";
        createTable("CREATE TABLE %s (partition_id text, ck blob, value blob, PRIMARY KEY ((partition_id), ck))" + options);
        ColumnFamilyStore cfs = getCurrentColumnFamilyStore();
        cfs.disableAutoCompaction();
        String ks = cfs.metadata().keyspace;
        String table = cfs.metadata().name;
        String schema = "CREATE TABLE " + ks + '.' + table
                        + " (partition_id text, ck blob, value blob, PRIMARY KEY ((partition_id), ck))" + options;
        String insert = "INSERT INTO " + ks + '.' + table
                        + " (partition_id, ck, value) VALUES (?, ?, ?) USING TIMESTAMP ?";
        SplittableRandom random = new SplittableRandom(SEED);
        long ordinal = 0;
        for (int flush = 0; flush < 4; flush++)
        {
            for (int i = 0; i < 4096; i++)
            {
                long key = random.nextLong(DOMAIN);
                execute("INSERT INTO %s (partition_id, ck, value) VALUES (?, ?, ?) USING TIMESTAMP ?",
                        layout.partitionFor(key).key(), codec.decode(key),
                        ByteBuffer.wrap(VCompCqlSstableMaterializer.valueFor(key, VALUE_BYTES)), ++ordinal);
            }
            cfs.forceBlockingFlush(ColumnFamilyStore.FlushReason.UNIT_TESTS);
        }
        List<SSTableReader> inputs = new ArrayList<>(cfs.getLiveSSTables());
        assertEquals(4, inputs.size());
        List<Map<String, Object>> inputEvidence = new ArrayList<>();
        for (SSTableReader input : inputs) inputEvidence.add(evidence(input));
        LifecycleTransaction txn = cfs.getTracker().tryModify(inputs, OperationType.COMPACTION);
        assertNotNull(txn);
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
                while (iterator.hasNext()) writer.append(iterator.next());
            }
            writer.finish();
        }
        List<SSTableReader> outputs = new ArrayList<>(cfs.getLiveSSTables());
        outputs.sort(Comparator.comparing(SSTableReader::getFirst));
        assertEquals(SHARDS, outputs.size());
        List<Map<String, Object>> cases = new ArrayList<>();
        for (int index = 0; index < outputs.size(); index++)
        {
            SSTableReader nativeOutput = outputs.get(index);
            List<long[]> exact = rows(nativeOutput);
            long maximum = exact.stream().mapToLong(row -> row[1]).max().getAsLong();
            long minimum = exact.stream().mapToLong(row -> row[1]).min().getAsLong();
            long partitionCount = exact.stream().map(row -> layout.partitionFor(row[0]).key()).distinct().count();
            Map<String, Object> lanes = new LinkedHashMap<>();
            lanes.put("native", evidence(nativeOutput));
            Path shard = root.resolve("shard-" + index);
            // Identical schema, format, row versions, and native serialization header stats.
            Path sameHeader = shard.resolve("same-rows-native-header").resolve(ks).resolve(table + "-00000000000000000000000000000001");
            write(sameHeader, schema, insert, exact, false, maximum, nativeOutput.header.stats(), partitionCount);
            lanes.put("same_rows_native_header", readEvidence(sameHeader, ks, table));
            // Change only the encoding header minimum, preserving all logical versions.
            Path exactMin = shard.resolve("same-rows-exact-header").resolve(ks).resolve(table + "-00000000000000000000000000000002");
            write(exactMin, schema, insert, exact, false, maximum, stats(minimum), partitionCount);
            lanes.put("same_rows_exact_header", readEvidence(exactMin, ks, table));
            // Change only descriptor timestamp representation relative to exact-header lane.
            Path flat = shard.resolve("flat-timestamps").resolve(ks).resolve(table + "-00000000000000000000000000000003");
            write(flat, schema, insert, exact, true, maximum, stats(maximum), partitionCount);
            lanes.put("flat_timestamps", readEvidence(flat, ks, table));
            long[] keys = exact.stream().mapToLong(row -> row[0]).toArray();
            VCompPipeline.VirtualSortedRun run = new DefaultFlushVirtualizer(0, 512, 8).virtualize(
                new VCompPipeline.FlushBatch("shard-" + index, keys, (long) keys.length * 1024, maximum));
            VCompCqlSstableMaterializer materializer = new VCompCqlSstableMaterializer(
                shard.resolve("production-materializer-exact-key-control"), schema, insert,
                ks, table, layout, 64, 1024, codec, VALUE_BYTES);
            VCompPipeline.MaterializedState state = materializer.materialize(
                new VCompPipeline.FrozenLayout(Collections.singletonList(run)));
            assertEquals(keys.length, state.materializedKeys());
            Map<String, Object> production = readEvidence(Paths.get(state.sstableIds().get(0)), ks, table);
            lanes.put("production_materializer_exact_key_control", production);
            Map<?, ?> nativeMap = (Map<?, ?>) lanes.get("native");
            for (String lane : Arrays.asList("same_rows_native_header", "same_rows_exact_header"))
            {
                Map<?, ?> other = (Map<?, ?>) lanes.get(lane);
                assertEquals(nativeMap.get("rows_sha256"), other.get("rows_sha256"));
                assertEquals(nativeMap.get("format_version"), other.get("format_version"));
                assertEquals(nativeMap.get("partitions"), other.get("partitions"));
            }
            Map<?, ?> flatMap = (Map<?, ?>) lanes.get("flat_timestamps");
            assertEquals(flatMap.get("rows_sha256"), production.get("rows_sha256"));
            assertEquals(flatMap.get("format_version"), production.get("format_version"));
            assertEquals(flatMap.get("data_db_bytes"), production.get("data_db_bytes"));
            cases.add(object("shard", index, "lanes", lanes));
        }
        Map<String, Object> report = object("diagnostic_only", true, "seed", SEED, "domain", DOMAIN,
            "partitions", PARTITIONS, "attempted_writes", ordinal, "key_bytes", 24, "value_bytes", VALUE_BYTES,
            "storage_compatibility_mode", DatabaseDescriptor.getStorageCompatibilityMode().toString(),
            "table_compaction", cfs.metadata().params.compaction.options(), "forced_shards", SHARDS,
            "inputs", inputEvidence, "cases", cases,
            "scope", "Same native job output partitions/rows supplied to CQL and exact-key production-materializer controls. Forced shard count; no scheduling or PLR/KMV fidelity claim.");
        Files.write(root.resolve("matched-materialization.json"),
                    JsonUtils.JSON_OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(report));
    }

    private EncodingStats stats(long minimum)
    {
        return new EncodingStats(minimum, EncodingStats.NO_STATS.minLocalDeletionTime, EncodingStats.NO_STATS.minTTL);
    }

    private void write(Path directory, String schema, String insert, List<long[]> rows,
                       boolean flatten, long maximum, EncodingStats stats, long partitions) throws Exception
    {
        Files.createDirectories(directory);
        try (CQLSSTableWriter writer = CQLSSTableWriter.builder().inDirectory(directory.toString())
            .forTable(schema).using(insert).withEstimatedPartitionCount(partitions).withEncodingStats(stats).sorted().build())
        {
            for (long[] row : rows)
                writer.addRow(layout.partitionFor(row[0]).key(), codec.decode(row[0]),
                    ByteBuffer.wrap(VCompCqlSstableMaterializer.valueFor(row[0], VALUE_BYTES)), flatten ? maximum : row[1]);
        }
    }

    private List<long[]> rows(SSTableReader reader) throws Exception
    {
        List<long[]> result = new ArrayList<>();
        try (ISSTableScanner scanner = reader.getScanner())
        {
            while (scanner.hasNext())
            {
                try (UnfilteredRowIterator partition = scanner.next())
                {
                    while (partition.hasNext())
                    {
                        Row row = (Row) partition.next();
                        long key = codec.encode(row.clustering());
                        assertEquals(layout.partitionFor(key).key(), ByteBufferUtil.string(partition.partitionKey().getKey()));
                        assertEquals(row.primaryKeyLivenessInfo().timestamp(), row.cells().iterator().next().timestamp());
                        assertEquals(ByteBuffer.wrap(VCompCqlSstableMaterializer.valueFor(key, VALUE_BYTES)),
                                     row.cells().iterator().next().buffer());
                        result.add(new long[] { key, row.primaryKeyLivenessInfo().timestamp() });
                    }
                }
            }
        }
        result.sort(Comparator.comparingLong(row -> row[0]));
        return result;
    }

    private Map<String, Object> readEvidence(Path directory, String ks, String table) throws Exception
    {
        List<Path> data = new ArrayList<>();
        try (Stream<Path> files = Files.walk(directory))
        {
            files.filter(p -> p.toString().endsWith("-Data.db")).forEach(data::add);
        }
        assertEquals("one physical file per controlled native shard", 1, data.size());
        SSTableReader reader = SSTableReader.open(null,
            Descriptor.fromFile(new org.apache.cassandra.io.util.File(data.get(0))), Schema.instance.getTableMetadataRef(ks, table));
        try { return evidence(reader); }
        finally { reader.selfRef().release(); }
    }

    private Map<String, Object> evidence(SSTableReader reader) throws Exception
    {
        List<long[]> rows = rows(reader);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (long[] row : rows) digest.update(ByteBuffer.allocate(16).putLong(row[0]).putLong(row[1]).array());
        return object("rows", rows.size(), "partitions", rows.stream().map(row -> layout.partitionFor(row[0]).key()).distinct().count(),
            "rows_sha256", ByteBufferUtil.bytesToHex(ByteBuffer.wrap(digest.digest())),
            "data_db_bytes", Files.size(Paths.get(reader.getFilename())), "on_disk_length_bytes", reader.onDiskLength(),
            "format_version", reader.descriptor.version.toString(), "encoding_min_timestamp", reader.header.stats().minTimestamp,
            "min_timestamp", reader.getMinTimestamp(), "max_timestamp", reader.getMaxTimestamp(),
            "first_token", reader.getFirst().getToken().toString(), "last_token", reader.getLast().getToken().toString(),
            "bloom_filter_bytes", Files.size(Paths.get(reader.getFilename().replace("Data.db", "Filter.db"))),
            "file", reader.getFilename());
    }

    private static Map<String, Object> object(Object... pairs)
    {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }
}
