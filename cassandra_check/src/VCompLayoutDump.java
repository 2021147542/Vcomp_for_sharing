import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import org.apache.cassandra.db.compaction.vcomp.VCompOrderedPartitionLayout;

/** Prints the deterministic partition-key to scalar-ordinal mapping for diagnostics. */
public final class VCompLayoutDump
{
    public static void main(String[] args)
    {
        if (args.length == 1 && args[0].equals("mixgraph-weights"))
        {
            List<Double> weights = new ArrayList<>(30);
            for (int i = 0; i < 30; i++)
            {
                double prefix = 30 - i;
                weights.add(14.18 * Math.exp(-2.917 * prefix) + 0.0164 * Math.exp(-0.08082 * prefix));
            }
            SplittableRandom shuffle = new SplittableRandom(0x6d69786772617068L);
            for (int i = weights.size() - 1; i > 0; i--)
            {
                int j = shuffle.nextInt(i + 1);
                double temporary = weights.get(i);
                weights.set(i, weights.get(j));
                weights.set(j, temporary);
            }
            double sum = weights.stream().mapToDouble(Double::doubleValue).sum();
            for (int i = 0; i < weights.size(); i++)
                System.out.printf("%d\t%.12f%n", i, weights.get(i) / sum);
            return;
        }
        if (args.length != 2)
            throw new IllegalArgumentException("usage: VCompLayoutDump <key-space> <partition-count> | mixgraph-weights");
        VCompOrderedPartitionLayout layout = new VCompOrderedPartitionLayout(Long.parseLong(args[0]),
                                                                              Integer.parseInt(args[1]));
        for (VCompOrderedPartitionLayout.Partition partition : layout.partitions())
            System.out.printf("%s\t%d\t%d\t%d\t%d%n", partition.key(), partition.ordinal(),
                              partition.token(), partition.minimum(), partition.maximum());
    }
}
