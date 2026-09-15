import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.SplittableRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.PreparedId;
import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.ColumnDefinitions;
import com.datastax.driver.core.ProtocolVersion;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;

/** Client-only regression checks; uses in-memory driver proxies and never connects to Cassandra. */
public final class CassandraPaperWorkloadTest
{
    public static void main(String[] args) throws Exception
    {
        if (args.length != 1)
            throw new IllegalArgumentException("usage: CassandraPaperWorkloadTest <existing-disk-device>");
        checkDiskCounters();
        checkWorkerStreams();
        checkEScanLengths();
        checkMixGraphReference();
        checkAcknowledgedInserts();
        checkGrowingZipf();
        checkScanAccounting();
        AtomicInteger reads = new AtomicInteger();
        Object context = context(reads, false);
        Object measurement = run(context, args[0]);
        String result = result(context, measurement);
        require(reads.get() == 32, "fixed operation count changed");
        require(result.contains("\"operations\":32,"), "operation total missing");
        require(result.contains("\"point_reads\":32,"), "point read total missing");
        require(result.contains("\"read_misses\":16,"), "legacy miss count changed");
        require(result.contains("\"point_read_hits\":16,"), "hit count wrong");
        require(result.contains("\"point_read_misses\":16,"), "miss count wrong");
        require(result.contains("\"point_lookup_hit_latency_p99_us\":"), "hit latency missing");
        require(result.contains("\"point_lookup_miss_latency_p99_us\":"), "miss latency missing");
        require(result.contains("\"generator_version\":\"reference-streams-v3\","), "generator provenance missing");
        require(result.contains("\"measurement_mode\":\"fixed-operations\","), "mode missing");
        require(result.contains("\"requested_duration_seconds\":1,"), "duration provenance missing");
        require(result.contains("\"operations_per_thread\":8,"), "operation limit provenance missing");
        require(result.contains("\"scan_length_min\":0,"), "empty scan metrics invalid");
        require((double) field(measurement, "wallSeconds") > 0, "invalid measurement interval");
        Object timedContext = context(new AtomicInteger(), false);
        Method timedRun = method(timedContext.getClass(), "run", int.class, long.class, String.class);
        Object timed = timedRun.invoke(timedContext, 1, 0L, args[0]);
        require((double) field(timed, "wallSeconds") >= 1, "time mode ended before its requested duration");
        require(result(timedContext, timed).contains("\"measurement_mode\":\"time\","), "time mode missing");

        AtomicInteger unstartedReads = new AtomicInteger();
        expectFailure(context(unstartedReads, false), "no-such-workload-test-device", "disk device not found");
        require(unstartedReads.get() == 0, "counter failure released workers into workload");
        expectFailure(context(new AtomicInteger(), true), args[0], "workload failed");
        System.out.println("CassandraPaperWorkloadTest: streams, E lengths, C++ MixGraph vectors, acknowledged inserts, growing Zipf, cross-partition scans, diskstats units/reset/nullability, mode/counts, and failure cleanup passed");
    }

