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

/** Greedily groups whole Cassandra partitions into target-sized materialization outputs. */
public final class VCompPartitionBoundarySplitter
{
    private VCompPartitionBoundarySplitter()
    {
    }

    /**
     * Split an already sorted sequence of partition estimates without ever splitting a partition.
     * An individual partition larger than {@code targetBytes} is emitted as one oversized output.
     */
    public static List<Output> split(List<PartitionEstimate> partitions, long targetBytes)
    {
        if (partitions == null)
            throw new IllegalArgumentException("partitions must not be null");
        if (targetBytes <= 0)
            throw new IllegalArgumentException("target bytes must be positive");
        if (partitions.isEmpty())
            return Collections.emptyList();

        validateSortedDistinctPartitions(partitions);

        List<Output> outputs = new ArrayList<>();
        List<PartitionEstimate> current = new ArrayList<>();
        long currentBytes = 0;
        long currentRows = 0;
        for (PartitionEstimate partition : partitions)
        {
            if (!current.isEmpty() && exceedsTarget(currentBytes, partition.estimatedBytes, targetBytes))
            {
                outputs.add(new Output(current, currentBytes, currentRows));
                current = new ArrayList<>();
                currentBytes = 0;
                currentRows = 0;
            }
            current.add(partition);
            currentBytes = Math.addExact(currentBytes, partition.estimatedBytes);
            currentRows = Math.addExact(currentRows, partition.estimatedRows);
        }
        outputs.add(new Output(current, currentBytes, currentRows));
        return Collections.unmodifiableList(outputs);
    }

    private static boolean exceedsTarget(long accumulated, long next, long target)
    {
        return next > target || accumulated > target - next;
    }

    private static void validateSortedDistinctPartitions(List<PartitionEstimate> partitions)
    {
        PartitionEstimate previous = null;
        for (PartitionEstimate current : partitions)
        {
            if (current == null)
                throw new IllegalArgumentException("partition estimates must not contain null");
            if (previous != null)
            {
                if (previous.last.compareTo(current.first) >= 0)
                    throw new IllegalArgumentException("partitions must be strictly ordered and non-overlapping");
                if (previous.first.samePartition(current.first))
                    throw new IllegalArgumentException("a partition must be represented by exactly one estimate");
            }
            previous = current;
        }
    }

    public static final class PartitionEstimate
    {
        private final VCompMurmur3CompositeKey first;
        private final VCompMurmur3CompositeKey last;
        private final long estimatedBytes;
        private final long estimatedRows;

        public PartitionEstimate(VCompMurmur3CompositeKey first,
                                 VCompMurmur3CompositeKey last,
                                 long estimatedBytes,
                                 long estimatedRows)
        {
            if (first == null || last == null)
                throw new IllegalArgumentException("partition bounds must not be null");
            if (!first.samePartition(last))
                throw new IllegalArgumentException("partition bounds must belong to the same partition");
            if (first.compareTo(last) > 0)
                throw new IllegalArgumentException("partition bounds are reversed");
            if (estimatedBytes < 0 || estimatedRows < 0)
                throw new IllegalArgumentException("partition estimates must be non-negative");
            this.first = first;
            this.last = last;
            this.estimatedBytes = estimatedBytes;
            this.estimatedRows = estimatedRows;
        }

        public VCompMurmur3CompositeKey first()
        {
            return first;
        }

        public VCompMurmur3CompositeKey last()
        {
            return last;
        }

        public long estimatedBytes()
        {
            return estimatedBytes;
        }

        public long estimatedRows()
        {
            return estimatedRows;
        }
    }

    public static final class Output
    {
        private final List<PartitionEstimate> partitions;
        private final long estimatedBytes;
        private final long estimatedRows;

        private Output(List<PartitionEstimate> partitions, long estimatedBytes, long estimatedRows)
        {
            this.partitions = Collections.unmodifiableList(new ArrayList<>(partitions));
            this.estimatedBytes = estimatedBytes;
            this.estimatedRows = estimatedRows;
        }

        public List<PartitionEstimate> partitions()
        {
            return partitions;
        }

        public VCompMurmur3CompositeKey first()
        {
            return partitions.get(0).first;
        }

        public VCompMurmur3CompositeKey last()
        {
            return partitions.get(partitions.size() - 1).last;
        }

        public long estimatedBytes()
        {
            return estimatedBytes;
        }

        public long estimatedRows()
        {
            return estimatedRows;
        }
    }
}
