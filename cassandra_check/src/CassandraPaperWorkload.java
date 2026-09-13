import java.io.BufferedReader;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.SocketOptions;
import org.HdrHistogram.Histogram;
import org.HdrHistogram.Recorder;

import org.apache.cassandra.db.compaction.vcomp.DeterministicFixedWidthKeyCodec;
import org.apache.cassandra.db.compaction.vcomp.VCompOrderedPartitionLayout;

/** Cassandra counterpart of the in-repository Pebble YCSB A-F/MixGraph workload. */
public final class CassandraPaperWorkload
{
    private static final long MAX_LATENCY_NANOS = TimeUnit.MINUTES.toNanos(2);
    private static final double ZIPF_THETA = 0.99;
    private static final long DEFAULT_ZIPF_MAX = 10_000_000_000L;
    private static final double DEFAULT_ZETA = 26.46902820178302;
    private static final long FNV_OFFSET = -3750763034362895579L;
    private static final long FNV_PRIME = 1099511628211L;

    private CassandraPaperWorkload()
    {
    }

    public static void main(String[] args) throws Exception
    {
        if (args.length != 11)
            throw new IllegalArgumentException("usage: CassandraPaperWorkload <keyspace> <table> <workload> "
                                               + "<seconds> <threads> <keyspace-size> <key-bytes> <value-bytes> "
                                               + "<partition-count> <seed> <disk-device>");
        String keyspace = identifier(args[0]);
        String table = identifier(args[1]);
        String workload = args[2].toUpperCase(Locale.ROOT);
        int seconds = Integer.parseInt(args[3]);
        int threads = Integer.parseInt(args[4]);
        long keySpace = Long.parseLong(args[5]);
        int keyBytes = Integer.parseInt(args[6]);
        int valueBytes = Integer.parseInt(args[7]);
        int partitionCount = Integer.parseInt(args[8]);
        long seed = Long.parseLong(args[9]);
        String diskDevice = args[10];
        definition(workload);
        if (seconds <= 0 || threads <= 0 || keySpace <= 0 || valueBytes <= 0)
            throw new IllegalArgumentException("seconds, threads, keyspace-size, and value-bytes must be positive");

        SocketOptions socket = new SocketOptions().setReadTimeoutMillis(120_000).setConnectTimeoutMillis(30_000);
        try (Cluster cluster = Cluster.builder().addContactPoint("127.0.0.1").withPort(9042)
                                      .withSocketOptions(socket).withoutJMXReporting().withoutMetrics().build();
             Session session = cluster.connect())
        {
            WorkloadContext context = new WorkloadContext(session, keyspace, table, workload, keySpace,
                                                          keyBytes, valueBytes, partitionCount, seed, threads);
            DiskCounters before = diskCounters(diskDevice);
            long start = System.nanoTime();
            context.run(seconds);
            double wallSeconds = (System.nanoTime() - start) / 1_000_000_000.0;
            DiskCounters after = diskCounters(diskDevice);
            context.printResult(wallSeconds, after.readBytes - before.readBytes,
                                after.writeBytes - before.writeBytes, diskDevice);
        }
    }

