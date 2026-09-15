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
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/** Bottom-K (KMV) samples used to estimate duplicate keys across virtual SSTables. */
public final class VCompKmvSketch implements VCompPipeline.MergedSketch
{
    public static final int DEFAULT_SAMPLES = 512;
    public static final int DEFAULT_RANGE_BUCKETS = 8;

    static final long MAX_UNSIGNED_LONG = -1L;
    static final Comparator<Sample> SAMPLE_ORDER = (left, right) ->
    {
        int hashComparison = Long.compareUnsigned(left.hash(), right.hash());
        return hashComparison != 0 ? hashComparison : Long.compare(left.key(), right.key());
    };

    private final List<Sample> samples;
    private final long thetaHash;
    private final boolean complete;

    VCompKmvSketch(List<Sample> samples, long thetaHash, boolean complete)
    {
        if (samples == null)
            throw new NullPointerException("samples");
        ArrayList<Sample> copy = new ArrayList<>(samples.size());
        Sample previous = null;
        for (Sample sample : samples)
        {
            if (sample == null)
                throw new NullPointerException("sample");
            if (sample.key < 0 || sample.hash != hash(sample.key))
                throw new IllegalArgumentException("invalid KMV sample");
            if (previous != null && SAMPLE_ORDER.compare(previous, sample) >= 0)
                throw new IllegalArgumentException("KMV samples must be strictly ordered");
            copy.add(sample);
            previous = sample;
        }
        if (complete && thetaHash != MAX_UNSIGNED_LONG)
            throw new IllegalArgumentException("a complete KMV must use the maximum theta hash");
        if (!complete && !copy.isEmpty()
            && Long.compareUnsigned(copy.get(copy.size() - 1).hash(), thetaHash) > 0)
            throw new IllegalArgumentException("an incomplete KMV contains a sample above theta");
        this.samples = Collections.unmodifiableList(copy);
        this.thetaHash = thetaHash;
        this.complete = complete;
    }

    public static VCompKmvSketch build(long[] sortedKeys, int maxSamples)
    {
        if (sortedKeys == null)
            throw new NullPointerException("sortedKeys");
        if (maxSamples <= 0)
            throw new IllegalArgumentException("KMV sample count must be positive");
        validateSortedKeys(sortedKeys);

        PriorityQueue<Sample> bottomK = new PriorityQueue<>(maxSamples, SAMPLE_ORDER.reversed());
        long previous = 0;
        boolean havePrevious = false;
        int uniqueCount = 0;
        for (long key : sortedKeys)
        {
            if (havePrevious && key == previous)
                continue;
            havePrevious = true;
            previous = key;
            uniqueCount++;

            Sample sample = new Sample(key, hash(key));
            if (bottomK.size() < maxSamples)
                bottomK.add(sample);
            else if (SAMPLE_ORDER.compare(sample, bottomK.element()) < 0)
            {
                bottomK.remove();
                bottomK.add(sample);
            }
        }

        List<Sample> samples = new ArrayList<>(bottomK);
        samples.sort(SAMPLE_ORDER);
        boolean complete = uniqueCount <= maxSamples;
        long thetaHash = !complete && !samples.isEmpty()
                         ? samples.get(samples.size() - 1).hash()
                         : MAX_UNSIGNED_LONG;
        return new VCompKmvSketch(samples, thetaHash, complete);
    }

    /** Divide the key sequence into equal-entry-count ranges and build a KMV per range. */
    public static List<Range> buildRanges(long[] sortedKeys, int maxSamples, int maxRanges)
    {
        if (sortedKeys == null)
            throw new NullPointerException("sortedKeys");
        if (maxSamples <= 0)
            throw new IllegalArgumentException("KMV sample count must be positive");
        if (maxRanges <= 0)
            throw new IllegalArgumentException("KMV range count must be positive");
        if (sortedKeys.length == 0)
            return Collections.emptyList();
        validateSortedKeys(sortedKeys);

        int rangeCount = Math.min(maxRanges, sortedKeys.length);
        int samplesPerRange = Math.max(1, maxSamples / rangeCount);
        List<Range> ranges = new ArrayList<>(rangeCount);
        for (int i = 0; i < rangeCount; i++)
        {
            int begin = sortedKeys.length * i / rangeCount;
            int end = sortedKeys.length * (i + 1) / rangeCount;
            long[] rangeKeys = new long[end - begin];
            System.arraycopy(sortedKeys, begin, rangeKeys, 0, rangeKeys.length);
            ranges.add(new Range(rangeKeys[0],
                                 rangeKeys[rangeKeys.length - 1],
                                 rangeKeys.length,
                                 build(rangeKeys, samplesPerRange)));
        }
        return Collections.unmodifiableList(ranges);
    }

