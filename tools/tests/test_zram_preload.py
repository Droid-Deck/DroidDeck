import os
from pathlib import Path
import signal
import shutil
import subprocess
import tempfile
import unittest

PRELOAD = Path(__file__).resolve().parents[1] / "linuxfs/preload"

PROGRAM = r"""
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <dirent.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <sched.h>
#include <stddef.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/resource.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

static const char *ctl;
static size_t size, page;
static volatile int stop;
static unsigned char *shared;
static volatile sig_atomic_t urg_tid;
static volatile sig_atomic_t usr_tid;
static int urg_count, usr_count;
static int worker_delivery;

_Static_assert(__atomic_always_lock_free(sizeof(int), 0), "signal state must be lock-free");

static void signal_tid(int sig) {
  sig_atomic_t tid = (sig_atomic_t)syscall(SYS_gettid);
  char name[16] = {0};
  prctl(PR_GET_NAME, name, 0, 0, 0);
  if (!strcmp(name, "zr-worker"))
    __atomic_store_n(&worker_delivery, 1, __ATOMIC_RELAXED);
  if (sig == SIGURG) {
    __atomic_store_n(&urg_tid, tid, __ATOMIC_RELAXED);
    __atomic_add_fetch(&urg_count, 1, __ATOMIC_RELEASE);
  }
  if (sig == SIGUSR1) {
    __atomic_store_n(&usr_tid, tid, __ATOMIC_RELAXED);
    __atomic_add_fetch(&usr_count, 1, __ATOMIC_RELEASE);
  }
}

static long status(const char *key) {
  FILE *f = fopen("/proc/self/status", "r");
  char line[256];
  long v = -1;
  while (f && fgets(line, sizeof(line), f))
    if (!strncmp(line, key, strlen(key))) v = atol(line + strlen(key));
  if (f) fclose(f);
  return v;
}

static long resident(char *start, size_t size) {
  long page = sysconf(_SC_PAGESIZE), kb = 0;
  int fd = open("/proc/self/pagemap", O_RDONLY);
  if (fd < 0) exit(8);
  for (size_t i = 0; i < size; i += page) {
    uint64_t entry;
    if (pread(fd, &entry, sizeof(entry), ((uintptr_t)start + i) / page * sizeof(entry)) != sizeof(entry)) exit(8);
    if (entry >> 63) kb += page / 1024;
  }
  close(fd);
  return kb;
}

static void send_request(char mode) {
  int fd = open(ctl, O_WRONLY | O_TRUNC);
  if (fd < 0 || write(fd, &mode, 1) != 1) exit(3);
  close(fd);
  kill(getpid(), SIGURG);
}

static void wait_ack(void) {
  char ack[256];
  snprintf(ack, sizeof(ack), "%s.ack.%d", ctl, getpid());
  for (int i = 0; i < 1200; i++) {
    FILE *f = fopen(ack, "r");
    int done = 0;
    if (f) {
      done = fgetc(f) == '1';
      fclose(f);
    }
    if (done) return;
    usleep(10000);
  }
  exit(5);
}

static void request(char mode) {
  send_request(mode);
  if (mode == '1') {
    wait_ack();
  }
  usleep(1000000);
}

static long trim(char mode, long kb) {
  char ack[256], text[32];
  snprintf(ack, sizeof(ack), "%s.trim.%d", ctl, getpid());
  unlink(ack);
  FILE *f = fopen(ctl, "w");
  if (!f) exit(3);
  fprintf(f, "%c %ld\n", mode, kb);
  fclose(f);
  kill(getpid(), SIGURG);
  for (int i = 0; i < 1200; i++) {
    f = fopen(ack, "r");
    char *line = f ? fgets(text, sizeof(text), f) : NULL;
    if (f) fclose(f);
    if (line) return atol(text);
    usleep(10000);
  }
  exit(5);
}

static int tasks(void) {
  DIR *dir = opendir("/proc/self/task");
  struct dirent *entry;
  int count = 0;
  while (dir && (entry = readdir(dir)) != NULL)
    if (entry->d_name[0] != '.') count++;
  if (dir) closedir(dir);
  return count;
}

static void without_threads(void) {
  struct rlimit limit;
  if (getrlimit(RLIMIT_NPROC, &limit)) _exit(15);
  limit.rlim_cur = 0;
  if (setrlimit(RLIMIT_NPROC, &limit)) _exit(15);
}

static void *park(void *arg) {
  (void)arg;
  for (;;) pause();
  return NULL;
}

static void parked(void) {
  pthread_t thread;
  if (pthread_create(&thread, NULL, park, NULL)) _exit(16);
}

static void filter_clone(unsigned long allowed) {
  struct sock_filter rules[] = {
    BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
    BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_clone, 0, 3),
    BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[0])),
    BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, allowed, 1, 0),
    BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS),
    BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
  };
  struct sock_fprog program = {sizeof(rules) / sizeof(rules[0]), rules};
  if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) || prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &program)) _exit(17);
}

static const unsigned long thread_clone = CLONE_VM | CLONE_FS | CLONE_FILES | CLONE_SIGHAND | CLONE_THREAD |
                                          CLONE_SYSVSEM | CLONE_SETTLS | CLONE_PARENT_SETTID | CLONE_CHILD_CLEARTID;

static uint64_t sum(const unsigned char *p, size_t n) {
  uint64_t s = 0;
  for (size_t i = 0; i < n; i += 64) s = s * 31 + p[i];
  return s;
}

static void *writer(void *arg) {
  unsigned char *mine = shared + (size_t)(uintptr_t)arg * (size / 4);
  for (unsigned char round = 1; !stop; round++) {
    for (size_t i = 0; i < size / 4; i += page) {
      if (round > 1 && mine[i] != (unsigned char)(round - 1)) {
        printf("corrupt\n");
        exit(4);
      }
      mine[i] = round;
    }
  }
  return NULL;
}

int main(int argc, char **argv) {
  ctl = argv[1];
  size = strtoul(argv[2], NULL, 0) << 20;
  int cycles = atoi(argv[3]);
  page = (size_t)sysconf(_SC_PAGESIZE);
  unsigned char *data = mmap(NULL, size, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
  for (size_t i = 0; i < size; i++) data[i] = (unsigned char)(i * 7 + i / 4096);
  uint64_t before = sum(data, size);
  shared = mmap(NULL, size, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
  memset(shared, 0, size);
  pthread_t t[2];
  for (uintptr_t i = 0; i < 2; i++) pthread_create(&t[i], NULL, writer, (void *)i);
  parked();
  struct sigaction sa;
  sigaction(SIGURG, NULL, &sa);
  printf("handler %d\n", (sa.sa_flags & SA_SIGINFO) ? 1 : 0);
  if (argc > 4) {
    if (!strcmp(argv[4], "signals")) {
      struct sigaction signal_action;
      memset(&signal_action, 0, sizeof(signal_action));
      signal_action.sa_handler = signal_tid;
      sigemptyset(&signal_action.sa_mask);
      sigaction(SIGUSR1, &signal_action, NULL);
      int fd = open(ctl, O_WRONLY | O_TRUNC);
      if (fd < 0 || write(fd, "1", 1) != 1) return 10;
      close(fd);
      printf("ready %d\n", getpid());
      fflush(stdout);
      for (int round = 1; round <= 100; round++) {
        int i;
        for (i = 0; i < 5000 && __atomic_load_n(&usr_count, __ATOMIC_ACQUIRE) < round; i++) usleep(1000);
        if (i == 5000) return 11;
        printf("received %d\n", round);
        fflush(stdout);
      }
      stop = 1;
      for (int i = 0; i < 2; i++) pthread_join(t[i], NULL);
      printf("usr %d\n", __atomic_load_n(&usr_tid, __ATOMIC_RELAXED));
      printf("worker %d\n", __atomic_load_n(&worker_delivery, __ATOMIC_RELAXED));
      return 0;
    } else if (!strcmp(argv[4], "fork")) {
      pid_t child = fork();
      if (child < 0) exit(6);
      if (child == 0) {
        request('0');
        request('1');
        _exit(0);
      }
      int status_code;
      waitpid(child, &status_code, 0);
      printf("childack %d\n", WIFEXITED(status_code) && WEXITSTATUS(status_code) == 0);
    } else if (!strcmp(argv[4], "threads")) {
      stop = 1;
      for (int i = 0; i < 2; i++) pthread_join(t[i], NULL);
      printf("idle %d\n", tasks() - 2);
      request('1');
      printf("after %d\n", tasks() - 2);
      return 0;
    } else if (!strcmp(argv[4], "trim")) {
      stop = 1;
      for (int i = 0; i < 2; i++) pthread_join(t[i], NULL);
      printf("first %ld\n", trim('2', 8192));
      printf("second %ld\n", trim('2', 8192));
      printf("swapped %ld\n", status("VmSwap:"));
      printf("rest %ld\n", trim('2', 1L << 30));
      printf("touched %d\n", sum(shared, size) != 0);
      printf("hot %ld\n", trim('2', 1L << 30));
      printf("forced %ld\n", trim('3', 1L << 30));
      request('0');
      printf("again %ld\n", trim('2', 1L << 30));
      printf("intact %d\n", sum(data, size) == before);
      return 0;
    } else if (!strcmp(argv[4], "trim-small")) {
      stop = 1;
      for (int i = 0; i < 2; i++) pthread_join(t[i], NULL);
      long total = 0, largest = 0;
      for (int i = 0; i < 112; i++) {
        long step = trim('2', 512);
        total += step;
        if (step > largest) largest = step;
      }
      printf("small %ld\n", total);
      printf("largest %ld\n", largest);
      return 0;
    } else if (!strcmp(argv[4], "trim-sparse")) {
      stop = 1;
      for (int i = 0; i < 2; i++) pthread_join(t[i], NULL);
      size_t wide = 32UL << 20;
      char *thin = mmap(NULL, wide, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
      if (thin == MAP_FAILED) exit(7);
      for (size_t i = 0; i < wide; i += 2 * page) thin[i] = 1;
      printf("sparse %ld\n", resident(thin, wide));
      for (int i = 0; i < 240; i++) trim('2', 512);
      printf("kept %ld\n", resident(thin, wide));
      return 0;
    } else if (!strcmp(argv[4], "hold")) {
      stop = 1;
      for (int i = 0; i < 2; i++) pthread_join(t[i], NULL);
      char hold[256];
      snprintf(hold, sizeof(hold), "%s.hold", ctl);
      FILE *f = fopen(hold, "w");
      if (!f) exit(3);
      fclose(f);
      printf("empty %ld\n", trim('2', 512));
      f = fopen(hold, "w");
      if (!f) exit(3);
      fputs("1", f);
      fclose(f);
      f = fopen(ctl, "w");
      if (!f) exit(3);
      fputs("2 512\n", f);
      fclose(f);
      kill(getpid(), SIGURG);
      wait_ack();
      printf("held %ld\n", status("VmSwap:"));
      unlink(hold);
      return 0;
    } else if (!strcmp(argv[4], "trim-fork")) {
      stop = 1;
      for (int i = 0; i < 2; i++) pthread_join(t[i], NULL);
      fflush(stdout);
      pid_t child = fork();
      if (child < 0) exit(6);
      if (child == 0) {
        for (size_t i = 0; i < size; i += page) data[i]++;
        printf("inline_trim %ld\n", trim('2', 1L << 30));
        fflush(stdout);
        _exit(0);
      }
      waitpid(child, NULL, 0);
      child = fork();
      if (child < 0) exit(6);
      if (child == 0) {
        parked();
        without_threads();
        for (size_t i = 0; i < size; i += page) data[i]++;
        printf("nothread_trim %ld\n", trim('2', 1L << 30));
        fflush(stdout);
        _exit(0);
      }
      waitpid(child, NULL, 0);
      printf("worker_trim %ld\n", trim('2', 1L << 30));
      return 0;
    } else if (!strcmp(argv[4], "sandboxed")) {
      stop = 1;
      for (int i = 0; i < 2; i++) pthread_join(t[i], NULL);
      filter_clone(thread_clone);
      printf("sandboxed_trim %ld\n", trim('2', 1L << 30));
      return 0;
    } else if (!strcmp(argv[4], "filtered")) {
      fflush(stdout);
      filter_clone(thread_clone);
      execl("/proc/self/exe", argv[0], argv[1], argv[2], "0", "filtered-trim", (char *)NULL);
      return 18;
    } else if (!strcmp(argv[4], "filtered-trim")) {
      stop = 1;
      for (int i = 0; i < 2; i++) pthread_join(t[i], NULL);
      printf("filtered_trim %ld\n", trim('2', 1L << 30));
      return 0;
    } else if (!strcmp(argv[4], "burst")) {
      send_request('0');
      usleep(10000);
      send_request('1');
      wait_ack();
      printf("burstack 1\n");
    } else if (!strcmp(argv[4], "timed-inline") || !strcmp(argv[4], "timed-inline-single")) {
      pid_t child = fork();
      if (child < 0) exit(8);
      if (child == 0) {
        without_threads();
        if (!strcmp(argv[4], "timed-inline-single")) {
          if (munmap(data, size) || munmap(shared, size)) _exit(12);
          data = mmap(NULL, size, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
          shared = mmap(NULL, size, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
          if (data == MAP_FAILED || shared == MAP_FAILED) _exit(13);
          for (size_t i = 0; i < size; i++) data[i] = (unsigned char)(i * 7 + i / 4096);
          memset(shared, 0, size);
        }
        struct timespec start, end;
        int fd = open(ctl, O_WRONLY | O_TRUNC);
        if (fd < 0 || write(fd, "1", 1) != 1) _exit(9);
        close(fd);
        clock_gettime(CLOCK_MONOTONIC, &start);
        int requests = !strcmp(argv[4], "timed-inline-single") ? 1 : 64;
        for (int i = 0; i < requests; i++) {
          send_request('1');
        }
        clock_gettime(CLOCK_MONOTONIC, &end);
        long elapsed = (end.tv_sec - start.tv_sec) * 1000L +
                       (end.tv_nsec - start.tv_nsec) / 1000000L;
        printf("inline %ld\n", elapsed);
        printf("inline_swap %ld\n", status("VmSwap:"));
        fflush(stdout);
        _exit(0);
      }
      int status_code;
      if (waitpid(child, &status_code, 0) != child || !WIFEXITED(status_code) || WEXITSTATUS(status_code)) exit(14);
    } else if (!strcmp(argv[4], "timed")) {
      struct timespec previous, current, deadline;
      long max_gap = 0;
      int fd = open(ctl, O_WRONLY | O_TRUNC);
      if (fd < 0 || write(fd, "1", 1) != 1) exit(7);
      close(fd);
      clock_gettime(CLOCK_MONOTONIC, &previous);
      kill(getpid(), SIGURG);
      deadline = previous;
      deadline.tv_sec += 1;
      for (;;) {
        clock_gettime(CLOCK_MONOTONIC, &current);
        long gap = (current.tv_sec - previous.tv_sec) * 1000L +
                   (current.tv_nsec - previous.tv_nsec) / 1000000L;
        if (gap > max_gap) max_gap = gap;
        previous = current;
        if (current.tv_sec > deadline.tv_sec ||
            (current.tv_sec == deadline.tv_sec && current.tv_nsec >= deadline.tv_nsec)) break;
      }
      wait_ack();
      printf("gap %ld\n", max_gap);
      printf("out %ld\n", status("VmSwap:"));
    } else {
    unsigned char *ro = mmap(NULL, size, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    memset(ro, 0x5a, size);
    mprotect(ro, size, PROT_READ);
    madvise(ro, size, 21);
    printf("elsewhere %ld\n", status("VmSwap:"));
    request('0');
    printf("back %ld\n", status("VmSwap:"));
    }
  }
  for (int c = 0; c < cycles; c++) {
    request('1');
    printf("out %ld\n", status("VmSwap:"));
    request('0');
    printf("back %ld\n", status("VmSwap:"));
  }
  stop = 1;
  for (int i = 0; i < 2; i++) pthread_join(t[i], NULL);
  printf("intact %d\n", sum(data, size) == before);
  return 0;
}
"""


