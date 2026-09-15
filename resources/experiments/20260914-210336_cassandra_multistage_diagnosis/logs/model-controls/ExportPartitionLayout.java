import java.nio.file.*;
import org.apache.cassandra.db.compaction.vcomp.VCompOrderedPartitionLayout;
public final class ExportPartitionLayout {
 public static void main(String[] args) throws Exception {
  VCompOrderedPartitionLayout l=new VCompOrderedPartitionLayout(104857600L,10000);
  StringBuilder b=new StringBuilder("ordinal\ttoken\tkey\tminimum\tmaximum\n");
  for(VCompOrderedPartitionLayout.Partition p:l.partitions()) b.append(p.ordinal()).append('\t').append(p.token()).append('\t').append(p.key()).append('\t').append(p.minimum()).append('\t').append(p.maximum()).append('\n');
  Files.writeString(Path.of(args[0]),b.toString());
 }
}
