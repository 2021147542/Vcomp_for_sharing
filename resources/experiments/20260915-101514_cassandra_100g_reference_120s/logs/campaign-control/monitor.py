import json,re,datetime
from pathlib import Path
raw=Path('/work/vcomp-pebble-1tb/cassandra-reference-100g-120s-20260915-101514')
control=Path(__file__).resolve().parent
log=(control/'campaign.log').read_text()
marks=[s for s in log.splitlines() if re.search(r'Preparing |Running [A-F]|Running MIXGRAPH|Completed:|ERROR|Exception',s)]
rows={p.stem:json.loads(p.read_text()) for p in (raw/'workloads/results').glob('*.json') if p.stat().st_size}
print(datetime.datetime.now().isoformat(timespec='seconds'),'cells',len(rows),'/14')
print('\n'.join(marks[-2:]))
for w in ['a','b','c','d','e','f','mixgraph']:
 if w+'_baseline' in rows and w+'_vcomp' in rows:
  a,b=rows[w+'_baseline'],rows[w+'_vcomp'];lat='scan_latency_p50_us' if w=='e' else 'point_lookup_latency_p50_us'
  print(w, 'throughput_delta_pct', round((b['throughput_ops_per_second']/a['throughput_ops_per_second']-1)*100,2), 'read_p50_delta_pct',round((b[lat]/a[lat]-1)*100,2))
if (control/'status.env').exists(): print((control/'status.env').read_text())
