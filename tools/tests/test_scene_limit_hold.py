import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'app/src/main/cpp/waylandcomp/src/compositor.c'


def extract_function(source, name):
    match = re.search(r'^static int ' + re.escape(name) + r'\([^;]*?\) \{', source, re.MULTILINE)
    if match is None:
        raise AssertionError(f'Missing compositor function: {name}')
    cursor, depth = match.end(), 1
    while depth:
        depth += (source[cursor] == '{') - (source[cursor] == '}')
        cursor += 1
    return source[match.start():cursor]


HARNESS = r'''
#include <assert.h>
#include <stdint.h>
#include <stdio.h>

%s

#define MS 1000000LL

int main(void) {
    const int64_t hz120 = 8333333, hz60 = 16666667;
    assert(!scene_held_for_limit(100 * MS, 0, 30, 0, hz120));
    assert(!scene_held_for_limit(100 * MS, 90 * MS, 0, 0, hz120));
    assert(!scene_held_for_limit(100 * MS, 99 * MS, 30, 1, hz120));
    assert(scene_held_for_limit(100 * MS, 92 * MS, 30, 0, hz120));
    assert(scene_held_for_limit(100 * MS, 75 * MS, 30, 0, hz120));
    assert(!scene_held_for_limit(100 * MS, 70 * MS, 30, 0, hz120));
    assert(!scene_held_for_limit(100 * MS, 67 * MS, 30, 0, hz120));
    assert(scene_held_for_limit(100 * MS, 80 * MS, 30, 0, hz60));
    assert(!scene_held_for_limit(100 * MS, 75 * MS, 30, 0, hz60));
    assert(!scene_held_for_limit(100 * MS, 88 * MS, 120, 0, hz120));
    int renders = 0;
    int64_t last = 0;
    for (int64_t tick = 0; tick < 120; tick++) {
        int64_t now = 1000 * MS + tick * hz120;
        if (!scene_held_for_limit(now, last, 30, 0, hz120)) { renders++; last = now; }
    }
    assert(renders == 30);
    puts("PASS");
    return 0;
}
'''


class SceneLimitHoldTest(unittest.TestCase):
    def test_a_frame_limit_holds_scene_renders_to_its_rate_without_slowing_paced_frames(self):
        compiler = shutil.which(os.environ.get('CC', 'cc'))
        if compiler is None:
            self.skipTest('A host C compiler is required')
        harness = HARNESS % extract_function(SOURCE.read_text(), 'scene_held_for_limit')
        with tempfile.TemporaryDirectory(prefix='droiddeck-scene-hold-') as folder:
            path = Path(folder)
            (path / 'hold.c').write_text(harness)
            binary = path / 'hold-test'
            build = subprocess.run([compiler, '-std=c11', '-Wall', '-Wextra', '-Werror', '-O2',
                                    str(path / 'hold.c'), '-o', str(binary)],
                                   capture_output=True, text=True)
            self.assertEqual(0, build.returncode, build.stderr)
            result = subprocess.run([str(binary)], capture_output=True, text=True, timeout=10)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual('PASS', result.stdout.strip())


if __name__ == '__main__':
    unittest.main()
