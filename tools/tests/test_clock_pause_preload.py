import os
from pathlib import Path
import queue
import shlex
import shutil
import struct
import subprocess
import tempfile
import threading
import unittest

PRELOAD = Path(__file__).resolve().parents[1] / "linuxfs/preload"


def clock_reset_command(control):
    script = (PRELOAD.parent / "overlay/usr/local/bin/droiddeck-session").read_text()
    start = script.index('if [ -z "${BL_INSIDE:-}" ]; then\n  if [ "${BL_ZRAM:-0}"')
    end = script.index("\nfi", start) + len("\nfi")
    return ('BL_INSIDE=; BL_ZRAM=0; ' + script[start:end]
            .replace("/tmp/.bl-clock", shlex.quote(str(control)))
            .replace("/tmp/.bl-zram", shlex.quote(str(control.parent / "zram"))))

PROGRAM = r"""
#define _GNU_SOURCE
#include <linux/futex.h>
#include <pthread.h>
#include <semaphore.h>
#include <stdint.h>
#include <stdlib.h>
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

static unsigned long long memory_probe(void) {
  static volatile unsigned char memory[4 * 1024 * 1024];
  unsigned long long checksum = 0;
  for (size_t i = 0; i < sizeof(memory); i += 4096) memory[i] = (unsigned char)(i / 4096 + 1);
  for (size_t i = 0; i < sizeof(memory); i += 4096) checksum += memory[i];
  return checksum;
}

int main(void) {
  const int64_t wait = 60000000LL;
  printf("gap %lld\n", (long long)((ns(CLOCK_BOOTTIME) - ns(CLOCK_MONOTONIC)) / 1000000LL));
  struct timespec raw;
  syscall(SYS_clock_gettime, CLOCK_MONOTONIC, &raw);
  printf("rawgap %lld\n", (long long)((ns(CLOCK_BOOTTIME) - ((int64_t)raw.tv_sec * 1000000000LL + raw.tv_nsec)) / 1000000LL));
  printf("memory %llu\n", memory_probe());
  if (getenv("CLOCK_PAUSE_WAIT")) { puts("ready"); fflush(stdout); getchar(); }
  if (getenv("CLOCK_PAUSE_WAIT")) {
    printf("livegap %lld\n", (long long)((ns(CLOCK_BOOTTIME) - ns(CLOCK_MONOTONIC)) / 1000000LL));
    printf("livememory %llu\n", memory_probe());
  }

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

    def test_program_without_preload_keeps_the_real_clock(self):
        plain = self.run_program(self.other, 0, preload=False)
        paused = self.run_program(self.other, SHIFT_MS, preload=False)
        self.assertAlmostEqual(paused["gap"], plain["gap"], delta=50)
        for name in ("cond_mono", "clockwait", "sem", "nanosleep", "timerfd", "futex"):
            self.assertGreaterEqual(paused[name], 50, name)
            self.assertLess(paused[name], 1000, name)

    def test_every_session_program_stops_monotonic_clock_and_keeps_memory_access(self):
        plain = self.run_program(self.other, 0)
        paused = self.run_program(self.other, SHIFT_MS)
        self.assertAlmostEqual(paused["gap"] - plain["gap"], SHIFT_MS, delta=50)
        self.assertAlmostEqual(paused["rawgap"] - plain["rawgap"], SHIFT_MS, delta=50)
        self.assertEqual(plain["memory"], 130560)
        self.assertEqual(paused["memory"], 130560)

    def test_running_session_program_observes_pause_offset_update(self):
        self.check_running_offset_update(False)

    def test_session_reset_preserves_live_guest_mapping(self):
        self.check_running_offset_update(True)

    def check_running_offset_update(self, reset):
        self.control.write_bytes(struct.pack("<q", 0))
        env = dict(os.environ)
        env["LD_PRELOAD"] = str(self.preload)
        env["CLOCK_PAUSE_WAIT"] = "1"
        with tempfile.TemporaryFile(mode="w+") as errors:
            process = subprocess.Popen([str(self.other)], env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                       stderr=errors, text=True)
            lines = queue.Queue()

            def read_handshake():
                for _ in range(4):
                    line = process.stdout.readline()
                    lines.put(line)
                    if not line:
                        break

            threading.Thread(target=read_handshake, daemon=True).start()
            try:
                handshake = []
                for _ in range(4):
                    try:
                        line = lines.get(timeout=5)
                    except queue.Empty:
                        line = ""
                    if not line:
                        if process.poll() is None:
                            process.kill()
                        process.wait(timeout=5)
                        errors.seek(0)
                        self.fail(f"child handshake timed out or ended early (exit {process.returncode}): {errors.read()}")
                    handshake.append(line.strip())
                self.assertEqual(handshake[3], "ready")
                self.assertTrue(handshake[0].startswith("gap "))
                initial_gap = int(handshake[0].split()[1])
                rawgap = handshake[1]
                memory = handshake[2]
                self.assertTrue(rawgap.startswith("rawgap "))
                self.assertEqual(memory, "memory 130560")
                if reset:
                    inode = self.control.stat().st_ino
                    subprocess.run(["bash", "-c", clock_reset_command(self.control)],
                                   check=True, timeout=5)
                    self.assertEqual(self.control.stat().st_ino, inode)
                    self.assertEqual(self.control.read_bytes(), struct.pack("<q", 0))
                with self.control.open("r+b") as control:
                    control.write(struct.pack("<q", SHIFT_MS * 1000000))
                    control.flush()
                process.stdin.write("\n")
                process.stdin.flush()
                output, _ = process.communicate(timeout=5)
                errors.seek(0)
                self.assertEqual(process.returncode, 0, errors.read())
                values = dict(line.split() for line in output.splitlines())
                self.assertAlmostEqual(int(values["livegap"]) - initial_gap, SHIFT_MS, delta=50)
                self.assertEqual(int(values["livememory"]), 130560)
            finally:
                if process.poll() is None:
                    process.kill()
                    process.communicate()


class ClockResetCommandTest(unittest.TestCase):
    def test_reset_keeps_paths_with_spaces_and_shell_syntax_as_single_arguments(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            folder = root / "session dir; touch injected $(touch substituted) 'quoted'"
            folder.mkdir()
            control = folder / "clock"
            control.write_bytes(struct.pack("<q", 10_000_000_000) + b"tail")
            (folder / "zram").write_text("1")
            bystander = root / "session"
            bystander.write_text("keep")
            inode = control.stat().st_ino
            subprocess.run(["bash", "-c", clock_reset_command(control)], cwd=root, check=True, timeout=5)
            self.assertEqual(control.stat().st_ino, inode)
            self.assertEqual(control.read_bytes(), struct.pack("<q", 0) + b"tail")
            self.assertFalse((folder / "zram").exists())
            self.assertEqual(bystander.read_text(), "keep")
            self.assertEqual(sorted(path.name for path in root.iterdir()), sorted([folder.name, bystander.name]))


if __name__ == "__main__":
    unittest.main()
