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

import java.util.Objects;
import java.util.Optional;

import org.apache.cassandra.db.compaction.unified.Controller;
import org.apache.cassandra.db.compaction.unified.UnifiedCompactionPicker;

/**
 * Metadata-only UCS picker for the first, deliberately restricted VComp port.
 *
 * <p>The candidate adapter exposes only the same size, token-range, timestamp,
 * and ordering metadata that native UCS reads from {@code SSTableReader}. The
 * policy object itself is Cassandra's {@link Controller} policy view; this
 * class must not carry a second set of UCS thresholds or tie-breaking rules.</p>
 */
public final class VCompUcsPlanner implements VCompPipeline.VirtualCompactionPlanner
{
    /** Cassandra 5.0's default T4 scaling parameter represented as W = 2. */
    public static final int DEFAULT_SCALING_PARAMETER = 2;

    private final long flushSizeBytes;
    private final VCompOrderedPartitionLayout partitionLayout;
    private final Controller controller;
    private long nextPlanId;

    private final UnifiedCompactionPicker.CandidateAdapter<VCompPipeline.VirtualSortedRun> candidateAdapter =
    new UnifiedCompactionPicker.CandidateAdapter<VCompPipeline.VirtualSortedRun>()
    {
        public double density(VCompPipeline.VirtualSortedRun run)
        {
            long bytes = 0;
            for (VCompPipeline.VirtualSSTable sstable : run.sstables())
            {
                if (sstable.estimatedBytes() <= 0)
                    throw new IllegalStateException("vSST estimated bytes must be positive: " + sstable.id());
                bytes = saturatedAdd(bytes, sstable.estimatedBytes());
            }
            if (partitionLayout == null)
                return bytes;
            VCompPipeline.VirtualSSTable first = run.sstables().get(0);
            VCompPipeline.VirtualSSTable last = run.sstables().get(run.sstables().size() - 1);
            return bytes / partitionLayout.tokenCoverage(first.keyMin(), last.keyMax());
        }

        public int compareFirst(VCompPipeline.VirtualSortedRun left, VCompPipeline.VirtualSortedRun right)
        {
            return partitionLayout == null ? 0 : Long.compare(first(left).keyMin(), first(right).keyMin());
        }

        public int compareLast(VCompPipeline.VirtualSortedRun left, VCompPipeline.VirtualSortedRun right)
        {
            return partitionLayout == null ? 0 : Long.compare(last(left).keyMax(), last(right).keyMax());
        }

        public boolean startsAfter(VCompPipeline.VirtualSortedRun left, VCompPipeline.VirtualSortedRun right)
        {
            return partitionLayout != null && first(left).keyMin() > last(right).keyMax();
        }

        public long maximumTimestamp(VCompPipeline.VirtualSortedRun run)
        {
            long maximumTimestamp = 0;
            for (VCompPipeline.VirtualSSTable sstable : run.sstables())
                maximumTimestamp = Math.max(maximumTimestamp, sstable.maximumTimestamp());
            return maximumTimestamp;
        }


        private VCompPipeline.VirtualSSTable first(VCompPipeline.VirtualSortedRun run)
        {
            return run.sstables().get(0);
        }

        private VCompPipeline.VirtualSSTable last(VCompPipeline.VirtualSortedRun run)
        {
            return run.sstables().get(run.sstables().size() - 1);
        }
    };

    public VCompUcsPlanner(long flushSizeBytes)
    {
        this(flushSizeBytes, null, new int[]{ DEFAULT_SCALING_PARAMETER }, Integer.MAX_VALUE);
    }

    public VCompUcsPlanner(long flushSizeBytes, VCompOrderedPartitionLayout partitionLayout)
    {
        this(flushSizeBytes, partitionLayout, new int[]{ DEFAULT_SCALING_PARAMETER }, Integer.MAX_VALUE);
    }

    public VCompUcsPlanner(long flushSizeBytes, int[] scalingParameters, int maxSSTablesToCompact)
    {
        this(flushSizeBytes, null, scalingParameters, maxSSTablesToCompact);
    }

    public VCompUcsPlanner(long flushSizeBytes,
                           VCompOrderedPartitionLayout partitionLayout,
                           int[] scalingParameters,
                           int maxSSTablesToCompact)
    {
        if (flushSizeBytes <= 0)
            throw new IllegalArgumentException("flushSizeBytes must be positive");
        Objects.requireNonNull(scalingParameters, "scalingParameters");
        if (scalingParameters.length == 0)
            throw new IllegalArgumentException("at least one scaling parameter is required");
        for (int scalingParameter : scalingParameters)
        {
            if (scalingParameter < 0)
                throw new IllegalArgumentException("the first VComp planner supports tiered UCS only");
        }
        this.flushSizeBytes = flushSizeBytes;
        this.partitionLayout = partitionLayout;
        this.controller = Controller.forOfflineTools(flushSizeBytes,
                                                     scalingParameters,
                                                     maxSSTablesToCompact,
                                                     1,
                                                     64L << 20,
                                                     0.333);
    }

    @Override
    public Optional<VCompPipeline.VirtualCompactionPlan> pick(VCompPipeline.VirtualStateSnapshot state)
    {
        Objects.requireNonNull(state, "state");
        UnifiedCompactionPicker.Pick<VCompPipeline.VirtualSortedRun> pick =
        UnifiedCompactionPicker.pick(state.runs(),
                                     candidateAdapter,
                                     controller.pickerPolicy(),
                                     firstLevelMinimumDensity());
        if (pick == null)
            return Optional.empty();

        String planId = "ucs-vcomp-" + nextPlanId++;
        return Optional.of(new VCompPipeline.VirtualCompactionPlan(planId,
                                                                   pick.inputs(),
                                                                   pick.level() + 1));
    }

    private static long saturatedAdd(long left, long right)
    {
        return right > Long.MAX_VALUE - left ? Long.MAX_VALUE : left + right;
    }

    private double firstLevelMinimumDensity()
    {
        return controller.getBaseSstableSize(controller.getFanout(0));
    }

    @Override
    public String toString()
    {
        return "VCompUcsPlanner{" +
               "flushSizeBytes=" + flushSizeBytes +
               '}';
    }
}
