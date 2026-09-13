import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.cassandra.dht.Murmur3Partitioner;
import org.apache.cassandra.io.sstable.CQLSSTableWriter;

/**
 * A deliberately small VComp-to-Cassandra integration smoke test.
 *
 * It exercises the boundary that matters for a first port:
 *   virtual descriptors -> model merge/dedup -> output split -> Cassandra SSTables.
 * It is not a Cassandra CompactionStrategy implementation and is not a benchmark.
 */
public final class CassandraVCompSmoke {
    private static final String PARTITION_ID = "all";
    private static final int TARGET_KEYS_PER_OUTPUT = 3;

    private static final String TABLE_SCHEMA =
        "CREATE TABLE vcomp_smoke.kv ("
        + "partition_id text, "
        + "ck bigint, "
        + "value blob, "
        + "PRIMARY KEY ((partition_id), ck)) "
        + "WITH CLUSTERING ORDER BY (ck ASC) "
        + "AND compaction = {'class': 'LeveledCompactionStrategy', "
        + "'enabled': 'false', 'sstable_size_in_mb': '1'} "
        + "AND compression = {'enabled': 'false'}";

    private static final String INSERT =
        "INSERT INTO vcomp_smoke.kv (partition_id, ck, value) "
        + "VALUES (?, ?, ?) USING TIMESTAMP ?";

    private CassandraVCompSmoke() {}

    private static final class VersionedValue {
        final long timestampMicros;
        final byte[] value;

        VersionedValue(long timestampMicros, byte[] value) {
            this.timestampMicros = timestampMicros;
            this.value = value;
        }
    }

    private static final class Segment {
        final long keyStart;
        final long keyEnd;
        final int rankStart;
        final int rankEnd;
        final double slope;
        final double intercept;

        Segment(long keyStart, long keyEnd, int rankStart, int rankEnd,
                double slope, double intercept) {
            this.keyStart = keyStart;
            this.keyEnd = keyEnd;
            this.rankStart = rankStart;
            this.rankEnd = rankEnd;
            this.slope = slope;
            this.intercept = intercept;
        }

        long keyAtRank(int rank) {
            if (rankStart == rankEnd || slope == 0.0) {
                return keyStart;
            }
            long key = Math.round((rank - intercept) / slope);
            return Math.max(keyStart, Math.min(keyEnd, key));
        }
    }

    private static final class Model {
        final int keyCount;
        final List<Segment> segments;

        Model(int keyCount, List<Segment> segments) {
            this.keyCount = keyCount;
            this.segments = segments;
        }

        List<Long> materializeKeys() {
            List<Long> result = new ArrayList<>(keyCount);
            for (Segment segment : segments) {
                for (int rank = segment.rankStart; rank <= segment.rankEnd; rank++) {
                    result.add(segment.keyAtRank(rank));
                }
            }
            if (result.size() != keyCount) {
                throw new IllegalStateException(
                    "model produced " + result.size() + " keys, expected " + keyCount);
            }
            return result;
        }
    }

    private static final class VirtualSST {
        final String name;
        final Model model;
        final long timestampMicros;

        VirtualSST(String name, long[] sortedKeys, long timestampMicros) {
            this.name = name;
            this.model = greedyFit(sortedKeys, 0.0);
            this.timestampMicros = timestampMicros;

            List<Long> reconstructed = model.materializeKeys();
            for (int i = 0; i < sortedKeys.length; i++) {
                if (reconstructed.get(i) != sortedKeys[i]) {
                    throw new IllegalStateException(
                        name + " model reconstruction differs at rank " + i);
                }
            }
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException(
                "usage: CassandraVCompSmoke <sstable-output-dir> <expected-tsv>");
        }
        Path sstableDirectory = Paths.get(args[0]).toAbsolutePath();
        Path expectedTsv = Paths.get(args[1]).toAbsolutePath();
        Files.createDirectories(sstableDirectory);

        // Each descriptor represents one immutable input SST.  All rows use the
        // same Cassandra partition key; ck is therefore the one-dimensional key.
        List<VirtualSST> inputs = Arrays.asList(
            new VirtualSST("input-A", new long[] {10, 20, 30, 50, 70}, 100_000L),
            new VirtualSST("input-B", new long[] {20, 40, 50, 60, 80}, 200_000L)
        );

        TreeMap<Long, VersionedValue> merged = mergeNewest(inputs);
        List<VirtualSST> outputs = splitOutputs(merged);
        assertOutputCoverage(outputs, merged);
        writeExpected(expectedTsv, merged);
        materializeSSTables(sstableDirectory, outputs, merged);

        System.out.printf("input virtual SSTs: %d%n", inputs.size());
        System.out.printf("merged distinct clustering keys: %d%n", merged.size());
        System.out.printf("output virtual SSTs: %d (target %d keys/output)%n",
                          outputs.size(), TARGET_KEYS_PER_OUTPUT);
        for (VirtualSST output : outputs) {
            List<Long> keys = output.model.materializeKeys();
            System.out.printf("  %s: [%d, %d], keys=%d, segments=%d%n",
                              output.name, keys.get(0), keys.get(keys.size() - 1),
                              keys.size(), output.model.segments.size());
        }
        System.out.println("materialized Cassandra SSTables: " + sstableDirectory);
    }

