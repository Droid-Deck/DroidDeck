"""Test freshness, narrow output, and lock handling without accessing a device."""
from datetime import datetime, timezone
import importlib.util
import json
import io
import os
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('reset_projection', Path(__file__).resolve().parents[1] / 'pixel_reset_diagnostics.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class ResetProjectionTest(unittest.TestCase):
    def test_only_primary_state(self):
        self.assertEqual(module.primary_state('Private space secret\nStarted users state: [0=RUNNING_UNLOCKED, 10=STOPPED]'), 'RUNNING_UNLOCKED')
        self.assertEqual(module.primary_state('State: RUNNING_UNLOCKED'), 'unknown')

    def test_stale_body_not_used(self):
        raw = '2026-10-10 20:10:09 SYSTEM_LAST_KMSG (text, 1024 bytes)\nGNOC error type 0x2000'
        result = module.kernel_projection(raw, 'America/Toronto', datetime(2026, 10, 11, 21, tzinfo=timezone.utc))
        self.assertEqual(result['status'], 'stale_entry_not_attributed_to_current_trial')
        self.assertNotIn('categories', result)

    def test_repeated_dst_hour_is_ambiguous_before_freshness(self):
        raw = '2026-11-01 01:30:00 SYSTEM_LAST_KMSG\nGNOC error type 0x2000'
        for after in (None, datetime(2026, 11, 1, 6, tzinfo=timezone.utc)):
            with self.subTest(after=after):
                result = module.kernel_projection(raw, 'America/Toronto', after)
                self.assertEqual(result['status'], 'ambiguous_device_local_time')
                self.assertNotIn('entry_date_utc', result)
                self.assertNotIn('categories', result)
                self.assertNotIn('hardware_fields', result)

    def test_skipped_dst_hour_is_not_a_real_instant(self):
        raw = '2026-03-08 02:30:00 SYSTEM_LAST_KMSG\nGNOC error type 0x2000'
        result = module.kernel_projection(raw, 'America/Toronto')
        self.assertEqual(result['status'], 'nonexistent_device_local_time')
        self.assertNotIn('entry_date_utc', result)
        self.assertNotIn('categories', result)

    def test_unambiguous_times_keep_correct_offsets_and_cutoff(self):
        for stamp, expected in (('2026-11-01 00:30:00', '2026-11-01T04:30:00+00:00'),
                                ('2026-11-01 02:30:00', '2026-11-01T07:30:00+00:00'),
                                ('2026-03-08 03:30:00', '2026-03-08T07:30:00+00:00')):
            with self.subTest(stamp=stamp):
                raw = stamp + ' SYSTEM_LAST_KMSG\nGNOC'
                result = module.kernel_projection(raw, 'America/Toronto')
                self.assertEqual(result['entry_date_utc'], expected)
                self.assertEqual(result['status'], 'projected_entry_not_a_root_cause')
                cutoff = datetime.fromisoformat(expected)
                self.assertEqual(module.kernel_projection(raw, 'America/Toronto', cutoff)['status'],
                                 'projected_entry_not_a_root_cause')

    def test_latest_entry_and_no_arbitrary_body(self):
        raw = ('2026-10-10 20:10:09 SYSTEM_LAST_KMSG (text)\nGNOC error type 0x1111\n'
               '2026-10-11 17:35:01 SYSTEM_LAST_KMSG (text)\n'
               'PRIVATE_TEST_VALUE=a_secret_that_must_not_be_emitted\n'
               'Error from GNOC at addr 0x28220000\nGNOC error type 0x2000\n'
               'Reboot reason: 0xc005 - Secret arbitrary description\nLast boot reason: reset,warm,gnoc_error\n')
        result = module.kernel_projection(raw, 'America/Toronto', datetime(2026, 10, 11, 21, tzinfo=timezone.utc))
        self.assertEqual(result['entry_date_utc'], '2026-10-11T21:35:01+00:00')
        self.assertEqual(result['hardware_fields']['gnoc_error_types'], ['0x2000'])
        self.assertNotIn('0x1111', json.dumps(result))
        self.assertNotIn('a_secret', json.dumps(result))
        self.assertNotIn('arbitrary description', json.dumps(result))
        self.assertFalse(result['root_cause_proven'])

    def test_missing_entry_and_timezone(self):
        self.assertEqual(module.kernel_projection('Permission denied', 'America/Toronto')['status'], 'no_accessible_entry')
        self.assertEqual(module.kernel_projection('2026-10-11 17:00:00 SYSTEM_LAST_KMSG\nGNOC', 'Unknown/Region')['status'], 'unknown_device_timezone')

    def test_reason_rejects_paths_and_urls(self):
        self.assertEqual(module.safe_reason('reset,warm,gnoc_error\n'), 'reset,warm,gnoc_error')
        self.assertEqual(module.safe_reason('https://private.invalid/login?secret=abc'), 'unknown')
        self.assertEqual(module.safe_reason('/data/private'), 'unknown')

    def test_reason_only_recognized_exact_values(self):
        for reason in ('cold', 'hard', 'warm', 'reboot', 'shutdown', 'recovery',
                       'bootloader', 'kernel_panic', 'watchdog', 'reset,warm,gnoc_error'):
            with self.subTest(reason=reason):
                self.assertEqual(module.safe_reason(reason + '\n'), reason)
        for reason in ('PRIVATE TEST VALUE', 'private_test_value', '123456',
                       'reboot,private_test_value', 'reset,warm,gnoc_error,private_test_value',
                       'reset,warm,gnoc_error trailing_private_test_value', ''):
            with self.subTest(reason=reason):
                self.assertEqual(module.safe_reason(reason), 'unknown')

    def test_kernel_reason_uses_same_fixed_projection(self):
        raw = ('2026-10-11 17:35:01 SYSTEM_LAST_KMSG (text)\n'
               'Last boot reason: private_test_value\n'
               'Last boot reason: reset,warm,gnoc_error trailing_private_test_value\n'
               'Last boot reason: reset,warm,gnoc_error\n'
               'Last boot reason:\nPRIVATE TEST VALUE\n')
        result = module.kernel_projection(raw, 'America/Toronto')
        self.assertEqual(result['last_boot_reasons'], ['reset,warm,gnoc_error', 'unknown'])
        self.assertNotIn('private_test_value', json.dumps(result))
        self.assertNotIn('PRIVATE TEST VALUE', json.dumps(result))

    def test_primary_state_uses_fixed_categories(self):
        for state in ('RUNNING_LOCKED', 'RUNNING_UNLOCKING', 'RUNNING_UNLOCKED'):
            self.assertEqual(module.primary_state('Started users state: [0=' + state + ']'), state)
        for state in ('RUNNING_PRIVATE_TEST_VALUE', 'RUNNING_UNLOCKED_PRIVATE',
                      'RUNNING_UNLOCKED=private_test_value'):
            self.assertEqual(module.primary_state('Started users state: [0=' + state + ']'), 'unknown')

    def test_property_reason_never_exports_custom_text(self):
        def run(argv, **kwargs):
            if argv[-2:] == ['getprop', 'ro.boot.bootreason']:
                text = 'PRIVATE TEST VALUE'
            elif argv[-2:] == ['getprop', 'sys.boot.reason']:
                text = 'reset,warm,gnoc_error,private_test_value'
            elif argv[-1] == 'persist.sys.timezone':
                text = 'America/Toronto'
            else:
                text = ''
            return subprocess.CompletedProcess(argv, 0, text, '')
        with patch.object(module.subprocess, 'run', side_effect=run):
            result = module.collect('adb', 'test-device')
        self.assertEqual(result['bootloader_reason'], 'unknown')
        self.assertEqual(result['system_boot_reason'], 'unknown')
        self.assertNotIn('PRIVATE TEST VALUE', json.dumps(result))
        self.assertNotIn('private_test_value', json.dumps(result))

    @unittest.skipUnless(os.name == 'posix', 'POSIX private file modes')
    def test_cli_output_is_private_before_writing(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'diagnostics.json'
            saved_umask = os.umask(0)
            original_fdopen = os.fdopen
            original_path_open = Path.open
            creation_modes = []
            def inspect_stream(stream):
                try:
                    creation_modes.append(stat.S_IMODE(os.fstat(stream.fileno()).st_mode))
                    self.assertEqual(os.fstat(stream.fileno()).st_size, 0)
                    self.assertEqual(creation_modes[-1], 0o600)
                except BaseException:
                    stream.close()
                    raise
                return stream
            def inspect_fd(fd, *args, **kwargs):
                return inspect_stream(original_fdopen(fd, *args, **kwargs))
            def inspect_path(path, *args, **kwargs):
                stream = original_path_open(path, *args, **kwargs)
                return inspect_stream(stream) if path == output else stream
            try:
                with patch.object(sys, 'argv', ['diagnostics', '--serial', 'test-device',
                                              '--output', str(output)]), \
                     patch.object(module, 'collect', return_value={'boot_count': 12}), \
                     patch.object(os, 'fdopen', side_effect=inspect_fd), \
                     patch.object(Path, 'open', new=inspect_path), \
                     patch('sys.stdout', new_callable=io.StringIO):
                    module.main()
            finally:
                os.umask(saved_umask)
            self.assertEqual(creation_modes, [0o600])
            self.assertEqual(stat.S_IMODE(output.stat().st_mode), 0o600)
            self.assertEqual(json.loads(output.read_text()), {'boot_count': 12})

    def test_output_refuses_existing_file(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'diagnostics.json'
            output.write_text('existing controlled test data')
            with self.assertRaises(FileExistsError):
                module.write_private_output(output, '{"replacement":true}\n')
            self.assertEqual(output.read_text(), 'existing controlled test data')

    @unittest.skipUnless(os.name == 'posix', 'POSIX symlink behavior')
    def test_output_refuses_symlink_without_touching_target(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / 'target.json'
            target.write_text('existing controlled test data')
            output = Path(directory) / 'diagnostics.json'
            output.symlink_to(target)
            with self.assertRaises(FileExistsError):
                module.write_private_output(output, '{"replacement":true}\n')
            self.assertTrue(output.is_symlink())
            self.assertEqual(target.read_text(), 'existing controlled test data')

    def test_locked_skips_kernel_body(self):
        commands = []
        def run(argv, **kwargs):
            commands.append(argv)
            if argv[-2:] == ['dumpsys', 'user']:
                text = 'Started users state: [0=RUNNING_LOCKED]'
            elif argv[-1] == 'persist.sys.timezone':
                text = 'America/Toronto'
            elif argv[-3:] == ['get', 'global', 'boot_count']:
                text = '12'
            else:
                text = ''
            return subprocess.CompletedProcess(argv, 0, text, 'PRIVATE_STDERR')
        with patch.object(module.subprocess, 'run', side_effect=run):
            result = module.collect('adb', 'test-device', True)
        self.assertEqual(result['kernel']['status'], 'requires_user_unlock')
        self.assertFalse(any('--print' in command for command in commands))
        self.assertNotIn('PRIVATE_STDERR', json.dumps(result))

    def test_command_failure_does_not_export_stderr(self):
        with patch.object(module.subprocess, 'run', return_value=subprocess.CompletedProcess([], 1, 'UNRELATED_DATA', 'PRIVATE_STDERR')):
            result = module.collect('adb', 'test-device', True)
        self.assertIsNone(result['boot_count'])
        self.assertNotIn('UNRELATED_DATA', json.dumps(result))
        self.assertNotIn('PRIVATE_STDERR', json.dumps(result))


if __name__ == '__main__':
    unittest.main()
