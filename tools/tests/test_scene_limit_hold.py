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
    match = re.search(r'^static int(?:64_t)? ' + re.escape(name) + r'\([^;]*?\) \{', source, re.MULTILINE)
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

@FUNCTIONS@

#define MS 1000000LL

static int run(int64_t refresh, int limit, int64_t jitter, int seconds) {
    int renders = 0;
    int64_t due = 0;
    for (int64_t tick = 0; tick * refresh < seconds * 1000 * MS; tick++) {
        int64_t now = 1000 * MS + tick * refresh + (tick % 2 ? jitter : -jitter);
        if (scene_held_for_limit(now, due, limit, 0)) continue;
        renders++;
        due = scene_next_due(now, due, limit);
    }
    return renders;
}

int main(void) {
    assert(!scene_held_for_limit(100 * MS, 0, 30, 0));
    assert(!scene_held_for_limit(100 * MS, 130 * MS, 0, 0));
    assert(!scene_held_for_limit(100 * MS, 130 * MS, 30, 1));
    assert(scene_held_for_limit(100 * MS, 130 * MS, 30, 0));
    assert(scene_held_for_limit(100 * MS, 102 * MS, 30, 0));
    assert(!scene_held_for_limit(100 * MS, 101 * MS, 30, 0));
    assert(!scene_held_for_limit(100 * MS, 90 * MS, 30, 0));
    assert(scene_next_due(100 * MS, 0, 0) == 0);
    assert(scene_next_due(100 * MS, 0, 25) == 140 * MS);
    assert(scene_next_due(101 * MS, 100 * MS, 25) == 140 * MS);
    assert(scene_next_due(200 * MS, 100 * MS, 25) == 240 * MS);
    const struct { int64_t refresh; int limit; int expected; } cases[] = {
        {8333333, 30, 300}, {8333333, 60, 600}, {16666667, 25, 250}, {16666667, 30, 300},
        {10000000, 30, 300}, {11111111, 30, 300}, {6944444, 40, 400}, {16666667, 120, 600},
    };
    for (unsigned i = 0; i < sizeof cases / sizeof cases[0]; i++) {
        int got = run(cases[i].refresh, cases[i].limit, 0, 10);
        if (got > cases[i].expected + 1 || got < cases[i].expected - 2) {
            fprintf(stderr, "refresh %lld limit %d: %d renders, expected %d\n",
                    (long long)cases[i].refresh, cases[i].limit, got, cases[i].expected);
            return 1;
        }
        got = run(cases[i].refresh, cases[i].limit, 300000, 10);
        if (got > cases[i].expected + 1 || got < cases[i].expected - 2) {
            fprintf(stderr, "jittered refresh %lld limit %d: %d renders, expected %d\n",
                    (long long)cases[i].refresh, cases[i].limit, got, cases[i].expected);
            return 1;
        }
    }
    int fallback = run(8 * MS, 30, 0, 10);
    assert(fallback <= 301 && fallback >= 290);
    puts("PASS");
    return 0;
}
'''


class SceneLimitHoldTest(unittest.TestCase):
    def test_a_frame_limit_holds_scene_renders_to_its_rate_without_slowing_paced_frames(self):
        compiler = shutil.which(os.environ.get('CC', 'cc'))
        if compiler is None:
            self.skipTest('A host C compiler is required')
        source = SOURCE.read_text()
        harness = HARNESS.replace('@FUNCTIONS@', '\n'.join(extract_function(source, name) for name in ('scene_held_for_limit', 'scene_next_due')))
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
