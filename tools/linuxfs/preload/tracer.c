/*
 * Ported from WinNative (maxjivi05, feature/wayland-gamescope, 2026-09-18/19), GPL-3.0.
 */

#define _GNU_SOURCE
#include <ctype.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <unistd.h>

#define STATUS_MAX 16384
#define TRACER_FIELD "\nTracerPid:\t"

int bl_from_ntdll(const void *caller) __attribute__((visibility("hidden")));

static const char *skip_digits(const char *s) {
  const char *end = s;
  while (isdigit((unsigned char)*end)) end++;
  return end == s ? NULL : end;
}

static long status_pid(const char *path) {
  const char *rest;
  long pid = 0;
  if (path == NULL || strncmp(path, "/proc/", 6) != 0) return -1;
  path += 6;
  if (strncmp(path, "self/", 5) == 0) rest = path + 4;
  else if (strncmp(path, "thread-self/", 12) == 0) rest = path + 11;
  else if ((rest = skip_digits(path)) == NULL) return -1;
  else pid = strtol(path, NULL, 10);
  if (strncmp(rest, "/task/", 6) == 0 && (rest = skip_digits(rest + 6)) == NULL) return -1;
  return strcmp(rest, "/status") == 0 ? pid : -1;
}

static int is_web_helper(const void *caller) {
  if (strcmp(program_invocation_short_name, "steamwebhelper") != 0 &&
      strcmp(program_invocation_short_name, "vrwebhelper") != 0)
    return 0;
  const char *hide = getenv("BL_WEBHELPER_HIDE_TRACER");
  Dl_info info;
  if (hide && hide[0] == '1' && caller && dladdr(caller, &info) && info.dli_fname
      && strstr(info.dli_fname, "libtier0_s.so"))
    return 0;
  return 1;
}

static int write_all(int fd, const char *data, size_t len) {
  while (len > 0) {
    ssize_t n = write(fd, data, len);
    if (n < 0 && errno == EINTR) continue;
    if (n <= 0) return -1;
    data += n;
    len -= (size_t)n;
  }
  return 0;
}

static int read_only_copy(const char *data, size_t len, int flags) {
  char path[] = "/tmp/.droiddeck-status-XXXXXX";
  int rw = mkostemp(path, O_CLOEXEC);
  if (rw < 0) return -1;
  int ro = -1;
  if (write_all(rw, data, len) == 0)
    ro = (int)syscall(SYS_openat, AT_FDCWD, path, O_RDONLY | (flags & O_CLOEXEC));
  unlink(path);
  close(rw);
  return ro;
}

__attribute__((visibility("hidden"))) int bl_status_without_tracer(const char *path, int flags, const void *caller) {
  char text[STATUS_MAX];
  size_t len = 0;
  int saved = errno;
  int out = -1;

  long pid;
  if ((flags & O_ACCMODE) != O_RDONLY || (pid = status_pid(path)) < 0 || is_web_helper(caller) || bl_from_ntdll(caller))
    return -1;
  int in = (int)syscall(SYS_openat, AT_FDCWD, path, O_RDONLY | O_CLOEXEC);
  if (in < 0) goto done;
  for (;;) {
    ssize_t n = read(in, text + len, sizeof(text) - 1 - len);
    if (n < 0 && errno == EINTR) continue;
    if (n <= 0) break;
    len += (size_t)n;
  }
  close(in);
  text[len] = '\0';

  char *field = strstr(text, TRACER_FIELD);
  if (field == NULL) goto done;
  char *value = field + strlen(TRACER_FIELD);
  char *line_end = strchr(value, '\n');
  if (line_end == NULL || line_end == value) goto done;

  memmove(value + 1, line_end, len - (size_t)(line_end - text) + 1);
  *value = '0';
  len = (size_t)(value + 1 - text) + strlen(value + 1);
  if (pid != 0 && pid != getpid() && (out = read_only_copy(text, len, flags)) >= 0) goto done;

  out = memfd_create("status", (flags & O_CLOEXEC) ? MFD_CLOEXEC : 0);
  if (out < 0) goto done;
  if (write_all(out, text, len) != 0 || lseek(out, 0, SEEK_SET) != 0) {
    close(out);
    out = -1;
  }
done:
  errno = saved;
  return out;
}
