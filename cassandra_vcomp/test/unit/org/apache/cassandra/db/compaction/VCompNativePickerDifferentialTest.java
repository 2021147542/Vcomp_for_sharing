/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.cassandra.db.compaction;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.Test;

import org.apache.cassandra.Util;
import org.apache.cassandra.cql3.CQLTester;
import org.apache.cassandra.db.ColumnFamilyStore;
import org.apache.cassandra.db.compaction.unified.Controller;
import org.apache.cassandra.db.compaction.vcomp.VCompKmvSketch;
import org.apache.cassandra.db.compaction.vcomp.VCompLearnedModel;
import org.apache.cassandra.db.compaction.vcomp.VCompOrderedPartitionLayout;
import org.apache.cassandra.db.compaction.vcomp.VCompPipeline;
import org.apache.cassandra.db.compaction.vcomp.VCompUcsPlanner;
import org.apache.cassandra.db.lifecycle.LifecycleTransaction;
import org.apache.cassandra.io.sstable.format.SSTableReader;
import org.apache.cassandra.utils.JsonUtils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * A bounded event oracle: native UCS reads real flushed SSTables; the production virtual planner
 * receives the same measured descriptors and availability. No production exact-key mode is added.
 * This isolates picker adaptation, not size prediction, reconciliation, or concurrent scheduling.
 */
public class VCompNativePickerDifferentialTest extends CQLTester
{
    private static final long SEED = 20260909L;
    private static final int ROWS = 2048;
    private static final int VALUE_BYTES = 256;

