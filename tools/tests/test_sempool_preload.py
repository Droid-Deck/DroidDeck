import os
from pathlib import Path
import subprocess
import tempfile
import unittest

from test_usr2_preload import runner

PRELOAD = Path(__file__).resolve().parents[1] / "linuxfs/preload"

NTDLL = r"""
#include <stdint.h>
#include <string.h>
#include <sys/uio.h>
#include <unistd.h>

struct reply { uint32_t error, size, handle, pad[13]; };

static uint32_t call(int req_fd, int reply_fd, const uint32_t *words, int nwords,
                     const void *data, uint32_t data_len, uint32_t *handle) {
  uint32_t msg[16] = {0};
  memcpy(msg, words, nwords * sizeof *words);
  msg[1] = data_len;
  if (data_len) {
    struct iovec iov[2] = { { msg, sizeof msg }, { (void *)data, data_len } };
    if (writev(req_fd, iov, 2) != (ssize_t)(sizeof msg + data_len)) return 0xdead;
  } else if (write(req_fd, msg, sizeof msg) != sizeof msg) {
    return 0xdead;
  }
  struct reply r;
  if (read(reply_fd, &r, sizeof r) != sizeof r) return 0xbeef;
  if (handle) *handle = r.handle;
  return r.error;
}

uint32_t nt_create_semaphore(int q, int a, uint32_t access, uint32_t initial, uint32_t max,
                             uint32_t attributes, uint32_t name_len, uint32_t *handle) {
  uint32_t words[6] = { 40, 0, 0, access, initial, max };
  uint32_t attr[6] = { 0, attributes, 0, name_len, 0x41, 0x42 };
  return call(q, a, words, 6, attr, 16 + name_len, handle);
}

uint32_t nt_close(int q, int a, uint32_t handle) {
  uint32_t words[4] = { 21, 0, 0, handle };
  return call(q, a, words, 4, NULL, 0, NULL);
}

uint32_t nt_other(int q, int a, uint32_t code, uint32_t handle) {
  uint32_t words[5] = { code, 0, 0, 7, handle };
  return call(q, a, words, 5, NULL, 0, NULL);
}

ssize_t nt_write_file(int fd, const uint32_t *words) {
  ssize_t r = write(fd, words, 64);
  __asm__ volatile("" ::: "memory");
  return r;
}

ssize_t nt_read_file(int fd, uint32_t *words) {
  ssize_t r = read(fd, words, 64);
  __asm__ volatile("" ::: "memory");
  return r;
}
"""

