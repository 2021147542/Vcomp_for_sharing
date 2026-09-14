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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.cassandra.db.compaction.unified.Controller;

/** Implements descriptor-only merge, deduplication estimation, and output construction. */
public final class DefaultVirtualCompaction implements VCompPipeline.ModelMerger,
                                                       VCompPipeline.SketchMerger,
                                                       VCompPipeline.DeduplicationEstimator,
                                                       VCompPipeline.VirtualOutputSplitter
{
    private final int kmvSamples;
    private final int rangeBuckets;
    private final long targetSSTBytes;
    private final VCompSSTSizeModel sizeModel;
    private final VCompOrderedPartitionLayout partitionLayout;
    private long nextOutputId;

    public DefaultVirtualCompaction()
    {
        this(VCompKmvSketch.DEFAULT_SAMPLES, VCompKmvSketch.DEFAULT_RANGE_BUCKETS);
    }

    public DefaultVirtualCompaction(int kmvSamples, int rangeBuckets)
    {
        this(kmvSamples, rangeBuckets, Long.MAX_VALUE, null);
    }

    public DefaultVirtualCompaction(int kmvSamples, int rangeBuckets,
                                    long targetSSTBytes, VCompSSTSizeModel sizeModel)
    {
        this(kmvSamples, rangeBuckets, targetSSTBytes, sizeModel, null);
    }

    public DefaultVirtualCompaction(int kmvSamples, int rangeBuckets,
                                    long targetSSTBytes, VCompSSTSizeModel sizeModel,
                                    VCompOrderedPartitionLayout partitionLayout)
    {
        if (kmvSamples <= 0)
            throw new IllegalArgumentException("KMV sample count must be positive");
        if (rangeBuckets <= 0)
            throw new IllegalArgumentException("range bucket count must be positive");
        this.kmvSamples = kmvSamples;
        this.rangeBuckets = rangeBuckets;
        this.targetSSTBytes = targetSSTBytes;
        this.sizeModel = sizeModel;
        this.partitionLayout = partitionLayout;
    }

    @Override
    public VCompLearnedModel merge(VCompPipeline.VirtualCompactionPlan plan, long estimatedUniqueKeys)
    {
        List<VCompPipeline.VirtualSSTable> inputs = inputSSTables(plan);
        if (estimatedUniqueKeys <= 0)
            throw new IllegalArgumentException("a non-empty compaction must estimate at least one unique key");
        return mergeModelsRangeAware(inputs, estimatedUniqueKeys);
    }

    @Override
    public VCompKmvSketch mergeAndDeduplicate(VCompPipeline.VirtualCompactionPlan plan)
    {
        List<SketchPiece> pieces = new ArrayList<>();
        for (VCompPipeline.VirtualSSTable input : inputSSTables(plan))
            pieces.add(new SketchPiece(input.sketch(), input.keyMin(), input.keyMax(), input.estimatedUniqueKeys()));
        return mergePieces(pieces, Long.MIN_VALUE, Long.MAX_VALUE, kmvSamples);
    }

    @Override
    public long estimateUniqueKeys(VCompPipeline.VirtualCompactionPlan plan,
                                   VCompPipeline.MergedSketch mergedSketch)
    {
        if (!(mergedSketch instanceof VCompKmvSketch))
            throw new IllegalArgumentException("DefaultVirtualCompaction requires a VCompKmvSketch");
        List<VCompPipeline.VirtualSSTable> inputs = inputSSTables(plan);
        long keyMin = Long.MAX_VALUE;
        long keyMax = Long.MIN_VALUE;
        for (VCompPipeline.VirtualSSTable input : inputs)
        {
            keyMin = Math.min(keyMin, input.keyMin());
            keyMax = Math.max(keyMax, input.keyMax());
        }
        long naiveEntries = sumEntries(inputs);
        long domainCapacity = inclusiveCardinality(keyMin, keyMax);
        long cap = Math.min(domainCapacity, naiveEntries);
        return estimateSketchCardinality((VCompKmvSketch) mergedSketch, cap, cap);
    }

    @Override
    public VCompPipeline.VirtualSortedRun split(VCompPipeline.VirtualCompactionPlan plan,
                                                VCompPipeline.MergedModel mergedModel,
                                                VCompPipeline.MergedSketch mergedSketch,
                                                long estimatedUniqueKeys)
    {
        if (!(mergedModel instanceof VCompLearnedModel))
            throw new IllegalArgumentException("DefaultVirtualCompaction requires a VCompLearnedModel");
        if (!(mergedSketch instanceof VCompKmvSketch))
            throw new IllegalArgumentException("DefaultVirtualCompaction requires a VCompKmvSketch");
        if (estimatedUniqueKeys <= 0)
            throw new IllegalArgumentException("a non-empty compaction must produce at least one key");

        List<VCompPipeline.VirtualSSTable> inputs = inputSSTables(plan);
        VCompLearnedModel model = (VCompLearnedModel) mergedModel;
        long naiveEntries = sumEntries(inputs);
        long inputBytes = sumBytes(inputs);
        long averageEntryBytes = Math.max(1, inputBytes / Math.max(1, naiveEntries));
        long maximumTimestamp = Long.MIN_VALUE;
        for (VCompPipeline.VirtualSSTable input : inputs)
            maximumTimestamp = Math.max(maximumTimestamp, input.maximumTimestamp());

        if (partitionLayout != null)
            return splitAtNativeShardBoundaries(plan, inputs, model, estimatedUniqueKeys,
                                                maximumTimestamp, averageEntryBytes);

        long entriesPerSST = targetSSTBytes == Long.MAX_VALUE || sizeModel == null
                             ? estimatedUniqueKeys : Math.max(1, sizeModel.maxEntries(targetSSTBytes));
        // Cassandra's supported one-partition path always emits one SST per run.
        // Retain count-safe splitting for direct unit coverage, but do not feed
        // its discrete projection back into later virtual compaction picks.
        if (entriesPerSST < estimatedUniqueKeys && model.discreteModel() == null)
            model = VCompDiscreteMerge.build(inputs, estimatedUniqueKeys,
                                             this::estimateUnionForRange).model;
        List<VCompPipeline.VirtualSSTable> outputs = new ArrayList<>();
        String runSuffix = Long.toString(nextOutputId++);
        for (long first = 0; first < estimatedUniqueKeys; )
        {
            long count = Math.min(entriesPerSST, estimatedUniqueKeys - first);
            VCompLearnedModel child = first == 0 && count == estimatedUniqueKeys
                                      ? model : model.slice(first, count);
            long keyMin = child.keyMin();
            long keyMax = child.keyMax();
            VCompKmvSketch childSketch = mergeSketches(inputs, keyMin, keyMax, kmvSamples);
            List<VCompKmvSketch.Range> ranges = outputRanges(inputs, keyMin, keyMax, count,
                                                                 child.discreteModel());
            long outputBytes = sizeModel == null ? saturatedMultiply(count, averageEntryBytes)
                                                     : sizeModel.estimate(count);
            String suffix = runSuffix + '-' + outputs.size();
            outputs.add(new VCompPipeline.VirtualSSTable("vsst-compaction-" + suffix,
                                                                keyMin, keyMax, count, outputBytes,
                                                                maximumTimestamp, child, childSketch, ranges));
            first += count;
        }
        return new VCompPipeline.VirtualSortedRun("run-compaction-" + runSuffix,
                                                  plan.outputLevel(), outputs);
    }

    /**
     * Mirror Cassandra UCS's sharded compaction writer: calculate its full-ring
     * shard count from combined density, then cut only at partition boundaries.
     */
    private VCompPipeline.VirtualSortedRun splitAtNativeShardBoundaries(
    VCompPipeline.VirtualCompactionPlan plan,
    List<VCompPipeline.VirtualSSTable> inputs,
    VCompLearnedModel model,
    long estimatedUniqueKeys,
    long maximumTimestamp,
    long averageEntryBytes)
    {
        if (targetSSTBytes <= 0 || targetSSTBytes == Long.MAX_VALUE || sizeModel == null)
            throw new IllegalStateException("ordered output sharding requires a finite target and size model");

        long inputMinimum = Long.MAX_VALUE;
        long inputMaximum = Long.MIN_VALUE;
        for (VCompPipeline.VirtualSSTable input : inputs)
        {
            inputMinimum = Math.min(inputMinimum, input.keyMin());
            inputMaximum = Math.max(inputMaximum, input.keyMax());
        }
        double combinedDensity = sumBytes(inputs) / partitionLayout.tokenCoverage(inputMinimum, inputMaximum);
        int shardCount = Controller.calculateNumShards(combinedDensity,
                                                       Controller.defaultMinSSTableSizeBytes(),
                                                       1,
                                                       targetSSTBytes,
                                                       0.333);

        List<PartitionSlice> slices = estimatePartitionSlices(inputs, model, estimatedUniqueKeys);
        List<VCompPipeline.VirtualSSTable> outputs = new ArrayList<>();
        String runSuffix = Long.toString(nextOutputId++);
        for (int start = 0; start < slices.size(); )
        {
            int shard = partitionLayout.shardIndex(slices.get(start).partition, shardCount);
            int end = start + 1;
            long count = slices.get(start).estimatedKeys;
            while (end < slices.size()
                   && partitionLayout.shardIndex(slices.get(end).partition, shardCount) == shard)
            {
                count = saturatedAdd(count, slices.get(end).estimatedKeys);
                end++;
            }
            if (count > 0)
            {
                long keyMin = slices.get(start).keyMin;
                long keyMax = slices.get(end - 1).keyMax;
                VCompLearnedModel child = model.sliceByKeyRange(keyMin, keyMax, count);
                VCompKmvSketch childSketch = mergeSketches(inputs, keyMin, keyMax, kmvSamples);
                List<VCompKmvSketch.Range> ranges = outputRanges(inputs, keyMin, keyMax, count, null);
                long outputBytes = sizeModel == null ? saturatedMultiply(count, averageEntryBytes)
                                                     : sizeModel.estimate(count);
                String suffix = runSuffix + '-' + outputs.size();
                outputs.add(new VCompPipeline.VirtualSSTable("vsst-compaction-" + suffix,
                                                              keyMin, keyMax, count, outputBytes,
                                                              maximumTimestamp, child, childSketch, ranges));
            }
            start = end;
        }
        if (outputs.isEmpty())
            throw new IllegalStateException("native shard splitting produced no vSST output");
        return new VCompPipeline.VirtualSortedRun("run-compaction-" + runSuffix,
                                                  plan.outputLevel(), outputs);
    }

    private List<PartitionSlice> estimatePartitionSlices(List<VCompPipeline.VirtualSSTable> inputs,
                                                          VCompLearnedModel model,
                                                          long totalKeys)
    {
        List<PartitionSlice> result = new ArrayList<>();
        double totalWeight = 0;
        for (VCompOrderedPartitionLayout.Partition partition : partitionLayout.partitions())
        {
            long minimum = Math.max(partition.minimum(), model.keyMin());
            long maximum = Math.min(partition.maximum(), model.keyMax());
            if (maximum < minimum)
                continue;
            long rangeEstimate = estimateUnionForRange(inputs, minimum, maximum);
            double modelMass = Math.max(0, model.predict(maximum) - model.predict(minimum));
            double weight = rangeEstimate > 0 ? rangeEstimate : modelMass;
            result.add(new PartitionSlice(partition, minimum, maximum, weight));
            totalWeight += weight;
        }
        if (!(totalWeight > 0))
            throw new IllegalStateException("ordered partitions have no estimated key mass");

        long assigned = 0;
        double cumulative = 0;
        for (int i = 0; i < result.size(); i++)
        {
            PartitionSlice slice = result.get(i);
            cumulative += slice.weight;
            long through = i + 1 == result.size()
                           ? totalKeys
                           : Math.min(totalKeys, (long) Math.floor(cumulative * totalKeys / totalWeight));
            slice.estimatedKeys = Math.max(0, through - assigned);
            assigned = through;
        }
        return result;
    }

    private static List<VCompPipeline.VirtualSSTable> inputSSTables(VCompPipeline.VirtualCompactionPlan plan)
    {
        Objects.requireNonNull(plan, "plan");
        List<VCompPipeline.VirtualSSTable> inputs = new ArrayList<>();
        for (VCompPipeline.VirtualSortedRun run : plan.inputs())
            inputs.addAll(run.sstables());
        if (inputs.isEmpty())
            throw new IllegalArgumentException("virtual compaction has no input vSSTs");
        for (VCompPipeline.VirtualSSTable input : inputs)
        {
            if (input.model() == null || input.model().isEmpty())
                throw new IllegalArgumentException("input vSST has no learned model: " + input.id());
            if (input.sketch() == null)
                throw new IllegalArgumentException("input vSST has no KMV sketch: " + input.id());
        }
        return inputs;
    }

    private VCompLearnedModel mergeModelsRangeAware(List<VCompPipeline.VirtualSSTable> inputs,
                                                     long totalUnique)
    {
        List<Long> breakpoints = new ArrayList<>();
        for (VCompPipeline.VirtualSSTable input : inputs)
        {
            breakpoints.add(input.keyMin());
            breakpoints.add(input.keyMax());
            for (VCompLearnedModel.Segment segment : input.model().segments())
            {
                breakpoints.add(segment.keyStart());
                breakpoints.add(segment.keyEnd());
            }
        }
        breakpoints.sort(Long::compare);
        List<Long> uniqueBreakpoints = new ArrayList<>(breakpoints.size());
        for (long breakpoint : breakpoints)
        {
            if (uniqueBreakpoints.isEmpty() || uniqueBreakpoints.get(uniqueBreakpoints.size() - 1) != breakpoint)
                uniqueBreakpoints.add(breakpoint);
        }

        if (uniqueBreakpoints.size() == 1)
        {
            long key = uniqueBreakpoints.get(0);
            return new VCompLearnedModel(Collections.singletonList(new VCompLearnedModel.Segment(key,
                                                                                                  key,
                                                                                                  0,
                                                                                                  totalUnique / 2.0)));
        }

        Map<VCompPipeline.VirtualSSTable, Integer> segmentIndexes = new HashMap<>();
        List<MutableSegment> merged = new ArrayList<>(uniqueBreakpoints.size() - 1);
        double rawTotal = 0;
        for (int i = 0; i + 1 < uniqueBreakpoints.size(); i++)
        {
            long keyStart = uniqueBreakpoints.get(i);
            long keyEnd = uniqueBreakpoints.get(i + 1);
            long keyMiddle = keyStart + (keyEnd - keyStart) / 2;
            double slopeSum = 0;
            List<VCompPipeline.VirtualSSTable> intervalInputs = new ArrayList<>();

            for (VCompPipeline.VirtualSSTable input : inputs)
            {
                if (keyMiddle < input.keyMin() || keyMiddle > input.keyMax())
                    continue;
                List<VCompLearnedModel.Segment> segments = input.model().segments();
                int segmentIndex = segmentIndexes.getOrDefault(input, 0);
                while (segmentIndex + 1 < segments.size() && segments.get(segmentIndex).keyEnd() < keyMiddle)
                    segmentIndex++;
                segmentIndexes.put(input, segmentIndex);
                VCompLearnedModel.Segment segment = segments.get(segmentIndex);
                if (keyMiddle < segment.keyStart() || keyMiddle > segment.keyEnd())
                    continue;
                slopeSum += Math.max(0, segment.slope());
                intervalInputs.add(input);
            }

            long intervalEntries = estimateUnionForRange(intervalInputs, keyStart, keyEnd);
            rawTotal += intervalEntries;
            double width = Math.max(1.0, (double) keyEnd - keyStart);
            double naiveMass = Math.max(0, slopeSum * width);
            double slope = 0;
            if (intervalEntries > 0)
                slope = slopeSum > 0 && naiveMass > 0 ? slopeSum * intervalEntries / naiveMass
                                                      : intervalEntries / width;
            merged.add(new MutableSegment(keyStart, keyEnd, slope));
        }

        if (totalUnique > 0 && rawTotal > 0)
        {
            double scale = totalUnique / rawTotal;
            for (MutableSegment segment : merged)
                segment.slope *= scale;
        }
        else if (totalUnique > 0 && !merged.isEmpty())
        {
            double width = Math.max(1.0,
                                    (double) uniqueBreakpoints.get(uniqueBreakpoints.size() - 1)
                                    - uniqueBreakpoints.get(0));
            double slope = totalUnique / width;
            for (MutableSegment segment : merged)
                segment.slope = slope;
        }

        List<VCompLearnedModel.Segment> result = new ArrayList<>(merged.size());
        double cumulativePosition = 0;
        for (MutableSegment segment : merged)
        {
            double intercept = cumulativePosition - segment.slope * segment.keyStart;
            VCompLearnedModel.Segment next = new VCompLearnedModel.Segment(segment.keyStart,
                                                                           segment.keyEnd,
                                                                           segment.slope,
                                                                           intercept);
            if (!result.isEmpty() && equivalent(result.get(result.size() - 1), next))
            {
                VCompLearnedModel.Segment previous = result.remove(result.size() - 1);
                next = new VCompLearnedModel.Segment(previous.keyStart(),
                                                     next.keyEnd(),
                                                     previous.slope(),
                                                     previous.intercept());
            }
            result.add(next);
            cumulativePosition += segment.slope * ((double) segment.keyEnd - segment.keyStart);
        }
        return new VCompLearnedModel(result);
    }

    private static boolean equivalent(VCompLearnedModel.Segment left, VCompLearnedModel.Segment right)
    {
        return Math.abs(left.slope() - right.slope()) < 1e-12
               && Math.abs(left.intercept() - right.intercept()) < 1e-9;
    }

    long estimateUnionForRange(List<VCompPipeline.VirtualSSTable> inputs, long keyMin, long keyMax)
    {
        if (inputs.isEmpty() || keyMax < keyMin)
            return 0;

        List<SketchPiece> pieces = sketchPieces(inputs, keyMin, keyMax);
        if (pieces.isEmpty())
            return 0;

        long inputEntriesCap = 0;
        double densityEstimate = 0;
        for (SketchPiece piece : pieces)
        {
            inputEntriesCap = saturatedAdd(inputEntriesCap, piece.entries);
            long overlapMin = Math.max(piece.keyMin, keyMin);
            long overlapMax = Math.min(piece.keyMax, keyMax);
            if (overlapMax >= overlapMin && piece.entries > 0)
            {
                double pieceSpan = (double) piece.keyMax - piece.keyMin + 1;
                double overlapSpan = (double) overlapMax - overlapMin + 1;
                densityEstimate += piece.entries * overlapSpan / pieceSpan;
            }
        }

        inputEntriesCap = Math.min(inputEntriesCap, inclusiveCardinality(keyMin, keyMax));
        List<VCompKmvSketch.Sample> sampled = samplesUnderCommonTheta(pieces, keyMin, keyMax);
        long sampledUnique = uniqueSampleCount(sampled);
        double inRange = Math.min(densityEstimate, inputEntriesCap);
        boolean complete = true;
        long thetaHash = VCompKmvSketch.MAX_UNSIGNED_LONG;
        for (SketchPiece piece : pieces)
        {
            complete &= piece.sketch.isComplete();
            if (Long.compareUnsigned(piece.sketch.thetaHash(), thetaHash) < 0)
                thetaHash = piece.sketch.thetaHash();
        }
        return estimateCommonThetaCardinality(sampledUnique,
                                               thetaHash,
                                               complete,
                                               inputEntriesCap,
                                               Math.min(inputEntriesCap, (long) Math.ceil(inRange)));
    }

    private List<VCompKmvSketch.Range> outputRanges(List<VCompPipeline.VirtualSSTable> inputs,
                                                     long keyMin,
                                                     long keyMax,
                                                     long outputEntries,
                                                     VCompDiscreteCdf model)
    {
        int count = (int) Math.min(rangeBuckets, outputEntries);
        if (count == 0)
            return Collections.emptyList();
        int samplesPerRange = Math.max(1, kmvSamples / count);
        double span = (double) keyMax - keyMin + 1;
        List<VCompKmvSketch.Range> ranges = new ArrayList<>(count);
        for (int i = 0; i < count; i++)
        {
            long rangeMin = keyMin + (long) Math.floor(span * i / count);
            long rangeMax;
            if (i + 1 == count)
            {
                rangeMax = keyMax;
            }
            else
            {
                long exclusiveEnd = keyMin + (long) Math.floor(span * (i + 1) / count);
                rangeMax = Math.max(rangeMin, exclusiveEnd - 1);
            }
            long rawEntries = estimateUnionForRange(inputs, rangeMin, rangeMax);
            long entries = model == null ? rawEntries
                           : model.countThrough(rangeMax) - model.countLessThan(rangeMin);
            VCompKmvSketch sketch = mergeSketches(inputs, rangeMin, rangeMax, samplesPerRange);
            if (entries > 0 || !sketch.samples().isEmpty())
                ranges.add(new VCompKmvSketch.Range(rangeMin, rangeMax, entries,
                                                    rawEntries, model != null, sketch));
        }
        return Collections.unmodifiableList(ranges);
    }

    private VCompKmvSketch mergeSketches(List<VCompPipeline.VirtualSSTable> inputs,
                                          long keyMin,
                                          long keyMax,
                                          int maxSamples)
    {
        return mergePieces(sketchPieces(inputs, keyMin, keyMax), keyMin, keyMax, maxSamples);
    }

    private static List<SketchPiece> sketchPieces(List<VCompPipeline.VirtualSSTable> inputs,
                                                   long keyMin,
                                                   long keyMax)
    {
        List<SketchPiece> pieces = new ArrayList<>();
        for (VCompPipeline.VirtualSSTable input : inputs)
        {
            if (input.keyMax() < keyMin || input.keyMin() > keyMax)
                continue;
            if (!input.rangeSketches().isEmpty())
            {
                for (VCompKmvSketch.Range range : input.rangeSketches())
                {
                    if (range.keyMax() >= keyMin && range.keyMin() <= keyMax)
                        pieces.add(new SketchPiece(range.sketch(),
                                                   range.keyMin(),
                                                   range.keyMax(),
                                           range.entriesAreModeled() ? range.rawEstimatedEntries()
                                                                     : range.entryCount()));
                }
            }
            else
            {
                pieces.add(new SketchPiece(input.sketch(),
                                           input.keyMin(),
                                           input.keyMax(),
                                           input.estimatedUniqueKeys()));
            }
        }
        return pieces;
    }

    private static VCompKmvSketch mergePieces(List<SketchPiece> pieces,
                                               long keyMin,
                                               long keyMax,
                                               int maxSamples)
    {
        if (pieces.isEmpty())
            return new VCompKmvSketch(Collections.emptyList(), VCompKmvSketch.MAX_UNSIGNED_LONG, true);

        boolean complete = true;
        long thetaHash = VCompKmvSketch.MAX_UNSIGNED_LONG;
        for (SketchPiece piece : pieces)
        {
            complete &= piece.sketch.isComplete();
            if (Long.compareUnsigned(piece.sketch.thetaHash(), thetaHash) < 0)
                thetaHash = piece.sketch.thetaHash();
        }

        Map<Long, VCompKmvSketch.Sample> byKey = new LinkedHashMap<>();
        for (SketchPiece piece : pieces)
        {
            for (VCompKmvSketch.Sample sample : piece.sketch.samples())
            {
                if (sample.key() >= keyMin
                    && sample.key() <= keyMax
                    && Long.compareUnsigned(sample.hash(), thetaHash) <= 0)
                    byKey.putIfAbsent(sample.key(), sample);
            }
        }
        List<VCompKmvSketch.Sample> samples = new ArrayList<>(byKey.values());
        samples.sort(VCompKmvSketch.SAMPLE_ORDER);
        boolean trimmed = maxSamples > 0 && samples.size() > maxSamples;
        if (trimmed)
        {
            samples = new ArrayList<>(samples.subList(0, maxSamples));
            complete = false;
            thetaHash = samples.get(samples.size() - 1).hash();
        }
        else if (complete)
        {
            thetaHash = VCompKmvSketch.MAX_UNSIGNED_LONG;
        }
        return new VCompKmvSketch(samples, thetaHash, complete);
    }

    /** Paper estimator: estimate the absolute union cardinality from m/theta. */
    private static long estimateSketchCardinality(VCompKmvSketch sketch, long cap, long fallback)
    {
        return estimateCommonThetaCardinality(sketch.samples().size(),
                                               sketch.thetaHash(),
                                               sketch.isComplete(),
                                               cap,
                                               fallback);
    }

    private static long estimateCommonThetaCardinality(long distinctSamples,
                                                        long thetaHash,
                                                        boolean complete,
                                                        long cap,
                                                        long fallback)
    {
        if (cap <= 0)
            return 0;
        if (complete)
            return Math.min(cap, distinctSamples);
        if (distinctSamples == 0)
            return Math.min(cap, fallback);
        double theta = unsignedHashProbability(thetaHash);
        if (!(theta > 0))
            return Math.min(cap, fallback);
        double estimate = distinctSamples / theta;
        if (estimate >= cap)
            return cap;
        return Math.max(1, (long) Math.ceil(estimate));
    }

    private static double unsignedHashProbability(long hash)
    {
        double unsigned = (double) (hash & Long.MAX_VALUE);
        if (hash < 0)
            unsigned += 0x1.0p63;
        return Math.scalb(unsigned + 1.0, -64);
    }

    private static List<VCompKmvSketch.Sample> samplesUnderCommonTheta(List<SketchPiece> pieces,
                                                                      long keyMin,
                                                                      long keyMax)
    {
        long theta = VCompKmvSketch.MAX_UNSIGNED_LONG;
        for (SketchPiece piece : pieces)
            if (Long.compareUnsigned(piece.sketch.thetaHash(), theta) < 0) theta = piece.sketch.thetaHash();
        List<VCompKmvSketch.Sample> result = new ArrayList<>();
        for (SketchPiece piece : pieces)
            for (VCompKmvSketch.Sample sample : piece.sketch.samples())
                if (sample.key() >= keyMin && sample.key() <= keyMax
                    && Long.compareUnsigned(sample.hash(), theta) <= 0) result.add(sample);
        return result;
    }

    private static long uniqueSampleCount(List<VCompKmvSketch.Sample> samples)
    {
        Map<Long, Boolean> keys = new HashMap<>();
        for (VCompKmvSketch.Sample sample : samples) keys.put(sample.key(), Boolean.TRUE);
        return keys.size();
    }

    private static long sumEntries(List<VCompPipeline.VirtualSSTable> inputs)
    {
        long result = 0;
        for (VCompPipeline.VirtualSSTable input : inputs)
            result = saturatedAdd(result, input.estimatedUniqueKeys());
        return result;
    }

    private static long sumBytes(List<VCompPipeline.VirtualSSTable> inputs)
    {
        long result = 0;
        for (VCompPipeline.VirtualSSTable input : inputs)
            result = saturatedAdd(result, input.estimatedBytes());
        return result;
    }

    private static long saturatedAdd(long left, long right)
    {
        if (right > Long.MAX_VALUE - left)
            return Long.MAX_VALUE;
        return left + right;
    }

    private static long saturatedMultiply(long left, long right)
    {
        if (left != 0 && right > Long.MAX_VALUE / left)
            return Long.MAX_VALUE;
        return left * right;
    }

    private static long inclusiveCardinality(long minimum, long maximum)
    {
        long difference = maximum - minimum;
        return difference == Long.MAX_VALUE ? Long.MAX_VALUE : difference + 1;
    }

    private static final class MutableSegment
    {
        private final long keyStart;
        private final long keyEnd;
        private double slope;

        private MutableSegment(long keyStart, long keyEnd, double slope)
        {
            this.keyStart = keyStart;
            this.keyEnd = keyEnd;
            this.slope = slope;
        }
    }

    private static final class SketchPiece
    {
        private final VCompKmvSketch sketch;
        private final long keyMin;
        private final long keyMax;
        private final long entries;

        private SketchPiece(VCompKmvSketch sketch, long keyMin, long keyMax, long entries)
        {
            this.sketch = sketch;
            this.keyMin = keyMin;
            this.keyMax = keyMax;
            this.entries = entries;
        }
    }

    private static final class PartitionSlice
    {
        private final VCompOrderedPartitionLayout.Partition partition;
        private final long keyMin;
        private final long keyMax;
        private final double weight;
        private long estimatedKeys;

        private PartitionSlice(VCompOrderedPartitionLayout.Partition partition,
                               long keyMin, long keyMax, double weight)
        {
            this.partition = partition;
            this.keyMin = keyMin;
            this.keyMax = keyMax;
            this.weight = weight;
        }
    }
}
