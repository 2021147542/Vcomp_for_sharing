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

import java.util.List;
import java.util.NoSuchElementException;
import java.util.PrimitiveIterator;

/**
 * Streams reconstructed keys from each final rank model. KMV samples are only
 * compaction metadata; even a complete sketch must not replace PLR inversion
 * with original-key replay during materialization (paper section 4.3).
 */
public final class VCompMaterializedKeyIterator implements PrimitiveIterator.OfLong
{
    private static final double MIN_SLOPE = 1e-15;

    private final VCompPipeline.VirtualSSTable descriptor;
    private final List<VCompLearnedModel.Segment> segments;
    private final VCompDiscreteCdf.Cursor discreteCursor;
    private long position;
    private int segmentIndex;
    private boolean havePrevious;
    private long previous;
    private boolean prepared;
    private boolean exhausted;
    private long next;
    private long emitted;

    public VCompMaterializedKeyIterator(VCompPipeline.VirtualSSTable descriptor)
    {
        if (descriptor == null)
            throw new NullPointerException("descriptor");
        if (descriptor.model() == null || descriptor.model().isEmpty())
            throw new IllegalArgumentException("descriptor must have a learned model");
        this.descriptor = descriptor;
        this.segments = descriptor.model().segments();
        if (descriptor.model().discreteModel() != null)
        {
            if (descriptor.model().discreteModel().count() != descriptor.estimatedUniqueKeys())
                throw new IllegalArgumentException("discrete certificate count differs from descriptor key count");
            discreteCursor = descriptor.model().discreteModel().cursor();
        }
        else
        {
            discreteCursor = null;
        }
    }

    @Override
    public boolean hasNext()
    {
        prepare();
        return !exhausted;
    }

    @Override
    public long nextLong()
    {
        prepare();
        if (exhausted)
            throw new NoSuchElementException();
        prepared = false;
        havePrevious = true;
        previous = next;
        return next;
    }

    private void prepare()
    {
        if (prepared || exhausted)
            return;

        if (discreteCursor != null)
        {
            if (!discreteCursor.hasNext())
            {
                if (emitted != descriptor.estimatedUniqueKeys())
                    throw new IllegalStateException("discrete materialization count mismatch");
                exhausted = true;
                return;
            }
            next = discreteCursor.nextLong();
            if (havePrevious && next <= previous)
                throw new IllegalStateException("discrete materialization emitted unordered keys");
            emitted++;
            prepared = true;
            return;
        }

        while (position < descriptor.estimatedUniqueKeys())
        {
            double rank = position++;
            while (segmentIndex + 1 < segments.size())
            {
                VCompLearnedModel.Segment segment = segments.get(segmentIndex);
                if (rank <= segment.slope() * segment.keyEnd() + segment.intercept())
                    break;
                segmentIndex++;
            }

            VCompLearnedModel.Segment segment = segments.get(segmentIndex);
            long key;
            if (Math.abs(segment.slope()) < MIN_SLOPE)
            {
                key = segment.keyStart() + (segment.keyEnd() - segment.keyStart()) / 2;
            }
            else
            {
                double predicted = (rank - segment.intercept()) / segment.slope();
                predicted = Math.max(predicted, segment.keyStart());
                predicted = Math.min(predicted, segment.keyEnd());
                key = Math.round(predicted);
            }

            key = Math.max(key, descriptor.keyMin());
            key = Math.min(key, descriptor.keyMax());
            if (havePrevious && key <= previous)
                key = previous == Long.MAX_VALUE ? Long.MAX_VALUE : previous + 1;
            key = Math.min(key, descriptor.keyMax());
            if (havePrevious && key == previous)
                continue;

            next = key;
            prepared = true;
            return;
        }
        exhausted = true;
    }
}
