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
        VCompPipeline.VirtualSortedRun run = new DefaultFlushVirtualizer().virtualize(
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
