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

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.junit.Test;

import org.apache.cassandra.db.DecoratedKey;
import org.apache.cassandra.dht.Murmur3Partitioner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VCompUcsPlannerTest
{
    private static final long MIB = 1L << 20;

    @Test
    public void observedFlushSizeUsesNativeWholeMibRounding()
    {
        assertEquals(MIB, VCompUcsPlanner.roundObservedFlushSize(1));
        assertEquals(64 * MIB, VCompUcsPlanner.roundObservedFlushSize(64 * MIB));
        assertEquals(65 * MIB, VCompUcsPlanner.roundObservedFlushSize(64 * MIB + 1));
    }

    @Test
    public void waitsForTieredThreshold()
    {
        VCompUcsPlanner planner = new VCompUcsPlanner(64 * MIB);

        Optional<VCompPipeline.VirtualCompactionPlan> plan = planner.pick(snapshot(run("1", 64, 1),
                                                                                  run("2", 64, 2),
                                                                                  run("3", 64, 3)));

        assertFalse(plan.isPresent());
    }

    @Test
    public void picksAllFourOverlappingRunsAtDefaultT4Threshold()
    {
        VCompUcsPlanner planner = new VCompUcsPlanner(64 * MIB);

        Optional<VCompPipeline.VirtualCompactionPlan> plan = planner.pick(snapshot(run("1", 64, 1),
                                                                                  run("2", 64, 2),
                                                                                  run("3", 64, 3),
                                                                                  run("4", 64, 4)));

        assertTrue(plan.isPresent());
        // Native UCS keeps max-timestamp-descending order on the normal count <= fanout path.
        assertEquals(Arrays.asList("run-4", "run-3", "run-2", "run-1"), ids(plan.get()));
        assertEquals(1, plan.get().outputLevel());
    }

    @Test
    public void prioritizesDensityLevelWithHigherOverlap()
    {
        VCompUcsPlanner planner = new VCompUcsPlanner(64 * MIB);
        VCompPipeline.VirtualStateSnapshot state = snapshot(run("l0-1", 64, 1),
                                                            run("l0-2", 64, 2),
                                                            run("l0-3", 64, 3),
                                                            run("l0-4", 64, 4),
                                                            run("l1-1", 256, 5),
                                                            run("l1-2", 256, 6),
                                                            run("l1-3", 256, 7),
                                                            run("l1-4", 256, 8),
                                                            run("l1-5", 256, 9));

        VCompPipeline.VirtualCompactionPlan plan = planner.pick(state).get();

        assertEquals(Arrays.asList("run-l1-5", "run-l1-4", "run-l1-3", "run-l1-2", "run-l1-1"),
                     ids(plan));
        assertEquals(2, plan.outputLevel());
    }

    @Test
    public void higherOverlapLevelBelowItsOwnThresholdSuppressesLowerLevelPick()
    {
        // This deliberately uses different tiered scaling parameters. Level 0
        // is eligible at T4, while level 1 has more overlap but is below T6.
        // Native UCS compares maxOverlap across all levels after asking each
        // level for a pick, so the null level-1 pick supersedes level 0.
        VCompUcsPlanner planner = new VCompUcsPlanner(64 * MIB, new int[]{ 2, 4 }, 32);
        VCompPipeline.VirtualStateSnapshot state = snapshot(run("l0-1", 64, 1),
                                                            run("l0-2", 64, 2),
                                                            run("l0-3", 64, 3),
                                                            run("l0-4", 64, 4),
                                                            run("l1-1", 256, 5),
                                                            run("l1-2", 256, 6),
                                                            run("l1-3", 256, 7),
                                                            run("l1-4", 256, 8),
                                                            run("l1-5", 256, 9));

        assertFalse(planner.pick(state).isPresent());
    }

    @Test
    public void capsModeratelyLateCompactionAndPicksOldestRuns()
    {
        VCompUcsPlanner planner = new VCompUcsPlanner(64 * MIB, new int[]{ 2 }, 4);
        VCompPipeline.VirtualStateSnapshot state = snapshot(run("newest", 64, 60),
                                                            run("oldest", 64, 10),
                                                            run("middle-3", 64, 40),
                                                            run("middle-1", 64, 20),
                                                            run("middle-4", 64, 50),
                                                            run("middle-2", 64, 30));

        VCompPipeline.VirtualCompactionPlan plan = planner.pick(state).get();

        assertEquals(Arrays.asList("run-oldest", "run-middle-1", "run-middle-2", "run-middle-3"),
                     ids(plan));
    }

    @Test
    public void usesTierStepWhenCompactionIsVeryLate()
    {
        VCompUcsPlanner planner = new VCompUcsPlanner(64 * MIB);
        List<VCompPipeline.VirtualSortedRun> runs = new ArrayList<>();
        for (int i = 0; i < 20; i++)
            runs.add(run(Integer.toString(i), 64, i));

        VCompPipeline.VirtualCompactionPlan plan = planner.pick(new VCompPipeline.VirtualStateSnapshot(runs)).get();

        assertEquals(16, plan.inputs().size());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsLevelledScalingParameters()
    {
        new VCompUcsPlanner(64 * MIB, new int[]{ -2 }, 32);
    }

    @Test
    public void disjointClusteringRangesInSamePartitionOverlapLikeNativeEndpoints()
    {
        VCompOrderedPartitionLayout layout = new VCompOrderedPartitionLayout(400, 4);
        List<VCompPipeline.VirtualSortedRun> runs = new ArrayList<>();
        for (int i = 0; i < 4; ++i)
        {
            runs.add(run(Integer.toString(i), 64, i, i * 10));
            // Native UCS compares SSTableReader first/last DecoratedKeys, so
            // every one of these disjoint clustering ranges has equal endpoints.
            assertEquals(0, decoratedKey(layout, i * 10).compareTo(decoratedKey(layout, 9)));
        }

        VCompPipeline.VirtualCompactionPlan plan = new VCompUcsPlanner(64 * MIB, layout)
                                                   .pick(new VCompPipeline.VirtualStateSnapshot(runs)).get();

        assertEquals(Arrays.asList("run-3", "run-2", "run-1", "run-0"), ids(plan));
    }

    @Test
    public void distinctPartitionRangesRemainDisjointLikeNativeEndpoints()
    {
        VCompOrderedPartitionLayout layout = new VCompOrderedPartitionLayout(400, 4);
        List<VCompPipeline.VirtualSortedRun> runs = new ArrayList<>();
        for (int i = 0; i < 4; ++i)
        {
            runs.add(run(Integer.toString(i), 64, i, i * 100));
            if (i > 0)
                assertTrue(decoratedKey(layout, i * 100).compareTo(decoratedKey(layout, (i - 1) * 100 + 9)) > 0);
        }

        assertFalse(new VCompUcsPlanner(64 * MIB, layout)
                    .pick(new VCompPipeline.VirtualStateSnapshot(runs)).isPresent());
    }

    private static DecoratedKey decoratedKey(VCompOrderedPartitionLayout layout, long coordinate)
    {
        return Murmur3Partitioner.instance.decorateKey(ByteBuffer.wrap(layout.partitionFor(coordinate).encodedKey()));
    }

    private static VCompPipeline.VirtualStateSnapshot snapshot(VCompPipeline.VirtualSortedRun... runs)
    {
        return new VCompPipeline.VirtualStateSnapshot(Arrays.asList(runs));
    }

    private static VCompPipeline.VirtualSortedRun run(String id, long sizeMiB, long maximumTimestamp)
    {
        return run(id, sizeMiB, maximumTimestamp, 0);
    }

    private static VCompPipeline.VirtualSortedRun run(String id, long sizeMiB, long maximumTimestamp, long minimum)
    {
        long[] keys = { 0, 1, 2, 3, 4, 5, 6, 7, 8, 9 };
        for (int i = 0; i < keys.length; ++i)
            keys[i] += minimum;
        VCompPipeline.VirtualSSTable sstable = new VCompPipeline.VirtualSSTable("vsst-" + id,
                                                                               minimum,
                                                                               minimum + 9,
                                                                               10,
                                                                               sizeMiB * MIB,
                                                                               maximumTimestamp,
                                                                               VCompLearnedModel.greedyFit(keys, 0),
                                                                               VCompKmvSketch.build(keys, 4),
                                                                               Collections.emptyList());
        return new VCompPipeline.VirtualSortedRun("run-" + id, 0, Collections.singletonList(sstable));
    }

    private static List<String> ids(VCompPipeline.VirtualCompactionPlan plan)
    {
        return plan.inputs().stream().map(VCompPipeline.VirtualSortedRun::id).collect(Collectors.toList());
    }
}
