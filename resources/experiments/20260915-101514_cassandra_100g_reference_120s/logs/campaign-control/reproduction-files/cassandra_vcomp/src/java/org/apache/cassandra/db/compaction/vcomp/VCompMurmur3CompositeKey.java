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

import java.util.Arrays;

/**
 * A deterministic, engine-independent coordinate in Cassandra's Murmur3 partition order.
 *
 * <p>Keys compare by signed Murmur3 token, then unsigned partition-key bytes to resolve token
 * collisions, then unsigned clustering-order bytes. The clustering bytes are deliberately named
 * "order bytes": callers must obtain an order-preserving representation from Cassandra's schema
 * comparator rather than passing arbitrary serialized CQL values. This keeps reversed and
 * composite clustering types out of this engine-independent foundation.</p>
 */
public final class VCompMurmur3CompositeKey implements Comparable<VCompMurmur3CompositeKey>
{
    private final long token;
    private final byte[] partitionKey;
    private final byte[] clusteringOrder;

    public VCompMurmur3CompositeKey(long token, byte[] partitionKey, byte[] clusteringOrder)
    {
        if (partitionKey == null || partitionKey.length == 0)
            throw new IllegalArgumentException("partition key must not be empty");
        if (clusteringOrder == null)
            throw new IllegalArgumentException("clustering order bytes must not be null");

        this.token = token;
        this.partitionKey = partitionKey.clone();
        this.clusteringOrder = clusteringOrder.clone();
    }

    public long token()
    {
        return token;
    }

    public byte[] partitionKey()
    {
        return partitionKey.clone();
    }

    public byte[] clusteringOrder()
    {
        return clusteringOrder.clone();
    }

    public boolean samePartition(VCompMurmur3CompositeKey other)
    {
        return other != null && token == other.token && Arrays.equals(partitionKey, other.partitionKey);
    }

    @Override
    public int compareTo(VCompMurmur3CompositeKey other)
    {
        int comparison = Long.compare(token, other.token);
        if (comparison != 0)
            return comparison;
        comparison = compareUnsigned(partitionKey, other.partitionKey);
        if (comparison != 0)
            return comparison;
        return compareUnsigned(clusteringOrder, other.clusteringOrder);
    }

    @Override
    public boolean equals(Object value)
    {
        if (this == value)
            return true;
        if (!(value instanceof VCompMurmur3CompositeKey))
            return false;
        VCompMurmur3CompositeKey other = (VCompMurmur3CompositeKey) value;
        return token == other.token
               && Arrays.equals(partitionKey, other.partitionKey)
               && Arrays.equals(clusteringOrder, other.clusteringOrder);
    }

    @Override
    public int hashCode()
    {
        int result = Long.hashCode(token);
        result = 31 * result + Arrays.hashCode(partitionKey);
        return 31 * result + Arrays.hashCode(clusteringOrder);
    }

    private static int compareUnsigned(byte[] left, byte[] right)
    {
        int commonLength = Math.min(left.length, right.length);
        for (int i = 0; i < commonLength; i++)
        {
            int comparison = Integer.compare(Byte.toUnsignedInt(left[i]), Byte.toUnsignedInt(right[i]));
            if (comparison != 0)
                return comparison;
        }
        return Integer.compare(left.length, right.length);
    }
}
