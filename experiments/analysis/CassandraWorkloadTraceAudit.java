import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.BitSet;
import java.util.HashSet;
import java.util.Set;
import java.util.SplittableRandom;

/** CPU-only replay of the retained faithful-v2 100 GiB workload C configuration. No DB access. */
public final class CassandraWorkloadTraceAudit
{
    public static void main(String[] args) throws Exception
    {
        if (args.length != 1 || !args[0].matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("usage: CassandraWorkloadTraceAudit <CassandraPaperWorkload.java-sha256>");
        final int keySpace = 104857600;
        final int operationsPerThread = 20000;
        final int threads = 48;
        final long seed = 20260909;
        BitSet loaded = new BitSet(keySpace);
        SplittableRandom load = new SplittableRandom(seed);
        for (int i = 0; i < keySpace; i++)
            loaded.set((int) load.nextLong(keySpace));

        Class<?> workloadClass = Class.forName("CassandraPaperWorkload");
        Class<?> zipfClass = Class.forName("CassandraPaperWorkload$Zipf");
        Constructor<?> constructor = zipfClass.getDeclaredConstructor(long.class, long.class, double.class);
        constructor.setAccessible(true);
        Object zipf = constructor.newInstance(1L, 10000000000L, 26.46902820178302);
        Method next = zipfClass.getDeclaredMethod("next", SplittableRandom.class);
        next.setAccessible(true);
        Method fnv = workloadClass.getDeclaredMethod("fnv", long.class);
        fnv.setAccessible(true);
        Method workerRandoms = workloadClass.getDeclaredMethod("workerRandoms", long.class, int.class);
        workerRandoms.setAccessible(true);
        Field generatorVersion = workloadClass.getDeclaredField("GENERATOR_VERSION");
        generatorVersion.setAccessible(true);

        for (boolean current : new boolean[] { false, true })
        {
            SplittableRandom[] randoms = current ? (SplittableRandom[]) workerRandoms.invoke(null, seed, threads)
                                                : new SplittableRandom[threads];
            Set<Long> unique = new HashSet<>();
            long misses = 0;
            long[][] keys = new long[threads][operationsPerThread];
            for (int worker = 0; worker < threads; worker++)
            {
                SplittableRandom random = current ? randoms[worker]
                                                 : new SplittableRandom(seed + worker * 0x9e3779b97f4a7c15L);
                // Match valuePool(): it consumes this many nextInt(256) calls before the first query.
                for (int i = 0; i < (1 << 20); i++)
                    random.nextInt(256);
                for (int i = 0; i < operationsPerThread; i++)
                {
                    random.nextLong(); // operation-choice draw, consumed even by pure-read workload C
                    long key = Long.remainderUnsigned((long) fnv.invoke(null, (long) next.invoke(zipf, random)), keySpace);
                    keys[worker][i] = key;
                    unique.add(key);
                    if (!loaded.get((int) key))
                        misses++;
                }
            }
            int shiftedMatches = 0;
            for (int i = 0; i < operationsPerThread - 1; i++)
                if (keys[0][i + 1] == keys[2][i])
                    shiftedMatches++;
            if (!current && (loaded.cardinality() != 66278498 || misses != 408858 || unique.size() != 28109))
                throw new AssertionError("legacy replay no longer reproduces the retained historical workload C");
            long requests = (long) threads * operationsPerThread;
            System.out.printf(java.util.Locale.ROOT,
                              "{\"workload\":\"C\",\"generator_version\":\"%s\",\"workload_source_sha256\":\"%s\","
                              + "\"seed\":%d,\"key_space\":%d,\"threads\":%d,\"operations_per_thread\":%d,"
                              + "\"loaded_unique_keys\":%d,\"requests\":%d,\"distinct_query_keys\":%d,"
                              + "\"read_misses\":%d,\"hit_rate_fraction\":%.9f,"
                              + "\"worker_0_shifted_vs_worker_2_equal_keys\":%d,\"shift_comparisons\":%d}%n",
                              current ? generatorVersion.get(null) : "legacy-gamma-v1", args[0],
                              seed, keySpace, threads, operationsPerThread, loaded.cardinality(), requests,
                              unique.size(), misses, 1 - misses / (double) requests, shiftedMatches, operationsPerThread - 1);
        }
    }
}
