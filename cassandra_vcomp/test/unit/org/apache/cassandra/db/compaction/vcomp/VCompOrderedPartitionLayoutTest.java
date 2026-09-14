/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.cassandra.db.compaction.vcomp;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class VCompOrderedPartitionLayoutTest
{
    @Test
    public void scalarRangesFollowMurmur3TokenOrder()
    {
        VCompOrderedPartitionLayout layout = new VCompOrderedPartitionLayout(1003, 100);
        long expectedMinimum = 0;
        long previousToken = Long.MIN_VALUE;
        for (VCompOrderedPartitionLayout.Partition partition : layout.partitions())
        {
            assertTrue(partition.token() >= previousToken);
            assertEquals(expectedMinimum, partition.minimum());
            assertEquals(partition.ordinal(), layout.partitionFor(partition.minimum()).ordinal());
            assertEquals(partition.ordinal(), layout.partitionFor(partition.maximum()).ordinal());
            expectedMinimum = partition.maximum() + 1;
            previousToken = partition.token();
        }
        assertEquals(layout.keySpace(), expectedMinimum);
    }

    @Test
    public void countsPartitionsTouchedByScalarRange()
    {
        VCompOrderedPartitionLayout layout = new VCompOrderedPartitionLayout(1003, 100);
        VCompOrderedPartitionLayout.Partition first = layout.partitions().get(17);
        VCompOrderedPartitionLayout.Partition last = layout.partitions().get(23);
        assertEquals(1, layout.partitionCount(first.minimum(), first.maximum()));
        assertEquals(7, layout.partitionCount(first.maximum(), last.minimum()));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMorePartitionsThanCoordinates()
    {
        new VCompOrderedPartitionLayout(4, 5);
    }
}
