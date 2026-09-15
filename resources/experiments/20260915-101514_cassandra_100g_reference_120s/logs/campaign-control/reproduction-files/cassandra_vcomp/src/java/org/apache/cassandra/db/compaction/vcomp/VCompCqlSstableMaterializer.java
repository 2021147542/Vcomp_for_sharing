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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PrimitiveIterator;
import java.util.UUID;
import java.util.Objects;
import java.util.Collections;
import java.util.stream.Stream;

import org.apache.cassandra.db.Clustering;
import org.apache.cassandra.db.DecoratedKey;
import org.apache.cassandra.db.DeletionTime;
import org.apache.cassandra.db.LivenessInfo;
import org.apache.cassandra.db.rows.AbstractUnfilteredRowIterator;
import org.apache.cassandra.db.rows.BTreeRow;
import org.apache.cassandra.db.rows.BufferCell;
import org.apache.cassandra.db.rows.EncodingStats;
import org.apache.cassandra.db.rows.Row;
import org.apache.cassandra.db.rows.Rows;
import org.apache.cassandra.db.rows.Unfiltered;
import org.apache.cassandra.db.rows.UnfilteredRowIterator;
import org.apache.cassandra.dht.Murmur3Partitioner;
import org.apache.cassandra.io.sstable.CQLSSTableWriter;
import org.apache.cassandra.schema.ColumnMetadata;
import org.apache.cassandra.schema.TableMetadata;
import org.apache.cassandra.serializers.UTF8Serializer;

/** Materializes final descriptors as Cassandra SSTables for the restricted one-partition schema. */
public final class VCompCqlSstableMaterializer implements VCompPipeline.FinalMaterializer
{
    private final Path outputDirectory;
    private final String tableSchema;
    private final String insertStatement;
    private final String keyspace;
    private final String table;
    private final String partitionKey;
    private final VCompOrderedPartitionLayout partitionLayout;
    private final int targetSSTableMiB;
    private final int logicalEntryBytes;
    private final VCompKeyCodec keyCodec;
    private final int valueBytes;

    public VCompCqlSstableMaterializer(Path outputDirectory,
                                       String tableSchema,
                                       String insertStatement,
                                       String keyspace,
                                       String table,
                                       String partitionKey,
                                       int logicalEntryBytes,
                                       VCompKeyCodec keyCodec,
                                       int valueBytes)
    {
        if (logicalEntryBytes <= 0)
            throw new IllegalArgumentException("logicalEntryBytes must be positive");
        if (valueBytes <= 0)
            throw new IllegalArgumentException("valueBytes must be positive");
        this.outputDirectory = Objects.requireNonNull(outputDirectory, "outputDirectory").toAbsolutePath();
        this.tableSchema = requireNonBlank(tableSchema, "tableSchema");
        this.insertStatement = requireNonBlank(insertStatement, "insertStatement");
        this.keyspace = requireNonBlank(keyspace, "keyspace");
        this.table = requireNonBlank(table, "table");
        this.partitionKey = requireNonBlank(partitionKey, "partitionKey");
        this.partitionLayout = null;
        this.targetSSTableMiB = 0;
        this.logicalEntryBytes = logicalEntryBytes;
        this.keyCodec = Objects.requireNonNull(keyCodec, "keyCodec");
        this.valueBytes = valueBytes;
    }

    public VCompCqlSstableMaterializer(Path outputDirectory,
                                       String tableSchema,
                                       String insertStatement,
                                       String keyspace,
                                       String table,
                                       VCompOrderedPartitionLayout partitionLayout,
                                       int targetSSTableMiB,
                                       int logicalEntryBytes,
                                       VCompKeyCodec keyCodec,
                                       int valueBytes)
    {
        if (targetSSTableMiB <= 0)
            throw new IllegalArgumentException("target SSTable MiB must be positive");
        if (logicalEntryBytes <= 0)
            throw new IllegalArgumentException("logicalEntryBytes must be positive");
        if (valueBytes <= 0)
            throw new IllegalArgumentException("valueBytes must be positive");
        this.outputDirectory = Objects.requireNonNull(outputDirectory, "outputDirectory").toAbsolutePath();
        this.tableSchema = requireNonBlank(tableSchema, "tableSchema");
        this.insertStatement = requireNonBlank(insertStatement, "insertStatement");
        this.keyspace = requireNonBlank(keyspace, "keyspace");
        this.table = requireNonBlank(table, "table");
        this.partitionKey = null;
        this.partitionLayout = Objects.requireNonNull(partitionLayout, "partitionLayout");
        this.targetSSTableMiB = targetSSTableMiB;
        this.logicalEntryBytes = logicalEntryBytes;
        this.keyCodec = Objects.requireNonNull(keyCodec, "keyCodec");
        this.valueBytes = valueBytes;
    }

