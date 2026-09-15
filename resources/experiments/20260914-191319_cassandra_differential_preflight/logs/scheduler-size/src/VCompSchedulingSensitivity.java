import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import org.apache.cassandra.db.compaction.unified.Controller;
import org.apache.cassandra.db.compaction.vcomp.*;
import org.apache.cassandra.utils.JsonUtils;

/** CPU-only intervention; never opens Cassandra or constructs physical SSTables. */
public class VCompSchedulingSensitivity {
    static final long MIB = 1L << 20;
    static final long SEED = 20260909L;
    static final VCompOrderedPartitionLayout LAYOUT = new VCompOrderedPartitionLayout(4096,64);
    static final long[] KEYS = {0,4095};
    static final double COVERAGE = LAYOUT.tokenCoverage(0,4095);
    static VCompPipeline.VirtualSortedRun run(String id, double densityMib, long timestamp) {
        long bytes = Math.round(densityMib * MIB * COVERAGE);
        VCompPipeline.VirtualSSTable s = new VCompPipeline.VirtualSSTable(id,0,4095,2,bytes,timestamp,
            VCompLearnedModel.greedyFit(KEYS,8), VCompKmvSketch.build(KEYS,512),Collections.emptyList());
        return new VCompPipeline.VirtualSortedRun(id,0,Collections.singletonList(s));
    }
    static Map<String,Object> pick(List<VCompPipeline.VirtualSortedRun> rs) {
        Optional<VCompPipeline.VirtualCompactionPlan> p = new VCompUcsPlanner(64*MIB,LAYOUT)
            .pick(new VCompPipeline.VirtualStateSnapshot(rs));
        Map<String,Object> m = new LinkedHashMap<>();
        m.put("eligible_ids", rs.stream().map(VCompPipeline.VirtualSortedRun::id).collect(Collectors.toList()));
        m.put("data_db_bytes",rs.stream().map(r->r.sstables().get(0).estimatedBytes()).collect(Collectors.toList()));
        m.put("input_ids",p.isPresent()?p.get().inputs().stream().map(VCompPipeline.VirtualSortedRun::id).collect(Collectors.toList()):Collections.emptyList());
        m.put("input_level",p.isPresent()?p.get().outputLevel()-1:null);
        return m;
    }
    public static void main(String[] args) throws Exception {
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("schema_version",1);out.put("diagnostic_only",true);out.put("seed",SEED);
        out.put("scope","CPU-only actual production planner/controller; synthetic metadata interventions, not measured size error or native timing replay");
        out.put("coverage",COVERAGE);out.put("flush_size_bytes",64*MIB);
        out.put("density_level_0_upper_mib",64*(1.0-0.9/4)*4);
        List<Object> sizeCases = new ArrayList<>();
        for(double factor:new double[]{0.9,1.0,1.1}) {
            List<VCompPipeline.VirtualSortedRun> runs=new ArrayList<>();
            for(int i=1;i<=4;i++) runs.add(run("flush-"+i,(i==4?205:190)*factor,i));
            Map<String,Object> c=pick(runs);c.put("uniform_size_multiplier",factor);sizeCases.add(c);
        }
        out.put("density_size_interventions",sizeCases);
        List<Object> shardCases=new ArrayList<>();
        for(double factor:new double[]{0.9,1.0,1.1}) {
            Map<String,Object> c=new LinkedHashMap<>();c.put("combined_density_mib",100*factor);
            c.put("shard_count",Controller.calculateNumShards(100*MIB*factor,Controller.defaultMinSSTableSizeBytes(),1,64*MIB,0.333));
            shardCases.add(c);
        }
        out.put("shard_size_interventions",shardCases);
        List<VCompPipeline.VirtualSortedRun> runs=new ArrayList<>();
        List<Object> immediate=new ArrayList<>();
        for(int i=1;i<=5;i++) {
            runs.add(run("flush-"+i,64,i));
            if(i<=4) { Map<String,Object> c=pick(runs);c.put("event","after-flush-"+i);immediate.add(c); }
        }
        out.put("immediate_pick_first_job",immediate);
        out.put("first_picker_call_delayed_to_flush_5",pick(runs));
        Map<String,Object> reserved=pick(new ArrayList<>(runs.subList(1,4)));
        reserved.put("held_input","flush-1");out.put("same_flush_4_with_input_1_reserved",reserved);
        // Two-point fitting mechanism only: fixed partition overhead omitted by contiguous calibration.
        VCompSSTSizeModel affine=new VCompSSTSizeModel();
        affine.addCalibration(4096,4096*1000L+100,8192,8192*1000L+100);
        Map<String,Object> calibration=new LinkedHashMap<>();
        calibration.put("synthetic_example_only",true);calibration.put("fit","1000 bytes per row + 100 bytes fixed per file");
        calibration.put("rows",8192);calibration.put("predicted",affine.estimate(8192));
        calibration.put("if_64_partitions_each_cost_100_bytes",8192*1000L+64*100);
        calibration.put("does_not_measure_actual_cassandra_partition_cost",true);out.put("calibration_omitted_variable_control",calibration);
        Files.write(Paths.get(args[0]),JsonUtils.writeAsJsonBytes(out));
        System.out.println(JsonUtils.writeAsJsonString(out));
    }
}
