import importlib.util
from pathlib import Path
import struct
import tempfile
import unittest
from unittest import mock


spec = importlib.util.spec_from_file_location(
    'prepare_windows_runtime', Path(__file__).resolve().parents[1] / 'prepare_windows_runtime.py')
prepare = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prepare)


class WindowsRuntimePreparationTest(unittest.TestCase):
    def test_archive_paths_cannot_escape_destination(self):
        for name in ('../outside', '/absolute', 'dir/../../outside', 'C:/absolute', 'dir\\outside', '.'):
            with self.subTest(name=name), self.assertRaises(ValueError):
                prepare.validate_relative(name)

    def test_tracked_outputs_cannot_be_overwritten(self):
        with mock.patch.object(prepare, 'git', return_value=b'tracked/file\n'):
            with self.assertRaisesRegex(ValueError, 'overwrite tracked'):
                prepare.checked_output('tracked/file')

    def test_rebuilt_libraries_must_match_each_architecture(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'libblsession.so'
            elf = bytearray(64)
            elf[:4] = b'\x7fELF'
            elf[5] = 1
            elf[18:20] = struct.pack('<H', 183)
            path.write_bytes(elf)
            self.assertEqual(prepare.validate_rebuilt(path, 183), elf)
            with self.assertRaisesRegex(ValueError, 'wrong ELF architecture'):
                prepare.validate_rebuilt(path, 62)
            path.write_bytes(b'not an ELF')
            with self.assertRaisesRegex(ValueError, 'wrong ELF architecture'):
                prepare.validate_rebuilt(path, 183)

    def test_new_committed_native_changes_reject_donor_reuse(self):
        with mock.patch.object(prepare, 'git', return_value=prepare.DONOR_TREE.encode()), \
                mock.patch.object(prepare, 'changed', return_value={'tools/vr/src/presenter.cpp'}):
            with self.assertRaisesRegex(ValueError, 'Sources changed since'):
                prepare.check_sources()

    def test_current_helpers_are_allowed_only_when_restaged_from_source(self):
        helpers = prepare.LOCAL_SESSION_HELPERS
        sources = {prepare.ROOT / path: 'assets/linuxfs/' + path.split('/overlay/')[1]
                   for path in helpers}
        for local_changes in (set(), helpers):
            with self.subTest(local_changes=local_changes), \
                    mock.patch.object(prepare, 'source_assets', return_value=sources), \
                    mock.patch.object(prepare, 'changed', side_effect=[helpers, local_changes, helpers]), \
                    mock.patch.object(prepare, 'git', side_effect=[prepare.DONOR_TREE.encode(), b'']):
                classifications = prepare.check_sources()
                self.assertEqual(set(classifications), helpers)
                self.assertTrue(all('restaged from current source' in action
                                    for action in classifications.values()))
        with mock.patch.object(prepare, 'source_assets', return_value={}), \
                mock.patch.object(prepare, 'changed', return_value=helpers), \
                mock.patch.object(prepare, 'git', return_value=prepare.DONOR_TREE.encode()):
            with self.assertRaisesRegex(ValueError, 'Sources changed since'):
                prepare.check_sources()

    def test_cardboard_changes_are_compiled_by_gradle_not_reused_from_donor(self):
        changed = {'app/src/main/cpp/waylandcomp/src/cardboard.c'}
        with mock.patch.object(prepare, 'changed', side_effect=[changed, changed, changed]), \
                mock.patch.object(prepare, 'git', side_effect=[prepare.DONOR_TREE.encode(), b'']):
            classifications = prepare.check_sources()
            self.assertEqual(set(classifications), changed)
            self.assertIn('compiled by Gradle; not imported', next(iter(classifications.values())))

    def test_other_helper_changes_still_need_an_audit(self):
        with mock.patch.object(prepare, 'git', return_value=prepare.DONOR_TREE.encode()), \
                mock.patch.object(prepare, 'changed', return_value={
                    'tools/linuxfs/overlay/usr/local/bin/droiddeck-session'}):
            with self.assertRaisesRegex(ValueError, 'Sources changed since'):
                prepare.check_sources()

    def test_uncommitted_native_changes_reject_donor_reuse(self):
        with mock.patch.object(prepare, 'git', side_effect=[prepare.DONOR_TREE.encode(), b'']), \
                mock.patch.object(prepare, 'changed', side_effect=[set(), {'tools/linuxfs/preload/net.c'}]):
            with self.assertRaisesRegex(ValueError, 'Unreviewed runtime'):
                prepare.check_sources()

    def test_wrong_apk_hash_fails_before_replacement_or_output_access(self):
        with tempfile.TemporaryDirectory() as folder:
            apk = Path(folder) / 'incorrect.apk'
            apk.write_bytes(b'incorrect donor')
            with mock.patch.object(prepare, 'check_sources', return_value={}), \
                    mock.patch.object(prepare, 'read_gamescope') as read_gamescope:
                with self.assertRaisesRegex(ValueError, 'Donor APK checksum mismatch'):
                    prepare.prepare(apk, Path(folder) / 'missing', Path(folder) / 'missing.tzst')
                read_gamescope.assert_not_called()

    def test_sdk_bridge_is_compiled_locally_while_other_untracked_native_files_fail(self):
        with mock.patch.object(prepare, 'source_assets', return_value={}), \
                mock.patch.object(prepare, 'changed', side_effect=[set(), {'app/src/main/cpp/CMakeLists.txt'}, set()]), \
                mock.patch.object(prepare, 'git', side_effect=[prepare.DONOR_TREE.encode(),
                    b'app/src/main/cpp/viture/sdk_bridge.cpp\0']):
            self.assertEqual({}, prepare.check_sources())
        with mock.patch.object(prepare, 'changed', side_effect=[set(), set()]), \
                mock.patch.object(prepare, 'git', side_effect=[prepare.DONOR_TREE.encode(),
                    b'app/src/main/cpp/unreviewed.cpp\0']):
            with self.assertRaisesRegex(ValueError, 'Unreviewed runtime'):
                prepare.check_sources()


if __name__ == '__main__':
    unittest.main()