    private static void checkDiskCounters() throws Exception
    {
        Method parse = method(CassandraPaperWorkload.class, "parseDiskCounters", String.class, String.class);
        String beforeLine = "  259 0 md0 100 999 1000 200 80 777 2000 400 3 900 5000";
        String afterLine = "259 0 md0 104 999 1200 210 82 777 2300 404 0 999 9999 0 0 0 0 0 0";
        Object before = parse.invoke(null, beforeLine, "md0");
        Object after = parse.invoke(null, afterLine, "md0");
        require((long) field(before, "readBytes") == 512000, "read sector unit/index wrong");
        require((long) field(before, "writeBytes") == 1024000, "write sector unit/index wrong");
        require((long) field(before, "readRequests") == 100, "read requests confused with merged requests");
        require((long) field(before, "writeRequests") == 80, "write requests confused with merged requests");
        require((long) field(before, "readTimeMs") == 200, "read time field wrong");
        require((long) field(before, "writeTimeMs") == 400, "write time field wrong");
        require(parse.invoke(null, beforeLine, "md01") == null, "device matching is not exact");
        require(parse.invoke(null, " ", "md0") == null, "blank diskstats line was not skipped");

        Object measured = diskMeasurement(before, after);
        require((long) field(measured, "diskReadBytes") == 200 * 512, "legacy read bytes changed");
        require((long) field(measured, "diskWriteBytes") == 300 * 512, "legacy write bytes changed");
        require((long) field(measured, "diskReadRequests") == 4, "read request delta wrong");
        require((long) field(measured, "diskWriteRequests") == 2, "write request delta wrong");
        require((long) field(measured, "diskReadTimeMs") == 10, "read elapsed delta wrong");
        require((long) field(measured, "diskWriteTimeMs") == 4, "write elapsed delta wrong");
        require((double) field(measured, "diskReadLatencyAvgMs") == 2.5, "read mean should be 10 ms / 4 requests");
        require((double) field(measured, "diskWriteLatencyAvgMs") == 2.0, "write mean should be 4 ms / 2 requests");
        String measuredJson = result(context(new AtomicInteger(), false), measured);
        require(measuredJson.contains("\"disk_read_latency_avg_ms\":2.5,"), "read mean JSON is not numeric ms");
        require(measuredJson.contains("\"disk_write_requests\":2,"), "write requests missing in JSON");
        require(measuredJson.contains("block device md0; mean completed request latency; not percentiles"), "device-layer scope missing");

        Object idle = diskMeasurement(before, before);
        require((long) field(idle, "diskReadRequests") == 0, "idle requests must remain zero");
        require(field(idle, "diskReadLatencyAvgMs") == null && field(idle, "diskWriteLatencyAvgMs") == null,
                "zero requests must yield undefined averages");
        String idleJson = result(context(new AtomicInteger(), false), idle);
        require(idleJson.contains("\"disk_read_latency_avg_ms\":null,"), "undefined mean must be JSON null");
        require(!idleJson.contains("NaN") && !idleJson.contains("Infinity"), "non-finite JSON output");

        for (String resetLine : new String[] {
            "259 0 md0 99 999 1200 210 82 777 2300 404 0 999 9999",
            "259 0 md0 104 999 1200 199 82 777 2300 404 0 999 9999",
            "259 0 md0 104 999 999 210 82 777 2300 404 0 999 9999" })
        {
            Object reset = diskMeasurement(before, parse.invoke(null, resetLine, "md0"));
            require(field(reset, "diskReadRequests") == null && field(reset, "diskReadTimeMs") == null
                    && field(reset, "diskReadLatencyAvgMs") == null, "reset read direction was reported as valid");
            require((double) field(reset, "diskWriteLatencyAvgMs") == 2.0, "read reset invalidated independent writes");
        }
        Object writeReset = diskMeasurement(before, parse.invoke(null,
        "259 0 md0 104 999 1200 210 82 777 2300 399 0 999 9999", "md0"));
        require(field(writeReset, "diskWriteLatencyAvgMs") == null, "write timer reset was not detected");
        Object roundedZero = diskMeasurement(before, parse.invoke(null,
        "259 0 md0 104 999 1200 200 82 777 2300 400 0 999 9999", "md0"));
        require((double) field(roundedZero, "diskReadLatencyAvgMs") == 0,
                "millisecond truncation with completed requests is a measured zero, not undefined");
        for (String malformed : new String[] { "259 0 md0 1 2 3", "259 0 md0 -1 0 1000 200 80 0 2000 400 0 0 0" })
        {
            try
            {
                parse.invoke(null, malformed, "md0");
                throw new AssertionError("malformed target diskstats line accepted");
            }
            catch (InvocationTargetException exception)
            {
                require(exception.getCause() instanceof IllegalArgumentException, "unexpected diskstats parsing failure");
            }
        }
    }

    private static Object diskMeasurement(Object before, Object after) throws Exception
    {
        return construct(Class.forName("CassandraPaperWorkload$Measurement"),
                         new Class<?>[] { double.class, before.getClass(), after.getClass() }, 1.0, before, after);
    }

