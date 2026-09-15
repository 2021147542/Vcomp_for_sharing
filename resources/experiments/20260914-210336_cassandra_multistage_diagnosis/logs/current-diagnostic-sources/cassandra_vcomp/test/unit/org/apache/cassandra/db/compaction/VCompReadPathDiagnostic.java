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

import com.codahale.metrics.Snapshot;
import com.codahale.metrics.Histogram;
import com.codahale.metrics.UniformReservoir;
import org.apache.cassandra.SchemaLoader;
import org.apache.cassandra.cache.ChunkCache;
import org.apache.cassandra.cql3.statements.schema.CreateTableStatement;
import org.apache.cassandra.db.Clustering;
import org.apache.cassandra.db.ColumnFamilyStore;
import org.apache.cassandra.db.Keyspace;
import org.apache.cassandra.db.ReadExecutionController;
import org.apache.cassandra.db.SinglePartitionReadCommand;
import org.apache.cassandra.db.compaction.vcomp.VCompCqlSstableMaterializer;
import org.apache.cassandra.db.compaction.vcomp.VCompKeyCodec;
import org.apache.cassandra.db.compaction.vcomp.VCompOrderedPartitionLayout;
import org.apache.cassandra.db.filter.ClusteringIndexNamesFilter;
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
import org.apache.cassandra.metrics.DecayingEstimatedHistogramReservoir;
import org.apache.cassandra.schema.KeyspaceParams;
import org.apache.cassandra.schema.Schema;
import org.apache.cassandra.schema.TableMetadata;
import org.apache.cassandra.service.CacheService;
import org.apache.cassandra.utils.ByteBufferUtil;
import org.apache.cassandra.utils.FBUtilities;
import org.apache.cassandra.utils.JsonUtils;
import org.apache.cassandra.utils.btree.BTreeSet;

/** Test-only real local read commands over preserved native and materialized VComp SSTs. */
public final class VCompReadPathDiagnostic
{
    private VCompReadPathDiagnostic() {}
    private static final java.util.concurrent.atomic.AtomicInteger RUN_IDS = new java.util.concurrent.atomic.AtomicInteger();

    public static void run(ColumnFamilyStore nativeCfs, List<String> virtualDataPaths,
                           VCompOrderedPartitionLayout layout, VCompKeyCodec codec,
                           long domain, Path output) throws Exception
    {
        if (!nativeCfs.getTracker().getCompacting().isEmpty())
            throw new IllegalStateException("Read diagnostic requires drained native compaction");
        String ks = "vcomp_diagnostic_read_" + RUN_IDS.incrementAndGet(), table = "kv";
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
        virtualCfs.addSSTables(readers);
        if (readers.isEmpty()) throw new IllegalArgumentException("No virtual SSTs");
        int count = Integer.getInteger("vcomp.read.requests", 10000);
        long[] keys = requests(domain, count);
        write(output.resolve("actual-read-requests.json"), obj("seed", 20260909L, "domain", domain,
            "workload", "C", "generator", "CassandraPaperWorkload actual reflection, workerRandoms(seed,1); original per-op draws",
            "key_selection", "Unconditioned generated requests; no key filtering or seed selection", "keys", keys));
        List<Map<String, Object>> phases = new ArrayList<>();
        // Fixed warmup and fixed ABBA order, retained in full. No page-cache dropping or selected fastest pass.
        phases.add(phase(nativeCfs, "native", "warmup", keys, layout, codec, null, output));
        phases.add(phase(virtualCfs, "vcomp", "warmup", keys, layout, codec, null, output));
        boolean[][] found = new boolean[2][];
        for (int round = 0; round < 4; round++)
        {
            boolean nativeFirst = round == 0 || round == 3;
            for (int side = 0; side < 2; side++)
            {
                boolean baseline = side == 0 ? nativeFirst : !nativeFirst;
                boolean[] hits = new boolean[count];
                phases.add(phase(baseline ? nativeCfs : virtualCfs, baseline ? "native" : "vcomp",
                    "measured_" + (round + 1), keys, layout, codec, hits, output));
                int index = baseline ? 0 : 1;
                if (found[index] != null && !Arrays.equals(found[index], hits))
                    throw new AssertionError("Read result changed between passes");
                found[index] = hits;
            }
        }
        long both = 0, nativeOnly = 0, virtualOnly = 0, neither = 0;
        for (int i = 0; i < count; i++)
            if (found[0][i] && found[1][i]) both++;
            else if (found[0][i]) nativeOnly++;
            else if (found[1][i]) virtualOnly++;
            else neither++;
        write(output.resolve("actual-read-summary.json"), obj("schema_version", 1, "diagnostic_only", true,
            "read_api", "SinglePartitionReadCommand.executeLocally; exact clustering name; value fully consumed and verified",
            "scope", "Actual engine local read path; one thread, cache policy reported separately, no CQL transport or contention; bounded read-only diagnostic, not full workload campaign",
            "cache_eviction_requested", Boolean.getBoolean("vcomp.read.evict_data_cache"),
            "eviction_scope", "Optional before each phase: invalidate Cassandra chunk-cache entries and best-effort POSIX_FADV_DONTNEED on this side fixture/checkpoint Data.db files only; index/key cache unchanged, no global cache drop",
            "cache_policy", "Existing cache configuration; equal fixed warmup; four fixed rounds with native-first,VComp-first,VComp-first,native-first; all retained",
            "instrumentation", "Test-only histogram delegate observes per-request merged SST count while retaining original metric updates; same instrumentation both sides",
            "io_limits", "SST histogram counts merged SST iterators, not physical disk operations. Bloom is partition-level. Chunk counters are process-global deltas. Linux process read_bytes delta is attributed storage reads under warm OS cache and can be zero; rchar includes logical syscall bytes and instrumentation. Physical I/O latency is not measured.",
            "native_sstables", nativeCfs.getLiveSSTables().size(), "vcomp_sstables", virtualCfs.getLiveSSTables().size(),
            "native_caching", nativeCfs.metadata().params.caching.toString(), "vcomp_caching", virtualCfs.metadata().params.caching.toString(),
            "requests_per_phase", count, "both_hit", both, "native_only_hit", nativeOnly,
            "vcomp_only_hit", virtualOnly, "both_miss", neither, "phases", phases));
    }

