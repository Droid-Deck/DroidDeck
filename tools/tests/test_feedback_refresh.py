import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

from test_output_refresh import SOURCE, extract_function


PROBE = r'''
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>

static atomic_int g_output_refresh_mhz;
static volatile int g_fps_limit;
static volatile int g_release_unpaced;

%s
%s

int main(int argc, char **argv) {
    atomic_store(&g_output_refresh_mhz, atoi(argv[1]));
    g_fps_limit = atoi(argv[2]);
    g_release_unpaced = atoi(argv[3]);
    printf("%%u\n", feedback_refresh_ns());
    return 0;
}
'''


@unittest.skipUnless(shutil.which('cc'), 'needs a C compiler')
class FeedbackRefreshTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        source = SOURCE.read_text()
        cls.tmp = tempfile.TemporaryDirectory()
        probe = Path(cls.tmp.name) / 'probe.c'
        probe.write_text(PROBE % (extract_function(source, 'nominal_refresh_ns'),
                                  extract_function(source, 'feedback_refresh_ns')))
        cls.binary = Path(cls.tmp.name) / 'probe'
        subprocess.run(['cc', '-std=c11', '-Wall', '-Werror', str(probe), '-o', str(cls.binary)], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def refresh(self, mhz, limit, unpaced=0):
        out = subprocess.run([str(self.binary), str(mhz), str(limit), str(unpaced)],
                             check=True, capture_output=True, text=True).stdout
        return int(out)

    def test_no_limit_reports_the_panel_refresh(self):
        self.assertEqual(self.refresh(120000, 0), 8333333)
        self.assertEqual(self.refresh(60000, 0), 16666666)

    def test_limit_below_the_panel_reports_the_limit_interval(self):
        self.assertEqual(self.refresh(120000, 60), 16666666)
        self.assertEqual(self.refresh(120000, 30), 33333333)
        self.assertEqual(self.refresh(90000, 45), 22222222)

    def test_limit_at_or_above_the_panel_keeps_the_panel_refresh(self):
        self.assertEqual(self.refresh(60000, 60), 16666666)
        self.assertEqual(self.refresh(60000, 144), 16666666)

    def test_unpaced_sessions_keep_the_panel_refresh(self):
        self.assertEqual(self.refresh(120000, 60, unpaced=1), 8333333)


if __name__ == '__main__':
    unittest.main()
