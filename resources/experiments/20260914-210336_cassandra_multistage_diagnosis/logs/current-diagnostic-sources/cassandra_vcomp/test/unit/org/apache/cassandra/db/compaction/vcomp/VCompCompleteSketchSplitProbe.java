/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.cassandra.db.compaction.vcomp;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TreeSet;

import org.apache.cassandra.db.compaction.unified.Controller;
import org.apache.cassandra.utils.JsonUtils;

/** CPU-only guard diagnostic: original-key sketch count versus modeled shard count. */
public final class VCompCompleteSketchSplitProbe
{
    private VCompCompleteSketchSplitProbe() {}

    public static void main(String[] args) throws Exception
    {
        List<long[]> batches = new ArrayList<>();
        TreeSet<Long> union = new TreeSet<>();
        SplittableRandom random = new SplittableRandom(20260909);
        for (int batch = 0; batch < 4; batch++)
        {
            TreeSet<Long> keys = new TreeSet<>();
            for (int write = 0; write < 128; write++) keys.add(random.nextLong(4096));
            union.addAll(keys);
            batches.add(keys.stream().mapToLong(Long::longValue).toArray());
        }
        List<Map<String, Object>> results = new ArrayList<>();
        // All cases declared before execution. The large metadata entry is a
        // synthetic intervention to exercise sharding, never a physical load.
        for (long bytesPerEntry : new long[] { 1024, 1048576 })
        {
            for (double error : new double[] { 8, 0 })
            {
                VCompOrderedPartitionLayout layout = new VCompOrderedPartitionLayout(4096, 64);
                VCompSSTSizeModel size = VCompSSTSizeModel.logical(bytesPerEntry);
                List<VCompPipeline.VirtualSortedRun> inputs = new ArrayList<>();
                long totalBytes = 0;
                for (int i = 0; i < batches.size(); i++)
                {
                    inputs.add(new DefaultFlushVirtualizer(error, 512, 8, size).virtualize(
                        new VCompPipeline.FlushBatch("f" + i, batches.get(i),
                                                     batches.get(i).length * bytesPerEntry, i + 1)));
                    totalBytes += size.estimate(batches.get(i).length);
                }
                DefaultVirtualCompaction compactor = new DefaultVirtualCompaction(512, 8, 64L << 20, size, layout);
                VCompPipeline.VirtualCompactionPlan plan = new VCompPipeline.VirtualCompactionPlan("job-1", inputs, 1);
                VCompKmvSketch sketch = compactor.mergeAndDeduplicate(plan);
                long count = compactor.estimateUniqueKeys(plan, sketch);
                VCompLearnedModel model = compactor.merge(plan, count);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("bytes_per_entry_metadata_only", bytesPerEntry);
                result.put("plr_error", error);
                result.put("global_sketch_complete", sketch.isComplete());
                result.put("global_sketch_count", sketch.samples().size());
                result.put("exact_union_count", union.size());
                result.put("estimated_count", count);
                double density = totalBytes / layout.tokenCoverage(union.first(), union.last());
                int shards = Controller.calculateNumShards(density, Controller.defaultMinSSTableSizeBytes(),
                                                            1, 64L << 20, 0.333);
                result.put("shard_count", shards);
                List<Map<String, Object>> allocations = new ArrayList<>();
                long assigned = 0;
                int currentShard = -1;
                Map<String, Object> allocation = null;
                for (VCompOrderedPartitionLayout.Partition partition : layout.partitions())
                {
                    long minimum = Math.max(partition.minimum(), model.keyMin());
                    long maximum = Math.min(partition.maximum(), model.keyMax());
                    if (maximum < minimum) continue;
                    long through = maximum == model.keyMax() ? count : model.ranksBeforeRoundedKey(maximum + 1, count);
                    long low = Math.max(assigned, count - (model.keyMax() - maximum));
                    long high = Math.min(count, assigned + maximum - minimum + 1);
                    through = Math.max(low, Math.min(high, through));
                    int shard = layout.shardIndex(partition, shards);
                    if (shard != currentShard)
                    {
                        allocation = new LinkedHashMap<>();
                        allocation.put("shard", shard);
                        allocation.put("modeled_count", 0L);
                        allocation.put("original_count", 0L);
                        allocations.add(allocation);
                        currentShard = shard;
                    }
                    allocation.put("modeled_count", (Long) allocation.get("modeled_count") + through - assigned);
                    allocation.put("original_count", (Long) allocation.get("original_count")
                                                        + union.subSet(minimum, true, maximum, true).size());
                    assigned = through;
                }
                result.put("diagnostic_allocations", allocations);
                try
                {
                    VCompPipeline.VirtualSortedRun output = compactor.split(plan, model, sketch, count);
                    result.put("production_split_status", "success");
                    result.put("output_count", output.sstables().size());
                }
                catch (IllegalArgumentException e)
                {
                    result.put("production_split_status", "rejected");
                    result.put("exception", e.getMessage());
                }
                results.add(result);
            }
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("diagnostic_only", true);
        report.put("seed", 20260909);
        report.put("domain", 4096);
        report.put("partitions", 64);
        report.put("flushes", 4);
        report.put("attempted_writes_per_flush", 128);
        report.put("cases", results);
        Files.write(Paths.get(args[0]), JsonUtils.JSON_OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(report));
    }
}
