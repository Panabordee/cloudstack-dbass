import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('agent', Path(__file__).with_name('dbaas_agent.py'))
agent = importlib.util.module_from_spec(spec)
spec.loader.exec_module(agent)


class PredropStorageFailureTests(unittest.TestCase):
    def test_unavailable_dump_directory_reports_failure_without_dropping(self):
        with tempfile.TemporaryDirectory() as folder:
            blocked = Path(folder) / 'predrop'
            blocked.write_text('existing file prevents creating the dump directory')
            job = {'type': 'table_drop', 'payload': json.dumps({
                'table': 'qa_table', 'statement': 'DROP TABLE qa_table'})}
            with patch.object(agent, 'PREDROP_DIR', str(blocked)), \
                    patch.object(agent, 'engine_name', return_value='postgresql'), \
                    patch.object(agent, 'connect_postgresql') as connect:
                result = agent.execute({'database': 'qa_database'}, job,
                                       {'user': 'qa_owner', 'password': 'test_only'})
                self.assertEqual(result[0], 'failed')
                self.assertIn('pre-drop dump failed', result[4])
                connect.assert_not_called()
            self.assertTrue(blocked.is_file())

    def test_directory_permission_failure_preserves_existing_dump(self):
        with tempfile.TemporaryDirectory() as folder:
            previous = Path(folder) / 'qa_table.same_time.sql'
            previous.write_text('existing backup')
            with patch.object(agent, 'PREDROP_DIR', folder), \
                    patch.object(agent.time, 'strftime', return_value='same_time'), \
                    patch.object(agent.os, 'chmod', side_effect=PermissionError('denied')):
                path, error = agent.dump_table_before_drop(
                    'postgresql', {'user': 'qa_owner', 'password': 'test_only'},
                    'qa_database', 'qa_table')
            self.assertIsNone(path)
            self.assertIn('denied', error)
            self.assertEqual(previous.read_text(), 'existing backup')


if __name__ == '__main__':
    unittest.main()
