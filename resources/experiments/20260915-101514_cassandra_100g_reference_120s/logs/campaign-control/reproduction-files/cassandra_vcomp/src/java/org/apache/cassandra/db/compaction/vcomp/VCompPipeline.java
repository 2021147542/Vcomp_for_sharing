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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Top-level orchestration for the experimental Cassandra VComp port.
 *
 * <p>The default pipeline is the standalone synthetic-load experiment path. It mirrors the
 * restricted single-partition behavior of Cassandra's tiered UCS picker, performs metadata-only
 * compactions, materializes final descriptors once, validates the generated components, and then
 * imports them. It is not registered as Cassandra's online flush/compaction strategy.</p>
 */
public final class VCompPipeline
{
    private static final int MAX_VIRTUAL_COMPACTIONS = 1_000_000;

    private final FlushVirtualizer flushVirtualizer;
    private final VirtualCompactionPlanner planner;
    private final ModelMerger modelMerger;
    private final SketchMerger sketchMerger;
    private final DeduplicationEstimator deduplicationEstimator;
    private final VirtualOutputSplitter outputSplitter;
    private final LayoutFreezer layoutFreezer;
    private final FinalMaterializer materializer;
    private final FinalStateInstaller installer;
    private final MaterializedStateVerifier verifier;

    public VCompPipeline(FlushVirtualizer flushVirtualizer,
                         VirtualCompactionPlanner planner,
                         ModelMerger modelMerger,
                         SketchMerger sketchMerger,
                         DeduplicationEstimator deduplicationEstimator,
                         VirtualOutputSplitter outputSplitter,
                         LayoutFreezer layoutFreezer,
                         FinalMaterializer materializer,
                         FinalStateInstaller installer,
                         MaterializedStateVerifier verifier)
    {
        this.flushVirtualizer = Objects.requireNonNull(flushVirtualizer, "flushVirtualizer");
        this.planner = Objects.requireNonNull(planner, "planner");
        this.modelMerger = Objects.requireNonNull(modelMerger, "modelMerger");
        this.sketchMerger = Objects.requireNonNull(sketchMerger, "sketchMerger");
        this.deduplicationEstimator = Objects.requireNonNull(deduplicationEstimator, "deduplicationEstimator");
        this.outputSplitter = Objects.requireNonNull(outputSplitter, "outputSplitter");
        this.layoutFreezer = Objects.requireNonNull(layoutFreezer, "layoutFreezer");
        this.materializer = Objects.requireNonNull(materializer, "materializer");
        this.installer = Objects.requireNonNull(installer, "installer");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
    }

    /** Build the complete restricted-mode descriptor pipeline with Cassandra T4 tiering. */
    public static VCompPipeline createDefault(long flushSizeBytes,
                                               FinalMaterializer materializer,
                                               FinalStateInstaller installer,
                                               MaterializedStateVerifier verifier)
    {
        return createDefault(flushSizeBytes, null, materializer, installer, verifier);
    }

    /** Build the ordered multi-partition path using native UCS candidate granularity. */
    public static VCompPipeline createDefault(long flushSizeBytes,
                                              long targetSSTBytes,
                                              VCompSSTSizeModel sizeModel,
                                              VCompOrderedPartitionLayout partitionLayout,
                                              FinalMaterializer materializer,
                                              FinalStateInstaller installer,
                                              MaterializedStateVerifier verifier)
    {
        return createDefault(flushSizeBytes,
                             flushSizeBytes,
                             targetSSTBytes,
                             sizeModel,
                             partitionLayout,
                             materializer,
                             installer,
                             verifier);
    }

