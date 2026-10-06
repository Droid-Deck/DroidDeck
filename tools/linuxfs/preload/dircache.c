/*
 * The Steam client's directory listings on the SD library, kept in the process.
 *
 * Steam resolves every path case-insensitively. For each file it creates while it allocates an
 * install, it first checks the exact name with access() several times, then lists the file's
 * directory to look for a differently cased match, and only then creates it. On an SD card Android
 * serves the library through its FUSE daemon, where listing a directory costs 20-100 ms and each
 * failed access() 2-4 ms, so an install of a few thousand files spends many minutes in "Reserving
 * space" before any byte is downloaded. Measured on an AYN Thor, Portal 2 (3,698 files) listed
 * directories 11,712 times.
 *
 * Only the client writes into its library, so a listing it has read once stays true as long as the
 * client's own creates, renames and deletes are applied to it. Those are, and a listing also
 * expires after DIRCACHE_TTL_NS. The cache serves:
 *   - readdir() of a directory listed before, from memory (opendir() still opens it for real);
 *   - access() of a name absent from its directory's listing: ENOENT without a FUSE round trip.
 * Only absolute paths under the library's mount, only in the client. BL_NO_DIRCACHE=1 turns it off.
 */
#define _GNU_SOURCE
#include <dirent.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#define DIRCACHE_SLOTS 64
#define DIRCACHE_TTL_NS 30000000000LL
#define DIRCACHE_PATH_MAX 1024

struct name {
  unsigned char type;
  ino_t ino;
  char *name;
};

struct listing {
  char path[DIRCACHE_PATH_MAX];
  long long expires;
  long long used;
  size_t count, capacity;
  struct name *names;
};

/* One open DIR the cache serves or records. */
struct reader {
  DIR *dir;
  char path[DIRCACHE_PATH_MAX];
  int serving;            /* entries come from snapshot */
  size_t pos;
  size_t count;
  struct name *snapshot;  /* serving: copy taken at opendir */
  int recording, broken;  /* not serving: capture a full read */
  size_t capacity;
  struct dirent64 out;
  struct reader *next;
};

static struct listing listings[DIRCACHE_SLOTS];
static struct reader *readers;
static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;

void bl_meta_log(const char *op, int dirfd, const char *path, int flags, unsigned long long start, long result, int err)
    __attribute__((visibility("hidden")));
unsigned long long bl_meta_now(void) __attribute__((visibility("hidden")));

static long long now_ns(void) {
  struct timespec t;
  clock_gettime(CLOCK_MONOTONIC_COARSE, &t);
  return (long long)t.tv_sec * 1000000000LL + t.tv_nsec;
}

static int in_scope(const char *path) {
  static int on = -1;
  if (on < 0) {
    const char *off = getenv("BL_NO_DIRCACHE");
    on = strcmp(program_invocation_short_name, "steam") == 0 &&
         strstr(program_invocation_name, "steamrtarm64") != NULL && !(off && strcmp(off, "1") == 0);
  }
  if (!on || path == NULL || path[0] != '/') return 0;
  if (strlen(path) >= DIRCACHE_PATH_MAX) return 0;
  return strncmp(path, "/mnt/droiddeck-sd/", 18) == 0 || strcmp(path, "/mnt/droiddeck-sd") == 0 ||
         strncmp(path, "/mnt/bannerlator-sd/", 20) == 0 || strcmp(path, "/mnt/bannerlator-sd") == 0;
}

/* "/a/b//c/" -> "/a/b/c"; refuses "." and ".." components. */
static int normalize(const char *in, char *out) {
  size_t o = 0;
  for (const char *p = in; *p;) {
    while (*p == '/') p++;
    if (!*p) break;
    const char *end = strchr(p, '/');
    size_t len = end ? (size_t)(end - p) : strlen(p);
    if ((len == 1 && p[0] == '.') || (len == 2 && p[0] == '.' && p[1] == '.')) return 0;
    if (o + 1 + len >= DIRCACHE_PATH_MAX) return 0;
    out[o++] = '/';
    memcpy(out + o, p, len);
    o += len;
    p += len;
  }
  if (o == 0) out[o++] = '/';
  out[o] = '\0';
  return 1;
}

static int split(const char *path, char *parent, const char **base) {
  if (!normalize(path, parent)) return 0;
  char *slash = strrchr(parent, '/');
  if (slash == NULL || slash[1] == '\0') return 0;
  *base = path + (strlen(path) - strlen(slash + 1));
  /* base must point at the normalized last component; recompute from parent copy */
  static __thread char last[DIRCACHE_PATH_MAX];
  strcpy(last, slash + 1);
  *base = last;
  if (slash == parent) slash[1] = '\0'; else *slash = '\0';
  return 1;
}