    private static final class WorkloadContext
    {
        private final String workload;
        private final long keySpace;
        private final int valueBytes;
        private final long seed;
        private final int threads;
        private final Session session;
        private final DeterministicFixedWidthKeyCodec codec;
        private final VCompOrderedPartitionLayout partitionLayout;
        private final PreparedStatement read;
        private final PreparedStatement write;
        private final PreparedStatement scan;
        private final Zipf zipf = new Zipf(1, DEFAULT_ZIPF_MAX, DEFAULT_ZETA);
        private final Zipf latestZipf;
        private final MixGraph mixGraph;
        private final AtomicLong nextInsert;
        private final LongAdder operations = new LongAdder();
        private final LongAdder pointReads = new LongAdder();
        private final LongAdder scans = new LongAdder();
        private final LongAdder writes = new LongAdder();
        private final LongAdder misses = new LongAdder();
        private final Recorder operationLatency = new Recorder(MAX_LATENCY_NANOS, 3);
        private final Recorder pointLatency = new Recorder(MAX_LATENCY_NANOS, 3);
        private final Recorder scanLatency = new Recorder(MAX_LATENCY_NANOS, 3);
        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        private WorkloadContext(Session session, String keyspace, String table, String workload,
                                long keySpace, int keyBytes, int valueBytes, int partitionCount,
                                long seed, int threads)
        {
            this.workload = workload;
            this.keySpace = keySpace;
            this.valueBytes = valueBytes;
            this.seed = seed;
            this.threads = threads;
            this.session = session;
            this.codec = new DeterministicFixedWidthKeyCodec(keyBytes);
            this.partitionLayout = new VCompOrderedPartitionLayout(keySpace, partitionCount);
            this.read = session.prepare("SELECT value FROM " + keyspace + '.' + table
                                        + " WHERE partition_id = ? AND ck = ?");
            this.write = session.prepare("INSERT INTO " + keyspace + '.' + table
                                         + " (partition_id, ck, value) VALUES (?, ?, ?)");
            this.scan = session.prepare("SELECT ck, value FROM " + keyspace + '.' + table
                                        + " WHERE partition_id = ? AND ck >= ? LIMIT ?");
            this.latestZipf = "D".equals(workload) ? Zipf.forMaximum(keySpace - 1, ZIPF_THETA) : null;
            this.mixGraph = new MixGraph(keySpace);
            this.nextInsert = new AtomicLong(keySpace);
        }

        private void run(int seconds) throws Exception
        {
            ExecutorService executor = Executors.newFixedThreadPool(threads);
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch start = new CountDownLatch(1);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
            for (int worker = 0; worker < threads; worker++)
            {
                final int workerId = worker;
                executor.execute(() -> runWorker(workerId, ready, start, deadline));
            }
            ready.await();
            start.countDown();
            long nextProgress = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (System.nanoTime() < deadline && failure.get() == null)
            {
                long sleep = Math.min(TimeUnit.SECONDS.toNanos(1), deadline - System.nanoTime());
                if (sleep > 0)
                    TimeUnit.NANOSECONDS.sleep(sleep);
                if (System.nanoTime() >= nextProgress)
                {
                    System.out.printf("WORKLOAD_PROGRESS workload=%s seconds=%d operations=%d%n",
                                      workload, seconds - Math.max(0, TimeUnit.NANOSECONDS.toSeconds(deadline - System.nanoTime())),
                                      operations.sum());
                    nextProgress += TimeUnit.SECONDS.toNanos(30);
                }
            }
            executor.shutdown();
            if (!executor.awaitTermination(5, TimeUnit.MINUTES))
                throw new IllegalStateException("workload workers did not stop");
            Throwable problem = failure.get();
            if (problem != null)
                throw new RuntimeException("workload failed", problem);
        }

