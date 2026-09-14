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
package org.apache.cassandra.io.util;

import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import org.apache.cassandra.cache.ChunkCache;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.db.ClusteringComparator;
import org.apache.cassandra.db.marshal.BytesType;
import org.apache.cassandra.io.compress.CompressedSequentialWriter;
import org.apache.cassandra.io.compress.CompressionMetadata;
import org.apache.cassandra.io.sstable.metadata.MetadataCollector;
import org.apache.cassandra.schema.CompressionParams;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Reproduces early-open readers sharing a growing SST component through the chunk cache. */
public class ChunkCacheFileHandleTest
{
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @BeforeClass
    public static void initialize()
    {
        DatabaseDescriptor.daemonInitialization();
        assertNotNull("test requires enabled native chunk cache", ChunkCache.instance);
    }

    @Test
    public void differentChunkSizesDoNotShareAnUndersizedBuffer() throws Exception
    {
        byte[] bytes = contents(16384);
        File file = writeFile(bytes);
        try (FileHandle small = handle(file, 4096);
             RandomAccessReader first = small.createReader())
        {
            assertEquals(bytes[0], first.readByte());
            try (FileHandle large = handle(file, 8192);
                 RandomAccessReader second = large.createReader())
            {
                second.seek(7523);
                assertEquals(bytes[7523], second.readByte());
            }
        }
    }

    @Test
    public void appendedTailDoesNotReuseAnEarlierShortRead() throws Exception
    {
        byte[] bytes = contents(8192);
        File file = writeFile(Arrays.copyOf(bytes, 4096));
        try (FileHandle early = handle(file, 8192);
             RandomAccessReader first = early.createReader())
        {
            assertEquals(bytes[0], first.readByte());
            Files.write(file.toPath(), Arrays.copyOfRange(bytes, 4096, bytes.length), StandardOpenOption.APPEND);
            try (FileHandle later = handle(file, 8192);
                 RandomAccessReader second = later.createReader())
            {
                byte[] actual = new byte[bytes.length];
                second.readFully(actual);
                assertArrayEquals(bytes, actual);
            }
        }
    }

    @Test
    public void completeChunksRemainSharedWhenTheFileGrows() throws Exception
    {
        byte[] bytes = contents(12288);
        File file = writeFile(Arrays.copyOf(bytes, 8192));
        try (FileHandle early = handle(file, 4096);
             RandomAccessReader first = early.createReader())
        {
            assertEquals(bytes[0], first.readByte());
            Files.write(file.toPath(), Arrays.copyOfRange(bytes, 8192, bytes.length), StandardOpenOption.APPEND);
            long hits = ChunkCache.instance.metrics.hits.getCount();
            try (FileHandle later = handle(file, 4096);
                 RandomAccessReader second = later.createReader())
            {
                assertEquals(bytes[0], second.readByte());
                assertEquals(hits + 1, ChunkCache.instance.metrics.hits.getCount());
            }
        }
    }

    @Test
    public void invalidatingTheCurrentViewReloadsItsChunk() throws Exception
    {
        File file = writeFile(contents(8192));
        try (FileHandle handle = handle(file, 4096);
             RandomAccessReader first = handle.createReader())
        {
            first.readByte();
            long misses = ChunkCache.instance.metrics.misses.getCount();
            ChunkCache.instance.invalidatePosition(handle, 0);
            try (RandomAccessReader second = handle.createReader())
            {
                second.readByte();
                assertEquals(misses + 1, ChunkCache.instance.metrics.misses.getCount());
            }
        }
    }

    @Test
    public void compressedCacheGeometryUsesUncompressedPositions() throws Exception
    {
        byte[] bytes = contents(65536 + 123);
        File file = new File(temporaryFolder.newFile());
        File metadata = new File(temporaryFolder.newFile());
        try (CompressedSequentialWriter writer = new CompressedSequentialWriter(
        file, metadata, null, SequentialWriterOption.DEFAULT, CompressionParams.lz4(16 * 1024),
        new MetadataCollector(new ClusteringComparator(BytesType.instance))))
        {
            writer.write(bytes);
            writer.finish();
        }
        assertTrue("compressed and logical lengths must differ for this control", file.length() < bytes.length);
        try (CompressionMetadata meta = CompressionMetadata.open(metadata, file.length(), true);
             FileHandle handle = new FileHandle.Builder(file).withCompressionMetadata(meta)
                                                             .withChunkCache(ChunkCache.instance).complete();
             RandomAccessReader first = handle.createReader())
        {
            first.seek(49152);
            assertEquals(bytes[49152], first.readByte());
            long hits = ChunkCache.instance.metrics.hits.getCount();
            try (RandomAccessReader second = handle.createReader())
            {
                second.seek(49152);
                assertEquals(bytes[49152], second.readByte());
                assertEquals(hits + 1, ChunkCache.instance.metrics.hits.getCount());
                byte[] actual = new byte[bytes.length];
                second.seek(0);
                second.readFully(actual);
                assertArrayEquals(bytes, actual);
            }
        }
    }

    private File writeFile(byte[] bytes) throws Exception
    {
        File file = new File(temporaryFolder.newFile());
        Files.write(file.toPath(), bytes);
        return file;
    }

    private static FileHandle handle(File file, int chunkSize)
    {
        return new FileHandle.Builder(file).withChunkCache(ChunkCache.instance).bufferSize(chunkSize).complete();
    }

    private static byte[] contents(int length)
    {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++)
            bytes[i] = (byte) (i * 31 + i / 251);
        return bytes;
    }
}
