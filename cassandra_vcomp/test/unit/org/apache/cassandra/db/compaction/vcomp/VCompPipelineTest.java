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
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class VCompPipelineTest
{
    @Test
    public void schedulesACompactionCheckAfterEachFlush() throws Exception
    {
        List<String> events = new ArrayList<>();
        VCompPipeline.LoadSource source = () ->
        {
            events.add("capture-flushes");
            return Arrays.asList(new VCompPipeline.FlushBatch("flush-1", coordinates(10), 10240),
                                 new VCompPipeline.FlushBatch("flush-2", coordinates(10), 10240));
        };

        VCompPipeline pipeline = new VCompPipeline(flush ->
        {
            events.add("virtualize-" + flush.id());
            VCompPipeline.VirtualSSTable sstable = descriptor("vsst-" + flush.id(),
                                                              (int) flush.keyCount(),
                                                              flush.logicalBytes());
            return new VCompPipeline.VirtualSortedRun("run-" + flush.id(), 0, Collections.singletonList(sstable));
        }, state ->
        {
            events.add("pick");
            if (state.runs().size() < 2)
                return Optional.empty();
            return Optional.of(new VCompPipeline.VirtualCompactionPlan("compaction-1", state.runs(), 1));
        }, (plan, estimatedUniqueKeys) ->
        {
            events.add("merge-models");
            assertEquals(18, estimatedUniqueKeys);
            return new VCompPipeline.MergedModel() { };
        }, plan ->
        {
            events.add("merge-sketches");
            return new VCompPipeline.MergedSketch() { };
        }, (plan, sketch) ->
        {
            events.add("estimate-dedup");
            return 18;
        }, (plan, model, sketch, estimatedUniqueKeys) ->
        {
            events.add("split-outputs");
            VCompPipeline.VirtualSSTable output = descriptor("vsst-output",
                                                             (int) estimatedUniqueKeys,
                                                             18432);
            return new VCompPipeline.VirtualSortedRun("run-output",
                                                      plan.outputLevel(),
                                                      Collections.singletonList(output));
        }, state ->
        {
            events.add("freeze-layout");
            return new VCompPipeline.FrozenLayout(state.runs());
        }, layout ->
        {
            events.add("materialize");
            return new VCompPipeline.MaterializedState(Collections.singletonList("physical-sstable-1"));
        }, materialized -> events.add("install"),
           (layout, materialized) -> events.add("verify"));

        VCompPipeline.Result result = pipeline.execute(new VCompPipeline.Request(
        VCompPipeline.ExecutionConstraints.singlePartitionKeyValue(), source));

        assertEquals(1, result.virtualCompactionCount());
        assertEquals(1, result.layout().runs().size());
        assertEquals(Collections.singletonList("physical-sstable-1"), result.materialized().sstableIds());
        assertEquals(Arrays.asList("capture-flushes",
                                   "virtualize-flush-1",
                                   "pick",
                                   "virtualize-flush-2",
                                   "pick",
                                   "merge-sketches",
                                   "estimate-dedup",
                                   "merge-models",
                                   "split-outputs",
                                   "pick",
                                   "freeze-layout",
                                   "materialize",
                                   "verify",
                                   "install"),
                     events);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMultiNodeExecution() throws Exception
    {
        VCompPipeline.ExecutionConstraints invalid = new VCompPipeline.ExecutionConstraints(2,
                                                                                             1,
                                                                                             1,
                                                                                             1,
                                                                                             1,
                                                                                             false,
                                                                                             false,
                                                                                             false,
                                                                                             false);
        noOpPipeline().execute(new VCompPipeline.Request(invalid, Collections::emptyList));
    }

    @Test
    public void acceptsMultipleDescriptorsForOrderedPartitions() throws Exception
    {
        VCompPipeline.VirtualSortedRun splitRun = new VCompPipeline.VirtualSortedRun(
        "split-output",
        1,
        Arrays.asList(descriptor("left", 0, 10, 10240),
                      descriptor("right", 10, 10, 10240)));
        VCompPipeline pipeline = new VCompPipeline(flush -> splitRun,
                                                   state -> Optional.empty(),
                                                   (plan, estimatedUniqueKeys) -> null,
                                                   plan -> null,
                                                   (plan, sketch) -> 0,
                                                   (plan, model, sketch, uniqueKeys) -> null,
                                                   state -> new VCompPipeline.FrozenLayout(state.runs()),
                                                   layout -> new VCompPipeline.MaterializedState(
                                                   Arrays.asList("left", "right"), 20, 20480),
                                                   materialized -> { },
                                                   (layout, materialized) -> { });

        VCompPipeline.Result result = pipeline.execute(new VCompPipeline.Request(
        VCompPipeline.ExecutionConstraints.orderedPartitionKeyValue(2),
        () -> Collections.singletonList(new VCompPipeline.FlushBatch("flush", coordinates(20), 20480))));
        assertEquals(2, result.layout().runs().get(0).sstables().size());
    }

    @Test
    public void rejectsSizeTargetForRestrictedSinglePartitionPipeline()
    {
        try
        {
            VCompPipeline.createDefault(64,
                                        64,
                                        VCompSSTSizeModel.logical(1),
                                        layout -> new VCompPipeline.MaterializedState(Collections.emptyList()),
                                        materialized -> { },
                                        (layout, materialized) -> { });
            fail("expected a size-based SST target to be rejected");
        }
        catch (IllegalArgumentException e)
        {
            assertTrue(e.getMessage().contains("partition-atomic"));
            assertTrue(e.getMessage().contains("Long.MAX_VALUE"));
        }
    }

    @Test
    public void defaultPipelineKeepsPaperContinuousModelThroughFreeze() throws Exception
    {
        long[] keys = coordinates(100);
        VCompPipeline pipeline = VCompPipeline.createDefault(
        1 << 20,
        layout ->
        {
            VCompPipeline.VirtualSSTable finalSSTable = layout.runs().get(0).sstables().get(0);
            assertTrue("paper materialization must invert the continuous corrected rank model",
                       finalSSTable.model().discreteModel() == null);
            return new VCompPipeline.MaterializedState(Collections.singletonList("final"),
                                                       keys.length,
                                                       keys.length);
        },
        materialized -> { },
        (layout, materialized) -> { });

        VCompPipeline.Result result = pipeline.execute(new VCompPipeline.Request(
        VCompPipeline.ExecutionConstraints.singlePartitionKeyValue(),
        () -> Collections.singletonList(new VCompPipeline.FlushBatch("one", keys, keys.length, 100))));

        assertEquals(0, result.virtualCompactionCount());
        assertTrue(result.layout().runs().get(0).sstables().get(0).model().discreteModel() == null);
    }

    private static VCompPipeline noOpPipeline()
    {
        return new VCompPipeline(flush -> null,
                                 state -> Optional.empty(),
                                 (plan, estimatedUniqueKeys) -> null,
                                 plan -> null,
                                 (plan, sketch) -> 0,
                                 (plan, model, sketch, uniqueKeys) -> null,
                                 state -> new VCompPipeline.FrozenLayout(state.runs()),
                                 layout -> new VCompPipeline.MaterializedState(Collections.emptyList()),
                                 materialized -> { },
                                 (layout, materialized) -> { });
    }

    private static VCompPipeline.VirtualSSTable descriptor(String id, int count, long bytes)
    {
        return descriptor(id, 0, count, bytes);
    }

    private static VCompPipeline.VirtualSSTable descriptor(String id, int first, int count, long bytes)
    {
        long[] keys = coordinates(first, count);
        return new VCompPipeline.VirtualSSTable(id,
                                                keys[0],
                                                keys[keys.length - 1],
                                                count,
                                                bytes,
                                                VCompLearnedModel.greedyFit(keys, 0),
                                                VCompKmvSketch.build(keys, Math.max(1, count)),
                                                Collections.emptyList());
    }

    private static long[] coordinates(int count)
    {
        return coordinates(0, count);
    }

    private static long[] coordinates(int first, int count)
    {
        long[] keys = new long[count];
        for (int i = 0; i < count; i++)
            keys[i] = first + i;
        return keys;
    }
}