    /**
     * Build the ordered multi-partition path with an independently measured
     * encoded flush size. The first argument controls flush cadence; the
     * second is the file-size metadata seen by the native UCS picker.
     */
    public static VCompPipeline createDefault(long flushSizeBytes,
                                              long pickerFlushSizeBytes,
                                              long targetSSTBytes,
                                              VCompSSTSizeModel sizeModel,
                                              VCompOrderedPartitionLayout partitionLayout,
                                              FinalMaterializer materializer,
                                              FinalStateInstaller installer,
                                              MaterializedStateVerifier verifier)
    {
        Objects.requireNonNull(sizeModel, "sizeModel");
        Objects.requireNonNull(partitionLayout, "partitionLayout");
        if (pickerFlushSizeBytes <= 0)
            throw new IllegalArgumentException("picker flush size must be positive");
        if (targetSSTBytes <= 0 || targetSSTBytes == Long.MAX_VALUE)
            throw new IllegalArgumentException("ordered multi-partition VComp requires a finite target SST size");
        DefaultVirtualCompaction compaction = new DefaultVirtualCompaction(VCompKmvSketch.DEFAULT_SAMPLES,
                                                                            VCompKmvSketch.DEFAULT_RANGE_BUCKETS,
                                                                            targetSSTBytes,
                                                                            sizeModel,
                                                                            partitionLayout);
        DefaultFlushVirtualizer virtualizer = new DefaultFlushVirtualizer(DefaultFlushVirtualizer.DEFAULT_MODEL_ERROR,
                                                                           VCompKmvSketch.DEFAULT_SAMPLES,
                                                                           VCompKmvSketch.DEFAULT_RANGE_BUCKETS,
                                                                           sizeModel);
        return new VCompPipeline(virtualizer,
                                 new VCompUcsPlanner(pickerFlushSizeBytes, partitionLayout),
                                 compaction,
                                 compaction,
                                 compaction,
                                 compaction,
                                 state -> new FrozenLayout(state.runs()),
                                 materializer,
                                 installer,
                                 verifier);
    }

    public static VCompPipeline createDefault(long flushSizeBytes,
                                              VCompSSTSizeModel sizeModel,
                                              FinalMaterializer materializer,
                                              FinalStateInstaller installer,
                                              MaterializedStateVerifier verifier)
    {
        // The restricted pipeline has exactly one Cassandra partition. A
        // native compaction writer cannot cut that partition at an arbitrary
        // clustering-key position, so the flush cadence must not double as an
        // output SSTable size target.
        return createDefault(flushSizeBytes, Long.MAX_VALUE, sizeModel,
                             materializer, installer, verifier);
    }

    /**
     * Builds the restricted-mode pipeline. The target must be
     * {@link Long#MAX_VALUE}, meaning that compaction output is never split
     * solely to meet a size goal. The restricted schema contains one
     * partition, and Cassandra's native UCS writer cannot split one oversized
     * partition at an SST boundary.
     */
    public static VCompPipeline createDefault(long flushSizeBytes,
                                              long targetSSTBytes,
                                              VCompSSTSizeModel sizeModel,
                                              FinalMaterializer materializer,
                                              FinalStateInstaller installer,
                                              MaterializedStateVerifier verifier)
    {
        if (targetSSTBytes != Long.MAX_VALUE)
            throw new IllegalArgumentException("single-partition VComp requires partition-atomic output; "
                                               + "targetSSTBytes must be Long.MAX_VALUE");
        DefaultVirtualCompaction compaction = sizeModel == null
                                              ? new DefaultVirtualCompaction()
                                              : new DefaultVirtualCompaction(VCompKmvSketch.DEFAULT_SAMPLES,
                                                                                   VCompKmvSketch.DEFAULT_RANGE_BUCKETS,
                                                                                   targetSSTBytes, sizeModel);
        DefaultFlushVirtualizer virtualizer = sizeModel == null
                                              ? new DefaultFlushVirtualizer()
                                              : new DefaultFlushVirtualizer(DefaultFlushVirtualizer.DEFAULT_MODEL_ERROR,
                                                                            VCompKmvSketch.DEFAULT_SAMPLES,
                                                                            VCompKmvSketch.DEFAULT_RANGE_BUCKETS,
                                                                            sizeModel);
        return new VCompPipeline(virtualizer,
                                 new VCompUcsPlanner(flushSizeBytes),
                                 compaction,
                                 compaction,
                                 compaction,
                                 compaction,
                                 state -> new FrozenLayout(state.runs()),
                                 materializer,
                                 installer,
                                 verifier);
    }

