/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.cassandra.db.compaction.vcomp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PrimitiveIterator;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DefaultVirtualCompactionTest
{
    @Test
    public void mergesDeduplicatesAndBuildsOneCassandraOutputRun() throws Exception
    {
        DefaultFlushVirtualizer virtualizer = new DefaultFlushVirtualizer(0, 64, 2);
        VCompPipeline.VirtualSortedRun left = virtualizer.virtualize(
        new VCompPipeline.FlushBatch("left", new long[]{ 0, 10, 20, 30 }, 400, 1));
        VCompPipeline.VirtualSortedRun right = virtualizer.virtualize(
        new VCompPipeline.FlushBatch("right", new long[]{ 20, 30, 40, 50 }, 400, 2));
        VCompPipeline.VirtualCompactionPlan plan = new VCompPipeline.VirtualCompactionPlan("merge",
                                                                                          Arrays.asList(left, right),
                                                                                          1);
        DefaultVirtualCompaction compaction = new DefaultVirtualCompaction(64, 2);

        VCompPipeline.MergedSketch sketch = compaction.mergeAndDeduplicate(plan);
        long uniqueKeys = compaction.estimateUniqueKeys(plan, sketch);
        VCompPipeline.MergedModel model = compaction.merge(plan, uniqueKeys);
        assertTrue("intermediate compaction must not attach a recursive discrete certificate",
                   ((VCompLearnedModel) model).discreteModel() == null);
        VCompPipeline.VirtualSortedRun output = compaction.split(plan, model, sketch, uniqueKeys);
        VCompPipeline.VirtualSSTable sstable = output.sstables().get(0);

        assertEquals(6, uniqueKeys);
        assertEquals(1, output.level());
        assertEquals(1, output.sstables().size());
        assertEquals(600, sstable.estimatedBytes());
        assertEquals(2, sstable.maximumTimestamp());
        assertTrue(sstable.model().discreteModel() == null);
        assertEquals(Arrays.asList(0L, 10L, 20L, 30L, 40L, 50L), materialize(sstable));
    }

    @Test
    public void rejectsDescriptorCardinalityLargerThanItsKeyDomain()
    {
        VCompLearnedModel model = new VCompLearnedModel(Arrays.asList(
        new VCompLearnedModel.Segment(7, 7, 0, 0)));
        try
        {
            new VCompPipeline.VirtualSSTable("one",
                                             7,
                                             7,
                                             3,
                                             300,
                                             model,
                                             VCompKmvSketch.build(new long[]{ 7 }, 4),
                                             new ArrayList<>());
            throw new AssertionError("impossible descriptor cardinality was accepted");
        }
        catch (IllegalArgumentException expected)
        {
            // Expected.
        }
    }

    @Test
    public void globalMergeUsesGlobalSketchesInsteadOfRangeSketchThresholds()
    {
        long[] leftKeys = new long[800];
        long[] rightKeys = new long[800];
        long[] union = new long[1600];
        for (int i = 0; i < 800; i++)
        {
            leftKeys[i] = i * 2L;
            rightKeys[i] = i * 2L + 1;
            union[i * 2] = leftKeys[i];
            union[i * 2 + 1] = rightKeys[i];
        }

        DefaultFlushVirtualizer virtualizer = new DefaultFlushVirtualizer(8, 512, 8);
        VCompPipeline.VirtualSortedRun left = virtualizer.virtualize(
        new VCompPipeline.FlushBatch("left-large", leftKeys, 800 * 1024L));
        VCompPipeline.VirtualSortedRun right = virtualizer.virtualize(
        new VCompPipeline.FlushBatch("right-large", rightKeys, 800 * 1024L));
        VCompPipeline.VirtualCompactionPlan plan = new VCompPipeline.VirtualCompactionPlan(
        "merge-large", Arrays.asList(left, right), 1);

        VCompKmvSketch actual = new DefaultVirtualCompaction(512, 8).mergeAndDeduplicate(plan);
        VCompKmvSketch expected = VCompKmvSketch.build(union, 512);

        assertFalse(actual.isComplete());
        assertEquals(expected.thetaHash(), actual.thetaHash());
        assertEquals(expected.samples().size(), actual.samples().size());
        for (int i = 0; i < expected.samples().size(); i++)
        {
            assertEquals(expected.samples().get(i).key(), actual.samples().get(i).key());
            assertEquals(expected.samples().get(i).hash(), actual.samples().get(i).hash());
        }
        assertTrue(actual.samples().size() == 512);
    }

    @Test
    public void incompleteKmvUsesCommonThetaCardinality()
    {
        long[] keys = new long[1000];
        for (int i = 0; i < keys.length; i++) keys[i] = i;
        DefaultFlushVirtualizer virtualizer = new DefaultFlushVirtualizer(8, 64, 2);
        VCompPipeline.VirtualSortedRun left = virtualizer.virtualize(
        new VCompPipeline.FlushBatch("ratio-left", keys, 100000));
        VCompPipeline.VirtualSortedRun right = virtualizer.virtualize(
        new VCompPipeline.FlushBatch("ratio-right", keys, 100000));
        VCompPipeline.VirtualCompactionPlan plan = new VCompPipeline.VirtualCompactionPlan(
        "ratio", Arrays.asList(left, right), 1);
        DefaultVirtualCompaction compaction = new DefaultVirtualCompaction(64, 2);
        VCompPipeline.MergedSketch merged = compaction.mergeAndDeduplicate(plan);
        assertEquals(1000, compaction.estimateUniqueKeys(plan, merged));
    }

    @Test
    public void commonThetaDoesNotReuseInflatedDescriptorCounts()
    {
        long[] leftKeys = new long[1000];
        long[] rightKeys = new long[1000];
        for (int i = 0; i < 1000; i++)
        {
            leftKeys[i] = i * 2L;
            rightKeys[i] = i * 2L + 1;
        }
        DefaultFlushVirtualizer virtualizer = new DefaultFlushVirtualizer(8, 64, 2);
        VCompPipeline.VirtualSSTable left = virtualizer.virtualize(
        new VCompPipeline.FlushBatch("theta-left", leftKeys, 100000)).sstables().get(0);
        VCompPipeline.VirtualSSTable right = virtualizer.virtualize(
        new VCompPipeline.FlushBatch("theta-right", rightKeys, 100000)).sstables().get(0);

        left = withEstimatedEntries(left, 1200);
        right = withEstimatedEntries(right, 1200);
        VCompPipeline.VirtualCompactionPlan plan = new VCompPipeline.VirtualCompactionPlan(
        "theta", Arrays.asList(new VCompPipeline.VirtualSortedRun("left", 0, Arrays.asList(left)),
                               new VCompPipeline.VirtualSortedRun("right", 0, Arrays.asList(right))), 1);
        DefaultVirtualCompaction compaction = new DefaultVirtualCompaction(64, 2);
        VCompPipeline.MergedSketch merged = compaction.mergeAndDeduplicate(plan);
        long estimated = compaction.estimateUniqueKeys(plan, merged);

        assertTrue("common-theta must not return the inflated naive sum", estimated < 2400);
        assertTrue("estimate should remain near the actual 2,000-key union", estimated > 1500);
    }

    @Test
    public void splitsOneSortedRunIntoCountPreservingSSTables()
    {
        DefaultFlushVirtualizer virtualizer = new DefaultFlushVirtualizer(0, 64, 2);
        VCompPipeline.VirtualSortedRun left = virtualizer.virtualize(
        new VCompPipeline.FlushBatch("left-split", new long[]{ 0, 10, 20, 30 }, 400, 1));
        VCompPipeline.VirtualSortedRun right = virtualizer.virtualize(
        new VCompPipeline.FlushBatch("right-split", new long[]{ 20, 30, 40, 50 }, 400, 2));
        VCompPipeline.VirtualCompactionPlan plan = new VCompPipeline.VirtualCompactionPlan(
        "split", Arrays.asList(left, right), 1);
        DefaultVirtualCompaction compaction = new DefaultVirtualCompaction(
        64, 2, 250, VCompSSTSizeModel.logical(100));
        VCompPipeline.MergedSketch sketch = compaction.mergeAndDeduplicate(plan);
        long count = compaction.estimateUniqueKeys(plan, sketch);
        VCompPipeline.MergedModel model = compaction.merge(plan, count);
        VCompPipeline.VirtualSortedRun output = compaction.split(plan, model, sketch, count);

        assertEquals(3, output.sstables().size());
        List<Long> keys = new ArrayList<>();
        for (VCompPipeline.VirtualSSTable sstable : output.sstables())
        {
            assertEquals(2, sstable.estimatedUniqueKeys());
            assertEquals(200, sstable.estimatedBytes());
            keys.addAll(materialize(sstable));
        }
        assertEquals(Arrays.asList(0L, 10L, 20L, 30L, 40L, 50L), keys);
    }

    @Test
    public void orderedPathSplitsAtTheSameFullRingShardBoundariesAsUcs()
    throws Exception
    {
        long mib = 1L << 20;
        long[] keys = new long[1000];
        for (int i = 0; i < keys.length; i++)
            keys[i] = i;
        VCompSSTSizeModel sizeModel = VCompSSTSizeModel.logical((64 * mib) / keys.length);
        DefaultFlushVirtualizer virtualizer = new DefaultFlushVirtualizer(0, 2048, 8, sizeModel);
        List<VCompPipeline.VirtualSortedRun> inputs = new ArrayList<>();
        for (int i = 0; i < 4; i++)
            inputs.add(virtualizer.virtualize(new VCompPipeline.FlushBatch("ordered-" + i,
                                                                           keys, 64 * mib, i + 1)));
        VCompPipeline.VirtualCompactionPlan plan = new VCompPipeline.VirtualCompactionPlan("ordered",
                                                                                           inputs, 1);
        VCompOrderedPartitionLayout layout = new VCompOrderedPartitionLayout(1000, 100);
        DefaultVirtualCompaction compaction = new DefaultVirtualCompaction(2048, 8, 64 * mib,
                                                                            sizeModel,
                                                                            layout);
        VCompPipeline.MergedSketch sketch = compaction.mergeAndDeduplicate(plan);
        long count = compaction.estimateUniqueKeys(plan, sketch);
        VCompLearnedModel model = compaction.merge(plan, count);
        VCompPipeline.VirtualSortedRun output = compaction.split(plan, model, sketch, count);

        assertEquals(1000, count);
        assertEquals("256 MiB at default UCS growth maps to two full-ring shards", 2,
                     output.sstables().size());
        long outputCount = 0;
        for (VCompPipeline.VirtualSSTable sstable : output.sstables())
        {
            assertEquals(layout.partitionFor(sstable.keyMin()).minimum(), sstable.keyMin());
            assertEquals(layout.partitionFor(sstable.keyMax()).maximum(), sstable.keyMax());
            outputCount += sstable.estimatedUniqueKeys();
        }
        assertEquals(count, outputCount);
    }

    private static List<Long> materialize(VCompPipeline.VirtualSSTable sstable)
    {
        List<Long> keys = new ArrayList<>();
        PrimitiveIterator.OfLong iterator = new VCompMaterializedKeyIterator(sstable);
        while (iterator.hasNext())
            keys.add(iterator.nextLong());
        return keys;
    }

    private static VCompPipeline.VirtualSSTable withEstimatedEntries(VCompPipeline.VirtualSSTable input,
                                                                      long entries)
    {
        return new VCompPipeline.VirtualSSTable(input.id(),
                                                input.keyMin(),
                                                input.keyMax(),
                                                entries,
                                                input.estimatedBytes(),
                                                input.maximumTimestamp(),
                                                new VCompLearnedModel(input.model().segments()),
                                                input.sketch(),
                                                input.rangeSketches());
    }
}
