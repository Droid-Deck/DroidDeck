import os
from pathlib import Path
import shutil
import struct
import subprocess
import tempfile
import unittest

PRELOAD = Path(__file__).resolve().parents[1] / "linuxfs/preload"

PROGRAM = r"""
#define _GNU_SOURCE
#include <linux/futex.h>
#include <pthread.h>
#include <semaphore.h>
#include <stdint.h>
#include <stdio.h>
#include <sys/syscall.h>
#include <sys/timerfd.h>
#include <time.h>
#include <unistd.h>

static int64_t ns(clockid_t clock) {
  struct timespec ts;
  clock_gettime(clock, &ts);
  return (int64_t)ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

static struct timespec in(clockid_t clock, int64_t delay) {
  int64_t t = ns(clock) + delay;
  struct timespec ts = {(time_t)(t / 1000000000LL), (long)(t % 1000000000LL)};
  return ts;
}

static int64_t start;
static void begin(void) { start = ns(CLOCK_BOOTTIME); }
static void end(const char *name) { printf("%s %lld\n", name, (long long)((ns(CLOCK_BOOTTIME) - start) / 1000000LL)); }

int main(void) {
  const int64_t wait = 60000000LL;
  printf("gap %lld\n", (long long)((ns(CLOCK_BOOTTIME) - ns(CLOCK_MONOTONIC)) / 1000000LL));
  struct timespec raw;
  syscall(SYS_clock_gettime, CLOCK_MONOTONIC, &raw);
  printf("rawgap %lld\n", (long long)((ns(CLOCK_BOOTTIME) - ((int64_t)raw.tv_sec * 1000000000LL + raw.tv_nsec)) / 1000000LL));

  pthread_mutex_t mutex = PTHREAD_MUTEX_INITIALIZER;
  pthread_condattr_t attr;
  pthread_cond_t mono, real;
  pthread_condattr_init(&attr);
  pthread_condattr_setclock(&attr, CLOCK_MONOTONIC);
  pthread_cond_init(&mono, &attr);
  pthread_cond_init(&real, NULL);
  struct timespec ts;

  pthread_mutex_lock(&mutex);
  ts = in(CLOCK_MONOTONIC, wait); begin(); pthread_cond_timedwait(&mono, &mutex, &ts); end("cond_mono");
  ts = in(CLOCK_REALTIME, wait); begin(); pthread_cond_timedwait(&real, &mutex, &ts); end("cond_real");
  ts = in(CLOCK_MONOTONIC, wait); begin(); pthread_cond_clockwait(&real, &mutex, CLOCK_MONOTONIC, &ts); end("clockwait");
  pthread_mutex_unlock(&mutex);

  sem_t sem;
  sem_init(&sem, 0, 0);
  ts = in(CLOCK_MONOTONIC, wait); begin(); sem_clockwait(&sem, CLOCK_MONOTONIC, &ts); end("sem");

  ts = in(CLOCK_MONOTONIC, wait); begin(); clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME, &ts, NULL); end("nanosleep");

  int fd = timerfd_create(CLOCK_MONOTONIC, 0);
  struct itimerspec it = {{0, 0}, in(CLOCK_MONOTONIC, wait)};
  uint64_t ticks;
  timerfd_settime(fd, TFD_TIMER_ABSTIME, &it, NULL);
  begin(); if (read(fd, &ticks, sizeof(ticks)) < 0) ticks = 0; end("timerfd");

  static uint32_t word;
  ts = in(CLOCK_MONOTONIC, wait); begin();
  syscall(SYS_futex, &word, FUTEX_WAIT_BITSET | FUTEX_PRIVATE_FLAG, 0, &ts, NULL, FUTEX_BITSET_MATCH_ANY);
  end("futex");
  return 0;
}
"""

SHIFT_MS = 10000


@unittest.skipUnless(shutil.which("cc"), "needs a C compiler")
class ClockPausePreloadTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.dir = Path(cls.temp.name)
        cls.control = cls.dir / "clock"
        build = cls.dir / "build"
        build.mkdir()
        (build / "program.c").write_text(PROGRAM)
        cls.preload = build / "libblsession.so"
        subprocess.run(["cc", "-shared", "-fPIC", "-O2", "-Wall", "-pthread", "-DBL_CLOCK_ANY_ARCH",
                        '-DCP_CTL="%s"' % cls.control, "-o", str(cls.preload),
                        *map(str, sorted(PRELOAD.glob("*.c"))), "-ldl"], check=True)
        cls.shell = build / "plasmashell"
        subprocess.run(["cc", "-O2", "-pthread", "-o", str(cls.shell), str(build / "program.c")], check=True)
        cls.other = build / "kwin_wayland"
        shutil.copy(cls.shell, cls.other)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def run_program(self, program, shift_ms, preload=True):
        self.control.write_bytes(struct.pack("<q", shift_ms * 1000000))
        env = dict(os.environ)
        if preload:
            env["LD_PRELOAD"] = str(self.preload)
        result = subprocess.run([str(program)], env=env, capture_output=True, text=True, check=True, timeout=30)
        return {name: int(value) for name, value in (line.split() for line in result.stdout.splitlines())}

    def test_the_shell_sees_monotonic_time_stopped_for_the_pause(self):
        plain = self.run_program(self.shell, 0)
        paused = self.run_program(self.shell, SHIFT_MS)
        self.assertAlmostEqual(paused["gap"] - plain["gap"], SHIFT_MS, delta=50)
        self.assertAlmostEqual(paused["rawgap"] - plain["rawgap"], SHIFT_MS, delta=50)

    def test_timed_waits_last_as_long_as_asked_after_a_pause(self):
        paused = self.run_program(self.shell, SHIFT_MS)
        for name in ("cond_mono", "cond_real", "clockwait", "sem", "nanosleep", "timerfd", "futex"):
            self.assertGreaterEqual(paused[name], 50, name)
            self.assertLess(paused[name], 1000, name)

    def test_other_programs_keep_the_real_clock(self):
        plain = self.run_program(self.other, 0, preload=False)
        paused = self.run_program(self.other, SHIFT_MS)
        self.assertAlmostEqual(paused["gap"], plain["gap"], delta=50)
        for name in ("cond_mono", "clockwait", "sem", "nanosleep", "timerfd", "futex"):
            self.assertGreaterEqual(paused[name], 50, name)
            self.assertLess(paused[name], 1000, name)


if __name__ == "__main__":
    unittest.main()
