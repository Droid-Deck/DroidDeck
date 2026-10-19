#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <linux/futex.h>
#include <linux/magic.h>
#include <pthread.h>
#include <stdarg.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <sys/vfs.h>
#include <unistd.h>

#ifndef SYS_openat2
#define SYS_openat2 437
#endif

#define BL_RESOLVE_BENEATH 0x08
#define BL_RESOLVE_IN_ROOT 0x10
// The kernel's MAXSYMLINKS.
#define BL_MAX_SYMLINKS 40
#define BL_PROC_ROOT_INO 1

#ifdef SYS_newfstatat
#define BL_SYS_FSTATAT SYS_newfstatat
typedef struct stat bl_stat;
#else
#define BL_SYS_FSTATAT SYS_fstatat64
typedef struct stat64 bl_stat;
#endif

struct bl_open_how {
  uint64_t flags;
  uint64_t mode;
  uint64_t resolve;
};

static long (*real_syscall)(long, ...);
static void *(*real_dlopen)(const char *, int);
static int openat2_missing;

void *dlopen(const char *filename, int flags) {
  if (!real_dlopen) {
    real_dlopen = (void *(*)(const char *, int))dlsym(RTLD_NEXT, "dlopen");
  }
  const char *basename = filename ? strrchr(filename, '/') : NULL;
  basename = basename ? basename + 1 : filename;
  // Steam's deep binding bypasses the robust-list shim under PRoot.
  if (basename && strcmp(basename, "vrclient.so") == 0) {
    flags &= ~RTLD_DEEPBIND;
  }
  return real_dlopen(filename, flags);
}

int bl_fsync_syscall(long number, const long *args, long *ret) __attribute__((visibility("hidden")));

static struct robust_list_head *thread_robust_head(void) {
  pthread_mutexattr_t attr;
  pthread_mutex_t mutex;
  struct robust_list_head *head = NULL;
  pthread_mutexattr_init(&attr);
  pthread_mutexattr_setrobust(&attr, PTHREAD_MUTEX_ROBUST);
  pthread_mutex_init(&mutex, &attr);
  pthread_mutexattr_destroy(&attr);
  if (pthread_mutex_lock(&mutex) != 0) {
    return NULL;
  }
  uintptr_t lo = (uintptr_t)&mutex, hi = lo + sizeof(mutex);
  for (uintptr_t p = lo; p + sizeof(void *) <= hi && !head; p += sizeof(void *)) {
    struct robust_list_head *candidate = *(struct robust_list_head **)p;
    struct robust_list_head copy;
    struct iovec local = {&copy, sizeof(copy)}, remote = {candidate, sizeof(copy)};
    if ((uintptr_t)candidate & (sizeof(void *) - 1)) {
      continue;
    }
    if (process_vm_readv(getpid(), &local, 1, &remote, 1, 0) != (ssize_t)sizeof(copy)) {
      continue;
    }
    uintptr_t entry = (uintptr_t)copy.list.next;
    if (entry >= lo && entry < hi && copy.futex_offset < 0 && copy.futex_offset > -256) {
      head = candidate;
    }
  }
  pthread_mutex_unlock(&mutex);
  pthread_mutex_destroy(&mutex);
  return head;
}

// Magic links are procfs links below a process's directory; procfs's root holds only plain ones (self, thread-self, mounts).
static int is_magic_link(int dirfd, char *link, size_t parent_len) {
  int fd = (int)real_syscall(SYS_openat, (long)dirfd, (long)link, (long)(O_PATH | O_NOFOLLOW | O_CLOEXEC), 0L);
  if (fd < 0) {
    return 0;
  }
  struct statfs fs;
  int procfs = real_syscall(SYS_fstatfs, (long)fd, (long)&fs) == 0 && fs.f_type == PROC_SUPER_MAGIC;
  real_syscall(SYS_close, (long)fd);
  if (!procfs) {
    return 0;
  }
  char saved = link[parent_len];
  link[parent_len] = 0;
  bl_stat parent;
  long result = real_syscall(BL_SYS_FSTATAT, (long)dirfd, (long)(parent_len ? link : "."), (long)&parent, 0L);
  link[parent_len] = saved;
  return result != 0 || parent.st_ino != BL_PROC_ROOT_INO;
}

