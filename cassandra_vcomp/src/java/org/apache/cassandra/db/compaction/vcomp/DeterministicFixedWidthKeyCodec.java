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

import org.apache.cassandra.db.ClusteringPrefix;
import org.apache.cassandra.db.marshal.BytesType;
import org.apache.cassandra.schema.TableMetadata;

/**
 * Reversible 24-byte and 48-byte experiment-key codec.
 *
 * <p>The first eight bytes contain a non-negative coordinate in big-endian order. The remaining
 * bytes contain a deterministic pseudorandom expansion of the coordinate. Consequently Cassandra's
 * unsigned blob order is the coordinate order, duplicate coordinates reproduce an identical full
 * key, and all configured key bytes carry data rather than a fixed zero prefix.</p>
 */
public final class DeterministicFixedWidthKeyCodec implements VCompKeyCodec
{
    public static final int SMALL_KEY_BYTES = 24;
    public static final int LARGE_KEY_BYTES = 48;

    private static final long GOLDEN_GAMMA = 0x9e3779b97f4a7c15L;
    private static final long WIDTH_DOMAIN = 0xd6e8feb86659fd93L;

    private final int keyBytes;

    public DeterministicFixedWidthKeyCodec(int keyBytes)
    {
        if (keyBytes != SMALL_KEY_BYTES && keyBytes != LARGE_KEY_BYTES)
            throw new IllegalArgumentException("VComp experiment key width must be 24 or 48 bytes");
        this.keyBytes = keyBytes;
    }

    public int keyBytes()
    {
        return keyBytes;
    }

    @Override
    public void validateSchema(TableMetadata metadata)
    {
        if (metadata.clusteringColumns().size() != 1)
            throw new IllegalArgumentException("VComp requires exactly one clustering column");
        if (metadata.clusteringColumns().get(0).type != BytesType.instance)
            throw new IllegalArgumentException("fixed-width VComp keys require a blob clustering column");
    }

    @Override
    public long encode(ClusteringPrefix<?> clustering)
    {
        if (clustering.size() != 1)
            throw new IllegalArgumentException("VComp requires exactly one clustering component");
        return encode(clustering.bufferAt(0));
    }

    /** Validate a serialized experiment key and return its numeric coordinate. */
    public long encode(ByteBuffer serializedKey)
    {
        ByteBuffer actual = serializedKey.duplicate();
        if (actual.remaining() != keyBytes)
            throw new IllegalArgumentException("VComp clustering key has " + actual.remaining()
                                               + " bytes; expected " + keyBytes);

        long coordinate = actual.getLong(actual.position());
        if (coordinate < 0)
            throw new IllegalArgumentException("VComp key coordinate exceeds the supported signed-long range");

        ByteBuffer expected = decode(coordinate);
        if (!actual.equals(expected))
            throw new IllegalArgumentException("VComp clustering key has an invalid deterministic payload");
        return coordinate;
    }

    @Override
    public ByteBuffer decode(long coordinate)
    {
        if (coordinate < 0)
            throw new IllegalArgumentException("VComp key coordinates must be non-negative");

        ByteBuffer key = ByteBuffer.allocate(keyBytes);
        key.putLong(coordinate);
        long state = coordinate ^ WIDTH_DOMAIN ^ ((long) keyBytes << 32);
        while (key.hasRemaining())
        {
            state = mix64(state + GOLDEN_GAMMA);
            for (int shift = Long.SIZE - Byte.SIZE; shift >= 0 && key.hasRemaining(); shift -= Byte.SIZE)
                key.put((byte) (state >>> shift));
        }
        return key.flip();
    }

    private static long mix64(long value)
    {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }
}