    @Override
    public VCompPipeline.MaterializedState materialize(VCompPipeline.FrozenLayout layout) throws Exception
    {
        Files.createDirectories(outputDirectory);
        List<String> directories = new ArrayList<>();
        long materializedKeys = 0;
        for (VCompPipeline.VirtualSortedRun run : layout.runs())
        {
            for (VCompPipeline.VirtualSSTable descriptor : run.sstables())
            {
                Path directory = descriptorDirectory(descriptor);
                requireEmptyDirectory(directory);
                long timestamp = Math.max(1, descriptor.maximumTimestamp());
                long[] written = new long[1];
                CQLSSTableWriter.Builder builder = CQLSSTableWriter.builder()
                                                                  .inDirectory(directory.toString())
                                                                  .forTable(tableSchema)
                                                                  .using(insertStatement)
                                                                  .withPartitioner(Murmur3Partitioner.instance)
                                                                  .withEncodingStats(encodingStats(timestamp))
                                                                  .withEstimatedPartitionCount(1)
                                                                  .sorted();
                if (partitionLayout != null)
                    builder.withEstimatedPartitionCount(partitionLayout.partitionCount(descriptor.keyMin(),
                                                                                        descriptor.keyMax()));
                // The virtual compaction output has already been split at the
                // exact UCS full-ring shard boundaries. Applying CQL writer's
                // unrelated byte cap here would split those eight native
                // candidates a second time (14 files in the 1 GiB pilot).
                try (CQLSSTableWriter writer = builder.build())
                {
                    keyCodec.validateSchema(writer.tableMetadata());
                    CoordinateCursor keys = new CoordinateCursor(new VCompMaterializedKeyIterator(descriptor));
                    while (keys.hasNext())
                    {
                        VCompOrderedPartitionLayout.Partition mapped = partitionLayout == null
                                                                         ? null
                                                                         : partitionLayout.partitionFor(keys.peek());
                        try (UnfilteredRowIterator partition = partition(writer.tableMetadata(), keys,
                                                                         mapped, timestamp, written))
                        {
                            writer.addPartition(partition);
                        }
                    }
                    materializedKeys += written[0];
                }
                directories.add(directory.toString());
            }
        }
        return new VCompPipeline.MaterializedState(directories,
                                                   materializedKeys,
                                                   saturatedMultiply(materializedKeys, logicalEntryBytes));
    }

	/**
	 * Measure the first source flush with its actual buffered coordinates and
	 * partition occupancy. This non-installed probe supplies the offline UCS
	 * observed-flush input; the ordinary descriptor size model is unchanged.
	 *
	 * Like final materialization, this probe uses the batch maximum timestamp
	 * for every row. It therefore measures our writer, not native per-row
	 * timestamp encoding, and must not be described as an exact native flush.
	 */
	public long measureFirstFlushDataBytes(VCompPipeline.FlushBatch firstFlush) throws Exception
	{
		Objects.requireNonNull(firstFlush, "firstFlush");
		long[] keys = firstFlush.keyCoordinates();
		Path probeRoot = outputDirectory.resolve(".vcomp-calibration").resolve("first-source-flush");
		requireEmptyDirectory(probeRoot);
		// Keep the ordinary keyspace/table directory shape so native SSTable
		// inspection tools can resolve this retained, non-installed probe.
		UUID probeId = UUID.nameUUIDFromBytes("first-source-flush".getBytes(StandardCharsets.UTF_8));
		Path directory = probeRoot.resolve(keyspace).resolve(table + '-' + probeId.toString().replace("-", ""));
		requireEmptyDirectory(directory);
		long timestamp = Math.max(1, firstFlush.maximumTimestamp());
		long[] written = new long[1];
		CQLSSTableWriter.Builder builder = CQLSSTableWriter.builder()
		                                                  .inDirectory(directory.toString())
		                                                  .forTable(tableSchema)
		                                                  .using(insertStatement)
		                                                  .withPartitioner(Murmur3Partitioner.instance)
		                                                  .withEncodingStats(encodingStats(timestamp))
		                                                  .withEstimatedPartitionCount(1)
		                                                  .sorted();
		if (partitionLayout != null)
			builder.withEstimatedPartitionCount(partitionLayout.partitionCount(keys[0], keys[keys.length - 1]));
		try (CQLSSTableWriter writer = builder.build())
		{
			keyCodec.validateSchema(writer.tableMetadata());
			CoordinateCursor cursor = new CoordinateCursor(Arrays.stream(keys).iterator());
			while (cursor.hasNext())
			{
				VCompOrderedPartitionLayout.Partition mapped = partitionLayout == null
				                                             ? null : partitionLayout.partitionFor(cursor.peek());
				try (UnfilteredRowIterator partition = partition(writer.tableMetadata(), cursor, mapped, timestamp, written))
				{
					writer.addPartition(partition);
				}
			}
		}
		if (written[0] != firstFlush.keyCount())
			throw new IllegalStateException("first-flush sizing probe changed the buffered key count");
		long bytes = 0;
		try (Stream<Path> files = Files.walk(directory))
		{
			for (Path path : (Iterable<Path>) files::iterator)
				if (Files.isRegularFile(path) && path.getFileName().toString().endsWith("-Data.db"))
					bytes = saturatedAdd(bytes, Files.size(path));
		}
		if (bytes <= 0)
			throw new IllegalStateException("first-flush sizing probe produced no Data.db bytes");
		return bytes;
	}