// RESOLVE_IN_ROOT walked one component at a time: absolute symlinks restart at dirfd and ".." stops there.
static long openat_in_root(int dirfd, const char *path, int flags, long mode) {
  char resolved[PATH_MAX];
  char pending[PATH_MAX];
  size_t resolved_len = 0;
  int symlinks = 0;
  int have_root = 0;
  int root_on_procfs = 0;
  dev_t root_dev = 0;
  // O_NOFOLLOW and O_CREAT | O_EXCL leave a final symlink alone, unless a trailing slash follows it.
  int nofollow = (flags & O_NOFOLLOW) || (flags & (O_CREAT | O_EXCL)) == (O_CREAT | O_EXCL);
  const char *rest = path;
  for (;;) {
    while (*rest == '/') {
      rest++;
    }
    if (!*rest) {
      break;
    }
    const char *name = rest;
    const char *end = name;
    while (*end && *end != '/') {
      end++;
    }
    size_t len = (size_t)(end - name);
    rest = end;
    while (*rest == '/') {
      rest++;
    }
    int last = !*rest;
    int trailing_slash = last && rest != end;
    if (len == 1 && name[0] == '.') {
      continue;
    }
    if (len == 2 && name[0] == '.' && name[1] == '.') {
      while (resolved_len > 0 && resolved[resolved_len - 1] != '/') {
        resolved_len--;
      }
      if (resolved_len > 0) {
        resolved_len--;
      }
      continue;
    }
    size_t separator = resolved_len > 0;
    size_t candidate_len = resolved_len + separator + len;
    if (candidate_len + 1 >= PATH_MAX) {
      errno = ENAMETOOLONG;
      return -1;
    }
    if (separator) {
      resolved[resolved_len] = '/';
    }
    memcpy(resolved + resolved_len + separator, name, len);
    resolved[candidate_len] = 0;
    bl_stat st;
    int follow = !last || trailing_slash || !nofollow;
    long stat_result = follow ? real_syscall(BL_SYS_FSTATAT, (long)dirfd, (long)resolved, (long)&st, (long)AT_SYMLINK_NOFOLLOW) : -1;
    if (stat_result == 0 && S_ISLNK(st.st_mode)) {
      if (++symlinks > BL_MAX_SYMLINKS) {
        errno = ELOOP;
        return -1;
      }
      if (!have_root) {
        bl_stat root;
        struct statfs root_fs;
        if (real_syscall(BL_SYS_FSTATAT, (long)dirfd, (long)".", (long)&root, 0L) != 0) {
          return -1;
        }
        root_dev = root.st_dev;
        root_on_procfs = real_syscall(SYS_fstatfs, (long)dirfd, (long)&root_fs) == 0 && root_fs.f_type == PROC_SUPER_MAGIC;
        have_root = 1;
      }
      if ((st.st_dev != root_dev || root_on_procfs) && is_magic_link(dirfd, resolved, resolved_len)) {
        // RESOLVE_IN_ROOT refuses magic links.
        errno = EXDEV;
        return -1;
      }
      size_t rest_len = strlen(end);
      if (rest_len + 1 >= PATH_MAX) {
        errno = ENAMETOOLONG;
        return -1;
      }
      char *tail = pending + PATH_MAX - rest_len - 1;
      memmove(tail, end, rest_len + 1);
      long target_len = real_syscall(SYS_readlinkat, (long)dirfd, (long)resolved, (long)pending, (long)(PATH_MAX - rest_len));
      if (target_len <= 0) {
        if (target_len == 0) {
          errno = ENOENT;
        }
        return -1;
      }
      if ((size_t)target_len + rest_len >= PATH_MAX) {
        errno = ENAMETOOLONG;
        return -1;
      }
      memmove(pending + target_len, tail, rest_len + 1);
      if (pending[0] == '/') {
        char saved = pending[target_len];
        pending[target_len] = 0;
        const char *inside = pending;
        while (*inside == '/') {
          inside++;
        }
        bl_stat probe;
        int host = *inside && real_syscall(BL_SYS_FSTATAT, (long)dirfd, (long)inside, (long)&probe, (long)AT_SYMLINK_NOFOLLOW) != 0 &&
                   errno == ENOENT && real_syscall(BL_SYS_FSTATAT, (long)AT_FDCWD, (long)pending, (long)&probe, 0L) == 0;
        pending[target_len] = saved;
        if (host) {
          return real_syscall(SYS_openat, (long)AT_FDCWD, (long)pending, (long)flags, mode);
        }
        resolved_len = 0;
      }
      rest = pending;
      continue;
    }
    if (last) {
      resolved_len = candidate_len;
      if (trailing_slash) {
        resolved[resolved_len++] = '/';
      }
      break;
    }
    if (stat_result != 0) {
      return -1;
    }
    if (!S_ISDIR(st.st_mode)) {
      errno = ENOTDIR;
      return -1;
    }
    resolved_len = candidate_len;
  }
  if (resolved_len == 0) {
    resolved[resolved_len++] = '.';
  }
  resolved[resolved_len] = 0;
  return real_syscall(SYS_openat, (long)dirfd, (long)resolved, (long)flags, mode);
}

