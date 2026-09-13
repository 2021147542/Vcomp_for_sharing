/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.cassandra.db.compaction.vcomp;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class VCompDiscreteCdfTest
{
    @Test
    public void selectionCountingCursorAndSliceAgree()
    {
        VCompDiscreteCdf cdf = new VCompDiscreteCdf(Arrays.asList(
        new VCompDiscreteCdf.Interval(10, 19, 4),
        new VCompDiscreteCdf.Interval(30, 39, 3)));
        assertEquals(7, cdf.count());
        for (long rank = 0; rank < cdf.count(); rank++)
            assertEquals(rank, cdf.countLessThan(cdf.select(rank)));

        VCompDiscreteCdf child = cdf.slice(2, 4);
        assertEquals(4, child.count());
        for (long rank = 0; rank < child.count(); rank++)
            assertEquals(cdf.select(rank + 2), child.select(rank));

        VCompDiscreteCdf.Cursor cursor = child.cursor();
        long rank = 0;
        while (cursor.hasNext())
            assertEquals(child.select(rank++), cursor.nextLong());
        assertEquals(child.count(), rank);
    }

    @Test
    public void physicalSizeCalibrationHasExactInverse()
    {
        VCompSSTSizeModel model = VCompSSTSizeModel.logical(100);
        org.junit.Assert.assertTrue(model.addCalibration(10, 1200, 20, 2200));
        assertEquals(1700, model.estimate(15));
        assertEquals(15, model.maxEntries(1700));
    }
}
