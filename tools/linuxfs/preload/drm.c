/*
 * GEM handle emulation for the KGSL device presented as a DRM render node.
 *
 * Compositors and Mesa validate or convert dma-bufs with PRIME ioctls on the render node; KGSL
 * has no GEM. Handles only serve as tokens to these callers, so a table of duplicated dma-buf
 * descriptors stands in for the kernel's, and every other device is left to libdrm.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <time.h>
#include <unistd.h>

#define MAX_HANDLES 4096

/* DRM_IOCTL_GEM_CLOSE from drm.h: _IOW('d', 0x09, struct drm_gem_close { u32 handle, pad; }). */
struct gem_close {
  uint32_t handle;
  uint32_t pad;
};
#define GEM_CLOSE _IOW('d', 0x09, struct gem_close)

struct handle_entry {
  uint32_t handle;
  int dma_fd;
  int drm_fd;
};

static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static struct handle_entry handle_entries[MAX_HANDLES];
static uint32_t next_handle = 1;

/*
 * Descriptors Zink gave back with GEM_CLOSE are held a little longer before closing (see drmIoctl):
 * at most RETIRED_MAX of them, none past RETIRED_NS. Keeps #275's bound on memory without handing
 * the buffer's pages straight back to the allocator.
 */
#define RETIRED_MAX 64
#define RETIRED_NS 2000000000LL
static struct {
  int fd;
  long long at;
} retired[RETIRED_MAX];
static int retired_head, retired_count;
static dev_t kgsl_dev;
static int kgsl_state; /* 0 unknown, 1 known, -1 absent */

static int is_kgsl(int fd) {
  struct stat st;
  pthread_mutex_lock(&lock);
  if (kgsl_state == 0) {
    kgsl_state = stat("/dev/kgsl-3d0", &st) == 0 ? 1 : -1;
    kgsl_dev = st.st_rdev;
  }
  pthread_mutex_unlock(&lock);
  return kgsl_state > 0 && fstat(fd, &st) == 0 && S_ISCHR(st.st_mode) && st.st_rdev == kgsl_dev;
}

static void *real(const char *name) {
  void *fn = dlsym(RTLD_NEXT, name);
  if (!fn) {
    errno = ENOSYS;
  }
  return fn;
}

static int same_file_description(int a, int b) {
  if (a == b) {
    return 1;
  }
#ifdef SYS_kcmp
  int saved_errno = errno;
  long ret = syscall(SYS_kcmp, getpid(), getpid(), 0 /* KCMP_FILE */, a, b);
  errno = saved_errno;
  return ret == 0;
#else
  return 0;
#endif
}

static int find_handle_locked(int fd, uint32_t handle) {
  for (int i = 0; i < MAX_HANDLES; i++) {
    if (handle_entries[i].handle == handle &&
        same_file_description(handle_entries[i].drm_fd, fd)) {
      return i;
    }
  }
  return -1;
}

/*
 * The old table used its slot index as the public handle. Once #275 started freeing slots,
 * rapidly recreated swapchains immediately reused those numbers. Keep storage slots reusable but
 * make the public token monotonic so a stale handle can fail instead of aliasing a newer buffer.
 */
static uint32_t alloc_handle_locked(void) {
  for (int tries = 0; tries <= MAX_HANDLES; tries++) {
    uint32_t candidate = next_handle++;
    if (next_handle == 0) {
      next_handle = 1;
    }
    if (candidate == 0) {
      continue;
    }
    int used = 0;
    for (int i = 0; i < MAX_HANDLES; i++) {
      if (handle_entries[i].handle == candidate) {
        used = 1;
        break;
      }
    }
    if (!used) {
      return candidate;
    }
  }
  return 0;
}