static long openat2_via_openat(const long *args) {
  const char *path = (const char *)args[1];
  const struct bl_open_how *how = (const struct bl_open_how *)args[2];
  if (!path || !how || (size_t)args[3] < sizeof(*how)) {
    errno = EINVAL;
    return -1;
  }
  if ((how->resolve & BL_RESOLVE_IN_ROOT) && (int)args[0] != AT_FDCWD) {
    return openat_in_root((int)args[0], path, (int)how->flags, (long)how->mode);
  }
  if (how->resolve & BL_RESOLVE_IN_ROOT) {
    while (*path == '/') {
      path++;
    }
    if (!*path) {
      path = ".";
    }
  } else if ((how->resolve & BL_RESOLVE_BENEATH) && *path == '/') {
    errno = EXDEV;
    return -1;
  }
  return real_syscall(SYS_openat, args[0], (long)path, (long)(int)how->flags, (long)how->mode);
}

static int execveat_is_execve(const long *args) {
  const char *path = (const char *)args[1];
  if (!path || (args[4] & (AT_EMPTY_PATH | AT_SYMLINK_NOFOLLOW))) {
    return 0;
  }
  return path[0] == '/' || (int)args[0] == AT_FDCWD;
}

long syscall(long number, ...) {
  va_list ap;
  long args[6];
  va_start(ap, number);
  for (int i = 0; i < 6; i++) {
    args[i] = va_arg(ap, long);
  }
  va_end(ap);
  if (number == SYS_get_robust_list && (args[0] == 0 || args[0] == gettid())) {
    struct robust_list_head *head = thread_robust_head();
    if (!head) {
      errno = ENOSYS;
      return -1;
    }
    *(struct robust_list_head **)args[1] = head;
    *(size_t *)args[2] = sizeof(*head);
    return 0;
  }
  long handled;
  if (bl_fsync_syscall(number, args, &handled)) {
    return handled;
  }
  if (!real_syscall) {
    real_syscall = (long (*)(long, ...)) dlsym(RTLD_NEXT, "syscall");
  }
  if (number == SYS_openat2) {
    if (!__atomic_load_n(&openat2_missing, __ATOMIC_RELAXED)) {
      long fd = real_syscall(number, args[0], args[1], args[2], args[3]);
      if (fd != -1 || errno != ENOSYS) {
        return fd;
      }
      __atomic_store_n(&openat2_missing, 1, __ATOMIC_RELAXED);
    }
    return openat2_via_openat(args);
  }
  if (number == SYS_execveat && execveat_is_execve(args)) {
    return real_syscall(SYS_execve, args[1], args[2], args[3]);
  }
  return real_syscall(number, args[0], args[1], args[2], args[3], args[4], args[5]);
}