    /** Execute the complete load-time VComp pipeline. */
    public Result execute(Request request) throws Exception
    {
        Objects.requireNonNull(request, "request");

        validateRestrictedMode(request.constraints());
        VirtualLoadResult loadResult = runVirtualLoad(request.loadSource());
        VirtualState state = loadResult.state;
        FrozenLayout layout = freezeFinalLayout(state);
        MaterializedState materialized = materializeFinalSSTables(layout);
        verifyMaterializedState(layout, materialized);
        installFinalState(materialized);

        return new Result(layout, materialized, loadResult.compactionCount);
    }

    /**
     * The first port supports only a deliberately KV-like Cassandra schema and workload.
     * Supporting general Cassandra reconciliation semantics is a later phase.
     */
    private void validateRestrictedMode(ExecutionConstraints constraints)
    {
        Objects.requireNonNull(constraints, "constraints");
        if (constraints.nodeCount() != 1)
            throw new IllegalArgumentException("VComp prototype requires exactly one Cassandra node");
        if (constraints.partitionKeyColumnCount() != 1 || constraints.distinctPartitionKeyCount() <= 0)
            throw new IllegalArgumentException("VComp prototype requires one or more ordered partition keys");
        if (constraints.clusteringColumnCount() != 1)
            throw new IllegalArgumentException("VComp prototype requires one clustering column as the KV key");
        if (constraints.regularColumnCount() != 1)
            throw new IllegalArgumentException("VComp prototype requires exactly one regular value column");
        if (constraints.hasStaticColumns())
            throw new IllegalArgumentException("VComp prototype does not support static columns");
        if (constraints.hasComplexColumns())
            throw new IllegalArgumentException("VComp prototype does not support collections or UDT values");
        if (constraints.hasExpiringCells())
            throw new IllegalArgumentException("VComp prototype does not support TTL expiration");
        if (constraints.hasRowOrRangeDeletions())
            throw new IllegalArgumentException("VComp prototype does not support row or range deletions");
    }

    /**
     * Convert each physical-flush boundary into one virtual sorted run and let
     * the unchanged UCS picker reach quiescence. A virtual compaction has no
     * synthetic disk service time: once its descriptor merge completes, its
     * output is immediately visible just as a completed native task would be.
     */
    private VirtualLoadResult runVirtualLoad(LoadSource source) throws Exception
    {
        VirtualState state = new VirtualState();
        int compactionCount = 0;
        for (FlushBatch flush : source.flushBatches())
        {
            VirtualSortedRun run = flushVirtualizer.virtualize(flush);
            state.add(run);
            compactionCount = compactToQuiescence(state, compactionCount);
        }
        return new VirtualLoadResult(state, compactionCount);
    }

    private int compactToQuiescence(VirtualState state, int compactionCount) throws Exception
    {
        while (true)
        {
            Optional<VirtualCompactionPlan> next = planner.pick(state.snapshot());
            if (!next.isPresent())
                return compactionCount;
            if (compactionCount >= MAX_VIRTUAL_COMPACTIONS)
                throw new IllegalStateException("virtual compaction did not converge");

            VirtualCompactionPlan plan = next.get();
            VirtualSortedRun output = buildVirtualCompactionOutput(state, plan);
            state.reserve(plan.inputs());
            state.complete(output);
            compactionCount++;
        }
    }

    /** One virtual compaction: model merge, KMV merge, dedup estimate, then vSST split. */
    private VirtualSortedRun buildVirtualCompactionOutput(VirtualState state,
                                                          VirtualCompactionPlan plan) throws Exception
    {
        state.validateInputs(plan.inputs());
        MergedSketch mergedSketch = sketchMerger.mergeAndDeduplicate(plan);
        long estimatedUniqueKeys = deduplicationEstimator.estimateUniqueKeys(plan, mergedSketch);
        MergedModel mergedModel = modelMerger.merge(plan, estimatedUniqueKeys);
        if (mergedModel instanceof VCompLearnedModel
            && ((VCompLearnedModel) mergedModel).discreteModel() != null)
            estimatedUniqueKeys = ((VCompLearnedModel) mergedModel).discreteModel().count();
        VirtualSortedRun output = outputSplitter.split(plan,
                                                       mergedModel,
                                                       mergedSketch,
                                                       estimatedUniqueKeys);
        return output;
    }

