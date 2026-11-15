#define _GNU_SOURCE
#include "checks.h"
#include <dlfcn.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdio.h>
#include <sys/resource.h>
#include <time.h>
#include <unistd.h>

static double now(void) {
  struct timespec t;
  assert(clock_gettime(CLOCK_MONOTONIC, &t) == 0);
  return t.tv_sec * 1e9 + t.tv_nsec;
}

static void *thread(void *arg) { return arg; }

int main(void) {
  void *libc = dlopen("libc.so.6", RTLD_NOW);
  assert(libc);
  int (*access)(int, const char *, int, int) = dlsym(libc, "faccessat");
  char *(*cwd)(char *, size_t) = dlsym(libc, "getcwd");
  assert(access && cwd);
  struct rusage before, after;
  assert(getrusage(RUSAGE_SELF, &before) == 0);
  const int threads = 128, calls = 2000;
  double start = now();
  for (int i = 0; i < threads; i++) {
    pthread_t id;
    void *value;
    assert(pthread_create(&id, NULL, thread, (void *)1) == 0);
    assert(pthread_join(id, &value) == 0 && value == (void *)1);
  }
  double thread_us = (now() - start) / threads / 1000;
  start = now();
  for (int i = 0; i < calls; i++) assert(access(AT_FDCWD, "/etc/os-release", R_OK, 0) == 0);
  double access_ns = (now() - start) / calls;
  char path[4096];
  start = now();
  for (int i = 0; i < calls; i++) assert(cwd(path, sizeof path));
  double cwd_ns = (now() - start) / calls;
  assert(getrusage(RUSAGE_SELF, &after) == 0 && dlclose(libc) == 0);
  printf("{\"thread_us\":%.0f,\"access_ns\":%.0f,\"cwd_ns\":%.0f,\"switches\":%ld}\n",
         thread_us, access_ns, cwd_ns, after.ru_nvcsw - before.ru_nvcsw);
  return 0;
}
