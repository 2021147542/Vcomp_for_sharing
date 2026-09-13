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
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class VCompPartitionBoundarySplitterTest
{
    @Test
    public void splitsOnlyBetweenPartitions()
    {
        VCompPartitionBoundarySplitter.PartitionEstimate one = partition(-10, 1, 60, 6);
        VCompPartitionBoundarySplitter.PartitionEstimate two = partition(0, 2, 40, 4);
        VCompPartitionBoundarySplitter.PartitionEstimate three = partition(10, 3, 30, 3);

        List<VCompPartitionBoundarySplitter.Output> outputs =
        VCompPartitionBoundarySplitter.split(Arrays.asList(one, two, three), 100);

        assertEquals(2, outputs.size());
        assertEquals(Arrays.asList(one, two), outputs.get(0).partitions());
        assertEquals(100, outputs.get(0).estimatedBytes());
        assertEquals(10, outputs.get(0).estimatedRows());
        assertEquals(Arrays.asList(three), outputs.get(1).partitions());
        assertSame(one.first(), outputs.get(0).first());
        assertSame(two.last(), outputs.get(0).last());
    }

    @Test
    public void emitsOversizedPartitionWhole()
    {
        VCompPartitionBoundarySplitter.PartitionEstimate giant = partition(0, 1, 250, 25);
        List<VCompPartitionBoundarySplitter.Output> outputs =
        VCompPartitionBoundarySplitter.split(Arrays.asList(giant, partition(1, 2, 10, 1)), 100);

        assertEquals(2, outputs.size());
        assertEquals(Arrays.asList(giant), outputs.get(0).partitions());
        assertEquals(250, outputs.get(0).estimatedBytes());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMultipleFragmentsOfOnePartition()
    {
        VCompPartitionBoundarySplitter.PartitionEstimate first =
        new VCompPartitionBoundarySplitter.PartitionEstimate(key(1, 1, 0), key(1, 1, 4), 10, 5);
        VCompPartitionBoundarySplitter.PartitionEstimate second =
        new VCompPartitionBoundarySplitter.PartitionEstimate(key(1, 1, 5), key(1, 1, 9), 10, 5);

        VCompPartitionBoundarySplitter.split(Arrays.asList(first, second), 100);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnsortedPartitions()
    {
        VCompPartitionBoundarySplitter.split(Arrays.asList(partition(2, 2, 10, 1),
                                                            partition(1, 1, 10, 1)),
                                             100);
    }

    private static VCompPartitionBoundarySplitter.PartitionEstimate partition(long token,
                                                                               int partition,
                                                                               long bytes,
                                                                               long rows)
    {
        return new VCompPartitionBoundarySplitter.PartitionEstimate(key(token, partition, 0),
                                                                     key(token, partition, 9),
                                                                     bytes,
                                                                     rows);
    }

    private static VCompMurmur3CompositeKey key(long token, int partition, int clustering)
    {
        return new VCompMurmur3CompositeKey(token,
                                            new byte[]{ (byte) partition },
                                            new byte[]{ (byte) clustering });
    }
}
