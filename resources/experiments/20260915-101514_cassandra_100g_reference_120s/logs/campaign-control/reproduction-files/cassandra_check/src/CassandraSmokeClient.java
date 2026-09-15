import java.io.BufferedWriter;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;

/** Native-protocol helper, avoiding cqlsh's Python-version dependency. */
public final class CassandraSmokeClient {
    private CassandraSmokeClient() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            throw new IllegalArgumentException("usage: CassandraSmokeClient wait|schema|verify ...");
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
                    Row local = session.execute(
                        "SELECT release_version FROM system.local").one();
                    if (local == null) {
                        throw new IllegalStateException("system.local returned no row");
                    }
                    System.out.println(local.getString("release_version"));
                    return;
                case "schema":
                    createSchema(session);
                    return;
                case "verify":
                    if (args.length != 3) {
                        throw new IllegalArgumentException(
                            "usage: CassandraSmokeClient verify <expected.tsv> <actual.tsv>");
                    }
                    verify(session, Paths.get(args[1]), Paths.get(args[2]));
                    return;
                default:
                    throw new IllegalArgumentException("unknown action: " + args[0]);
            }
        }
    }

    private static void createSchema(Session session) {
        session.execute(
            "CREATE KEYSPACE IF NOT EXISTS vcomp_smoke "
            + "WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1}");
        session.execute(
            "CREATE TABLE IF NOT EXISTS vcomp_smoke.kv ("
            + "partition_id text, ck bigint, value blob, "
            + "PRIMARY KEY ((partition_id), ck)) "
            + "WITH CLUSTERING ORDER BY (ck ASC) "
            + "AND compaction = {'class': 'LeveledCompactionStrategy', "
            + "'enabled': 'false', 'sstable_size_in_mb': '1'} "
            + "AND compression = {'enabled': 'false'}");
        session.execute(
            "CREATE TABLE IF NOT EXISTS vcomp_smoke.reconciliation_probe ("
            + "partition_id text, ck bigint, value blob, "
            + "PRIMARY KEY ((partition_id), ck)) "
            + "WITH CLUSTERING ORDER BY (ck ASC)");
        clusterAgreement(session);
        verifySingleColumnReconciliation(session);
    }

    private static void clusterAgreement(Session session) {
        // A query through the newly created table also waits for this one-node
        // schema to become visible to the native-protocol coordinator.
        session.execute("SELECT ck FROM vcomp_smoke.kv WHERE partition_id = 'all' LIMIT 1");
    }

    private static void verifySingleColumnReconciliation(Session session) {
        session.execute(
            "INSERT INTO vcomp_smoke.reconciliation_probe "
            + "(partition_id, ck, value) VALUES ('all', 20, 0x6f6c64) "
            + "USING TIMESTAMP 100000");
        session.execute(
            "INSERT INTO vcomp_smoke.reconciliation_probe "
            + "(partition_id, ck, value) VALUES ('all', 20, 0x6e6577) "
            + "USING TIMESTAMP 200000");
        Row row = session.execute(
            "SELECT value, writetime(value) AS value_ts "
            + "FROM vcomp_smoke.reconciliation_probe "
            + "WHERE partition_id = 'all' AND ck = 20").one();
        ByteBuffer bytes = row.getBytes("value").duplicate();
        byte[] value = new byte[bytes.remaining()];
        bytes.get(value);
        String decoded = new String(value, StandardCharsets.UTF_8);
        if (!"new".equals(decoded) || row.getLong("value_ts") != 200_000L) {
            throw new IllegalStateException(
                "single-column timestamp reconciliation failed: value=" + decoded
                + ", timestamp=" + row.getLong("value_ts"));
        }
        System.out.println(
            "PASS: one regular column reconciles to the newest Cassandra timestamp");
    }

    private static void verify(Session session, Path expectedPath, Path actualPath)
            throws Exception {
        List<String> expected = Files.readAllLines(expectedPath, StandardCharsets.UTF_8);
        List<String> actual = new ArrayList<>();
        ResultSet result = session.execute(
            "SELECT ck, value FROM vcomp_smoke.kv "
            + "WHERE partition_id = 'all' ORDER BY ck ASC");
        long previousKey = Long.MIN_VALUE;
        for (Row row : result) {
            long key = row.getLong("ck");
            if (!actual.isEmpty() && key <= previousKey) {
                throw new IllegalStateException("clustering keys are not strictly ascending");
            }
            previousKey = key;
            ByteBuffer bytes = row.getBytes("value").duplicate();
            byte[] valueBytes = new byte[bytes.remaining()];
            bytes.get(valueBytes);
            actual.add(key + "\t" + new String(valueBytes, StandardCharsets.UTF_8));
        }

        try (BufferedWriter writer = Files.newBufferedWriter(actualPath,
                                                              StandardCharsets.UTF_8)) {
            for (String row : actual) {
                writer.write(row);
                writer.newLine();
            }
        }
        if (!actual.equals(expected)) {
            throw new IllegalStateException(
                "Cassandra rows differ from materialized expectation\nexpected="
                + expected + "\nactual=" + actual);
        }
        System.out.printf(
            "PASS: %d rows; ascending clustering keys; newest values retained%n",
            actual.size());
    }
}