        private void runWorker(int worker, CountDownLatch ready, CountDownLatch start, long deadline)
        {
            SplittableRandom random = new SplittableRandom(seed + worker * 0x9e3779b97f4a7c15L);
            byte[] valuePool = valuePool(random);
            ready.countDown();
            try
            {
                start.await();
                while (System.nanoTime() < deadline && failure.get() == null)
                {
                    long randomValue = random.nextLong();
                    int choice = (int) Long.remainderUnsigned(randomValue, 100);
                    long key = scrambledZipf(random);
                    long started = System.nanoTime();
                    switch (workload)
                    {
                        case "A":
                            if (choice < 50) pointRead(key); else put(key, valuePool, randomValue, valueBytes);
                            break;
                        case "B":
                            if (choice < 95) pointRead(key); else put(key, valuePool, randomValue, valueBytes);
                            break;
                        case "C":
                            pointRead(key);
                            break;
                        case "D":
                            if (choice < 95)
                            {
                                long maximum = nextInsert.get() - 1;
                                long rank = latestZipf.next(random);
                                pointRead(Math.max(0, maximum - Math.min(maximum, rank)));
                            }
                            else
                            {
                                put(nextInsert.getAndIncrement(), valuePool, randomValue, valueBytes);
                            }
                            break;
                        case "E":
                            if (choice < 95) rangeScan(key, 1 + (int) Long.remainderUnsigned(randomValue, 100));
                            else put(nextInsert.getAndIncrement(), valuePool, randomValue, valueBytes);
                            break;
                        case "F":
                            if (choice < 50) pointRead(key); else readModifyWrite(key, valuePool, randomValue);
                            break;
                        case "MIXGRAPH":
                            key = mixGraph.key(randomValue);
                            int mixChoice = (int) Long.remainderUnsigned(randomValue, 1000);
                            if (mixChoice < 830) pointRead(key);
                            else if (mixChoice < 970)
                            {
                                int size = (int) pareto(randomValue, 0.2615, 25.45);
                                if (size < 10) size = 10;
                                else if (size > 1024) size %= 1024;
                                put(key, valuePool, randomValue, size);
                            }
                            else
                            {
                                int length = (int) (pareto(randomValue, 2.517, 14.236) % 10_000);
                                rangeScan(key, length);
                            }
                            break;
                        default:
                            throw new AssertionError(workload);
                    }
                    operationLatency.recordValue(Math.min(MAX_LATENCY_NANOS, System.nanoTime() - started));
                    operations.increment();
                }
            }
            catch (Throwable t)
            {
                failure.compareAndSet(null, t);
            }
        }

        private long scrambledZipf(SplittableRandom random)
        {
            return Long.remainderUnsigned(fnv(zipf.next(random)), keySpace);
        }

        private void pointRead(long key)
        {
            long started = System.nanoTime();
            Row row = session.execute(read.bind(partitionKey(key), codec.decode(key))).one();
            pointLatency.recordValue(Math.min(MAX_LATENCY_NANOS, System.nanoTime() - started));
            pointReads.increment();
            if (row == null)
                misses.increment();
            else
                row.getBytes("value").remaining();
        }

        private void put(long key, byte[] pool, long random, int size)
        {
            session.execute(write.bind(partitionKey(key), codec.decode(key), value(pool, random, size)));
            writes.increment();
        }

        private void readModifyWrite(long key, byte[] pool, long random)
        {
            long started = System.nanoTime();
            Row row = session.execute(read.bind(partitionKey(key), codec.decode(key))).one();
            pointLatency.recordValue(Math.min(MAX_LATENCY_NANOS, System.nanoTime() - started));
            pointReads.increment();
            byte[] value;
            if (row == null)
            {
                misses.increment();
                value = copyValue(pool, random, valueBytes);
            }
            else
            {
                ByteBuffer buffer = row.getBytes("value").duplicate();
                value = new byte[buffer.remaining()];
                buffer.get(value);
            }
            if (value.length > 0)
                value[(int) Long.remainderUnsigned(random, value.length)]++;
            session.execute(write.bind(partitionKey(key), codec.decode(key), ByteBuffer.wrap(value)));
            writes.increment();
        }

        private void rangeScan(long key, int limit)
        {
            long started = System.nanoTime();
            if (limit > 0)
            {
                int remaining = limit;
                VCompOrderedPartitionLayout.Partition partition = partitionFor(key);
                long lowerBound = key;
                for (int index = partition.ordinal(); index < partitionLayout.partitionCount() && remaining > 0; index++)
                {
                    partition = partitionLayout.partitions().get(index);
                    if (index != partitionFor(key).ordinal())
                        lowerBound = partition.minimum();
                    BoundStatement statement = scan.bind(partition.key(), codec.decode(lowerBound), remaining);
                    statement.setFetchSize(Math.min(4096, remaining));
                    ResultSet rows = session.execute(statement);
                    for (Row row : rows)
                    {
                        row.getBytes("ck").remaining();
                        row.getBytes("value").remaining();
                        remaining--;
                        if (remaining == 0)
                            break;
                    }
                }
            }
            scanLatency.recordValue(Math.min(MAX_LATENCY_NANOS, System.nanoTime() - started));
            scans.increment();
        }

        private VCompOrderedPartitionLayout.Partition partitionFor(long key)
        {
            return key < keySpace ? partitionLayout.partitionFor(key)
                                  : partitionLayout.partitions().get(partitionLayout.partitionCount() - 1);
        }

