/* SPDX-License-Identifier: GPL-3.0-or-later */
#include "kgsl_compat.h"
#include <errno.h>
#include <limits.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#ifdef __ANDROID__
#include <android/log.h>
#endif

#if __SIZEOF_POINTER__ == 8
_Static_assert(sizeof(struct kgsl_gpumem_alloc_id) == 48, "KGSL alloc ABI");
_Static_assert(sizeof(struct kgsl_gpuobj_alloc) == 48, "GPUOBJ alloc ABI");
_Static_assert(sizeof(struct kgsl_gpuobj_free) == 32, "GPUOBJ free ABI");
_Static_assert(sizeof(struct kgsl_gpuobj_info) == 48, "GPUOBJ info ABI");
#endif

static void report(const char *operation, int error) {
    static unsigned successes, failures;
    unsigned *counter = error ? &failures : &successes;
    if (__atomic_fetch_add(counter, 1, __ATOMIC_RELAXED) >= (error ? 8u : 1u)) return;
    int saved = errno;
#ifdef __ANDROID__
    __android_log_print(error ? ANDROID_LOG_ERROR : ANDROID_LOG_INFO, "QuestKGSL",
                        "%s: GPUOBJ compatibility path, errno=%d", operation, error);
#else
    fprintf(stderr, "[QuestKGSL] %s: GPUOBJ compatibility path, errno=%d\n", operation, error);
#endif
    errno = saved;
}

struct tracked_context {
    unsigned int context, queued, retired, generation;
};

static struct tracked_context *tracked;
static unsigned int tracked_count, tracked_capacity;
static unsigned int next_generation;
static char tracked_lock;

static void tracked_acquire(void) {
    while (__atomic_test_and_set(&tracked_lock, __ATOMIC_ACQUIRE)) {}
}

static void tracked_release(void) {
    __atomic_clear(&tracked_lock, __ATOMIC_RELEASE);
}

static int tracked_find(unsigned int context, int create) {
    int free_slot = -1;
    for (unsigned int i = 0; i < tracked_count; i++) {
        if (tracked[i].context == context) return (int)i;
        if (free_slot < 0 && tracked[i].context == 0) free_slot = (int)i;
    }
    if (!create) return -1;
    if (free_slot < 0) {
        if (tracked_count == tracked_capacity) {
            unsigned int capacity = tracked_capacity ? tracked_capacity * 2 : 32;
            struct tracked_context *grown = realloc(tracked, capacity * sizeof *grown);
            if (grown == NULL) return -1;
            tracked = grown;
            tracked_capacity = capacity;
        }
        free_slot = (int)tracked_count++;
    }
    tracked[free_slot].context = context;
    tracked[free_slot].queued = tracked[free_slot].retired = 0;
    tracked[free_slot].generation = ++next_generation;
    return free_slot;
}

static void track_submit(unsigned int context, unsigned int timestamp) {
    if (context == 0) return;
    tracked_acquire();
    int slot = tracked_find(context, 1);
    if (slot < 0) { tracked_release(); return; }
    if ((int)(timestamp - tracked[slot].queued) < 0) tracked[slot].retired = 0;
    tracked[slot].queued = timestamp;
    tracked_release();
}

static struct tracked_context track_detach(unsigned int context) {
    struct tracked_context gone = {0};
    tracked_acquire();
    int slot = tracked_find(context, 0);
    if (slot >= 0) {
        gone = tracked[slot];
        tracked[slot].context = 0;
    }
    tracked_release();
    return gone;
}

static void track_restore(struct tracked_context entry) {
    if (entry.context == 0) return;
    tracked_acquire();
    if (tracked_find(entry.context, 0) < 0) {
        int slot = tracked_find(entry.context, 1);
        if (slot >= 0) {
            unsigned int generation = tracked[slot].generation;
            tracked[slot] = entry;
            tracked[slot].generation = generation;
        }
    }
    tracked_release();
}

/* Quest's SELinux policy denies CMDSTREAM_READTIMESTAMP_CTXTID, which Mesa 26's Turnip needs to wait on
 * timeline semaphores; a zero-timeout WAITTIMESTAMP on the last submitted timestamp answers it. */
