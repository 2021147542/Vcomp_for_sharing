/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.cassandra.db.compaction.vcomp;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

/** Builds the integer count/select certificate for a descriptor-only merge. */
final class VCompDiscreteMerge
{
    interface RangeEstimator
    {
        long estimate(List<VCompPipeline.VirtualSSTable> inputs, long keyMin, long keyMax);
    }

    static Result build(List<VCompPipeline.VirtualSSTable> inputs,
                        long requested,
                        RangeEstimator estimator)
    {
        if (inputs == null || estimator == null)
            throw new NullPointerException();
        List<VCompPipeline.VirtualSSTable> nonempty = new ArrayList<>();
        TreeSet<Long> starts = new TreeSet<>();
        TreeSet<Long> witnesses = new TreeSet<>();
        long inputSum = 0;
        long globalMax = -1;
        for (VCompPipeline.VirtualSSTable input : inputs)
        {
            if (input == null)
                throw new IllegalArgumentException("null discrete-merge input");
            inputSum = saturatedAdd(inputSum, input.estimatedUniqueKeys());
            nonempty.add(input);
            starts.add(input.keyMin());
            if (input.keyMax() != Long.MAX_VALUE) starts.add(input.keyMax() + 1);
            globalMax = Math.max(globalMax, input.keyMax());
            witnesses.add(input.keyMin());
            witnesses.add(input.keyMax());
            for (VCompKmvSketch.Sample sample : input.sketch().samples()) witnesses.add(sample.key());
            for (VCompKmvSketch.Range range : input.rangeSketches())
            {
                starts.add(range.keyMin());
                if (range.keyMax() != Long.MAX_VALUE) starts.add(range.keyMax() + 1);
                for (VCompKmvSketch.Sample sample : range.sketch().samples()) witnesses.add(sample.key());
            }
            if (input.model().discreteModel() == null)
            {
                for (VCompLearnedModel.Segment segment : input.model().segments())
                {
                    long begin = Math.max(segment.keyStart(), input.keyMin());
                    long end = Math.min(segment.keyEnd(), input.keyMax());
                    if (begin <= end)
                    {
                        starts.add(begin);
                        if (end != Long.MAX_VALUE) starts.add(end + 1);
                    }
                }
            }
        }
        List<Long> edges = new ArrayList<>(starts);
        List<Base> bases = new ArrayList<>();
        BigInteger totalCapacity = BigInteger.ZERO;
        for (int i = 0; i < edges.size(); i++)
        {
            long begin = edges.get(i);
            if (begin > globalMax) break;
            long end = i + 1 < edges.size() ? edges.get(i + 1) - 1 : globalMax;
            List<VCompPipeline.VirtualSSTable> active = new ArrayList<>();
            for (VCompPipeline.VirtualSSTable input : nonempty)
            {
                if (input.keyMin() <= begin && input.keyMax() >= begin) active.add(input);
            }
            if (active.isEmpty()) continue;
            totalCapacity = totalCapacity.add(span(begin, end));
            bases.add(new Base(begin, end, estimator.estimate(active, begin, end)));
        }
        if (BigInteger.valueOf(witnesses.size()).compareTo(totalCapacity) > 0)
            throw new IllegalArgumentException("discrete witnesses exceed supported capacity");
        long capacity = totalCapacity.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0
                        ? Long.MAX_VALUE : totalCapacity.longValueExact();
        long target = Math.max(witnesses.size(), Math.min(requested, capacity));
        if (target > inputSum)
            throw new IllegalArgumentException("projected count exceeds input count");

        List<Long> witnessList = new ArrayList<>(witnesses);
        List<Part> parts = new ArrayList<>();
        int witnessIndex = 0;
        for (Base base : bases)
        {
            long begin = base.minimum;
            double baseSpan = Math.max(1.0, (double) base.maximum - base.minimum + 1);
            while (witnessIndex < witnessList.size() && witnessList.get(witnessIndex) < base.minimum)
                throw new IllegalArgumentException("witness outside supported partition");
            while (witnessIndex < witnessList.size() && witnessList.get(witnessIndex) <= base.maximum)
            {
                long key = witnessList.get(witnessIndex++);
                if (begin < key)
                    parts.add(new Part(begin, key - 1, base.weight * (key - begin) / baseSpan, false, 0));
                parts.add(new Part(key, key, 0, true, 1));
                if (key == Long.MAX_VALUE) break;
                begin = key + 1;
            }
            if (begin <= base.maximum && (parts.isEmpty() || !parts.get(parts.size() - 1).witness
                                          || parts.get(parts.size() - 1).maximum != base.maximum))
                parts.add(new Part(begin, base.maximum,
                                   base.weight * ((double) base.maximum - begin + 1) / baseSpan,
                                   false, 0));
        }
        if (witnessIndex != witnessList.size())
            throw new IllegalArgumentException("unassigned discrete witness");
        allocate(parts, target - witnesses.size());

        List<VCompDiscreteCdf.Interval> intervals = new ArrayList<>(parts.size());
        for (Part part : parts)
            intervals.add(new VCompDiscreteCdf.Interval(part.minimum, part.maximum, part.mass));
        VCompDiscreteCdf cdf = new VCompDiscreteCdf(intervals);
        if (cdf.count() != target)
            throw new IllegalStateException("discrete allocation count mismatch");
        List<VCompLearnedModel.Segment> segments = new ArrayList<>(bases.size());
        for (Base base : bases)
        {
            long yBegin = cdf.countLessThan(base.minimum);
            long yEnd = cdf.countLessThan(base.maximum);
            double slope = base.minimum == base.maximum ? 0
                           : (double) (yEnd - yBegin) / (base.maximum - base.minimum);
            segments.add(new VCompLearnedModel.Segment(base.minimum, base.maximum,
                                                       slope, yBegin - slope * base.minimum));
        }
        return new Result(new VCompLearnedModel(segments, cdf), target);
    }

