import java.util.ArrayList;
import java.util.List;

import org.apache.cassandra.db.compaction.vcomp.SyntheticVCompLoadSource;
import org.apache.cassandra.db.compaction.vcomp.VCompOrderedPartitionLayout;
import org.apache.cassandra.db.compaction.vcomp.VCompPipeline;
import org.apache.cassandra.db.compaction.vcomp.VCompSSTSizeModel;

/** Metadata-only diagnostic entry point. It never starts Cassandra or writes SSTables. */
public final class VCompSimulation
{
    public static void main(String[] args) throws Exception
    {
        if (args.length != 4)
            throw new IllegalArgumentException("usage: VCompSimulation <writes> <entry-bytes> <partition-count> <seed>");
        long writes = Long.parseLong(args[0]);
        int entryBytes = Integer.parseInt(args[1]);
        int partitions = Integer.parseInt(args[2]);
        long seed = Long.parseLong(args[3]);
        long flushBytes = 64L << 20;
        long targetBytes = 64L << 20;
        int writesPerFlush = Math.toIntExact(flushBytes / entryBytes);
        VCompOrderedPartitionLayout layout = new VCompOrderedPartitionLayout(writes, partitions);
        SyntheticVCompLoadSource source = new SyntheticVCompLoadSource(writes,
                                                                        writesPerFlush,
                                                                        writes,
                                                                        entryBytes,
                                                                        seed);
        VCompPipeline.FinalMaterializer counter = frozen ->
        {
            List<String> ids = new ArrayList<>();
            long keys = 0;
            long bytes = 0;
            for (VCompPipeline.VirtualSortedRun run : frozen.runs())
                for (VCompPipeline.VirtualSSTable sstable : run.sstables())
                {
                    ids.add(sstable.id());
                    keys += sstable.estimatedUniqueKeys();
                    bytes += sstable.estimatedBytes();
                }
            return new VCompPipeline.MaterializedState(ids, keys, bytes);
        };
        VCompPipeline pipeline = VCompPipeline.createDefault(flushBytes,
                                                              sizeModel.estimate(writesPerFlush),
                                                              targetBytes,
                                                              VCompSSTSizeModel.logical(entryBytes),
                                                              layout,
                                                              counter,
                                                              ignored -> { },
                                                              (frozen, materialized) -> { });
        long start = System.nanoTime();
        VCompPipeline.Result result = pipeline.execute(new VCompPipeline.Request(
        VCompPipeline.ExecutionConstraints.orderedPartitionKeyValue(partitions), source));
        long maxBytes = 0;
        double scalarOverlap = 0;
        double tokenOverlap = 0;
        for (VCompPipeline.VirtualSortedRun run : result.layout().runs())
            for (VCompPipeline.VirtualSSTable sstable : run.sstables())
            {
                maxBytes = Math.max(maxBytes, sstable.estimatedBytes());
                scalarOverlap += ((double) sstable.keyMax() - sstable.keyMin() + 1) / writes;
                tokenOverlap += layout.tokenCoverage(sstable.keyMin(), sstable.keyMax());
            }
        System.out.printf("SIMULATION seconds=%.3f compactions=%d runs=%d sstables=%d physical_rows=%d estimated_bytes=%d max_sstable_bytes=%d scalar_mean_overlap=%.4f token_mean_overlap=%.4f%n",
                          (System.nanoTime() - start) / 1e9,
                          result.virtualCompactionCount(),
                          result.layout().runs().size(),
                          result.materialized().sstableIds().size(),
                          result.materialized().materializedKeys(),
                          result.materialized().logicalBytes(),
                          maxBytes,
                          scalarOverlap,
                          tokenOverlap);
    }
}
