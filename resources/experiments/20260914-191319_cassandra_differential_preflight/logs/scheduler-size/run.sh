#!/usr/bin/env bash
set -euo pipefail
repo="${REPO_ROOT:-/home/dongju/vcomp}"
audit="${AUDIT_ROOT:-$(cd "$(dirname "$0")" && pwd)}"
cd "$repo/cassandra_vcomp"
mkdir -p "$audit/classes"
jdk=/usr/lib/jvm/java-11-openjdk-amd64/bin
"$jdk/javac" -cp 'build/classes/main:build/lib/jars/*' -d "$audit/classes" "$audit/src/VCompSchedulingSensitivity.java" "$audit/src/VCompCalibrationCoverage.java"
for kind in VCompSchedulingSensitivity VCompCalibrationCoverage; do
 case "$kind" in VCompSchedulingSensitivity) name=sensitivity;; *) name=calibration-coverage;; esac
 "$jdk/java" -ea -Dlogback.configurationFile="$repo/cassandra_check/logback-smoke.xml" -cp "$audit/classes:build/classes/main:build/lib/jars/*" "$kind" "$audit/$name.json" > "$audit/$name.log" 2>&1
done
python3 - "$audit" <<'PY'
import json,sys
from pathlib import Path
root=Path(sys.argv[1]);s=json.loads((root/'sensitivity.json').read_text());c=json.loads((root/'calibration-coverage.json').read_text())
assert [len(x['input_ids']) for x in s['density_size_interventions']]==[4,0,4]
assert [x['input_level'] for x in s['density_size_interventions']]==[0,None,1]
assert [x['shard_count'] for x in s['shard_size_interventions']]==[1,1,2]
assert s['immediate_pick_first_job'][-1]['input_ids']==['flush-4','flush-3','flush-2','flush-1']
assert s['first_picker_call_delayed_to_flush_5']['input_ids']==['flush-5','flush-4','flush-3','flush-2','flush-1']
assert c['random_first_flush_unique']==65519 and c['random_first_flush_occupied_partitions']==9983
assert c['calibration_4096_partition_count']==1 and c['calibration_8192_partition_count']==2
print('PASS: seven bounded diagnostic assertions; not a physical size/native scheduling fidelity certificate')
PY
