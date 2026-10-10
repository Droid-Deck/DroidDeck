/* The calls the fast path took over from proot, timed: run under fpoff and fastpath and compare.
 * Usage: fpbench <scratch dir in the guest> [iterations]. Builds a Wine-like prefix there. */
#define _GNU_SOURCE
#include <fcntl.h>
#include <limits.h>
#include <pwd.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/statvfs.h>
#include <sys/un.h>
#include <sys/utsname.h>
#include <sys/xattr.h>
#include <time.h>
#include <unistd.h>

static double now(void) {
  struct timespec t;
  clock_gettime(CLOCK_MONOTONIC, &t);
  return t.tv_sec * 1e9 + t.tv_nsec;
}

#define BENCH(name, n, body)                                   \
  do {                                                         \
    for (int _w = 0; _w < (n) / 10 + 1; _w++) { body; }        \
    double _t0 = now();                                        \
    for (int _i = 0; _i < (n); _i++) { body; }                 \
    printf("%-34s %10.0f ns\n", name, (now() - _t0) / (n));    \
    fflush(stdout);                                            \
  } while (0)

int main(int argc, char **argv) {
  const char *d = argc > 1 ? argv[1] : "/root/fpb";
  int n = argc > 2 ? atoi(argv[2]) : 5000;
  char p[PATH_MAX], q[2 * PATH_MAX], f[PATH_MAX], dos[PATH_MAX], buf[PATH_MAX];
  struct stat st;
  const char *dirs[] = {"", "/pfx", "/pfx/drive_c", "/pfx/drive_c/windows", "/pfx/drive_c/windows/system32", "/pfx/dosdevices"};
  for (size_t i = 0; i < sizeof dirs / sizeof *dirs; i++) {
    snprintf(p, sizeof p, "%s%s", d, dirs[i]);
    mkdir(p, 0755);
  }
  snprintf(p, sizeof p, "%s/pfx/drive_c/windows/system32/kernel32.dll", d);
  close(open(p, O_CREAT | O_WRONLY, 0644));
  snprintf(p, sizeof p, "%s/pfx/dosdevices/c:", d);
  unlink(p);
  if (symlink("../drive_c", p) != 0) return 1;
  snprintf(p, sizeof p, "%s/pfx/dosdevices/z:", d);
  unlink(p);
  if (symlink("/", p) != 0) return 1;
  snprintf(dos, sizeof dos, "%s/pfx/dosdevices/c:/windows/system32/kernel32.dll", d);
  snprintf(f, sizeof f, "%s/pfx/drive_c/windows/system32/kernel32.dll", d);
  snprintf(q, sizeof q, "%s/pfx/dosdevices/z:%s", d, f);

  BENCH("stat plain path", n, stat(f, &st));
  BENCH("stat through c: link", n, stat(dos, &st));
  BENCH("stat through z: link", n, stat(q, &st));
  BENCH("open+close through c:", n, close(open(dos, O_RDONLY)));
  BENCH("stat missing through c:", n, stat("/root/fpb/pfx/dosdevices/c:/windows/nope.dll", &st));
  BENCH("realpath through c:", n, if (!realpath(dos, buf)) return 1);
  BENCH("realpath plain", n, if (!realpath(f, buf)) return 1);
  BENCH("getcwd", n, if (!getcwd(buf, sizeof buf)) return 1);
  BENCH("fstat", n, { int fd = open(f, O_RDONLY); fstat(fd, &st); close(fd); });
  BENCH("faccessat AT_EACCESS", n, (void)!faccessat(AT_FDCWD, f, R_OK, AT_EACCESS));
  BENCH("getxattr through c:", n, getxattr(dos, "user.DOSATTRIB", buf, sizeof buf));
  BENCH("statvfs", n, { struct statvfs v; statvfs(f, &v); });
  snprintf(p, sizeof p, "%s/w", d);
  snprintf(q, sizeof q, "%s/w2", d);
  BENCH("mkdir+rmdir", n / 2, { mkdir(p, 0755); rmdir(p); });
  BENCH("create+rename+unlink", n / 2, { close(open(p, O_CREAT | O_WRONLY, 0644)); rename(p, q); unlink(q); });
  BENCH("symlink+unlink", n / 2, { (void)!symlink("x", p); unlink(p); });
  BENCH("utimensat", n, utimensat(AT_FDCWD, f, NULL, 0));
  BENCH("sysconf NPROCESSORS_ONLN", n, sysconf(_SC_NPROCESSORS_ONLN));
  int held = open(f, O_RDONLY);
  char fdlink[64];
  snprintf(fdlink, sizeof fdlink, "/proc/self/fd/%d", held);
  BENCH("readlink /proc/self/fd/N", n, (void)!readlink(fdlink, buf, sizeof buf));
  BENCH("realpath /proc/self/fd/N", n, if (!realpath(fdlink, buf)) return 1);
  BENCH("realpath /proc/self/exe", n, if (!realpath("/proc/self/exe", buf)) return 1);
  BENCH("open+close /proc/self/fd/N", n, close(open(fdlink, O_RDONLY)));
  BENCH("open+close /proc/mounts", n / 4, close(open("/proc/mounts", O_RDONLY)));
  BENCH("access /proc/1/fd/0 (EACCES)", n, (void)!access("/proc/1/fd/0", F_OK));
  snprintf(p, sizeof p, "%s/pfx/drive_c/windows/nope/", d);
  BENCH("stat missing dir/", n, stat(p, &st));
  BENCH("open+close /proc/self/status", n, close(open("/proc/self/status", O_RDONLY)));
  close(held);
  BENCH("shm_open+close", n, close(shm_open("/fpbench", O_RDWR | O_CREAT, 0600)));
  shm_unlink("/fpbench");
  snprintf(p, sizeof p, "%s/pfx/drive_c/windows/system32/a-name-long-enough-to-push-the-host-path-past-sun_path.dll", d);
  BENCH("stat missing, long host path", n, stat(p, &st));
  BENCH("create+unlink, long host path", n / 2, { close(open(p, O_CREAT | O_WRONLY, 0644)); unlink(p); });
  {
    int l = socket(AF_UNIX, SOCK_STREAM, 0);
    struct sockaddr_un a = { .sun_family = AF_UNIX };
    memcpy(a.sun_path, "\0fpbench-listener", 18);
    if (bind(l, (struct sockaddr *)&a, sizeof a) != 0 || listen(l, 64) != 0) { perror("listener"); return 1; }
    BENCH("connect+accept unix (blocking)", n / 4, {
      int c = socket(AF_UNIX, SOCK_STREAM, 0);
      connect(c, (struct sockaddr *)&a, sizeof a);
      close(accept(l, NULL, NULL));
      close(c);
    });
    close(l);
  }
  BENCH("connect abstract unix", n / 4, {
    int s = socket(AF_UNIX, SOCK_STREAM, 0);
    struct sockaddr_un a = { .sun_family = AF_UNIX };
    memcpy(a.sun_path, "\0fpbench-none", 13);
    connect(s, (struct sockaddr *)&a, offsetof(struct sockaddr_un, sun_path) + 13);
    close(s);
  });
  BENCH("getpwuid", n / 4, getpwuid(0));
  BENCH("uname", n, { struct utsname u; uname(&u); });
  BENCH("isatty", n, isatty(0));
  return 0;
}
