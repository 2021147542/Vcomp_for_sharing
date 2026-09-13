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

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VCompMurmur3CompositeKeyTest
{
    @Test
    public void ordersBySignedTokenThenUnsignedPartitionThenClustering()
    {
        assertTrue(key(-1, 0xff, 9).compareTo(key(0, 0, 0)) < 0);
        assertTrue(key(7, 0x7f, 9).compareTo(key(7, 0x80, 0)) < 0);
        assertTrue(key(7, 0x80, 1).compareTo(key(7, 0x80, 2)) < 0);
    }

    @Test
    public void partitionIdentityIncludesCollisionResolvingKey()
    {
        VCompMurmur3CompositeKey first = key(5, 1, 1);
        VCompMurmur3CompositeKey last = key(5, 1, 9);
        VCompMurmur3CompositeKey collision = key(5, 2, 1);

        assertTrue(first.samePartition(last));
        assertFalse(first.samePartition(collision));
    }

    @Test
    public void ownsInputAndReturnedArrays()
    {
        byte[] partition = { 1 };
        byte[] clustering = { 2 };
        VCompMurmur3CompositeKey key = new VCompMurmur3CompositeKey(3, partition, clustering);
        partition[0] = 9;
        clustering[0] = 9;
        byte[] returned = key.partitionKey();
        returned[0] = 8;

        assertEquals(new VCompMurmur3CompositeKey(3, new byte[]{ 1 }, new byte[]{ 2 }), key);
    }

    private static VCompMurmur3CompositeKey key(long token, int partition, int clustering)
    {
        return new VCompMurmur3CompositeKey(token,
                                            new byte[]{ (byte) partition },
                                            new byte[]{ (byte) clustering });
    }
}