    private static Map<String, Object> phase(ColumnFamilyStore cfs, String side, String name, long[] keys,
        VCompOrderedPartitionLayout layout, VCompKeyCodec codec, boolean[] hits, Path output) throws Exception
    {
        List<String> evictionPaths = evictDataCache(cfs);
        ((ClearableHistogram) cfs.metric.sstablesPerReadHistogram.cf).clear();
        long[] before = counters(cfs);
        Map<String, Long> ioBefore = processIo();
        long[] nanos = new long[keys.length];
        long[] sstIterators = new long[keys.length];
        Histogram originalHistogram = cfs.metric.sstablesPerReadHistogram.all[0];
        ObservingHistogram observer = new ObservingHistogram(originalHistogram);
        cfs.metric.sstablesPerReadHistogram.all[0] = observer;
        long[] timestamps = new long[keys.length];
        Arrays.fill(timestamps, Long.MIN_VALUE);
        long found = 0;
        long begin = System.nanoTime();
        try
        {
        for (int i = 0; i < keys.length; i++)
        {
            long key = keys[i];
            BTreeSet.Builder<Clustering<?>> names = BTreeSet.builder(cfs.metadata().comparator);
            names.add(Clustering.make(codec.decode(key)));
            SinglePartitionReadCommand command = SinglePartitionReadCommand.create(cfs.metadata(), FBUtilities.nowInSeconds(),
                ColumnFilter.all(cfs.metadata()), RowFilter.none(), DataLimits.NONE,
                cfs.metadata().partitioner.decorateKey(ByteBufferUtil.bytes(layout.partitionFor(key).key())),
                new ClusteringIndexNamesFilter(names.build(), false));
            long updatesBefore = observer.updates;
            long start = System.nanoTime();
            int rows = 0;
            ByteBuffer readValue = null;
            try (ReadExecutionController controller = command.executionController();
                 UnfilteredPartitionIterator partitions = command.executeLocally(controller))
            {
                while (partitions.hasNext()) try (UnfilteredRowIterator partition = partitions.next())
                {
                    while (partition.hasNext())
                    {
                        Object item = partition.next();
                        if (!(item instanceof Row)) throw new AssertionError("Unexpected tombstone");
                        Row row = (Row) item;
                        if (codec.encode(row.clustering()) != key) throw new AssertionError("Wrong clustering key");
                        if (!row.cells().iterator().hasNext()) continue;
                        ByteBuffer value = row.cells().iterator().next().buffer();
                        readValue = value;
                        // Validate after timing below; value bytes must be materialized during the command.
                        if (value.remaining() != 1000) throw new AssertionError("Wrong value size");
                        timestamps[i] = row.cells().iterator().next().timestamp();
                        rows++;
                    }
                }
            }
            nanos[i] = System.nanoTime() - start;
            if (observer.updates - updatesBefore != 1) throw new AssertionError("Expected one SST histogram observation per read");
            sstIterators[i] = observer.last;
            if (rows > 1) throw new AssertionError("Point read returned multiple rows");
            if (readValue != null && !readValue.equals(ByteBuffer.wrap(VCompCqlSstableMaterializer.valueFor(key, 1000))))
                throw new AssertionError("Read value differs from deterministic input");
            if (rows == 1) found++;
            if (hits != null) hits[i] = rows == 1;
        }
        }
        finally
        {
            cfs.metric.sstablesPerReadHistogram.all[0] = originalHistogram;
        }
        long elapsed = System.nanoTime() - begin;
        long[] after = counters(cfs);
        Map<String, Long> ioAfter = processIo();
        Map<String, Long> ioDelta = new LinkedHashMap<>();
        for (String key : ioBefore.keySet()) ioDelta.put(key, ioAfter.get(key) - ioBefore.get(key));
        long[] delta = new long[before.length];
        for (int i = 0; i < delta.length; i++) delta[i] = after[i] - before[i];
        Snapshot snapshot = cfs.metric.sstablesPerReadHistogram.cf.getSnapshot();
        Map<String, Object> result = obj("side", side, "phase", name, "requests", keys.length,
            "hits", found, "misses", keys.length - found, "elapsed_ns_including_command_setup", elapsed,
            "ops_per_second_including_setup", keys.length * 1e9 / elapsed,
            "command_latency_ns", quantiles(nanos), "sst_histogram_count", cfs.metric.sstablesPerReadHistogram.cf.getCount(),
            "sst_histogram_bucket_upper_bounds", DecayingEstimatedHistogramReservoir.DEFAULT_WITH_ZERO_BUCKET_OFFSETS,
            "sst_histogram_bucket_counts", snapshot.getValues(), "sst_histogram_decaying_mean", snapshot.getMean(),
            "counter_names", Arrays.asList("bloom_true_positive", "bloom_false_positive", "bloom_true_negative", "key_cache_requests", "key_cache_hits", "chunk_cache_requests", "chunk_cache_hits", "chunk_cache_misses"),
            "counter_deltas", delta, "linux_process_io_deltas", ioDelta, "best_effort_data_cache_eviction_paths", evictionPaths);
        write(output.resolve("actual-read-" + side + '-' + name + ".json"), obj("summary", result, "latency_ns", nanos, "sst_merged_iterators", sstIterators, "timestamps", timestamps));
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

    private static List<String> evictDataCache(ColumnFamilyStore cfs) throws Exception
    {
        List<String> paths = new ArrayList<>();
        if (!Boolean.getBoolean("vcomp.read.evict_data_cache")) return paths;
        for (SSTableReader reader : cfs.getLiveSSTables())
        {
            Path path = Path.of(reader.getFilename()).toRealPath();
            boolean scratch = path.getNameCount() >= 3 && path.getName(0).toString().equals("tmp")
                              && path.getName(1).toString().startsWith("vcomp-");
            boolean checkpoint = false;
            String configuredRoot = System.getProperty("vcomp.diagnostic.checkpoint_root");
            if (configuredRoot != null)
            {
                Path root = Path.of(configuredRoot).toRealPath();
                if (!root.getFileName().toString().startsWith("vcomp-cassandra-read-diagnosis-"))
                    throw new IllegalArgumentException("Expected explicitly prepared diagnostic checkpoint root: " + root);
                checkpoint = path.startsWith(root) && !path.equals(root);
            }
            if ((!scratch && !checkpoint) || !path.toString().endsWith("-Data.db"))
                throw new IllegalArgumentException("Cache eviction requires isolated /tmp/vcomp-* data or an explicitly prepared diagnostic checkpoint root: " + path);
            paths.add(path.toString());
        }
        for (String path : paths)
        {
            if (ChunkCache.instance != null) ChunkCache.instance.invalidateFile(path);
            org.apache.cassandra.utils.NativeLibrary.trySkipCache(path, 0L, 0L);
        }
        return paths;
    }

    private static long[] requests(long domain, int count) throws Exception
    {
        if (domain <= 0 || count <= 0) throw new IllegalArgumentException("Positive domain/count required");
        Class<?> source = Class.forName("CassandraPaperWorkload");
        SplittableRandom random = ((SplittableRandom[]) method(source, "workerRandoms", long.class, int.class).invoke(null, 20260909L, 1))[0];
        SplittableRandom values = random.split();
        method(source, "valuePool", SplittableRandom.class).invoke(null, values);
        Class<?> zipfClass = Class.forName("CassandraPaperWorkload$Zipf");
        Constructor<?> constructor = zipfClass.getDeclaredConstructor(long.class, long.class, double.class);
        constructor.setAccessible(true);
        Object zipf = constructor.newInstance(1L, constant(source, "DEFAULT_ZIPF_MAX"), constant(source, "DEFAULT_ZETA"));
        Method next = method(zipfClass, "next", SplittableRandom.class), hash = method(source, "fnv", long.class);
        long[] result = new long[count];
        for (int i = 0; i < count; i++)
        {
            random.nextInt(100);
            values.nextLong();
            result[i] = Long.remainderUnsigned((long) hash.invoke(null, (long) next.invoke(zipf, random)), domain);
        }
        return result;
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
