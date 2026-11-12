#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#define DESCRIPTOR "user.wine.sd"
#define STORE "/.droiddeck-descriptors"
#define ENTRY_MAX (PATH_MAX * 2)

int bl_is_wineserver(void) __attribute__((visibility("hidden")));

static int store_seen;

static int kept(const char *name) {
  return name != NULL && strcmp(name, DESCRIPTOR) == 0 && bl_is_wineserver();
}

static int store_root(char *out, size_t size) {
  const char *prefix = getenv("WINEPREFIX");
  int n;

  if (prefix == NULL || prefix[0] != '/') return -1;
  n = snprintf(out, size, "%s" STORE, prefix);
  return n > 0 && (size_t)n < size ? n : -1;
}

static int store_exists(void) {
  char root[PATH_MAX];

  if (store_seen) return 1;
  if (store_root(root, sizeof(root)) < 0) return 0;
  store_seen = access(root, F_OK) == 0;
  return store_seen;
}

static int entry_for(const char *path, char *out, size_t size, const char *suffix, int create) {
  int used = store_root(out, size);
  const char *part = path, *end;

  if (used < 0 || path[0] != '/') return -1;
  if (create && mkdir(out, 0700) != 0 && errno != EEXIST) return -1;
  while (*part == '/') part++;
  if (*part == '\0') return -1;
  for (;;) {
    size_t length;
    const char *tail;

    end = strchr(part, '/');
    length = end ? (size_t)(end - part) : strlen(part);
    tail = end ? ".d" : suffix;
    if (length + strlen(tail) > NAME_MAX || used + length + strlen(tail) + 2 > size) return -1;
    out[used++] = '/';
    memcpy(out + used, part, length);
    used += (int)length;
    strcpy(out + used, tail);
    used += (int)strlen(tail);
    if (!end) break;
    if (create && mkdir(out, 0700) != 0 && errno != EEXIST) return -1;
    part = end + 1;
  }
  if (create) store_seen = 1;
  return 0;
}

static int path_of(int fd, char *out, size_t size) {
  char link[40];
  ssize_t n;

  snprintf(link, sizeof(link), "/proc/self/fd/%d", fd);
  n = readlink(link, out, size - 1);
  if (n <= 0 || out[0] != '/') return -1;
  out[n] = '\0';
  return 0;
}

static int resolved(const char *path, char *out, size_t size) {
  int fd, result;

  if (path == NULL) return -1;
  fd = open(path, O_PATH | O_NOFOLLOW | O_CLOEXEC);
  if (fd < 0) return -1;
  result = path_of(fd, out, size);
  close(fd);
  return result;
}

static int entry_of(int fd, char *out, size_t size, int create) {
  char path[PATH_MAX];

  if (path_of(fd, path, sizeof(path)) != 0) return -1;
  return entry_for(path, out, size, ".sd", create);
}

