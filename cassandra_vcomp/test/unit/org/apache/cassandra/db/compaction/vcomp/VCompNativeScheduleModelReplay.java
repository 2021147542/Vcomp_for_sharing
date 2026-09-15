/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.cassandra.db.compaction.vcomp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.Arrays;

import org.apache.cassandra.db.compaction.unified.Controller;
import org.apache.cassandra.utils.JsonUtils;

/** Test-only CPU intervention: carry models through a recorded native job DAG. */
public final class VCompNativeScheduleModelReplay
{
    private VCompNativeScheduleModelReplay() {}

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception
    {
        if (args.length != 2 && args.length != 4) throw new IllegalArgumentException("native-output-directory output-json [samples plr-error]");
        int samples = args.length == 4 ? Integer.parseInt(args[2]) : 512;
        double error = args.length == 4 ? Double.parseDouble(args[3]) : 8;
        Path input = Path.of(args[0]);
        Map<String, Object> config = (Map<String, Object>) read(input.resolve("input-config.json"));
        Map<String, Object> sizeConfig = (Map<String, Object>) read(input.resolve("virtual-result.json"));
        Map<String, Map<String, Object>> metadata = (Map<String, Map<String, Object>>) read(input.resolve("native-sst-metadata.json"));
        List<Map<String, Object>> events = (List<Map<String, Object>>) read(input.resolve("native-events.json"));
        Map<String, Map<String, Object>> vectors = (Map<String, Map<String, Object>>) read(input.resolve("native-exact-vectors.json"));
        VCompOrderedPartitionLayout layout = new VCompOrderedPartitionLayout(number(config, "domain"), (int) number(config, "partitions"));
        if (!config.containsKey("target_sstable_bytes"))
            throw new IllegalArgumentException("Native target must be explicitly recorded; historical unmatched fixture is unsupported");
        long target = number(config, "target_sstable_bytes");
        VCompSSTSizeModel size = new VCompSSTSizeModel();
        if (!size.addCalibration(4096, number(sizeConfig, "predicted_data_bytes_for_4096"),
                                 8192, number(sizeConfig, "predicted_data_bytes_for_8192")))
            throw new IllegalArgumentException("Invalid recorded size model");
        DefaultFlushVirtualizer virtualizer = new DefaultFlushVirtualizer(error, samples, 8, size);
        DefaultVirtualCompaction compactor = new DefaultVirtualCompaction(samples, 8, target, size, layout);
        Map<String, VCompPipeline.VirtualSortedRun> live = new LinkedHashMap<>();
        Map<String, Integer> depths = new LinkedHashMap<>();
        List<Map<String, Object>> jobs = new ArrayList<>();
        Map<String, Object> firstDifference = null;
        String status = "complete";
        for (Map<String, Object> event : events)
        {
            String kind = (String) event.get("kind");
            if (kind.equals("flush_registered"))
            {
                List<String> added = (List<String>) event.get("added");
                if (added.size() != 1)
                {
                    status = "stopped_unsupported_multi_sst_flush";
                    break;
                }
                String id = added.get(0);
                Map<String, Object> m = metadata.get(id);
                long[] keys = vectors.get(id).keySet().stream().mapToLong(Long::parseLong).sorted().toArray();
                VCompPipeline.VirtualSortedRun run = virtualizer.virtualize(new VCompPipeline.FlushBatch(id, keys,
                    number(m, "on_disk_length"), number(m, "max_timestamp")));
                live.put(id, rename(id, run.sstables().get(0), 0));
                depths.put(id, 0);
            }
            if (!kind.equals("job_commit_visible")) continue;
            List<String> selected = (List<String>) event.get("selected"), added = (List<String>) event.get("added");
            List<VCompPipeline.VirtualSortedRun> inputs = new ArrayList<>();
            int depth = 0;
            long nativeBytes = 0, modeledBytes = 0, nativeMin = Long.MAX_VALUE, nativeMax = Long.MIN_VALUE,
                 modeledMin = Long.MAX_VALUE, modeledMax = Long.MIN_VALUE, nativeRows = 0;
            boolean missingInput = false;
            for (String id : selected)
            {
                VCompPipeline.VirtualSortedRun run = live.get(id);
                if (run == null) { missingInput = true; break; }
                inputs.add(run);
                depth = Math.max(depth, depths.get(id) + 1);
                Map<String, Object> m = metadata.get(id);
                nativeBytes += number(m, "on_disk_length");
                nativeMin = Math.min(nativeMin, number(m, "key_min"));
                nativeMax = Math.max(nativeMax, number(m, "key_max"));
                for (VCompPipeline.VirtualSSTable s : run.sstables())
                {
                    modeledBytes += s.estimatedBytes();
                    modeledMin = Math.min(modeledMin, s.keyMin());
                    modeledMax = Math.max(modeledMax, s.keyMax());
                }
            }
            if (missingInput) { status = "stopped_missing_native_input_mapping"; break; }
            for (String id : added) nativeRows += number(metadata.get(id), "rows");
            int nativeShards = shards(nativeBytes, nativeMin, nativeMax, target, layout);
            int modeledShards = shards(modeledBytes, modeledMin, modeledMax, target, layout);
            VCompPipeline.VirtualCompactionPlan plan = new VCompPipeline.VirtualCompactionPlan("native-event-"+event.get("seq"), inputs, depth);
            VCompKmvSketch sketch = compactor.mergeAndDeduplicate(plan);
            long estimated = compactor.estimateUniqueKeys(plan, sketch);
            TreeSet<Long> exactUnion = new TreeSet<>();
            for (String id : selected) for (String key : vectors.get(id).keySet()) exactUnion.add(Long.parseLong(key));
            VCompKmvSketch direct = VCompKmvSketch.build(exactUnion.stream().mapToLong(Long::longValue).toArray(), samples);
            long directEstimated = compactor.estimateUniqueKeys(plan, direct);
            long[] carriedSamples = sketch.samples().stream().mapToLong(VCompKmvSketch.Sample::key).toArray();
            long[] directSamples = direct.samples().stream().mapToLong(VCompKmvSketch.Sample::key).toArray();
            Map<String, Object> job = obj("event_seq", event.get("seq"), "generation_depth", depth,
                "native_inputs", selected, "native_outputs", added,
                "native_rows", nativeRows, "modeled_estimated_rows", estimated,
                "native_formula_shards", nativeShards, "modeled_formula_shards", modeledShards);
            job.put("carried_global_sample_count", carriedSamples.length);
            job.put("carried_global_theta_unsigned", Long.toUnsignedString(sketch.thetaHash()));
            job.put("direct_exact_union_estimate", directEstimated);
            job.put("direct_exact_union_sample_count", directSamples.length);
            job.put("direct_exact_union_theta_unsigned", Long.toUnsignedString(direct.thetaHash()));
            job.put("carried_global_equals_direct_exact_union_bottom_k", Arrays.equals(carriedSamples, directSamples)
                && sketch.thetaHash() == direct.thetaHash() && sketch.isComplete() == direct.isComplete());
            if (nativeRows != estimated && firstDifference == null)
                firstDifference = obj("event_seq", event.get("seq"), "stage", "global_cardinality", "field", "row_count",
                    "native", nativeRows, "modeled", estimated);
            VCompPipeline.VirtualSortedRun output;
            try
            {
                output = compactor.split(plan, compactor.merge(plan, estimated), sketch, estimated);
            }
            catch (RuntimeException e)
            {
                job.put("exception", e.toString());
                jobs.add(job);
                status = "stopped_production_model_split_rejected";
                break;
            }
            List<Map<String, Object>> descriptions = new ArrayList<>();
            for (VCompPipeline.VirtualSSTable s : output.sstables())
                descriptions.add(obj("id", s.id(), "key_min", s.keyMin(), "key_max", s.keyMax(),
                    "rows", s.estimatedUniqueKeys(), "bytes", s.estimatedBytes(), "max_timestamp", s.maximumTimestamp()));
            job.put("model_outputs", descriptions);
            Map<Integer, String> nativeByShard = new TreeMap<>();
            Map<Integer, VCompPipeline.VirtualSSTable> modelByShard = new TreeMap<>();
            boolean valid = nativeShards == modeledShards;
            for (String id : added)
            {
                Map<String, Object> m = metadata.get(id);
                int first = layout.shardIndex(layout.partitionFor(number(m, "key_min")), nativeShards);
                int last = layout.shardIndex(layout.partitionFor(number(m, "key_max")), nativeShards);
                valid &= first == last && nativeByShard.put(first, id) == null;
            }
            for (VCompPipeline.VirtualSSTable s : output.sstables())
            {
                int first = layout.shardIndex(layout.partitionFor(s.keyMin()), modeledShards);
                int last = layout.shardIndex(layout.partitionFor(s.keyMax()), modeledShards);
                valid &= first == last && modelByShard.put(first, s) == null;
            }
            valid &= nativeByShard.keySet().equals(modelByShard.keySet());
            job.put("mapping_verified", valid);
            job.put("mapping_basis", "Equal calculated full-ring shard count, every output wholly within one shard, unique identical occupied shard IDs; never positional zip or nearest boundary");
            jobs.add(job);
            if (!valid)
            {
                status = "stopped_undefined_native_to_model_shard_mapping";
                if (firstDifference == null)
                    firstDifference = obj("event_seq", event.get("seq"), "stage", "split", "field", "shard_mapping");
                break;
            }
            for (String id : selected) { live.remove(id); depths.remove(id); }
            for (Map.Entry<Integer, String> entry : nativeByShard.entrySet())
            {
                String id = entry.getValue();
                live.put(id, rename(id, modelByShard.get(entry.getKey()), depth));
                depths.put(id, depth);
            }
        }
        List<Map<String, Object>> frontier = new ArrayList<>();
        long[] modelPartitionRows = new long[layout.partitionCount()];
        long[] nativePartitionRows = new long[layout.partitionCount()];
        Map<String, Map<String, Object>> actualVirtual = Files.exists(input.resolve("virtual-exact-vectors.json"))
            ? (Map<String, Map<String, Object>>) read(input.resolve("virtual-exact-vectors.json")) : Collections.emptyMap();
        for (Map.Entry<String, VCompPipeline.VirtualSortedRun> e : live.entrySet())
        {
            VCompPipeline.VirtualSSTable s = e.getValue().sstables().get(0);
            Map<String, Object> row = obj("native_lineage", e.getKey(), "generation_depth", depths.get(e.getKey()),
                "modeled_rows", s.estimatedUniqueKeys(), "native_rows", number(metadata.get(e.getKey()), "rows"),
                "modeled_key_min", s.keyMin(), "modeled_key_max", s.keyMax(), "modeled_bytes", s.estimatedBytes());
            List<Long> emitted = new ArrayList<>();
            VCompMaterializedKeyIterator iterator = new VCompMaterializedKeyIterator(s);
            while (iterator.hasNext()) emitted.add(iterator.nextLong());
            long[] keys = emitted.stream().mapToLong(Long::longValue).toArray();
            for (long key : keys) modelPartitionRows[layout.partitionFor(key).ordinal()]++;
            for (String key : vectors.get(e.getKey()).keySet()) nativePartitionRows[layout.partitionFor(Long.parseLong(key)).ordinal()]++;
            String hash = hash(keys);
            row.put("emitted_rows", keys.length);
            row.put("emitted_key_sha256", hash);
            List<Map<String, Object>> candidateMatches = new ArrayList<>();
            for (Map.Entry<String, Map<String, Object>> candidate : actualVirtual.entrySet())
            {
                long[] actual = candidate.getValue().keySet().stream().mapToLong(Long::parseLong).sorted().toArray();
                if (actual.length > 0 && actual[0] >= s.keyMin() && actual[actual.length - 1] <= s.keyMax())
                    candidateMatches.add(obj("actual_virtual_file", candidate.getKey(), "actual_rows", actual.length,
                        "actual_key_sha256", hash(actual), "emitted_keys_match", hash.equals(hash(actual)) && keys.length == actual.length));
            }
            row.put("actual_virtual_range_contained_candidates", candidateMatches);
            row.put("unique_actual_virtual_candidate", candidateMatches.size() == 1);
            frontier.add(row);
        }
        Files.writeString(Path.of(args[1]), JsonUtils.JSON_OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(
            obj("diagnostic_only", true, "status", status, "input_directory", input.toString(), "seed", config.get("seed"),
                "kmv_samples", samples, "plr_error", error, "diagnostic_parameter_intervention", samples != 512 || error != 8,
                "scope", "Actual native job DAG imposed on carried production descriptors; bypasses picker intentionally; original keys used only for actual initial flush fits. No inherited models refit. No native timing replay or physical SST generation.",
                "first_cardinality_or_mapping_difference", firstDifference, "jobs", jobs,
                "frontier_scope", status.equals("complete") ? "fully_replayed_native_final_lineage" : "partial_before_unmappable_job",
                "model_partition_rows", modelPartitionRows, "native_partition_rows", nativePartitionRows,
                "model_frontier", frontier)));
    }

