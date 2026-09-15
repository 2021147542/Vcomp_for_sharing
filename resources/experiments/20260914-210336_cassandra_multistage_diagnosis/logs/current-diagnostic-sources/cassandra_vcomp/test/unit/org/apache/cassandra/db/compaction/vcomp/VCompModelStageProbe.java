/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.cassandra.db.compaction.vcomp;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.apache.cassandra.utils.JsonUtils;

/** CPU-only first-job interventions. Exact sets and enlarged sketches are diagnostic only. */
public final class VCompModelStageProbe
{
    private VCompModelStageProbe() {}

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception
    {
        Map<String, Object> trace = JsonUtils.JSON_OBJECT_MAPPER.readValue(Files.readAllBytes(Paths.get(args[0])), Map.class);
        Map<String, Object> job = ((List<Map<String, Object>>) trace.get("jobs")).get(0);
        List<Map<String, Object>> inputs = (List<Map<String, Object>>) job.get("inputs");
        List<long[]> keySets = new ArrayList<>();
        TreeSet<Long> union = new TreeSet<>();
        for (Map<String, Object> input : inputs)
        {
            List<List<Object>> rows = (List<List<Object>>) input.get("rows");
            long[] keys = rows.stream().mapToLong(r -> ((Number) r.get(1)).longValue()).sorted().toArray();
            keySets.add(keys);
            for (long key : keys) union.add(key);
        }
        long[] unionKeys = union.stream().mapToLong(Long::longValue).toArray();
        List<Map<String, Object>> lanes = new ArrayList<>();
        for (int samples : new int[] { 512, 4096 })
        {
            for (double error : new double[] { 8, 0 })
            {
                List<VCompPipeline.VirtualSortedRun> runs = new ArrayList<>();
                for (int i = 0; i < keySets.size(); i++)
                    runs.add(new DefaultFlushVirtualizer(error, samples, 8).virtualize(
                        new VCompPipeline.FlushBatch("input-" + i, keySets.get(i), keySets.get(i).length * 67L, 1)));
                DefaultVirtualCompaction compactor = new DefaultVirtualCompaction(samples, 8);
                VCompPipeline.VirtualCompactionPlan plan = new VCompPipeline.VirtualCompactionPlan("first-job", runs, 1);
                VCompKmvSketch merged = compactor.mergeAndDeduplicate(plan);
                VCompKmvSketch direct = VCompKmvSketch.build(unionKeys, samples);
                boolean sameSketch = merged.thetaHash() == direct.thetaHash()
                                     && merged.isComplete() == direct.isComplete()
                                     && Arrays.equals(merged.samples().stream().mapToLong(VCompKmvSketch.Sample::key).toArray(),
                                                      direct.samples().stream().mapToLong(VCompKmvSketch.Sample::key).toArray());
                if (!sameSketch) throw new AssertionError("Merged bottom-K differs from direct exact-union bottom-K");
                long estimate = compactor.estimateUniqueKeys(plan, merged);
                VCompLearnedModel model = compactor.merge(plan, estimate);
                VCompPipeline.VirtualSortedRun output = compactor.split(plan, model, merged, estimate);
                TreeSet<Long> emitted = new TreeSet<>();
                for (VCompPipeline.VirtualSSTable sst : output.sstables())
                {
                    VCompMaterializedKeyIterator iterator = new VCompMaterializedKeyIterator(sst);
                    while (iterator.hasNext()) emitted.add(iterator.nextLong());
                }
                TreeSet<Long> missing = new TreeSet<>(union);
                missing.removeAll(emitted);
                TreeSet<Long> extra = new TreeSet<>(emitted);
                extra.removeAll(union);
                Map<String, Object> lane = new LinkedHashMap<>();
                lane.put("samples", samples);
                lane.put("plr_error", error);
                lane.put("diagnostic_intervention", samples != 512 || error != 8);
                lane.put("merged_sketch_equals_direct_exact_union_sketch", sameSketch);
                lane.put("kmv_complete", merged.isComplete());
                lane.put("estimated_count", estimate);
                lane.put("emitted_count", emitted.size());
                lane.put("missing_keys", missing.size());
                lane.put("extra_keys", extra.size());
                lane.put("segments", model.segments().size());
                List<VCompPipeline.VirtualSSTable> descriptors = new ArrayList<>();
                for (VCompPipeline.VirtualSortedRun run : runs) descriptors.addAll(run.sstables());
                List<Map<String, Object>> intervals = new ArrayList<>();
                TreeSet<Long> cuts = new TreeSet<>();
                for (VCompPipeline.VirtualSSTable sst : descriptors)
                    for (VCompLearnedModel.Segment segment : sst.model().segments())
                    { cuts.add(segment.keyStart()); cuts.add(segment.keyEnd()); }
                List<Long> bounds = new ArrayList<>(cuts);
                long exactInclusiveTotal = 0;
                long estimatedInclusiveTotal = 0;
                for (int i = 0; i + 1 < bounds.size(); i++)
                {
                    long low = bounds.get(i), high = bounds.get(i + 1);
                    long exactCount = union.subSet(low, true, high, true).size();
                    long predicted = compactor.estimateUnionForRange(descriptors, low, high);
                    Map<String, Object> interval = new LinkedHashMap<>();
                    interval.put("low", low); interval.put("high", high);
                    interval.put("exact_inclusive_union", exactCount); interval.put("range_estimate", predicted);
                    intervals.add(interval);
                    exactInclusiveTotal += exactCount;
                    estimatedInclusiveTotal += predicted;
                }
                lane.put("range_probe_scope", "all input descriptors on inclusive adjacent breakpoints; not a copy of merge active-segment filtering");
                lane.put("exact_inclusive_mass", exactInclusiveTotal);
                lane.put("estimated_inclusive_mass", estimatedInclusiveTotal);
                lane.put("intervals", intervals);
                lanes.add(lane);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("diagnostic_only", true);
        result.put("native_union_count", union.size());
        result.put("lanes", lanes);
        Files.write(Paths.get(args[1]), JsonUtils.JSON_OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(result));
        System.out.println("Model-stage probe complete: " + args[1]);
    }
}
