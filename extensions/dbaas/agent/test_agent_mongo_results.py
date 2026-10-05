import importlib.util
import json
from pathlib import Path
import unittest
from unittest.mock import MagicMock, patch

spec = importlib.util.spec_from_file_location('agent', Path(__file__).with_name('dbaas_agent.py'))
agent = importlib.util.module_from_spec(spec)
spec.loader.exec_module(agent)


class MongoResultTests(unittest.TestCase):
    def query(self, documents, **options):
        client = MagicMock()
        database = MagicMock()
        cursor = database.__getitem__.return_value.find.return_value
        cursor.limit.return_value = iter(documents)
        job = {'payload': json.dumps({'sql': json.dumps({
            'collection': 'items', 'limit': options.pop('limit', 100)})}),
            'db_role': 'readonly', **options}
        with patch.object(agent, 'connect_mongodb', return_value=(client, database)):
            result = agent.run_mongo_job({}, job, {})
        return result, database

    def test_zero_and_negative_limits_are_rejected_before_reading(self):
        for limit in (0, -1):
            with self.subTest(limit=limit):
                result, database = self.query([], limit=limit)
                self.assertEqual(result[0], 'failed')
                database.__getitem__.return_value.find.assert_not_called()

    def test_exact_limit_is_not_truncated_but_extra_document_is(self):
        exact, _ = self.query([{'value': 1}], limit=1)
        extra, _ = self.query([{'value': 1}, {'value': 2}], limit=1)
        self.assertFalse(exact[2])
        self.assertTrue(extra[2])
        self.assertEqual(extra[1], 1)

    def test_byte_cap_stops_before_oversized_document(self):
        result, _ = self.query([{'value': 'x' * 100}], bytes_limit=20)
        self.assertEqual(result[1], 0)
        self.assertTrue(result[2])

    def test_null_boolean_and_nested_values_remain_readable(self):
        result, _ = self.query([{'optional': None, 'enabled': True,
                                'nested': {'value': False}}])
        data = json.loads(result[3])
        row = dict(zip(data['columns'], data['rows'][0]))
        self.assertIsNone(row['optional'])
        self.assertEqual(row['enabled'], 'true')
        self.assertEqual(json.loads(row['nested']), {'value': False})

    def test_preview_uses_same_byte_cap(self):
        client, database = MagicMock(), MagicMock()
        database.__getitem__.return_value.find.return_value.skip.return_value.limit.return_value = iter([
            {'value': 'x' * 100}])
        job = {'payload': json.dumps({'table': 'items', 'limit': 1}), 'bytes_limit': 20}
        with patch.object(agent, 'engine_name', return_value='mongodb'), \
                patch.object(agent, 'connect_mongodb', return_value=(client, database)):
            result = agent.run_table_preview_job({}, job, {})
        self.assertEqual(result[0], 'confirmed')
        self.assertEqual(result[1], 0)
        self.assertTrue(result[2])
        client.close.assert_called_once()


if __name__ == '__main__':
    unittest.main()