static void drop(struct listing *l) {
  for (size_t i = 0; i < l->count; i++) free(l->names[i].name);
  free(l->names);
  memset(l, 0, sizeof(*l));
}

static struct listing *find(const char *path, long long now) {
  for (int i = 0; i < DIRCACHE_SLOTS; i++) {
    struct listing *l = &listings[i];
    if (l->names == NULL && l->count == 0 && l->path[0] == '\0') continue;
    if (strcmp(l->path, path) != 0) continue;
    if (l->expires <= now) { drop(l); return NULL; }
    l->used = now;
    return l;
  }
  return NULL;
}

static ssize_t index_of(struct listing *l, const char *name) {
  for (size_t i = 0; i < l->count; i++)
    if (strcmp(l->names[i].name, name) == 0) return (ssize_t)i;
  return -1;
}

static void add_name(struct listing *l, const char *name, unsigned char type, ino_t ino) {
  if (index_of(l, name) >= 0) return;
  if (l->count == l->capacity) {
    size_t cap = l->capacity ? l->capacity * 2 : 32;
    struct name *grown = realloc(l->names, cap * sizeof(*grown));
    if (grown == NULL) { drop(l); return; }
    l->names = grown;
    l->capacity = cap;
  }
  char *copy = strdup(name);
  if (copy == NULL) { drop(l); return; }
  l->names[l->count++] = (struct name){type, ino, copy};
}

static void remove_name(struct listing *l, const char *name) {
  ssize_t i = index_of(l, name);
  if (i < 0) return;
  free(l->names[i].name);
  l->names[i] = l->names[--l->count];
}

static int in_scope(const char *path);

/* A change the cache can't place (a relative path): no listing can be trusted after it. */
static void forget_all(void) {
  if (!in_scope("/mnt/droiddeck-sd")) return;
  pthread_mutex_lock(&lock);
  for (int i = 0; i < DIRCACHE_SLOTS; i++) drop(&listings[i]);
  pthread_mutex_unlock(&lock);
}

/* The client's own change to a directory, applied to its listing when one is cached. */
static void note(const char *path, int added, unsigned char type) {
  char parent[DIRCACHE_PATH_MAX];
  const char *base;
  if (path != NULL && path[0] != '/') { forget_all(); return; }
  if (!in_scope(path) || !split(path, parent, &base)) return;
  pthread_mutex_lock(&lock);
  struct listing *l = find(parent, now_ns());
  if (l) {
    if (added) add_name(l, base, type, 0);
    else remove_name(l, base);
  }
  if (!added && type == DT_DIR) {
    char self[DIRCACHE_PATH_MAX];
    if (normalize(path, self)) {
      size_t n = strlen(self);
      for (int i = 0; i < DIRCACHE_SLOTS; i++)
        if (strncmp(listings[i].path, self, n) == 0 && (listings[i].path[n] == '\0' || listings[i].path[n] == '/'))
          drop(&listings[i]);
    }
  }
  pthread_mutex_unlock(&lock);
}

void bl_dircache_created(const char *path) __attribute__((visibility("hidden")));
void bl_dircache_created(const char *path) { note(path, 1, DT_REG); }

/* 1: the path is known not to exist (errno set to ENOENT); 0: ask the filesystem. */
int bl_dircache_absent(const char *path) __attribute__((visibility("hidden")));
int bl_dircache_absent(const char *path) {
  char parent[DIRCACHE_PATH_MAX];
  const char *base;
  if (!in_scope(path) || !split(path, parent, &base)) return 0;
  int absent = 0;
  pthread_mutex_lock(&lock);
  struct listing *l = find(parent, now_ns());
  if (l && index_of(l, base) < 0) absent = 1;
  pthread_mutex_unlock(&lock);
  if (absent) errno = ENOENT;
  return absent;
}

static struct reader *reader_of(DIR *d) {
  for (struct reader *r = readers; r; r = r->next)
    if (r->dir == d) return r;
  return NULL;
}