static ssize_t stored_read(int fd, void *value, size_t size) {
  char entry[ENTRY_MAX];
  struct stat st;
  ssize_t got = 0;
  int file;

  if (entry_of(fd, entry, sizeof(entry), 0) != 0) return -2;
  file = open(entry, O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
  if (file < 0) {
    if (errno != ENOENT) return -2;
    errno = ENODATA;
    return -1;
  }
  if (fstat(file, &st) != 0) {
    close(file);
    return -2;
  }
  if (size == 0) {
    close(file);
    return (ssize_t)st.st_size;
  }
  if ((size_t)st.st_size > size) {
    close(file);
    errno = ERANGE;
    return -1;
  }
  while (got < st.st_size) {
    ssize_t n = read(file, (char *)value + got, (size_t)(st.st_size - got));
    if (n <= 0) {
      close(file);
      return -2;
    }
    got += n;
  }
  close(file);
  return got;
}

static int stored_write(int fd, const void *value, size_t size) {
  char entry[ENTRY_MAX], fresh[ENTRY_MAX + 8];
  size_t put = 0;
  int file;

  if (entry_of(fd, entry, sizeof(entry), 1) != 0) return -1;
  snprintf(fresh, sizeof(fresh), "%s.new", entry);
  file = open(fresh, O_WRONLY | O_CREAT | O_TRUNC | O_NOFOLLOW | O_CLOEXEC, 0600);
  if (file < 0) return -1;
  while (put < size) {
    ssize_t n = write(file, (const char *)value + put, size - put);
    if (n <= 0) {
      close(file);
      unlinkat(AT_FDCWD, fresh, 0);
      return -1;
    }
    put += (size_t)n;
  }
  if (close(file) != 0 || renameat(AT_FDCWD, fresh, AT_FDCWD, entry) != 0) {
    unlinkat(AT_FDCWD, fresh, 0);
    return -1;
  }
  return 0;
}

ssize_t fgetxattr(int fd, const char *name, void *value, size_t size) {
  static ssize_t (*next)(int, const char *, void *, size_t);
  ssize_t result;

  if (next == NULL) next = dlsym(RTLD_NEXT, "fgetxattr");
  if (next == NULL) {
    errno = ENOSYS;
    return -1;
  }
  result = next(fd, name, value, size);
  if (result >= 0 || errno != ENOTSUP || !kept(name)) return result;
  result = stored_read(fd, value, size);
  if (result == -2) {
    errno = ENOTSUP;
    return -1;
  }
  return result;
}

int fsetxattr(int fd, const char *name, const void *value, size_t size, int flags) {
  static int (*next)(int, const char *, const void *, size_t, int);
  int saved = errno, result;

  if (next == NULL) next = dlsym(RTLD_NEXT, "fsetxattr");
  if (next == NULL) {
    errno = ENOSYS;
    return -1;
  }
  result = next(fd, name, value, size, flags);
  if (result == 0 || errno != ENOTSUP || !kept(name)) return result;
  if (stored_write(fd, value, size) != 0) {
    errno = ENOTSUP;
    return -1;
  }
  errno = saved;
  return 0;
}

int fremovexattr(int fd, const char *name) {
  static int (*next)(int, const char *);
  char entry[ENTRY_MAX];
  int saved = errno, result;

  if (next == NULL) next = dlsym(RTLD_NEXT, "fremovexattr");
  if (next == NULL) {
    errno = ENOSYS;
    return -1;
  }
  result = next(fd, name);
  if (result == 0 || errno != ENOTSUP || !kept(name)) return result;
  if (entry_of(fd, entry, sizeof(entry), 0) != 0) {
    errno = ENOTSUP;
    return -1;
  }
  if (unlinkat(AT_FDCWD, entry, 0) != 0) {
    errno = errno == ENOENT ? ENODATA : ENOTSUP;
    return -1;
  }
  errno = saved;
  return 0;
}

static int tracked(const char *path, char *out, size_t size) {
  int saved = errno, found;

  found = bl_is_wineserver() && store_exists() && resolved(path, out, size) == 0;
  errno = saved;
  return found;
}

static void forget(const char *path, int folder) {
  char entry[ENTRY_MAX];
  int saved = errno;

  if (entry_for(path, entry, sizeof(entry), ".sd", 0) == 0) unlinkat(AT_FDCWD, entry, 0);
  if (folder && entry_for(path, entry, sizeof(entry), ".d", 0) == 0) unlinkat(AT_FDCWD, entry, AT_REMOVEDIR);
  errno = saved;
}

static void follow(const char *from, const char *to) {
  static const char *const kinds[] = {".sd", ".d"};
  char old[ENTRY_MAX], new[ENTRY_MAX];
  int saved = errno;

  for (size_t i = 0; i < 2; i++) {
    if (entry_for(from, old, sizeof(old), kinds[i], 0) != 0) continue;
    if (access(old, F_OK) != 0) {
      if (i == 0 && entry_for(to, new, sizeof(new), kinds[i], 0) == 0) unlinkat(AT_FDCWD, new, 0);
      continue;
    }
    if (entry_for(to, new, sizeof(new), kinds[i], 1) != 0 || renameat(AT_FDCWD, old, AT_FDCWD, new) != 0) {
      if (i == 0) unlinkat(AT_FDCWD, old, 0);
    }
  }
  errno = saved;
}

__attribute__((visibility("hidden")))
int bl_descriptor_unlink(const char *path, int (*real)(const char *)) {
  char target[PATH_MAX];
  int known = tracked(path, target, sizeof(target)), result = real(path);

  if (result == 0 && known) forget(target, 0);
  return result;
}

__attribute__((visibility("hidden")))
int bl_descriptor_rmdir(const char *path, int (*real)(const char *)) {
  char target[PATH_MAX];
  int known = tracked(path, target, sizeof(target)), result = real(path);

  if (result == 0 && known) forget(target, 1);
  return result;
}

__attribute__((visibility("hidden")))
int bl_descriptor_rename(const char *from, const char *to, int (*real)(const char *, const char *)) {
  char old[PATH_MAX], new[PATH_MAX];
  int known = tracked(from, old, sizeof(old)), result = real(from, to);

  if (result == 0 && known && tracked(to, new, sizeof(new))) follow(old, new);
  return result;
}
