import unittest

from cassandra_first_divergence import analyze, first_difference


class FirstDivergenceTest(unittest.TestCase):
    def trace(self, stages):
        return {'schema_version': 1, 'diagnostic_only': True,
                'jobs': [{'job_id': 1, 'stages': stages}]}

    def test_timestamp_difference_is_not_hidden_by_equal_keys(self):
        result = first_difference([{'key': 8, 'timestamp': 2}], [{'key': 8, 'timestamp': 1}])
        self.assertEqual('$[0].timestamp', result['field'])

    def test_stop_before_later_stage_and_job(self):
        trace = self.trace({'merge': {'status': 'checked', 'native': {'rows': 4}, 'exact': {'rows': 3}},
                            'split': {'status': 'checked', 'native': {'count': 2}, 'exact': {'count': 1}}})
        trace['jobs'].append({'job_id': 2, 'stages': {}})
        report = analyze(trace)
        self.assertEqual(1, len(report['comparisons']))
        self.assertEqual('merge', report['first_divergence']['stage'])

    def test_missing_picker_never_becomes_a_full_pass(self):
        report = analyze(self.trace({'merge': {'status': 'checked', 'native': [1], 'exact': [1]}}))
        self.assertIsNone(report['first_divergence'])
        self.assertFalse(report['all_required_stages_checked'])
        self.assertFalse(report['production_fidelity_certified'])

    def test_missing_field_differs_from_null(self):
        report = first_difference({'timestamp': None}, {})
        self.assertEqual('missing_field', report['reason'])

    def test_approximation_is_separate(self):
        report = analyze(self.trace({'approximation': {'status': 'checked', 'native': [1, 3],
                                                       'approximate': [1, 2]}}))
        self.assertEqual('model_path_difference', report['first_divergence']['category'])

    def test_malformed_checked_stage_is_rejected(self):
        with self.assertRaises(ValueError):
            analyze(self.trace({'merge': {'status': 'checked', 'native': [1]}}))


if __name__ == '__main__':
    unittest.main()
