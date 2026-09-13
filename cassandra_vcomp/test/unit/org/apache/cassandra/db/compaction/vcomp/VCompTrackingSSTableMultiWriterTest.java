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

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import org.junit.Test;

import org.apache.cassandra.db.BufferDecoratedKey;
import org.apache.cassandra.db.Clustering;
import org.apache.cassandra.db.DeletionTime;
import org.apache.cassandra.db.marshal.AsciiType;
import org.apache.cassandra.db.marshal.BytesType;
import org.apache.cassandra.db.rows.AbstractUnfilteredRowIterator;
import org.apache.cassandra.db.rows.BTreeRow;
import org.apache.cassandra.db.rows.BufferCell;
import org.apache.cassandra.db.rows.EncodingStats;
import org.apache.cassandra.db.rows.Row;
import org.apache.cassandra.db.rows.Rows;
import org.apache.cassandra.db.rows.Unfiltered;
import org.apache.cassandra.db.rows.UnfilteredRowIterator;
import org.apache.cassandra.dht.Murmur3Partitioner;
import org.apache.cassandra.io.sstable.SSTableMultiWriter;
import org.apache.cassandra.io.sstable.format.SSTableReader;
import org.apache.cassandra.schema.ColumnMetadata;
import org.apache.cassandra.schema.TableId;
import org.apache.cassandra.schema.TableMetadata;
import org.apache.cassandra.utils.ByteBufferUtil;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class VCompTrackingSSTableMultiWriterTest
{
    private static final int KEY_BYTES = 24;

    @Test
    public void fixedWidthCodecRoundTripsAndUsesEveryConfiguredWidth()
    {
        TableMetadata metadata = metadata();
        for (int width : new int[]{ 24, 48 })
        {
            DeterministicFixedWidthKeyCodec codec = new DeterministicFixedWidthKeyCodec(width);
            codec.validateSchema(metadata);
            ByteBuffer key = codec.decode(123456789L);
            assertEquals(width, key.remaining());
            assertEquals(123456789L, codec.encode(Clustering.make(key)));
            assertFalse(allZero(key, Long.BYTES, width));
        }
    }

    @Test
    public void fixedWidthCodecPreservesOrderAndRejectsCorruption()
    {
        for (int width : new int[]{ 24, 48 })
        {
            DeterministicFixedWidthKeyCodec codec = new DeterministicFixedWidthKeyCodec(width);
            ByteBuffer lower = codec.decode(123456788L);
            ByteBuffer higher = codec.decode(123456789L);
            assertTrue(ByteBufferUtil.compareUnsigned(lower, higher) < 0);

            ByteBuffer corrupt = higher.duplicate();
            corrupt.put(corrupt.position() + Long.BYTES,
                        (byte) (corrupt.get(corrupt.position() + Long.BYTES) ^ 1));
            try
            {
                codec.encode(corrupt);
                fail("corrupted deterministic payload was accepted");
            }
            catch (IllegalArgumentException expected)
            {
                // Expected.
            }
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void fixedWidthCodecRejectsUnsupportedWidth()
    {
        new DeterministicFixedWidthKeyCodec(32);
    }

    @Test
    public void publishesVirtualRunOnlyAfterPhysicalCommit()
    {
        TableMetadata metadata = metadata();
        RecordingWriter delegate = new RecordingWriter(metadata.id);
        List<VCompPipeline.VirtualSortedRun> published = new ArrayList<>();
        VCompTrackingSSTableMultiWriter writer = new VCompTrackingSSTableMultiWriter(delegate,
                                                                                     "flush-7",
                                                                                     new DeterministicFixedWidthKeyCodec(KEY_BYTES),
                                                                                     new DefaultFlushVirtualizer(0, 16, 2),
                                                                                     (run, readers) -> published.add(run));

        writer.append(rows(metadata, row(metadata, 10), row(metadata, 20), row(metadata, 50)));
        assertEquals(3, delegate.rowCount);

        writer.prepareToCommit();
        assertTrue(published.isEmpty());
        assertNull(writer.commit(null));
        assertTrue(published.isEmpty());

        writer.finished();
        writer.finished();
        assertEquals(1, published.size());

        VCompPipeline.VirtualSSTable descriptor = published.get(0).sstables().get(0);
        assertEquals("run-flush-7", published.get(0).id());
        assertEquals(10, descriptor.keyMin());
        assertEquals(50, descriptor.keyMax());
        assertEquals(3, descriptor.estimatedUniqueKeys());
        assertEquals(50, descriptor.maximumTimestamp());
        assertFalse(descriptor.model().isEmpty());
        assertTrue(descriptor.sketch().isComplete());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsASecondPartitionKey()
    {
        TableMetadata metadata = metadata();
        RecordingWriter delegate = new RecordingWriter(metadata.id);
        VCompTrackingSSTableMultiWriter writer = new VCompTrackingSSTableMultiWriter(delegate,
                                                                                     "flush-8",
                                                                                     new DeterministicFixedWidthKeyCodec(KEY_BYTES),
                                                                                     new DefaultFlushVirtualizer(),
                                                                                     (run, readers) -> { });

        writer.append(rows(metadata, "partition-a", row(metadata, 10)));
        writer.append(rows(metadata, "partition-b", row(metadata, 20)));
    }

    private static TableMetadata metadata()
    {
        return TableMetadata.builder("vcomp", "kv")
                            .addPartitionKeyColumn("partition", AsciiType.instance)
                            .addClusteringColumn("key", BytesType.instance)
                            .addRegularColumn("value", BytesType.instance)
                            .build();
    }

    private static Row row(TableMetadata metadata, long coordinate)
    {
        ColumnMetadata valueColumn = metadata.regularColumns().iterator().next();
        Row.Builder builder = BTreeRow.sortedBuilder();
        builder.newRow(Clustering.make(key(coordinate)));
        builder.addCell(BufferCell.live(valueColumn, coordinate, ByteBufferUtil.bytes("value")));
        return builder.build();
    }

    private static ByteBuffer key(long coordinate)
    {
        ByteBuffer key = ByteBuffer.allocate(KEY_BYTES);
        return new DeterministicFixedWidthKeyCodec(KEY_BYTES).decode(coordinate);
    }

    private static boolean allZero(ByteBuffer value, int start, int end)
    {
        for (int i = start; i < end; i++)
        {
            if (value.get(value.position() + i) != 0)
                return false;
        }
        return true;
    }

    private static UnfilteredRowIterator rows(TableMetadata metadata, Row... rows)
    {
        return rows(metadata, "shared-partition", rows);
    }

    private static UnfilteredRowIterator rows(TableMetadata metadata, String partitionKey, Row... rows)
    {
        Iterator<Unfiltered> iterator = new ArrayList<Unfiltered>(Arrays.asList(rows)).iterator();
        return new AbstractUnfilteredRowIterator(metadata,
                                                 new BufferDecoratedKey(new Murmur3Partitioner.LongToken(0),
                                                                        ByteBufferUtil.bytes(partitionKey)),
                                                 DeletionTime.LIVE,
                                                 metadata.regularAndStaticColumns(),
                                                 Rows.EMPTY_STATIC_ROW,
                                                 false,
                                                 EncodingStats.NO_STATS)
        {
            @Override
            protected Unfiltered computeNext()
            {
                return iterator.hasNext() ? iterator.next() : endOfData();
            }
        };
    }

    private static final class RecordingWriter implements SSTableMultiWriter
    {
        private final TableId tableId;
        private int rowCount;

        private RecordingWriter(TableId tableId)
        {
            this.tableId = tableId;
        }

        @Override
        public void append(UnfilteredRowIterator partition)
        {
            while (partition.hasNext())
            {
                partition.next();
                rowCount++;
            }
        }

        @Override
        public Collection<SSTableReader> finish(boolean openResult)
        {
            return Collections.emptyList();
        }

        @Override
        public Collection<SSTableReader> finished()
        {
            return Collections.emptyList();
        }

        @Override
        public SSTableMultiWriter setOpenResult(boolean openResult)
        {
            return this;
        }

        @Override
        public String getFilename()
        {
            return "fake";
        }

        @Override
        public long getBytesWritten()
        {
            return rowCount * 1024L;
        }

        @Override
        public long getOnDiskBytesWritten()
        {
            return getBytesWritten();
        }

        @Override
        public TableId getTableId()
        {
            return tableId;
        }

        @Override
        public Throwable commit(Throwable accumulate)
        {
            return accumulate;
        }

        @Override
        public Throwable abort(Throwable accumulate)
        {
            return accumulate;
        }

        @Override
        public void prepareToCommit()
        {
        }

        @Override
        public void close()
        {
        }
    }
}
