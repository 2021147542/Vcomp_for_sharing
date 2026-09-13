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
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import org.apache.cassandra.db.rows.Cell;
import org.apache.cassandra.db.rows.Row;
import org.apache.cassandra.db.rows.Unfiltered;
import org.apache.cassandra.db.rows.UnfilteredRowIterator;
import org.apache.cassandra.db.rows.WrappingUnfilteredRowIterator;
import org.apache.cassandra.io.sstable.SSTableMultiWriter;
import org.apache.cassandra.io.sstable.format.SSTableReader;
import org.apache.cassandra.schema.ColumnMetadata;
import org.apache.cassandra.schema.TableId;
import org.apache.cassandra.schema.TableMetadata;
import org.apache.cassandra.utils.ByteBufferUtil;

/**
 * Decorates Cassandra's ordinary flush writer and observes the clustering keys it consumes.
 * Physical SSTable writing is unchanged; this class only captures enough metadata to build a vSST.
 * It is a tested integration building block, but the standalone bulk-load experiment does not
 * install it into Cassandra's live flush path.
 */
public final class VCompTrackingSSTableMultiWriter implements SSTableMultiWriter
{
    private static final int INITIAL_KEY_CAPACITY = 1024;

    private final SSTableMultiWriter delegate;
    private final String flushId;
    private final VCompKeyCodec keyCodec;
    private final VCompPipeline.FlushVirtualizer virtualizer;
    private final FlushObserver observer;

    private long[] keys = new long[INITIAL_KEY_CAPACITY];
    private int keyCount;
    private long maximumTimestamp = Long.MIN_VALUE;
    private ByteBuffer sharedPartitionKey;
    private TableMetadata metadata;
    private VCompPipeline.VirtualSortedRun preparedRun;
    private boolean committed;
    private boolean published;