    @Test
    public void compareNativeFlushSnapshotsAndReservationEvents() throws Throwable
    {
        createTable("CREATE TABLE %s (p text, k bigint, v blob, PRIMARY KEY (p, k)) " +
                    "WITH compression = {'enabled':'false'} AND compaction = " +
                    "{'class':'UnifiedCompactionStrategy', 'scaling_parameters':'T4', 'enabled':'false'}");
        ColumnFamilyStore cfs = getCurrentColumnFamilyStore();
        cfs.disableAutoCompaction();
        Map<String, String> options = new HashMap<>();
        options.put("scaling_parameters", "T4");
        options.put("base_shard_count", "1");
        options.put("target_sstable_size", "64MiB");
        options.put("max_sstables_to_compact", Integer.toString(Integer.MAX_VALUE));
        UnifiedCompactionStrategy nativeStrategy = new UnifiedCompactionStrategy(cfs, options);
        VCompOrderedPartitionLayout layout = new VCompOrderedPartitionLayout(ROWS, 16);
        Map<SSTableReader, String> identifiers = new LinkedHashMap<>();
        List<Map<String, Object>> events = new ArrayList<>();
        Random random = new Random(SEED);
        long[] keys = new long[ROWS];
        for (int row = 0; row < ROWS; row++)
            keys[row] = row;

        for (int flush = 1; flush <= 5; flush++)
        {
            for (long key : keys)
            {
                byte[] value = new byte[VALUE_BYTES];
                random.nextBytes(value);
                execute("INSERT INTO %s (p,k,v) VALUES (?,?,?) USING TIMESTAMP ?",
                        layout.partitionFor(key).key(), key, ByteBuffer.wrap(value), 1000000L + flush);
            }
            Util.flush(cfs);
            List<SSTableReader> added = cfs.getLiveSSTables().stream()
                                         .filter(s -> !identifiers.containsKey(s)).collect(Collectors.toList());
            assertEquals("one real SST per controlled flush", 1, added.size());
            SSTableReader reader = added.get(0);
            identifiers.put(reader, "flush-" + flush);
            nativeStrategy.addSSTable(reader);
            events.add(snapshot("flush-" + flush, cfs, nativeStrategy, layout, identifiers, keys));

            if (flush == 4)
            {
                SSTableReader held = identifiers.keySet().iterator().next();
                try (LifecycleTransaction transaction = cfs.getTracker().tryModify(held, OperationType.COMPACTION))
                {
                    assertNotNull(transaction);
                    Map<String, Object> reserved = snapshot("reserve-flush-1", cfs, nativeStrategy,
                                                           layout, identifiers, keys);
                    events.add(reserved);
                    assertEquals(Collections.emptyList(), reserved.get("native_input_ids"));
                    assertEquals(4, ((List<?>) reserved.get("unfiltered_virtual_input_ids")).size());
                    assertEquals("eligibility: input reserved in native tracker", reserved.get("unfiltered_first_difference"));
                }
                events.add(snapshot("release-flush-1", cfs, nativeStrategy, layout, identifiers, keys));
            }
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schema_version", 1);
        report.put("diagnostic_only", true);
        report.put("schema", "vcomp-native-picker-differential-v1");
        report.put("seed", SEED);
        report.put("rows_per_flush", ROWS);
        report.put("flushes", 5);
        report.put("oracle", "UnifiedCompactionStrategy.getNextCompactionPick on real CQL-flushed SSTables");
        report.put("virtual", "production VCompUcsPlanner with measured Data.db bytes and matched availability");
        report.put("scope", "controlled event snapshots; no compaction execution or 100GiB causal claim");
        report.put("production_estimated_metadata_tested", false);
        List<Map<String, Object>> jobs = new ArrayList<>();
        List<Map<String, Object>> controls = new ArrayList<>();
        for (Map<String, Object> event : events)
        {
            Map<String, Object> nativeObservation = new LinkedHashMap<>();
            nativeObservation.put("input_ids", event.get("native_input_ids"));
            nativeObservation.put("level", event.get("native_level"));
            nativeObservation.put("eligible_ids", event.get("eligible_ids"));
            Map<String, Object> virtualObservation = new LinkedHashMap<>();
            virtualObservation.put("input_ids", event.get("virtual_input_ids"));
            virtualObservation.put("level", event.get("virtual_level"));
            virtualObservation.put("eligible_ids", event.get("eligible_ids"));
            Map<String, Object> stage = new LinkedHashMap<>();
            stage.put("status", "checked");
            stage.put("native", nativeObservation);
            stage.put("exact", virtualObservation);
            Map<String, Object> job = new LinkedHashMap<>();
            job.put("job_id", event.get("event"));
            job.put("stages", Collections.singletonMap("picker", stage));
            Map<String, Object> control = new LinkedHashMap<>();
            control.put("job_id", event.get("event"));
            control.put("injected_test_condition", "virtual snapshot deliberately retains tracker-reserved SSTables");
            control.put("native_input_ids", event.get("native_input_ids"));
            control.put("virtual_input_ids", event.remove("unfiltered_virtual_input_ids"));
            control.put("first_difference", event.remove("unfiltered_first_difference"));
            controls.add(control);
            job.put("measured_metadata_diagnostics", event);
            jobs.add(job);
        }
        report.put("jobs", jobs);
        report.put("negative_controls", controls);
        report.put("first_difference", events.stream().filter(e -> e.get("first_difference") != null)
                                             .findFirst().orElse(null));
        System.out.println("VCOMP_NATIVE_PICKER_DIFFERENTIAL=" + JsonUtils.writeAsJsonString(report));
        String output = System.getProperty("vcomp.picker.differential.output");
        if (output != null)
            java.nio.file.Files.write(java.nio.file.Paths.get(output), JsonUtils.writeAsJsonBytes(report));
        for (Map<String, Object> event : events)
            assertEquals("matched snapshot " + event.get("event"), event.get("native_input_ids"), event.get("virtual_input_ids"));
    }

    private static Map<String, Object> snapshot(String event, ColumnFamilyStore cfs,
                                                UnifiedCompactionStrategy strategy,
                                                VCompOrderedPartitionLayout layout,
                                                Map<SSTableReader, String> identifiers, long[] keys)
    {
        Controller controller = strategy.getController();
        long observedFlushBytes = controller.getFlushSizeBytes();
        assertTrue(observedFlushBytes > 0);
        VCompUcsPlanner virtual = new VCompUcsPlanner(observedFlushBytes, layout);
        List<VCompPipeline.VirtualSortedRun> availableRuns = new ArrayList<>();
        List<VCompPipeline.VirtualSortedRun> allRuns = new ArrayList<>();
        List<Map<String, Object>> metadata = new ArrayList<>();
        Set<SSTableReader> compacting = cfs.getTracker().getCompacting();
        String firstDifference = null;
        for (Map.Entry<SSTableReader, String> entry : identifiers.entrySet())
        {
            SSTableReader reader = entry.getKey();
            String id = entry.getValue();
            assertEquals(ByteBuffer.wrap(layout.partitionFor(0).encodedKey()), reader.getFirst().getKey());
            assertEquals(ByteBuffer.wrap(layout.partitionFor(ROWS - 1).encodedKey()), reader.getLast().getKey());
            VCompPipeline.VirtualSSTable sstable = new VCompPipeline.VirtualSSTable(id, 0, ROWS - 1,
                    ROWS, reader.onDiskLength(), reader.getMaxTimestamp(), VCompLearnedModel.greedyFit(keys, 0),
                    VCompKmvSketch.build(keys, 64), Collections.emptyList());
            VCompPipeline.VirtualSortedRun run = new VCompPipeline.VirtualSortedRun(id, 0, Collections.singletonList(sstable));
            boolean eligible = !compacting.contains(reader) && UnifiedCompactionStrategy.isSuitableForCompaction(reader);
            if (eligible)
                availableRuns.add(run);
            allRuns.add(run);
            double nativeDensity = strategy.getShardManager().density(reader);
            double virtualDensity = reader.onDiskLength() / layout.tokenCoverage(0, ROWS - 1);
            boolean densityMatches = Math.abs(nativeDensity - virtualDensity) <= Math.abs(nativeDensity) * 1e-12;
            if (!densityMatches && firstDifference == null)
                firstDifference = "density:" + id;
            Map<String, Object> descriptor = new LinkedHashMap<>();
            descriptor.put("id", id);
            descriptor.put("sstable", reader.getFilename());
            descriptor.put("data_db_bytes", reader.onDiskLength());
            descriptor.put("first_token", reader.getFirst().getToken().toString());
            descriptor.put("last_token", reader.getLast().getToken().toString());
            descriptor.put("key_min", 0);
            descriptor.put("key_max", ROWS - 1);
            descriptor.put("maximum_timestamp", reader.getMaxTimestamp());
            descriptor.put("native_eligible", eligible);
            descriptor.put("virtual_eligible", eligible);
            descriptor.put("in_progress", compacting.contains(reader));
            descriptor.put("native_token_coverage", strategy.getShardManager().rangeSpanned(reader));
            descriptor.put("virtual_token_coverage", layout.tokenCoverage(0, ROWS - 1));
            descriptor.put("native_density", nativeDensity);
            descriptor.put("virtual_density", virtualDensity);
            descriptor.put("density_matches_relative_1e_12", densityMatches);
            metadata.add(descriptor);
        }
        UnifiedCompactionStrategy.CompactionPick nativePick = strategy.getNextCompactionPick(0);
        Optional<VCompPipeline.VirtualCompactionPlan> virtualPick = virtual.pick(new VCompPipeline.VirtualStateSnapshot(availableRuns));
        List<String> nativeIds = nativePick == null ? Collections.emptyList()
                                                   : nativePick.stream().map(identifiers::get).collect(Collectors.toList());
        List<String> virtualIds = ids(virtualPick);
        List<String> unfilteredIds = ids(virtual.pick(new VCompPipeline.VirtualStateSnapshot(allRuns)));
        Integer nativeLevel = nativePick == null ? null : nativePick.level;
        Integer virtualLevel = virtualPick.isPresent() ? virtualPick.get().outputLevel() - 1 : null;
        if (firstDifference == null && !nativeIds.equals(virtualIds))
            firstDifference = "selected ordered input_ids";
        if (firstDifference == null && !java.util.Objects.equals(nativeLevel, virtualLevel))
            firstDifference = "selected density level";
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("event", event);
        output.put("metadata_lane", "observed native Data.db sizes; original scalar bounds; native max timestamps");
        output.put("observed_rounded_flush_bytes", observedFlushBytes);
        output.put("native_local_space_coverage", strategy.getShardManager().localSpaceCoverage());
        output.put("native_base_density", controller.getBaseSstableSize(controller.getFanout(0)) /
                                           strategy.getShardManager().localSpaceCoverage());
        output.put("virtual_base_density", Math.max(1L << 20, observedFlushBytes) * (1.0 - 0.9 / 4));
        output.put("threshold", controller.getThreshold(0));
        output.put("fanout", controller.getFanout(0));
        output.put("candidates_in_flush_order", metadata);
        output.put("eligible_ids", availableRuns.stream().map(VCompPipeline.VirtualSortedRun::id).collect(Collectors.toList()));
        output.put("native_input_ids", nativeIds);
        output.put("virtual_input_ids", virtualIds);
        output.put("native_level", nativeLevel);
        output.put("virtual_level", virtualLevel);
        output.put("first_difference", firstDifference);
        output.put("unfiltered_virtual_input_ids", unfilteredIds);
        output.put("unfiltered_first_difference", nativeIds.equals(unfilteredIds) ? null
                                                  : "eligibility: input reserved in native tracker");
        return output;
    }

    private static List<String> ids(Optional<VCompPipeline.VirtualCompactionPlan> pick)
    {
        return pick.isPresent() ? pick.get().inputs().stream().map(VCompPipeline.VirtualSortedRun::id).collect(Collectors.toList())
                                : Collections.emptyList();
    }
}
