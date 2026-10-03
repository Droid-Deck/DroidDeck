import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

PRELOAD = Path(__file__).resolve().parents[1] / "linuxfs/preload"

PROGRAM = r"""
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <linux/futex.h>
#include <pthread.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

struct waitv { uint64_t val; uint64_t uaddr; uint32_t flags; uint32_t reserved; };

static char name[64];
static int *page;

static long waitv(int **addrs, int *vals, int n, const struct timespec *ts, int clk) {
  struct waitv v[8];
  for (int i = 0; i < n; i++) {
    v[i].val = (uint32_t)vals[i];
    v[i].uaddr = (uintptr_t)addrs[i];
    v[i].flags = 2;
    v[i].reserved = 0;
  }
  return syscall(449, v, n, 0, ts, clk);
}

static long waitv_retry(int **addrs, int *vals, int n) {
  long r;
  do r = waitv(addrs, vals, n, NULL, 0); while (r == -1 && errno == EINTR);
  return r;
}

static void wake(int *addr) {
  syscall(SYS_futex, addr, FUTEX_WAKE, 0x7fffffff, NULL, NULL, 0);
}

static void deadline(struct timespec *ts, int clk, long ms) {
  clock_gettime(clk, ts);
  ts->tv_sec += ms / 1000;
  ts->tv_nsec += (ms % 1000) * 1000000;
  if (ts->tv_nsec >= 1000000000) {
    ts->tv_sec++;
    ts->tv_nsec -= 1000000000;
  }
}

static double now(void) {
  struct timespec ts;
  clock_gettime(CLOCK_MONOTONIC, &ts);
  return ts.tv_sec + ts.tv_nsec / 1e9;
}

static int *map_page(int fd, int index) {
  int *p = mmap(NULL, 0x10000, PROT_READ | PROT_WRITE, MAP_SHARED, fd, (off_t)index * 0x10000);
  if (p == MAP_FAILED) { perror("mmap"); exit(2); }
  return p;
}

#define CHECK(c, ...) do { if (!(c)) { printf("FAIL %d: ", __LINE__); printf(__VA_ARGS__); printf("\n"); exit(1); } } while (0)

static int client(void) {
  int fd = shm_open(name, O_RDWR, 0644);
  int *p, *addrs[3];
  int vals[3] = {0, 0, 0};
  long r;
  CHECK(fd >= 0, "client open %d", errno);
  p = map_page(fd, 1);
  addrs[0] = &p[0]; addrs[1] = &p[4]; addrs[2] = &p[8];
  __atomic_store_n(&p[12], 1, __ATOMIC_SEQ_CST);
  wake(&p[12]);
  r = waitv_retry(addrs, vals, 3);
  CHECK(r == 2, "client woke with %ld errno %d", r, errno);
  __atomic_store_n(&p[16], 1, __ATOMIC_SEQ_CST);
  wake(&p[16]);
  return 0;
}

static int sleeper(void) {
  int fd = shm_open(name, O_RDWR, 0644);
  int *p = map_page(fd, 1), *a = &p[20], v = 0;
  __atomic_store_n(&p[24], 1, __ATOMIC_SEQ_CST);
  wake(&p[24]);
  for (;;) waitv(&a, &v, 1, NULL, 0);
  return 0;
}

struct sem { int *count; int n; };
static int *sems;
static volatile int taken, given;
#define SEMS 6
#define ROUNDS 20000

static void *consumer(void *arg) {
  int got = 0;
  while (got < ROUNDS) {
    int *addrs[SEMS], vals[SEMS], i;
    for (i = 0; i < SEMS; i++) {
      int *c = &sems[i * 4], cur = __atomic_load_n(c, __ATOMIC_SEQ_CST);
      while (cur > 0) {
        if (__atomic_compare_exchange_n(c, &cur, cur - 1, 0, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST)) { got++; goto next; }
      }
      addrs[i] = c;
      vals[i] = 0;
    }
    waitv(addrs, vals, SEMS, NULL, 0);
  next:;
  }
  __atomic_add_fetch(&taken, got, __ATOMIC_SEQ_CST);
  return arg;
}

static int churn_fd;
static volatile int churn_stop;

static void *churn(void *arg) {
  while (!churn_stop) munmap(map_page(churn_fd, 1), 0x10000);
  return arg;
}

static void *one_wait(void *arg) {
  int *q = map_page(churn_fd, 1), *a = &q[64], v = 0;
  long r;
  do r = waitv(&a, &v, 1, NULL, 0); while (r == -1 && errno == EINTR && !__atomic_load_n(a, __ATOMIC_SEQ_CST));
  munmap(q, 0x10000);
  return (void *)(intptr_t)(r == -1 && errno == EINTR);
}

static int used_slots(void) {
  char reg[96];
  int fd, n = 0;
  uint64_t *head;
  snprintf(reg, sizeof(reg), "%s-droiddeck", name);
  fd = shm_open(reg, O_RDONLY, 0);
  if (fd < 0) return -1;
  head = mmap(NULL, 4096, PROT_READ, MAP_SHARED, fd, 0);
  close(fd);
  if (head == MAP_FAILED) return -1;
  for (int i = 0; i < 32; i++) n += __builtin_popcountll(__atomic_load_n(&head[3 + i], __ATOMIC_SEQ_CST));
  munmap(head, 4096);
  return n;
}

static void *producer(void *arg) {
  unsigned seed = (unsigned)(uintptr_t)arg;
  for (int i = 0; i < ROUNDS; i++) {
    int *c = &sems[(rand_r(&seed) % SEMS) * 4];
    __atomic_add_fetch(c, 1, __ATOMIC_SEQ_CST);
    wake(c);
    __atomic_add_fetch(&given, 1, __ATOMIC_SEQ_CST);
  }
  return arg;
}

int main(int argc, char **argv) {
  snprintf(name, sizeof(name), "/wine-%x-fsync", (unsigned)getpid() ^ 0x5a5a00);
  if (argc > 2) snprintf(name, sizeof(name), "%s", argv[2]);
  if (argc > 1 && !strcmp(argv[1], "client")) return client();
  if (argc > 1 && !strcmp(argv[1], "sleeper")) return sleeper();
  {
    long r = syscall(449, NULL, 0, 0, NULL, 0);
    CHECK(r == -1 && errno == EINVAL, "probe %ld errno %d", r, errno);
  }
  int fd = shm_open(name, O_RDWR | O_CREAT | O_EXCL, 0644);
  CHECK(fd >= 0, "create %d", errno);
  CHECK(ftruncate(fd, 0x20000) == 0, "truncate");
  page = map_page(fd, 1);
  {
    char reg[96];
    struct stat st;
    snprintf(reg, sizeof(reg), "/dev/shm%s-droiddeck", name);
    CHECK(stat(reg, &st) == 0 && st.st_size > 0, "registry %s missing", reg);
  }
  {
    int *a = &page[40], v = 1;
    long r = waitv(&a, &v, 1, NULL, 0);
    CHECK(r == -1 && errno == EAGAIN, "mismatch %ld errno %d", r, errno);
  }
  for (int clk = 0; clk < 2; clk++) {
    int c = clk ? CLOCK_REALTIME : CLOCK_MONOTONIC, *a = &page[44], v = 0;
    struct timespec ts;
    double t = now();
    long r;
    deadline(&ts, c, 150);
    do r = waitv(&a, &v, 1, &ts, c); while (r == -1 && errno == EINTR);
    t = now() - t;
    CHECK(r == -1 && errno == ETIMEDOUT && t > 0.12 && t < 1.5, "timeout clock %d r %ld errno %d after %.3f", c, r, errno, t);
  }
  {
    pid_t pid = fork();
    if (!pid) { execl("/proc/self/exe", argv[0], "client", name, (char *)NULL); _exit(3); }
    int *ready = &page[12], *done = &page[16], z = 0, st;
    double t = now();
    while (!__atomic_load_n(ready, __ATOMIC_SEQ_CST) && now() - t < 20) waitv(&ready, &z, 1, NULL, 0);
    usleep(20000);
    __atomic_store_n(&page[8], 1, __ATOMIC_SEQ_CST);
    wake(&page[8]);
    while (!__atomic_load_n(done, __ATOMIC_SEQ_CST) && now() - t < 20) waitv(&done, &z, 1, NULL, 0);
    CHECK(__atomic_load_n(done, __ATOMIC_SEQ_CST), "client never finished");
    waitpid(pid, &st, 0);
    CHECK(WIFEXITED(st) && WEXITSTATUS(st) == 0, "client status %d", st);
  }
  {
    int rounds = 2000, z = 0;
    double t = now();
    pid_t pid = fork();
    if (!pid) {
      int fd2 = shm_open(name, O_RDWR, 0644);
      int *p = map_page(fd2, 1);
      for (int i = 0; i < rounds; i++) {
        int *ping = &p[48], *pong = &p[52];
        while (__atomic_load_n(ping, __ATOMIC_SEQ_CST) == i * 2) { int v = i * 2; waitv(&ping, &v, 1, NULL, 0); }
        __atomic_store_n(pong, i * 2 + 2, __ATOMIC_SEQ_CST);
        wake(pong);
      }
      _exit(0);
    }
    for (int i = 0; i < rounds; i++) {
      int *ping = &page[48], *pong = &page[52];
      __atomic_store_n(ping, i * 2 + 2, __ATOMIC_SEQ_CST);
      wake(ping);
      while (__atomic_load_n(pong, __ATOMIC_SEQ_CST) == i * 2) { int v = i * 2; waitv(&pong, &v, 1, NULL, 0); }
    }
    waitpid(pid, NULL, 0);
    printf("pingpong %.2f us/round\n", (now() - t) * 1e6 / rounds);
    (void)z;
  }
  {
    pthread_t c[4], p[4];
    sems = &page[256];
    for (int i = 0; i < 4; i++) pthread_create(&c[i], NULL, consumer, NULL);
    for (int i = 0; i < 4; i++) pthread_create(&p[i], NULL, producer, (void *)(uintptr_t)(i + 1));
    for (int i = 0; i < 4; i++) pthread_join(p[i], NULL);
    for (int i = 0; i < 4; i++) pthread_join(c[i], NULL);
    CHECK(taken == 4 * ROUNDS && given == 4 * ROUNDS, "stress taken %d given %d", taken, given);
    for (int i = 0; i < SEMS; i++) CHECK(sems[i * 4] == 0, "sem %d left %d", i, sems[i * 4]);
  }
  for (int round = 0; round < 3; round++) {
    pid_t kids[40];
    int z = 0;
    for (int i = 0; i < 40; i++) {
      __atomic_store_n(&page[24], 0, __ATOMIC_SEQ_CST);
      kids[i] = fork();
      if (!kids[i]) { execl("/proc/self/exe", argv[0], "sleeper", name, (char *)NULL); _exit(3); }
      int *r = &page[24];
      while (!__atomic_load_n(r, __ATOMIC_SEQ_CST)) waitv(&r, &z, 1, NULL, 0);
    }
    for (int i = 0; i < 40; i++) { kill(kids[i], SIGKILL); waitpid(kids[i], NULL, 0); }
    double t = now();
    for (int i = 0; i < 1000; i++) wake(&page[20]);
    CHECK(now() - t < 2.0, "wakes after killed sleepers took %.3f", now() - t);
  }
  {
    pthread_t churners[2], t;
    int rescued = 0, threads = 3000;
    double t0 = now();
    churn_fd = fd;
    for (int i = 0; i < 2; i++) pthread_create(&churners[i], NULL, churn, NULL);
    for (int i = 0; i < threads; i++) {
      void *res;
      __atomic_store_n(&page[64], 0, __ATOMIC_SEQ_CST);
      pthread_create(&t, NULL, one_wait, NULL);
      if (i & 1) usleep(50);
      __atomic_store_n(&page[64], 1, __ATOMIC_SEQ_CST);
      wake(&page[64]);
      pthread_join(t, &res);
      rescued += (int)(intptr_t)res;
    }
    churn_stop = 1;
    for (int i = 0; i < 2; i++) pthread_join(churners[i], NULL);
    printf("thread churn %.2f s, %d of %d waits needed the nap\n", now() - t0, rescued, threads);
    CHECK(rescued <= 3 && now() - t0 < 30, "thread churn: %d naps in %.2f s", rescued, now() - t0);
    usleep(1100000);
    __atomic_store_n(&page[64], 0, __ATOMIC_SEQ_CST);
    pthread_create(&t, NULL, one_wait, NULL);
    usleep(20000);
    __atomic_store_n(&page[64], 1, __ATOMIC_SEQ_CST);
    wake(&page[64]);
    pthread_join(t, NULL);
    CHECK(used_slots() == 1, "registry slots still in use: %d", used_slots());
  }
  {
    char reg[96];
    struct stat st;
    snprintf(reg, sizeof(reg), "/dev/shm%s-droiddeck", name);
    shm_unlink(name);
    CHECK(stat(reg, &st) != 0, "registry left behind");
  }
  printf("fsync emulation ok\n");
  return 0;
}
"""