    private FrozenLayout freezeFinalLayout(VirtualState state) throws Exception
    {
        return layoutFreezer.freeze(state.snapshot());
    }

    private MaterializedState materializeFinalSSTables(FrozenLayout layout) throws Exception
    {
        return materializer.materialize(layout);
    }

    private void installFinalState(MaterializedState materialized) throws Exception
    {
        installer.install(materialized);
    }

    private void verifyMaterializedState(FrozenLayout layout, MaterializedState materialized) throws Exception
    {
        verifier.verify(layout, materialized);
    }

    public interface LoadSource
    {
        Iterable<FlushBatch> flushBatches() throws Exception;
    }

    public interface FlushVirtualizer
    {
        VirtualSortedRun virtualize(FlushBatch flush) throws Exception;
    }

    public interface VirtualCompactionPlanner
    {
        Optional<VirtualCompactionPlan> pick(VirtualStateSnapshot state) throws Exception;
    }

    public interface ModelMerger
    {
        MergedModel merge(VirtualCompactionPlan plan, long estimatedUniqueKeys) throws Exception;
    }

    public interface SketchMerger
    {
        MergedSketch mergeAndDeduplicate(VirtualCompactionPlan plan) throws Exception;
    }

    public interface DeduplicationEstimator
    {
        long estimateUniqueKeys(VirtualCompactionPlan plan, MergedSketch sketch) throws Exception;
    }

    public interface VirtualOutputSplitter
    {
        VirtualSortedRun split(VirtualCompactionPlan plan,
                               MergedModel model,
                               MergedSketch sketch,
                               long estimatedUniqueKeys) throws Exception;
    }

    public interface LayoutFreezer
    {
        FrozenLayout freeze(VirtualStateSnapshot state) throws Exception;
    }

    public interface FinalMaterializer
    {
        MaterializedState materialize(FrozenLayout layout) throws Exception;
    }

    public interface FinalStateInstaller
    {
        void install(MaterializedState materialized) throws Exception;
    }

    /** Validates generated SSTable components before they are offered to Cassandra for import. */
    public interface MaterializedStateVerifier
    {
        void verify(FrozenLayout layout, MaterializedState materialized) throws Exception;
    }

    /** Marker for the piecewise-linear model produced by a virtual model merge. */
    public interface MergedModel
    {
    }

    /** Marker for the range-aware KMV result produced by a virtual sketch merge. */
    public interface MergedSketch
    {
    }

    public static final class Request
    {
        private final ExecutionConstraints constraints;
        private final LoadSource loadSource;

        public Request(ExecutionConstraints constraints, LoadSource loadSource)
        {
            this.constraints = Objects.requireNonNull(constraints, "constraints");
            this.loadSource = Objects.requireNonNull(loadSource, "loadSource");
        }

        public ExecutionConstraints constraints()
        {
            return constraints;
        }

        public LoadSource loadSource()
        {
            return loadSource;
        }
    }

    public static final class ExecutionConstraints
    {
        private final int nodeCount;
        private final int partitionKeyColumnCount;
        private final int distinctPartitionKeyCount;
        private final int clusteringColumnCount;
        private final int regularColumnCount;
        private final boolean hasStaticColumns;
        private final boolean hasComplexColumns;
        private final boolean hasExpiringCells;
        private final boolean hasRowOrRangeDeletions;

