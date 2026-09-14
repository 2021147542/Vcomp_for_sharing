import javax.management.MBeanServerConnection;
import javax.management.ObjectName;

import org.apache.cassandra.tools.NodeProbe;

/** Read-only native cache and memtable diagnostics, collected outside client timing. */
public final class CassandraRuntimeMetrics
{
    public static void main(String[] args) throws Exception
    {
        if (args.length != 1) throw new IllegalArgumentException("usage: CassandraRuntimeMetrics <chunk-cache-bytes>");
        long expected = Long.parseLong(args[0]);
        try (NodeProbe probe = new NodeProbe("127.0.0.1", 7199))
        {
            long capacity = ((Number) probe.getCacheMetric("ChunkCache", "Capacity")).longValue();
            if (capacity != expected)
                throw new IllegalStateException("Chunk cache capacity " + capacity + " differs from " + expected);
            System.out.println("chunk_cache_capacity_bytes=" + capacity);
            for (String metric : new String[] { "Size", "Hits", "Misses", "Requests" })
                System.out.println("chunk_cache_" + metric.toLowerCase(java.util.Locale.ROOT) + "="
                                   + ((Number) probe.getCacheMetric("ChunkCache", metric)).longValue());
            MBeanServerConnection server = probe.getMbeanServerConn();
            System.out.println("memtable_blocked_allocation_count=" + server.getAttribute(
                               new ObjectName("org.apache.cassandra.metrics:type=MemtablePool,name=BlockedOnAllocation"), "Count"));
            System.out.println("memtable_pending_flush_tasks=" + server.getAttribute(
                               new ObjectName("org.apache.cassandra.metrics:type=MemtablePool,name=PendingFlushTasks"), "Value"));
        }
    }
}
