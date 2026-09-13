/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.cassandra.db.compaction.vcomp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class VCompFilesystemVerifierTest
{
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void acceptsPaperPathCollapsedMaterializationCount() throws Exception
    {
        Path directory = temporaryFolder.newFolder("sstable").toPath();
        Files.write(directory.resolve("oa-1-big-Data.db"), new byte[]{ 1 });
        VCompPipeline.FrozenLayout layout = layout(10);
        VCompPipeline.MaterializedState truncated = new VCompPipeline.MaterializedState(
        Collections.singletonList(directory.toString()), 9, 9 * 1024L);

        new VCompFilesystemVerifier().verify(layout, truncated);
    }

    @Test
    public void acceptsExactMaterializationCount() throws Exception
    {
        Path directory = temporaryFolder.newFolder("sstable").toPath();
        Files.write(directory.resolve("oa-1-big-Data.db"), new byte[]{ 1 });
        new VCompFilesystemVerifier().verify(
        layout(10),
        new VCompPipeline.MaterializedState(Collections.singletonList(directory.toString()), 10, 10 * 1024L));
    }

    @Test
    public void acceptsMultiplePhysicalSSTablesPerFinalDescriptor() throws Exception
    {
        Path directory = temporaryFolder.newFolder("sstables").toPath();
        Files.write(directory.resolve("oa-1-big-Data.db"), new byte[]{ 1 });
        Files.write(directory.resolve("oa-2-big-Data.db"), new byte[]{ 1 });
        new VCompFilesystemVerifier().verify(
        layout(10),
        new VCompPipeline.MaterializedState(Collections.singletonList(directory.toString()), 9, 9 * 1024L));
    }

    @Test
    public void rejectsMaterializationAboveDescriptorEstimate() throws Exception
    {
        Path directory = temporaryFolder.newFolder("overflow").toPath();
        Files.write(directory.resolve("oa-1-big-Data.db"), new byte[]{ 1 });
        try
        {
            new VCompFilesystemVerifier().verify(
            layout(10),
            new VCompPipeline.MaterializedState(Collections.singletonList(directory.toString()), 11, 11 * 1024L));
            fail("expected an over-count to be rejected");
        }
        catch (IllegalStateException e)
        {
            assertTrue(e.getMessage().contains("exceeds"));
        }
    }

    private static VCompPipeline.FrozenLayout layout(int count)
    {
        long[] keys = new long[count];
        for (int i = 0; i < count; i++) keys[i] = i;
        VCompPipeline.VirtualSSTable sstable = new VCompPipeline.VirtualSSTable(
        "vsst", 0, count - 1, count, count * 1024L,
        VCompLearnedModel.greedyFit(keys, 0), VCompKmvSketch.build(keys, count), Collections.emptyList());
        return new VCompPipeline.FrozenLayout(Collections.singletonList(
        new VCompPipeline.VirtualSortedRun("run", 0, Collections.singletonList(sstable))));
    }
}
