/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.cassandra.db.compaction;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicInteger;
import com.codahale.metrics.Histogram;
import com.codahale.metrics.UniformReservoir;
import org.apache.cassandra.SchemaLoader;
import org.apache.cassandra.cache.ChunkCache;
import org.apache.cassandra.cql3.statements.schema.CreateTableStatement;
import org.apache.cassandra.db.BufferClusteringBound;
import org.apache.cassandra.db.ColumnFamilyStore;
import org.apache.cassandra.db.Keyspace;
import org.apache.cassandra.db.ReadExecutionController;
import org.apache.cassandra.db.SinglePartitionReadCommand;
import org.apache.cassandra.db.Slice;
import org.apache.cassandra.db.Slices;
import org.apache.cassandra.db.compaction.vcomp.VCompCqlSstableMaterializer;
import org.apache.cassandra.db.compaction.vcomp.VCompKeyCodec;
import org.apache.cassandra.db.compaction.vcomp.VCompOrderedPartitionLayout;
import org.apache.cassandra.db.filter.ClusteringIndexSliceFilter;
import org.apache.cassandra.db.filter.ColumnFilter;
import org.apache.cassandra.db.filter.DataLimits;
import org.apache.cassandra.db.filter.RowFilter;
import org.apache.cassandra.db.partitions.UnfilteredPartitionIterator;
import org.apache.cassandra.db.rows.Row;
import org.apache.cassandra.db.rows.UnfilteredRowIterator;
import org.apache.cassandra.io.sstable.Descriptor;
import org.apache.cassandra.io.sstable.format.SSTableReader;
import org.apache.cassandra.io.sstable.format.SSTableReaderWithFilter;
import org.apache.cassandra.metrics.ClearableHistogram;
import org.apache.cassandra.schema.KeyspaceParams;
import org.apache.cassandra.schema.Schema;
import org.apache.cassandra.schema.TableMetadata;
import org.apache.cassandra.service.CacheService;
import org.apache.cassandra.utils.ByteBufferUtil;
import org.apache.cassandra.utils.FBUtilities;
import org.apache.cassandra.utils.JsonUtils;

/** Actual local E scan read component on frozen states; never executes the E writes. */
public final class VCompScanPathDiagnostic
{
    private static final AtomicInteger IDS = new AtomicInteger();
    private VCompScanPathDiagnostic() {}

