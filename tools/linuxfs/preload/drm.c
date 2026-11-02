#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdint.h>
#include <string.h>
#include <stdlib.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

#define MAX_HANDLES 4096
#ifndef F_DUPFD_QUERY
#define F_DUPFD_QUERY 1027
#endif
#define PVR_HANDLE_BIT UINT32_C(0x80000000)

struct gem_close {
  uint32_t handle;
  uint32_t pad;
};
#define GEM_CLOSE _IOW('d', 0x09, struct gem_close)

struct handle_entry {
  uint32_t handle;
  int dma_fd;
  int drm_fd;
  int owned_drm_fd; /* PowerVR: pin the open file description, not its reusable fd number. */
  int powervr;
};

static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static struct handle_entry handle_entries[MAX_HANDLES];
static uint32_t next_handle = 1;
static uint32_t next_pvr_handle = PVR_HANDLE_BIT;
static dev_t kgsl_dev;
static int kgsl_state;

static int is_kgsl(int fd) {
  struct stat st;
  pthread_mutex_lock(&lock);
  if (kgsl_state == 0) {
    kgsl_state = stat("/dev/kgsl-3d0", &st) == 0 ? 1 : -1;
    if (kgsl_state > 0) kgsl_dev = st.st_rdev;
  }
  pthread_mutex_unlock(&lock);
  return kgsl_state > 0 && fstat(fd, &st) == 0 && S_ISCHR(st.st_mode) && st.st_rdev == kgsl_dev;
}

/* Keep the opt-in separate from GPU detection: manual ICDs must retain native DRM behavior. */
struct bl_drm_version {
  int major, minor, patch;
  size_t name_len;
  char *name;
  size_t date_len;
  char *date;
  size_t desc_len;
  char *desc;
};
#define BL_DRM_VERSION _IOWR('d', 0x00, struct bl_drm_version)

static int is_powervr(int fd) {
  const char *enabled = getenv("BL_POWERVR_DRM_HANDLES");
  if (!enabled || strcmp(enabled, "1")) return 0;
  const char *path = getenv("BL_POWERVR_RENDER_NODE");
  if (!path || !*path) return 0;
  struct stat node, candidate;
  if (stat(path, &node) || fstat(fd, &candidate) ||
      !S_ISCHR(candidate.st_mode) || !S_ISCHR(node.st_mode) ||
      candidate.st_rdev != node.st_rdev) return 0;
  char name[16] = {0};
  struct bl_drm_version version = {.name_len = sizeof(name) - 1, .name = name};
  /* Bypass the preload's ioctl dispatcher to avoid recursively entering this hook. */
  return syscall(SYS_ioctl, fd, BL_DRM_VERSION, &version) == 0 &&
      version.name_len == 3 && !memcmp(name, "pvr", 3);
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
  long ret = syscall(SYS_kcmp, getpid(), getpid(), 0  , a, b);
  errno = saved_errno;
  return ret == 0;
#else
  return 0;
#endif
}

/*
 * Android kernels before 6.10 have no F_DUPFD_QUERY, while the app seccomp policy blocks kcmp.
 * F_SETOWN_EX updates the open file description, so a duplicate observes a temporary owner marker
 * and an independent open of the same render node does not. Restore the original owner before
 * returning; no signal is armed because these DRM descriptors do not have O_ASYNC set.
 */
static int same_owner_description(int owned, int fd) {
  struct f_owner_ex original_owned, original_fd, observed;
  if (fcntl(owned, F_GETOWN_EX, &original_owned) < 0 ||
      fcntl(fd, F_GETOWN_EX, &original_fd) < 0) {
    return -1;
  }
  const struct f_owner_ex candidates[] = {
      {.type = F_OWNER_TID, .pid = getpid()},
      {.type = F_OWNER_PID, .pid = getpid()},
      {.type = F_OWNER_PGRP, .pid = getpgrp()},
  };
  const struct f_owner_ex *marker = NULL;
  for (size_t i = 0; i < sizeof(candidates) / sizeof(candidates[0]); i++) {
    if (candidates[i].type != original_fd.type || candidates[i].pid != original_fd.pid) {
      marker = &candidates[i];
      break;
    }
  }
  if (!marker || fcntl(owned, F_SETOWN_EX, marker) < 0) return -1;
  int observed_result = fcntl(fd, F_GETOWN_EX, &observed);
  int observed_errno = errno;
  int restore_result = fcntl(owned, F_SETOWN_EX, &original_owned);
  int restore_errno = errno;
  if (restore_result < 0) {
    errno = restore_errno;
    return -1;
  }
  if (observed_result < 0) {
    errno = observed_errno;
    return -1;
  }
  return observed.type == marker->type && observed.pid == marker->pid;
}

static int same_owned_description(int owned, int fd) {
  int saved_errno = errno;
  int result = fcntl(owned, F_DUPFD_QUERY, fd);
  if (result >= 0) {
    errno = saved_errno;
    return result == 1;
  }
#ifdef SYS_kcmp
  long compared = syscall(SYS_kcmp, getpid(), getpid(), 0 /* KCMP_FILE */, owned, fd);
  if (compared >= 0) {
    errno = saved_errno;
    return compared == 0;
  }
#endif
  int owner_match = same_owner_description(owned, fd);
  errno = saved_errno;
  return owner_match == 1;
}

static int find_handle_locked(int fd, uint32_t handle) {
  for (int i = 0; i < MAX_HANDLES; i++) {
    if (handle_entries[i].handle == handle &&
        (handle_entries[i].powervr
          ? same_owned_description(handle_entries[i].owned_drm_fd, fd)
          : same_file_description(handle_entries[i].drm_fd, fd))) {
      return i;
    }
  }
  return -1;
}

