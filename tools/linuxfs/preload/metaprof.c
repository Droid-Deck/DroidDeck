/*
 * EXPERIMENT: per-call log of the Steam client's metadata calls on library paths, to find what
 * dominates its allocation phase on a FUSE-served SD library. On only while
 * /root/.droiddeck-metaprof exists; lines go to /tmp/bl-meta.log.
 */
#define _GNU_SOURCE
#include <dirent.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <time.h>
#include <unistd.h>

static int prof_state = -1;
static volatile unsigned long prof_lines;

uint64_t bl_meta_now(void) __attribute__((visibility("hidden")));
uint64_t bl_meta_now(void) {
  struct timespec ts;
  clock_gettime(CLOCK_MONOTONIC, &ts);
  return (uint64_t)ts.tv_sec * 1000000000ULL + (uint64_t)ts.tv_nsec;
}

int bl_meta_on(void) __attribute__((visibility("hidden")));
int bl_meta_on(void) {
  if (prof_state < 0) {
    struct stat st;
    prof_state = strcmp(program_invocation_short_name, "steam") == 0 &&
                 syscall(SYS_newfstatat, AT_FDCWD, "/root/.droiddeck-metaprof", &st, 0) == 0;
  }
  return prof_state;
}

void bl_meta_log(const char *op, int dirfd, const char *path, int flags, uint64_t start, long result, int err)
    __attribute__((visibility("hidden")));
void bl_meta_log(const char *op, int dirfd, const char *path, int flags, uint64_t start, long result, int err) {
  if (!bl_meta_on() || path == NULL) return;
  if (path[0] == '/' && strstr(path, "steamapps") == NULL && strstr(path, "droiddeck-sd") == NULL) return;
  if (__atomic_add_fetch(&prof_lines, 1, __ATOMIC_RELAXED) > 400000) return;
  uint64_t end = bl_meta_now();
  char line[768];
  int n = snprintf(line, sizeof(line), "%llu %ld %s fd=%d fl=0x%x us=%llu r=%ld e=%d %s\n",
                   (unsigned long long)(end / 1000000ULL), (long)syscall(SYS_gettid), op, dirfd, flags,
                   (unsigned long long)((end - start) / 1000ULL), result, result < 0 ? err : 0, path);
  if (n <= 0 || (size_t)n >= sizeof(line)) return;
  int fd = (int)syscall(SYS_openat, AT_FDCWD, "/tmp/bl-meta.log", O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0600);
  if (fd < 0) return;
  syscall(SYS_write, fd, line, n);
  syscall(SYS_close, fd);
}

#define WRAP_PATH(name, ret, sig, args, dirfd, path, flags)                 \
  ret name sig {                                                           \
    static ret (*real) sig;                                                \
    if (real == NULL) real = dlsym(RTLD_NEXT, #name);                      \
    if (!bl_meta_on()) return real args;                                   \
    uint64_t t0 = bl_meta_now();                                           \
    ret r = real args;                                                     \
    int e = errno;                                                         \
    bl_meta_log(#name, dirfd, path, flags, t0, (long)(r), e);              \
    errno = e;                                                             \
    return r;                                                              \
  }

WRAP_PATH(stat, int, (const char *p, struct stat *s), (p, s), -100, p, 0)
WRAP_PATH(lstat, int, (const char *p, struct stat *s), (p, s), -100, p, 0)
WRAP_PATH(fstatat, int, (int d, const char *p, struct stat *s, int f), (d, p, s, f), d, p, f)
WRAP_PATH(statx, int, (int d, const char *p, int f, unsigned m, struct statx *s), (d, p, f, m, s), d, p, f)

int stat64(const char *p, struct stat64 *s) __attribute__((alias("stat")));
int lstat64(const char *p, struct stat64 *s) __attribute__((alias("lstat")));
int fstatat64(int d, const char *p, struct stat64 *s, int f) __attribute__((alias("fstatat")));
