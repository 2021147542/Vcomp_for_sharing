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

import java.util.Collections;
import java.util.List;

/** Builds the first vSST descriptor from the keys represented by one Cassandra flush. */
public final class DefaultFlushVirtualizer implements VCompPipeline.FlushVirtualizer
{
    public static final double DEFAULT_MODEL_ERROR = 8.0;

    private final double modelError;
    private final int kmvSamples;
    private final int kmvRangeBuckets;
    private final VCompSSTSizeModel sizeModel;

    public DefaultFlushVirtualizer()
    {
        this(DEFAULT_MODEL_ERROR,
             VCompKmvSketch.DEFAULT_SAMPLES,
             VCompKmvSketch.DEFAULT_RANGE_BUCKETS,
             null);
    }

    public DefaultFlushVirtualizer(double modelError, int kmvSamples, int kmvRangeBuckets)
    {
        this(modelError, kmvSamples, kmvRangeBuckets, null);
    }

    public DefaultFlushVirtualizer(double modelError, int kmvSamples, int kmvRangeBuckets,
                                   VCompSSTSizeModel sizeModel)
    {
        if (!Double.isFinite(modelError) || modelError < 0)
            throw new IllegalArgumentException("model error bound must be finite and non-negative");
        if (kmvSamples <= 0)
            throw new IllegalArgumentException("KMV sample count must be positive");
        if (kmvRangeBuckets <= 0)
            throw new IllegalArgumentException("KMV range count must be positive");
        this.modelError = modelError;
        this.kmvSamples = kmvSamples;
        this.kmvRangeBuckets = kmvRangeBuckets;
        this.sizeModel = sizeModel;
    }

    @Override
    public VCompPipeline.VirtualSortedRun virtualize(VCompPipeline.FlushBatch flush)
    {
        long[] keys = flush.keyCoordinates();
        if (keys.length == 0)
            throw new IllegalArgumentException("cannot virtualize an empty flush");

        VCompLearnedModel model = VCompLearnedModel.greedyFit(keys, modelError);
        VCompKmvSketch sketch = VCompKmvSketch.build(keys, kmvSamples);
        List<VCompKmvSketch.Range> rangeSketches = VCompKmvSketch.buildRanges(keys,
                                                                              kmvSamples,
                                                                              kmvRangeBuckets);
        long estimatedBytes = sizeModel == null ? flush.logicalBytes() : sizeModel.estimate(keys.length);
        VCompPipeline.VirtualSSTable sstable = new VCompPipeline.VirtualSSTable("vsst-" + flush.id(),
                                                                               keys[0],
                                                                               keys[keys.length - 1],
                                                                               keys.length,
                                                                               estimatedBytes,
                                                                               flush.maximumTimestamp(),
                                                                               model,
                                                                               sketch,
                                                                               rangeSketches);
        return new VCompPipeline.VirtualSortedRun("run-" + flush.id(),
                                                  0,
                                                  Collections.singletonList(sstable));
    }
}
