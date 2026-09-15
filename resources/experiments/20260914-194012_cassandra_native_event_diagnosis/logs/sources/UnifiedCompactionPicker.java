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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.cassandra.db.compaction.unified;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.cassandra.utils.Overlaps;

/**
 * The metadata-only decision kernel of Unified Compaction Strategy.
 *
 * <p>The picker deliberately knows nothing about {@code SSTableReader}, lifecycle transactions,
 * or compaction execution. Physical Cassandra SSTables and VComp descriptors supply lossless
 * metadata adapters and receive the same selected candidate objects back. Execution may branch
 * only after this class has returned a pick.</p>
 */
public final class UnifiedCompactionPicker
{
    private static final int MAX_LEVELS = 32;

    private UnifiedCompactionPicker()
    {
    }

    public interface CandidateAdapter<T>
    {
        double density(T candidate);

        int compareFirst(T left, T right);

        int compareLast(T left, T right);

        boolean startsAfter(T left, T right);

        long maximumTimestamp(T candidate);
    }

    /** UCS configuration required for selection, normally backed by {@link Controller}. */
    public interface Policy
    {
        int scalingParameter(int level);

        int fanout(int level);

        int threshold(int level);

        double maximumLevelDensity(int level, double minimumDensity);

        int maximumSSTablesToCompact();

        Overlaps.InclusionMethod overlapInclusionMethod();

        int randomInt(int bound);
    }

    public static final class Pick<T>
    {
        private final int level;
        private final int overlap;
        private final List<T> inputs;
        private final int estimatedRemainingTasks;

        private Pick(int level, int overlap, Collection<T> inputs, int estimatedRemainingTasks)
        {
            this.level = level;
            this.overlap = overlap;
            this.inputs = new ArrayList<>(inputs);
            this.estimatedRemainingTasks = estimatedRemainingTasks;
        }

        public int level()
        {
            return level;
        }

        public int overlap()
        {
            return overlap;
        }

        public List<T> inputs()
        {
            return new ArrayList<>(inputs);
        }

        public int estimatedRemainingTasks()
        {
            return estimatedRemainingTasks;
        }
    }

    public static <T> Pick<T> pick(List<T> candidates,
                                   CandidateAdapter<T> adapter,
                                   Policy policy,
                                   double firstLevelMinimumDensity)
    {
        List<Level<T>> levels = formLevels(candidates, adapter, policy, firstLevelMinimumDensity);
        int maximumOverlap = -1;
        Bucket<T> selected = null;
        int selectedLevel = -1;
        int estimatedRemainingTasks = 0;
        for (Level<T> level : levels)
        {
            BucketSelection<T> selection = level.select(adapter, policy);
            estimatedRemainingTasks += selection.estimatedRemainingTasks;
            if (selection.maximumOverlap > maximumOverlap)
            {
                maximumOverlap = selection.maximumOverlap;
                selected = selection.bucket;
                selectedLevel = level.index;
            }
        }
        if (selected == null)
            return null;

        Collection<T> inputs = selected.constructPick(adapter, policy);
        return new Pick<>(selectedLevel, selected.maximumOverlap, inputs, estimatedRemainingTasks);
    }

    private static <T> List<Level<T>> formLevels(List<T> input,
                                                  CandidateAdapter<T> adapter,
                                                  Policy policy,
                                                  double firstLevelMinimumDensity)
    {
        List<T> candidates = new ArrayList<>(input);
        candidates.sort(Comparator.comparingDouble(adapter::density));
        List<Level<T>> levels = new ArrayList<>(MAX_LEVELS);
        int index = 0;
        double maximumDensity = policy.maximumLevelDensity(index, firstLevelMinimumDensity);
        Level<T> level = new Level<>(index, maximumDensity, policy.threshold(index), policy.fanout(index),
                                     policy.scalingParameter(index));
        for (T candidate : candidates)
        {
            double density = adapter.density(candidate);
            if (density < level.maximumDensity)
            {
                level.candidates.add(candidate);
                continue;
            }

            levels.add(level);
            while (true)
            {
                ++index;
                double minimumDensity = maximumDensity;
                maximumDensity = policy.maximumLevelDensity(index, minimumDensity);
                level = new Level<>(index, maximumDensity, policy.threshold(index), policy.fanout(index),
                                    policy.scalingParameter(index));
                if (density < level.maximumDensity)
                {
                    level.candidates.add(candidate);
                    break;
                }
                levels.add(level);
            }
        }
        if (!level.candidates.isEmpty())
            levels.add(level);
        return levels;
    }