	/**
	 * Write two small, non-installed SSTables and fit the physical-size model
	 * used for virtual sizing and output splitting. Calibration artifacts are
	 * retained below .vcomp-calibration for auditability and are never imported.
	 */
	public VCompSSTSizeModel calibrateSizeModel(int firstEntries, int secondEntries) throws Exception
	{
		if (firstEntries <= 0 || secondEntries <= firstEntries)
			throw new IllegalArgumentException("invalid SST calibration counts");
		Path calibrationRoot = outputDirectory.resolve(".vcomp-calibration");
		requireEmptyDirectory(calibrationRoot);
		VCompCqlSstableMaterializer calibration = partitionLayout == null
		                                             ? new VCompCqlSstableMaterializer(calibrationRoot,
		                                                                                 tableSchema, insertStatement,
		                                                                                 keyspace, table, partitionKey,
		                                                                                 logicalEntryBytes, keyCodec, valueBytes)
		                                             : new VCompCqlSstableMaterializer(calibrationRoot,
		                                                                                 tableSchema, insertStatement,
		                                                                                 keyspace, table, partitionLayout,
		                                                                                 targetSSTableMiB,
		                                                                                 logicalEntryBytes, keyCodec, valueBytes);
		long bytes1 = calibration.writeCalibration("calibration-small", firstEntries, 0);
		long bytes2 = calibration.writeCalibration("calibration-large", secondEntries,
		                                           Math.multiplyExact((long) firstEntries, 4));
		// UCS density uses SSTableReader.onDiskLength(), i.e. Data.db only.
		// Logical KV bytes are a flush-cadence input, not a physical-size floor.
		VCompSSTSizeModel model = new VCompSSTSizeModel();
		if (!model.addCalibration(firstEntries, bytes1, secondEntries, bytes2))
			throw new IllegalStateException("invalid physical SST calibration samples: "
			                                + firstEntries + '/' + bytes1 + ", "
			                                + secondEntries + '/' + bytes2);
		return model;
	}

	private long writeCalibration(String id, int entries, long keyBase) throws Exception
	{
		long[] keys = new long[entries];
		for (int i = 0; i < entries; i++) keys[i] = keyBase + i;
		VCompPipeline.VirtualSortedRun run = new DefaultFlushVirtualizer().virtualize(
		new VCompPipeline.FlushBatch(id, keys, saturatedMultiply(entries, logicalEntryBytes), 1));
		VCompPipeline.MaterializedState state = materialize(
		new VCompPipeline.FrozenLayout(Collections.singletonList(run)));
		long bytes = 0;
		for (String directory : state.sstableIds())
		{
			try (Stream<Path> paths = Files.walk(java.nio.file.Paths.get(directory)))
			{
				for (Path path : (Iterable<Path>) paths::iterator)
					if (Files.isRegularFile(path) && path.getFileName().toString().endsWith("-Data.db"))
						bytes = saturatedAdd(bytes, Files.size(path));
			}
		}
		return bytes;
	}

