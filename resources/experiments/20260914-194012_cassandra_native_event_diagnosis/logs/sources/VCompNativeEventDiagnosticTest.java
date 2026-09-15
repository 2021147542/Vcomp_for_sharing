/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.cassandra.db.compaction;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.junit.After;
import org.junit.Test;
import org.apache.cassandra.cql3.CQLTester;
import org.apache.cassandra.db.ColumnFamilyStore;
import org.apache.cassandra.db.compaction.unified.UnifiedCompactionTask;
import org.apache.cassandra.db.compaction.vcomp.*;
import org.apache.cassandra.db.lifecycle.View;
import org.apache.cassandra.db.lifecycle.SSTableSet;
import org.apache.cassandra.db.rows.Row;
import org.apache.cassandra.db.rows.UnfilteredRowIterator;
import org.apache.cassandra.io.sstable.ISSTableScanner;
import org.apache.cassandra.io.sstable.format.SSTableReader;
import org.apache.cassandra.notifications.*;
import org.apache.cassandra.utils.JsonUtils;
import org.apache.cassandra.utils.ByteBufferUtil;
import org.apache.cassandra.utils.concurrent.Ref;
import static org.junit.Assert.*;

/** One bounded actual-background fixture; all hooks and exact vectors are test-only. */
public class VCompNativeEventDiagnosticTest extends CQLTester
{
    static final long SEED = 20260909L, DOMAIN = 104857600L;
    static final int FLUSH_WRITES = 4096, FLUSHES = 5, PARTITIONS = 10000;
    static final VCompOrderedPartitionLayout LAYOUT = new VCompOrderedPartitionLayout(DOMAIN, PARTITIONS);
    static final DeterministicFixedWidthKeyCodec CODEC = new DeterministicFixedWidthKeyCodec(24);
    static final Recorder TRACE = new Recorder();
    static final AtomicInteger COMPLETED = new AtomicInteger();
    static volatile ColumnFamilyStore target;

    // CQLTester otherwise recursively deletes its SST directories even with retained refs.
    @After
    @Override
    public void afterTest() { /* Fresh isolated JVM scratch intentionally retained. */ }

    public static class ObservedUCS extends UnifiedCompactionStrategy
    {
        public ObservedUCS(ColumnFamilyStore cfs, Map<String, String> options) { super(cfs, options); }

        @Override
        CompactionPick getNextCompactionPick(long gcBefore)
        {
            View before = cfs.getTracker().getView();
            List<SSTableReader> strategyCandidates = new ArrayList<>(getSSTables());
            CompactionPick pick = super.getNextCompactionPick(gcBefore);
            if (cfs == target)
            {
                Map<String, Object> event = TRACE.event("native_picker", cfs, before,
                    pick == null ? Collections.emptyList() : pick, Collections.emptyList());
                event.put("strategy_candidates", TRACE.ids(strategyCandidates));
                event.put("view_stable_during_picker", before == cfs.getTracker().getView());
                event.put("selected_level", pick == null ? null : pick.level);
                event.put("observed_flush_bytes", getController().getFlushSizeBytes());
                event.put("base_density", getController().getBaseSstableSize(getController().getFanout(0)) / getShardManager().localSpaceCoverage());
            }
            return pick;
        }

        @Override
        public synchronized UnifiedCompactionTask getNextBackgroundTask(long gcBefore)
        {
            UnifiedCompactionTask original = super.getNextBackgroundTask(gcBefore);
            if (original == null || cfs != target) return original;
            List<SSTableReader> inputs = new ArrayList<>(original.transaction.originals());
            TRACE.event("job_reserved", cfs, cfs.getTracker().getView(), inputs, Collections.emptyList());
            // Identical native task constructor and transaction; only observe execute's return.
            return new UnifiedCompactionTask(cfs, this, original.transaction, gcBefore, getShardManager())
            {
                @Override
                public int execute(ActiveCompactionsTracker active)
                {
                    boolean ok = false;
                    try { int result = super.execute(active); ok = true; return result; }
                    finally
                    {
                        Map<String, Object> e = TRACE.event("job_complete", cfs, cfs.getTracker().getView(), inputs, Collections.emptyList());
                        e.put("success", ok);
                        COMPLETED.incrementAndGet();
                    }
                }
            };
        }
    }

