/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.cassandra.db.compaction.vcomp;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.apache.cassandra.dht.Murmur3Partitioner;

/**
 * Maps one non-negative scalar key space onto disjoint Cassandra partitions.
 *
 * <p>Partition identifiers are first sorted by their actual signed Murmur3
 * token. Consecutive, non-overlapping scalar ranges are then assigned in that
 * order. Consequently Cassandra's {@code (token, partition key, clustering)}
 * order and the paper's one-dimensional scalar-key order are equivalent.</p>
 */
public final class VCompOrderedPartitionLayout
{
    private static final String PREFIX = "vcomp-partition-";
    private static final double TOKEN_SPACE = Math.scalb(1.0, 64);
    private static final double MINIMUM_TOKEN_COVERAGE = Math.scalb(1.0, -48);

    private final long keySpace;
    private final long baseRange;
    private final int extraRanges;
    private final long expandedPrefix;
    private final List<Partition> partitions;

    public VCompOrderedPartitionLayout(long keySpace, int partitionCount)
    {
        if (keySpace <= 0)
            throw new IllegalArgumentException("key space must be positive");
        if (partitionCount <= 0 || partitionCount > keySpace)
            throw new IllegalArgumentException("partition count must be in [1, key space]");
        this.keySpace = keySpace;
        this.baseRange = keySpace / partitionCount;
        this.extraRanges = (int) (keySpace % partitionCount);
        this.expandedPrefix = Math.multiplyExact(baseRange + 1, extraRanges);

        List<Candidate> candidates = new ArrayList<>(partitionCount);
        for (int i = 0; i < partitionCount; i++)
        {
            String key = String.format("%s%08d", PREFIX, i);
            byte[] encoded = key.getBytes(StandardCharsets.UTF_8);
            long token = Murmur3Partitioner.instance.getToken(ByteBuffer.wrap(encoded)).getLongValue();
            candidates.add(new Candidate(key, encoded, token));
        }
        candidates.sort(Comparator.comparingLong((Candidate candidate) -> candidate.token)
                                  .thenComparing(candidate -> candidate.key));

        List<Partition> ordered = new ArrayList<>(partitionCount);
        long minimum = 0;
        long previousToken = Long.MIN_VALUE;
        byte[] previousKey = null;
        for (int i = 0; i < candidates.size(); i++)
        {
            Candidate candidate = candidates.get(i);
            if (i > 0 && candidate.token == previousToken
                && compareUnsigned(candidate.encoded, previousKey) <= 0)
                throw new IllegalStateException("partition token collision was not ordered by key bytes");
            long width = baseRange + (i < extraRanges ? 1 : 0);
            long maximum = Math.addExact(minimum, width) - 1;
            ordered.add(new Partition(i, candidate.key, candidate.encoded,
                                      candidate.token, minimum, maximum));
            minimum = maximum + 1;
            previousToken = candidate.token;
            previousKey = candidate.encoded;
        }
        if (minimum != keySpace)
            throw new IllegalStateException("partition ranges do not cover the scalar key space");
        this.partitions = Collections.unmodifiableList(ordered);
    }

    public long keySpace()
    {
        return keySpace;
    }

    public int partitionCount()
    {
        return partitions.size();
    }

    public List<Partition> partitions()
    {
        return partitions;
    }

    public Partition partitionFor(long key)
    {
        if (key < 0 || key >= keySpace)
            throw new IllegalArgumentException("key lies outside the configured scalar key space: " + key);
        final long index;
        if (key < expandedPrefix)
            index = key / (baseRange + 1);
        else
            index = extraRanges + (key - expandedPrefix) / baseRange;
        return partitions.get(Math.toIntExact(index));
    }

    /** Number of Cassandra partitions touched by an inclusive scalar range. */
    public long partitionCount(long minimum, long maximum)
    {
        if (maximum < minimum)
            throw new IllegalArgumentException("maximum must not precede minimum");
        Partition first = partitionFor(minimum);
        Partition last = partitionFor(maximum);
        return (long) last.ordinal - first.ordinal + 1;
    }

    /** Cassandra single-node token-space coverage for an inclusive scalar range. */
    public double tokenCoverage(long minimum, long maximum)
    {
        Partition first = partitionFor(minimum);
        Partition last = partitionFor(maximum);
        double coverage = (tokenPosition(last.token) - tokenPosition(first.token)) / TOKEN_SPACE;
        return coverage >= MINIMUM_TOKEN_COVERAGE ? coverage : 1.0;
    }

    /** Full-ring UCS shard containing this partition token. Shard counts are powers of two. */
    public int shardIndex(Partition partition, int shardCount)
    {
        if (partition == null)
            throw new NullPointerException("partition");
        if (shardCount <= 0)
            throw new IllegalArgumentException("shard count must be positive");
        double normalized = tokenPosition(partition.token) / TOKEN_SPACE;
        return Math.min(shardCount - 1, (int) (normalized * shardCount));
    }

    private static double tokenPosition(long token)
    {
        return (double) token - Long.MIN_VALUE;
    }

    private static int compareUnsigned(byte[] left, byte[] right)
    {
        int common = Math.min(left.length, right.length);
        for (int i = 0; i < common; i++)
        {
            int comparison = Integer.compare(Byte.toUnsignedInt(left[i]), Byte.toUnsignedInt(right[i]));
            if (comparison != 0)
                return comparison;
        }
        return Integer.compare(left.length, right.length);
    }

    public static final class Partition
    {
        private final int ordinal;
        private final String key;
        private final byte[] encodedKey;
        private final long token;
        private final long minimum;
        private final long maximum;

        private Partition(int ordinal, String key, byte[] encodedKey, long token,
                          long minimum, long maximum)
        {
            this.ordinal = ordinal;
            this.key = key;
            this.encodedKey = encodedKey.clone();
            this.token = token;
            this.minimum = minimum;
            this.maximum = maximum;
        }

        public int ordinal() { return ordinal; }
        public String key() { return key; }
        public byte[] encodedKey() { return encodedKey.clone(); }
        public long token() { return token; }
        public long minimum() { return minimum; }
        public long maximum() { return maximum; }
        public boolean contains(long coordinate) { return coordinate >= minimum && coordinate <= maximum; }
    }

    private static final class Candidate
    {
        private final String key;
        private final byte[] encoded;
        private final long token;

        private Candidate(String key, byte[] encoded, long token)
        {
            this.key = key;
            this.encoded = encoded;
            this.token = token;
        }
    }
}
