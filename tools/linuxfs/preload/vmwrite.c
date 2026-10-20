#define _GNU_SOURCE
#define _FILE_OFFSET_BITS 64
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/uio.h>
#include <unistd.h>

typedef ssize_t (*vm_writev_fn)(pid_t, const struct iovec *, unsigned long, const struct iovec *, unsigned long,
                                unsigned long);

static int in_wineserver(void) {
  static int known = -1;
  if (known < 0) {
    char exe[512];
    ssize_t n = readlink("/proc/self/exe", exe, sizeof exe - 1);
    exe[n > 0 ? n : 0] = 0;
    const char *name = strrchr(exe, '/');
    known = !strcmp(name ? name + 1 : exe, "wineserver");
  }
  return known;
}

ssize_t process_vm_writev(pid_t pid, const struct iovec *local, unsigned long liovcnt, const struct iovec *remote,
                          unsigned long riovcnt, unsigned long flags) {
  static vm_writev_fn real;
  if (!real) real = (vm_writev_fn)dlsym(RTLD_NEXT, "process_vm_writev");
  if (!real) {
    errno = ENOSYS;
    return -1;
  }
  ssize_t result = real(pid, local, liovcnt, remote, riovcnt, flags);
  if (result >= 0 || errno != EFAULT || liovcnt != 1 || riovcnt != 1 || flags || !in_wineserver()) return result;
  struct iovec ends[2];
  struct iovec into = {ends, sizeof ends};
  struct iovec from[2] = {{(void *)local, sizeof *local}, {(void *)remote, sizeof *remote}};
  if (process_vm_readv(getpid(), &into, 1, from, 2, 0) != (ssize_t)sizeof ends || ends[0].iov_len != ends[1].iov_len) {
    errno = EFAULT;
    return -1;
  }
  char path[32];
  snprintf(path, sizeof path, "/proc/%d/mem", (int)pid);
  int fd = open(path, O_WRONLY | O_CLOEXEC);
  if (fd < 0) {
    errno = EFAULT;
    return -1;
  }
  ssize_t written = pwrite(fd, ends[0].iov_base, ends[0].iov_len, (off_t)(uintptr_t)ends[1].iov_base);
  close(fd);
  if (written < 0) errno = EFAULT;
  return written;
}