    static class Recorder implements INotificationConsumer
    {
        final List<Map<String, Object>> events = new ArrayList<>();
        final Map<String, SSTableReader> readers = new LinkedHashMap<>();
        final List<Ref<SSTableReader>> retained = new ArrayList<>();
        synchronized List<String> ids(Iterable<SSTableReader> input)
        {
            List<String> result = new ArrayList<>();
            for (SSTableReader r : input)
            {
                String id = r.descriptor.toString();
                if (!readers.containsKey(id))
                {
                    Ref<SSTableReader> ref = r.tryRef();
                    if (ref == null) throw new IllegalStateException("could not retain " + id);
                    retained.add(ref);
                    readers.put(id, r);
                }
                result.add(id);
            }
            Collections.sort(result);
            return result;
        }
        synchronized Map<String, Object> event(String kind, ColumnFamilyStore cfs, View view,
                                               Iterable<SSTableReader> selected, Iterable<SSTableReader> added)
        {
            List<String> live = ids(view.liveSSTables());
            List<String> eligible = ids(view.select(SSTableSet.NONCOMPACTING));
            List<String> compactingLive = new ArrayList<>(live);
            compactingLive.removeAll(eligible);
            Map<String, Object> e = obj("seq", events.size(), "nano_time", System.nanoTime(), "kind", kind,
                "thread", Thread.currentThread().getName(), "live", live, "eligible", eligible,
                "in_flight_live", compactingLive, "tracker_compacting_separate_read", ids(cfs.getTracker().getCompacting()),
                "selected", ids(selected), "added", ids(added));
            events.add(e);
            return e;
        }
        @Override
        public void handleNotification(INotification n, Object sender)
        {
            if (n instanceof SSTableAddedNotification)
            {
                SSTableAddedNotification a = (SSTableAddedNotification) n;
                event(a.memtable().isPresent() ? "flush_registered" : "sst_added", target,
                      target.getTracker().getView(), Collections.emptyList(), a.added);
            }
            if (n instanceof SSTableListChangedNotification)
            {
                SSTableListChangedNotification c = (SSTableListChangedNotification) n;
                event("job_commit_visible", target, target.getTracker().getView(), c.removed, c.added);
            }
        }
    }