    public static void run(ColumnFamilyStore nativeCfs, List<String> virtualDataPaths,
                           VCompOrderedPartitionLayout layout, VCompKeyCodec codec,
                           long domain, Path output) throws Exception
    {
        Files.createDirectories(output);
        nativeCfs.disableAutoCompaction();
        if (!nativeCfs.getTracker().getCompacting().isEmpty()) throw new IllegalStateException("Compaction active");
        String ks = "vcomp_diagnostic_scan_" + IDS.incrementAndGet(), table = "kv";
        TableMetadata metadata = CreateTableStatement.parse("CREATE TABLE " + ks + '.' + table
            + " (partition_id text, ck blob, value blob, PRIMARY KEY ((partition_id),ck))"
            + " WITH compression={'enabled':'false'} AND caching={'keys':'ALL','rows_per_partition':'NONE'}", ks).build();
        SchemaLoader.createKeyspace(ks, KeyspaceParams.simple(1), metadata);
        ColumnFamilyStore virtualCfs = Keyspace.open(ks).getColumnFamilyStore(table);
        virtualCfs.disableAutoCompaction();
        List<SSTableReader> readers = new ArrayList<>();
        for (String path : virtualDataPaths)
            readers.add(SSTableReader.open(null, Descriptor.fromFile(new org.apache.cassandra.io.util.File(path)),
                Schema.instance.getTableMetadataRef(ks, table)));
        if (readers.isEmpty()) throw new IllegalArgumentException("No VComp SSTs");
        virtualCfs.addSSTables(readers);
        long[][] requests = requests(domain, Integer.getInteger("vcomp.scan.operations", 1000));
        write(output.resolve("actual-scan-requests.json"), obj("seed", 20260909L, "domain", domain,
            "workload", "E read-only frozen-state diagnostic", "request_format", "[generated scrambled-Zipf key, scan length; -1 length denotes skipped INSERT]",
            "generation", "Actual CassandraPaperWorkload workerRandoms, valuePool, Zipf, fnv, ycsbScanLength by reflection; original per-op draws, fixed seed and all generated operations retained",
            "scope", "95% scan decisions execute; 5% INSERT decisions recorded and skipped symmetrically. Not full E write evolution or CQL transport.", "operations", requests));
        List<Map<String, Object>> phases = new ArrayList<>();
        phases.add(phase(nativeCfs, "native", "warmup", requests, layout, codec, output));
        phases.add(phase(virtualCfs, "vcomp", "warmup", requests, layout, codec, output));
        for (int round = 1; round <= 4; round++)
        {
            boolean nativeFirst = round == 1 || round == 4;
            for (int side = 0; side < 2; side++)
            {
                boolean baseline = side == 0 ? nativeFirst : !nativeFirst;
                phases.add(phase(baseline ? nativeCfs : virtualCfs, baseline ? "native" : "vcomp",
                    "measured_" + round, requests, layout, codec, output));
            }
        }
        write(output.resolve("actual-scan-summary.json"), obj("diagnostic_only", true, "schema_version", 1,
            "native_sstables", nativeCfs.getLiveSSTables().size(), "vcomp_sstables", virtualCfs.getLiveSSTables().size(),
            "phases", phases, "cache_eviction_requested", Boolean.getBoolean("vcomp.scan.evict_data_cache"),
            "eviction_scope", "Optional before each phase: invalidate Cassandra chunk-cache entries and best-effort POSIX_FADV_DONTNEED on this side fixture Data.db files only. Index/key cache unchanged, no global cache drop; /tmp filesystem is not canonical /work device.",
            "measurement_scope", "Actual local SinglePartitionReadCommand slices and CQL row limits, same next-partition fallback as workload; cache policy reported separately by cache_eviction_requested/eviction_scope; single thread, fixed warmup and all four ABBA rounds; 5% writes omitted from frozen states",
            "io_limits", "Per-scan SST counts are summed actual merged iterators over all partition commands, not distinct SSTs or physical I/O. Process read_bytes under warm OS can be zero. rchar includes logical syscall bytes and instrumentation. Physical I/O latency not measured.",
            "integrity", "Rows checked in ascending scalar order and correct partition; all values read and deterministic values checked. Row-key hashes and per-scan first/last keys retained; exact row lists not production algorithm."));
    }