        public ExecutionConstraints(int nodeCount,
                                    int partitionKeyColumnCount,
                                    int distinctPartitionKeyCount,
                                    int clusteringColumnCount,
                                    int regularColumnCount,
                                    boolean hasStaticColumns,
                                    boolean hasComplexColumns,
                                    boolean hasExpiringCells,
                                    boolean hasRowOrRangeDeletions)
        {
            this.nodeCount = nodeCount;
            this.partitionKeyColumnCount = partitionKeyColumnCount;
            this.distinctPartitionKeyCount = distinctPartitionKeyCount;
            this.clusteringColumnCount = clusteringColumnCount;
            this.regularColumnCount = regularColumnCount;
            this.hasStaticColumns = hasStaticColumns;
            this.hasComplexColumns = hasComplexColumns;
            this.hasExpiringCells = hasExpiringCells;
            this.hasRowOrRangeDeletions = hasRowOrRangeDeletions;
        }

        public static ExecutionConstraints singlePartitionKeyValue()
        {
            return new ExecutionConstraints(1, 1, 1, 1, 1, false, false, false, false);
        }

        public static ExecutionConstraints orderedPartitionKeyValue(int partitionCount)
        {
            return new ExecutionConstraints(1, 1, partitionCount, 1, 1,
                                            false, false, false, false);
        }

        public int nodeCount()
        {
            return nodeCount;
        }

        public int partitionKeyColumnCount()
        {
            return partitionKeyColumnCount;
        }

        public int distinctPartitionKeyCount()
        {
            return distinctPartitionKeyCount;
        }

        public int clusteringColumnCount()
        {
            return clusteringColumnCount;
        }

        public int regularColumnCount()
        {
            return regularColumnCount;
        }

        public boolean hasStaticColumns()
        {
            return hasStaticColumns;
        }

        public boolean hasComplexColumns()
        {
            return hasComplexColumns;
        }

        public boolean hasExpiringCells()
        {
            return hasExpiringCells;
        }

        public boolean hasRowOrRangeDeletions()
        {
            return hasRowOrRangeDeletions;
        }
    }

    public static final class FlushBatch
    {
        private final String id;
        private final long keyCount;
        private final long logicalBytes;
        private final long maximumTimestamp;
        private final long[] keyCoordinates;

        public FlushBatch(String id, long[] keyCoordinates, long logicalBytes)
        {
            this(id, keyCoordinates, logicalBytes, 0);
        }

        public FlushBatch(String id, long[] keyCoordinates, long logicalBytes, long maximumTimestamp)
        {
            this.id = requireNonBlank(id, "id");
            this.keyCoordinates = Objects.requireNonNull(keyCoordinates, "keyCoordinates").clone();
            if (this.keyCoordinates.length == 0)
                throw new IllegalArgumentException("flush must contain at least one key coordinate");
            for (int i = 0; i < this.keyCoordinates.length; i++)
            {
                if (this.keyCoordinates[i] < 0)
                    throw new IllegalArgumentException("flush key coordinates must be non-negative");
                if (i > 0 && this.keyCoordinates[i - 1] >= this.keyCoordinates[i])
                    throw new IllegalArgumentException("flush key coordinates must be sorted and distinct");
            }
            if (logicalBytes <= 0)
                throw new IllegalArgumentException("flush logical bytes must be positive");
            if (maximumTimestamp < 0)
                throw new IllegalArgumentException("flush maximum timestamp must be non-negative");
            this.keyCount = keyCoordinates.length;
            this.logicalBytes = logicalBytes;
            this.maximumTimestamp = maximumTimestamp;
        }

        public String id()
        {
            return id;
        }

        public long keyCount()
        {
            return keyCount;
        }

        public long logicalBytes()
        {
            return logicalBytes;
        }

        public long maximumTimestamp()
        {
            return maximumTimestamp;
        }

        public long[] keyCoordinates()
        {
            return keyCoordinates.clone();
        }
    }

    /** A vSortedRun is the tiered-compaction root; its children are non-overlapping vSSTs. */
    public static final class VirtualSortedRun
    {
        private final String id;
        private final int level;
        private final List<VirtualSSTable> sstables;

