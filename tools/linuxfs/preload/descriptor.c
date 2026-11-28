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
#define MACHINE_ID "/etc/machine-id"
#define SD_HEADER 22
#define SID_SIZE 28

int dd_is_wineserver(void) __attribute__((visibility("hidden")));

static int store_seen;
static unsigned int user_sub[3];

static int kept(const char *name) {
  return name != NULL && strcmp(name, DESCRIPTOR) == 0 && dd_is_wineserver();
}

__attribute__((visibility("hidden")))
void dd_descriptor_opened(const char *path) {
  char id[17];
  ssize_t got, n = 0;
  unsigned long long value;
  int saved = errno, fd;

  if (path == NULL || strcmp(path, MACHINE_ID) != 0 || !dd_is_wineserver()) {
    errno = saved;
    return;
  }
  memset(user_sub, 0, sizeof(user_sub));
  fd = open(path, O_RDONLY | O_CLOEXEC);
  if (fd >= 0) {
    while (n < 16 && (got = read(fd, id + n, (size_t)(16 - n))) > 0) n += got;
    close(fd);
  }
  if (n == 16) {
    id[16] = '\0';
    value = strtoull(id, NULL, 16);
    user_sub[0] = (unsigned int)(value >> 32);
    user_sub[1] = (unsigned int)value;
    user_sub[2] = (unsigned int)getuid();
  }
  errno = saved;
}

static unsigned int word_at(const unsigned char *p) {
  unsigned int v;

  memcpy(&v, p, sizeof(v));
  return v;
}

static unsigned short half_at(const unsigned char *p) {
  unsigned short v;

  memcpy(&v, p, sizeof(v));
  return v;
}

static void current_user(unsigned char *sid, size_t size) {
  static const unsigned char local[8] = {1, 5, 0, 0, 0, 0, 0, 5};
  unsigned int owner;

  if (size < SID_SIZE || memcmp(sid, local, sizeof(local)) != 0) return;
  if (word_at(sid + 8) != 21 || word_at(sid + 24) != 1000) return;
  owner = word_at(sid + 20);
  if (owner != 0 && owner != (unsigned int)getuid()) return;
  memcpy(sid + 12, user_sub, sizeof(user_sub));
}

static int current_user_acl(unsigned char *acl, size_t size, int apply) {
  size_t used = 8;
  unsigned int count, i;

  if (size == 0) return 1;
  if (size < 8) return 0;
  count = half_at(acl + 4);
  for (i = 0; i < count && used + 8 <= size; i++) {
    unsigned char *ace = acl + used;
    size_t length = half_at(ace + 2);

    if (length < 8 || length > size - used) return 0;
    if (apply && ace[0] <= 3) current_user(ace + 8, length - 8);
    used += length;
  }
  return i == count;
}

static void current_user_sd(unsigned char *value, size_t size) {
  size_t part[4], at = SD_HEADER;
  int i;

  if (size < SD_HEADER || value[0] != 1 || value[1] != 0) return;
  for (i = 0; i < 4; i++) {
    part[i] = word_at(value + 6 + 4 * i);
    if (part[i] > size - at) return;
    at += part[i];
  }
  at = SD_HEADER + part[0] + part[1];
  if (!current_user_acl(value + at, part[2], 0) || !current_user_acl(value + at + part[2], part[3], 0)) return;
  current_user(value + SD_HEADER, part[0]);
  current_user(value + SD_HEADER + part[0], part[1]);
  current_user_acl(value + at, part[2], 1);
  current_user_acl(value + at + part[2], part[3], 1);
}

static int store_root(char *out, size_t size) {
  const char *prefix = getenv("WINEPREFIX");
  int n;

  if (prefix == NULL || prefix[0] != '/') return -1;
  n = snprintf(out, size, "%s" STORE, prefix);
  return n > 0 && (size_t)n < size ? n : -1;
}

static __attribute__((noinline)) int store_exists(void) {
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
  if (result < 0 && errno == ENOTSUP && kept(name)) {
    result = stored_read(fd, value, size);
    if (result == -2) {
      errno = ENOTSUP;
      return -1;
    }
  }
  if (result > 0 && size > 0 && kept(name)) current_user_sd(value, (size_t)result);
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

static int watched(void) {
  int saved = errno, found;

  found = dd_is_wineserver() && store_exists();
  errno = saved;
  return found;
}

static int tracked(const char *path, char *out, size_t size) {
  int saved = errno, found;

  found = resolved(path, out, size) == 0;
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

static __attribute__((noinline)) int removed(const char *path, int (*real)(const char *), int folder) {
  char target[PATH_MAX];
  int known = tracked(path, target, sizeof(target)), result = real(path);

  if (result == 0 && known) forget(target, folder);
  return result;
}

static __attribute__((noinline)) int moved(const char *from, const char *to, int (*real)(const char *, const char *)) {
  char old[PATH_MAX], new[PATH_MAX];
  int known = tracked(from, old, sizeof(old)), result = real(from, to);

  if (result == 0 && known && tracked(to, new, sizeof(new))) follow(old, new);
  return result;
}

__attribute__((visibility("hidden")))
int dd_descriptor_unlink(const char *path, int (*real)(const char *)) {
  return watched() ? removed(path, real, 0) : real(path);
}

__attribute__((visibility("hidden")))
int dd_descriptor_rmdir(const char *path, int (*real)(const char *)) {
  return watched() ? removed(path, real, 1) : real(path);
}

__attribute__((visibility("hidden")))
int dd_descriptor_remove(const char *path, int (*real)(const char *)) {
  return dd_descriptor_rmdir(path, real);
}

__attribute__((visibility("hidden")))
int dd_descriptor_rename(const char *from, const char *to, int (*real)(const char *, const char *)) {
  return watched() ? moved(from, to, real) : real(from, to);
}