    private static void allocate(List<Part> parts, long remaining)
    {
        if (remaining == 0) return;
        List<Part> active = new ArrayList<>();
        for (Part part : parts) if (!part.witness) active.add(part);
        while (remaining > 0)
        {
            if (active.isEmpty()) throw new IllegalArgumentException("insufficient free capacity");
            double weightSum = 0;
            for (Part part : active) weightSum += part.weight;
            if (!(weightSum > 0) || !Double.isFinite(weightSum))
            {
                weightSum = 0;
                for (Part part : active) { part.weight = capacity(part.minimum, part.maximum); weightSum += part.weight; }
            }
            List<Part> saturating = new ArrayList<>();
            BigInteger saturationMass = BigInteger.ZERO;
            for (Part part : active)
            {
                long capacity = capacity(part.minimum, part.maximum);
                double quota = (double) remaining * part.weight / weightSum;
                if (capacity <= remaining && quota >= capacity)
                {
                    saturating.add(part);
                    saturationMass = saturationMass.add(BigInteger.valueOf(capacity));
                }
            }
            if (!saturating.isEmpty() && saturationMass.compareTo(BigInteger.valueOf(remaining)) <= 0)
            {
                for (Part part : saturating) part.mass = capacity(part.minimum, part.maximum);
                remaining -= saturationMass.longValueExact();
                active.removeAll(saturating);
                continue;
            }

            long allocated = 0;
            List<Remainder> remainders = new ArrayList<>();
            for (Part part : active)
            {
                long capacity = capacity(part.minimum, part.maximum);
                double quota = Math.min(capacity, (double) remaining * part.weight / weightSum);
                part.mass = Math.min(capacity, (long) Math.floor(quota));
                allocated = saturatedAdd(allocated, part.mass);
                remainders.add(new Remainder(part, quota - part.mass));
            }
            if (allocated > remaining) throw new IllegalStateException("invalid discrete allocation");
            long residual = remaining - allocated;
            remainders.sort(Comparator.comparingDouble((Remainder r) -> r.fraction).reversed());
            for (Remainder remainder : remainders)
            {
                if (residual == 0) break;
                long available = capacity(remainder.part.minimum, remainder.part.maximum) - remainder.part.mass;
                long amount = Math.min(residual, available);
                remainder.part.mass += amount;
                residual -= amount;
            }
            if (residual != 0) throw new IllegalArgumentException("integer allocation residual is infeasible");
            remaining = 0;
        }
    }

    private static BigInteger span(long minimum, long maximum)
    {
        return BigInteger.valueOf(maximum).subtract(BigInteger.valueOf(minimum)).add(BigInteger.ONE);
    }

    private static long capacity(long minimum, long maximum)
    {
        BigInteger span = span(minimum, maximum);
        return span.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0 ? Long.MAX_VALUE : span.longValueExact();
    }

    private static long saturatedAdd(long left, long right)
    {
        return right > Long.MAX_VALUE - left ? Long.MAX_VALUE : left + right;
    }

    static final class Result
    {
        final VCompLearnedModel model;
        final long count;
        Result(VCompLearnedModel model, long count) { this.model = model; this.count = count; }
    }

    private static final class Base
    {
        final long minimum, maximum;
        final double weight;
        Base(long minimum, long maximum, double weight) { this.minimum = minimum; this.maximum = maximum; this.weight = weight; }
    }

    private static final class Part
    {
        final long minimum, maximum;
        double weight;
        final boolean witness;
        long mass;
        Part(long minimum, long maximum, double weight, boolean witness, long mass)
        { this.minimum = minimum; this.maximum = maximum; this.weight = weight; this.witness = witness; this.mass = mass; }
    }

    private static final class Remainder
    {
        final Part part;
        final double fraction;
        Remainder(Part part, double fraction) { this.part = part; this.fraction = fraction; }
    }
}
