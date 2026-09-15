import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.SplittableRandom;
import java.util.concurrent.ExecutionException;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSetFuture;
import com.datastax.driver.core.Session;

import org.apache.cassandra.db.compaction.vcomp.DeterministicFixedWidthKeyCodec;
import org.apache.cassandra.db.compaction.vcomp.VCompCqlSstableMaterializer;
import org.apache.cassandra.db.compaction.vcomp.VCompOrderedPartitionLayout;

/**
 * Writes the same synthetic stream as VCompBulkLoad through Cassandra's normal
 * native CQL write path. Explicit 64-MiB flush boundaries make the baseline's
 * initial SST cadence match the VComp descriptor flush cadence.
 */
public final class CassandraBaselineLoad
{
    private CassandraBaselineLoad()
    {
    }

    public static void main(String[] arguments) throws Exception
    {
        if (arguments.length != 11)
            throw new IllegalArgumentException("usage: CassandraBaselineLoad <nodetool> <keyspace> <table> "
                                               + "<writes> <key-bytes> <value-bytes> <flush-bytes> <target-sstable-size-or-default> "
                                               + "<partition-count> <seed> <inflight>");

        String nodetool = arguments[0];
        String keyspace = arguments[1];
        String table = arguments[2];
        long writes = Long.parseLong(arguments[3]);
        int keyBytes = Integer.parseInt(arguments[4]);
        int valueBytes = Integer.parseInt(arguments[5]);
        long flushBytes = Long.parseLong(arguments[6]);
        String targetSSTableSize = arguments[7];
        int partitionCount = Integer.parseInt(arguments[8]);
        long seed = Long.parseLong(arguments[9]);
        int maxInflight = Integer.parseInt(arguments[10]);
        if (writes <= 0 || valueBytes <= 0 || flushBytes <= 0 || maxInflight <= 0)
            throw new IllegalArgumentException("writes, value bytes, flush bytes, and inflight must be positive");
        int entryBytes = Math.addExact(keyBytes, valueBytes);
        int writesPerFlush = Math.toIntExact(flushBytes / entryBytes);
        if (writesPerFlush <= 0)
            throw new IllegalArgumentException("flush bytes must hold one entry");

        DeterministicFixedWidthKeyCodec keyCodec = new DeterministicFixedWidthKeyCodec(keyBytes);
        VCompOrderedPartitionLayout partitionLayout = new VCompOrderedPartitionLayout(writes, partitionCount);
        try (Cluster cluster = Cluster.builder()
                                            .addContactPoint("127.0.0.1")
                                            .withPort(9042)
                                            .withoutJMXReporting()
                                            .withoutMetrics()
                                            .build();
             Session session = cluster.connect())
        {
            createBaselineSchema(session, keyspace, table, targetSSTableSize);
            PreparedStatement insert = session.prepare("INSERT INTO " + keyspace + '.' + table
                                                       + " (partition_id, ck, value) VALUES (?, ?, ?) USING TIMESTAMP ?");
            SplittableRandom random = new SplittableRandom(seed);
            ArrayDeque<ResultSetFuture> pending = new ArrayDeque<>(maxInflight);
            long startNanos = System.nanoTime();
            long completed = 0;
            int flushes = 0;

            for (long ordinal = 0; ordinal < writes; ordinal++)
            {
                long key = random.nextLong(writes);
                String partitionKey = partitionLayout.partitionFor(key).key();
                ByteBuffer value = ByteBuffer.wrap(VCompCqlSstableMaterializer.valueFor(key, valueBytes));
                BoundStatement statement = insert.bind(partitionKey, keyCodec.decode(key), value, ordinal + 1);
                pending.addLast(session.executeAsync(statement));
                if (pending.size() >= maxInflight)
                    waitFor(pending.removeFirst());

                completed = ordinal + 1;
                if (completed % writesPerFlush == 0 || completed == writes)
                {
                    drain(pending);
                    runNodetool(nodetool, "flush", keyspace, table);
                    flushes++;
                    System.out.printf("BASELINE_PROGRESS writes=%d flushes=%d%n", completed, flushes);
                }
            }
            drain(pending);
            double seconds = (System.nanoTime() - startNanos) / 1_000_000_000.0;
            System.out.printf("BASELINE_RESULT seconds=%.3f writes=%d explicit_flushes=%d writes_per_flush=%d%n",
                              seconds, writes, flushes, writesPerFlush);
        }
    }

    private static void createBaselineSchema(Session session,
                                             String keyspace,
                                             String table,
                                             String targetSSTableSize)
    {
        String targetOption = "default".equals(targetSSTableSize)
                              ? ""
                              : ", 'target_sstable_size': '" + requireSSTableSize(targetSSTableSize) + "'";
        session.execute("CREATE KEYSPACE " + keyspace
                        + " WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1}"
                        + " AND durable_writes = false");
        session.execute("CREATE TABLE " + keyspace + '.' + table + " ("
                        + "partition_id text, ck blob, value blob, "
                        + "PRIMARY KEY ((partition_id), ck)) "
                        + "WITH CLUSTERING ORDER BY (ck ASC) "
                        + "AND compaction = {'class': 'UnifiedCompactionStrategy', "
                        + "'scaling_parameters': 'T4'" + targetOption + ", "
                        + "'base_shard_count': '1', 'sstable_growth': '0.333'} "
                        + "AND compression = {'enabled': 'false'}");
        session.execute("SELECT ck FROM " + keyspace + '.' + table
                        + " WHERE partition_id = 'all' LIMIT 1");
    }

    private static String requireSSTableSize(String value)
    {
        if (!value.matches("[1-9][0-9]*(MiB|GiB)"))
            throw new IllegalArgumentException("target sstable size must be default, or a positive MiB/GiB value");
        return value;
    }

    private static void drain(ArrayDeque<ResultSetFuture> pending) throws InterruptedException, ExecutionException
    {
        while (!pending.isEmpty())
            waitFor(pending.removeFirst());
    }

    private static void waitFor(ResultSetFuture future) throws InterruptedException, ExecutionException
    {
        future.get();
    }

    private static void runNodetool(String nodetool, String... arguments) throws IOException, InterruptedException
    {
        String[] command = new String[arguments.length + 1];
        command[0] = nodetool;
        System.arraycopy(arguments, 0, command, 1, arguments.length);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        byte[] output = process.getInputStream().readAllBytes();
        if (process.waitFor() != 0)
            throw new IOException("nodetool failed: " + new String(output));
    }
}
