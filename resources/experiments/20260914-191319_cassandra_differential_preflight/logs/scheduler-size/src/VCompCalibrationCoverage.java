import java.nio.file.*;
import java.util.*;
import org.apache.cassandra.db.compaction.vcomp.*;
import org.apache.cassandra.utils.JsonUtils;
public class VCompCalibrationCoverage {
 public static void main(String[] args) throws Exception {
  long domain=104857600L,seed=20260909L;int count=65536;
  VCompOrderedPartitionLayout l=new VCompOrderedPartitionLayout(domain,10000);
  SyntheticVCompLoadSource s=new SyntheticVCompLoadSource(count,count,domain,1024,seed);
  VCompPipeline.FlushBatch f=s.flushBatches().iterator().next();
  Set<Integer> occupied=new HashSet<>();
  for(long k:f.keyCoordinates()) occupied.add(l.partitionFor(k).ordinal());
  Map<String,Object> m=new LinkedHashMap<>();m.put("diagnostic_only",true);m.put("seed",seed);m.put("domain",domain);m.put("partition_count",10000);
  m.put("scope","Actual production load-source first 65536 keys and partition layout only; no SST write, no size error measurement");
  m.put("random_first_flush_attempts",count);m.put("random_first_flush_unique",f.keyCoordinates().length);m.put("random_first_flush_occupied_partitions",occupied.size());
  m.put("random_first_flush_scalar_range_partitions",l.partitionCount(f.keyCoordinates()[0],f.keyCoordinates()[f.keyCoordinates().length-1]));
  m.put("calibration_4096_partition_count",l.partitionCount(0,4095));
  m.put("calibration_8192_partition_count",l.partitionCount(16384,24575));
  Files.write(Paths.get(args[0]),JsonUtils.writeAsJsonBytes(m));System.out.println(JsonUtils.writeAsJsonString(m));
 }
}