    public VCompTrackingSSTableMultiWriter(SSTableMultiWriter delegate,
                                           String flushId,
                                           VCompKeyCodec keyCodec,
                                           VCompPipeline.FlushVirtualizer virtualizer,
                                           FlushObserver observer)
    {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.flushId = Objects.requireNonNull(flushId, "flushId");
        this.keyCodec = Objects.requireNonNull(keyCodec, "keyCodec");
        this.virtualizer = Objects.requireNonNull(virtualizer, "virtualizer");
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    @Override
    public void append(UnfilteredRowIterator partition)
    {
        validatePartition(partition);
        delegate.append(trackingIterator(partition));
    }

    private void validatePartition(UnfilteredRowIterator partition)
    {
        if (partition.isReverseOrder())
            throw new IllegalArgumentException("VComp flush input must use forward clustering order");
        if (!partition.partitionLevelDeletion().isLive())
            throw new IllegalArgumentException("VComp prototype does not support partition deletions");
        if (!partition.staticRow().isEmpty())
            throw new IllegalArgumentException("VComp prototype does not support static rows");

        if (metadata == null)
        {
            metadata = partition.metadata();
            validateRestrictedSchema(metadata);
            keyCodec.validateSchema(metadata);
        }
        else if (!metadata.id.equals(partition.metadata().id))
        {
            throw new IllegalArgumentException("one VComp flush writer cannot mix table schemas");
        }

        ByteBuffer partitionKey = partition.partitionKey().getKey();
        if (sharedPartitionKey == null)
            sharedPartitionKey = ByteBufferUtil.clone(partitionKey);
        else if (ByteBufferUtil.compareUnsigned(sharedPartitionKey, partitionKey) != 0)
            throw new IllegalArgumentException("VComp prototype requires one shared partition key");
    }

    private static void validateRestrictedSchema(TableMetadata metadata)
    {
        if (metadata.partitionKeyColumns().size() != 1)
            throw new IllegalArgumentException("VComp prototype requires one partition-key column");
        if (metadata.clusteringColumns().size() != 1)
            throw new IllegalArgumentException("VComp prototype requires one clustering column");
        if (metadata.regularColumns().size() != 1)
            throw new IllegalArgumentException("VComp prototype requires one regular value column");
        if (!metadata.staticColumns().isEmpty())
            throw new IllegalArgumentException("VComp prototype does not support static columns");

        ColumnMetadata valueColumn = metadata.regularColumns().iterator().next();
        if (valueColumn.isComplex())
            throw new IllegalArgumentException("VComp prototype does not support complex value columns");
    }

    private UnfilteredRowIterator trackingIterator(UnfilteredRowIterator partition)
    {
        return new WrappingUnfilteredRowIterator()
        {
            @Override
            public UnfilteredRowIterator wrapped()
            {
                return partition;
            }

            @Override
            public Unfiltered next()
            {
                Unfiltered unfiltered = partition.next();
                if (!unfiltered.isRow())
                    throw new IllegalArgumentException("VComp prototype does not support range tombstones");

                Row row = (Row) unfiltered;
                validateAndRecord(row);
                return row;
            }
        };
    }

    private void validateAndRecord(Row row)
    {
        if (!row.deletion().isLive())
            throw new IllegalArgumentException("VComp prototype does not support row deletions");
        if (row.primaryKeyLivenessInfo().isExpiring())
            throw new IllegalArgumentException("VComp prototype does not support expiring row liveness");

        ColumnMetadata valueColumn = metadata.regularColumns().iterator().next();
        int cellCount = 0;
        for (Cell<?> cell : row.cells())
        {
            cellCount++;
            if (!cell.column().equals(valueColumn))
                throw new IllegalArgumentException("VComp row contains a column other than its one value column");
            if (cell.isCounterCell())
                throw new IllegalArgumentException("VComp prototype does not support counter cells");
            if (cell.isTombstone())
                throw new IllegalArgumentException("VComp prototype does not support cell tombstones");
            if (cell.isExpiring())
                throw new IllegalArgumentException("VComp prototype does not support cell TTLs");
            maximumTimestamp = Math.max(maximumTimestamp, cell.timestamp());
        }
        if (cellCount != 1)
            throw new IllegalArgumentException("VComp rows must contain exactly one live value cell");

        addKey(keyCodec.encode(row.clustering()));
    }

    private void addKey(long key)
    {
        if (keyCount > 0 && keys[keyCount - 1] >= key)
            throw new IllegalArgumentException("VComp flush keys must be strictly increasing");
        if (keyCount == keys.length)
        {
            long[] expanded = new long[Math.multiplyExact(keys.length, 2)];
            System.arraycopy(keys, 0, expanded, 0, keys.length);
            keys = expanded;
        }
        keys[keyCount++] = key;
    }

    private void prepareVirtualRun()
    {
        if (preparedRun != null)
            return;
        if (keyCount == 0)
            throw new IllegalStateException("cannot prepare an empty VComp flush");

        long[] capturedKeys = new long[keyCount];
        System.arraycopy(keys, 0, capturedKeys, 0, keyCount);
        VCompPipeline.FlushBatch batch = new VCompPipeline.FlushBatch(flushId,
                                                                      capturedKeys,
                                                                      delegate.getBytesWritten(),
                                                                      maximumTimestamp);
        try
        {
            preparedRun = virtualizer.virtualize(batch);
        }
        catch (RuntimeException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new RuntimeException("failed to virtualize Cassandra flush " + flushId, e);
        }
    }

    private void publish(Collection<SSTableReader> readers)
    {
        if (published)
            return;
        if (!committed)
            throw new IllegalStateException("cannot publish a VComp descriptor before commit");

        List<SSTableReader> immutableReaders = Collections.unmodifiableList(new ArrayList<>(readers));
        observer.onFlushCommitted(preparedRun, immutableReaders);
        published = true;
    }

    @Override
    public Collection<SSTableReader> finish(boolean openResult)
    {
        prepareVirtualRun();
        Collection<SSTableReader> readers = delegate.finish(openResult);
        committed = true;
        publish(readers);
        return readers;
    }

    @Override
    public Collection<SSTableReader> finished()
    {
        Collection<SSTableReader> readers = delegate.finished();
        publish(readers);
        return readers;
    }

    @Override
    public SSTableMultiWriter setOpenResult(boolean openResult)
    {
        delegate.setOpenResult(openResult);
        return this;
    }

    @Override
    public String getFilename()
    {
        return delegate.getFilename();
    }

    @Override
    public long getBytesWritten()
    {
        return delegate.getBytesWritten();
    }

    @Override
    public long getOnDiskBytesWritten()
    {
        return delegate.getOnDiskBytesWritten();
    }

    @Override
    public TableId getTableId()
    {
        return delegate.getTableId();
    }

    @Override
    public Throwable commit(Throwable accumulate)
    {
        Throwable result = delegate.commit(accumulate);
        committed = result == null;
        return result;
    }

    @Override
    public Throwable abort(Throwable accumulate)
    {
        preparedRun = null;
        committed = false;
        return delegate.abort(accumulate);
    }

    @Override
    public void prepareToCommit()
    {
        prepareVirtualRun();
        delegate.prepareToCommit();
    }

    @Override
    public void close()
    {
        delegate.close();
    }

    public interface FlushObserver
    {
        void onFlushCommitted(VCompPipeline.VirtualSortedRun run, Collection<SSTableReader> physicalSSTables);
    }
}
