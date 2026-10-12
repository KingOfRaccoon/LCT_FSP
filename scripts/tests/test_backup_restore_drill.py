import unittest
from pathlib import Path
from unittest.mock import patch

from scripts.backup_restore_drill import validate_target, quote_identifier, checked_command, drill
from types import SimpleNamespace


class RecoverySafetyTest(unittest.TestCase):
    def test_restore_never_targets_application_or_test_databases(self):
        for name in ('fsp_dev', 'fsp_test', 'postgres', 'template1', 'fsp_restore_drill_;DROP DATABASE fsp_dev'):
            with self.subTest(name=name), self.assertRaises(ValueError):
                validate_target(name)

    def test_generated_namespace_is_allowed(self):
        validate_target('fsp_restore_drill_20261008_abcdef')

    def test_sql_identifier_is_quoted_without_execution(self):
        self.assertEqual(quote_identifier('a";DROP TABLE users;--'), '"a"";DROP TABLE users;--"')

    def test_failed_database_creation_never_continues_to_restore(self):
        with patch('scripts.backup_restore_drill.subprocess.run') as run:
            run.return_value.returncode = 1
            run.return_value.stderr = b'sensitive connection details'
            with self.assertRaisesRegex(RuntimeError, '^createdb failed; inspect local PostgreSQL logs$'):
                checked_command([str(Path('/bin/createdb')), 'fsp_restore_drill_collision'])
            self.assertEqual(run.call_count, 1)

    def test_non_demo_source_rejected_before_files_or_database_commands(self):
        for source in ('fsp_prod', 'fsp_test', 'postgres'):
            with self.subTest(source=source), patch('pathlib.Path.mkdir') as mkdir, \
                 patch('scripts.backup_restore_drill.subprocess.Popen') as start:
                start.side_effect = AssertionError('Database command started for a forbidden source')
                args = SimpleNamespace(source=source, target='fsp_restore_drill_safe',
                                       host='127.0.0.1', out_dir='local/should_not_exist',
                                       pg_bin='', port=55432, user='fsp')
                with self.assertRaises(ValueError):
                    drill(args)
                mkdir.assert_not_called()
                start.assert_not_called()


if __name__ == '__main__':
    unittest.main()