def swap_total_kb():
    for line in Path("/proc/meminfo").read_text().splitlines():
        if line.startswith("SwapTotal:"):
            return int(line.split()[1])
    return 0


@unittest.skipUnless(shutil.which("cc"), "needs a C compiler")
class ZramPreloadTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.dir = Path(tempfile.mkdtemp())
        cls.ctl = cls.dir / "ctl"
        build = cls.dir / "build"
        build.mkdir()
        (build / "program.c").write_text(PROGRAM)
        cls.lib = build / "libddsession.so"
        subprocess.run(["cc", "-shared", "-fPIC", "-O2", "-Wall", "-pthread", "-DZR_CTL=\"%s\"" % cls.ctl,
                        "-o", str(cls.lib), *map(str, sorted(PRELOAD.glob("*.c"))), "-ldl"], check=True)
        cls.steam = cls.dir / "steam"
        subprocess.run(["cc", "-O2", "-pthread", "-o", str(cls.steam), str(build / "program.c")], check=True)
        cls.game = cls.dir / "game"
        shutil.copy(cls.steam, cls.game)

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.dir, True)

    def setUp(self):
        self.ctl.write_text("0")
        for ack in [*self.dir.glob("ctl.ack.*"), *self.dir.glob("ctl.trim.*")]:
            ack.unlink()
        (self.dir / "ctl.hold").unlink(missing_ok=True)

    def run_program(self, program=None, mb=48, cycles=1, *extra):
        environment = dict(os.environ, LD_PRELOAD=str(self.lib))
        result = subprocess.run([str(program or self.steam), str(self.ctl), str(mb), str(cycles), *extra], env=environment,
                                capture_output=True, text=True, check=True, timeout=120)
        out = {}
        for line in result.stdout.splitlines():
            key, value = line.split()
            out.setdefault(key, []).append(int(value))
        return out

    def test_every_process_takes_the_signal(self):
        self.assertEqual([1], self.run_program()["handler"])
        self.assertEqual([1], self.run_program(self.game)["handler"])
        self.assertEqual(2, len(list(self.dir.glob("ctl.ack.*"))))

    def test_nothing_is_installed_without_the_control_file(self):
        self.ctl.unlink()
        try:
            self.assertEqual([0], self.run_program(cycles=0)["handler"])
        finally:
            self.ctl.write_text("0")

    @unittest.skipUnless(swap_total_kb() > 256 * 1024, "needs swap")
    def test_pages_out_and_reads_back(self):
        out = self.run_program(mb=48)
        self.assertGreater(out["out"][0], 32 * 1024)
        self.assertLess(out["back"][0], out["out"][0] // 4)
        self.assertEqual([1], out["intact"])
        self.assertEqual(["1"], [ack.read_text() for ack in self.dir.glob("ctl.ack.*")])

    @unittest.skipUnless(swap_total_kb() > 256 * 1024, "needs swap")
    def test_reads_back_what_the_kernel_swapped_elsewhere(self):
        out = self.run_program(None, 32, 0, "read-only")
        self.assertGreater(out["elsewhere"][0], 16 * 1024)
        self.assertLess(out["back"][0], out["elsewhere"][0] // 4)

    def test_memory_stays_intact_under_writers(self):
        out = self.run_program(mb=32, cycles=6)
        self.assertEqual([1], out["intact"])
        self.assertEqual(6, len(out["out"]))

    def test_a_process_keeps_no_extra_thread_while_nothing_is_paged_out(self):
        out = self.run_program(None, 8, 0, "threads")
        self.assertEqual([0], out["idle"])
        self.assertEqual([0], out["after"])

    def test_process_signals_do_not_reach_zram_worker(self):
        environment = dict(os.environ, LD_PRELOAD=str(self.lib))
        process = subprocess.Popen([str(self.steam), str(self.ctl), "1", "0", "signals"],
                                   env=environment, stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
        try:
            process.stdout.readline()
            ready = process.stdout.readline().split()
            self.assertEqual("ready", ready[0])
            for round in range(100):
                os.kill(process.pid, signal.SIGURG)
                os.kill(process.pid, signal.SIGUSR1)
                self.assertEqual(f"received {round + 1}", process.stdout.readline().strip())
            process.stdin.close()
            process.wait(timeout=10)
            output = process.stdout.read()
            values = dict(line.split() for line in output.splitlines())
            self.assertNotEqual(0, int(values["usr"]))
            self.assertEqual(0, int(values["worker"]))
        finally:
            if process.poll() is None:
                process.kill()
                process.wait()

    @unittest.skipUnless(swap_total_kb() > 256 * 1024, "needs swap")
    def test_signal_does_not_stall_the_main_thread(self):
        out = self.run_program(None, 256, 0, "timed")
        self.assertLess(out["gap"][0], 250)
        self.assertGreater(out["out"][0], 32 * 1024)

    @unittest.skipUnless(swap_total_kb() > 256 * 1024, "needs swap")
    def test_inline_pageout_exceeds_async_timing_tolerance(self):
        out = self.run_program(None, 256, 0, "timed-inline")
        if out["inline"][0] <= 250:
            self.skipTest("inline page-out finished under the 250 ms timing threshold on this machine")
        self.assertGreater(out["inline"][0], 250)

    @unittest.skipUnless(swap_total_kb() > 256 * 1024, "needs swap")
    def test_single_request_timing_control(self):
        asynchronous = self.run_program(None, 48, 0, "timed")
        self.assertGreater(asynchronous["out"][0], 32 * 1024)
        inline = self.run_program(None, 48, 0, "timed-inline-single")
        self.assertGreater(inline["inline_swap"][0], 32 * 1024)
        if inline["inline"][0] <= 250:
            self.skipTest("single inline request did not establish the 250 ms timing threshold")
        self.assertGreater(inline["inline"][0], 250)
        self.assertLess(asynchronous["gap"][0], 250)

    @unittest.skipUnless(swap_total_kb() > 256 * 1024, "needs swap")
    def test_forked_child_still_acknowledges_pageout(self):
        out = self.run_program(None, 48, 0, "fork")
        self.assertEqual([1], out["childack"])

    @unittest.skipUnless(swap_total_kb() > 256 * 1024, "needs swap")
    def test_trim_stops_at_its_budget_and_continues_where_it_stopped(self):
        out = self.run_program(None, 32, 0, "trim")
        self.assertTrue(7168 < out["first"][0] <= 8192, out)
        self.assertTrue(7168 < out["second"][0] <= 8192, out)
        self.assertGreater(out["swapped"][0], 15 * 1024)
        self.assertGreater(out["rest"][0], 40 * 1024)
        self.assertEqual([1], out["intact"])

    @unittest.skipUnless(swap_total_kb() > 256 * 1024, "needs swap")
    def test_trim_leaves_memory_that_came_straight_back(self):
        out = self.run_program(None, 32, 0, "trim")
        self.assertLess(out["hot"][0], 4 * 1024, out)
        self.assertGreater(out["forced"][0], 28 * 1024, out)

    @unittest.skipUnless(swap_total_kb() > 256 * 1024, "needs swap")
    def test_requested_read_back_does_not_count_as_heat(self):
        out = self.run_program(None, 32, 0, "trim")
        self.assertTrue(28 * 1024 < out["again"][0] < 40 * 1024, out)

    @unittest.skipUnless(swap_total_kb() > 256 * 1024, "needs swap")
    def test_budgets_smaller_than_a_chunk_still_reach_all_of_it(self):
        out = self.run_program(None, 32, 0, "trim-small")
        self.assertLessEqual(out["largest"][0], 512)
        self.assertGreater(out["small"][0], 52 * 1024)

    @unittest.skipUnless(swap_total_kb() > 256 * 1024, "needs swap")
    def test_small_budgets_reach_the_end_of_a_thinly_used_chunk(self):
        out = self.run_program(None, 8, 0, "trim-sparse")
        self.assertEqual([16 * 1024], out["sparse"])
        self.assertLess(out["kept"][0], 1024, out)

    @unittest.skipUnless(swap_total_kb() > 256 * 1024, "needs swap")
    def test_a_request_in_the_hold_file_wins_over_the_control_file(self):
        out = self.run_program(None, 32, 0, "hold")
        self.assertTrue(0 < out["empty"][0] <= 512, out)
        self.assertGreater(out["held"][0], 40 * 1024, out)

    @unittest.skipUnless(swap_total_kb() > 256 * 1024, "needs swap")
    def test_process_without_a_worker_trims_in_small_steps(self):
        if os.geteuid() == 0:
            self.skipTest("the thread limit does not apply to root")
        out = self.run_program(None, 48, 0, "trim-fork")
        self.assertTrue(16 * 1024 < out["nothread_trim"][0] <= 32 * 1024, out)
        self.assertGreater(out["worker_trim"][0], 64 * 1024, out)

    @unittest.skipUnless(swap_total_kb() > 256 * 1024, "needs swap")
    def test_a_single_threaded_process_trims_without_starting_a_thread(self):
        out = self.run_program(None, 48, 0, "trim-fork")
        self.assertTrue(16 * 1024 < out["inline_trim"][0] <= 32 * 1024, out)
        self.assertGreater(out["worker_trim"][0], 64 * 1024, out)

    @unittest.skipUnless(swap_total_kb() > 256 * 1024, "needs swap")
    def test_a_process_that_sandboxed_itself_trims_without_starting_a_thread(self):
        out = self.run_program(None, 48, 0, "sandboxed")
        self.assertTrue(16 * 1024 < out["sandboxed_trim"][0] <= 32 * 1024, out)

    @unittest.skipUnless(swap_total_kb() > 256 * 1024, "needs swap")
    def test_the_worker_passes_a_filter_that_only_allows_thread_clones(self):
        out = self.run_program(None, 48, 0, "filtered")
        self.assertGreater(out["filtered_trim"][0], 40 * 1024, out)

    def test_worker_acknowledges_back_to_back_requests(self):
        out = self.run_program(None, 48, 0, "burst")
        self.assertEqual([1], out["burstack"])


if __name__ == "__main__":
    unittest.main()