static long long now_ns(void) {
  struct timespec ts;
  clock_gettime(CLOCK_MONOTONIC, &ts);
  return ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

/* Closes retired descriptors that are too old, or the oldest when room is needed for one more. */
static void reap_retired_locked(long long now, int need_room) {
  while (retired_count > 0 &&
         ((need_room && retired_count == RETIRED_MAX) || now - retired[retired_head].at >= RETIRED_NS)) {
    close(retired[retired_head].fd);
    retired_head = (retired_head + 1) % RETIRED_MAX;
    retired_count--;
  }
}

int drmPrimeFDToHandle(int fd, int prime_fd, uint32_t *handle) {
  if (!is_kgsl(fd)) {
    int (*fn)(int, int, uint32_t *) = (int (*)(int, int, uint32_t *)) real("drmPrimeFDToHandle");
    return fn ? fn(fd, prime_fd, handle) : -ENOSYS;
  }
  int dup_fd = fcntl(prime_fd, F_DUPFD_CLOEXEC, 0);
  if (dup_fd < 0) {
    return -errno;
  }
  int ret = -ENOMEM;
  pthread_mutex_lock(&lock);
  reap_retired_locked(now_ns(), 0);
  for (int i = 0; i < MAX_HANDLES; i++) {
    if (handle_entries[i].handle == 0) {
      uint32_t token = alloc_handle_locked();
      if (!token) {
        break;
      }
      handle_entries[i].handle = token;
      handle_entries[i].dma_fd = dup_fd;
      handle_entries[i].drm_fd = fd;
      *handle = token;
      ret = 0;
      break;
    }
  }
  pthread_mutex_unlock(&lock);
  if (ret) {
    close(dup_fd);
  }
  return ret;
}

int drmPrimeHandleToFD(int fd, uint32_t handle, uint32_t flags, int *prime_fd) {
  if (!is_kgsl(fd)) {
    int (*fn)(int, uint32_t, uint32_t, int *) = (int (*)(int, uint32_t, uint32_t, int *)) real("drmPrimeHandleToFD");
    return fn ? fn(fd, handle, flags, prime_fd) : -ENOSYS;
  }
  int ret = -EINVAL;
  pthread_mutex_lock(&lock);
  int index = find_handle_locked(fd, handle);
  if (index >= 0) {
    *prime_fd = fcntl(handle_entries[index].dma_fd, F_DUPFD_CLOEXEC, 0);
    ret = *prime_fd < 0 ? -errno : 0;
  }
  pthread_mutex_unlock(&lock);
  return ret;
}

/* Drops only a handle belonging to this KGSL file description; deferred keeps its fd open a while. */
static int release_handle(int fd, uint32_t handle, int deferred) {
  int found = 0;
  pthread_mutex_lock(&lock);
  int index = find_handle_locked(fd, handle);
  if (index >= 0) {
    if (deferred) {
      long long now = now_ns();
      reap_retired_locked(now, 1);
      int slot = (retired_head + retired_count) % RETIRED_MAX;
      retired[slot].fd = handle_entries[index].dma_fd;
      retired[slot].at = now;
      retired_count++;
    } else {
      close(handle_entries[index].dma_fd);
    }
    handle_entries[index] = (struct handle_entry){0};
    found = 1;
  }
  pthread_mutex_unlock(&lock);
  return found;
}

int drmCloseBufferHandle(int fd, uint32_t handle) {
  if (!is_kgsl(fd)) {
    int (*fn)(int, uint32_t) = (int (*)(int, uint32_t)) real("drmCloseBufferHandle");
    return fn ? fn(fd, handle) : -ENOSYS;
  }
  return release_handle(fd, handle, 0) ? 0 : -EINVAL;
}

/*
 * Zink gives its handles back with the GEM_CLOSE ioctl itself, not drmCloseBufferHandle
 * (zink_bo.c, bo_destroy). KGSL refuses the ioctl, so the duplicated descriptor stayed open and
 * kept the whole buffer alive. Preserve #275's reclamation without letting another KGSL file
 * description release the token or making a new buffer immediately inherit a retired token.
 * With the descriptor closed at once, games presenting through gamescope showed old frames again
 * (No Man's Sky, Adreno 840), so it goes through the retired queue instead.
 */
int drmIoctl(int fd, unsigned long request, void *arg) {
  if (request == GEM_CLOSE && arg && is_kgsl(fd)) {
    if (release_handle(fd, ((struct gem_close *) arg)->handle, 1)) {
      return 0;
    }
    errno = EINVAL;
    return -1;
  }
  int (*fn)(int, unsigned long, void *) = (int (*)(int, unsigned long, void *)) real("drmIoctl");
  if (!fn) {
    return -1;
  }
  return fn(fd, request, arg);
}

/*
 * fork() carries over only the calling thread, so a lock another thread was holding at that
 * instant stays held in the child by a thread that is not there to release it. Taking it before
 * the fork makes the copy consistent; the parent then unlocks it and the child, whose one thread
 * never locked it, gets a fresh one. (WinNative 79aa7f68.)
 */
static void drm_lock_before_fork(void) { pthread_mutex_lock(&lock); }
static void drm_unlock_after_fork(void) { pthread_mutex_unlock(&lock); }
static void drm_reset_after_fork(void) { pthread_mutex_init(&lock, NULL); }

__attribute__((constructor)) static void install_drm_fork_handlers(void) {
  pthread_atfork(drm_lock_before_fork, drm_unlock_after_fork, drm_reset_after_fork);
}