    private static void checkEScanLengths() throws Exception
    {
        Method length = method(CassandraPaperWorkload.class, "ycsbScanLength", SplittableRandom.class);
        SplittableRandom random = new SplittableRandom(20260909L);
        int[] lengths = new int[101];
        long total = 0;
        long scans = 0;
        for (int i = 0; i < 100_000; i++)
        {
            if (random.nextInt(100) >= 95) continue;
            int sampled = (int) length.invoke(null, random);
            require(sampled >= 1 && sampled <= 100, "E length outside reference bounds");
            lengths[sampled]++;
            total += sampled;
            scans++;
        }
        for (int i = 1; i <= 100; i++) require(lengths[i] > 700, "E lengths are coupled to operation choice: " + i);
        require(Math.abs(total / (double) scans - 50.5) < 0.3, "E scan mean is not uniform 1..100");
    }

    private static void checkMixGraphReference() throws Exception
    {
        // Golden vectors independently produced with C++ std::mt19937_64 and
        // tools/db_bench_tool.cc GenerateTwoTermExpKeys (not this Java implementation).
        Class<?> mt = Class.forName("CassandraPaperWorkload$Mt19937_64");
        Method next = method(mt, "next");
        Method first = method(mt, "first", long.class);
        String[][] vectors = {
            { "0", "2947667278772165694", "18301848765998365067", "729919693006235833", "11021831128136023278" },
            { "1", "2469588189546311528", "2516265689700432462", "8323445853463659930", "387828560950575246" },
            { "5489", "14514284786278117030", "4620546740167642908", "13109570281517897720", "17462938647148434322" },
            { "20260909", "8761891397929658114", "1285713182708298724", "5438228646420350821", "12210227628917637337" },
            { "18446744073709551615", "478026398904862820", "13243134898385798468", "709236020254955927", "9482188692832154854" }
        };
        for (String[] vector : vectors)
        {
            long seed = Long.parseUnsignedLong(vector[0]);
            Object generator = construct(mt, new Class<?>[] { long.class }, seed);
            require((long) first.invoke(null, seed) == Long.parseUnsignedLong(vector[1]), "optimized MT first draw differs from C++");
            for (int i = 1; i < vector.length; i++)
                require((long) next.invoke(generator) == Long.parseUnsignedLong(vector[i]), "MT differs from C++");
        }
        Class<?> type = Class.forName("CassandraPaperWorkload$MixGraph");
        Method key = method(type, "key", long.class);
        Object model = construct(type, new Class<?>[] { long.class }, 104857600L);
        long[][] keys = { {0, 1662797}, {1, 5193262}, {829, 103060346}, {830, 103060346},
                          {969, 103060346}, {970, 103060346}, {999, 103060346},
                          {100000, 102388373}, {104857599, 103060346} };
        for (long[] vector : keys)
            require((long) key.invoke(model, vector[0]) == vector[1], "MixGraph key differs from C++ for " + vector[0]);
        Method size = method(CassandraPaperWorkload.class, "mixValueSize", long.class);
        require((int) size.invoke(null, 0L) == 10, "small MixGraph value bound changed");
        require((int) size.invoke(null, 1024L) == 1024, "exact MixGraph value cap changed");
        require((int) size.invoke(null, 2147483771L) == 123, "Pareto sample overflowed before value cap");
        require((int) size.invoke(null, 2048L) == 0, "reference modulo value-size semantics changed");
    }

    private static void checkAcknowledgedInserts() throws Exception
    {
        Class<?> type = Class.forName("CassandraPaperWorkload$AcknowledgedInserts");
        Object tracker = construct(type, new Class<?>[] { long.class }, 999L);
        Method acknowledge = method(type, "acknowledge", long.class);
        Method maximum = method(type, "maximum");
        acknowledge.invoke(tracker, 1001L);
        require((long) maximum.invoke(tracker) == 999, "latest read exposed unfinished insert");
        acknowledge.invoke(tracker, 1000L);
        require((long) maximum.invoke(tracker) == 1001, "acknowledged frontier did not catch up");
    }

