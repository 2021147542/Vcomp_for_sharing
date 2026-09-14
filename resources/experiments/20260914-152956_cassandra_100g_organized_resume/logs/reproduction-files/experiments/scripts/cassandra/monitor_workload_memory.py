#!/usr/bin/env python3
"""Record the shared daemon/client cgroup memory budget and enforce OOM checks."""

import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time


def pairs(path):
    return {key: int(value) for key, value in
            (line.split() for line in path.read_text().splitlines())}


def main():
    output, runner = map(Path, sys.argv[1:])
    membership = Path('/proc/self/cgroup').read_text()
    relative = next(line[3:] for line in membership.splitlines() if line.startswith('0::'))
    group = Path('/sys/fs/cgroup') / relative.lstrip('/')
    maximum = (group / 'memory.max').read_text().strip()
    swap = (group / 'memory.swap.max').read_text().strip()
    if maximum != str(20 * 1024 ** 3) or swap != '0':
        raise RuntimeError(f'Unexpected memory budget: {maximum}, swap={swap}')
    before = pairs(group / 'memory.events')
    protocol = dict(cgroup=str(group), memory_max_bytes=int(maximum), swap_max_bytes=0,
                    scope='Cassandra daemon and workload clients together',
                    interpretation='Total memory cap; additional OS page cache is not an exact 5% cache',
                    memory_events_before=before)
    trace_fd, trace_name = tempfile.mkstemp(prefix='vcomp-workload-memory-', suffix='.jsonl', dir='/tmp')
    os.close(trace_fd)
    if os.stat(trace_name).st_dev == output.stat().st_dev:
        raise RuntimeError('Memory diagnostics must use a filesystem outside the measured DB device')
    protocol['live_memory_trace'] = trace_name
    protocol['logging'] = 'Temporary trace outside DB filesystem; archived after all measured clients exit'
    (output / 'protocol.json').write_text(json.dumps(protocol, indent=2) + '\n')
    child = subprocess.Popen(['bash', str(runner)])
    with open(trace_name, 'w', buffering=1) as stream:
        while True:
            record = dict(unix_seconds=time.time(),
                          memory_current=int((group / 'memory.current').read_text()),
                          memory_stat=pairs(group / 'memory.stat'),
                          memory_events=pairs(group / 'memory.events'))
            stream.write(json.dumps(record) + '\n')
            try:
                status = child.wait(timeout=5)
                break
            except subprocess.TimeoutExpired:
                pass
    after = pairs(group / 'memory.events')
    shutil.copyfile(trace_name, output / 'memory.jsonl')
    protocol.update(memory_events_after=after, runner_exit_status=status)
    (output / 'protocol.json').write_text(json.dumps(protocol, indent=2) + '\n')
    if any(after.get(key, 0) > before.get(key, 0) for key in ('oom', 'oom_kill', 'oom_group_kill')):
        raise RuntimeError('Cgroup OOM event: workload results are invalid; retain failure evidence')
    return status


if __name__ == '__main__':
    sys.exit(main())
