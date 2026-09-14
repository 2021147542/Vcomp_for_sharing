#!/usr/bin/env python3
"""Find the first unequal field in a bounded native/exact compaction trace.

This consumes diagnostic observations, not benchmark scores. Unchecked stages
remain unchecked; matching a small fixture never certifies the production port.
"""
import argparse
import json
from pathlib import Path

STAGES = ('picker', 'merge', 'split', 'physical_bytes', 'materialization', 'approximation')
REQUIRED = STAGES[:-1]


def first_difference(native, predicted, path='$'):
    if type(native) is not type(predicted):
        # JSON integer/float representations of the same measurement are equal.
        numeric = lambda value: type(value) in (int, float)
        if numeric(native) and numeric(predicted) and native == predicted:
            return None
        return {'field': path, 'native': native, 'predicted': predicted, 'reason': 'type'}
    if isinstance(native, dict):
        for key in sorted(set(native) | set(predicted)):
            if key not in native or key not in predicted:
                return {'field': path + '.' + key, 'native_present': key in native,
                        'predicted_present': key in predicted, 'reason': 'missing_field'}
            difference = first_difference(native[key], predicted[key], path + '.' + key)
            if difference is not None:
                return difference
    elif isinstance(native, list):
        if len(native) != len(predicted):
            return {'field': path + '.length', 'native': len(native),
                    'predicted': len(predicted), 'reason': 'length'}
        for index, (left, right) in enumerate(zip(native, predicted)):
            difference = first_difference(left, right, f'{path}[{index}]')
            if difference is not None:
                return difference
    elif native != predicted:
        return {'field': path, 'native': native, 'predicted': predicted, 'reason': 'value'}
    return None


def analyze(trace):
    if trace.get('schema_version') != 1 or trace.get('diagnostic_only') is not True:
        raise ValueError('Expected schema_version=1 and diagnostic_only=true')
    jobs = trace.get('jobs')
    if not isinstance(jobs, list) or not jobs:
        raise ValueError('Trace must contain actual observed jobs')
    report = {'diagnostic_only': True, 'scope': trace.get('scope'), 'seed': trace.get('seed'),
              'jobs_observed': len(jobs), 'comparisons': [], 'unchecked': [],
              'fixture': trace.get('fixture'),
              'first_divergence': None, 'production_fidelity_certified': False}
    seen = set()
    for job in jobs:
        job_id = job['job_id']
        if str(job_id) in seen:
            raise ValueError('Duplicate job_id: ' + str(job_id))
        seen.add(str(job_id))
        stages = job.get('stages', {})
        for name in STAGES:
            stage = stages.get(name, {})
            status = stage.get('status', 'not_checked')
            if status == 'not_checked':
                report['unchecked'].append({'job_id': job_id, 'stage': name,
                                            'reason': stage.get('reason', 'No observation supplied')})
                continue
            if status != 'checked':
                raise ValueError(f'Unsupported stage status: {name}/{status}')
            predicted_name = 'approximate' if name == 'approximation' else 'exact'
            if 'native' not in stage or predicted_name not in stage:
                raise ValueError(f'Checked {name} requires native and {predicted_name} observations')
            difference = first_difference(stage['native'], stage[predicted_name])
            observation = {'job_id': job_id, 'stage': name, 'matches': difference is None}
            report['comparisons'].append(observation)
            if difference is not None:
                report['first_divergence'] = dict(observation, **difference,
                    category='model_path_difference' if name == 'approximation' else 'exact_path_difference')
                report['stopped_at_first_divergence'] = True
                report['all_required_stages_checked'] = False
                return report
    report['stopped_at_first_divergence'] = False
    report['all_required_stages_checked'] = not any(x['stage'] in REQUIRED for x in report['unchecked'])
    return report


def markdown(report, source):
    difference = report['first_divergence']
    lines = ['# Cassandra differential diagnostic', '', f'Source trace: `{source}`.', '',
             'This is a bounded diagnostic, not a 100 GiB benchmark or a production fidelity certificate.', '']
    if difference:
        lines += [f"First difference in this trace: job `{difference['job_id']}`, stage `{difference['stage']}`, field `{difference['field']}`.", '',
                  '```json', json.dumps(difference, indent=2, ensure_ascii=False), '```', '']
    else:
        lines += ['No difference in the checked observations. Unchecked stages remain unresolved.', '']
    lines += ['| Job | Stage | Match |', '|---|---|---|']
    lines += [f"| {r['job_id']} | {r['stage']} | {r['matches']} |" for r in report['comparisons']]
    lines += ['', 'Unchecked observations:', '']
    lines += [f"- Job {r['job_id']}, {r['stage']}: {r['reason']}" for r in report['unchecked']] or ['None.']
    return '\n'.join(lines) + '\n'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('trace', type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--markdown', type=Path)
    args = parser.parse_args()
    report = analyze(json.loads(args.trace.read_text()))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, ensure_ascii=False) + '\n')
    if args.markdown:
        args.markdown.write_text(markdown(report, args.trace))
    print(json.dumps({'first_divergence': report['first_divergence'],
                      'all_required_stages_checked': report['all_required_stages_checked']}))


if __name__ == '__main__':
    main()
