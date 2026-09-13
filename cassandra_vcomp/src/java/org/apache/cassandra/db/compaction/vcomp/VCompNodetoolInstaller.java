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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Installs externally materialized SSTables through Cassandra's supported nodetool import path.
 * Cassandra imports all supplied directories in one command, but the command is not transactional:
 * a failed import may require inspecting the live table before retrying.
 */
public final class VCompNodetoolInstaller implements VCompPipeline.FinalStateInstaller
{
    private final Path nodetool;
    private final String keyspace;
    private final String table;

    public VCompNodetoolInstaller(Path nodetool, String keyspace, String table)
    {
        this.nodetool = Objects.requireNonNull(nodetool, "nodetool").toAbsolutePath();
        this.keyspace = requireNonBlank(keyspace, "keyspace");
        this.table = requireNonBlank(table, "table");
    }

    @Override
    public void install(VCompPipeline.MaterializedState materialized) throws Exception
    {
        if (materialized.sstableIds().isEmpty())
            throw new IllegalStateException("there are no materialized SSTables to install");
        if (!java.nio.file.Files.isRegularFile(nodetool) || !java.nio.file.Files.isExecutable(nodetool))
            throw new IllegalStateException("nodetool is missing or not executable: " + nodetool);

        List<String> command = new ArrayList<>();
        command.add(nodetool.toString());
        command.add("import");
        command.add("--copy-data");
        command.add("--");
        command.add(keyspace);
        command.add(table);
        command.addAll(materialized.sstableIds());
        Process process = new ProcessBuilder(command).inheritIO().start();
        int status = process.waitFor();
        if (status != 0)
            throw new IllegalStateException("nodetool import failed with exit status " + status);
    }

    private static String requireNonBlank(String value, String name)
    {
        Objects.requireNonNull(value, name);
        if (value.trim().isEmpty())
            throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