    /** Greedy piecewise-linear fit of rank = slope * key + intercept. */
    private static Model greedyFit(long[] sortedKeys, double errorBound) {
        if (sortedKeys.length == 0) {
            return new Model(0, new ArrayList<Segment>());
        }
        for (int i = 1; i < sortedKeys.length; i++) {
            if (sortedKeys[i] <= sortedKeys[i - 1]) {
                throw new IllegalArgumentException("keys must be strictly increasing");
            }
        }

        List<Segment> segments = new ArrayList<>();
        int segmentStart = 0;
        while (segmentStart < sortedKeys.length) {
            if (segmentStart == sortedKeys.length - 1) {
                segments.add(new Segment(sortedKeys[segmentStart], sortedKeys[segmentStart],
                                         segmentStart, segmentStart, 0.0, segmentStart));
                break;
            }

            double x0 = sortedKeys[segmentStart];
            double y0 = segmentStart;
            double slopeLow = Double.NEGATIVE_INFINITY;
            double slopeHigh = Double.POSITIVE_INFINITY;
            int segmentEnd = segmentStart;

            for (int i = segmentStart + 1; i < sortedKeys.length; i++) {
                double dx = sortedKeys[i] - x0;
                double dy = i - y0;
                double newLow = (dy - errorBound) / dx;
                double newHigh = (dy + errorBound) / dx;
                if (newLow > slopeHigh || newHigh < slopeLow) {
                    break;
                }
                slopeLow = Math.max(slopeLow, newLow);
                slopeHigh = Math.min(slopeHigh, newHigh);
                segmentEnd = i;
            }

            double slope;
            if (Double.isInfinite(slopeLow) && Double.isInfinite(slopeHigh)) {
                slope = 0.0;
            } else if (Double.isInfinite(slopeLow)) {
                slope = slopeHigh;
            } else if (Double.isInfinite(slopeHigh)) {
                slope = slopeLow;
            } else {
                slope = (slopeLow + slopeHigh) / 2.0;
            }
            segments.add(new Segment(
                sortedKeys[segmentStart], sortedKeys[segmentEnd],
                segmentStart, segmentEnd, slope, y0 - slope * x0));
            segmentStart = segmentEnd + 1;
        }
        return new Model(sortedKeys.length, segments);
    }

    private static TreeMap<Long, VersionedValue> mergeNewest(List<VirtualSST> inputs) {
        TreeMap<Long, VersionedValue> merged = new TreeMap<>();
        for (VirtualSST input : inputs) {
            for (long key : input.model.materializeKeys()) {
                VersionedValue candidate = new VersionedValue(
                    input.timestampMicros, valueFor(key, input.timestampMicros));
                VersionedValue previous = merged.get(key);
                if (previous == null || candidate.timestampMicros > previous.timestampMicros) {
                    merged.put(key, candidate);
                }
            }
        }
        return merged;
    }

    private static List<VirtualSST> splitOutputs(TreeMap<Long, VersionedValue> merged) {
        List<Long> allKeys = new ArrayList<>(merged.keySet());
        List<VirtualSST> outputs = new ArrayList<>();
        for (int start = 0, outputNumber = 0; start < allKeys.size();
             start += TARGET_KEYS_PER_OUTPUT, outputNumber++) {
            int end = Math.min(start + TARGET_KEYS_PER_OUTPUT, allKeys.size());
            long[] keys = new long[end - start];
            for (int i = start; i < end; i++) {
                keys[i - start] = allKeys.get(i);
            }
            // Output rows may retain different per-cell timestamps, so the
            // descriptor timestamp is not used during final materialization.
            outputs.add(new VirtualSST("output-" + outputNumber, keys, 0L));
        }
        return outputs;
    }

    private static void assertOutputCoverage(List<VirtualSST> outputs,
                                             TreeMap<Long, VersionedValue> merged) {
        List<Long> reconstructed = new ArrayList<>();
        for (VirtualSST output : outputs) {
            reconstructed.addAll(output.model.materializeKeys());
        }
        if (!reconstructed.equals(new ArrayList<>(merged.keySet()))) {
            throw new IllegalStateException("split output descriptors changed the merged key set");
        }
    }

    private static byte[] valueFor(long key, long timestampMicros) {
        return ("value(key=" + key + ",ts=" + timestampMicros + ")")
            .getBytes(StandardCharsets.UTF_8);
    }

    private static void writeExpected(Path output,
                                      TreeMap<Long, VersionedValue> merged) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            for (Map.Entry<Long, VersionedValue> entry : merged.entrySet()) {
                writer.write(Long.toString(entry.getKey()));
                writer.write('\t');
                writer.write(new String(entry.getValue().value, StandardCharsets.UTF_8));
                writer.newLine();
            }
        }
    }

    private static void materializeSSTables(Path directory,
                                            List<VirtualSST> outputs,
                                            TreeMap<Long, VersionedValue> merged) throws Exception {
        for (VirtualSST output : outputs) {
            Path outputDirectory = directory.resolve(output.name);
            Files.createDirectories(outputDirectory);
            try (CQLSSTableWriter writer = CQLSSTableWriter.builder()
                    .inDirectory(outputDirectory.toString())
                    .forTable(TABLE_SCHEMA)
                    .using(INSERT)
                    .withPartitioner(Murmur3Partitioner.instance)
                    .withMaxSSTableSizeInMiB(1)
                    .sorted()
                    .build()) {
                for (long key : output.model.materializeKeys()) {
                    VersionedValue row = merged.get(key);
                    writer.addRow(PARTITION_ID, key, ByteBuffer.wrap(row.value),
                                  row.timestampMicros);
                }
            }
        }
    }
}
