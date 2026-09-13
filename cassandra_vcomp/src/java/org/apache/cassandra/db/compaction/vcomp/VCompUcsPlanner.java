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
import java.util.Objects;
import java.util.Optional;
import java.util.Random;

import org.apache.cassandra.db.compaction.UnifiedCompactionStrategy;
import org.apache.cassandra.db.compaction.unified.UnifiedCompactionPicker;
import org.apache.cassandra.utils.Overlaps;

/**
 * Metadata-only UCS picker for the first, deliberately restricted VComp port.
 *
 * <p>Every row in this port has the same partition key. Cassandra therefore regards every
 * candidate SSTable as overlapping every other candidate in the same density level. It also
 * substitutes token-space coverage {@code 1.0} for a single-partition SSTable, making density
 * equal to on-disk size. This class implements that exact restricted case without pretending
 * that clustering-key coordinates are Cassandra token ranges.</p>
 *
 * <p>A sorted run may contain multiple non-overlapping vSST outputs. UCS selects runs in this
 * restricted tiered model, so density is the sum of their physical-size estimates and age is
 * the newest timestamp represented by any child.</p>
 */
public final class VCompUcsPlanner implements VCompPipeline.VirtualCompactionPlanner
{
    /** Cassandra 5.0's default T4 scaling parameter represented as W = 2. */
    public static final int DEFAULT_SCALING_PARAMETER = 2;

    private static final long MINIMUM_BASE_SIZE_BYTES = 1L << 20;

    private final long flushSizeBytes;
    private final VCompOrderedPartitionLayout partitionLayout;
    private final int[] scalingParameters;
    private final int configuredMaxSSTablesToCompact;
    private final Random bucketRandom = new Random(Long.getLong("cassandra.ucs.picker_seed", 20260909L));
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
        this.scalingParameters = scalingParameters.clone();
        this.configuredMaxSSTablesToCompact = maxSSTablesToCompact <= 0
                                              ? Integer.MAX_VALUE
                                              : maxSSTablesToCompact;
    }

    @Override
    public Optional<VCompPipeline.VirtualCompactionPlan> pick(VCompPipeline.VirtualStateSnapshot state)
    {
        Objects.requireNonNull(state, "state");
        UnifiedCompactionPicker.Pick<VCompPipeline.VirtualSortedRun> pick =
        UnifiedCompactionPicker.pick(state.runs(),
                                     candidateAdapter,
                                     new VCompPolicy(),
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
        int fanout = fanout(0);
        return Math.max(MINIMUM_BASE_SIZE_BYTES, flushSizeBytes) * (1.0 - 0.9 / fanout);
    }

    private int fanout(int levelIndex)
    {
        return UnifiedCompactionStrategy.fanoutFromScalingParameter(scalingParameter(levelIndex));
    }

    private int threshold(int levelIndex)
    {
        return UnifiedCompactionStrategy.thresholdFromScalingParameter(scalingParameter(levelIndex));
    }

    private int scalingParameter(int levelIndex)
    {
        return levelIndex < scalingParameters.length
               ? scalingParameters[levelIndex]
               : scalingParameters[scalingParameters.length - 1];
    }

    @Override
    public String toString()
    {
        return "VCompUcsPlanner{" +
               "flushSizeBytes=" + flushSizeBytes +
               ", scalingParameters=" + Arrays.toString(scalingParameters) +
               ", maxSSTablesToCompact=" + configuredMaxSSTablesToCompact +
               '}';
    }

    private final class VCompPolicy implements UnifiedCompactionPicker.Policy
    {
        public int scalingParameter(int level)
        {
            return VCompUcsPlanner.this.scalingParameter(level);
        }

        public int fanout(int level)
        {
            return VCompUcsPlanner.this.fanout(level);
        }

        public int threshold(int level)
        {
            return VCompUcsPlanner.this.threshold(level);
        }

        public double maximumLevelDensity(int level, double minimumDensity)
        {
            // The restricted single-partition T4 schema has survival factor 1.0.
            return Math.floor(minimumDensity * fanout(level));
        }

        public int maximumSSTablesToCompact()
        {
            return configuredMaxSSTablesToCompact;
        }

        public Overlaps.InclusionMethod overlapInclusionMethod()
        {
            return Overlaps.InclusionMethod.TRANSITIVE;
        }

        public int randomInt(int bound)
        {
            // Match Controller.random().nextInt(bound), while retaining a fixed
            // experiment seed so paired VComp runs are reproducible.
            return bucketRandom.nextInt(bound);
        }
    }
}