        public VirtualSortedRun(String id, int level, List<VirtualSSTable> sstables)
        {
            this.id = requireNonBlank(id, "id");
            if (level < 0)
                throw new IllegalArgumentException("virtual sorted-run level must be non-negative");
            this.level = level;
            this.sstables = immutableCopy(sstables, "sstables");
            if (this.sstables.isEmpty())
                throw new IllegalArgumentException("a virtual sorted run must contain at least one vSST");
            for (int i = 1; i < this.sstables.size(); i++)
            {
                if (this.sstables.get(i - 1).keyMax() >= this.sstables.get(i).keyMin())
                    throw new IllegalArgumentException("vSSTs in a sorted run must have ordered, non-overlapping ranges");
            }
        }

        public String id()
        {
            return id;
        }

        public int level()
        {
            return level;
        }

        public List<VirtualSSTable> sstables()
        {
            return sstables;
        }
    }

    public static final class VirtualSSTable
    {
        private final String id;
        private final long keyMin;
        private final long keyMax;
        private final long estimatedUniqueKeys;
        private final long estimatedBytes;
        private final long maximumTimestamp;
        private final VCompLearnedModel model;
        private final VCompKmvSketch sketch;
        private final List<VCompKmvSketch.Range> rangeSketches;

        public VirtualSSTable(String id,
                              long keyMin,
                              long keyMax,
                              long estimatedUniqueKeys,
                              long estimatedBytes,
                              VCompLearnedModel model,
                              VCompKmvSketch sketch,
                              List<VCompKmvSketch.Range> rangeSketches)
        {
            this(id,
                 keyMin,
                 keyMax,
                 estimatedUniqueKeys,
                 estimatedBytes,
                 0,
                 model,
                 sketch,
                 rangeSketches);
        }

        public VirtualSSTable(String id,
                              long keyMin,
                              long keyMax,
                              long estimatedUniqueKeys,
                              long estimatedBytes,
                              long maximumTimestamp,
                              VCompLearnedModel model,
                              VCompKmvSketch sketch,
                              List<VCompKmvSketch.Range> rangeSketches)
        {
            this.id = requireNonBlank(id, "id");
            if (keyMin < 0 || keyMax < keyMin)
                throw new IllegalArgumentException("invalid vSST key range");
            if (estimatedUniqueKeys <= 0)
                throw new IllegalArgumentException("vSST estimated unique-key count must be positive");
            if (estimatedUniqueKeys > inclusiveCardinality(keyMin, keyMax))
                throw new IllegalArgumentException("vSST estimated unique-key count exceeds its key domain");
            if (estimatedBytes <= 0)
                throw new IllegalArgumentException("vSST estimated bytes must be positive");
            if (maximumTimestamp < 0)
                throw new IllegalArgumentException("vSST maximum timestamp must be non-negative");
            this.model = Objects.requireNonNull(model, "model");
            this.sketch = Objects.requireNonNull(sketch, "sketch");
            if (model.isEmpty() || model.keyMin() != keyMin || model.keyMax() != keyMax)
                throw new IllegalArgumentException("vSST model range differs from descriptor range");
            if (model.discreteModel() != null
                && model.discreteModel().count() != estimatedUniqueKeys)
                throw new IllegalArgumentException("vSST discrete certificate count differs from descriptor estimate");
            for (VCompKmvSketch.Sample sample : sketch.samples())
            {
                if (sample.key() < keyMin || sample.key() > keyMax)
                    throw new IllegalArgumentException("global KMV sample lies outside its vSST range");
            }
            // Sketches retain original source keys, whereas a PLR shard's count
            // describes its modeled rank interval. Even a complete sketch can
            // therefore have a different cardinality after a key-range split.
            // Do not replace either count with the other: later KMV unions need
            // the original samples, and materialization needs the modeled count.
            this.keyMin = keyMin;
            this.keyMax = keyMax;
            this.estimatedUniqueKeys = estimatedUniqueKeys;
            this.estimatedBytes = estimatedBytes;
            this.maximumTimestamp = maximumTimestamp;
            this.rangeSketches = immutableCopy(rangeSketches, "rangeSketches");
            long previousRangeMax = -1;
            for (VCompKmvSketch.Range range : this.rangeSketches)
            {
                if (range.keyMin() < keyMin || range.keyMax() > keyMax)
                    throw new IllegalArgumentException("range KMV lies outside its vSST range");
                if (previousRangeMax >= range.keyMin())
                    throw new IllegalArgumentException("range KMVs must be ordered and non-overlapping");
                previousRangeMax = range.keyMax();
            }
        }