    private static void checkGrowingZipf() throws Exception
    {
        Class<?> type = Class.forName("CassandraPaperWorkload$Zipf");
        Method factory = method(type, "forMaximum", long.class, double.class);
        Object growing = factory.invoke(null, 99L, 0.99);
        Object full = factory.invoke(null, 999L, 0.99);
        method(type, "extendMaximum", long.class).invoke(growing, 999L);
        require(Math.abs((double) field(growing, "zetaN") - (double) field(full, "zetaN")) < 1e-12,
                "latest Zipf normalization differs from rebuilt distribution");
        Method next = method(type, "next", SplittableRandom.class);
        SplittableRandom a = new SplittableRandom(123), b = new SplittableRandom(123);
        for (int i = 0; i < 1000; i++) require(next.invoke(growing, a).equals(next.invoke(full, b)), "growing Zipf changed ranks");
        Object singleton = factory.invoke(null, 0L, 0.99);
        for (int i = 0; i < 100; i++) require((long) next.invoke(singleton, a) == 0, "singleton Zipf out of bounds");
    }

    private static void checkScanAccounting() throws Exception
    {
        Field empty = ColumnDefinitions.class.getDeclaredField("EMPTY");
        empty.setAccessible(true);
        Constructor<?> idConstructor = PreparedId.class.getDeclaredConstructors()[0];
        idConstructor.setAccessible(true);
        Class<?> digestType = Class.forName("com.datastax.driver.core.MD5Digest");
        Object digest = method(digestType, "wrap", byte[].class).invoke(null, (Object) new byte[16]);
        Object metadata = construct(Class.forName("com.datastax.driver.core.PreparedId$PreparedMetadata"),
                                    new Class<?>[] { digestType, ColumnDefinitions.class }, digest, empty.get(null));
        Object id = idConstructor.newInstance(metadata, metadata, new int[0], ProtocolVersion.V4);
        Map<BoundStatement, Object[]> bindings = new IdentityHashMap<>();
        List<Object[]> requests = new ArrayList<>();
        PreparedStatement prepared = proxy(PreparedStatement.class, (p, operation, args) -> {
            switch (operation.getName())
            {
                case "getVariables": return empty.get(null);
                case "getPreparedId": return id;
                case "isTracing": return false;
                case "bind":
                    BoundStatement statement = new BoundStatement((PreparedStatement) p);
                    bindings.put(statement, (Object[]) args[0]);
                    return statement;
                default: return null;
            }
        });
        AtomicInteger call = new AtomicInteger();
        Row row = proxy(Row.class, (p, operation, args) -> ByteBuffer.wrap(new byte[("ck".equals(args[0])) ? 24 : 1000]));
        Session session = proxy(Session.class, (p, operation, args) -> {
            if (operation.getName().equals("prepare")) return prepared;
            if (operation.getName().equals("execute"))
            {
                requests.add(bindings.get((BoundStatement) args[0]));
                int count = call.getAndIncrement() == 0 ? 1 : 2;
                return proxy(ResultSet.class, (r, method, unused) -> {
                    if (method.getName().equals("iterator")) return Collections.nCopies(count, row).iterator();
                    throw new AssertionError(method);
                });
            }
            throw new AssertionError(operation);
        });
        Class<?> type = Class.forName("CassandraPaperWorkload$WorkloadContext");
        Constructor<?> constructor = type.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        Object context = constructor.newInstance(session, "test", "kv", "E", 1000L, 24, 1000, 5, 20260909L, 1);
        Method scan = method(type, "rangeScan", long.class, int.class);
        scan.invoke(context, 199L, 3);
        require(requests.size() == 2, "scan did not cross partition boundary");
        require((int) requests.get(0)[2] == 3 && (int) requests.get(1)[2] == 2, "scan did not reduce remaining row limit");
        require(!requests.get(0)[0].equals(requests.get(1)[0]), "scan did not advance partition");
        org.apache.cassandra.db.compaction.vcomp.DeterministicFixedWidthKeyCodec codec =
            new org.apache.cassandra.db.compaction.vcomp.DeterministicFixedWidthKeyCodec(24);
        require(requests.get(0)[1].equals(codec.decode(199)) && requests.get(1)[1].equals(codec.decode(200)),
                "scan lower bound was not rebased at partition boundary");
        require(((java.util.concurrent.atomic.LongAdder) field(context, "scanReturnedRows")).sum() == 3, "scan row accounting wrong");
        require(((java.util.concurrent.atomic.LongAdder) field(context, "scanReturnedBytes")).sum() == 3072, "scan byte accounting wrong");
        scan.invoke(context, 999L, 0);
        require(requests.size() == 3, "zero-length MixGraph scan skipped its seek");
        require((int) requests.get(2)[2] == 1, "seek-only CQL emulation did not request one row");
        require(((java.util.concurrent.atomic.LongAdder) field(context, "scanSeekOnlyRows")).sum() == 1, "seek-only rows not separated");
        require(((java.util.concurrent.atomic.LongAdder) field(context, "scanReturnedRows")).sum() == 3, "seek-only row counted as logical scan row");
    }

