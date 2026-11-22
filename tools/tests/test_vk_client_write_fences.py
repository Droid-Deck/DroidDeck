"""Reads of a client's dma-buf wait for the client's unfinished GPU writes.

The compiled functions come from vk_present.c; the kernel's dma-buf and sync_file ioctls are faked.
"""
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest

SOURCE = Path(__file__).resolve().parents[2] / 'app/src/main/cpp/waylandcomp/src/vk_present.c'


def function(text, name):
    match = re.search(r'^(?:static )?[^\n;]+\b' + name + r'\([^;]*?\)\s*\{', text, re.M)
    if not match:
        raise AssertionError('Production function not found: ' + name)
    pos = text.index('{', match.start())
    depth, end = 1, pos + 1
    while depth:
        depth += (text[end] == '{') - (text[end] == '}')
        end += 1
    return text[match.start():end]


HARNESS = r'''
#include <assert.h>
#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#define DMA_BUF_SYNC_READ 1
#define DMA_BUF_IOCTL_EXPORT_SYNC_FILE 100
#define POLLIN 1
struct dma_buf_export_sync_file { unsigned flags; int fd; };
struct pollfd { int fd; short events, revents; };
struct vkp_image { int client_fd, has_client_fd, client_stalled; };
struct vkp_draw { struct vkp_image *img; };

/* Fake fds: 10..19 are client dma-bufs, 20+ are sync_files. A write finishes at finish_ms[dma-buf]. */
static int next_fd = 20, closed[64], export_ok = 1, interrupted, poll_fails, polled[64];
static int64_t now_ms, finish_ms[64];
static int owner[64];
static struct { unsigned client_write_waits; } g_perf;
static int64_t perf_now(void) { return now_ms * 1000000; }
static int ioctl(int fd, unsigned long req, void *arg) {
    struct dma_buf_export_sync_file *e = arg;
    assert(req == DMA_BUF_IOCTL_EXPORT_SYNC_FILE && fd >= 10 && fd < 20 && e->flags == DMA_BUF_SYNC_READ);
    if (!export_ok) { errno = ENOTTY; return -1; }
    e->fd = next_fd++; owner[e->fd] = fd; return 0;
}
static int poll(struct pollfd *p, unsigned n, int timeout) {
    assert(n == 1 && p->events == POLLIN && timeout >= 0);
    polled[p->fd]++;
    if (interrupted) { interrupted--; errno = EINTR; return -1; }
    if (poll_fails) { errno = ENOMEM; return -1; }
    const int buf = p->fd >= 20 ? owner[p->fd] : p->fd;
    if (finish_ms[buf] <= now_ms + timeout) { if (finish_ms[buf] > now_ms) now_ms = finish_ms[buf]; return 1; }
    now_ms += timeout; return 0;
}
static int close(int fd) { closed[fd]++; return 0; }
/* PRODUCTION_FUNCTIONS */
int main(void) {
    struct vkp_image idle = {0}, slow = {0}, stuck = {0}, shm = {0};
    vkp_image_track_client_writes(&idle, 10);
    vkp_image_track_client_writes(&slow, 11);
    vkp_image_track_client_writes(&stuck, 12);
    finish_ms[11] = 30; finish_ms[12] = 1000000;

    assert(vkp_image_client_writes_done(&shm) && vkp_image_client_writes_done(&idle));
    assert(g_perf.client_write_waits == 0 && now_ms == 0 && closed[20] == 1);

    struct vkp_draw draws[] = {{&idle}, {&slow}, {&shm}};
    assert(vkp_draws_client_writes_done(draws, 3) && now_ms == 30 && g_perf.client_write_waits == 1);

    /* A write still running at the deadline skips the read. Until it finishes, its image is only checked. */
    now_ms = 0;
    struct vkp_draw blocked[] = {{&slow}, {&stuck}};
    finish_ms[11] = 40;
    assert(!vkp_draws_client_writes_done(blocked, 2) && now_ms == 100 && g_perf.client_write_waits == 3);
    assert(vkp_client_write_deferrals() == 1);
    assert(!vkp_image_client_writes_done(&stuck) && now_ms == 100 && g_perf.client_write_waits == 3);
    assert(vkp_client_write_deferrals() == 2);
    assert(vkp_image_client_writes_done(&slow) && now_ms == 100 && vkp_client_write_deferrals() == 2);

    /* Two stalled images each block once, whatever order they are checked in. */
    finish_ms[11] = 1000;
    assert(!vkp_image_client_writes_done(&slow) && now_ms == 200);
    assert(!vkp_image_client_writes_done(&stuck) && !vkp_image_client_writes_done(&slow) && now_ms == 200);
    finish_ms[11] = 250; finish_ms[12] = 250; now_ms = 216;
    assert(!vkp_image_client_writes_done(&stuck) && now_ms == 216);
    now_ms = 260;
    assert(vkp_image_client_writes_done(&stuck) && vkp_image_client_writes_done(&slow) && now_ms == 260);
    assert(!stuck.client_stalled && !slow.client_stalled);
    finish_ms[12] = 290;
    assert(vkp_image_client_writes_done(&stuck) && now_ms == 290 && g_perf.client_write_waits == 5);
    for (int fd = 20; fd < next_fd; fd++) assert(closed[fd] == 1);

    /* Without sync_file export the dma-buf itself is polled for its write fences. */
    export_ok = 0; now_ms = 0; finish_ms[11] = 20;
    const int before = next_fd;
    assert(vkp_image_client_writes_done(&slow) && now_ms == 20 && next_fd == before && polled[11] == 2);
    finish_ms[11] = 1000;
    assert(!vkp_image_client_writes_done(&slow) && now_ms == 120 && closed[11] == 0);

    /* EINTR retries; a failing poll does not count as a finished write. */
    export_ok = 1; now_ms = 0; finish_ms[10] = 10; interrupted = 2;
    assert(vkp_image_client_writes_done(&idle) && now_ms == 10);
    poll_fails = 1;
    assert(!vkp_image_client_writes_done(&idle) && idle.client_stalled);
    puts("PASS");
    return 0;
}
'''


