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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import org.apache.cassandra.io.util.File;

/** Checks generated SSTable components before Cassandra imports them. */
public final class VCompFilesystemVerifier implements VCompPipeline.MaterializedStateVerifier
{
    @Override
    public void verify(VCompPipeline.FrozenLayout layout,
                       VCompPipeline.MaterializedState materialized) throws Exception
    {
        int expected = 0;
        long expectedKeys = 0;
        for (VCompPipeline.VirtualSortedRun run : layout.runs())
        {
            expected += run.sstables().size();
            for (VCompPipeline.VirtualSSTable sstable : run.sstables())
            {
                if (Long.MAX_VALUE - expectedKeys < sstable.estimatedUniqueKeys())
                    throw new IllegalStateException("frozen-layout key count overflow");
                expectedKeys += sstable.estimatedUniqueKeys();
            }
        }
        if (materialized.sstableIds().size() != expected)
            throw new IllegalStateException("materialized SSTable count differs from frozen layout");
        if (expected == 0)
            throw new IllegalStateException("frozen layout has no SSTables");
        if (materialized.materializedKeys() <= 0 || materialized.logicalBytes() <= 0)
            throw new IllegalStateException("materialization produced no logical data");
        // The paper's continuous inverse may collapse rounded keys or stop at
        // a descriptor upper bound, so the physical count can be lower than
        // the KMV-estimated descriptor count, but never higher.
        if (materialized.materializedKeys() > expectedKeys)
            throw new IllegalStateException("materialized key count exceeds frozen-layout estimate: expected at most "
                                            + expectedKeys + ", got " + materialized.materializedKeys());

        Set<Path> uniqueDirectories = new HashSet<>();
        for (String id : materialized.sstableIds())
        {
            Path directory = new File(id).toPath().toAbsolutePath().normalize();
            if (!uniqueDirectories.add(directory))
                throw new IllegalStateException("duplicate materialization directory: " + directory);
            if (!Files.isDirectory(directory))
                throw new IllegalStateException("materialization directory does not exist: " + directory);
            try (java.util.stream.Stream<Path> files = Files.list(directory))
            {
                Path[] dataComponents = files.filter(path -> path.getFileName().toString().endsWith("-Data.db"))
                                             .toArray(Path[]::new);
                if (dataComponents.length == 0)
                    throw new IllegalStateException("expected at least one Data.db component in " + directory);
                for (Path dataComponent : dataComponents)
                {
                    if (!Files.isRegularFile(dataComponent) || Files.size(dataComponent) <= 0)
                        throw new IllegalStateException("materialized Data.db is empty: " + dataComponent);
                }
            }
        }
    }
}
