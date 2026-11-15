#define _GNU_SOURCE
#include "checks.h"
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>

int main(void) {
  char work[] = "/tmp/proot-svc-race-XXXXXX";
  assert(mkdtemp(work));
  int cwd = open(".", O_RDONLY | O_DIRECTORY);
  assert(cwd >= 0 && chdir(work) == 0);
  for (int i = 0; i < 2; i++) {
    int fd = open(i ? "b" : "a", O_CREAT | O_EXCL | O_WRONLY, 0600);
    assert(fd >= 0 && ftruncate(fd, i + 1) == 0 && close(fd) == 0);
  }
  assert(symlink("a", "live") == 0);
  void *libc = dlopen("libc.so.6", RTLD_NOW);
  int (*stat)(int, const char *, struct stat *, int) = dlsym(libc, "fstatat");
  ssize_t (*link)(int, const char *, char *, size_t) = dlsym(libc, "readlinkat");
  assert(stat && link);
  pid_t writer = fork();
  assert(writer >= 0);
  if (!writer) {
    for (int i = 0; i < 3000; i++) {
      assert(symlink(i & 1 ? "b" : "a", "next") == 0);
      assert(rename("next", "live") == 0);
    }
    _exit(0);
  }
  for (int i = 0; i < 3000; i++) {
    struct stat st;
    assert(stat(AT_FDCWD, "live", &st, 0) == 0 && S_ISREG(st.st_mode) && (st.st_size == 1 || st.st_size == 2));
    char text[8];
    assert(link(AT_FDCWD, "live", text, sizeof text) == 1 && (text[0] == 'a' || text[0] == 'b'));
  }
  int status;
  assert(waitpid(writer, &status, 0) == writer && WIFEXITED(status) && WEXITSTATUS(status) == 0);
  assert(unlink("live") == 0 && unlink("a") == 0 && unlink("b") == 0);
  assert(dlclose(libc) == 0 && fchdir(cwd) == 0 && close(cwd) == 0 && rmdir(work) == 0);
  puts("PASS: concurrent atomic symlink replacement, internal stat/readlink legal results");
  return 0;
}