        private String partitionKey(long key)
        {
            return partitionFor(key).key();
        }

        private void printResult(double wallSeconds, long diskReadBytes, long diskWriteBytes, String device)
        {
            Histogram all = operationLatency.getIntervalHistogram();
            Histogram points = pointLatency.getIntervalHistogram();
            Histogram range = scanLatency.getIntervalHistogram();
            long count = operations.sum();
            System.out.printf(Locale.ROOT,
                              "WORKLOAD_RESULT {\"workload\":\"%s\",\"definition\":\"%s\","
                              + "\"key_distribution\":\"%s\",\"wall_seconds\":%.6f,\"threads\":%d,"
                              + "\"operations\":%d,\"throughput_ops_per_second\":%.6f,"
                              + "\"point_reads\":%d,\"writes\":%d,\"scans\":%d,\"read_misses\":%d,"
                              + "\"operation_latency_p50_us\":%.3f,\"operation_latency_p95_us\":%.3f,"
                              + "\"operation_latency_p99_us\":%.3f,\"point_lookup_latency_p50_us\":%.3f,"
                              + "\"point_lookup_latency_p95_us\":%.3f,\"point_lookup_latency_p99_us\":%.3f,"
                              + "\"scan_latency_p50_us\":%.3f,\"scan_latency_p95_us\":%.3f,"
                              + "\"scan_latency_p99_us\":%.3f,\"disk_device\":\"%s\","
                              + "\"disk_read_bytes\":%d,\"disk_write_bytes\":%d}%n",
                              workload, definition(workload), distribution(workload), wallSeconds, threads,
                              count, count / wallSeconds, pointReads.sum(), writes.sum(), scans.sum(), misses.sum(),
                              micros(all, 50), micros(all, 95), micros(all, 99),
                              micros(points, 50), micros(points, 95), micros(points, 99),
                              micros(range, 50), micros(range, 95), micros(range, 99),
                              device, diskReadBytes, diskWriteBytes);
        }
    }

    private static double micros(Histogram histogram, double percentile)
    {
        return histogram.getTotalCount() == 0 ? 0 : histogram.getValueAtPercentile(percentile) / 1000.0;
    }

    private static byte[] valuePool(SplittableRandom random)
    {
        byte[] pool = new byte[1 << 20];
        for (int i = 0; i < pool.length; i++)
            pool[i] = (byte) random.nextInt(256);
        return pool;
    }

    private static ByteBuffer value(byte[] pool, long random, int size)
    {
        int start = (int) Long.remainderUnsigned(random, pool.length - size + 1);
        return ByteBuffer.wrap(pool, start, size).slice();
    }

    private static byte[] copyValue(byte[] pool, long random, int size)
    {
        ByteBuffer source = value(pool, random, size);
        byte[] result = new byte[size];
        source.get(result);
        return result;
    }

    private static long fnv(long key)
    {
        long hash = FNV_OFFSET;
        for (int i = 0; i < Long.BYTES; i++)
        {
            hash *= FNV_PRIME;
            hash ^= key & 0xff;
            key >>>= 8;
        }
        return hash;
    }

    private static long pareto(long random, double k, double sigma)
    {
        double u = ((random >>> 11) + 1.0) / (0x1.0p53 + 1.0);
        double value = Math.ceil(sigma * (Math.pow(u, -k) - 1) / k);
        return value >= Long.MAX_VALUE ? Long.MAX_VALUE : (long) value;
    }

    private static String identifier(String value)
    {
        if (!value.matches("[A-Za-z][A-Za-z0-9_]*"))
            throw new IllegalArgumentException("invalid CQL identifier: " + value);
        return value;
    }

    private static String definition(String workload)
    {
        switch (workload)
        {
            case "A": return "50% read / 50% update";
            case "B": return "95% read / 5% update";
            case "C": return "100% read";
            case "D": return "95% read / 5% insert";
            case "E": return "95% scan / 5% insert";
            case "F": return "50% read / 50% read-modify-write";
            case "MIXGRAPH": return "83% get / 14% put / 3% seek";
            default: throw new IllegalArgumentException("workload must be A-F or MIXGRAPH: " + workload);
        }
    }

