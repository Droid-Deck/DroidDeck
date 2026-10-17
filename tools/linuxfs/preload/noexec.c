#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <string.h>
#include <sys/mman.h>
#include <fcntl.h>
#include <stdint.h>
#include <sys/auxv.h>
#include <sys/stat.h>
#include <stdlib.h>
#include <sys/statvfs.h>
#include <sys/syscall.h>
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

__attribute__((constructor)) static void noexec_init(void) {
  real_mprotect = (int (*)(void *, size_t, int))dlsym(RTLD_NEXT, "mprotect");
}

static int private_file_mapping(uintptr_t start, uintptr_t end) {
  int fd = (int)syscall(SYS_openat, AT_FDCWD, "/proc/self/maps", O_RDONLY | O_CLOEXEC);
  if (fd < 0) return 0;
  char buf[4096], line[1024];
  size_t used = 0;
  int found = 0, done = 0;
  ssize_t n;
  while (!done && (n = syscall(SYS_read, fd, buf, sizeof buf)) > 0) {
    for (ssize_t i = 0; i < n && !done; i++) {
      if (buf[i] != '\n') {
        if (used < sizeof line - 1) line[used++] = buf[i];
        continue;
      }
      line[used] = 0;
      used = 0;
      char *p = line;
      uintptr_t lo = strtoull(p, &p, 16), hi = strtoull(p + 1, &p, 16);
      if (start < lo || start >= hi) continue;
      done = 1;
      char *perms = p + 1;
      if (end > hi || perms[3] != 'p') break;
      char *path = strchr(perms, '/');
      struct statvfs vfs;
      found = path && !(statvfs(path, &vfs) == 0 && (vfs.f_flag & ST_NOEXEC));
    }
  }
  syscall(SYS_close, fd);
  return found;
}

static const char rx_dir[] = "/tmp/.droiddeck-rx";

static uint64_t fnv1a(const unsigned char *p, size_t n) {
  uint64_t h = 1469598103934665603ULL;
  for (size_t i = 0; i < n; i++) {
    h ^= p[i];
    h *= 1099511628211ULL;
  }
  return h;
}

static char *put_hex(char *out, uint64_t v) {
  for (int shift = 60; shift >= 0; shift -= 4) *out++ = "0123456789abcdef"[(v >> shift) & 15];
  return out;
}

static int same_content(int fd, const unsigned char *p, size_t len) {
  char buf[16384];
  for (size_t off = 0; off < len;) {
    size_t want = len - off < sizeof buf ? len - off : sizeof buf;
    if (syscall(SYS_pread64, fd, buf, want, off) != (long)want || memcmp(buf, p + off, want)) return 0;
    off += want;
  }
  return 1;
}

static int write_all(int fd, const unsigned char *p, size_t len) {
  for (size_t off = 0; off < len;) {
    long n = syscall(SYS_write, fd, p + off, len - off);
    if (n <= 0) return -1;
    off += (size_t)n;
  }
  return 0;
}

static int rebake_shared(void *addr, size_t len, int prot) {
  char path[sizeof rx_dir + 40], staged[sizeof path + 20];
  char *end = path + sizeof rx_dir - 1;
  memcpy(path, rx_dir, sizeof rx_dir - 1);
  *end++ = '/';
  end = put_hex(end, fnv1a(addr, len));
  *end++ = '-';
  end = put_hex(end, len);
  *end = 0;
  int fd = (int)syscall(SYS_openat, AT_FDCWD, path, O_RDONLY | O_CLOEXEC);
  if (fd >= 0 && !same_content(fd, addr, len)) {
    syscall(SYS_close, fd);
    return -1;
  }
  if (fd < 0) {
    syscall(SYS_mkdirat, AT_FDCWD, rx_dir, 0700);
    memcpy(staged, path, (size_t)(end - path));
    char *tail = put_hex(staged + (end - path), (uint64_t)syscall(SYS_gettid));
    *tail = 0;
    int out = (int)syscall(SYS_openat, AT_FDCWD, staged, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    if (out < 0) return -1;
    int bad = write_all(out, addr, len);
    syscall(SYS_close, out);
    if (bad || syscall(SYS_renameat, AT_FDCWD, staged, AT_FDCWD, path)) {
      syscall(SYS_unlinkat, AT_FDCWD, staged, 0);
      return -1;
    }
    fd = (int)syscall(SYS_openat, AT_FDCWD, path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return -1;
  }
  long mapped = syscall(SYS_mmap, addr, len, prot, MAP_PRIVATE | MAP_FIXED, fd, 0);
  syscall(SYS_close, fd);
  return mapped == (long)addr ? 0 : -1;
}

static int rebake(void *addr, size_t len, int prot) {
  size_t page = getauxval(AT_PAGESZ);
  if (page) len = (len + page - 1) & ~(page - 1);
  if (real_mprotect(addr, len, PROT_READ)) return -1;
  if (!rebake_shared(addr, len, prot)) return 0;
  void *copy = mmap(NULL, len, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
  if (copy == MAP_FAILED) return -1;
  memcpy(copy, addr, len);
  if (mmap(addr, len, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED, -1, 0) == MAP_FAILED) {
    munmap(copy, len);
    return -1;
  }
  memcpy(addr, copy, len);
  munmap(copy, len);
  __builtin___clear_cache((char *)addr, (char *)addr + len);
  return real_mprotect(addr, len, prot);
}

int mprotect(void *addr, size_t len, int prot) {
  if (!real_mprotect) noexec_init();
  int result = real_mprotect(addr, len, prot);
  if (result && errno == EACCES && (prot & PROT_EXEC)) {
    if (in_fex()) {
      result = real_mprotect(addr, len, prot & ~PROT_EXEC);
    } else if (len && private_file_mapping((uintptr_t)addr, (uintptr_t)addr + len)) {
      result = rebake(addr, len, prot);
    }
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
