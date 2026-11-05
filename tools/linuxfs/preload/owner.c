#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <limits.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/statfs.h>
#include <unistd.h>

#define FUSE_MAGIC 0x65735546
#define SDCARDFS_MAGIC 0x5dca2df5
#define FAT_MAGIC 0x4d44
#define EXFAT_MAGIC 0x2011bab0

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

static int has_no_unix_owner(int fd) {
  struct statfs fs;

  if (fstatfs(fd, &fs) != 0) return 0;
  return fs.f_type == FUSE_MAGIC || fs.f_type == SDCARDFS_MAGIC || fs.f_type == FAT_MAGIC ||
         (unsigned int)fs.f_type == EXFAT_MAGIC;
}

__attribute__((visibility("hidden")))
void bl_storage_owner(int fd, struct stat *st) {
  int saved = errno;
  uid_t uid;

  if (st != NULL && is_wineserver()) {
    uid = getuid();
    if (st->st_uid != uid && has_no_unix_owner(fd)) st->st_uid = uid;
  }
  errno = saved;
}

int fchmod(int fd, mode_t mode) {
  static int (*next_fchmod)(int, mode_t);
  int saved = errno, result;

  if (next_fchmod == NULL) next_fchmod = dlsym(RTLD_NEXT, "fchmod");
  if (next_fchmod == NULL) {
    errno = ENOSYS;
    return -1;
  }
  result = next_fchmod(fd, mode);
  if (result != 0 && errno == EPERM && is_wineserver()) {
    if (has_no_unix_owner(fd)) {
      errno = saved;
      return 0;
    }
    errno = EPERM;
  }
  return result;
}
