import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))
from proot_profile import report

PROGRAM = r'''
#define _POSIX_C_SOURCE 200809L
#define PR_NB_SYSNUM 2
#define PR_void 0
static const char *stringify_sysnum(unsigned nr) { return nr ? "openat" : "void"; }
#include "profile.h"
int main(void) {
    errno = EBUSY;
    if (!dd_profile_enabled()) return errno != EBUSY;
    if (errno != EBUSY) return 1;
    for (int i = 0; i < 40; i++) {
        uint64_t wall = dd_profile_clock(CLOCK_MONOTONIC);
        uint64_t cpu = dd_profile_clock(CLOCK_THREAD_CPUTIME_ID);
        struct timespec wait = {0, 2000000};
        nanosleep(&wait, 0);
        errno = ENOENT;
        dd_profile_record(1, 1, 123, wall, cpu);
        if (errno != ENOENT) return 2;
        if (i == 9) dd_profile_flush();
    }
    dd_profile_next = 0;
    uint64_t wall = dd_profile_clock(CLOCK_MONOTONIC);
    uint64_t cpu = dd_profile_clock(CLOCK_THREAD_CPUTIME_ID);
    struct timespec wait = {0, 2000000};
    nanosleep(&wait, 0);
    dd_profile_record(1, 0, 123, wall, cpu);
    return 0;
}
'''


class ProotProfileTest(unittest.TestCase):
    def test_snapshots_replace_instead_of_adding_and_tracers_remain_separate(self):
        with tempfile.TemporaryDirectory() as work:
            path = Path(work) / 'session.log'
            records = [dict(type='total', tracer=1, id=2, syscall='statx', stage='enter',
                            calls=n, wall_ns=n * 100, cpu_ns=n * 10, max_ns=100) for n in (3, 8)]
            records.append(dict(records[-1], tracer=2, calls=2))
            records += [dict(type='total', tracer=1, id=3, syscall='openat', stage='enter',
                             calls=1, wall_ns=1000, cpu_ns=1000, max_ns=1000)]
            path.write_text('unrelated log\n' + ''.join('prefix proot-profile ' + json.dumps(r) + '\n' for r in records)
                            + 'proot-profile {truncated\n' + 'proot-profile []\n')
            result = report(path)
            self.assertEqual(11, result['handlerCalls'])
            self.assertEqual(3, result['tracers'])
            self.assertEqual(2, result['malformedRecords'])
            self.assertEqual('statx', result['totals'][0]['syscall'])
            self.assertEqual('openat', report(path, 'cpu')['totals'][0]['syscall'])

    def test_empty_logs_do_not_claim_profiling_ran(self):
        with tempfile.TemporaryDirectory() as work:
            path = Path(work) / 'session.log'
            path.write_text('ordinary log\n')
            self.assertFalse(report(path)['hasCounters'])

    @unittest.skipUnless(shutil.which('cc'), 'C compiler required')
    def test_real_clocks_bounded_samples_errno_and_periodic_counters(self):
        with tempfile.TemporaryDirectory() as work:
            source = Path(work) / 'profile.c'
            binary = Path(work) / 'profile'
            source.write_text(PROGRAM)
            subprocess.run(['cc', '-Wall', '-Wextra', '-Werror', '-std=c99', '-I', str(TOOLS / 'proot'),
                            str(source), '-o', str(binary)], check=True)
            for setting in ('0', ''):
                result = subprocess.run([binary], env=dict(os.environ, PROOT_PROFILE=setting), capture_output=True, check=True)
                self.assertEqual(b'', result.stderr)
            result = subprocess.run([binary], env=dict(os.environ, PROOT_PROFILE='1', PROOT_PROFILE_SLOW_US='1'),
                                    capture_output=True, check=True)
            rows = [json.loads(line.split(b'proot-profile ', 1)[1]) for line in result.stderr.splitlines()]
            self.assertEqual(33, sum(row['type'] == 'slow' for row in rows))
            self.assertEqual(32, sum(row['type'] == 'slow' and row['stage'] == 'enter' for row in rows))
            self.assertGreater(sum(row['type'] == 'total' for row in rows), 2)
            log = Path(work) / 'session.log'
            log.write_bytes(result.stderr)
            counters = report(log)
            self.assertEqual(41, counters['handlerCalls'])
            self.assertGreater(counters['handlerWallNs'], counters['handlerCpuNs'])
            self.assertEqual(40, counters['totals'][0]['calls'])
