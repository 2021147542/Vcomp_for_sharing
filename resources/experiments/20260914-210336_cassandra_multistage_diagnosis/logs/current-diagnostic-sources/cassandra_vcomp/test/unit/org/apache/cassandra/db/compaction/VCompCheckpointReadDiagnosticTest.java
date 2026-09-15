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
import org.apache.cassandra.io.sstable.format.SSTableReaderWithFilter;
import org.apache.cassandra.schema.KeyspaceParams;
import org.apache.cassandra.schema.Schema;
import org.apache.cassandra.schema.TableMetadata;
import org.apache.cassandra.utils.JsonUtils;
import org.apache.cassandra.utils.ByteBufferUtil;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/** Bounded fixed-request reads on explicitly supplied fresh checkpoints, never a load or mutation. */
public class VCompCheckpointReadDiagnosticTest extends CQLTester
{
    @After
    @Override
    public void afterTest() { /* No CQLTester truncation/drop: checkpoint SST files must remain immutable. */ }

    @Test
    @SuppressWarnings("unchecked")
    public void compareCheckpointReads() throws Exception
    {
        DatabaseDescriptor.setIncrementalBackupsEnabled(false);
        Path manifestPath = Paths.get(System.getProperty("vcomp.checkpoint.manifest")).toRealPath();
        Map<String, Object> manifest = JsonUtils.JSON_OBJECT_MAPPER.readValue(Files.readAllBytes(manifestPath), Map.class);
        Path root = Paths.get((String) manifest.get("checkpoint_root")).toRealPath();
        Path explicitRoot = Paths.get(System.getProperty("vcomp.diagnostic.checkpoint_root")).toRealPath();
        assertEquals("Manifest and cache-eviction root must identify the same fresh checkpoint", root, explicitRoot);
        if (root.getNameCount() < 2 || !root.getFileName().toString().startsWith("vcomp-cassandra-read-diagnosis-")
            || !manifestPath.startsWith(root))
            throw new IllegalArgumentException("Manifest must be inside the explicitly authorized checkpoint directory");
        assertEquals(20260909L, ((Number) manifest.get("seed")).longValue());
        long domain = ((Number) manifest.get("domain")).longValue();
        int partitions = ((Number) manifest.get("partitions")).intValue();
        List<String> nativePaths = (List<String>) manifest.get("native_data_paths");
        List<String> virtualPaths = (List<String>) manifest.get("vcomp_data_paths");
        assertFalse(nativePaths.isEmpty());
        assertFalse(virtualPaths.isEmpty());
        List<String> allPaths = new ArrayList<>(nativePaths);
        allPaths.addAll(virtualPaths);
        Path output = Paths.get(System.getProperty("vcomp.checkpoint.output"));
        Files.createDirectories(output);
        Files.copy(manifestPath, output.resolve("checkpoint-input-manifest.json"));
        Map<String, Object> before = inventory(root, allPaths);
        write(output.resolve("checkpoint-components-before.json"), before);
        String ks = "vcomp_checkpoint_native", table = "kv";
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
        List<Map<String, Object>> nativeMetadata = new ArrayList<>();
        for (SSTableReader reader : readers) nativeMetadata.add(sstableMetadata(reader));
        List<Map<String, Object>> virtualMetadata = new ArrayList<>();
        for (String path : virtualPaths)
        {
            SSTableReader reader = SSTableReader.open(null,
                Descriptor.fromFile(new org.apache.cassandra.io.util.File(path)), Schema.instance.getTableMetadataRef(ks, table));
            try { virtualMetadata.add(sstableMetadata(reader)); }
            finally { reader.selfRef().release(); }
        }
        write(output.resolve("opened-sst-metadata.json"), object("native", nativeMetadata, "vcomp", virtualMetadata,
            "scope", "Reader metadata only; no full row scan. estimated_partition_keys is an estimate, not row count"));
        VCompOrderedPartitionLayout layout = new VCompOrderedPartitionLayout(domain, partitions);
        DeterministicFixedWidthKeyCodec codec = new DeterministicFixedWidthKeyCodec(24);
        try
        {
            Path points = output.resolve("point-C");
            Files.createDirectories(points);
            VCompReadPathDiagnostic.run(nativeCfs, virtualPaths, layout, codec, domain, points);
            VCompScanPathDiagnostic.run(nativeCfs, virtualPaths, layout, codec, domain, output.resolve("scan-E"));
        }
        finally
        {
            Map<String, Object> after = inventory(root, allPaths);
            write(output.resolve("checkpoint-components-after.json"), after);
            assertEquals("All checkpoint SST component paths, sizes, and modification times must remain unchanged", before, after);
        }
        write(output.resolve("checkpoint-read-validation.json"), object("diagnostic_only", true,
            "seed", 20260909L, "domain", domain, "partitions", partitions,
            "baseline_ssts", nativePaths.size(), "vcomp_ssts", virtualPaths.size(),
            "point_requests", Integer.getInteger("vcomp.read.requests", 1000),
            "scan_operations", Integer.getInteger("vcomp.scan.operations", 1000),
            "checkpoint_root", root.toString(), "checkpoint_components_unchanged", true,
            "scope", "Frozen checkpoint local C and E read components; no writes, CQL transport, or new loading; all ABBA rounds retained"));
    }

    private static Map<String, Object> sstableMetadata(SSTableReader reader) throws Exception
    {
        return object("file", reader.getFilename(), "format_version", reader.descriptor.version.toString(),
            "data_db_bytes", Files.size(Paths.get(reader.getFilename())),
            "first_token", reader.getFirst().getToken().toString(), "last_token", reader.getLast().getToken().toString(),
            "first_partition_key", ByteBufferUtil.string(reader.getFirst().getKey()),
            "last_partition_key", ByteBufferUtil.string(reader.getLast().getKey()),
            "min_timestamp", reader.getMinTimestamp(), "max_timestamp", reader.getMaxTimestamp(),
            "encoding_min_timestamp", reader.header.stats().minTimestamp,
            "estimated_partition_keys", reader.estimatedKeys(), "token_space_coverage", reader.tokenSpaceCoverage(),
            "bloom_filter_bytes", reader instanceof SSTableReaderWithFilter
                                  ? ((SSTableReaderWithFilter) reader).getFilterSerializedSize() : null);
    }

    private static Map<String, Object> inventory(Path root, List<String> dataPaths) throws Exception
    {
        Map<String, Object> result = new java.util.TreeMap<>();
        for (String name : dataPaths)
        {
            Path data = Paths.get(name).toRealPath();
            if (!data.startsWith(root) || !data.getFileName().toString().endsWith("-Data.db"))
                throw new IllegalArgumentException("SST outside explicit checkpoint root: " + data);
            String prefix = data.getFileName().toString().replace("Data.db", "");
            try (Stream<Path> files = Files.list(data.getParent()))
            {
                for (Path file : (Iterable<Path>) files.filter(p -> p.getFileName().toString().startsWith(prefix))::iterator)
                {
                    if (!file.toRealPath().startsWith(root)) throw new IllegalArgumentException("Component alias outside checkpoint");
                    BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
                    result.put(file.toString(), object("bytes", attrs.size(), "mtime", attrs.lastModifiedTime().toString()));
                }
            }
        }
        return result;
    }

    private static void write(Path path, Object value) throws Exception
    { Files.write(path, JsonUtils.JSON_OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(value)); }
    private static Map<String, Object> object(Object... pairs)
    {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }
}
