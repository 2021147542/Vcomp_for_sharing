import json,math,collections
from pathlib import Path
root=Path('/tmp/vcomp-complete-sketch-probe');d=json.loads((root/'original-100g-topology.json').read_text());r=dict(diagnostic_only=True,method='UCS T4 density buckets using base=max(1MiB,rounded_flush)*(1-.9/4); next upper=floor(previous*4); survival_factor1. Native66MiB inferred from logged uncompressed flushes; model65MiB recorded; both65/66 sensitivities retained. Density bucket is not physical level metadata or actual compaction lineage.',arms={})
for side in ['native','vcomp']:
 r['arms'][side]={}
 for flush_mib in (65,66):
  upper=math.floor(flush_mib*1048576*.775*4);bounds=[]
  for level in range(9):bounds.append(upper);upper=math.floor(upper*4)
  groups=collections.defaultdict(lambda:dict(files=0,data_bytes=0,partition_range_slots=0,per_partition_depth=[0]*10000,densities=[]))
  for f in d['arms'][side]['files']:
   density=f['density_bytes_per_ring'];level=next(i for i,u in enumerate(bounds) if density<u);g=groups[level];g['files']+=1;g['data_bytes']+=f['data_db_bytes'];g['partition_range_slots']+=f['range_partition_count'];g['densities'].append(density)
   for i in range(f['range_first_partition_ordinal'],f['range_last_partition_ordinal']+1):g['per_partition_depth'][i]+=1
  for level,g in groups.items():
   g['coverage_partition_count']=sum(v>0 for v in g['per_partition_depth']);g['depth_histogram']=dict(sorted(collections.Counter(g['per_partition_depth']).items()));g['density_min']=min(g.pop('densities'));g['upper_bound_bytes_per_ring']=bounds[level]
  r['arms'][side][str(flush_mib)]={str(k):v for k,v in sorted(groups.items())}
(root/'original-100g-density-tiers.json').write_text(json.dumps(r,indent=2)+'\n')
for side in ['native','vcomp']:
 for size in ['65','66']:
  print(side,'flush',size)
  for l,g in r['arms'][side][size].items():print(l,g['files'],g['coverage_partition_count'],g['partition_range_slots'],g['depth_histogram'])
