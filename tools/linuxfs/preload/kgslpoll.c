#define _GNU_SOURCE
#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/ioctl.h>

#include "../../quest/kgsl_compat.h"

#define KGSL_CONTEXTS 1024

static struct {
  uint32_t submitted;
  uint32_t retired;
  int owner;
} contexts[KGSL_CONTEXTS];

static void report_denied(unsigned long request, int error) {
  static unsigned reports;
  int saved = errno;

  if (__atomic_fetch_add(&reports, 1, __ATOMIC_RELAXED) < 8)
    fprintf(stderr, "[QuestKGSL] ioctl 0x%lx refused, errno=%d\n", request & 0xff, error);
  errno = saved;
}

static void note_submit(unsigned int context_id, unsigned int timestamp) {
  uint32_t known;

  if (context_id >= KGSL_CONTEXTS) return;
  known = __atomic_load_n(&contexts[context_id].submitted, __ATOMIC_RELAXED);
  while ((int32_t)(timestamp - known) > 0 &&
         !__atomic_compare_exchange_n(&contexts[context_id].submitted, &known, timestamp, 1,
                                      __ATOMIC_RELAXED, __ATOMIC_RELAXED)) {
  }
}

static int wait_retired(int (*real)(int, unsigned long, void *), int fd, uint32_t id, uint32_t timestamp) {
  /* KGSL reads a timeout of 0 as wait forever. */
  struct kgsl_device_waittimestamp_ctxtid wait = {.context_id = id, .timestamp = timestamp, .timeout = 1};

  if (real(fd, IOCTL_KGSL_DEVICE_WAITTIMESTAMP_CTXTID, &wait) == 0) return 0;
  return errno == ETIMEDOUT || errno == ETIME || errno == EINTR || errno == EAGAIN ? 1 : -1;
}

/* shortcut: an unfinished timestamp sleeps a jiffy per poll, read the memstore instead if Quest's mmap of it is proven */
static int read_retired(int (*real)(int, unsigned long, void *), int fd,
                        struct kgsl_cmdstream_readtimestamp_ctxtid *read) {
  uint32_t id = read->context_id;
  uint32_t timestamp, submitted;
  int result;

  if (read->type != KGSL_TIMESTAMP_RETIRED || id >= KGSL_CONTEXTS ||
      __atomic_load_n(&contexts[id].owner, __ATOMIC_RELAXED) != fd + 1)
    return -1;
  timestamp = __atomic_load_n(&contexts[id].retired, __ATOMIC_RELAXED);
  submitted = __atomic_load_n(&contexts[id].submitted, __ATOMIC_RELAXED);
  /* KGSL checks a context's owner on every wait, which catches one that died with a reused descriptor. */
  if (timestamp == submitted && wait_retired(real, fd, id, timestamp) < 0) return -1;
  while (timestamp != submitted) {
    result = wait_retired(real, fd, id, timestamp + 1);
    if (result < 0) return -1;
    if (result > 0) break;
    timestamp++;
    __atomic_store_n(&contexts[id].retired, timestamp, __ATOMIC_RELAXED);
  }
  read->timestamp = timestamp;
  return 0;
}

/* Quest refuses some KGSL ioctls, and Mesa 27 turns a failed timestamp read into VK_ERROR_UNKNOWN. */
__attribute__((visibility("hidden"))) void bl_kgsl_track(int (*real)(int, unsigned long, void *),
                                                         int fd, unsigned long request, void *arg,
                                                         int *rc) {
  int saved = errno;

  if (arg == NULL) return;
  if (*rc == 0) {
    if (request == IOCTL_KGSL_GPU_COMMAND) {
      const struct kgsl_gpu_command *submit = arg;
      note_submit(submit->context_id, submit->timestamp);
    } else if (request == IOCTL_KGSL_GPU_AUX_COMMAND) {
      const struct kgsl_gpu_aux_command *submit = arg;
      note_submit(submit->context_id, submit->timestamp);
    } else if (request == IOCTL_KGSL_DRAWCTXT_CREATE) {
      const struct kgsl_drawctxt_create *create = arg;
      if (create->drawctxt_id < KGSL_CONTEXTS) {
        __atomic_store_n(&contexts[create->drawctxt_id].submitted, 0, __ATOMIC_RELAXED);
        __atomic_store_n(&contexts[create->drawctxt_id].retired, 0, __ATOMIC_RELAXED);
        /* User timestamps can leave gaps that would take one wait per integer, so their reads stay failed. */
        __atomic_store_n(&contexts[create->drawctxt_id].owner,
                         create->flags & KGSL_CONTEXT_USER_GENERATED_TS ? 0 : fd + 1, __ATOMIC_RELAXED);
      }
    } else if (request == IOCTL_KGSL_DRAWCTXT_DESTROY) {
      const struct kgsl_drawctxt_destroy *destroy = arg;
      if (destroy->drawctxt_id < KGSL_CONTEXTS)
        __atomic_store_n(&contexts[destroy->drawctxt_id].owner, 0, __ATOMIC_RELAXED);
    }
    return;
  }
  if (_IOC_TYPE(request) == KGSL_IOC_TYPE && (saved == EACCES || saved == EPERM ||
                                              request == IOCTL_KGSL_CMDSTREAM_READTIMESTAMP_CTXTID))
    report_denied(request, saved);
  if (request != IOCTL_KGSL_CMDSTREAM_READTIMESTAMP_CTXTID || saved == EBADF) return;
  if (read_retired(real, fd, arg) == 0)
    *rc = 0;
  else if (errno != saved)
    report_denied(IOCTL_KGSL_DEVICE_WAITTIMESTAMP_CTXTID, errno);
}

static int enabled(void) {
  static int cached = -1;
  const char *off;

  if (cached >= 0) return cached;
  off = getenv("BL_NO_KGSL_POLL_FIX");
  cached = !(off != NULL && off[0] == '1');
  return cached;
}

__attribute__((visibility("hidden"))) int bl_kgsl_poll(int (*real)(int, unsigned long, void *),
                                                       int fd, unsigned long request, void *arg,
                                                       int *rc) {
  const struct kgsl_device_waittimestamp_ctxtid *wait = arg;
  struct kgsl_cmdstream_readtimestamp_ctxtid read;
  int saved = errno;

  if (request != IOCTL_KGSL_DEVICE_WAITTIMESTAMP_CTXTID || arg == NULL || wait->timeout != 0 ||
      !enabled())
    return 0;
  read.context_id = wait->context_id;
  read.type = KGSL_TIMESTAMP_RETIRED;
  read.timestamp = 0;
  if (real(fd, IOCTL_KGSL_CMDSTREAM_READTIMESTAMP_CTXTID, &read) != 0) {
    errno = saved;
    return 0;
  }
  if ((int32_t)(read.timestamp - wait->timestamp) >= 0) {
    errno = saved;
    *rc = 0;
  } else {
    errno = ETIMEDOUT;
    *rc = -1;
  }
  return 1;
}