    private static String distribution(String workload)
    {
        if ("D".equals(workload)) return "latest theta=0.99";
        if ("MIXGRAPH".equals(workload)) return "MixGraph power + two-term-exponential key ranges";
        return "scrambled Zipfian theta=0.99";
    }

    private static final class Zipf
    {
        private final long minimum;
        private final long maximum;
        private final double theta;
        private final double zetaN;
        private final double zeta2;
        private final double alpha;
        private final double eta;
        private final double halfPowTheta;

        private Zipf(long minimum, long maximum, double zetaN)
        {
            this(minimum, maximum, ZIPF_THETA, zetaN);
        }

        private Zipf(long minimum, long maximum, double theta, double zetaN)
        {
            this.minimum = minimum;
            this.maximum = maximum;
            this.theta = theta;
            this.zetaN = zetaN;
            this.zeta2 = 1 + Math.pow(0.5, theta);
            this.alpha = 1 / (1 - theta);
            this.eta = (1 - Math.pow(2.0 / (maximum + 1.0 - minimum), 1 - theta)) / (1 - zeta2 / zetaN);
            this.halfPowTheta = 1 + Math.pow(0.5, theta);
        }

        private static Zipf forMaximum(long maximum, double theta)
        {
            double zeta = 0;
            for (long i = 1; i <= maximum + 1; i++)
                zeta += 1 / Math.pow(i, theta);
            return new Zipf(0, maximum, theta, zeta);
        }

        private long next(SplittableRandom random)
        {
            double u = random.nextDouble();
            double uz = u * zetaN;
            if (uz < 1) return minimum;
            if (uz < halfPowTheta) return minimum + 1;
            double spread = maximum + 1.0 - minimum;
            long value = minimum + (long) (spread * Math.pow(eta * u - eta + 1, alpha));
            return Math.min(maximum, value);
        }
    }

    private static final class MixGraph
    {
        private final double[] cumulative = new double[30];
        private final long keySpace;
        private final long rangeSize;

        private MixGraph(long keySpace)
        {
            this.keySpace = keySpace;
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
            double running = 0;
            for (int i = 0; i < cumulative.length; i++)
            {
                running += weights.get(i) / sum;
                cumulative[i] = running;
            }
            cumulative[cumulative.length - 1] = 1;
            rangeSize = Math.max(1, keySpace / cumulative.length);
        }

        private long key(long random)
        {
            double u = ((random >>> 11) + 1.0) / (0x1.0p53 + 1.0);
            int range = 0;
            while (range < cumulative.length - 1 && u >= cumulative[range]) range++;
            double within = Long.remainderUnsigned(random, rangeSize) / (double) rangeSize;
            long seed = (long) Math.ceil(Math.pow(within / 0.002312, 1 / 0.3467));
            long offset = Long.remainderUnsigned(splitMix64(seed), rangeSize);
            long result = range * rangeSize + offset;
            return Math.min(keySpace - 1, result);
        }
    }

    private static long splitMix64(long value)
    {
        value += 0x9e3779b97f4a7c15L;
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    private static final class DiskCounters
    {
        private final long readBytes;
        private final long writeBytes;

        private DiskCounters(long readBytes, long writeBytes)
        {
            this.readBytes = readBytes;
            this.writeBytes = writeBytes;
        }
    }

    private static DiskCounters diskCounters(String device) throws Exception
    {
        try (BufferedReader reader = Files.newBufferedReader(Paths.get("/proc/diskstats")))
        {
            String line;
            while ((line = reader.readLine()) != null)
            {
                String[] fields = line.trim().split("\\s+");
                if (fields.length >= 10 && fields[2].equals(device))
                    return new DiskCounters(Math.multiplyExact(Long.parseLong(fields[5]), 512),
                                            Math.multiplyExact(Long.parseLong(fields[9]), 512));
            }
        }
        throw new IllegalArgumentException("disk device not found: " + device);
    }
}
