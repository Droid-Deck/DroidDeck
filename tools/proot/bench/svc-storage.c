#define _GNU_SOURCE
#include "checks.h"
#include <dlfcn.h>
#include <fcntl.h>
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
  return t.tv_sec + t.tv_nsec / 1e9;
}

int main(int argc, char **argv) {
  assert(argc == 2 && mkdir(argv[1], 0700) == 0);
  void *libc = dlopen("libc.so.6", RTLD_NOW);
  assert(libc);
  int (*internal_open)(const char *, int, ...) = dlsym(libc, "__open64_nocancel");
  int (*internal_stat)(int, const char *, struct stat *, int) = dlsym(libc, "fstatat");
  assert(internal_open && internal_stat);
  struct rusage before, after;
  assert(getrusage(RUSAGE_SELF, &before) == 0);
  char paths[64][4096], stream[4096];
  double creation = 0, extension = 0, closing = 0, total = now();
  for (int i = 0; i < 64; i++) {
    snprintf(paths[i], sizeof paths[i], "%s/asset-with-a-longish-steam-depot-filename-%03d.dat", argv[1], i);
    double start = now();
    int fd = internal_open(paths[i], O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC, 0600);
    assert(fd >= 0);
    creation += now() - start;
    start = now();
    assert(ftruncate(fd, 262144) == 0);
    extension += now() - start;
    start = now();
    assert(close(fd) == 0);
    closing += now() - start;
  }
  double allocation = now() - total;
  struct stat st;
  double start = now();
  for (int i = 0; i < 1000; i++) assert(internal_stat(AT_FDCWD, paths[i % 64], &st, 0) == 0);
  double metadata = now() - start;
  snprintf(stream, sizeof stream, "%s/stream.bin", argv[1]);
  start = now();
  int fd = internal_open(stream, O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC, 0600);
  assert(fd >= 0);
  double stream_create = now() - start;
  static unsigned char block[1024 * 1024];
  memset(block, 0x5a, sizeof block);
  start = now();
  for (int i = 0; i < 64; i++) assert(write(fd, block, sizeof block) == sizeof block);
  double writing = now() - start;
  start = now();
  assert(fdatasync(fd) == 0);
  double syncing = now() - start;
  assert(close(fd) == 0);
  assert(getrusage(RUSAGE_SELF, &after) == 0);
  printf("{\"files\":64,\"bytes_per_file\":262144,\"create_s\":%.6f,\"extend_s\":%.6f,"
         "\"close_s\":%.6f,\"allocation_s\":%.6f,\"stat1000_s\":%.6f,"
         "\"stream_bytes\":67108864,\"stream_create_s\":%.6f,\"write_s\":%.6f,"
         "\"fdatasync_s\":%.6f,\"voluntary_switches\":%ld}\n",
         creation, extension, closing, allocation, metadata, stream_create, writing,
         syncing, after.ru_nvcsw - before.ru_nvcsw);
  fflush(stdout);
  for (int i = 0; i < 64; i++) assert(unlink(paths[i]) == 0);
  assert(unlink(stream) == 0 && rmdir(argv[1]) == 0);
  assert(dlclose(libc) == 0);
  return 0;
}
