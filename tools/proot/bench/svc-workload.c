#define _GNU_SOURCE
#include "checks.h"
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <fts.h>
#include <pwd.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/resource.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

static double now(void) {
  struct timespec t;
  assert(clock_gettime(CLOCK_MONOTONIC, &t) == 0);
  return t.tv_sec * 1e9 + t.tv_nsec;
}

int main(int argc, char **argv) {
  assert(argc >= 2);
  void *libc = dlopen("libc.so.6", RTLD_NOW);
  assert(libc);
  int (*internal_stat)(int, const char *, struct stat *, int) = dlsym(libc, "fstatat");
  int (*internal_open)(const char *, int, ...) = dlsym(libc, "__open64_nocancel");
  assert(internal_stat && internal_open);
  char path[4096], link[4096], missing[4096];
  snprintf(path, sizeof path, "%s/file", argv[1]);
  snprintf(link, sizeof link, "%s/link", argv[1]);
  snprintf(missing, sizeof missing, "%s/missing", argv[1]);
  const char *paths[] = {path, link, missing, "/etc/os-release", "/lib/libc.so.6", "/proc/self/exe", "/dev/null", "", NULL};
  struct stat st;
  for (size_t i = 0; i < sizeof paths / sizeof *paths; i++) {
    errno = 0;
    int r = internal_stat(AT_FDCWD, paths[i], &st, 0);
    printf("stat %zu: %d %d", i, r, r ? errno : 0);
    if (!r) printf(" %o %ld", st.st_mode & S_IFMT, S_ISREG(st.st_mode) && i != 5 ? st.st_size : 0L);
    putchar('\n');
    errno = 0;
    int fd = internal_open(paths[i], O_RDONLY | O_CLOEXEC);
    int err = fd < 0 ? errno : 0;
    printf("open %zu: %d %d\n", i, fd < 0 ? -1 : 0, err);
    if (fd >= 0) close(fd);
  }
  errno = 0;
  int r = internal_stat(AT_FDCWD, path, &st, 0x40000000);
  printf("flags: %d %d\n", r, r ? errno : 0);
  char *tree[] = {argv[1], NULL};
  FTS *fts = fts_open(tree, FTS_PHYSICAL | FTS_NOCHDIR, NULL);
  assert(fts);
  unsigned files = 0, dirs = 0, links = 0;
  FTSENT *ent;
  while ((ent = fts_read(fts))) {
    if (ent->fts_info == FTS_F) files++;
    if (ent->fts_info == FTS_D) dirs++;
    if (ent->fts_info == FTS_SL) links++;
  }
  assert(fts_close(fts) == 0);
  printf("fts: %u files %u dirs %u links\n", files, dirs, links);
  char resolved[4096], canonical[4096];
  assert(realpath(path, canonical));
  assert(realpath(link, resolved) && !strcmp(resolved, canonical));
  struct passwd pw, *found;
  char buf[16384];
  r = getpwnam_r("root", &pw, buf, sizeof buf, &found);
  printf("nss: %d %d\n", r, found != NULL);
  void *libm = dlopen("libm.so.6", RTLD_NOW | RTLD_LOCAL);
  assert(libm && dlsym(libm, "cos"));
  assert(dlclose(libm) == 0);
  puts("realpath/dlopen: PASS");
  if (argc > 2) {
    const int loops = 5000;
    struct rusage before, after;
    assert(getrusage(RUSAGE_SELF, &before) == 0);
    double start = now();
    for (int i = 0; i < loops; i++) assert(internal_stat(AT_FDCWD, path, &st, 0) == 0);
    double stat_ns = (now() - start) / loops;
    start = now();
    for (int i = 0; i < loops; i++) {
      int fd = internal_open(path, O_RDONLY | O_CLOEXEC);
      assert(fd >= 0 && close(fd) == 0);
    }
    double open_ns = (now() - start) / loops;
    assert(getrusage(RUSAGE_SELF, &after) == 0);
    printf("bench: stat %.0f ns, open+close %.0f ns, voluntary switches %ld\n",
           stat_ns, open_ns, after.ru_nvcsw - before.ru_nvcsw);
  }
  assert(dlclose(libc) == 0);
  return 0;
}
