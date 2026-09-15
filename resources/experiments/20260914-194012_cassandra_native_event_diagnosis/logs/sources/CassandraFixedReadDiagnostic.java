import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Set;
import java.util.SplittableRandom;

/** CPU-only fixed workload-C request export. Never constructs a Cassandra client or opens a DB. */
public final class CassandraFixedReadDiagnostic
{
    public static void main(String[] args) throws Exception
    {
        if (args.length != 1)
            throw new IllegalArgumentException("usage: CassandraFixedReadDiagnostic <new-output-json>");
        final long seed = 20260909L;
        final long domain = 104857600L;
        final int requests = 10000;
        Class<?> workload = CassandraPaperWorkload.class;
        Method workers = method(workload, "workerRandoms", long.class, int.class);
        SplittableRandom random = ((SplittableRandom[]) workers.invoke(null, seed, 1))[0];
        SplittableRandom valueRandom = random.split();
        // Preserve the actual worker setup and per-operation draws, including unused C draws.
        method(workload, "valuePool", SplittableRandom.class).invoke(null, valueRandom);
        Class<?> zipfClass = Class.forName("CassandraPaperWorkload$Zipf");
        Constructor<?> constructor = zipfClass.getDeclaredConstructor(long.class, long.class, double.class);
        constructor.setAccessible(true);
        long maximum = (long) constant(workload, "DEFAULT_ZIPF_MAX");
        double zeta = (double) constant(workload, "DEFAULT_ZETA");
        Object zipf = constructor.newInstance(1L, maximum, zeta);
        Method next = method(zipfClass, "next", SplittableRandom.class);
        Method fnv = method(workload, "fnv", long.class);
        Set<Long> unique = new HashSet<>();
        StringBuilder keys = new StringBuilder();
        for (int i = 0; i < requests; i++)
        {
            random.nextInt(100);
            valueRandom.nextLong();
            long sample = (long) next.invoke(zipf, random);
            long key = Long.remainderUnsigned((long) fnv.invoke(null, sample), domain);
            if (key < 0 || key >= domain)
                throw new AssertionError("request outside key domain");
            if (i != 0) keys.append(',');
            keys.append(key);
            unique.add(key);
        }
        String json = "{\n"
                    + "  \"classification\":\"CPU-only actual workload generator request export; no database requests\",\n"
                    + "  \"generator_version\":\"" + constant(workload, "GENERATOR_VERSION") + "\",\n"
                    + "  \"workload\":\"C\",\"seed\":" + seed + ",\"threads\":1,\"requests\":" + requests + ",\n"
                    + "  \"domain\":" + domain + ",\"partition_count\":10000,\"key_bytes\":24,\"value_bytes\":1000,\n"
                    + "  \"zipf_minimum\":1,\"zipf_maximum\":" + maximum + ",\"zipf_zeta\":" + zeta
                    + ",\"zipf_theta\":" + constant(workload, "ZIPF_THETA") + ",\n"
                    + "  \"rng_semantics\":\"actual workerRandoms(seed,1)[0]; payload=random.split(); actual valuePool(payload); per request random.nextInt(100),payload.nextLong(),actual Zipf.next(random),actual fnv,unsigned remainder(domain)\",\n"
                    + "  \"partition_semantics\":\"VCompOrderedPartitionLayout(domain,10000).partitionFor(key); contiguous scalar ranges assigned to token-sorted partition keys\",\n"
                    + "  \"query_semantics\":\"SELECT value WHERE partition_id=? AND ck=?; returned row null is miss\",\n"
                    + "  \"measurement_limits\":\"No native requests, latency, cache, Bloom/index or physical I/O measurement; static SST membership replay is not an SST-access count\",\n"
                    + "  \"unique_request_keys\":" + unique.size() + ",\n"
                    + "  \"keys\":[" + keys + "]\n}\n";
        Files.writeString(Path.of(args[0]), json, StandardCharsets.UTF_8,
                          StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        System.out.println("Exported " + requests + " fixed C requests (" + unique.size()
                           + " distinct keys), without database access: " + args[0]);
    }

    private static Method method(Class<?> owner, String name, Class<?>... types) throws Exception
    {
        Method method = owner.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method;
    }

    private static Object constant(Class<?> owner, String name) throws Exception
    {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }
}
