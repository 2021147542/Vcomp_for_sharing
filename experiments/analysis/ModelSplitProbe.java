package org.apache.cassandra.db.compaction.vcomp;

import java.nio.file.*;
import java.util.*;

/** Isolated descriptor-only diagnostic: never opens Cassandra data or writes SSTs. */
public final class ModelSplitProbe {
    public static void main(String[] args) throws Exception {
        int keySpace = args.length > 1 ? Integer.parseInt(args[1]) : 1048576;
        int partitions = args.length > 2 ? Integer.parseInt(args[2]) : 10000;
        Path output = Paths.get(args[0]);
        Files.createDirectories(output);
        long seed = 20260909L;
        long flushBytes = 64L << 20;
        VCompOrderedPartitionLayout layout = new VCompOrderedPartitionLayout(keySpace, partitions);
        VCompSSTSizeModel sizes = VCompSSTSizeModel.logical(1024);
        DefaultFlushVirtualizer virtualizer = new DefaultFlushVirtualizer(8, 512, 8, sizes);
        DefaultVirtualCompaction compaction = new DefaultVirtualCompaction(512, 8, flushBytes, sizes, layout);
        VCompUcsPlanner picker = new VCompUcsPlanner(flushBytes, layout);
        List<VCompPipeline.VirtualSortedRun> live = new ArrayList<>();
        BitSet original = new BitSet(keySpace);
        int flushes = 0, compactions = 0;
        long start = System.nanoTime();
        for (VCompPipeline.FlushBatch batch : new SyntheticVCompLoadSource(keySpace, 65536, keySpace, 1024, seed).flushBatches()) {
            for (long key : batch.keyCoordinates()) original.set((int) key);
            live.add(virtualizer.virtualize(batch));
            flushes++;
            for (;;) {
                Optional<VCompPipeline.VirtualCompactionPlan> picked = picker.pick(new VCompPipeline.VirtualStateSnapshot(live));
                if (!picked.isPresent()) break;
                VCompPipeline.VirtualCompactionPlan plan = picked.get();
                VCompKmvSketch sketch = compaction.mergeAndDeduplicate(plan);
                long count = compaction.estimateUniqueKeys(plan, sketch);
                VCompLearnedModel model = compaction.merge(plan, count);
                VCompPipeline.VirtualSortedRun merged = compaction.split(plan, model, sketch, count);
                live.removeAll(plan.inputs());
                int shard = 0;
                for (VCompPipeline.VirtualSSTable sst : merged.sstables())
                    live.add(new VCompPipeline.VirtualSortedRun(merged.id() + "-shard-" + shard++, merged.level(), Collections.singletonList(sst)));
                compactions++;
            }
        }
        double metadataSeconds = (System.nanoTime() - start) / 1e9;
        BitSet generated = new BitSet(keySpace);
        long physical = 0, estimated = 0, truncated = 0;
        long[] physicalBuckets = new long[100];
        List<String> ssts = new ArrayList<>();
        ssts.add("run,level,key_min,key_max,estimated_keys,emitted_keys,segments,sketch_samples");
        for (VCompPipeline.VirtualSortedRun run : live) for (VCompPipeline.VirtualSSTable sst : run.sstables()) {
            estimated += sst.estimatedUniqueKeys();
            VCompMaterializedKeyIterator iter = new VCompMaterializedKeyIterator(sst);
            long emitted = 0;
            while (iter.hasNext()) {
                int key = (int) iter.nextLong();
                generated.set(key);
                physicalBuckets[Math.min(99, (int) ((long) key * 100 / keySpace))]++;
                emitted++;
            }
            physical += emitted;
            if (emitted != sst.estimatedUniqueKeys()) truncated++;
            ssts.add(run.id() + "," + run.level() + "," + sst.keyMin() + "," + sst.keyMax() + "," + sst.estimatedUniqueKeys() + "," + emitted + "," + sst.model().segments().size() + "," + sst.sketch().samples().size());
        }
        Files.write(output.resolve("ssts.csv"), ssts);
        BitSet retained = (BitSet) original.clone(); retained.and(generated);
        List<String> buckets = new ArrayList<>();
        buckets.add("bucket,baseline_union,vcomp_union,vcomp_physical");
        for (int i = 0; i < 100; i++) {
            int begin = (int) ((long) keySpace * i / 100), end = (int) ((long) keySpace * (i + 1) / 100);
            buckets.add(i + "," + original.get(begin, end).cardinality() + "," + generated.get(begin, end).cardinality() + "," + physicalBuckets[i]);
        }
        Files.write(output.resolve("distribution.csv"), buckets);
        List<String> hot = new ArrayList<>(); hot.add("key,baseline_present,vcomp_present");
        for (int key = 0; key < Math.min(1000, keySpace); key++) hot.add(key + "," + original.get(key) + "," + generated.get(key));
        Files.write(output.resolve("hot_keys.csv"), hot);
        String summary = "writes=" + keySpace + "\nkey_space=" + keySpace + "\npartitions=" + partitions + "\nseed=" + seed + "\nflushes=" + flushes + "\ncompactions=" + compactions + "\nfinal_ssts=" + ssts.size() + "\nbaseline_union=" + original.cardinality() + "\nvcomp_union=" + generated.cardinality() + "\nvcomp_estimated_entries=" + estimated + "\nvcomp_physical_entries=" + physical + "\nvcomp_cross_sst_duplicates=" + (physical - generated.cardinality()) + "\ntruncated_ssts=" + truncated + "\nretained_original_keys=" + retained.cardinality() + "\nmetadata_seconds=" + metadataSeconds + "\n";
        summary = summary.replace("final_ssts=" + ssts.size(), "final_ssts=" + (ssts.size() - 1));
        Files.writeString(output.resolve("summary.env"), summary);
        System.out.print(summary);
    }
}