    private static final class Level<T>
    {
        private final int index;
        private final double maximumDensity;
        private final int threshold;
        private final int fanout;
        private final int scalingParameter;
        private final List<T> candidates = new ArrayList<>();

        private Level(int index, double maximumDensity, int threshold, int fanout, int scalingParameter)
        {
            this.index = index;
            this.maximumDensity = maximumDensity;
            this.threshold = threshold;
            this.fanout = fanout;
            this.scalingParameter = scalingParameter;
        }

        private BucketSelection<T> select(CandidateAdapter<T> adapter, Policy policy)
        {
            List<Set<T>> overlaps = Overlaps.constructOverlapSets(new ArrayList<>(candidates),
                                                                  adapter::startsAfter,
                                                                  adapter::compareFirst,
                                                                  adapter::compareLast);
            int maximumOverlap = -1;
            for (Set<T> overlap : overlaps)
                maximumOverlap = Math.max(maximumOverlap, overlap.size());
            if (maximumOverlap < threshold)
                return new BucketSelection<>(maximumOverlap, null, 0);

            List<Bucket<T>> buckets = Overlaps.assignOverlapsIntoBuckets(threshold,
                                                                          policy.overlapInclusionMethod(),
                                                                          overlaps,
                                                                          (sets, start, end) ->
                                                                          new Bucket<>(this, sets, start, end));
            int estimatedRemainingTasks = 0;
            int matching = 0;
            Bucket<T> selected = null;
            for (Bucket<T> bucket : buckets)
            {
                if (bucket.maximumOverlap == maximumOverlap && policy.randomInt(++matching) == 0)
                    selected = bucket;
                estimatedRemainingTasks += bucket.maximumOverlap / threshold;
            }
            if (selected == null)
                throw new IllegalStateException("eligible UCS level produced no compaction bucket");
            return new BucketSelection<>(maximumOverlap, selected, estimatedRemainingTasks);
        }
    }

    private static final class BucketSelection<T>
    {
        private final int maximumOverlap;
        private final Bucket<T> bucket;
        private final int estimatedRemainingTasks;

        private BucketSelection(int maximumOverlap, Bucket<T> bucket, int estimatedRemainingTasks)
        {
            this.maximumOverlap = maximumOverlap;
            this.bucket = bucket;
            this.estimatedRemainingTasks = estimatedRemainingTasks;
        }
    }

    private static final class Bucket<T>
    {
        private final Level<T> level;
        private final List<T> allCandidatesNewestFirst;
        private final List<Set<T>> overlapSets;
        private final int maximumOverlap;

        private Bucket(Level<T> level, List<Set<T>> overlaps, int start, int end)
        {
            this.level = level;
            this.overlapSets = new ArrayList<>(overlaps.subList(start, end));
            Set<T> all = new HashSet<>();
            int maximumOverlap = 0;
            for (Set<T> overlap : overlapSets)
            {
                maximumOverlap = Math.max(maximumOverlap, overlap.size());
                all.addAll(overlap);
            }
            this.maximumOverlap = maximumOverlap;
            this.allCandidatesNewestFirst = new ArrayList<>(all);
        }

        private Collection<T> constructPick(CandidateAdapter<T> adapter, Policy policy)
        {
            allCandidatesNewestFirst.sort(Comparator.comparingLong(adapter::maximumTimestamp).reversed());
            int count = maximumOverlap;
            int maximum = Math.max(level.fanout, policy.maximumSSTablesToCompact());
            if (count <= level.fanout)
                return allCandidatesNewestFirst;
            if (count <= level.fanout * policy.fanout(level.index + 1) || maximum == level.fanout)
                return count <= maximum ? allCandidatesNewestFirst : Overlaps.pullLast(allCandidatesNewestFirst, maximum);

            int pickSize;
            int fanout = level.fanout;
            int nextStep = fanout;
            int index = level.index;
            int limit = Math.min(maximum, maximumOverlap);
            do
            {
                pickSize = nextStep;
                fanout = policy.fanout(++index);
                nextStep *= fanout;
            }
            while (nextStep <= limit);
            if (level.scalingParameter < 0)
                pickSize *= limit / pickSize;
            return overlapSets.size() == 1
                   ? Overlaps.pullLast(allCandidatesNewestFirst, pickSize)
                   : Overlaps.pullLastWithOverlapLimit(allCandidatesNewestFirst, overlapSets, pickSize);
        }
    }
}
