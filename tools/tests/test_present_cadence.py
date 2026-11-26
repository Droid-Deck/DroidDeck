import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'app/src/main/cpp/waylandcomp/src/present_cadence.c'
HARNESS = ROOT / 'tools/tests/present_cadence_harness.c'
COMPOSITOR = ROOT / 'app/src/main/cpp/waylandcomp/src/compositor.c'


def extract(source, name):
    match = re.search(r'^static [^;{]*\b' + re.escape(name) + r'\(void\) \{', source, re.MULTILINE)
    if match is None:
        raise AssertionError(f'Missing compositor function: {name}')
    cursor, depth = match.end(), 1
    while depth:
        depth += (source[cursor] == '{') - (source[cursor] == '}')
        cursor += 1
    return source[match.start():cursor]


YIELD_HARNESS = r'''
#include <assert.h>
#include <stdarg.h>
#include <stdatomic.h>
#include <stdio.h>
#include <string.h>

static int g_bfi_running, framegen, cardboard, quest, layer_on = -1, logs;
static atomic_int g_stream_on;
static char last_log[256];
static atomic_int g_present_cadence = 1;
static atomic_int g_game_on_other_display;
static int g_cadence_running = -1;
static const char *g_cadence_blocked;
static int vkp_framegen_active(void) { return framegen; }
static int vkp_cardboard_active(void) { return cardboard; }
static int vkp_quest_layer_presentation(void) { return quest; }
static void sc_layer_set_present_cadence(int on) { layer_on = on; }
static void droiddeck_log(const char *tag, const char *fmt, ...) {
    va_list ap; va_start(ap, fmt); vsnprintf(last_log, sizeof last_log, fmt, ap); va_end(ap);
    (void)tag; logs++;
}

@FUNCTIONS@

int main(void) {
    cadence_update();
    assert(layer_on == 1);
    int *blockers[] = {&g_bfi_running, &framegen, &cardboard, &quest};
    for (unsigned i = 0; i < sizeof blockers / sizeof blockers[0]; i++) {
        *blockers[i] = 1;
        cadence_update();
        assert(layer_on == 0);
        *blockers[i] = 0;
        cadence_update();
        assert(layer_on == 1);
    }
    atomic_store(&g_stream_on, 1);
    cadence_update();
    assert(layer_on == 0 && strstr(last_log, "pacing off") != NULL);
    const int before = logs;
    cadence_update();
    assert(logs == before);
    atomic_store(&g_stream_on, 0);
    atomic_store(&g_game_on_other_display, 1);
    cadence_update();
    assert(layer_on == 0 && strstr(last_log, "another screen") != NULL);
    atomic_store(&g_game_on_other_display, 0);
    cadence_update();
    assert(layer_on == 1);
    framegen = 1;
    cadence_update();
    assert(layer_on == 0);
    atomic_store(&g_present_cadence, 0);
    framegen = 0;
    cadence_update();
    assert(layer_on == 0);
    atomic_store(&g_present_cadence, 1);
    cadence_update();
    assert(layer_on == 1);
    puts("PASS");
    return 0;
}
'''


@unittest.skipUnless(shutil.which('cc'), 'needs a C compiler')
class PresentCadenceTest(unittest.TestCase):
    def test_game_layer_frames_keep_their_cadence_through_copy_jitter(self):
        with tempfile.TemporaryDirectory(prefix='droiddeck-cadence-') as folder:
            binary = Path(folder) / 'cadence-test'
            build = subprocess.run(['cc', '-std=c11', '-Wall', '-Wextra', '-Werror', '-O2', '-I', str(SOURCE.parent),
                                    str(HARNESS), str(SOURCE), '-o', str(binary)], capture_output=True, text=True)
            self.assertEqual(0, build.returncode, build.stderr)
            result = subprocess.run([str(binary)], capture_output=True, text=True, timeout=30)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual('PASS', result.stdout.strip())

    def test_pacing_yields_to_presentation_that_paces_every_refresh(self):
        source = COMPOSITOR.read_text()
        functions = '\n'.join(extract(source, name) for name in ('cadence_blocked', 'cadence_update'))
        with tempfile.TemporaryDirectory(prefix='droiddeck-cadence-yield-') as folder:
            path = Path(folder)
            (path / 'yield.c').write_text(YIELD_HARNESS.replace('@FUNCTIONS@', functions))
            binary = path / 'yield-test'
            build = subprocess.run(['cc', '-std=c11', '-Wall', '-Wextra', '-Werror', '-O2', str(path / 'yield.c'),
                                    '-o', str(binary)], capture_output=True, text=True)
            self.assertEqual(0, build.returncode, build.stderr)
            result = subprocess.run([str(binary)], capture_output=True, text=True, timeout=10)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual('PASS', result.stdout.strip())


if __name__ == '__main__':
    unittest.main()