        public String id()
        {
            return id;
        }

        public long estimatedUniqueKeys()
        {
            return estimatedUniqueKeys;
        }

        public long estimatedBytes()
        {
            return estimatedBytes;
        }

        /** Maximum Cassandra cell timestamp represented by this vSST. */
        public long maximumTimestamp()
        {
            return maximumTimestamp;
        }

        public long keyMin()
        {
            return keyMin;
        }

        public long keyMax()
        {
            return keyMax;
        }

        public VCompLearnedModel model()
        {
            return model;
        }

        public VCompKmvSketch sketch()
        {
            return sketch;
        }

        public List<VCompKmvSketch.Range> rangeSketches()
        {
            return rangeSketches;
        }
    }

    public static final class VirtualCompactionPlan
    {
        private final String id;
        private final List<VirtualSortedRun> inputs;
        private final int outputLevel;

        public VirtualCompactionPlan(String id, List<VirtualSortedRun> inputs, int outputLevel)
        {
            this.id = requireNonBlank(id, "id");
            this.inputs = immutableCopy(inputs, "inputs");
            if (this.inputs.isEmpty())
                throw new IllegalArgumentException("a virtual compaction requires at least one input run");
            if (outputLevel < 0)
                throw new IllegalArgumentException("virtual compaction output level must be non-negative");
            java.util.HashSet<String> ids = new java.util.HashSet<>();
            for (VirtualSortedRun input : this.inputs)
            {
                if (!ids.add(input.id()))
                    throw new IllegalArgumentException("duplicate virtual compaction input: " + input.id());
            }
            this.outputLevel = outputLevel;
        }

        public String id()
        {
            return id;
        }

        public List<VirtualSortedRun> inputs()
        {
            return inputs;
        }

        public int outputLevel()
        {
            return outputLevel;
        }
    }

    public static final class VirtualStateSnapshot
    {
        private final List<VirtualSortedRun> runs;

        public VirtualStateSnapshot(List<VirtualSortedRun> runs)
        {
            this.runs = immutableCopy(runs, "runs");
        }

        public List<VirtualSortedRun> runs()
        {
            return runs;
        }
    }

    public static final class FrozenLayout
    {
        private final List<VirtualSortedRun> runs;

        public FrozenLayout(List<VirtualSortedRun> runs)
        {
            this.runs = immutableCopy(runs, "runs");
            if (this.runs.isEmpty())
                throw new IllegalArgumentException("a frozen layout must contain at least one sorted run");
            java.util.HashSet<String> runIds = new java.util.HashSet<>();
            java.util.HashSet<String> sstableIds = new java.util.HashSet<>();
            for (VirtualSortedRun run : this.runs)
            {
                if (!runIds.add(run.id()))
                    throw new IllegalArgumentException("duplicate frozen sorted-run id: " + run.id());
                for (VirtualSSTable sstable : run.sstables())
                {
                    if (!sstableIds.add(sstable.id()))
                        throw new IllegalArgumentException("duplicate frozen vSST id: " + sstable.id());
                }
            }
        }

        public List<VirtualSortedRun> runs()
        {
            return runs;
        }
    }

    public static final class MaterializedState
    {
        private final List<String> sstableIds;
        private final long materializedKeys;
        private final long logicalBytes;

        public MaterializedState(List<String> sstableIds)
        {
            this(sstableIds, -1, -1);
        }

        public MaterializedState(List<String> sstableIds, long materializedKeys, long logicalBytes)
        {
            this.sstableIds = immutableCopy(sstableIds, "sstableIds");
            java.util.HashSet<String> uniqueIds = new java.util.HashSet<>();
            for (String id : this.sstableIds)
            {
                requireNonBlank(id, "sstable id");
                if (!uniqueIds.add(id))
                    throw new IllegalArgumentException("duplicate materialized SSTable id: " + id);
            }
            if (materializedKeys < -1 || logicalBytes < -1)
                throw new IllegalArgumentException("materialization counters must be non-negative or unknown (-1)");
            this.materializedKeys = materializedKeys;
            this.logicalBytes = logicalBytes;
        }

