/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.cassandra.db.compaction;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.apache.cassandra.db.compaction.vcomp.*;
import org.apache.cassandra.db.compaction.unified.Controller;
import org.apache.cassandra.db.compaction.unified.UnifiedCompactionPicker;
import org.apache.cassandra.db.rows.Row;
import org.apache.cassandra.db.rows.UnfilteredRowIterator;
import org.apache.cassandra.io.sstable.ISSTableScanner;
import org.apache.cassandra.io.sstable.format.SSTableReader;
import org.apache.cassandra.io.sstable.Descriptor;
import org.apache.cassandra.io.sstable.metadata.MetadataType;
import org.apache.cassandra.io.sstable.metadata.StatsMetadata;
import org.apache.cassandra.utils.ByteBufferUtil;
import org.apache.cassandra.utils.JsonUtils;

/** Offline continuation of one saved native case. Never loads a CQLTester subclass or installs SSTs. */
public final class VCompNativeEventReplay
{
    static final long SEED = 20260909L, DOMAIN = 104857600L;
    static final int FLUSH_WRITES = 4096, FLUSHES = 5, PARTITIONS = 10000;
    static final VCompOrderedPartitionLayout LAYOUT = new VCompOrderedPartitionLayout(DOMAIN, PARTITIONS);
    static final DeterministicFixedWidthKeyCodec CODEC = new DeterministicFixedWidthKeyCodec(24);

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception
    {
        if (args.length != 2) throw new IllegalArgumentException("inputRoot outputRoot required");
        Path input = Paths.get(args[0]), output = Paths.get(args[1]);
        if (Files.exists(output)) throw new IllegalArgumentException("fresh outputRoot required: " + output);
        Files.createDirectories(output);
        Map<String, Object> config = read(input.resolve("input-config.json"), Map.class);
        require(number(config, "seed") == SEED && number(config, "domain") == DOMAIN
                && number(config, "partitions") == PARTITIONS && number(config, "flushes") == FLUSHES
                && number(config, "writes_per_flush") == FLUSH_WRITES,
                "saved case differs from fixed authorized fixture");
        List<Map<String, Object>> events = read(input.resolve("native-events.json"), List.class);
        Map<String, Map<String, Object>> files = read(input.resolve("native-sst-metadata.json"), Map.class);
        Map<String, Map<String, Object>> savedVectors = read(input.resolve("native-exact-vectors.json"), Map.class);
        Map<String, TreeMap<Long, Long>> vectors = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> entry : savedVectors.entrySet())
        {
            TreeMap<Long, Long> rows = new TreeMap<>();
            entry.getValue().forEach((k, v) -> rows.put(Long.parseLong(k), ((Number) v).longValue()));
            vectors.put(entry.getKey(), rows);
        }
        verifyInputSequence(events, vectors);
        write(output.resolve("replay-provenance.json"), obj("input_root", input.toAbsolutePath().toString(),
            "diagnostic_only", true, "native_storage_repeated", false,
            "input_sequence_verified_against_saved_flush_vectors", true,
            "scope", "same native case offline continuation; exact saved vectors are test-only picker controls",
            "installation", "no-op installer and verifier; standalone physical SST scan validates deterministic values, partition mapping and liveness; no read workload"));