    private UnfilteredRowIterator partition(TableMetadata metadata,
                                             CoordinateCursor keys,
                                             VCompOrderedPartitionLayout.Partition mappedPartition,
                                             long timestamp,
                                             long[] written)
    {
        String currentPartitionKey = mappedPartition == null ? partitionKey : mappedPartition.key();
        DecoratedKey decoratedKey = metadata.partitioner.decorateKey(UTF8Serializer.instance.serialize(currentPartitionKey));
        ColumnMetadata valueColumn = metadata.regularColumns().iterator().next();
        byte[] value = new byte[valueBytes];
        return new AbstractUnfilteredRowIterator(metadata,
                                                 decoratedKey,
                                                 DeletionTime.LIVE,
                                                 metadata.regularAndStaticColumns(),
                                                 Rows.EMPTY_STATIC_ROW,
                                                 false,
                                                 encodingStats(timestamp))
        {
            @Override
            protected Unfiltered computeNext()
            {
                if (!keys.hasNext())
                    return endOfData();
                if (mappedPartition != null && !mappedPartition.contains(keys.peek()))
                    return endOfData();
                long key = keys.nextLong();
                written[0]++;
                fillValue(key, value);
                Row.Builder builder = BTreeRow.sortedBuilder();
                builder.newRow(Clustering.make(keyCodec.decode(key)));
                // Match the native CQL INSERT used by the baseline. A live
                // value cell alone has UPDATE semantics and a different row
                // encoding (the cell cannot reuse a missing row timestamp).
                builder.addPrimaryKeyLivenessInfo(LivenessInfo.create(timestamp, 0));
                builder.addCell(BufferCell.live(valueColumn, timestamp, ByteBuffer.wrap(value)));
                return builder.build();
            }
        };
    }

    private static EncodingStats encodingStats(long timestamp)
    {
        return new EncodingStats(timestamp, EncodingStats.NO_STATS.minLocalDeletionTime,
                                 EncodingStats.NO_STATS.minTTL);
    }

    private static final class CoordinateCursor
    {
        private final PrimitiveIterator.OfLong source;
        private boolean prepared;
        private long next;

        private CoordinateCursor(PrimitiveIterator.OfLong source)
        {
            this.source = source;
        }

        private boolean hasNext()
        {
            prepare();
            return prepared;
        }

        private long peek()
        {
            prepare();
            if (!prepared)
                throw new java.util.NoSuchElementException();
            return next;
        }

        private long nextLong()
        {
            long value = peek();
            prepared = false;
            return value;
        }

        private void prepare()
        {
            if (!prepared && source.hasNext())
            {
                next = source.nextLong();
                prepared = true;
            }
        }
    }

    private Path descriptorDirectory(VCompPipeline.VirtualSSTable descriptor)
    {
        UUID id = UUID.nameUUIDFromBytes(descriptor.id().getBytes(StandardCharsets.UTF_8));
        return outputDirectory.resolve(keyspace)
                              .resolve(table + '-' + id.toString().replace("-", ""));
    }

    public static byte[] valueFor(long key, int size)
    {
        byte[] value = new byte[size];
        fillValue(key, value);
        return value;
    }

    private static void fillValue(long key, byte[] value)
    {
        long state = key;
        for (int offset = 0; offset < value.length; offset += Long.BYTES)
        {
            state += 0x9e3779b97f4a7c15L;
            long mixed = state;
            mixed = (mixed ^ (mixed >>> 30)) * 0xbf58476d1ce4e5b9L;
            mixed = (mixed ^ (mixed >>> 27)) * 0x94d049bb133111ebL;
            mixed ^= mixed >>> 31;
            for (int byteIndex = 0; byteIndex < Long.BYTES && offset + byteIndex < value.length; byteIndex++)
                value[offset + byteIndex] = (byte) (mixed >>> (byteIndex * Byte.SIZE));
        }
    }

    private static void requireEmptyDirectory(Path directory) throws IOException
    {
        Files.createDirectories(directory);
        try (java.util.stream.Stream<Path> entries = Files.list(directory))
        {
            if (entries.findAny().isPresent())
                throw new IllegalStateException("materialization directory is not empty: " + directory);
        }
    }

    private static long saturatedMultiply(long left, long right)
    {
        if (left != 0 && right > Long.MAX_VALUE / left)
            return Long.MAX_VALUE;
        return left * right;
    }

	private static long saturatedAdd(long left, long right)
	{
		return right > Long.MAX_VALUE - left ? Long.MAX_VALUE : left + right;
	}

    private static String requireNonBlank(String value, String name)
    {
        Objects.requireNonNull(value, name);
        if (value.trim().isEmpty())
            throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