PROGRAM = r"""
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <stdlib.h>

uint32_t nt_create_semaphore(int, int, uint32_t, uint32_t, uint32_t, uint32_t, uint32_t, uint32_t *);
uint32_t nt_close(int, int, uint32_t);
uint32_t nt_other(int, int, uint32_t, uint32_t);
ssize_t nt_write_file(int, const uint32_t *);
ssize_t nt_read_file(int, uint32_t *);

static int q[2], a[2];
static int creates, closes, others;

static void *server(void *unused) {
  (void)unused;
  uint32_t next = 0x1000;
  for (;;) {
    uint32_t msg[16], reply[16] = {0};
    if (read(q[0], msg, sizeof msg) != sizeof msg) return NULL;
    if (msg[1]) {
      char data[256];
      if (read(q[0], data, msg[1]) != (ssize_t)msg[1]) return NULL;
    }
    if (msg[0] == 40) { creates++; reply[2] = next; next += 4; }
    else if (msg[0] == 21) closes++;
    else others++;
    if (write(a[1], reply, sizeof reply) != sizeof reply) return NULL;
  }
}

static uint32_t create(uint32_t max, uint32_t name_len) {
  uint32_t h = 0;
  if (nt_create_semaphore(q[1], a[0], 0x1f0002, 0, max, 0x80, name_len, &h)) return 0;
  return h;
}

int main(int argc, char **argv) {
  if (pipe(q) || pipe(a)) return 1;
  pthread_t t;
  pthread_create(&t, NULL, server, NULL);
  const char *mode = argv[1];
  uint32_t first = 0, last = 0, distinct = 0;
  if (!strcmp(mode, "files-then-loop")) {
    char out[] = "/tmp/sempool-out-XXXXXX", in[] = "/tmp/sempool-in-XXXXXX";
    int fo = mkstemp(out), fi = mkstemp(in);
    uint32_t zeros[16] = {0};
    if (fo < 0 || fi < 0 || write(fi, zeros, sizeof zeros) != sizeof zeros || lseek(fi, 0, SEEK_SET)) return 5;
    unlink(out);
    unlink(in);
    if (nt_write_file(fo, zeros) != 64 || nt_read_file(fi, zeros) != 64) return 6;
    mode = "loop";
  }
  if (!strcmp(mode, "loop")) {
    for (int i = 0; i < 1000; i++) {
      uint32_t h = create(0x7fffffff, 0);
      if (!first) first = h;
      if (h != last) distinct++;
      last = h;
      if (nt_close(q[1], a[0], h)) return 2;
    }
  } else if (!strcmp(mode, "used")) {
    uint32_t h = create(0x7fffffff, 0);
    nt_other(q[1], a[0], 99, h);
    nt_close(q[1], a[0], h);
    uint32_t h2 = create(0x7fffffff, 0);
    distinct = h2 != h;
    nt_close(q[1], a[0], h2);
  } else if (!strcmp(mode, "freed-then-used")) {
    uint32_t h = create(0x7fffffff, 0);
    nt_close(q[1], a[0], h);
    nt_other(q[1], a[0], 99, h);
    uint32_t h2 = create(0x7fffffff, 0);
    distinct = h2 != h;
  } else if (!strcmp(mode, "double-close")) {
    uint32_t h = create(0x7fffffff, 0);
    uint32_t first = nt_close(q[1], a[0], h);
    uint32_t second = nt_close(q[1], a[0], h);
    uint32_t h2 = create(0x7fffffff, 0);
    distinct = !first && second == 0xC0000008u && h2 == h;
  } else if (!strcmp(mode, "file-write")) {
    int f[2];
    if (pipe(f)) return 4;
    uint32_t h = create(0x7fffffff, 0);
    uint32_t words[16] = { 21, 0, 0, h };
    uint32_t back[16] = {0};
    distinct = nt_write_file(f[1], words) == 64 && read(f[0], back, sizeof back) == sizeof back && back[3] == h;
    nt_close(q[1], a[0], h);
  } else if (!strcmp(mode, "file-write-pooled") || !strcmp(mode, "zombie-close")) {
    int f[2];
    if (pipe(f)) return 4;
    uint32_t h = create(0x7fffffff, 0);
    nt_close(q[1], a[0], h);
    uint32_t words[16] = { 21, 0, 0, h };
    uint32_t back[16] = {0};
    distinct = nt_write_file(f[1], words) == 64 && read(f[0], back, sizeof back) == sizeof back;
    if (!strcmp(mode, "zombie-close")) distinct &= nt_close(q[1], a[0], h) == 0xC0000008u;
    distinct &= create(0x7fffffff, 0) != h;
  } else if (!strcmp(mode, "different")) {
    uint32_t h = create(0x7fffffff, 0);
    nt_close(q[1], a[0], h);
    uint32_t h2 = create(5, 0);
    distinct = h2 != h;
  } else if (!strcmp(mode, "named")) {
    for (int i = 0; i < 10; i++) nt_close(q[1], a[0], create(0x7fffffff, 8));
  } else if (!strcmp(mode, "outside")) {
    uint32_t h = create(0x7fffffff, 0);
    uint32_t msg[16] = { 21, 0, 0, h };
    uint32_t reply[16];
    if (write(q[1], msg, sizeof msg) != sizeof msg || read(a[0], reply, sizeof reply) != sizeof reply) return 3;
  }
  printf("%d %d %d %u\n", creates, closes, others, distinct);
  return 0;
}
"""


class SemaphorePoolPreloadTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.cc, cls.prefix = runner()
        if cls.cc is None:
            raise unittest.SkipTest("no aarch64 compiler and runner")
        cls.tmp = tempfile.TemporaryDirectory()
        tmp = Path(cls.tmp.name)
        (tmp / "ntdll.c").write_text(NTDLL)
        (tmp / "prog.c").write_text(PROGRAM)
        sources = sorted(str(p) for p in PRELOAD.glob("*.c"))
        subprocess.run([cls.cc, "-shared", "-fPIC", "-O2", "-Wall", "-pthread", "-o", str(tmp / "libblsession.so"),
                        *sources, "-ldl"], check=True)
        subprocess.run([cls.cc, "-shared", "-fPIC", "-O2", "-o", str(tmp / "ntdll.so"), str(tmp / "ntdll.c")], check=True)
        subprocess.run([cls.cc, "-O1", "-pthread", "-o", str(tmp / "prog"), str(tmp / "prog.c"), str(tmp / "ntdll.so"),
                        "-Wl,-rpath," + str(tmp)], check=True)
        cls.program = tmp / "prog"
        cls.preload = tmp / "libblsession.so"

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def run_program(self, mode, protocol="40,21"):
        env = dict(os.environ)
        env.pop("LD_PRELOAD", None)
        env.pop("BL_SEMPOOL", None)
        if protocol:
            env["BL_SEMPOOL"] = protocol
        if self.prefix:
            # qemu splits -E values on commas, so BL_SEMPOOL reaches the guest through qemu's own environment.
            cmd = list(self.prefix) + ["-E", "LD_PRELOAD=" + str(self.preload), str(self.program), mode]
        else:
            env["LD_PRELOAD"] = str(self.preload)
            cmd = [str(self.program), mode]
        out = subprocess.run(cmd, env=env, capture_output=True, text=True, timeout=120)
        self.assertEqual(out.returncode, 0, out.stderr)
        creates, closes, others, distinct = map(int, out.stdout.split())
        return creates, closes, others, distinct

    def test_create_close_loop_reaches_the_server_once(self):
        self.assertEqual(self.run_program("loop"), (1, 0, 0, 1))

    def test_file_io_before_the_first_request_is_not_taken_for_the_server(self):
        self.assertEqual(self.run_program("files-then-loop"), (1, 0, 0, 1))

    def test_missing_or_malformed_request_numbers_change_nothing(self):
        self.assertEqual(self.run_program("loop", protocol=None), (1000, 1000, 0, 1000))
        self.assertEqual(self.run_program("loop", protocol="40"), (1000, 1000, 0, 1000))
        self.assertEqual(self.run_program("loop", protocol="40,21x"), (1000, 1000, 0, 1000))
        self.assertEqual(self.run_program("loop", protocol="40,"), (1000, 1000, 0, 1000))
        self.assertEqual(self.run_program("loop", protocol=",21"), (1000, 1000, 0, 1000))

    def test_a_semaphore_named_in_another_request_is_really_closed(self):
        self.assertEqual(self.run_program("used"), (2, 1, 1, 1))

    def test_a_kept_semaphore_named_after_its_close_is_closed_before_that_request(self):
        self.assertEqual(self.run_program("freed-then-used"), (2, 1, 1, 1))

    def test_a_second_close_fails_like_wine_and_keeps_the_semaphore(self):
        self.assertEqual(self.run_program("double-close"), (1, 0, 0, 1))

    def test_a_64_byte_file_write_from_ntdll_is_written(self):
        self.assertEqual(self.run_program("file-write"), (1, 1, 0, 1))

    def test_a_pooled_semaphore_named_in_a_file_write_is_closed_not_leaked(self):
        self.assertEqual(self.run_program("file-write-pooled"), (2, 1, 0, 1))

    def test_closing_a_semaphore_waiting_to_be_closed_fails_like_wine(self):
        self.assertEqual(self.run_program("zombie-close"), (2, 1, 0, 1))

    def test_only_an_identical_create_reuses_a_semaphore(self):
        self.assertEqual(self.run_program("different"), (2, 0, 0, 1))

    def test_named_semaphores_always_reach_the_server(self):
        self.assertEqual(self.run_program("named"), (10, 10, 0, 0))

    def test_requests_not_sent_by_ntdll_pass_through(self):
        self.assertEqual(self.run_program("outside"), (1, 1, 0, 0))


if __name__ == "__main__":
    unittest.main()
