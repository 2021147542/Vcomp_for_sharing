/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.cassandra.db.compaction.vcomp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
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
import org.apache.cassandra.utils.ByteBufferUtil;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VCompCqlSstableMaterializerTest
{
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void materializedRowsRetainInsertLivenessAndEncodingMinima() throws Exception
    {
        roundTrip(24, 1000, 1);
        roundTrip(48, 43, 4);
    }

    @Test
    public void calibrationUsesNativeDataComponentBytes() throws Exception
    {
        String keyspace = "vcomp_materializer_test";
        String table = "calibration_bytes";
        String schema = "CREATE TABLE " + keyspace + '.' + table
                        + " (partition_id text, ck blob, value blob, PRIMARY KEY ((partition_id), ck))"
                        + " WITH compression = {'enabled': 'false'}";
        String insert = "INSERT INTO " + keyspace + '.' + table
                        + " (partition_id, ck, value) VALUES (?, ?, ?) USING TIMESTAMP ?";
        Path output = temporaryFolder.newFolder(table).toPath();
        VCompCqlSstableMaterializer materializer = new VCompCqlSstableMaterializer(
        output, schema, insert, keyspace, table, new VCompOrderedPartitionLayout(4096, 1),
        64, 1024, new DeterministicFixedWidthKeyCodec(24), 1000);
        VCompSSTSizeModel model = materializer.calibrateSizeModel(32, 64);
        java.util.List<Long> dataSizes = new java.util.ArrayList<>();
        long componentBytes = 0;
        try (Stream<Path> files = Files.walk(output.resolve(".vcomp-calibration")))
        {
            for (Path path : (Iterable<Path>) files::iterator)
            {
                if (!Files.isRegularFile(path))
                    continue;
                componentBytes += Files.size(path);
                if (path.getFileName().toString().endsWith("-Data.db"))
                    dataSizes.add(Files.size(path));
            }
        }
        Collections.sort(dataSizes);
        assertEquals(2, dataSizes.size());
        assertTrue("calibration has non-data components that must be excluded",
                   componentBytes > dataSizes.get(0) + dataSizes.get(1));
        // With one partition the same fixed row/partition framing applies to
        // both probes. The affine estimate should recover their Data.db bytes.
        assertEquals(dataSizes.get(0).longValue(), model.estimate(32));
        assertEquals(dataSizes.get(1).longValue(), model.estimate(64));
    }

    private void roundTrip(int keyBytes, int valueBytes, int partitionCount) throws Exception
    {
        String keyspace = "vcomp_materializer_test";
        String table = "kv" + keyBytes;
        String schema = "CREATE TABLE " + keyspace + '.' + table
                        + " (partition_id text, ck blob, value blob, PRIMARY KEY ((partition_id), ck))"
                        + " WITH compression = {'enabled': 'false'}";
        String insert = "INSERT INTO " + keyspace + '.' + table
                        + " (partition_id, ck, value) VALUES (?, ?, ?) USING TIMESTAMP ?";
        long[] keys = { 0, 2, 5, 8, 11, 14 };
        long timestamp = 12345;
        int entryBytes = keyBytes + valueBytes;
        VCompOrderedPartitionLayout partitions = new VCompOrderedPartitionLayout(16, partitionCount);
        DeterministicFixedWidthKeyCodec codec = new DeterministicFixedWidthKeyCodec(keyBytes);
        Path output = temporaryFolder.newFolder(table).toPath();
        VCompCqlSstableMaterializer materializer = new VCompCqlSstableMaterializer(
        output, schema, insert, keyspace, table, partitions, 64, entryBytes, codec, valueBytes);
        VCompPipeline.VirtualSortedRun run = new DefaultFlushVirtualizer(0, 64, 2).virtualize(
        new VCompPipeline.FlushBatch("flush", keys, (long) keys.length * entryBytes, timestamp));
        VCompPipeline.MaterializedState state = materializer.materialize(
        new VCompPipeline.FrozenLayout(Collections.singletonList(run)));
        assertEquals(keys.length, state.materializedKeys());

        Path data;
        try (Stream<Path> files = Files.walk(java.nio.file.Paths.get(state.sstableIds().get(0))))
        {
            data = files.filter(p -> p.toString().endsWith("-Data.db")).findFirst().get();
        }
        SSTableReader reader = SSTableReader.open(null, Descriptor.fromFile(new org.apache.cassandra.io.util.File(data)),
                                                  Schema.instance.getTableMetadataRef(keyspace, table));
        try
        {
            assertEquals(timestamp, reader.header.stats().minTimestamp);
            int index = 0;
            try (ISSTableScanner scanner = reader.getScanner())
            {
                while (scanner.hasNext())
                {
                    try (UnfilteredRowIterator partition = scanner.next())
                    {
                        while (partition.hasNext())
                        {
                            Row row = (Row) partition.next();
                            long key = codec.encode(row.clustering());
                            assertEquals(keys[index++], key);
                            assertFalse(row.primaryKeyLivenessInfo().isEmpty());
                            assertEquals(timestamp, row.primaryKeyLivenessInfo().timestamp());
                            assertEquals(timestamp, row.cells().iterator().next().timestamp());
                            assertArrayEquals(VCompCqlSstableMaterializer.valueFor(key, valueBytes),
                                              ByteBufferUtil.getArray(row.cells().iterator().next().buffer()));
                        }
                    }
                }
            }
            assertEquals(keys.length, index);
        }
        finally
        {
            reader.selfRef().release();
        }
    }
}