    private static VCompPipeline.VirtualSortedRun rename(String id, VCompPipeline.VirtualSSTable s, int level)
    { return new VCompPipeline.VirtualSortedRun(id, level, Collections.singletonList(s)); }
    private static int shards(long bytes, long min, long max, long target, VCompOrderedPartitionLayout layout)
    { return Controller.calculateNumShards(bytes / layout.tokenCoverage(min, max), Controller.defaultMinSSTableSizeBytes(), 1, target, 0.333); }
    private static Object read(Path path) throws Exception
    { return JsonUtils.JSON_OBJECT_MAPPER.readValue(Files.readAllBytes(path), Object.class); }
    private static long number(Map<String, ?> map, String key)
    {
        Object value = map.get(key);
        if (!(value instanceof Number)) throw new IllegalArgumentException("Missing numeric field " + key);
        return ((Number) value).longValue();
    }
    private static Map<String, Object> obj(Object... pairs)
    { Map<String, Object> map = new LinkedHashMap<>(); for (int i=0;i<pairs.length;i+=2) map.put((String)pairs[i],pairs[i+1]);return map; }
    private static String hash(long[] keys) throws Exception
    {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        ByteBuffer bytes = ByteBuffer.allocate(Long.BYTES);
        for (long key : keys) { bytes.clear(); bytes.putLong(key); digest.update(bytes.array()); }
        StringBuilder result = new StringBuilder();
        for (byte value : digest.digest()) result.append(String.format("%02x", value & 255));
        return result.toString();
    }
}