    private static Map<String, Object> phase(ColumnFamilyStore cfs, String side, String phase,
        long[][] requests, VCompOrderedPartitionLayout layout, VCompKeyCodec codec, Path output) throws Exception
    {
        List<String> evictionPaths = evictDataCache(cfs);
        ((ClearableHistogram) cfs.metric.sstablesPerReadHistogram.cf).clear();
        long[] before = counters(cfs);
        Map<String, Long> ioBefore = processIo();
        Histogram previous = cfs.metric.sstablesPerReadHistogram.all[0];
        ObservingHistogram observer = new ObservingHistogram(previous);
        cfs.metric.sstablesPerReadHistogram.all[0] = observer;
        List<Map<String, Object>> vectors = new ArrayList<>();
        List<Long> latencies = new ArrayList<>();
        long totalRows = 0, totalPartitions = 0, totalSsts = 0, skipped = 0;
        long begin = System.nanoTime();
        try
        {
            for (int operation = 0; operation < requests.length; operation++)
            {
                long key = requests[operation][0];
                int limit = (int) requests[operation][1];
                if (limit < 0) { skipped++; continue; }
                int remaining = limit, partitionsRead = 0;
                long ssts = 0, previousKey = key - 1, first = -1, last = -1, hash = 1;
                int firstPartition = layout.partitionFor(key).ordinal();
                long start = System.nanoTime();
                for (int p = firstPartition; p < layout.partitionCount() && remaining > 0; p++)
                {
                    VCompOrderedPartitionLayout.Partition partition = layout.partitions().get(p);
                    long lower = p == firstPartition ? key : partition.minimum();
                    Slices slices = Slices.with(cfs.metadata().comparator,
                        Slice.make(BufferClusteringBound.inclusiveStartOf(codec.decode(lower)), BufferClusteringBound.TOP));
                    SinglePartitionReadCommand command = SinglePartitionReadCommand.create(cfs.metadata(), FBUtilities.nowInSeconds(),
                        ColumnFilter.all(cfs.metadata()), RowFilter.none(), DataLimits.cqlLimits(remaining),
                        cfs.metadata().partitioner.decorateKey(ByteBufferUtil.bytes(partition.key())),
                        new ClusteringIndexSliceFilter(slices, false));
                    long updates = observer.updates;
                    try (ReadExecutionController controller = command.executionController();
                         UnfilteredPartitionIterator result = command.executeLocally(controller))
                    {
                        while (result.hasNext()) try (UnfilteredRowIterator rows = result.next())
                        {
                            while (rows.hasNext())
                            {
                                Object item = rows.next();
                                if (!(item instanceof Row)) throw new AssertionError("Unexpected tombstone");
                                Row row = (Row) item;
                                long actualKey = codec.encode(row.clustering());
                                if (actualKey <= previousKey || layout.partitionFor(actualKey).ordinal() != p)
                                    throw new AssertionError("Wrong scan ordering/partition");
                                ByteBuffer value = row.cells().iterator().next().buffer();
                                if (!value.equals(ByteBuffer.wrap(VCompCqlSstableMaterializer.valueFor(actualKey, 1000))))
                                    throw new AssertionError("Wrong scan value");
                                if (first < 0) first = actualKey;
                                previousKey = last = actualKey;
                                hash = hash * 31 + actualKey;
                                remaining--;
                                if (remaining < 0) throw new AssertionError("CQL row limit exceeded");
                            }
                        }
                    }
                    if (observer.updates - updates != 1) throw new AssertionError("Expected one SST observation per partition command");
                    ssts += observer.last;
                    partitionsRead++;
                }
                long nanos = System.nanoTime() - start;
                latencies.add(nanos);
                int rows = limit - remaining;
                totalRows += rows;
                totalPartitions += partitionsRead;
                totalSsts += ssts;
                vectors.add(obj("operation", operation, "rows", rows, "partition_commands", partitionsRead,
                    "sst_merged_iterators", ssts, "latency_ns", nanos, "first_key", first, "last_key", last, "row_keys_hash31", hash));
            }
        }
        finally { cfs.metric.sstablesPerReadHistogram.all[0] = previous; }
        long elapsed = System.nanoTime() - begin;
        long[] after = counters(cfs), delta = new long[after.length];
        for (int i = 0; i < delta.length; i++) delta[i] = after[i] - before[i];
        Map<String, Long> ioAfter = processIo(), ioDelta = new LinkedHashMap<>();
        for (String key : ioBefore.keySet()) ioDelta.put(key, ioAfter.get(key) - ioBefore.get(key));
        Map<String, Object> summary = obj("side", side, "phase", phase, "scans", latencies.size(),
            "skipped_insert_decisions", skipped, "returned_rows", totalRows, "returned_logical_bytes", totalRows * 1024L,
            "partition_commands", totalPartitions, "sst_merged_iterators", totalSsts, "elapsed_ns", elapsed,
            "latency_ns_including_value_validation", quantiles(latencies.stream().mapToLong(Long::longValue).toArray()),
            "counter_names", Arrays.asList("bloom_true_positive", "bloom_false_positive", "bloom_true_negative", "key_cache_requests", "key_cache_hits", "chunk_cache_requests", "chunk_cache_hits", "chunk_cache_misses"),
            "counter_deltas", delta, "linux_process_io_deltas", ioDelta, "best_effort_data_cache_eviction_paths", evictionPaths);
        write(output.resolve("actual-scan-" + side + '-' + phase + ".json"), obj("summary", summary, "scans", vectors));
        return summary;
    }

    private static List<String> evictDataCache(ColumnFamilyStore cfs) throws Exception
    {
        List<String> paths = new ArrayList<>();
        if (!Boolean.getBoolean("vcomp.scan.evict_data_cache")) return paths;
        for (SSTableReader reader : cfs.getLiveSSTables())
        {
            Path path = Path.of(reader.getFilename()).toRealPath();
            if (path.getNameCount() < 3 || !path.getName(0).toString().equals("tmp")
                || !path.getName(1).toString().startsWith("vcomp-") || !path.toString().endsWith("-Data.db"))
                throw new IllegalArgumentException("Cache eviction is restricted to isolated /tmp/vcomp-* fixture Data.db files: " + path);
            paths.add(path.toString());
        }
        for (String path : paths)
        {
            if (ChunkCache.instance != null) ChunkCache.instance.invalidateFile(path);
            org.apache.cassandra.utils.NativeLibrary.trySkipCache(path, 0L, 0L);
        }
        return paths;
    }