    @Test
    public void observeRealNativeBackgroundAndReplayVirtualLoop() throws Throwable
    {
        Path output = Paths.get(System.getProperty("vcomp.event.output"));
        Files.createDirectories(output);
        createTable("CREATE TABLE %s (partition_id text, ck blob, value blob, PRIMARY KEY ((partition_id), ck))"
            + " WITH compression = {'enabled':'false'} AND compaction = {'class':'"
            + ObservedUCS.class.getName() + "','scaling_parameters':'T4','base_shard_count':'1','sstable_growth':'0.333'}");
        target = getCurrentColumnFamilyStore();
        target.getTracker().subscribe(TRACE);
        target.enableAutoCompaction();
        SplittableRandom random = new SplittableRandom(SEED);
        TreeMap<Long, Long> expected = new TreeMap<>();
        List<long[]> rawKeys = new ArrayList<>();
        for (int f = 0; f < FLUSHES; f++)
        {
            long[] keys = new long[FLUSH_WRITES];
            for (int i = 0; i < FLUSH_WRITES; i++)
            {
                long key = random.nextLong(DOMAIN);
                long timestamp = (long) f * FLUSH_WRITES + i + 1;
                keys[i] = key;
                expected.put(key, timestamp);
                execute("INSERT INTO %s (partition_id, ck, value) VALUES (?, ?, ?) USING TIMESTAMP ?",
                    LAYOUT.partitionFor(key).key(), CODEC.decode(key),
                    ByteBuffer.wrap(VCompCqlSstableMaterializer.valueFor(key, 1000)), timestamp);
            }
            rawKeys.add(Arrays.stream(keys).sorted().distinct().toArray());
            target.forceBlockingFlush(ColumnFamilyStore.FlushReason.UNIT_TESTS);
            TRACE.event("flush_return", target, target.getTracker().getView(), Collections.emptyList(), Collections.emptyList())
                 .put("flush_number", f + 1);
        }
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
        while (COMPLETED.get() == 0 && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue("actual native background job did not complete", COMPLETED.get() > 0);
        target.disableAutoCompaction();
        assertTrue("no native work remains in flight", target.getTracker().getCompacting().isEmpty());
        TRACE.event("native_drained", target, target.getTracker().getView(), Collections.emptyList(), Collections.emptyList());
        target.getTracker().unsubscribe(TRACE);
        // Event JSON is persisted before expensive post-run analysis.
        write(output.resolve("native-events.json"), TRACE.events);
        Map<String, Map<String, Object>> files = new LinkedHashMap<>();
        Map<String, TreeMap<Long, Long>> vectors = new LinkedHashMap<>();
        for (Map.Entry<String, SSTableReader> entry : TRACE.readers.entrySet())
        {
            SSTableReader r = entry.getValue();
            TreeMap<Long, Long> rows = scan(r);
            vectors.put(entry.getKey(), rows);
            Map<String, Object> m = obj("data_db_path", r.getFilename(), "data_db_bytes", Files.size(Paths.get(r.getFilename())),
                "on_disk_length", r.onDiskLength(), "key_min", rows.firstKey(), "key_max", rows.lastKey(),
                "min_timestamp", r.getMinTimestamp(), "max_timestamp", r.getMaxTimestamp(),
                "first_token", r.getFirst().getToken().toString(), "last_token", r.getLast().getToken().toString(),
                "coverage", LAYOUT.tokenCoverage(rows.firstKey(), rows.lastKey()), "rows", rows.size(),
                "occupied_partitions", rows.keySet().stream().map(k -> LAYOUT.partitionFor(k).ordinal()).distinct().count());
            files.put(entry.getKey(), m);
        }
        write(output.resolve("native-sst-metadata.json"), files);
        write(output.resolve("native-exact-vectors.json"), vectors);
        TreeMap<Long, Long> finalRows = new TreeMap<>();
        for (SSTableReader r : target.getLiveSSTables())
            vectors.get(r.descriptor.toString()).forEach((k,v) -> finalRows.merge(k,v,Math::max));
        assertEquals("native final exact key/version reconciliation", expected, finalRows);
        write(output.resolve("input-config.json"), obj("seed", SEED, "domain", DOMAIN, "partitions", PARTITIONS,
            "flushes", FLUSHES, "writes_per_flush", FLUSH_WRITES, "logical_inserted_bytes", FLUSHES * FLUSH_WRITES * 1024L,
            "key_bytes", 24, "value_bytes", 1000, "actual_native_background", true,
            "load_api", "CQLTester internal CQL synchronous inserts; native background CompactionManager; explicit forceBlockingFlush",
            "deviation", "4096 vs canonical65536 writes/flush; synchronous internal CQL vs driver async; no throttle overrides",
            "exact_native_final_rows", finalRows.size(), "completed_jobs", COMPLETED.get()));

        String ks = "vcomp_event_materialized", table = "kv";
        VCompCqlSstableMaterializer writer = new VCompCqlSstableMaterializer(output.resolve("materialized"),
            "CREATE TABLE " + ks + '.' + table + " (partition_id text, ck blob, value blob, PRIMARY KEY ((partition_id), ck)) WITH compression = {'enabled':'false'}",
            "INSERT INTO " + ks + '.' + table + " (partition_id, ck, value) VALUES (?, ?, ?) USING TIMESTAMP ?",
            ks, table, LAYOUT, 64, 1024, CODEC, 1000);
        VCompSSTSizeModel size = writer.calibrateSizeModel(4096, 8192);
        long predictedFlush = VCompUcsPlanner.roundObservedFlushSize(size.estimate(FLUSH_WRITES));
        List<Map<String, Object>> controls = new ArrayList<>();
        for (Map<String, Object> event : TRACE.events)
        {
            if (!event.get("kind").equals("native_picker")) continue;
            List<VCompPipeline.VirtualSortedRun> measured = new ArrayList<>(), predicted = new ArrayList<>();
            for (String id : strings(event.get("strategy_candidates")))
            {
                if (!strings(event.get("eligible")).contains(id)) continue;
                SSTableReader reader = TRACE.readers.get(id);
                long[] keys = vectors.get(id).keySet().stream().mapToLong(Long::longValue).toArray();
                VCompPipeline.FlushBatch b = new VCompPipeline.FlushBatch(id, keys, reader.onDiskLength(), reader.getMaxTimestamp());
                measured.add(named(id, new DefaultFlushVirtualizer().virtualize(b)));
                predicted.add(named(id, new DefaultFlushVirtualizer(8, 512, 8, size).virtualize(b)));
            }
            long observed = ((Number) event.get("observed_flush_bytes")).longValue();
            if (observed <= 0)
            {
                assertTrue("no flush size is only admissible before candidates exist", measured.isEmpty());
                controls.add(obj("event_seq", event.get("seq"), "status", "not_applicable_before_first_flush"));
                continue;
            }
            controls.add(obj("event_seq", event.get("seq"), "view_stable", event.get("view_stable_during_picker"),
                "native_selected", event.get("selected"), "native_level", event.get("selected_level"),
                "measured_control", pick(new VCompUcsPlanner(observed, LAYOUT), measured),
                "predicted_size_same_availability", pick(new VCompUcsPlanner(predictedFlush, LAYOUT), predicted),
                "scope", "predicted-size intervention retains exact native bounds/count/max timestamp; not full production metadata"));
        }
        write(output.resolve("same-snapshot-controls.json"), controls);
        List<Map<String, Object>> virtualEvents = new ArrayList<>();
        VCompUcsPlanner planner = new VCompUcsPlanner(predictedFlush, LAYOUT);
        DefaultVirtualCompaction compactor = new DefaultVirtualCompaction(512, 8, 64L << 20, size, LAYOUT);
        AtomicInteger flushCount = new AtomicInteger();
        VCompPipeline.VirtualCompactionPlanner observedPlanner = state -> {
            Optional<VCompPipeline.VirtualCompactionPlan> p = planner.pick(state);
            virtualEvents.add(obj("flush_number", flushCount.get(), "live", state.runs().stream().map(VCompPipeline.VirtualSortedRun::id).collect(Collectors.toList()),
                "selected", p.isPresent() ? p.get().inputs().stream().map(VCompPipeline.VirtualSortedRun::id).collect(Collectors.toList()) : Collections.emptyList(),
                "output_level", p.isPresent() ? p.get().outputLevel() : null));
            return p;
        };
        DefaultFlushVirtualizer vf = new DefaultFlushVirtualizer(8, 512, 8, size);
        VCompPipeline pipeline = new VCompPipeline(b -> { flushCount.incrementAndGet(); return vf.virtualize(b); },
            observedPlanner, compactor, compactor, compactor, compactor,
            s -> new VCompPipeline.FrozenLayout(s.runs()), writer, s -> {}, (f,s) -> {});
        VCompPipeline.Result result = pipeline.execute(new VCompPipeline.Request(
            VCompPipeline.ExecutionConstraints.orderedPartitionKeyValue(PARTITIONS),
            new SyntheticVCompLoadSource((long) FLUSHES * FLUSH_WRITES, FLUSH_WRITES, DOMAIN, 1024, SEED)));
        write(output.resolve("virtual-events.json"), virtualEvents);
        Map<String, TreeMap<Long, Long>> virtualVectors = new LinkedHashMap<>();
        Map<String, Object> virtualMetadata = new LinkedHashMap<>();
        for (String directory : result.materialized().sstableIds())
        {
            try (java.util.stream.Stream<Path> paths = Files.walk(Paths.get(directory)))
            {
                for (Path data : paths.filter(p -> p.toString().endsWith("-Data.db")).collect(Collectors.toList()))
                {
                    SSTableReader reader = SSTableReader.open(null,
                        org.apache.cassandra.io.sstable.Descriptor.fromFile(new org.apache.cassandra.io.util.File(data)),
                        org.apache.cassandra.schema.Schema.instance.getTableMetadataRef(ks, table));
                    TreeMap<Long, Long> rows = scan(reader);
                    virtualVectors.put(data.toString(), rows);
                    virtualMetadata.put(data.toString(), obj("rows", rows.size(), "data_db_bytes", Files.size(data),
                        "occupied_partitions", rows.keySet().stream().map(k -> LAYOUT.partitionFor(k).ordinal()).distinct().count()));
                    reader.selfRef().release();
                }
            }
        }
        write(output.resolve("virtual-exact-vectors.json"), virtualVectors);
        write(output.resolve("virtual-sst-metadata.json"), virtualMetadata);
        write(output.resolve("virtual-result.json"), obj("predicted_flush_bytes_rounded", predictedFlush,
            "predicted_data_bytes_for_4096", size.estimate(4096), "materialized", result.materialized().sstableIds()));
    }

    static TreeMap<Long, Long> scan(SSTableReader reader) throws Exception
    {
        TreeMap<Long, Long> result = new TreeMap<>();
        try (ISSTableScanner scanner = reader.getScanner())
        {
            while (scanner.hasNext()) try (UnfilteredRowIterator p = scanner.next())
            {
                while (p.hasNext())
                {
                    Row row = (Row) p.next();
                    long key = CODEC.encode(row.clustering());
                    assertEquals(LAYOUT.partitionFor(key).key(), ByteBufferUtil.string(p.partitionKey().getKey()));
                    org.apache.cassandra.db.rows.Cell<?> cell = row.cells().iterator().next();
                    assertEquals(ByteBuffer.wrap(VCompCqlSstableMaterializer.valueFor(key, 1000)), cell.buffer());
                    assertEquals(row.primaryKeyLivenessInfo().timestamp(), cell.timestamp());
                    assertNull(result.put(key, cell.timestamp()));
                }
            }
        }
        return result;
    }
    static VCompPipeline.VirtualSortedRun named(String id, VCompPipeline.VirtualSortedRun r)
    { return new VCompPipeline.VirtualSortedRun(id, r.level(), r.sstables()); }
    @SuppressWarnings("unchecked")
    static List<String> strings(Object o) { return (List<String>) o; }
    static Map<String, Object> pick(VCompUcsPlanner p, List<VCompPipeline.VirtualSortedRun> runs)
    {
        Optional<VCompPipeline.VirtualCompactionPlan> plan = p.pick(new VCompPipeline.VirtualStateSnapshot(runs));
        List<String> ids = plan.isPresent() ? plan.get().inputs().stream().map(VCompPipeline.VirtualSortedRun::id).sorted().collect(Collectors.toList()) : Collections.emptyList();
        return obj("selected", ids, "level", plan.isPresent() ? plan.get().outputLevel() - 1 : null);
    }
    static Map<String, Object> obj(Object... pairs)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i=0; i<pairs.length; i+=2) m.put((String)pairs[i], pairs[i+1]);
        return m;
    }
    static void write(Path p, Object value) throws Exception
    { Files.write(p, JsonUtils.JSON_OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(value)); }
}