    private static void validateSortedKeys(long[] sortedKeys)
    {
        for (int i = 0; i < sortedKeys.length; i++)
        {
            if (sortedKeys[i] < 0)
                throw new IllegalArgumentException("VComp prototype key coordinates must be non-negative");
            if (i > 0 && sortedKeys[i - 1] > sortedKeys[i])
                throw new IllegalArgumentException("KMV input keys must be sorted");
        }
    }

    public List<Sample> samples()
    {
        return samples;
    }

    public long thetaHash()
    {
        return thetaHash;
    }

    public boolean isComplete()
    {
        return complete;
    }

    static long hash(long key)
    {
        long value = key + 0x9e3779b97f4a7c15L;
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    public static final class Sample
    {
        private final long key;
        private final long hash;

        Sample(long key, long hash)
        {
            this.key = key;
            this.hash = hash;
        }

        public long key()
        {
            return key;
        }

        public long hash()
        {
            return hash;
        }
    }

    public static final class Range
    {
        private final long keyMin;
        private final long keyMax;
        private final long entryCount;
        private final long rawEstimatedEntries;
        private final boolean entriesAreModeled;
        private final VCompKmvSketch sketch;

        Range(long keyMin, long keyMax, long entryCount, VCompKmvSketch sketch)
        {
            this(keyMin, keyMax, entryCount, entryCount, false, sketch);
        }

        Range(long keyMin, long keyMax, long entryCount,
              long rawEstimatedEntries, boolean entriesAreModeled,
              VCompKmvSketch sketch)
        {
            if (keyMin < 0 || keyMax < keyMin)
                throw new IllegalArgumentException("invalid range-KMV key range");
            if (entryCount <= 0)
                throw new IllegalArgumentException("range-KMV entry count must be positive");
            if (entryCount > inclusiveCardinality(keyMin, keyMax))
                throw new IllegalArgumentException("range-KMV entry count exceeds its key domain");
            if (rawEstimatedEntries < 0)
                throw new IllegalArgumentException("raw range estimate must be non-negative");
            if (sketch == null)
                throw new NullPointerException("sketch");
            if (!entriesAreModeled && sketch.complete && sketch.samples.size() != entryCount)
                throw new IllegalArgumentException("complete range-KMV cardinality differs from entry count");
            for (Sample sample : sketch.samples)
            {
                if (sample.key < keyMin || sample.key > keyMax)
                    throw new IllegalArgumentException("range-KMV sample lies outside its key range");
            }
            this.keyMin = keyMin;
            this.keyMax = keyMax;
            this.entryCount = entryCount;
            this.rawEstimatedEntries = rawEstimatedEntries;
            this.entriesAreModeled = entriesAreModeled;
            this.sketch = sketch;
        }

        public long keyMin()
        {
            return keyMin;
        }

        public long keyMax()
        {
            return keyMax;
        }

        public long entryCount()
        {
            return entryCount;
        }

        public VCompKmvSketch sketch()
        {
            return sketch;
        }

        public long rawEstimatedEntries()
        {
            return rawEstimatedEntries;
        }

        public boolean entriesAreModeled()
        {
            return entriesAreModeled;
        }
    }

    private static long inclusiveCardinality(long minimum, long maximum)
    {
        long difference = maximum - minimum;
        return difference == Long.MAX_VALUE ? Long.MAX_VALUE : difference + 1;
    }
}
