import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Map;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.SimpleStatement;

import org.apache.cassandra.db.compaction.vcomp.DeterministicFixedWidthKeyCodec;
import org.apache.cassandra.db.compaction.vcomp.VCompOrderedPartitionLayout;

/** Native-protocol setup and verification client for the Cassandra 5 VComp pipeline. */
public final class CassandraVCompPipelineClient {
    private CassandraVCompPipelineClient() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            throw new IllegalArgumentException(
                    "usage: wait|schema|enable-compaction|assert-compaction-enabled|verify|fingerprint ...");
        }
        try (Cluster cluster = Cluster.builder()
                .addContactPoint("127.0.0.1")
                .withPort(9042)
                .withoutJMXReporting()
                .withoutMetrics()
                .build();
             Session session = cluster.connect()) {
            switch (args[0]) {
                case "wait":
                    if (session.execute("SELECT release_version FROM system.local").one() == null) {
                        throw new IllegalStateException("system.local returned no row");
                    }
                    return;
                case "schema":
                    requireArgs(args, 7);
                    createSchema(session, args[1], args[2], Integer.parseInt(args[3]),
                                 Long.parseLong(args[4]), Integer.parseInt(args[5]),
                                 Long.parseLong(args[6]));
                    return;
                case "verify":
                    requireArgs(args, 8);
                    verify(session,
                           args[1],
                           args[2],
                           Integer.parseInt(args[3]),
                           Integer.parseInt(args[4]),
                           Integer.parseInt(args[5]),
                           Long.parseLong(args[6]), Integer.parseInt(args[7]));
                    return;
                case "enable-compaction":
                    requireArgs(args, 4);
                    enableCompaction(session, args[1], args[2], Long.parseLong(args[3]));
                    return;
                case "assert-compaction-enabled":
                    requireArgs(args, 3);
                    assertCompactionEnabled(session, args[1], args[2]);
                    return;
                case "fingerprint":
                    requireArgs(args, 7);
                    fingerprint(session, args[1], args[2],
                                Integer.parseInt(args[3]), Integer.parseInt(args[4]),
                                Long.parseLong(args[5]), Integer.parseInt(args[6]));
                    return;
                default:
                    throw new IllegalArgumentException("unknown action: " + args[0]);
            }
        }
    }

    private static void createSchema(Session session, String keyspace, String table, int keyBytes,
                                     long keySpace, int partitionCount, long targetSSTableBytes) {
        new DeterministicFixedWidthKeyCodec(keyBytes);
        VCompOrderedPartitionLayout layout = new VCompOrderedPartitionLayout(keySpace, partitionCount);
        session.execute("CREATE KEYSPACE IF NOT EXISTS " + keyspace
                        + " WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1}"
                        + " AND durable_writes = false");
        session.execute("CREATE TABLE IF NOT EXISTS " + keyspace + '.' + table + " ("
                        + "partition_id text, ck blob, value blob, "
                        + "PRIMARY KEY ((partition_id), ck)) "
                        + "WITH CLUSTERING ORDER BY (ck ASC) "
                        + "AND compaction = {'class': 'UnifiedCompactionStrategy', "
                        + "'enabled': 'false', 'scaling_parameters': 'T4', "
                        + "'target_sstable_size': '" + targetSSTableBytes + "B', "
                        + "'base_shard_count': '1', 'sstable_growth': '0.333'} "
                        + "AND compression = {'enabled': 'false'}");
        session.execute("SELECT ck FROM " + keyspace + '.' + table
                        + " WHERE partition_id = '" + layout.partitions().get(0).key() + "' LIMIT 1");
    }

    /**
     * Persistently return the imported table to normal UCS behavior. The
     * schema is disabled only while the final VComp SSTables are installed,
     * preventing Cassandra from racing the installer with background work.
     */
    private static void enableCompaction(Session session, String keyspace, String table,
                                         long targetSSTableBytes) {
        requireIdentifier(keyspace, "keyspace");
        requireIdentifier(table, "table");
        session.execute("ALTER TABLE " + keyspace + '.' + table
                        + " WITH compaction = {'class': 'UnifiedCompactionStrategy', "
                        + "'enabled': 'true', 'scaling_parameters': 'T4', "
                        + "'target_sstable_size': '" + targetSSTableBytes + "B', "
                        + "'base_shard_count': '1', 'sstable_growth': '0.333'}");
        assertCompactionEnabled(session, keyspace, table);
    }

    private static void assertCompactionEnabled(Session session, String keyspace, String table) {
        requireIdentifier(keyspace, "keyspace");
        requireIdentifier(table, "table");
        Row row = session.execute("SELECT compaction FROM system_schema.tables"
                                  + " WHERE keyspace_name = '" + keyspace + "'"
                                  + " AND table_name = '" + table + "'").one();
        if (row == null) {
            throw new IllegalStateException("table schema not found: " + keyspace + '.' + table);
        }
        Map<String, String> options = row.getMap("compaction", String.class, String.class);
        if (!compactionEnabled(options)) {
            throw new IllegalStateException("automatic compaction is not enabled: " + options);
        }
        String strategy = options.get("class");
        if (strategy == null || !strategy.endsWith("UnifiedCompactionStrategy")) {
            throw new IllegalStateException("table is not using UCS: " + options);
        }
        System.out.printf("PASS: automatic UCS compaction enabled for %s.%s%n", keyspace, table);
    }

    static boolean compactionEnabled(Map<String, String> options) {
        // CompactionParams.DEFAULT_ENABLED is true. Native CREATE TABLE may
        // omit the option entirely; an absent option is not a disabled table.
        return options != null && Boolean.parseBoolean(options.getOrDefault("enabled", "true"));
    }

    private static void verify(Session session,
                               String keyspace,
                               String table,
                               int keyBytes,
                               int valueBytes,
                               int limit,
                               long keySpace,
                               int partitionCount) {
        int rows = 0;
        long previous = Long.MIN_VALUE;
        DeterministicFixedWidthKeyCodec keyCodec = new DeterministicFixedWidthKeyCodec(keyBytes);
        VCompOrderedPartitionLayout layout = new VCompOrderedPartitionLayout(keySpace, partitionCount);
        outer:
        for (VCompOrderedPartitionLayout.Partition partition : layout.partitions()) {
            ResultSet result = session.execute("SELECT ck, value FROM " + keyspace + '.' + table
                                               + " WHERE partition_id = '" + partition.key() + "' LIMIT " + limit);
            for (Row row : result) {
                long key = keyCodec.encode(row.getBytes("ck"));
                if (!partition.contains(key)) {
                    throw new IllegalStateException("key " + key + " is stored in the wrong partition");
                }
                if (rows > 0 && key <= previous) {
                    throw new IllegalStateException("keys are not strictly ascending");
                }
                previous = key;
                ByteBuffer buffer = row.getBytes("value").duplicate();
                byte[] actual = new byte[buffer.remaining()];
                buffer.get(actual);
                byte[] expected = valueFor(key, valueBytes);
                if (!Arrays.equals(actual, expected)) {
                    throw new IllegalStateException("value mismatch for key " + key);
                }
                rows++;
                if (rows >= limit)
                    break outer;
            }
        }
        if (rows == 0) {
            throw new IllegalStateException("materialized table returned no rows");
        }
        System.out.printf("PASS: verified %d ascending rows and deterministic values%n", rows);
    }

    /**
     * Canonical full-table digest for the restricted one-partition schema.
     * CQL returns clustering rows in their declared ascending order, so the
     * digest checks both row count and every visible (ck, value) pair.
     */
    private static void fingerprint(Session session,
                                    String keyspace,
                                    String table,
                                    int keyBytes,
                                    int valueBytes,
                                    long keySpace,
                                    int partitionCount) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        DeterministicFixedWidthKeyCodec keyCodec = new DeterministicFixedWidthKeyCodec(keyBytes);
        long rows = 0;
        long previous = Long.MIN_VALUE;
        VCompOrderedPartitionLayout layout = new VCompOrderedPartitionLayout(keySpace, partitionCount);
        for (VCompOrderedPartitionLayout.Partition partition : layout.partitions()) {
            SimpleStatement query = new SimpleStatement("SELECT ck, value FROM " + keyspace + '.' + table
                                                         + " WHERE partition_id = '" + partition.key() + "'");
            query.setFetchSize(4096);
            for (Row row : session.execute(query)) {
                ByteBuffer encodedKey = row.getBytes("ck");
                ByteBuffer encodedValue = row.getBytes("value");
                long key = keyCodec.encode(encodedKey);
                if (!partition.contains(key))
                    throw new IllegalStateException("key " + key + " is stored in the wrong partition");
                if (rows > 0 && key <= previous)
                    throw new IllegalStateException("full-table scan returned unordered or duplicate key " + key);
                previous = key;
                if (!matchesValue(key, encodedValue, valueBytes))
                    throw new IllegalStateException("value mismatch for key " + key + " during full-table verification");
                updateLengthDelimited(digest, encodedKey);
                updateLengthDelimited(digest, encodedValue);
                rows++;
            }
        }
        System.out.printf("FINGERPRINT rows=%d sha256=%s values_verified=true%n", rows, hex(digest.digest()));
    }

    private static boolean matchesValue(long key, ByteBuffer encoded, int expectedBytes) {
        ByteBuffer actual = encoded.duplicate();
        if (actual.remaining() != expectedBytes)
            return false;
        long state = key;
        int offset = 0;
        while (offset < expectedBytes) {
            state += 0x9e3779b97f4a7c15L;
            long mixed = state;
            mixed = (mixed ^ (mixed >>> 30)) * 0xbf58476d1ce4e5b9L;
            mixed = (mixed ^ (mixed >>> 27)) * 0x94d049bb133111ebL;
            mixed ^= mixed >>> 31;
            for (int byteIndex = 0; byteIndex < Long.BYTES && offset < expectedBytes; byteIndex++, offset++)
                if (actual.get() != (byte) (mixed >>> (byteIndex * Byte.SIZE)))
                    return false;
        }
        return true;
    }

    private static void updateLengthDelimited(MessageDigest digest, ByteBuffer buffer) {
        ByteBuffer copy = buffer.duplicate();
        int length = copy.remaining();
        digest.update((byte) (length >>> 24));
        digest.update((byte) (length >>> 16));
        digest.update((byte) (length >>> 8));
        digest.update((byte) length);
        byte[] chunk = new byte[Math.min(length, 8192)];
        while (copy.hasRemaining()) {
            int count = Math.min(copy.remaining(), chunk.length);
            copy.get(chunk, 0, count);
            digest.update(chunk, 0, count);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes)
            result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }

    private static byte[] valueFor(long key, int size) {
        byte[] value = new byte[size];
        long state = key;
        for (int offset = 0; offset < size; offset += Long.BYTES) {
            state += 0x9e3779b97f4a7c15L;
            long mixed = state;
            mixed = (mixed ^ (mixed >>> 30)) * 0xbf58476d1ce4e5b9L;
            mixed = (mixed ^ (mixed >>> 27)) * 0x94d049bb133111ebL;
            mixed ^= mixed >>> 31;
            for (int byteIndex = 0;
                 byteIndex < Long.BYTES && offset + byteIndex < size;
                 byteIndex++) {
                value[offset + byteIndex] = (byte) (mixed >>> (byteIndex * Byte.SIZE));
            }
        }
        return value;
    }

    private static void requireArgs(String[] args, int count) {
        if (args.length != count) {
            throw new IllegalArgumentException("wrong number of arguments for " + args[0]);
        }
    }

    private static void requireIdentifier(String identifier, String label) {
        if (!identifier.matches("[A-Za-z][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("invalid " + label + " identifier: " + identifier);
        }
    }
}
