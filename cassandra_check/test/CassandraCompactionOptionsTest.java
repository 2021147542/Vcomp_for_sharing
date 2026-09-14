import java.util.HashMap;
import java.util.Map;

/** Regression for a native UCS schema with its default enabled option omitted. */
public final class CassandraCompactionOptionsTest
{
    public static void main(String[] args)
    {
        Map<String, String> options = new HashMap<>();
        options.put("class", "org.apache.cassandra.db.compaction.UnifiedCompactionStrategy");
        options.put("scaling_parameters", "T4");
        if (!CassandraVCompPipelineClient.compactionEnabled(options))
            throw new AssertionError("The native default-enabled UCS schema must pass");
        options.put("enabled", "false");
        if (CassandraVCompPipelineClient.compactionEnabled(options))
            throw new AssertionError("The virtual import stage's disabled schema must fail");
        options.put("enabled", "true");
        if (!CassandraVCompPipelineClient.compactionEnabled(options))
            throw new AssertionError("The re-enabled schema must pass");
        if (CassandraVCompPipelineClient.compactionEnabled(null))
            throw new AssertionError("Missing schema options must fail");
        System.out.println("CassandraCompactionOptionsTest: default/disabled/enabled/missing schemas passed");
    }
}
