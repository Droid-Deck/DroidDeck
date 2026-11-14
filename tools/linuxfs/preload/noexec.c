#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/statvfs.h>
#include <unistd.h>

#if defined(__aarch64__) || defined(NOEXEC_TEST)

static int in_fex(void) {
  static int known = -1;
  if (known < 0) {
    char exe[512];
    ssize_t n = readlink("/proc/self/exe", exe, sizeof exe - 1);
    exe[n > 0 ? n : 0] = 0;
    const char *name = strrchr(exe, '/');
    name = name ? name + 1 : exe;
    known = !strcmp(name, "FEX") || !strcmp(name, "FEXInterpreter") || !strcmp(name, "FEXLoader");
  }
  return known;
}

__attribute__((visibility("hidden"))) int bl_noexec_retry(int prot, int flags, int fd) {
  int error = errno;
  struct statvfs vfs;
  int retry = (error == EACCES || error == EPERM) && (prot & PROT_EXEC) && fd >= 0 && !(flags & MAP_ANONYMOUS) &&
              in_fex() && fstatvfs(fd, &vfs) == 0 && (vfs.f_flag & ST_NOEXEC);
  errno = error;
  return retry;
}

static int (*real_mprotect)(void *, size_t, int);

static int private_range(uintptr_t start, uintptr_t end, int *readable) {
  char buf[4096];
  size_t used = 0;
  uintptr_t covered = start;
  int fd = open("/proc/self/maps", O_RDONLY | O_CLOEXEC);
  if (fd < 0) return 0;
  *readable = 1;
  for (;;) {
    if (used >= sizeof buf - 1) used = 0;
    ssize_t n = read(fd, buf + used, sizeof buf - 1 - used);
    if (n <= 0) break;
    used += (size_t)n;
    buf[used] = 0;
    char *line = buf, *nl;
    while ((nl = strchr(line, '\n'))) {
      *nl = 0;
      char *p = line;
      uintptr_t lo = strtoull(p, &p, 16), hi = strtoull(p + 1, &p, 16);
      char perms[5] = {0};
      if (*p == ' ') memcpy(perms, p + 1, 4);
      line = nl + 1;
      if (hi <= covered || lo >= end) continue;
      if (lo > covered || perms[3] != 'p') {
        close(fd);
        return 0;
      }
      if (perms[0] != 'r') *readable = 0;
      covered = hi;
      if (covered >= end) {
        close(fd);
        return 1;
      }
    }
    used = strlen(line);
    memmove(buf, line, used);
  }
  close(fd);
  return 0;
}

static int copy_to_anonymous(void *addr, size_t len, int prot) {
  uintptr_t page = (uintptr_t)sysconf(_SC_PAGESIZE);
  uintptr_t start = (uintptr_t)addr, end = (start + len + page - 1) & ~(page - 1);
  int readable;
  if (start & (page - 1) || end <= start || !private_range(start, end, &readable)) return -1;
  size_t size = end - start;
  void *copy = mmap(NULL, size, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
  if (copy == MAP_FAILED) return -1;
  if (!readable && real_mprotect(addr, size, PROT_READ)) {
    munmap(copy, size);
    return -1;
  }
  memcpy(copy, addr, size);
  if (mremap(copy, size, size, MREMAP_MAYMOVE | MREMAP_FIXED, addr) == MAP_FAILED) {
    munmap(copy, size);
    return -1;
  }
  return real_mprotect(addr, size, prot);
}

__attribute__((constructor)) static void noexec_init(void) {
  real_mprotect = (int (*)(void *, size_t, int))dlsym(RTLD_NEXT, "mprotect");
}

int mprotect(void *addr, size_t len, int prot) {
  if (!real_mprotect) noexec_init();
  int result = real_mprotect(addr, len, prot);
  if (result && errno == EACCES && (prot & PROT_EXEC)) {
    result = in_fex() ? real_mprotect(addr, len, prot & ~PROT_EXEC) : copy_to_anonymous(addr, len, prot);
    if (result) errno = EACCES;
  }
  return result;
}

#else

__attribute__((visibility("hidden"))) int bl_noexec_retry(int prot, int flags, int fd) {
  (void)prot, (void)flags, (void)fd;
  return 0;
}

#endif
