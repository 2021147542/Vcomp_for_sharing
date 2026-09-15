/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.cassandra.db.compaction;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.After;
import org.junit.Test;

import org.apache.cassandra.SchemaLoader;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.cql3.CQLTester;
import org.apache.cassandra.cql3.statements.schema.CreateTableStatement;
import org.apache.cassandra.db.ColumnFamilyStore;
import org.apache.cassandra.db.Keyspace;
import org.apache.cassandra.db.compaction.vcomp.DeterministicFixedWidthKeyCodec;
import org.apache.cassandra.db.compaction.vcomp.VCompOrderedPartitionLayout;
import org.apache.cassandra.io.sstable.Descriptor;
import org.apache.cassandra.io.sstable.format.SSTableReader;
import org.apache.cassandra.schema.KeyspaceParams;
import org.apache.cassandra.schema.Schema;
import org.apache.cassandra.schema.TableMetadata;
import org.apache.cassandra.utils.JsonUtils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/** Read-only continuation on retained diagnostic SSTs; canonical 100GiB DB cannot be supplied. */
public class VCompRetainedScanDiagnosticTest extends CQLTester
{
    @After
    @Override
    public void afterTest() { /* Source files are retained diagnostic evidence; never truncate/drop them. */ }

    @Test
    @SuppressWarnings("unchecked")
    public void readRetainedFinalStates() throws Exception
    {
        // addSSTables must not create backup links alongside retained source evidence.
        DatabaseDescriptor.setIncrementalBackupsEnabled(false);
        Path input = Paths.get(System.getProperty("vcomp.retained.input")).toRealPath();
        Path output = Paths.get(System.getProperty("vcomp.retained.output"));
        if (!input.toString().startsWith("/tmp/vcomp-")) throw new IllegalArgumentException("Only retained /tmp/vcomp-* diagnostic inputs allowed");
        Files.createDirectories(output);
        Map<String, Object> config = read(input.resolve("input-config.json"), Map.class);
        assertEquals(20260909L, ((Number) config.get("seed")).longValue());
        assertEquals("NONE", config.get("storage_compatibility_mode"));
        assertEquals(64L << 20, ((Number) config.get("target_sstable_bytes")).longValue());
        long domain = ((Number) config.get("domain")).longValue();
        int partitions = ((Number) config.get("partitions")).intValue();
        List<Map<String, Object>> events = read(input.resolve("native-events.json"), List.class);
        Map<String, Map<String, Object>> metadata = read(input.resolve("native-sst-metadata.json"), Map.class);
        List<String> nativeIds = null;
        for (Map<String, Object> event : events)
            if ("native_drained".equals(event.get("kind"))) nativeIds = (List<String>) event.get("live");
        if (nativeIds == null || nativeIds.isEmpty()) throw new IllegalArgumentException("Missing drained native final state");
        List<String> nativePaths = new ArrayList<>();
        for (String id : nativeIds) nativePaths.add((String) metadata.get(id).get("data_db_path"));
        Map<String, List<String>> arms = new LinkedHashMap<>();
        arms.put("model", new ArrayList<>(read(input.resolve("virtual-sst-metadata.json"), Map.class).keySet()));
        for (String arm : new String[] { "exact-writer-control", "timestamp-control" })
        {
            Path control = input.resolve(arm).resolve("native-writer-control.json");
            if (!Files.exists(control)) continue; // Early smoke fixtures predate writer interventions.
            Map<String, Object> manifest = read(control, Map.class);
            List<String> paths = new ArrayList<>();
            for (Map<String, Object> file : (List<Map<String, Object>>) manifest.get("files"))
                paths.add((String) file.get("control_file"));
            arms.put(arm, paths);
        }
        List<String> allPaths = new ArrayList<>(nativePaths);
        arms.values().forEach(allPaths::addAll);
        Map<String, Object> before = inventory(allPaths);
        write(output.resolve("source-files-before.json"), before);
        String ks = "vcomp_retained_scan_native", table = "kv";
        TableMetadata tableMetadata = CreateTableStatement.parse("CREATE TABLE " + ks + '.' + table
            + " (partition_id text, ck blob, value blob, PRIMARY KEY ((partition_id),ck))"
            + " WITH compression={'enabled':'false'} AND caching={'keys':'ALL','rows_per_partition':'NONE'}"
            + " AND compaction={'class':'UnifiedCompactionStrategy','scaling_parameters':'T4','target_sstable_size':'64MiB',"
            + "'base_shard_count':'1','sstable_growth':'0.333'}", ks).build();
        SchemaLoader.createKeyspace(ks, KeyspaceParams.simple(1), tableMetadata);
        ColumnFamilyStore nativeCfs = Keyspace.open(ks).getColumnFamilyStore(table);
        nativeCfs.disableAutoCompaction();
        List<SSTableReader> readers = new ArrayList<>();
        for (String path : nativePaths)
            readers.add(SSTableReader.open(null, Descriptor.fromFile(new org.apache.cassandra.io.util.File(path)),
                Schema.instance.getTableMetadataRef(ks, table)));
        nativeCfs.addSSTables(readers);
        assertFalse(readers.isEmpty());
        VCompOrderedPartitionLayout layout = new VCompOrderedPartitionLayout(domain, partitions);
        for (Map.Entry<String, List<String>> arm : arms.entrySet())
            VCompScanPathDiagnostic.run(nativeCfs, arm.getValue(), layout,
                new DeterministicFixedWidthKeyCodec(24), domain, output.resolve(arm.getKey()));
        Map<String, Object> after = inventory(allPaths);
        write(output.resolve("source-files-after.json"), after);
        assertEquals("Retained source SST inventory/size/mtime unchanged", before, after);
        write(output.resolve("retained-scan-config.json"), object("input", input.toString(), "input_config", config,
            "native_data_paths", nativePaths, "arms", arms, "diagnostic_only", true,
            "source_preservation", "all source components have unchanged path, size, and mtime; source files not copied or mutated"));
    }

    private static Map<String, Object> inventory(List<String> dataPaths) throws Exception
    {
        Map<String, Object> result = new java.util.TreeMap<>();
        for (String name : dataPaths)
        {
            Path data = Paths.get(name).toRealPath();
            if (!data.toString().startsWith("/tmp/vcomp-")) throw new IllegalArgumentException("Source SST outside isolated diagnostic scratch");
            String prefix = data.getFileName().toString().replace("Data.db", "");
            try (Stream<Path> files = Files.list(data.getParent()))
            {
                for (Path file : (Iterable<Path>) files.filter(p -> p.getFileName().toString().startsWith(prefix))::iterator)
                {
                    BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
                    result.put(file.toString(), object("bytes", attrs.size(), "mtime", attrs.lastModifiedTime().toString()));
                }
            }
        }
        return result;
    }

    private static <T> T read(Path path, Class<T> type) throws Exception
    { return JsonUtils.JSON_OBJECT_MAPPER.readValue(Files.readAllBytes(path), type); }
    private static void write(Path path, Object value) throws Exception
    { Files.write(path, JsonUtils.JSON_OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(value)); }
    private static Map<String, Object> object(Object... pairs)
    {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }
}