        String ks = "vcomp_event_replay", table = "kv";
        VCompCqlSstableMaterializer writer = new VCompCqlSstableMaterializer(output.resolve("materialized"),
            "CREATE TABLE " + ks + '.' + table + " (partition_id text, ck blob, value blob, PRIMARY KEY ((partition_id), ck)) WITH compression = {'enabled':'false'}",
            "INSERT INTO " + ks + '.' + table + " (partition_id, ck, value) VALUES (?, ?, ?) USING TIMESTAMP ?",
            ks, table, LAYOUT, 64, 1024, CODEC, 1000);
        VCompSSTSizeModel size = writer.calibrateSizeModel(4096, 8192);
        long predictedFlush = VCompUcsPlanner.roundObservedFlushSize(size.estimate(FLUSH_WRITES));
        Map<String, Map<String, Object>> coverageEvidence = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> entry : files.entrySet())
        {
            Map<String, Object> metadata = entry.getValue();
            Path data = Paths.get((String) metadata.get("data_db_path")).toAbsolutePath().normalize();
            require(data.startsWith(Paths.get("/tmp")) && data.toString().startsWith("/tmp/vcomp-"),
                    "metadata reads restricted to native /tmp/vcomp-* scratch");
            Descriptor descriptor = Descriptor.fromFile(new org.apache.cassandra.io.util.File(data));
            StatsMetadata stats = (StatsMetadata) descriptor.getMetadataSerializer().deserialize(descriptor, MetadataType.STATS);
            double derived = ((Number) metadata.get("coverage")).doubleValue();
            org.apache.cassandra.dht.Token first = new org.apache.cassandra.dht.Murmur3Partitioner.LongToken(Long.parseLong((String) metadata.get("first_token")));
            org.apache.cassandra.dht.Token last = new org.apache.cassandra.dht.Murmur3Partitioner.LongToken(Long.parseLong((String) metadata.get("last_token")));
            double endpointSpan = first.size(last.nextValidToken());
            double span = stats.tokenSpaceCoverage > 0 ? stats.tokenSpaceCoverage : endpointSpan;
            double actual = span >= ShardManager.MINIMUM_TOKEN_COVERAGE ? span : 1.0;
            metadata.put("native_effective_coverage", actual);
            coverageEvidence.put(entry.getKey(), obj("data_db_path", data.toString(),
                "stats_token_space_coverage", Double.isFinite(stats.tokenSpaceCoverage) ? stats.tokenSpaceCoverage : "NaN",
                "layout_endpoint_coverage", derived, "native_effective_coverage", actual,
                "native_full_ring_endpoint_span", endpointSpan,
                "native_coverage_source", stats.tokenSpaceCoverage > 0 ? "Statistics.db tokenSpaceCoverage" : "native Token.size(last.nextValidToken()) full-ring endpoint fallback",
                "layout_coverage_equals_native", Double.compare(derived, actual) == 0));
        }
        write(output.resolve("native-coverage-evidence.json"), coverageEvidence);
        List<Map<String, Object>> probes = new ArrayList<>();
        try (java.util.stream.Stream<Path> paths = Files.walk(output.resolve("materialized/.vcomp-calibration")))
        {
            for (Path data : paths.filter(p -> p.toString().endsWith("-Data.db")).sorted().collect(Collectors.toList()))
                probes.add(obj("data_db_path", data.toString(), "data_db_bytes", Files.size(data)));
        }
        write(output.resolve("calibration-summary.json"), obj("method", "unchanged production calibrateSizeModel",
            "probe_entries", Arrays.asList(4096, 8192), "probes", probes,
            "estimate_4096", size.estimate(4096), "estimate_8192", size.estimate(8192),
            "rounded_flush_bytes", predictedFlush, "plr_error", 8, "kmv_samples", 512, "kmv_ranges", 8));

        List<Map<String, Object>> controls = new ArrayList<>();
        for (Map<String, Object> event : events)
        {
            if (!event.get("kind").equals("native_picker")) continue;
            List<VCompPipeline.VirtualSortedRun> measured = new ArrayList<>(), predicted = new ArrayList<>();
            List<String> candidateIds = new ArrayList<>();
            List<Map<String, Object>> adaptedMetadata = new ArrayList<>();
            for (String id : strings(event.get("strategy_candidates")))
            {
                if (!strings(event.get("eligible")).contains(id)) continue;
                candidateIds.add(id);
                Map<String, Object> metadata = files.get(id);
                long[] keys = vectors.get(id).keySet().stream().mapToLong(Long::longValue).toArray();
                VCompPipeline.FlushBatch b = new VCompPipeline.FlushBatch(id, keys,
                    number(metadata, "on_disk_length"), number(metadata, "max_timestamp"));
                measured.add(named(id, new DefaultFlushVirtualizer().virtualize(b)));
                predicted.add(named(id, new DefaultFlushVirtualizer(8, 512, 8, size).virtualize(b)));
                adaptedMetadata.add(obj("id", id, "measured_data_db_bytes", metadata.get("data_db_bytes"),
                    "measured_on_disk_length_used", metadata.get("on_disk_length"), "predicted_bytes", size.estimate(keys.length),
                    "exact_rows", keys.length, "exact_min_key", keys[0], "exact_max_key", keys[keys.length - 1],
                    "native_max_timestamp", metadata.get("max_timestamp"),
                    "native_effective_coverage", metadata.get("native_effective_coverage"),
                    "layout_token_coverage", LAYOUT.tokenCoverage(keys[0], keys[keys.length - 1])));
            }
            long observed = number(event, "observed_flush_bytes");
            Map<String, Object> measuredControl;
            if (observed == 0)
            {
                require(measured.isEmpty(), "zero observed flush size with nonempty candidates");
                measuredControl = obj("status", "not_applicable", "reason", "native initial empty state has observed flush bytes 0; offline planner requires positive bytes");
            }
            else measuredControl = pick(new VCompUcsPlanner(observed, LAYOUT), measured);
            controls.add(obj("event_seq", event.get("seq"), "view_stable", event.get("view_stable_during_picker"),
                "native_selected", event.get("selected"), "native_level", event.get("selected_level"),
                "observed_flush_bytes", observed, "native_base_density", event.get("base_density"),
                "candidate_metadata", adaptedMetadata, "measured_control", measuredControl,
                "native_metadata_shared_kernel_control", observed == 0 ? measuredControl :
                    nativeMetadataPick(candidateIds, files, observed, ((Number) event.get("base_density")).doubleValue()),
                "predicted_size_same_availability", pick(new VCompUcsPlanner(predictedFlush, LAYOUT), predicted),
                "scope", "same saved strategy candidates intersect captured noncompacting view; measured VComp control uses native onDiskLength, exact bounds/count/max timestamp but layout-derived coverage; predicted-size control also uses layout coverage and changes size/flush calibration only; shared-kernel control uses native effective coverage and captured native base density"));
        }
        write(output.resolve("same-snapshot-controls.json"), controls);

        List<Map<String, Object>> virtualEvents = new ArrayList<>();
        VCompUcsPlanner planner = new VCompUcsPlanner(predictedFlush, LAYOUT);
        DefaultVirtualCompaction compactor = new DefaultVirtualCompaction(512, 8, 64L << 20, size, LAYOUT);
        AtomicInteger flushCount = new AtomicInteger();
        VCompPipeline.VirtualCompactionPlanner observedPlanner = state -> {
            Optional<VCompPipeline.VirtualCompactionPlan> p = planner.pick(state);
            virtualEvents.add(obj("seq", virtualEvents.size(), "kind", "virtual_picker", "flush_number", flushCount.get(),
                "live", state.runs().stream().map(VCompPipeline.VirtualSortedRun::id).collect(Collectors.toList()),
                "in_flight", Collections.emptyList(), "runs", describeRuns(state.runs()),
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
                for (Path data : paths.filter(p -> p.toString().endsWith("-Data.db")).sorted().collect(Collectors.toList()))
                {
                    SSTableReader reader = SSTableReader.open(null,
                        org.apache.cassandra.io.sstable.Descriptor.fromFile(new org.apache.cassandra.io.util.File(data)),
                        org.apache.cassandra.schema.Schema.instance.getTableMetadataRef(ks, table));
                    try
                    {
                        TreeMap<Long, Long> rows = scan(reader);
                        virtualVectors.put(data.toString(), rows);
                        virtualMetadata.put(data.toString(), obj("rows", rows.size(), "data_db_bytes", Files.size(data),
                            "on_disk_length", reader.onDiskLength(), "key_min", rows.firstKey(), "key_max", rows.lastKey(),
                            "min_timestamp", reader.getMinTimestamp(), "max_timestamp", reader.getMaxTimestamp(),
                            "first_token", reader.getFirst().getToken().toString(), "last_token", reader.getLast().getToken().toString(),
                            "coverage", LAYOUT.tokenCoverage(rows.firstKey(), rows.lastKey()),
                            "occupied_partitions", rows.keySet().stream().map(k -> LAYOUT.partitionFor(k).ordinal()).distinct().count()));
                    }
                    finally { reader.selfRef().release(); }
                }
            }
        }
        write(output.resolve("virtual-exact-vectors.json"), virtualVectors);
        write(output.resolve("virtual-sst-metadata.json"), virtualMetadata);
        write(output.resolve("virtual-result.json"), obj("diagnostic_only", true, "predicted_flush_bytes_rounded", predictedFlush,
            "predicted_data_bytes_for_4096", size.estimate(4096), "materialized", result.materialized().sstableIds(),
            "materialized_keys", result.materialized().materializedKeys(), "physical_scan_passed", true,
            "install_and_pipeline_verify_callbacks", "no-op; not installed or workload-tested",
            "production_settings", "error8, KMV512, 8 ranges, production 4096/8192 calibration; no exact-vector production interventions"));
        System.out.println("Saved native-event offline replay: " + output);
    }

    static void verifyInputSequence(List<Map<String, Object>> events, Map<String, TreeMap<Long, Long>> vectors)
    {
        SplittableRandom random = new SplittableRandom(SEED);
        int flush = 0;
        for (Map<String, Object> event : events)
        {
            if (!event.get("kind").equals("flush_registered")) continue;
            TreeMap<Long, Long> expected = new TreeMap<>();
            for (int i = 0; i < FLUSH_WRITES; i++)
                expected.put(random.nextLong(DOMAIN), (long) flush * FLUSH_WRITES + i + 1);
            TreeMap<Long, Long> observed = new TreeMap<>();
            for (String id : strings(event.get("added"))) vectors.get(id).forEach((k,v) -> observed.merge(k,v,Math::max));
            require(expected.equals(observed), "native saved flush sequence differs at " + (flush + 1));
            flush++;
        }
        require(flush == FLUSHES, "unexpected saved flush count");
    }

    static List<Map<String, Object>> describeRuns(List<VCompPipeline.VirtualSortedRun> runs)
    {
        List<Map<String, Object>> result = new ArrayList<>();
        for (VCompPipeline.VirtualSortedRun run : runs)
        {
            List<Map<String, Object>> descriptors = new ArrayList<>();
            for (VCompPipeline.VirtualSSTable sst : run.sstables())
                descriptors.add(obj("id", sst.id(), "key_min", sst.keyMin(), "key_max", sst.keyMax(),
                    "estimated_bytes", sst.estimatedBytes(), "max_timestamp", sst.maximumTimestamp(),
                    "coverage", LAYOUT.tokenCoverage(sst.keyMin(), sst.keyMax())));
            result.add(obj("id", run.id(), "level", run.level(), "sstables", descriptors));
        }
        return result;
    }

    static TreeMap<Long, Long> scan(SSTableReader reader) throws Exception
    {
        TreeMap<Long, Long> result = new TreeMap<>();
        try (ISSTableScanner scanner = reader.getScanner())
        {
            while (scanner.hasNext()) try (UnfilteredRowIterator partition = scanner.next())
            {
                while (partition.hasNext())
                {
                    Row row = (Row) partition.next();
                    long key = CODEC.encode(row.clustering());
                    require(LAYOUT.partitionFor(key).key().equals(ByteBufferUtil.string(partition.partitionKey().getKey())), "partition mismatch");
                    org.apache.cassandra.db.rows.Cell<?> cell = row.cells().iterator().next();
                    require(ByteBuffer.wrap(VCompCqlSstableMaterializer.valueFor(key, 1000)).equals(cell.buffer()), "value mismatch");
                    require(!row.primaryKeyLivenessInfo().isEmpty() && row.primaryKeyLivenessInfo().timestamp() == cell.timestamp(), "liveness mismatch");
                    require(result.put(key, cell.timestamp()) == null, "duplicate key within SST");
                }
            }
        }
        return result;
    }
    static VCompPipeline.VirtualSortedRun named(String id, VCompPipeline.VirtualSortedRun r)
    { return new VCompPipeline.VirtualSortedRun(id, r.level(), r.sstables()); }
    @SuppressWarnings("unchecked")
    static List<String> strings(Object o) { return (List<String>) o; }
    static long number(Map<String, Object> map, String field) { return ((Number) map.get(field)).longValue(); }
    static Map<String, Object> pick(VCompUcsPlanner p, List<VCompPipeline.VirtualSortedRun> runs)
    {
        Optional<VCompPipeline.VirtualCompactionPlan> plan = p.pick(new VCompPipeline.VirtualStateSnapshot(runs));
        List<String> ids = plan.isPresent() ? plan.get().inputs().stream().map(VCompPipeline.VirtualSortedRun::id).sorted().collect(Collectors.toList()) : Collections.emptyList();
        return obj("status", "evaluated", "selected", ids, "level", plan.isPresent() ? plan.get().outputLevel() - 1 : null);
    }
    static Map<String, Object> nativeMetadataPick(List<String> ids, Map<String, Map<String, Object>> files,
                                                 long flushBytes, double baseDensity)
    {
        UnifiedCompactionPicker.CandidateAdapter<String> adapter = new UnifiedCompactionPicker.CandidateAdapter<String>()
        {
            public double density(String id)
            { return number(files.get(id), "on_disk_length") / ((Number) files.get(id).get("native_effective_coverage")).doubleValue(); }
            long token(String id, String field) { return Long.parseLong((String) files.get(id).get(field)); }
            public int compareFirst(String a, String b) { return Long.compare(token(a, "first_token"), token(b, "first_token")); }
            public int compareLast(String a, String b) { return Long.compare(token(a, "last_token"), token(b, "last_token")); }
            public boolean startsAfter(String a, String b) { return token(a, "first_token") > token(b, "last_token"); }
            public long maximumTimestamp(String id) { return number(files.get(id), "max_timestamp"); }
        };
        Controller nativePolicy = Controller.forOfflineTools(flushBytes, new int[]{2}, Integer.MAX_VALUE, 1, 1L << 30, 0.333);
        UnifiedCompactionPicker.Pick<String> pick = UnifiedCompactionPicker.pick(ids, adapter, nativePolicy.pickerPolicy(), baseDensity);
        List<String> selected = pick == null ? Collections.emptyList() : pick.inputs();
        Collections.sort(selected);
        return obj("status", "evaluated", "selected", selected, "level", pick == null ? null : pick.level(),
            "scope", "shared production picker kernel; saved native bytes/max timestamp/first-last tokens, Statistics.db effective coverage, captured native base density; T4/baseShard1/target1GiB native defaults; no new native execution");
    }
    static Map<String, Object> obj(Object... pairs)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], pairs[i + 1]);
        return m;
    }
    static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    static <T> T read(Path p, Class<T> type) throws Exception
    { return JsonUtils.JSON_OBJECT_MAPPER.readValue(Files.readAllBytes(p), type); }
    static void write(Path p, Object value) throws Exception
    { Files.write(p, JsonUtils.JSON_OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(value)); }
}
