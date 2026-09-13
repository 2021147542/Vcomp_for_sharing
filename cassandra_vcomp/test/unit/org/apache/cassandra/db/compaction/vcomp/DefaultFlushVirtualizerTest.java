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

public class DefaultFlushVirtualizerTest
{
    @Test
    public void buildsModelAndSketchesForOneFlush() throws Exception
    {
        long[] keys = { 10, 20, 30, 50, 80, 130 };
        VCompPipeline.FlushBatch flush = new VCompPipeline.FlushBatch("1", keys, 6144, 77);
        DefaultFlushVirtualizer virtualizer = new DefaultFlushVirtualizer(0, 16, 2);

        VCompPipeline.VirtualSortedRun run = virtualizer.virtualize(flush);
        VCompPipeline.VirtualSSTable sstable = run.sstables().get(0);

        assertEquals("run-1", run.id());
        assertEquals(0, run.level());
        assertEquals(10, sstable.keyMin());
        assertEquals(130, sstable.keyMax());
        assertEquals(keys.length, sstable.estimatedUniqueKeys());
        assertEquals(6144, sstable.estimatedBytes());
        assertEquals(77, sstable.maximumTimestamp());
        assertFalse(sstable.model().isEmpty());
        assertTrue("flush metadata must stay on the paper-path continuous PLR",
                   sstable.model().discreteModel() == null);
        assertEquals(2, sstable.rangeSketches().size());
        assertTrue(sstable.sketch().isComplete());
        assertEquals(keys.length, sstable.sketch().samples().size());

        for (int i = 0; i < keys.length; i++)
        {
            assertEquals(i, sstable.model().predict(keys[i]), 0.000001);
            assertEquals(keys[i], sstable.model().inverse(i));
        }
    }

    @Test
    public void retainsOnlyBottomKSamples()
    {
        long[] keys = { 1, 2, 3, 4, 5, 6, 7, 8 };
        VCompKmvSketch sketch = VCompKmvSketch.build(keys, 3);

        assertFalse(sketch.isComplete());
        assertEquals(3, sketch.samples().size());
        assertEquals(sketch.samples().get(2).hash(), sketch.thetaHash());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnsortedFlushKeys() throws Exception
    {
        VCompPipeline.FlushBatch flush = new VCompPipeline.FlushBatch("bad", new long[]{ 1, 3, 2 }, 3);
        new DefaultFlushVirtualizer().virtualize(flush);
    }
}
