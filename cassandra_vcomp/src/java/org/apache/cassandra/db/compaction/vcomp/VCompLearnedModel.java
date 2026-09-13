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
import java.util.List;

/** A piecewise-linear approximation of {@code rank(key)}. */
public final class VCompLearnedModel implements VCompPipeline.MergedModel
{
    private static final double MIN_SLOPE = 1e-15;

    private final List<Segment> segments;
    private final VCompDiscreteCdf discrete;

    VCompLearnedModel(List<Segment> segments)
    {
        this(segments, null);
    }

    VCompLearnedModel(List<Segment> segments, VCompDiscreteCdf discrete)
    {
        if (segments == null)
            throw new NullPointerException("segments");
        ArrayList<Segment> copy = new ArrayList<>(segments.size());
        Segment previous = null;
        for (Segment segment : segments)
        {
            if (segment == null)
                throw new NullPointerException("segment");
            if (segment.keyStart < 0 || segment.keyEnd < segment.keyStart)
                throw new IllegalArgumentException("invalid learned-model segment key range");
            if (!Double.isFinite(segment.slope) || segment.slope < 0)
                throw new IllegalArgumentException("learned-model slope must be finite and non-negative");
            if (!Double.isFinite(segment.intercept))
                throw new IllegalArgumentException("learned-model intercept must be finite");
            if (previous != null && previous.keyEnd > segment.keyStart)
                throw new IllegalArgumentException("learned-model segments must be ordered and non-overlapping");
            copy.add(segment);
            previous = segment;
        }
        this.segments = Collections.unmodifiableList(copy);
        this.discrete = discrete;
        if (discrete != null && !discrete.isEmpty() && !copy.isEmpty()
            && (discrete.select(0) != copy.get(0).keyStart()
                || discrete.select(discrete.count() - 1) != copy.get(copy.size() - 1).keyEnd()))
            throw new IllegalArgumentException("discrete and learned-model ranges differ");
    }

    /**
     * Build a model using the shrinking-cone greedy fit used by the Pebble prototype.
     * Keys must be distinct, non-negative, and sorted in ascending order.
     */
    public static VCompLearnedModel greedyFit(long[] sortedKeys, double errorBound)
    {
        if (!Double.isFinite(errorBound) || errorBound < 0)
            throw new IllegalArgumentException("model error bound must be finite and non-negative");

        validateKeys(sortedKeys);
        List<Segment> segments = new ArrayList<>();
        for (int segmentStart = 0; segmentStart < sortedKeys.length; )
        {
            if (segmentStart == sortedKeys.length - 1)
            {
                long key = sortedKeys[segmentStart];
                segments.add(new Segment(key, key, 0, segmentStart));
                break;
            }

            double x0 = sortedKeys[segmentStart];
            double y0 = segmentStart;
            double slopeLow = Double.NEGATIVE_INFINITY;
            double slopeHigh = Double.POSITIVE_INFINITY;
            int segmentEnd = segmentStart;

            for (int i = segmentStart + 1; i < sortedKeys.length; i++)
            {
                double dx = sortedKeys[i] - x0;
                double dy = i - y0;
                double newSlopeLow = (dy - errorBound) / dx;
                double newSlopeHigh = (dy + errorBound) / dx;
                if (newSlopeLow > slopeHigh || newSlopeHigh < slopeLow)
                    break;

                slopeLow = Math.max(slopeLow, newSlopeLow);
                slopeHigh = Math.min(slopeHigh, newSlopeHigh);
                segmentEnd = i;
            }

            double slope;
            if (Double.isInfinite(slopeLow) && Double.isInfinite(slopeHigh))
                slope = 0;
            else if (Double.isInfinite(slopeLow))
                slope = slopeHigh;
            else if (Double.isInfinite(slopeHigh))
                slope = slopeLow;
            else
                slope = (slopeLow + slopeHigh) / 2;

            segments.add(new Segment(sortedKeys[segmentStart],
                                     sortedKeys[segmentEnd],
                                     slope,
                                     y0 - slope * x0));
            segmentStart = segmentEnd + 1;
        }
        return new VCompLearnedModel(segments);
    }

    public boolean isEmpty()
    {
        return segments.isEmpty();
    }

    public List<Segment> segments()
    {
        return segments;
    }

    public VCompDiscreteCdf discreteModel()
    {
        return discrete;
    }

    public long keyMin()
    {
        if (segments.isEmpty())
            throw new IllegalStateException("empty model has no minimum key");
        return segments.get(0).keyStart();
    }

    public long keyMax()
    {
        if (segments.isEmpty())
            throw new IllegalStateException("empty model has no maximum key");
        return segments.get(segments.size() - 1).keyEnd();
    }

    /** Estimate the zero-based rank of a key, using flat CDF semantics between segments. */
    public double predict(long key)
    {
        if (discrete != null)
            return discrete.countLessThan(key);
        if (segments.isEmpty())
            return 0;

        int low = 0;
        int high = segments.size();
        while (low < high)
        {
            int middle = (low + high) >>> 1;
            if (segments.get(middle).keyEnd() >= key)
                high = middle;
            else
                low = middle + 1;
        }

        if (low == segments.size())
        {
            Segment last = segments.get(segments.size() - 1);
            return last.evaluate(last.keyEnd());
        }

        Segment segment = segments.get(low);
        if (key < segment.keyStart())
        {
            if (low == 0)
                return 0;
            Segment previous = segments.get(low - 1);
            return previous.evaluate(previous.keyEnd());
        }
        return segment.evaluate(key);
    }