static uint32_t alloc_handle_locked(int powervr) {
  for (int tries = 0; tries <= MAX_HANDLES; tries++) {
    uint32_t candidate;
    if (powervr) {
      candidate = next_pvr_handle++;
      if (!(next_pvr_handle & PVR_HANDLE_BIT)) next_pvr_handle = PVR_HANDLE_BIT;
    } else {
      candidate = next_handle++;
      if (next_handle == PVR_HANDLE_BIT) next_handle = 1;
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

int drmPrimeFDToHandle(int fd, int prime_fd, uint32_t *handle) {
  int kgsl = is_kgsl(fd);
  int powervr = !kgsl && is_powervr(fd);
  if (!kgsl) {
    int (*fn)(int, int, uint32_t *) = (int (*)(int, int, uint32_t *)) real("drmPrimeFDToHandle");
    if (!fn) return -ENOSYS;
    int result = fn(fd, prime_fd, handle);
    if (!result || !powervr || errno != EINVAL) return result;
  }
  if (!handle) { errno = EINVAL; return -EINVAL; }
  int owner = -1;
  if (powervr) {
    owner = fcntl(fd, F_DUPFD_CLOEXEC, 0);
    if (owner < 0) return -errno;
    /* The old-kernel owner probe mutates shared OFD state briefly; serialize it with lookups. */
    pthread_mutex_lock(&lock);
    int owner_matches = same_owned_description(owner, fd);
    pthread_mutex_unlock(&lock);
    if (!owner_matches) {
      close(owner);
      errno = EOPNOTSUPP;
      return -EOPNOTSUPP;
    }
  }
  int dup_fd = fcntl(prime_fd, F_DUPFD_CLOEXEC, 0);
  if (dup_fd < 0) {
    int error = errno;
    if (owner >= 0) close(owner);
    errno = error;
    return -error;
  }
  int ret = -ENOMEM;
  pthread_mutex_lock(&lock);
  for (int i = 0; i < MAX_HANDLES; i++) {
    if (handle_entries[i].handle == 0) {
      uint32_t token = alloc_handle_locked(powervr);
      if (!token) {
        break;
      }
      handle_entries[i].handle = token;
      handle_entries[i].dma_fd = dup_fd;
      handle_entries[i].drm_fd = fd;
      handle_entries[i].owned_drm_fd = owner;
      handle_entries[i].powervr = powervr;
      *handle = token;
      ret = 0;
      break;
    }
  }
  pthread_mutex_unlock(&lock);
  if (ret) {
    close(dup_fd);
    if (owner >= 0) close(owner);
    errno = -ret;
  }
  return ret;
}

int drmPrimeHandleToFD(int fd, uint32_t handle, uint32_t flags, int *prime_fd) {
  int kgsl = is_kgsl(fd);
  int ret = -EINVAL;
  pthread_mutex_lock(&lock);
  int index = find_handle_locked(fd, handle);
  if (index >= 0) {
    if (!prime_fd || (handle_entries[index].powervr && (flags & ~(O_CLOEXEC | O_RDWR)))) {
      errno = EINVAL;
    } else {
      int command = handle_entries[index].powervr && !(flags & O_CLOEXEC) ? F_DUPFD : F_DUPFD_CLOEXEC;
      *prime_fd = fcntl(handle_entries[index].dma_fd, command, 0);
      ret = *prime_fd < 0 ? -errno : 0;
    }
  }
  pthread_mutex_unlock(&lock);
  if (index >= 0 || kgsl) return ret;
  /* Unknown handles (including real PowerVR GEM handles) retain kernel semantics. */
  int (*fn)(int, uint32_t, uint32_t, int *) =
      (int (*)(int, uint32_t, uint32_t, int *)) real("drmPrimeHandleToFD");
  return fn ? fn(fd, handle, flags, prime_fd) : -ENOSYS;
}

static int release_handle(int fd, uint32_t handle) {
  int found = 0;
  pthread_mutex_lock(&lock);
  int index = find_handle_locked(fd, handle);
  if (index >= 0) {
    close(handle_entries[index].dma_fd);
    if (handle_entries[index].powervr) close(handle_entries[index].owned_drm_fd);
    handle_entries[index] = (struct handle_entry){0};
    found = 1;
  }
  pthread_mutex_unlock(&lock);
  return found;
}

int drmCloseBufferHandle(int fd, uint32_t handle) {
  if (release_handle(fd, handle)) return 0;
  if (is_kgsl(fd)) { errno = EINVAL; return -EINVAL; }
  int (*fn)(int, uint32_t) = (int (*)(int, uint32_t)) real("drmCloseBufferHandle");
  return fn ? fn(fd, handle) : -ENOSYS;
}

__attribute__((visibility("hidden"))) int bl_drm_gem_close(int fd, unsigned long request, void *arg, int *rc) {
  if (request != GEM_CLOSE || !arg) {
    return 0;
  }
  if (release_handle(fd, ((struct gem_close *) arg)->handle)) {
    *rc = 0;
  } else if (is_kgsl(fd)) {
    errno = EINVAL;
    *rc = -1;
  } else {
    return 0;
  }
  return 1;
}

static void drm_lock_before_fork(void) { pthread_mutex_lock(&lock); }
static void drm_unlock_after_fork(void) { pthread_mutex_unlock(&lock); }
static void drm_reset_after_fork(void) { pthread_mutex_init(&lock, NULL); }

__attribute__((constructor)) static void install_drm_fork_handlers(void) {
  pthread_atfork(drm_lock_before_fork, drm_unlock_after_fork, drm_reset_after_fork);
}
