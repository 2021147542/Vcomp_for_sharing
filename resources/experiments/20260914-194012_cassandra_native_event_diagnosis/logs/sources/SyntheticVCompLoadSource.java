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
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.SplittableRandom;

/** Lazy fillrandom-like source that emits one sorted, deduplicated descriptor batch per flush. */
public final class SyntheticVCompLoadSource implements VCompPipeline.LoadSource
{
    private final long writeCount;
    private final int writesPerFlush;
    private final long keySpace;
    private final int entryBytes;
    private final long seed;

    public SyntheticVCompLoadSource(long writeCount,
                                    int writesPerFlush,
                                    long keySpace,
                                    int entryBytes,
                                    long seed)
    {
        if (writeCount <= 0)
            throw new IllegalArgumentException("writeCount must be positive");
        if (writesPerFlush <= 0)
            throw new IllegalArgumentException("writesPerFlush must be positive");
        if (keySpace <= 0)
            throw new IllegalArgumentException("keySpace must be positive");
        if (entryBytes <= 0)
            throw new IllegalArgumentException("entryBytes must be positive");
        this.writeCount = writeCount;
        this.writesPerFlush = writesPerFlush;
        this.keySpace = keySpace;
        this.entryBytes = entryBytes;
        this.seed = seed;
    }

    @Override
    public Iterable<VCompPipeline.FlushBatch> flushBatches()
    {
        return () -> new Iterator<VCompPipeline.FlushBatch>()
        {
            private final SplittableRandom random = new SplittableRandom(seed);
            private long generated;
            private long flushNumber;

            @Override
            public boolean hasNext()
            {
                return generated < writeCount;
            }

            @Override
            public VCompPipeline.FlushBatch next()
            {
                if (!hasNext())
                    throw new NoSuchElementException();
                int count = (int) Math.min(writesPerFlush, writeCount - generated);
                long[] keys = new long[count];
                for (int i = 0; i < count; i++)
                    keys[i] = random.nextLong(keySpace);
                Arrays.sort(keys);

                int unique = 0;
                for (long key : keys)
                {
                    if (unique == 0 || keys[unique - 1] != key)
                        keys[unique++] = key;
                }
                keys = Arrays.copyOf(keys, unique);
                generated += count;
                long timestamp = generated;
                long bytes = saturatedMultiply(unique, entryBytes);
                return new VCompPipeline.FlushBatch(Long.toString(flushNumber++), keys, bytes, timestamp);
            }
        };
    }

    private static long saturatedMultiply(long left, long right)
    {
        if (left != 0 && right > Long.MAX_VALUE / left)
            return Long.MAX_VALUE;
        return left * right;
    }
}