    /** Estimate the key at a zero-based rank. Used later by final materialization. */
    public long inverse(double position)
    {
        if (segments.isEmpty())
            throw new IllegalStateException("cannot invert an empty model");
        if (discrete != null && !discrete.isEmpty())
        {
            long rank = Math.max(0, Math.round(position));
            return discrete.select(Math.min(rank, discrete.count() - 1));
        }

        Segment best = segments.get(0);
        double bestDistance = Double.POSITIVE_INFINITY;
        for (Segment segment : segments)
        {
            double positionStart = segment.evaluate(segment.keyStart());
            double positionEnd = segment.evaluate(segment.keyEnd());
            double rangeLow = Math.min(positionStart, positionEnd);
            double rangeHigh = Math.max(positionStart, positionEnd);
            if (position >= rangeLow && position <= rangeHigh)
            {
                best = segment;
                break;
            }

            double distance = position < rangeLow ? rangeLow - position : position - rangeHigh;
            if (distance < bestDistance)
            {
                bestDistance = distance;
                best = segment;
            }
        }

        if (Math.abs(best.slope()) < MIN_SLOPE)
            return best.keyStart() + (best.keyEnd() - best.keyStart()) / 2;

        double predicted = (position - best.intercept()) / best.slope();
        predicted = Math.max(predicted, best.keyStart());
        predicted = Math.min(predicted, best.keyEnd());
        return Math.round(predicted);
    }

    VCompLearnedModel slice(long firstRank, long count)
    {
        if (discrete == null)
            throw new IllegalStateException("cannot exactly slice a model without a discrete certificate");
        VCompDiscreteCdf child = discrete.slice(firstRank, count);
        long minimum = child.select(0);
        long maximum = child.select(count - 1);
        ArrayList<Segment> result = new ArrayList<>();
        for (Segment segment : segments)
        {
            if (segment.keyEnd() < minimum) continue;
            if (segment.keyStart() > maximum) break;
            result.add(new Segment(Math.max(segment.keyStart(), minimum),
                                   Math.min(segment.keyEnd(), maximum),
                                   segment.slope(),
                                   segment.intercept() - firstRank));
        }
        if (result.isEmpty())
            result.add(new Segment(minimum, maximum, 0, 0));
        return new VCompLearnedModel(result, child);
    }

    /**
     * Restrict a continuous rank model to an inclusive key range and rebase
     * its rank origin to zero.  This is deliberately a continuous operation:
     * output sharding must not introduce the post-paper discrete certificate.
     */
    VCompLearnedModel sliceByKeyRange(long minimum, long maximum, long estimatedCount)
    {
        if (minimum < keyMin() || maximum > keyMax() || maximum < minimum)
            throw new IllegalArgumentException("invalid continuous model slice");
        if (estimatedCount <= 0)
            throw new IllegalArgumentException("continuous model slice count must be positive");
        if (discrete != null)
            throw new IllegalStateException("continuous key-range slicing does not accept a discrete certificate");

        double origin = predict(minimum);
        double mass = Math.max(0, predict(maximum) - origin);
        double scale = mass > 0 ? estimatedCount / mass : 1.0;
        ArrayList<Segment> result = new ArrayList<>();
        for (Segment segment : segments)
        {
            if (segment.keyEnd() < minimum)
                continue;
            if (segment.keyStart() > maximum)
                break;
            long start = Math.max(segment.keyStart(), minimum);
            long end = Math.min(segment.keyEnd(), maximum);
            result.add(new Segment(start,
                                   end,
                                   segment.slope() * scale,
                                   (segment.intercept() - origin) * scale));
        }
        if (result.isEmpty())
            result.add(new Segment(minimum, maximum, 0, 0));
        else
        {
            Segment first = result.get(0);
            if (first.keyStart() != minimum)
                result.add(0, new Segment(minimum, first.keyStart(), 0, 0));
            Segment last = result.get(result.size() - 1);
            if (last.keyEnd() != maximum)
                result.add(new Segment(last.keyEnd(), maximum, 0, last.evaluate(last.keyEnd())));
        }
        return new VCompLearnedModel(result);
    }

    private static void validateKeys(long[] sortedKeys)
    {
        if (sortedKeys == null)
            throw new NullPointerException("sortedKeys");
        for (int i = 0; i < sortedKeys.length; i++)
        {
            if (sortedKeys[i] < 0)
                throw new IllegalArgumentException("VComp prototype key coordinates must be non-negative");
            if (i > 0 && sortedKeys[i - 1] >= sortedKeys[i])
                throw new IllegalArgumentException("VComp prototype key coordinates must be sorted and distinct");
        }
    }

    public static final class Segment
    {
        private final long keyStart;
        private final long keyEnd;
        private final double slope;
        private final double intercept;

        Segment(long keyStart, long keyEnd, double slope, double intercept)
        {
            this.keyStart = keyStart;
            this.keyEnd = keyEnd;
            this.slope = slope;
            this.intercept = intercept;
        }

        public long keyStart()
        {
            return keyStart;
        }

        public long keyEnd()
        {
            return keyEnd;
        }

        public double slope()
        {
            return slope;
        }

        public double intercept()
        {
            return intercept;
        }

        private double evaluate(long key)
        {
            return slope * key + intercept;
        }
    }
}