static void store_listing(struct reader *r) {
  long long now = now_ns();
  struct listing *slot = NULL;
  for (int i = 0; i < DIRCACHE_SLOTS; i++) {
    struct listing *l = &listings[i];
    if (strcmp(l->path, r->path) == 0) { slot = l; break; }
  }
  if (slot == NULL) {
    for (int i = 0; i < DIRCACHE_SLOTS; i++) {
      struct listing *l = &listings[i];
      if (l->path[0] == '\0' || l->expires <= now) { slot = l; break; }
      if (slot == NULL || l->used < slot->used) slot = l;
    }
  }
  drop(slot);
  strcpy(slot->path, r->path);
  slot->names = r->snapshot;
  slot->count = slot->capacity = r->count;
  slot->expires = now + DIRCACHE_TTL_NS;
  slot->used = now;
  r->snapshot = NULL;
  r->count = 0;
}

static void free_reader(struct reader *r) {
  if (r->snapshot) {
    for (size_t i = 0; i < r->count; i++) free(r->snapshot[i].name);
    free(r->snapshot);
  }
  free(r);
}

DIR *opendir(const char *path) {
  static DIR *(*real)(const char *);
  if (real == NULL) real = dlsym(RTLD_NEXT, "opendir");
  unsigned long long t0 = bl_meta_now();
  DIR *d = real(path);
  int e = errno;
  bl_meta_log("opendir", -100, path, 0, t0, d ? 0 : -1, e);
  if (d == NULL || !in_scope(path)) { errno = e; return d; }
  struct reader *r = calloc(1, sizeof(*r));
  if (r == NULL || !normalize(path, r->path)) { free(r); errno = e; return d; }
  r->dir = d;
  pthread_mutex_lock(&lock);
  struct listing *l = find(r->path, now_ns());
  if (l) {
    r->snapshot = calloc(l->count ? l->count : 1, sizeof(struct name));
    if (r->snapshot) {
      r->serving = 1;
      for (size_t i = 0; i < l->count; i++) {
        r->snapshot[i] = l->names[i];
        r->snapshot[i].name = strdup(l->names[i].name);
        if (r->snapshot[i].name == NULL) { r->serving = 0; break; }
        r->count = i + 1;
      }
      if (!r->serving) { for (size_t i = 0; i < r->count; i++) free(r->snapshot[i].name); free(r->snapshot); r->snapshot = NULL; r->count = 0; }
    }
  }
  if (!r->serving) r->recording = 1;
  r->next = readers;
  readers = r;
  pthread_mutex_unlock(&lock);
  errno = e;
  return d;
}

struct dirent64 *readdir64(DIR *d) {
  static struct dirent64 *(*real)(DIR *);
  if (real == NULL) real = dlsym(RTLD_NEXT, "readdir64");
  pthread_mutex_lock(&lock);
  struct reader *r = reader_of(d);
  if (r && r->serving) {
    /* "." and ".." first, as the filesystem lists them. */
    struct dirent64 *out = &r->out;
    memset(out, 0, sizeof(*out));
    size_t total = r->count + 2;
    if (r->pos >= total) { pthread_mutex_unlock(&lock); return NULL; }
    if (r->pos < 2) {
      strcpy(out->d_name, r->pos == 0 ? "." : "..");
      out->d_type = DT_DIR;
      out->d_ino = 1;
    } else {
      struct name *n = &r->snapshot[r->pos - 2];
      strncpy(out->d_name, n->name, sizeof(out->d_name) - 1);
      out->d_type = n->type;
      out->d_ino = n->ino ? n->ino : 2 + r->pos;
    }
    r->pos++;
    out->d_off = (off_t)r->pos;
    out->d_reclen = sizeof(*out);
    pthread_mutex_unlock(&lock);
    return out;
  }
  pthread_mutex_unlock(&lock);
  int entry_errno = errno;
  errno = 0;  /* the end of a listing leaves errno alone; a failure sets it */
  struct dirent64 *e = real(d);
  int err = errno;
  pthread_mutex_lock(&lock);
  r = reader_of(d);
  if (r && r->recording && !r->broken) {
    if (e == NULL) {
      if (err == 0) store_listing(r);
      r->recording = 0;
    } else if (strcmp(e->d_name, ".") != 0 && strcmp(e->d_name, "..") != 0) {
      if (r->count == r->capacity) {
        size_t cap = r->capacity ? r->capacity * 2 : 32;
        struct name *grown = realloc(r->snapshot, cap * sizeof(*grown));
        if (grown == NULL) r->broken = 1;
        else { r->snapshot = grown; r->capacity = cap; }
      }
      if (!r->broken) {
        char *copy = strdup(e->d_name);
        if (copy == NULL) r->broken = 1;
        else r->snapshot[r->count++] = (struct name){e->d_type, e->d_ino, copy};
      }
    }
  }
  pthread_mutex_unlock(&lock);
  errno = err == 0 ? entry_errno : err;
  return e;
}
struct dirent *readdir(DIR *d) __attribute__((alias("readdir64")));

