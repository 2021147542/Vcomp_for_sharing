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
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.Test;

import org.apache.cassandra.db.compaction.UnifiedCompactionStrategy;
import org.apache.cassandra.utils.Overlaps;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class UnifiedCompactionPickerTest
{
    private static final UnifiedCompactionPicker.CandidateAdapter<Candidate> ADAPTER =
    new UnifiedCompactionPicker.CandidateAdapter<Candidate>()
    {
        public double density(Candidate candidate)
        {
            return candidate.density;
        }

        public int compareFirst(Candidate left, Candidate right)
        {
            return Long.compare(left.first, right.first);
        }

        public int compareLast(Candidate left, Candidate right)
        {
            return Long.compare(left.last, right.last);
        }

        public boolean startsAfter(Candidate left, Candidate right)
        {
            return left.first > right.last;
        }

        public long maximumTimestamp(Candidate candidate)
        {
            return candidate.timestamp;
        }
    };

    @Test
    public void capsLateTieredPickAndChoosesOldestInputs()
    {
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 5; i >= 0; --i)
            candidates.add(new Candidate("c" + i, 64, 0, 9, i));

        UnifiedCompactionPicker.Pick<Candidate> pick = UnifiedCompactionPicker.pick(candidates,
                                                                                    ADAPTER,
                                                                                    new TestPolicy(new int[]{ 2 }, 4),
                                                                                    50);

        assertEquals(0, pick.level());
        assertEquals(6, pick.overlap());
        assertEquals(Arrays.asList("c0", "c1", "c2", "c3"), ids(pick.inputs()));
    }

    @Test
    public void preservesNativePriorityWhenHigherOverlapLevelIsBelowItsThreshold()
    {
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < 4; ++i)
            candidates.add(new Candidate("l0-" + i, 64, 0, 9, i));
        for (int i = 0; i < 5; ++i)
            candidates.add(new Candidate("l1-" + i, 256, 0, 9, 10 + i));

        UnifiedCompactionPicker.Pick<Candidate> pick = UnifiedCompactionPicker.pick(candidates,
                                                                                    ADAPTER,
                                                                                    new TestPolicy(new int[]{ 2, 4 }, 32),
                                                                                    50);

        assertNull(pick);
    }

    private static List<String> ids(List<Candidate> candidates)
    {
        return candidates.stream().map(candidate -> candidate.id).collect(Collectors.toList());
    }

    private static final class Candidate
    {
        private final String id;
        private final long density;
        private final long first;
        private final long last;
        private final long timestamp;

        private Candidate(String id, long density, long first, long last, long timestamp)
        {
            this.id = id;
            this.density = density;
            this.first = first;
            this.last = last;
            this.timestamp = timestamp;
        }
    }

    private static final class TestPolicy implements UnifiedCompactionPicker.Policy
    {
        private final int[] scalingParameters;
        private final int maximumSSTablesToCompact;

        private TestPolicy(int[] scalingParameters, int maximumSSTablesToCompact)
        {
            this.scalingParameters = scalingParameters;
            this.maximumSSTablesToCompact = maximumSSTablesToCompact;
        }

        public int scalingParameter(int level)
        {
            return level < scalingParameters.length
                   ? scalingParameters[level]
                   : scalingParameters[scalingParameters.length - 1];
        }

        public int fanout(int level)
        {
            return UnifiedCompactionStrategy.fanoutFromScalingParameter(scalingParameter(level));
        }

        public int threshold(int level)
        {
            return UnifiedCompactionStrategy.thresholdFromScalingParameter(scalingParameter(level));
        }

        public double maximumLevelDensity(int level, double minimumDensity)
        {
            return Math.floor(minimumDensity * fanout(level));
        }

        public int maximumSSTablesToCompact()
        {
            return maximumSSTablesToCompact;
        }

        public Overlaps.InclusionMethod overlapInclusionMethod()
        {
            return Overlaps.InclusionMethod.TRANSITIVE;
        }

        public int randomInt(int bound)
        {
            return 0;
        }
    }
}
