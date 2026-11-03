/* SPDX-License-Identifier: GPL-3.0-or-later */
#include "kgsl_compat.h"
#include <assert.h>
#include <errno.h>
#include <stdio.h>
#include <string.h>

static unsigned long calls[8];
static int count, old_error, alloc_error, info_error, free_error;
static unsigned long address;
static int mock(int fd, unsigned long request, void *arg) {
    assert(fd == 42 && count < 8);
    calls[count++] = request;
    if (request == IOCTL_KGSL_GPUMEM_ALLOC_ID || request == IOCTL_KGSL_GPUMEM_FREE_ID) {
        if (old_error) { errno = old_error; return -1; }
        return 0;
    }
    if (request == IOCTL_KGSL_GPUOBJ_ALLOC) {
        if (alloc_error) { errno = alloc_error; return -1; }
        struct kgsl_gpuobj_alloc *p = arg;
        assert(p->size == 123 && p->flags == 0x10000 && !p->metadata && !p->metadata_len);
        p->id = 77; p->size = 4096; p->mmapsize = 8192;
        return 0;
    }
    if (request == IOCTL_KGSL_GPUOBJ_INFO) {
        struct kgsl_gpuobj_info *p = arg;
        assert(p->id == 77);
        if (info_error) { errno = info_error; return -1; }
        p->gpuaddr = address; p->flags = 0x20000; p->size = 4096;
        return 0;
    }
    if (request == IOCTL_KGSL_GPUOBJ_FREE) {
        struct kgsl_gpuobj_free *p = arg;
        assert(p->id == 77 && !p->flags && !p->priv && !p->type && !p->len);
        if (free_error) { errno = free_error; return -1; }
        return 0;
    }
    errno = EACCES;
    return -1;
}
static struct kgsl_gpumem_alloc_id reset(void) {
    count = old_error = alloc_error = info_error = free_error = 0;
    address = 0x100000;
    return (struct kgsl_gpumem_alloc_id){.size = 123, .flags = 0x10000};
}
static int read_error, wait_error, waits;
static unsigned int waited_for;
static void (*during_wait)(void);
static int timestamp_mock(int fd, unsigned long request, void *arg) {
    assert(fd == 42);
    if (request == IOCTL_KGSL_GPU_COMMAND || request == IOCTL_KGSL_DRAWCTXT_DESTROY) return 0;
    if (request == IOCTL_KGSL_CMDSTREAM_READTIMESTAMP_CTXTID) {
        errno = read_error;
        return -1;
    }
    if (request == IOCTL_KGSL_DEVICE_WAITTIMESTAMP_CTXTID) {
        const struct kgsl_device_waittimestamp_ctxtid *w = arg;
        assert(w->timeout == 0);
        waits++;
        waited_for = w->timestamp;
        if (during_wait) during_wait();
        if (wait_error) { errno = wait_error; return -1; }
        return 0;
    }
    errno = EACCES;
    return -1;
}
static void submit(unsigned int context, unsigned int timestamp) {
    struct kgsl_gpu_command cmd = {.context_id = context, .timestamp = timestamp};
    assert(quest_kgsl_ioctl(timestamp_mock, 42, IOCTL_KGSL_GPU_COMMAND, &cmd) == 0);
}
static int read_ts(unsigned int context, unsigned int type, unsigned int *timestamp) {
    struct kgsl_cmdstream_readtimestamp_ctxtid q = {.context_id = context, .type = type, .timestamp = 0xdead};
    int rc = quest_kgsl_ioctl(timestamp_mock, 42, IOCTL_KGSL_CMDSTREAM_READTIMESTAMP_CTXTID, &q);
    *timestamp = q.timestamp;
    return rc;
}
static void submit(unsigned int context, unsigned int timestamp);
static void recreate_context(void) {
    struct kgsl_drawctxt_destroy gone = {.drawctxt_id = 8};
    during_wait = NULL;
    assert(quest_kgsl_ioctl(timestamp_mock, 42, IOCTL_KGSL_DRAWCTXT_DESTROY, &gone) == 0);
    submit(8, 20);
}
static void timestamp_scenarios(void) {
    unsigned int ts;
    read_error = EACCES;
    assert(read_ts(5, KGSL_TIMESTAMP_RETIRED, &ts) == -1 && errno == EACCES && ts == 0xdead);
    submit(5, 10);
    assert(read_ts(5, KGSL_TIMESTAMP_QUEUED, &ts) == 0 && ts == 10 && waits == 0);
    assert(read_ts(5, KGSL_TIMESTAMP_CONSUMED, &ts) == -1 && errno == EACCES && ts == 0xdead);
    wait_error = ETIMEDOUT;
    assert(read_ts(5, KGSL_TIMESTAMP_RETIRED, &ts) == 0 && ts == 0 && waits == 1 && waited_for == 10);
    wait_error = 0;
    assert(read_ts(5, KGSL_TIMESTAMP_RETIRED, &ts) == 0 && ts == 10 && waits == 2);
    assert(read_ts(5, KGSL_TIMESTAMP_RETIRED, &ts) == 0 && ts == 10 && waits == 2);
    submit(5, 11);
    wait_error = ETIMEDOUT;
    assert(read_ts(5, KGSL_TIMESTAMP_RETIRED, &ts) == 0 && ts == 10 && waited_for == 11);
    wait_error = EIO;
    assert(read_ts(5, KGSL_TIMESTAMP_RETIRED, &ts) == -1 && errno == EIO);
    wait_error = 0;
    submit(5, 0xfffffffe);
    submit(5, 1);
    assert(read_ts(5, KGSL_TIMESTAMP_RETIRED, &ts) == 0 && ts == 1);
    struct kgsl_drawctxt_destroy gone = {.drawctxt_id = 5};
    assert(quest_kgsl_ioctl(timestamp_mock, 42, IOCTL_KGSL_DRAWCTXT_DESTROY, &gone) == 0);
    assert(read_ts(5, KGSL_TIMESTAMP_QUEUED, &ts) == -1 && errno == EACCES);
    for (unsigned int c = 100; c < 100 + 129; c++) submit(c, c);
    for (unsigned int c = 100; c < 100 + 129; c++) assert(read_ts(c, KGSL_TIMESTAMP_QUEUED, &ts) == 0 && ts == c);
    submit(8, 5);
    during_wait = recreate_context;
    assert(read_ts(8, KGSL_TIMESTAMP_RETIRED, &ts) == 0 && ts == 5);
    wait_error = ETIMEDOUT;
    assert(read_ts(8, KGSL_TIMESTAMP_RETIRED, &ts) == 0 && ts == 0 && waited_for == 20);
    wait_error = 0;
    assert(read_ts(8, KGSL_TIMESTAMP_RETIRED, &ts) == 0 && ts == 20);
    read_error = EIO;
    submit(6, 3);
    assert(read_ts(6, KGSL_TIMESTAMP_QUEUED, &ts) == -1 && errno == EIO);
}