        public List<String> sstableIds()
        {
            return sstableIds;
        }

        public long materializedKeys()
        {
            return materializedKeys;
        }

        public long logicalBytes()
        {
            return logicalBytes;
        }
    }

    public static final class Result
    {
        private final FrozenLayout layout;
        private final MaterializedState materialized;
        private final int virtualCompactionCount;

        private Result(FrozenLayout layout, MaterializedState materialized, int virtualCompactionCount)
        {
            this.layout = layout;
            this.materialized = materialized;
            this.virtualCompactionCount = virtualCompactionCount;
        }

        public FrozenLayout layout()
        {
            return layout;
        }

        public MaterializedState materialized()
        {
            return materialized;
        }

        public int virtualCompactionCount()
        {
            return virtualCompactionCount;
        }
    }

    private static final class VirtualState
    {
        private final Map<String, VirtualSortedRun> runs = new LinkedHashMap<>();
        private final java.util.HashSet<String> sstableIds = new java.util.HashSet<>();

        private void add(VirtualSortedRun run)
        {
            Objects.requireNonNull(run, "run");
            if (runs.containsKey(run.id()))
                throw new IllegalStateException("duplicate virtual sorted run id: " + run.id());
            for (VirtualSSTable sstable : run.sstables())
            {
                if (sstableIds.contains(sstable.id()))
                    throw new IllegalStateException("duplicate virtual SSTable id: " + sstable.id());
            }
            runs.put(run.id(), run);
            for (VirtualSSTable sstable : run.sstables())
                sstableIds.add(sstable.id());
        }

        private VirtualStateSnapshot snapshot()
        {
            return new VirtualStateSnapshot(new ArrayList<>(runs.values()));
        }

        private void validateInputs(List<VirtualSortedRun> inputs)
        {
            for (VirtualSortedRun input : inputs)
            {
                if (runs.get(input.id()) != input)
                    throw new IllegalStateException("compaction input is not in the current virtual state: " + input.id());
            }
        }

        private void reserve(List<VirtualSortedRun> inputs)
        {
            validateInputs(inputs);
            for (VirtualSortedRun input : inputs)
            {
                runs.remove(input.id());
                for (VirtualSSTable sstable : input.sstables())
                    sstableIds.remove(sstable.id());
            }
        }

        private void complete(VirtualSortedRun output)
        {
            Objects.requireNonNull(output, "output");
            // Cassandra registers each sharded compaction output as an independent
            // SSTable candidate. Preserve the common compaction lineage in the IDs,
            // but never feed an aggregate sorted run back to the UCS picker.
            if (output.sstables().size() == 1)
                add(output);
            else
            {
                int index = 0;
                for (VirtualSSTable sstable : output.sstables())
                    add(new VirtualSortedRun(output.id() + "-shard-" + index++,
                                             output.level(),
                                             Collections.singletonList(sstable)));
            }
        }
    }

    private static final class VirtualLoadResult
    {
        private final VirtualState state;
        private final int compactionCount;

        private VirtualLoadResult(VirtualState state, int compactionCount)
        {
            this.state = state;
            this.compactionCount = compactionCount;
        }
    }

    private static <T> List<T> immutableCopy(List<T> values, String name)
    {
        Objects.requireNonNull(values, name);
        ArrayList<T> copy = new ArrayList<>(values.size());
        for (T value : values)
            copy.add(Objects.requireNonNull(value, name + " element"));
        return Collections.unmodifiableList(copy);
    }

    private static String requireNonBlank(String value, String name)
    {
        Objects.requireNonNull(value, name);
        if (value.trim().isEmpty())
            throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    private static long inclusiveCardinality(long minimum, long maximum)
    {
        long difference = maximum - minimum;
        return difference == Long.MAX_VALUE ? Long.MAX_VALUE : difference + 1;
    }
}
