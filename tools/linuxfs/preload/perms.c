#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <string.h>
#include <strings.h>
#include <sys/stat.h>
#include <unistd.h>

static int in_steam_dirs(const char *path) {
  return path != NULL && (strstr(path, "/Steam/steamrtarm64/") != NULL || strstr(path, "/Steam/steamrt64/") != NULL
      || strstr(path, "/Steam/ubuntu12_64/") != NULL || strstr(path, "/Steam/linuxarm64/") != NULL);
}

static int is_steam_client(void) {
  static int cached = -1;
  char exe[PATH_MAX];
  ssize_t n;

  if (cached >= 0) return cached;
  n = readlink("/proc/self/exe", exe, sizeof(exe) - 1);
  if (n > 0) exe[n] = '\0';
  cached = (n > 0 && in_steam_dirs(exe)) || in_steam_dirs(program_invocation_name);
  return cached;
}

static int wine_name(const char *path) {
  const char *base;
  size_t n;

  if (path == NULL) return 0;
  base = strrchr(path, '/');
  base = base ? base + 1 : path;
  n = strlen(path);
  return strncmp(base, "wine", 4) == 0 || strstr(path, "/lib/wine/") != NULL
      || (n > 4 && strcasecmp(path + n - 4, ".exe") == 0);
}

static int is_wine(void) {
  static int cached = -1;
  char exe[PATH_MAX];
  ssize_t n;

  if (cached >= 0) return cached;
  n = readlink("/proc/self/exe", exe, sizeof(exe) - 1);
  if (n > 0) exe[n] = '\0';
  cached = (n > 0 && wine_name(exe)) || wine_name(program_invocation_name);
  return cached;
}

static int acts_as_root(void) {
  return is_steam_client() || !is_wine();
}

static int grant_owner_write(int dirfd, const char *path) {
  struct stat st;

  if (fstatat(dirfd, path, &st, 0) != 0 || (st.st_mode & S_IWUSR)) return 0;
  return fchmodat(dirfd, path, (st.st_mode & 07777) | S_IWUSR, 0) == 0;
}

__attribute__((visibility("hidden")))
int bl_writable_retry(int dirfd, const char *path, int flags) {
  int saved = errno;
  int granted = 0;
  char parent[PATH_MAX];
  const char *slash;

  if (saved != EACCES || path == NULL || (flags & O_ACCMODE) == O_RDONLY || !acts_as_root()) return 0;
  if (faccessat(dirfd, path, F_OK, 0) == 0) {
    granted = grant_owner_write(dirfd, path);
  } else if (flags & O_CREAT) {
    slash = strrchr(path, '/');
    if (slash == NULL) {
      granted = grant_owner_write(dirfd, ".");
    } else if ((size_t) (slash - path) < sizeof(parent)) {
      memcpy(parent, path, slash - path);
      parent[slash - path] = '\0';
      granted = grant_owner_write(dirfd, parent[0] ? parent : "/");
    }
  }
  errno = saved;
  return granted;
}

__attribute__((visibility("hidden")))
int bl_writable_access(int dirfd, const char *path, int mode, int flags) {
  int saved = errno;
  struct stat st;
  int owned;

  if (saved != EACCES || path == NULL || !(mode & W_OK) || !acts_as_root()) return 0;
  owned = fstatat(dirfd, path, &st, flags & AT_SYMLINK_NOFOLLOW) == 0 && st.st_uid == geteuid();
  errno = saved;
  return owned;
}
