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
#define SYNC_IOC_MERGE 200
#define POLLIN 1
#define F_DUPFD_CLOEXEC 1030
#define VK_NULL_HANDLE 0
#define VK_SUCCESS 0
#define VK_STRUCTURE_TYPE_IMPORT_SEMAPHORE_FD_INFO_KHR 1
#define VK_SEMAPHORE_IMPORT_TEMPORARY_BIT 1
#define VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT 16
typedef uintptr_t VkSemaphore;
typedef int VkResult;
struct dma_buf_export_sync_file { unsigned flags; int fd; };
struct sync_merge_data { char name[32]; int fd2; int fence; unsigned flags, pad; };
struct pollfd { int fd; short events, revents; };
typedef struct { int sType; VkSemaphore semaphore; unsigned flags, handleType; int fd; } VkImportSemaphoreFdInfoKHR;
struct vkp_image { int client_fd, has_client_fd; };

/* Fake fds: 10..19 are client dma-bufs, 20+ are sync_files. done[fd] says a sync_file has signalled. */
static int next_fd = 20, done[64], closed[64], merges, imports, polled_ms;
static int pending_of[64];
static struct { unsigned client_write_waits; } g_perf;
static uintptr_t g_dev = 1;
static VkSemaphore g_client_sem = 7;
static int g_client_wait_fd = -1;
static int import_ok = 1;
static VkResult import_fn(uintptr_t dev, const VkImportSemaphoreFdInfoKHR *ii) {
    (void)dev; assert(ii->semaphore == g_client_sem); assert(ii->flags == VK_SEMAPHORE_IMPORT_TEMPORARY_BIT);
    ++imports; return import_ok ? VK_SUCCESS : 1;
}
static VkResult (*g_import_sem_fd)(uintptr_t, const VkImportSemaphoreFdInfoKHR *) = import_fn;
static int ioctl(int fd, unsigned long req, void *arg) {
    if (req == DMA_BUF_IOCTL_EXPORT_SYNC_FILE) {
        struct dma_buf_export_sync_file *e = arg;
        assert(fd >= 10 && fd < 20 && e->flags == DMA_BUF_SYNC_READ);
        e->fd = next_fd++; done[e->fd] = !pending_of[fd]; return 0;
    }
    if (req == SYNC_IOC_MERGE) {
        struct sync_merge_data *m = arg;
        ++merges; m->fence = next_fd++; done[m->fence] = done[fd] && done[m->fd2]; return 0;
    }
    return -1;
}
static int poll(struct pollfd *p, unsigned n, int timeout) {
    (void)n; polled_ms += timeout; return done[p->fd] ? 1 : 0;
}
static int close(int fd) { closed[fd]++; return 0; }
static int fcntl(int fd, int cmd, int arg) { (void)arg; assert(cmd == F_DUPFD_CLOEXEC); return fd; }
/* PRODUCTION_FUNCTIONS */
int main(void) {
    struct vkp_image busy = {0}, idle = {0}, busy2 = {0}, shm = {0};
    vkp_image_track_client_writes(&busy, 10);
    vkp_image_track_client_writes(&idle, 11);
    vkp_image_track_client_writes(&busy2, 12);
    pending_of[10] = 1; pending_of[12] = 1;

    note_client_writes(&shm);
    note_client_writes(&idle);
    assert(g_client_wait_fd == -1);
    assert(take_client_wait() == 0 && g_perf.client_write_waits == 0);

    note_client_writes(&busy);
    note_client_writes(&busy2);
    assert(merges == 1 && g_client_wait_fd >= 20);
    assert(take_client_wait() == 1 && imports == 1 && g_perf.client_write_waits == 1);
    assert(g_client_wait_fd == -1 && polled_ms == 0);

    import_ok = 0;
    note_client_writes(&busy);
    assert(take_client_wait() == 0 && polled_ms == 100 && g_perf.client_write_waits == 2);
    puts("PASS");
    return 0;
}
'''


class VkClientWriteFencesTest(unittest.TestCase):
    def test_reads_wait_only_for_unfinished_client_writes(self):
        compiler = shutil.which(os.environ.get('CC', 'cc'))
        if not compiler:
            self.skipTest('A host C compiler is required')
        source = SOURCE.read_text()
        functions = '\n'.join(function(source, name) for name in
                              ('note_client_writes', 'take_client_wait', 'vkp_image_track_client_writes'))
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