int closedir(DIR *d) {
  static int (*real)(DIR *);
  if (real == NULL) real = dlsym(RTLD_NEXT, "closedir");
  pthread_mutex_lock(&lock);
  for (struct reader **link = &readers; *link; link = &(*link)->next) {
    if ((*link)->dir == d) {
      struct reader *r = *link;
      *link = r->next;
      free_reader(r);
      break;
    }
  }
  pthread_mutex_unlock(&lock);
  return real(d);
}

void rewinddir(DIR *d) {
  static void (*real)(DIR *);
  if (real == NULL) real = dlsym(RTLD_NEXT, "rewinddir");
  pthread_mutex_lock(&lock);
  struct reader *r = reader_of(d);
  if (r) {
    if (r->serving) r->pos = 0;
    else r->broken = 1;  /* a partial recording can't be trusted */
  }
  pthread_mutex_unlock(&lock);
  real(d);
}

/* Positions in a served listing are ours; a recording stops being trusted once the caller seeks. */
long telldir(DIR *d) {
  static long (*real)(DIR *);
  if (real == NULL) real = dlsym(RTLD_NEXT, "telldir");
  pthread_mutex_lock(&lock);
  struct reader *r = reader_of(d);
  long pos = r && r->serving ? (long)r->pos : -2;
  pthread_mutex_unlock(&lock);
  return pos != -2 ? pos : real(d);
}

void seekdir(DIR *d, long pos) {
  static void (*real)(DIR *, long);
  if (real == NULL) real = dlsym(RTLD_NEXT, "seekdir");
  pthread_mutex_lock(&lock);
  struct reader *r = reader_of(d);
  if (r && r->serving) { r->pos = pos < 0 ? 0 : (size_t)pos; pthread_mutex_unlock(&lock); return; }
  if (r) r->broken = 1;
  pthread_mutex_unlock(&lock);
  real(d, pos);
}

#define MUTATE(name, sig, args, path, after)                       \
  int name sig {                                                   \
    static int (*real) sig;                                        \
    if (real == NULL) real = dlsym(RTLD_NEXT, #name);              \
    int r = real args;                                             \
    int e = errno;                                                 \
    if (r == 0) { after; }                                         \
    errno = e;                                                     \
    return r;                                                      \
  }

MUTATE(mkdir, (const char *p, mode_t m), (p, m), p, note(p, 1, DT_DIR))
MUTATE(unlink, (const char *p), (p), p, note(p, 0, DT_REG))
MUTATE(rmdir, (const char *p), (p), p, note(p, 0, DT_DIR))
MUTATE(rename, (const char *a, const char *b), (a, b), a, { note(a, 0, DT_DIR); note(b, 0, DT_DIR); note(b, 1, DT_UNKNOWN); })


#define MUTATE_AT(name, sig, args)                                  \
  int name sig {                                                    \
    static int (*real) sig;                                         \
    if (real == NULL) real = dlsym(RTLD_NEXT, #name);               \
    int r = real args;                                              \
    int e = errno;                                                  \
    if (r == 0) forget_all();                                       \
    errno = e;                                                      \
    return r;                                                       \
  }

MUTATE_AT(mkdirat, (int d, const char *p, mode_t m), (d, p, m))
MUTATE_AT(unlinkat, (int d, const char *p, int f), (d, p, f))
MUTATE_AT(renameat, (int a, const char *p, int b, const char *q), (a, p, b, q))
MUTATE_AT(renameat2, (int a, const char *p, int b, const char *q, unsigned f), (a, p, b, q, f))
MUTATE_AT(linkat, (int a, const char *p, int b, const char *q, int f), (a, p, b, q, f))
MUTATE_AT(symlinkat, (const char *t, int d, const char *p), (t, d, p))
MUTATE(link, (const char *a, const char *b), (a, b), b, note(b, 1, DT_UNKNOWN))
MUTATE(symlink, (const char *a, const char *b), (a, b), b, note(b, 1, DT_LNK))
int creat(const char *p, mode_t m) {
  static int (*real)(const char *, mode_t);
  if (real == NULL) real = dlsym(RTLD_NEXT, "creat");
  int fd = real(p, m);
  int e = errno;
  if (fd >= 0) note(p, 1, DT_REG);
  errno = e;
  return fd;
}
