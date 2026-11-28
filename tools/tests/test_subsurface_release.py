import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[2]
SOURCE = REPO / 'app/src/main/cpp/waylandcomp/src/compositor.c'
FUNCTIONS = (
    'pending_release_free', 'pending_releases_forget_surface', 'pending_releases_cancel',
    'pending_releases_apply', 'sync_holder', 'pending_releases_rehold', 'on_pending_release_buffer_destroyed',
    'arm_release_timer', 'release_due_buffers', 'release_buffer', 'buffer_shown_elsewhere',
    'drop_dmabuf',
)


def extract_function(source, name):
    match = re.search(r'^static [^\n;]*?\b' + re.escape(name) + r'\([^;{]*\) \{$', source, re.MULTILINE)
    if match is None:
        raise AssertionError(f'Missing compositor function: {name}')
    cursor, depth = match.end(), 1
    while depth:
        depth += (source[cursor] == '{') - (source[cursor] == '}')
        cursor += 1
    return match.group(0)[:-2] + ';', source[match.start():cursor]


def function_body(source, name):
    return extract_function(source, name)[1]


class SubsurfaceReleaseTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        compiler = shutil.which(os.environ.get('CC', 'cc'))
        if compiler is None:
            raise unittest.SkipTest('A host C compiler is required')
        cls.source = SOURCE.read_text()
        start = cls.source.index('struct pending_release {')
        end = cls.source.index('\n};\n', start) + 4
        parts = [extract_function(cls.source, name) for name in FUNCTIONS]
        depth = re.search(r'^#define RELEASE_PACE_DEPTH \d+$', cls.source, re.MULTILINE).group(0)
        code = depth + '\n' + cls.source[start:end] + '\n'.join(p[0] for p in parts) + '\n' + '\n'.join(p[1] for p in parts) + '\n'
        cls.temp = tempfile.TemporaryDirectory(prefix='droiddeck-subsurface-release-')
        cls.addClassCleanup(cls.temp.cleanup)
        folder = Path(cls.temp.name)
        (folder / 'release.inc').write_text(code)
        cls.binary = folder / 'release-test'
        subprocess.run([compiler, '-std=c11', '-Wall', '-Werror', '-Wno-unused-function',
                        '-I', str(folder), '-I', str(REPO / 'app/src/main/cpp/waylandcomp/prebuilt/include'),
                        str(REPO / 'tools/tests/subsurface_release_harness.c'), '-o', str(cls.binary)],
                       check=True, capture_output=True, text=True)

    def check_case(self, case):
        result = subprocess.run([str(self.binary), case], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_buffer_moved_to_another_plane_is_released_once_after_the_parent_commit(self):
        self.check_case('moved')

    def test_buffer_attached_again_before_its_paced_release_is_released_once(self):
        self.check_case('reattached')

    def test_buffers_cycled_faster_than_their_paced_releases_are_released_once_each(self):
        self.check_case('cycled')

    def test_unpaced_sync_child_waits_for_the_parent_commit(self):
        self.check_case('unpaced')

    def test_desynchronized_child_is_released_without_a_parent_commit(self):
        self.check_case('desync')

    def test_toplevel_release_pacing_is_unchanged(self):
        self.check_case('toplevel')

    def test_client_that_never_waits_keeps_a_bounded_release_queue(self):
        self.check_case('never-waits')

    def test_sync_child_that_never_waits_keeps_a_bounded_release_queue(self):
        self.check_case('never-waits-sync')

    def test_fixed_pool_as_deep_as_the_cap_stays_held_to_the_limit(self):
        self.check_case('deep-pool')

    def test_held_release_is_sent_when_the_parent_is_destroyed(self):
        self.check_case('destroyed')

    def test_desynchronized_parent_releases_its_synchronized_child(self):
        self.check_case('nested')

    def test_set_desync_releases_what_the_old_parent_held(self):
        self.check_case('set-desync')

    def test_compositor_attach_and_commit_use_the_release_hold(self):
        self.assertIn('pending_releases_cancel(buffer);', function_body(self.source, 'take_dmabuf'))
        commit = function_body(self.source, 'surface_commit')
        self.assertIn('pending_releases_apply(s);', commit)
        self.assertLess(commit.index('take_dmabuf(s, db, buffer);'), commit.index('pending_releases_apply(s);'))
        self.assertLess(commit.index('buf_trace_attach(buffer);'), commit.index('if (s->role == ROLE_CURSOR'))
        self.assertIn('pending_releases_rehold(s);', function_body(self.source, 'subsurface_set_desync'))


if __name__ == '__main__':
    unittest.main()
