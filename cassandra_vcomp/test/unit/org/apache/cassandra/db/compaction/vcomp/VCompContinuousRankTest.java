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

import java.util.SplittableRandom;
import java.util.TreeSet;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class VCompContinuousRankTest
{
    @Test
    public void doesNotDropRankAtTheNextAnchor()
    {
        // The former independent fit predicted rank 35.66 at key 776, then
        // rank 29 at key 813. A rank function cannot decrease in key order.
        long[] keys = { 2, 8, 24, 46, 63, 85, 116, 117, 144, 148, 149, 166, 170,
                        189, 196, 200, 219, 220, 238, 243, 371, 419, 463, 515,
                        616, 638, 657, 747, 776, 813, 827, 843, 866, 883, 898,
                        927, 941, 954, 977, 985 };
        verify(keys, 8);
    }

    @Test
    public void preservesErrorAndContinuousMassOnDifferentShapes()
    {
        SplittableRandom random = new SplittableRandom(20260909);
        for (int shape = 0; shape < 3; shape++)
        {
            TreeSet<Long> keys = new TreeSet<>();
            for (int i = 0; i < 4096; i++)
            {
                long key = random.nextLong(1 << 20);
                if (shape == 1)
                    key = key * key / (1 << 20);
                if (shape == 2)
                    key += (key / (1 << 17)) * (1L << 22);
                keys.add(key);
            }
            long[] input = keys.stream().mapToLong(Long::longValue).toArray();
            for (double error : new double[] { 0, 1, 8 })
                verify(input, error);
        }
    }

    @Test
    public void handlesEmptySingletonAndExactlyLinearInputs()
    {
        assertTrue(VCompLearnedModel.greedyFit(new long[0], 8).isEmpty());
        verify(new long[] { 13 }, 8);
        verify(new long[] { 1, 11, 21, 31, 41 }, 0);
        assertEquals(1, VCompLearnedModel.greedyFit(new long[] { 1, 11, 21, 31, 41 }, 0).segments().size());
    }

    private static void verify(long[] keys, double error)
    {
        VCompLearnedModel model = VCompLearnedModel.greedyFit(keys, error);
        assertTrue(model.discreteModel() == null);
        double previousRank = -1;
        for (int i = 0; i < keys.length; i++)
        {
            double rank = model.predict(keys[i]);
            assertEquals("bounded fit at " + keys[i], i, rank, error + 1e-7);
            assertTrue("monotone fit at " + keys[i], rank + 1e-7 >= previousRank);
            previousRank = rank;
        }
        assertEquals(0, model.predict(keys[0]), 1e-7);
        assertEquals(keys.length - 1, model.predict(keys[keys.length - 1]), 1e-7);
        VCompLearnedModel.Segment previous = null;
        for (VCompLearnedModel.Segment segment : model.segments())
        {
            if (previous != null)
            {
                assertEquals(previous.keyEnd(), segment.keyStart());
                double left = previous.slope() * previous.keyEnd() + previous.intercept();
                double right = segment.slope() * segment.keyStart() + segment.intercept();
                assertEquals("no lost mass at segment boundary", left, right, 1e-7);
            }
            previous = segment;
        }
        long previousKey = -1;
        for (int rank = 0; rank < keys.length; rank++)
        {
            long key = model.inverse(rank);
            assertTrue("inverse is monotone", key >= previousKey);
            previousKey = key;
        }
    }
}
