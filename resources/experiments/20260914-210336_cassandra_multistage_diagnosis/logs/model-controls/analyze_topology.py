import csv,json,math,statistics,bisect,collections
from pathlib import Path
root=Path('/tmp/vcomp-complete-sketch-probe')
source=Path('/home/dongju/vcomp/experiments/artifacts/cassandra-checkpoint-read-20260914-212215/opened-sst-metadata.json')
data=json.loads(source.read_text())
layout=list(csv.DictReader((root/'partition-layout-100g.tsv').open(),delimiter='\t'))
by_key={p['key']:int(p['ordinal']) for p in layout}
weights=[int(p['maximum'])-int(p['minimum'])+1 for p in layout]
minimum=[int(p['minimum']) for p in layout]
request_path=source.parent/'point-C'/'actual-read-requests.json'
requests=json.loads(request_path.read_text())['keys'] if request_path.exists() else []
def stats(a):
 s=sorted(a);return dict(count=len(s),minimum=s[0],maximum=s[-1],mean=statistics.mean(s),population_sd=statistics.pstdev(s),p50=s[math.ceil(.5*len(s))-1],p95=s[math.ceil(.95*len(s))-1],p99=s[math.ceil(.99*len(s))-1])
result=dict(diagnostic_only=True,source=str(source),method='Opened SST metadata only; coverage is inclusive first/last partition range, not actual partition occupancy or physical read count. No full row scans.',domain=104857600,partitions=10000,arms={})
for side in ('native','vcomp'):
 files=data[side];by_partition=[[] for _ in layout];descriptions=[];cohorts=collections.defaultdict(lambda:dict(files=0,bytes=0,partition_range_slots=0,timestamp_width_sum=0))
 for f in files:
  first,last=by_key[f['first_partition_key']],by_key[f['last_partition_key']]
  assert first<=last
  span=last-first+1
  for p in range(first,last+1):by_partition[p].append(f)
  reported=f['token_space_coverage'];coverage=(int(f['last_token'])-int(f['first_token'])+1)/2**64
  if coverage<2**-48:coverage=1.0
  effective=reported if isinstance(reported,(float,int)) and math.isfinite(reported) and reported>0 else coverage
  f['range_first_partition_ordinal']=first;f['range_last_partition_ordinal']=last
  f['range_partition_count']=span;f['effective_token_coverage']=effective;f['density_bytes_per_ring']=f['data_db_bytes']/effective
  f['timestamp_width']=f['max_timestamp']-f['min_timestamp']
  c=cohorts[str(math.ceil(f['max_timestamp']/65536))];c['files']+=1;c['bytes']+=f['data_db_bytes'];c['partition_range_slots']+=span;c['timestamp_width_sum']+=f['timestamp_width']
  descriptions.append(f)
 depths=[len(p) for p in by_partition]
 shared_time_pairs=0;all_pairs=0
 for fs in by_partition:
  for i,a in enumerate(fs):
   for b in fs[i+1:]:
    all_pairs+=1;shared_time_pairs+= max(a['min_timestamp'],b['min_timestamp'])<=min(a['max_timestamp'],b['max_timestamp'])
 request_depths=[depths[bisect.bisect_right(minimum,key)-1] for key in requests]
 result['arms'][side]=dict(file_count=len(files),data_db_bytes=sum(f['data_db_bytes'] for f in files),
   format_counts=dict(collections.Counter(f['format_version'] for f in files)),
   constant_timestamp_files=sum(f['timestamp_width']==0 for f in files),timestamp_width=stats([f['timestamp_width'] for f in files]),
   endpoint_range_candidate_depth=stats(depths),candidate_depth_histogram=dict(sorted(collections.Counter(depths).items())),
   key_domain_weighted_range_depth=sum(x*w for x,w in zip(depths,weights))/sum(weights),
   request_range_depth=stats(request_depths) if request_depths else None,
   partition_range_span_per_file=stats([f['range_partition_count'] for f in files]),
   sstable_size_bytes=stats([f['data_db_bytes'] for f in files]),
   density_bytes_per_ring=stats([f['density_bytes_per_ring'] for f in files]),
   summed_partition_key_estimates=sum(f['estimated_partition_keys'] for f in files),
   bloom_filter_bytes=sum(f['bloom_filter_bytes'] for f in files),
   overlap_pair_slots_across_partitions=all_pairs,overlap_pairs_with_intersecting_timestamp_intervals=shared_time_pairs,
   max_timestamp_flush_buckets=dict(sorted(cohorts.items(),key=lambda kv:int(kv[0]))),
   partition_depths=depths,files=descriptions)
root.joinpath('original-100g-topology.json').write_text(json.dumps(result,indent=2)+'\n')
for side,a in result['arms'].items():
 print(side,{k:v for k,v in a.items() if k not in ('partition_depths','files','max_timestamp_flush_buckets')})
 print('cohorts',a['max_timestamp_flush_buckets'])