class VkClientWriteFencesTest(unittest.TestCase):
    def test_reads_wait_for_client_writes_or_skip_the_frame(self):
        compiler = shutil.which(os.environ.get('CC', 'cc'))
        if not compiler:
            self.skipTest('A host C compiler is required')
        source = SOURCE.read_text()
        bound = re.search(r'^#define CLIENT_WRITE_WAIT_NS .*\nstatic unsigned g_client_write_deferrals;$',
                          source, re.M).group(0)
        functions = bound + '\n' + '\n'.join(function(source, name) for name in
                              ('wait_client_writes', 'vkp_draws_client_writes_done', 'vkp_image_client_writes_done',
                               'vkp_client_write_deferrals', 'vkp_image_track_client_writes'))
        with tempfile.TemporaryDirectory(prefix='droiddeck-client-writes-') as temp:
            folder = Path(temp)
            (folder / 'probe.c').write_text(HARNESS.replace('/* PRODUCTION_FUNCTIONS */', functions))
            built = subprocess.run([compiler, '-std=gnu11', '-Wall', '-Wextra', '-Wno-unused-parameter',
                                    '-Wno-missing-field-initializers', str(folder / 'probe.c'),
                                    '-o', str(folder / 'probe')], capture_output=True, text=True)
            self.assertEqual(built.returncode, 0, built.stdout + built.stderr)
            run = subprocess.run([str(folder / 'probe')], capture_output=True, text=True, timeout=10)
            self.assertEqual(run.returncode, 0, run.stdout + run.stderr)
            self.assertEqual(run.stdout.strip(), 'PASS')


if __name__ == '__main__':
    unittest.main()