int main(void) {
    timestamp_scenarios();
    struct kgsl_gpumem_alloc_id p = reset();
    assert(quest_kgsl_ioctl(mock, 42, IOCTL_KGSL_GPUMEM_ALLOC_ID, &p) == 0 && count == 1);
    p = reset(); old_error = EACCES;
    assert(quest_kgsl_ioctl(mock, 42, IOCTL_KGSL_GPUMEM_ALLOC_ID, &p) == 0);
    assert(count == 3 && p.id == 77 && p.size == 4096 && p.mmapsize == 8192);
    assert(p.flags == 0x20000 && p.gpuaddr == address);
    p = reset(); old_error = EPERM; address = 0;
    assert(quest_kgsl_ioctl(mock, 42, IOCTL_KGSL_GPUMEM_ALLOC_ID, &p) == 0 && p.gpuaddr == 0);
    p = reset(); old_error = ENOMEM;
    assert(quest_kgsl_ioctl(mock, 42, IOCTL_KGSL_GPUMEM_ALLOC_ID, &p) == -1 && errno == ENOMEM && count == 1);
    p = reset(); old_error = EACCES; alloc_error = ENOMEM;
    assert(quest_kgsl_ioctl(mock, 42, IOCTL_KGSL_GPUMEM_ALLOC_ID, &p) == -1 && errno == ENOMEM && count == 2);
    assert(p.id == 0 && p.size == 123);
    p = reset(); old_error = EACCES; info_error = EIO;
    assert(quest_kgsl_ioctl(mock, 42, IOCTL_KGSL_GPUMEM_ALLOC_ID, &p) == -1 && errno == EIO);
    assert(count == 4 && calls[3] == IOCTL_KGSL_GPUOBJ_FREE && p.id == 0);
    p = reset(); old_error = EACCES; info_error = EIO; free_error = ENOSPC;
    assert(quest_kgsl_ioctl(mock, 42, IOCTL_KGSL_GPUMEM_ALLOC_ID, &p) == -1 && errno == EIO && count == 4);
    p = reset(); old_error = EACCES;
    assert(quest_kgsl_ioctl(mock, 42, IOCTL_KGSL_GPUMEM_ALLOC_ID, NULL) == -1 && count == 1);
    p = reset(); old_error = EACCES;
    assert(quest_kgsl_ioctl(mock, 42, 0x1234, &p) == -1 && errno == EACCES && count == 1);
    struct kgsl_gpumem_free_id release = {.id = 77};
    reset(); old_error = EACCES;
    assert(quest_kgsl_ioctl(mock, 42, IOCTL_KGSL_GPUMEM_FREE_ID, &release) == 0 && count == 2);
    reset(); old_error = EPERM; free_error = EIO;
    assert(quest_kgsl_ioctl(mock, 42, IOCTL_KGSL_GPUMEM_FREE_ID, &release) == -1 && errno == EIO && count == 2);
    reset();
    assert(quest_kgsl_ioctl(mock, 42, IOCTL_KGSL_GPUMEM_FREE_ID, &release) == 0 && count == 1);
    puts("PASS: 12 GPUOBJ translation, passthrough, failure and cleanup scenarios");
    puts("PASS: denied timestamp reads answered from tracked submissions");
}