    private static Method method(Class<?> type, String name, Class<?>... arguments) throws Exception
    {
        Method result = type.getDeclaredMethod(name, arguments);
        result.setAccessible(true);
        return result;
    }

    private static Object construct(Class<?> type, Class<?>[] arguments, Object... values) throws Exception
    {
        Constructor<?> constructor = type.getDeclaredConstructor(arguments);
        constructor.setAccessible(true);
        return constructor.newInstance(values);
    }

    private static void checkWorkerStreams() throws Exception
    {
        Method factory = CassandraPaperWorkload.class.getDeclaredMethod("workerRandoms", long.class, int.class);
        factory.setAccessible(true);
        SplittableRandom[] first = (SplittableRandom[]) factory.invoke(null, 20260909L, 48);
        SplittableRandom[] second = (SplittableRandom[]) factory.invoke(null, 20260909L, 48);
        long[][] samples = new long[48][128];
        for (int worker = 0; worker < samples.length; worker++)
            for (int draw = 0; draw < samples[worker].length; draw++)
            {
                samples[worker][draw] = first[worker].nextLong();
                require(samples[worker][draw] == second[worker].nextLong(), "worker stream is not deterministic");
            }
        for (int worker = 1; worker < samples.length; worker++)
            for (int offset = 0; offset < 48; offset++)
                require(samples[0][offset] != samples[worker][0], "worker streams repeat a shifted prefix");
    }

    private static Object context(AtomicInteger reads, boolean failReads) throws Exception
    {
        PreparedStatement prepared = proxy(PreparedStatement.class, (p, method, args) -> null);
        Row row = proxy(Row.class, (p, method, args) -> ByteBuffer.wrap(new byte[] { 1 }));
        Session session = proxy(Session.class, (p, method, args) -> {
            if (method.getName().equals("prepare"))
                return prepared;
            if (method.getName().equals("execute"))
            {
                if (failReads)
                    throw new IllegalStateException("injected read failure");
                boolean hit = reads.incrementAndGet() % 2 == 0;
                return proxy(ResultSet.class, (r, operation, arguments) -> hit ? row : null);
            }
            throw new AssertionError("unexpected session call " + method);
        });
        Class<?> type = Class.forName("CassandraPaperWorkload$WorkloadContext");
        Constructor<?> constructor = type.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        return constructor.newInstance(session, "test", "kv", "C", 1000L, 24, 1000, 5, 20260909L, 4);
    }

    private static Object run(Object context, String device) throws Exception
    {
        Method run = context.getClass().getDeclaredMethod("run", int.class, long.class, String.class);
        run.setAccessible(true);
        return run.invoke(context, 1, 8L, device);
    }

    private static void expectFailure(Object context, String device, String message) throws Exception
    {
        try
        {
            run(context, device);
            throw new AssertionError("expected failure: " + message);
        }
        catch (InvocationTargetException exception)
        {
            require(exception.getCause().getMessage().contains(message), "unexpected failure: " + exception.getCause());
        }
    }

    private static String result(Object context, Object measurement) throws Exception
    {
        Method print = context.getClass().getDeclaredMethod("printResult", measurement.getClass(), String.class);
        print.setAccessible(true);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream original = System.out;
        try (PrintStream capture = new PrintStream(output, true, StandardCharsets.UTF_8.name()))
        {
            System.setOut(capture);
            print.invoke(context, measurement, "md0");
        }
        finally
        {
            System.setOut(original);
        }
        return output.toString(StandardCharsets.UTF_8.name());
    }

    private static Object field(Object object, String name) throws Exception
    {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler)
    {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, handler));
    }

    private static void require(boolean condition, String message)
    {
        if (!condition)
            throw new AssertionError(message);
    }
}
