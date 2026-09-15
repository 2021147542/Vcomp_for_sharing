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

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** A complete original-key sketch is exact even after approximate row allocation. */
public class VCompCompleteCardinalityTest
{
    @Test
    public void completeUnionIsNotCappedByModeledInputRows()
    {
        VCompPipeline.VirtualSSTable left = modeled("left", new long[] { 0, 2, 4 }, 2);
        VCompPipeline.VirtualSSTable right = modeled("right", new long[] { 2, 4, 6 }, 1);
        VCompPipeline.VirtualCompactionPlan plan = new VCompPipeline.VirtualCompactionPlan(
            "merge", Arrays.asList(run(left), run(right)), 1);
        DefaultVirtualCompaction compactor = new DefaultVirtualCompaction(512, 8);
        VCompKmvSketch union = compactor.mergeAndDeduplicate(plan);
        assertTrue(union.isComplete());
        assertEquals(4, union.samples().size());
        assertEquals(4, compactor.estimateUniqueKeys(plan, union));
    }

    @Test
    public void completeLocalUnionUsesFilteredOriginalKeys()
    {
        VCompPipeline.VirtualSSTable child = modeled("child", new long[] { 0, 1, 2, 3, 4 }, 1);
        DefaultVirtualCompaction compactor = new DefaultVirtualCompaction(512, 8);
        assertEquals(3, compactor.estimateUnionForRange(Collections.singletonList(child), 1, 3));
        assertEquals(1, compactor.estimateUnionForRange(Collections.singletonList(child), 2, 2));
        assertEquals(0, compactor.estimateUnionForRange(Collections.singletonList(child), 10, 11));
    }

    @Test
    public void exactCompleteCountStillFitsTheOriginalKeyDomain()
    {
        VCompPipeline.VirtualSSTable child = modeled("dense", new long[] { 0, 1, 2, 3 }, 1);
        DefaultVirtualCompaction compactor = new DefaultVirtualCompaction(512, 8);
        VCompPipeline.VirtualCompactionPlan plan = new VCompPipeline.VirtualCompactionPlan(
            "dense", Collections.singletonList(run(child)), 1);
        long count = compactor.estimateUniqueKeys(plan, compactor.mergeAndDeduplicate(plan));
        assertEquals(4, count);
        assertTrue(count <= child.keyMax() - child.keyMin() + 1);
    }

    private static VCompPipeline.VirtualSortedRun run(VCompPipeline.VirtualSSTable sstable)
    {
        return new VCompPipeline.VirtualSortedRun(sstable.id(), 0, Collections.singletonList(sstable));
    }

    private static VCompPipeline.VirtualSSTable modeled(String id, long[] original, long count)
    {
        long minimum = original[0], maximum = original[original.length - 1];
        double slope = count / (double) (maximum - minimum);
        VCompLearnedModel model = new VCompLearnedModel(Collections.singletonList(
            new VCompLearnedModel.Segment(minimum, maximum, slope, -slope * minimum)));
        return new VCompPipeline.VirtualSSTable(id, minimum, maximum, count, count * 1000,
                                                model, VCompKmvSketch.build(original, 512),
                                                Collections.emptyList());
    }
}
