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
import java.util.concurrent.atomic.AtomicInteger;

import com.datastax.driver.core.PreparedStatement;
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
        checkWorkerStreams();
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
        require(result.contains("\"generator_version\":\"split-streams-v2\","), "generator provenance missing");
        require((double) field(measurement, "wallSeconds") > 0, "invalid measurement interval");

        AtomicInteger unstartedReads = new AtomicInteger();
        expectFailure(context(unstartedReads, false), "no-such-workload-test-device", "disk device not found");
        require(unstartedReads.get() == 0, "counter failure released workers into workload");
        expectFailure(context(new AtomicInteger(), true), args[0], "workload failed");
        System.out.println("CassandraPaperWorkloadTest: independent deterministic streams, fixed counts, hit/miss output, and failure cleanup passed");
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
        Method print = context.getClass().getDeclaredMethod("printResult", double.class, long.class, long.class, String.class);
        print.setAccessible(true);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream original = System.out;
        try (PrintStream capture = new PrintStream(output, true, StandardCharsets.UTF_8.name()))
        {
            System.setOut(capture);
            print.invoke(context, field(measurement, "wallSeconds"), 0L, 0L, "test");
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
