#!/usr/bin/env python3
"""Bounded native lifecycle analysis; exact vectors and request footprints are diagnostic-only."""
import argparse
from collections import Counter
import gzip
import json
from pathlib import Path


def load(path):
    return json.loads(path.read_text())


def write(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n')


def partition(key):
    # VCompOrderedPartitionLayout's ordinal; actual IDs are token-sorted at construction.
    base, extra = divmod(104857600, 10000)
    prefix = (base + 1) * extra
    return key // (base + 1) if key < prefix else extra + (key - prefix) // base


def distribution(vectors):
    multiplicity = Counter(k for v in vectors.values() for k in v)
    files = {}
    for name, rows in vectors.items():
        keys = sorted(rows)
        counts = Counter(partition(k) for k in keys)
        files[name] = dict(rows=len(rows), occupied_partitions=len(counts), key_min=min(keys), key_max=max(keys),
                           partition_row_count_histogram=dict(sorted(Counter(counts.values()).items())),
                           key_quantiles={str(q): keys[round(q * (len(keys) - 1))] for q in [0, .25, .5, .75, 1]})
    return dict(physical_rows=sum(map(len, vectors.values())), unique_keys=len(multiplicity),
                cross_sst_duplicate_rows=sum(multiplicity.values()) - len(multiplicity),
                duplicate_keys=sum(v > 1 for v in multiplicity.values()),
                key_multiplicity_histogram=dict(sorted(Counter(multiplicity.values()).items())), files=files)


def main():
    p = argparse.ArgumentParser()
    p.add_argument('native', type=Path)
    p.add_argument('replay', type=Path)
    p.add_argument('requests', type=Path)
    p.add_argument('output', type=Path)
    a = p.parse_args()
    a.output.mkdir(parents=True, exist_ok=True)
    events = load(a.native / 'native-events.json')
    virtual = load(a.replay / 'virtual-events.json')
    names = {}
    flush = job = 0
    for e in events:
        if e['kind'] == 'flush_registered':
            flush += 1
            assert len(e['added']) == 1
            names[e['added'][0]] = f'F{flush}'
        elif e['kind'] == 'job_commit_visible':
            job += 1
            for i, name in enumerate(e['added']):
                names[name] = f'J{job}.{i}'
    timeline = []
    for e in events:
        row = dict(e)
        for field in ['live', 'eligible', 'in_flight_live', 'selected', 'added', 'strategy_candidates', 'tracker_compacting_separate_read']:
            if field in row:
                row[field] = [names.get(x, x) for x in row[field]]
        timeline.append(row)
    write(a.output / 'native-timeline.json', timeline)
    write(a.output / 'native-identities.json', names)
    nv = load(a.native / 'native-exact-vectors.json')
    native = {names[k]: {int(key): ts for key, ts in nv[k].items()} for k in events[-1]['live']}
    vv = load(a.replay / 'virtual-exact-vectors.json')
    vcomp = {f'V{i}': {int(key): ts for key, ts in rows.items()} for i, (name, rows) in enumerate(vv.items())}
    vectors = dict(native=native, vcomp=vcomp)
    distributions = {lane: distribution(rows) for lane, rows in vectors.items()}
    nu, vu = set().union(*native.values()), set().union(*vcomp.values())
    distributions['comparison'] = dict(native_only_keys=len(nu-vu), vcomp_only_keys=len(vu-nu), common_keys=len(nu & vu))
    write(a.output / 'sst-distributions.json', distributions)
    req = load(a.requests)
    keys = req['keys']
    assert len(keys) == 10000 and req['seed'] == 20260909
    partitions = {lane: {name: {partition(k) for k in rows} for name, rows in files.items()} for lane, files in vectors.items()}
    counts = {lane: Counter() for lane in vectors}
    footprints = []
    for i, k in enumerate(keys):
        item = dict(request=i, key=k, partition_ordinal=partition(k))
        for lane, files in vectors.items():
            membership = [name for name, rows in files.items() if k in rows]
            presence = [name for name, ordinals in partitions[lane].items() if partition(k) in ordinals]
            counts[lane]['hits' if membership else 'misses'] += 1
            counts[lane]['key_membership_ssts'] += len(membership)
            counts[lane]['partition_presence_ssts'] += len(presence)
            item[lane] = dict(key_membership=membership, partition_presence=presence)
        footprints.append(item)
    write(a.output / 'fixed-read-summary.json', dict(scope='static exact SST key membership / partition presence; NOT actual read requests, SST accesses, Bloom checks or I/O',
         generator=req['generator_version'], requests=len(keys), counts={lane:dict(c) for lane,c in counts.items()},
         hit_outcome_differences=sum(bool(r['native']['key_membership']) != bool(r['vcomp']['key_membership']) for r in footprints)))
    with gzip.open(a.output / 'fixed-read-footprints.json.gz', 'wt') as f:
        json.dump(footprints, f, separators=(',', ':'))
    # Validate the corresponding real production-loop snapshots without assigning synthetic time.
    virtual_names = {f'run-{i}': f'F{i+1}' for i in range(5)}
    virtual_names['run-compaction-0'] = 'J1.0'
    vf5 = next(e for e in virtual if e['flush_number'] == 5)
    assert sorted(virtual_names[k] for k in vf5['live']) == ['F5', 'J1.0']
    assert vf5['selected'] == []
    for flush_number, native_seq in [(1,5),(2,8),(3,12),(4,17)]:
        v = next(e for e in virtual if e['flush_number'] == flush_number)
        assert sorted(virtual_names[k] for k in v['live']) == sorted(timeline[native_seq]['strategy_candidates'])
        assert sorted(virtual_names[k] for k in v['selected']) == sorted(timeline[native_seq]['selected'])
    controls = load(a.replay / 'same-snapshot-controls.json')
    checked = 0
    for control in controls:
        assert control['view_stable']
        if control['observed_flush_bytes'] == 0:
            assert control['native_selected'] == []
            continue
        for lane in ['measured_control','native_metadata_shared_kernel_control','predicted_size_same_availability']:
            assert control[lane]['selected'] == control['native_selected'], (control['event_seq'], lane)
            assert control[lane]['level'] == control['native_level'], (control['event_seq'], lane, 'level')
        checked += 1
    assert checked == 12
    metadata = load(a.native / 'native-sst-metadata.json')
    first_id = next(k for k,v in names.items() if v == 'F1')
    observed = metadata[first_id]['data_db_bytes']
    predicted = virtual[0]['runs'][0]['sstables'][0]['estimated_bytes']
    write(a.output / 'first-differences.json', dict(
        metadata=dict(native_event_seq=2, flush='F1', field='Data.db bytes vs estimated_bytes', native_bytes=observed,
                      virtual_estimated_bytes=predicted, delta_percent=(predicted/observed-1)*100,
                      scope='first substantive picker-size metadata difference; coverage has roundoff differences; timestamp collapse already established'),
        availability=dict(native_event_seq=19, confirmed_native_picker_seq=20, virtual_event_seq=vf5['seq'], flush='F5',
                          field='eligible input identities / prior job output availability', native_eligible=['F5'],
                          native_inflight=['F1','F2','F3','F4'], virtual_eligible=['J1.0','F5'],
                          native_selected=[], virtual_selected=[],
                          scope='first differing equivalent pre-pick flush boundary; no selected input-set divergence'),
        policy=dict(checked_native_snapshots=checked, initial_empty_not_applicable=2,
                    shared_kernel_matches=True, measured_vcomp_matches=True, predicted_size_matches=True,
                    selected_set_difference_observed=False)))
    # Preserve actual pipeline schema as emitted; analysis does not invent virtual timings.
    write(a.output / 'virtual-events-reviewed.json', virtual)
    assert len(events) == 28 and flush == 5 and job == 1
    assert timeline[17]['selected'] == ['F1','F2','F3','F4']
    assert timeline[19]['kind'] == 'flush_registered' and timeline[19]['eligible'] == ['F5']
    assert timeline[20]['selected'] == []
    assert timeline[24]['kind'] == 'job_complete' and timeline[24]['success']
    assert timeline[26]['selected'] == []
    assert distributions['native']['unique_keys'] == 20478
    write(a.output / 'analysis-validation.json', dict(native_events=28, native_flushes=5, native_jobs=1,
         native_final_exact_rows=20478, requests=10000, performance_measured=False,
         first_availability_boundary=dict(native_event_seq=19, confirmed_picker_seq=20, field='eligible input identities / prior job output availability'),
         note='Corresponding virtual F5, earlier flush boundaries, and12 same-snapshot set/level controls validated. Fixture-specific assertions are evidence validation, not production tests.'))

if __name__ == '__main__':
    main()
