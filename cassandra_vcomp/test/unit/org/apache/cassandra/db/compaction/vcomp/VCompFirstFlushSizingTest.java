/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.cassandra.db.compaction.vcomp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import org.apache.cassandra.db.rows.Row;
import org.apache.cassandra.db.rows.UnfilteredRowIterator;
import org.apache.cassandra.io.sstable.Descriptor;
import org.apache.cassandra.io.sstable.ISSTableScanner;
import org.apache.cassandra.io.sstable.format.SSTableReader;
import org.apache.cassandra.schema.Schema;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class VCompFirstFlushSizingTest
{
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void sizingRetainsSparseInputAndCountsOnlyDataComponent() throws Exception
    {
        String keyspace = "vcomp_first_flush_sizing", table = "kv";
        String schema = "CREATE TABLE " + keyspace + '.' + table
                        + " (partition_id text, ck blob, value blob, PRIMARY KEY ((partition_id), ck))"
                        + " WITH compression = {'enabled': 'false'}";
        String insert = "INSERT INTO " + keyspace + '.' + table
                        + " (partition_id, ck, value) VALUES (?, ?, ?) USING TIMESTAMP ?";
        Path output = temporaryFolder.newFolder("sizing").toPath();
        DeterministicFixedWidthKeyCodec codec = new DeterministicFixedWidthKeyCodec(24);
        VCompCqlSstableMaterializer materializer = new VCompCqlSstableMaterializer(
        output, schema, insert, keyspace, table, new VCompOrderedPartitionLayout(1024, 16),
        64, 1024, codec, 1000);
        materializer.calibrateSizeModel(8, 16);
        SyntheticVCompLoadSource source = new SyntheticVCompLoadSource(64, 32, 1024, 1024, 20260909);
        VCompPipeline.FlushBatch first = source.flushBatches().iterator().next();
        long bytes = materializer.measureFirstFlushDataBytes(first);
        assertArrayEquals("sizing must not consume or change the load stream", first.keyCoordinates(),
                          source.flushBatches().iterator().next().keyCoordinates());

        Path probe = output.resolve(".vcomp-calibration").resolve("first-source-flush");
        List<Path> dataFiles = new ArrayList<>();
        long allBytes = 0;
        try (Stream<Path> files = Files.walk(probe))
        {
            for (Path file : (Iterable<Path>) files::iterator)
            {
                if (!Files.isRegularFile(file)) continue;
                allBytes += Files.size(file);
                if (file.getFileName().toString().endsWith("-Data.db")) dataFiles.add(file);
            }
        }
        assertEquals(1, dataFiles.size());
        assertEquals(Files.size(dataFiles.get(0)), bytes);
        assertTrue("non-data components must not enter the UCS density input", allBytes > bytes);
        SSTableReader reader = SSTableReader.open(null,
        Descriptor.fromFile(new org.apache.cassandra.io.util.File(dataFiles.get(0))),
        Schema.instance.getTableMetadataRef(keyspace, table));
        List<Long> actual = new ArrayList<>();
        try
        {
            try (ISSTableScanner scanner = reader.getScanner())
            {
                while (scanner.hasNext())
                {
                    try (UnfilteredRowIterator partition = scanner.next())
                    {
                        while (partition.hasNext())
                        {
                            Row row = (Row) partition.next();
                            actual.add(codec.encode(row.clustering()));
                            assertEquals(first.maximumTimestamp(), row.primaryKeyLivenessInfo().timestamp());
                        }
                    }
                }
            }
        }
        finally
        {
            reader.selfRef().release();
        }
        assertArrayEquals("probe must use buffered keys, not PLR-generated replacements", first.keyCoordinates(),
                          actual.stream().mapToLong(Long::longValue).toArray());
    }
}
