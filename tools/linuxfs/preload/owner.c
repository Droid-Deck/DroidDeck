#define _GNU_SOURCE
#include <errno.h>
#include <limits.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/statfs.h>
#include <unistd.h>

#define FUSE_MAGIC 0x65735546
#define SDCARDFS_MAGIC 0x5dca2df5

static int is_wineserver(void) {
  static int cached = -1;
  char exe[PATH_MAX];
  const char *base;
  ssize_t n;

  if (cached >= 0) return cached;
  n = readlink("/proc/self/exe", exe, sizeof(exe) - 1);
  if (n <= 0) {
    cached = 0;
    return cached;
  }
  exe[n] = '\0';
  base = strrchr(exe, '/');
  cached = strcmp(base ? base + 1 : exe, "wineserver") == 0;
  return cached;
}

__attribute__((visibility("hidden")))
void bl_storage_owner(int fd, struct stat *st) {
  struct statfs fs;
  int saved = errno;
  uid_t uid;

  if (st != NULL && is_wineserver()) {
    uid = getuid();
    if (st->st_uid != uid && fstatfs(fd, &fs) == 0 && (fs.f_type == FUSE_MAGIC || fs.f_type == SDCARDFS_MAGIC)) st->st_uid = uid;
  }
  errno = saved;
}
