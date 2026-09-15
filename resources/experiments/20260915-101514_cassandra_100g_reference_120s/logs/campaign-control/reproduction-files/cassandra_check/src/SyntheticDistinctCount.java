import java.util.BitSet;
import java.util.SplittableRandom;

/** Exact distinct counter for the bounded synthetic key stream used by VCompBulkLoad. */
public final class SyntheticDistinctCount
{
    private SyntheticDistinctCount()
    {
    }

    public static void main(String[] args)
    {
        if (args.length != 3)
            throw new IllegalArgumentException("usage: SyntheticDistinctCount <writes> <keyspace> <seed>");

        long writes = Long.parseLong(args[0]);
        long keyspace = Long.parseLong(args[1]);
        long seed = Long.parseLong(args[2]);
        if (keyspace > Integer.MAX_VALUE)
            throw new IllegalArgumentException("BitSet diagnostic requires keyspace <= Integer.MAX_VALUE");

        BitSet seen = new BitSet((int) keyspace);
        SplittableRandom random = new SplittableRandom(seed);
        for (long i = 0; i < writes; i++)
            seen.set((int) random.nextLong(keyspace));

        System.out.printf("writes=%d keyspace=%d distinct=%d duplicates=%d%n",
                          writes,
                          keyspace,
                          seen.cardinality(),
                          writes - seen.cardinality());
    }
}
