import java.nio.file.*;import java.util.*;
import org.apache.cassandra.utils.JsonUtils;
import org.apache.cassandra.db.compaction.unified.*;
public final class FinalTopologyPicker {
 static long n(Map<String,Object> m,String k){return ((Number)m.get(k)).longValue();}
 @SuppressWarnings("unchecked") public static void main(String[]args)throws Exception{
  Map<String,Object>d=JsonUtils.JSON_OBJECT_MAPPER.readValue(Files.readAllBytes(Path.of(args[0])),Map.class);Map<String,Object>arms=(Map<String,Object>)d.get("arms");List<Map<String,Object>>r=new ArrayList<>();
  UnifiedCompactionPicker.CandidateAdapter<Map<String,Object>>adapter=new UnifiedCompactionPicker.CandidateAdapter<Map<String,Object>>(){
   public double density(Map<String,Object>m){return ((Number)m.get("density_bytes_per_ring")).doubleValue();}
   public int compareFirst(Map<String,Object>a,Map<String,Object>b){return Long.compare(n(a,"range_first_partition_ordinal"),n(b,"range_first_partition_ordinal"));}
   public int compareLast(Map<String,Object>a,Map<String,Object>b){return Long.compare(n(a,"range_last_partition_ordinal"),n(b,"range_last_partition_ordinal"));}
   public boolean startsAfter(Map<String,Object>a,Map<String,Object>b){return n(a,"range_first_partition_ordinal")>n(b,"range_last_partition_ordinal");}
   public long maximumTimestamp(Map<String,Object>m){return n(m,"max_timestamp");}
  };
  for(String arm:new String[]{"native","vcomp"})for(int mib:new int[]{65,66}){
   List<Map<String,Object>>files=(List<Map<String,Object>>)((Map<String,Object>)arms.get(arm)).get("files");
   Controller c=Controller.forOfflineTools((long)mib<<20,new int[]{2},Integer.MAX_VALUE,1,64L<<20,.333);
   UnifiedCompactionPicker.Pick<Map<String,Object>>p=UnifiedCompactionPicker.pick(files,adapter,c.pickerPolicy(),c.getBaseSstableSize(c.getFanout(0)));
   Map<String,Object>q=new LinkedHashMap<>();q.put("arm",arm);q.put("flush_mib",mib);q.put("files",files.size());q.put("selected",p!=null);q.put("level",p==null?null:p.level());List<String>selected=new ArrayList<>();if(p!=null)for(Map<String,Object>f:p.inputs())selected.add((String)f.get("file"));q.put("selected_files",selected);r.add(q);
  }
  Map<String,Object>out=new LinkedHashMap<>();out.put("diagnostic_only",true);out.put("scope","Shared production UCS picker on opened final metadata; all files assumed eligible, no TTL/expired/inflight, single-node full-ring, T4; native66MiB inferred and model65MiB recorded; not online task scheduling");out.put("cases",r);
  Files.writeString(Path.of(args[1]),JsonUtils.JSON_OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(out));
 }
}