static int read_timestamp(quest_ioctl_fn real, int fd, struct kgsl_cmdstream_readtimestamp_ctxtid *query) {
    tracked_acquire();
    int slot = tracked_find(query->context_id, 0);
    unsigned int queued = slot < 0 ? 0 : tracked[slot].queued;
    unsigned int retired = slot < 0 ? 0 : tracked[slot].retired;
    unsigned int generation = slot < 0 ? 0 : tracked[slot].generation;
    tracked_release();
    if (slot < 0 || (query->type != KGSL_TIMESTAMP_RETIRED && query->type != KGSL_TIMESTAMP_QUEUED)) {
        errno = EACCES;
        return -1;
    }

    if (query->type == KGSL_TIMESTAMP_RETIRED && (int)(queued - retired) > 0) {
        struct kgsl_device_waittimestamp_ctxtid wait = {
            .context_id = query->context_id, .timestamp = queued, .timeout = 0};
        if (real(fd, IOCTL_KGSL_DEVICE_WAITTIMESTAMP_CTXTID, &wait) == 0) {
            retired = queued;
            tracked_acquire();
            slot = tracked_find(query->context_id, 0);
            if (slot >= 0 && tracked[slot].generation == generation &&
                (int)(retired - tracked[slot].retired) > 0) tracked[slot].retired = retired;
            tracked_release();
        } else if (errno != ETIMEDOUT) {
            return -1;
        }
    }
    query->timestamp = query->type == KGSL_TIMESTAMP_RETIRED ? retired : queued;
    return 0;
}

int quest_kgsl_ioctl(quest_ioctl_fn real, int fd, unsigned long request, void *arg) {
    struct tracked_context detached = {0};
    if (request == IOCTL_KGSL_DRAWCTXT_DESTROY && arg != NULL)
        detached = track_detach(((const struct kgsl_drawctxt_destroy *)arg)->drawctxt_id);
    int rc = real(fd, request, arg);
    if (request == IOCTL_KGSL_DRAWCTXT_DESTROY && rc != 0) {
        int error = errno;
        track_restore(detached);
        errno = error;
    }
    if (rc == 0 && arg != NULL && request == IOCTL_KGSL_GPU_COMMAND) {
        const struct kgsl_gpu_command *submit = arg;
        track_submit(submit->context_id, submit->timestamp);
    }
    if (rc != -1 || (errno != EACCES && errno != EPERM) || arg == NULL) return rc;

    if (request == IOCTL_KGSL_CMDSTREAM_READTIMESTAMP_CTXTID)
        return read_timestamp(real, fd, arg);

    if (request == IOCTL_KGSL_GPUMEM_ALLOC_ID) {
        struct kgsl_gpumem_alloc_id *old = arg;
        struct kgsl_gpuobj_alloc alloc = {.size = old->size, .flags = old->flags};
        rc = real(fd, IOCTL_KGSL_GPUOBJ_ALLOC, &alloc);
        if (rc != 0) { report("alloc failed", errno); return rc; }
        struct kgsl_gpuobj_info info = {.id = alloc.id};
        rc = real(fd, IOCTL_KGSL_GPUOBJ_INFO, &info);
        int error = errno;
        if (rc == 0 && (info.size > SIZE_MAX || alloc.mmapsize > SIZE_MAX ||
                       info.gpuaddr > ULONG_MAX || info.flags > UINT_MAX)) {
            rc = -1;
            error = EOVERFLOW;
        }
        if (rc != 0) {
            struct kgsl_gpuobj_free release = {.id = alloc.id};
            if (real(fd, IOCTL_KGSL_GPUOBJ_FREE, &release) != 0)
                report("cleanup failed", errno);
            errno = error;
            report("info failed", error);
            return rc;
        }
        old->id = alloc.id;
        old->flags = (unsigned int)info.flags;
        old->size = info.size;
        old->mmapsize = alloc.mmapsize;
        old->gpuaddr = info.gpuaddr;
        report("allocation succeeded", 0);
        return 0;
    }
    if (request == IOCTL_KGSL_GPUMEM_FREE_ID) {
        const struct kgsl_gpumem_free_id *old = arg;
        struct kgsl_gpuobj_free release = {.id = old->id};
        rc = real(fd, IOCTL_KGSL_GPUOBJ_FREE, &release);
        if (rc != 0) report("free failed", errno);
    }
    return rc;
}