@unittest.skipUnless(shutil.which("cc") and os.path.exists("/dev/shm"), "a C compiler and /dev/shm are needed")
class FsyncPreloadTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        base = Path(cls.tmp.name)
        cls.lib = base / "libfsync.so"
        cls.exe = base / "fsync-test"
        source = base / "fsync-test.c"
        source.write_text(PROGRAM)
        subprocess.run(["cc", "-O2", "-Wall", "-fPIC", "-shared", "-pthread", "-o", str(cls.lib),
                        str(PRELOAD / "fsync.c"), str(PRELOAD / "robust.c"), "-ldl"], check=True)
        subprocess.run(["cc", "-O2", "-Wall", "-pthread", "-o", str(cls.exe), str(source)], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def run_program(self, **env):
        return subprocess.run([str(self.exe)], env=dict(os.environ, LD_PRELOAD=str(self.lib), **env),
                              capture_output=True, text=True, timeout=120)

    def test_emulated_waits_wake_time_out_and_survive_dead_waiters(self):
        result = self.run_program(BL_FSYNC="1")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("fsync emulation ok", result.stdout)
        self.assertIn("droiddeck-fsync: up and running.", result.stderr)

    def test_off_without_the_switch(self):
        result = self.run_program(BL_FSYNC="0")
        self.assertNotIn("droiddeck-fsync", result.stderr)
        self.assertNotIn("fsync emulation ok", result.stdout)


if __name__ == "__main__":
    unittest.main()