    private static long[][] requests(long domain, int operations) throws Exception
    {
        Class<?> source = Class.forName("CassandraPaperWorkload");
        SplittableRandom random = ((SplittableRandom[]) method(source, "workerRandoms", long.class, int.class).invoke(null, 20260909L, 1))[0];
        SplittableRandom values = random.split();
        method(source, "valuePool", SplittableRandom.class).invoke(null, values);
        Class<?> zipfClass = Class.forName("CassandraPaperWorkload$Zipf");
        Constructor<?> constructor = zipfClass.getDeclaredConstructor(long.class, long.class, double.class);
        constructor.setAccessible(true);
        Object zipf = constructor.newInstance(1L, constant(source, "DEFAULT_ZIPF_MAX"), constant(source, "DEFAULT_ZETA"));
        Method next = method(zipfClass, "next", SplittableRandom.class), fnv = method(source, "fnv", long.class),
               length = method(source, "ycsbScanLength", SplittableRandom.class);
        long[][] result = new long[operations][2];
        for (int i = 0; i < operations; i++)
        {
            int choice = random.nextInt(100);
            values.nextLong();
            result[i][0] = Long.remainderUnsigned((long) fnv.invoke(null, (long) next.invoke(zipf, random)), domain);
            result[i][1] = choice < 95 ? (int) length.invoke(null, random) : -1;
        }
        return result;
    }
    /** Test-only observer delegates unchanged histogram updates and records the current read's count. */
    private static final class ObservingHistogram extends Histogram
    {
        private final Histogram delegate;
        private final Thread owner = Thread.currentThread();
        private long updates, last;
        private ObservingHistogram(Histogram delegate)
        { super(new UniformReservoir()); this.delegate = delegate; }
        @Override public void update(long value)
        {
            delegate.update(value);
            if (Thread.currentThread() == owner) { updates++; last = value; }
        }
    }

    private static Map<String, Long> processIo() throws Exception
    {
        Map<String, Long> values = new LinkedHashMap<>();
        for (String line : Files.readAllLines(Path.of("/proc/self/io")))
        {
            String[] fields = line.split(":");
            if (fields[0].equals("read_bytes") || fields[0].equals("rchar") || fields[0].equals("syscr"))
                values.put(fields[0], Long.parseLong(fields[1].trim()));
        }
        return values;
    }

    private static long[] counters(ColumnFamilyStore cfs)
    {
        long tp = 0, fp = 0, tn = 0;
        for (SSTableReader reader : cfs.getLiveSSTables()) if (reader instanceof SSTableReaderWithFilter)
        {
            SSTableReaderWithFilter filtered = (SSTableReaderWithFilter) reader;
            tp += filtered.getFilterTracker().getTruePositiveCount();
            fp += filtered.getFilterTracker().getFalsePositiveCount();
            tn += filtered.getFilterTracker().getTrueNegativeCount();
        }
        return new long[] { tp, fp, tn, CacheService.instance.keyCache.getMetrics().requests.getCount(),
            CacheService.instance.keyCache.getMetrics().hits.getCount(),
            ChunkCache.instance == null ? 0 : ChunkCache.instance.metrics.requests.getCount(),
            ChunkCache.instance == null ? 0 : ChunkCache.instance.metrics.hits.getCount(),
            ChunkCache.instance == null ? 0 : ChunkCache.instance.metrics.misses.getCount() };
    }

    private static Map<String, Object> quantiles(long[] samples)
    {
        long[] sorted = samples.clone();
        Arrays.sort(sorted);
        return obj("p50", sorted[(int) Math.ceil(sorted.length * .50) - 1],
            "p95", sorted[(int) Math.ceil(sorted.length * .95) - 1],
            "p99", sorted[(int) Math.ceil(sorted.length * .99) - 1],
            "mean", Arrays.stream(sorted).average().orElse(0));
    }

    private static Method method(Class<?> owner, String name, Class<?>... types) throws Exception
    { Method m = owner.getDeclaredMethod(name, types); m.setAccessible(true); return m; }
    private static Object constant(Class<?> owner, String name) throws Exception
    { Field f = owner.getDeclaredField(name); f.setAccessible(true); return f.get(null); }
    private static Map<String, Object> obj(Object... pairs)
    { Map<String, Object> m = new LinkedHashMap<>(); for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], pairs[i + 1]); return m; }
    private static void write(Path path, Object value) throws Exception
    { Files.writeString(path, JsonUtils.JSON_OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value)); }
}
