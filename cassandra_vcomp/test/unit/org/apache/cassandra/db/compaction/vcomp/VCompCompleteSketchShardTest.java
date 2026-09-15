/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.cassandra.db.compaction.vcomp;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.TreeSet;

import org.junit.Test;
import static org.junit.Assert.*;

/** Original-key sketch cardinality is independent of a modeled shard's rank count. */
public class VCompCompleteSketchShardTest
{
    @Test
    public void completeOriginalSketchSurvivesApproximateShardAllocation()
    {
        for (double error : new double[] { 8, 0 })
        {
            SplittableRandom random = new SplittableRandom(20260909);
            TreeSet<Long> original = new TreeSet<>();
            List<VCompPipeline.VirtualSortedRun> inputs = new ArrayList<>();
            // Metadata-only size forces native sharding without writing large values.
            VCompSSTSizeModel size = VCompSSTSizeModel.logical(1L << 20);
            for (int i = 0; i < 4; i++)
            {
                TreeSet<Long> keys = new TreeSet<>();
                for (int j = 0; j < 128; j++) keys.add(random.nextLong(4096));
                original.addAll(keys);
                long[] coordinates = keys.stream().mapToLong(Long::longValue).toArray();
                inputs.add(new DefaultFlushVirtualizer(error, 512, 8, size).virtualize(
                    new VCompPipeline.FlushBatch("f" + i, coordinates, size.estimate(keys.size()), i + 1)));
            }
            DefaultVirtualCompaction compactor = new DefaultVirtualCompaction(
                512, 8, 64L << 20, size, new VCompOrderedPartitionLayout(4096, 64));
            VCompPipeline.VirtualCompactionPlan plan = new VCompPipeline.VirtualCompactionPlan("j", inputs, 1);
            VCompKmvSketch sketch = compactor.mergeAndDeduplicate(plan);
            long count = compactor.estimateUniqueKeys(plan, sketch);
            assertEquals(original.size(), count);
            VCompPipeline.VirtualSortedRun output = compactor.split(plan, compactor.merge(plan, count), sketch, count);
            assertTrue(output.sstables().size() > 1);
            long allocated = 0;
            TreeSet<Long> carried = new TreeSet<>();
            for (VCompPipeline.VirtualSSTable shard : output.sstables())
            {
                allocated += shard.estimatedUniqueKeys();
                assertTrue(shard.sketch().isComplete());
                TreeSet<Long> shardKeys = new TreeSet<>();
                for (VCompKmvSketch.Sample sample : shard.sketch().samples()) shardKeys.add(sample.key());
                assertEquals(original.subSet(shard.keyMin(), true, shard.keyMax(), true), shardKeys);
                carried.addAll(shardKeys);
            }
            assertEquals(count, allocated);
            assertEquals(original, carried);
        }
    }
}
