#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <grp.h>
#include <pwd.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <unistd.h>

#define REAL(name) static __typeof__(name) *real_##name; if (!real_##name) real_##name = dlsym(RTLD_NEXT, #name)

static uid_t real_uid(void) { return (uid_t)syscall(SYS_getuid); }
static gid_t real_gid(void) { return (gid_t)syscall(SYS_getgid); }

uid_t getuid(void) { return 0; }
uid_t geteuid(void) { return 0; }
gid_t getgid(void) { return 0; }
gid_t getegid(void) { return 0; }

int getresuid(uid_t *r, uid_t *e, uid_t *s) {
  if (r) *r = 0;
  if (e) *e = 0;
  if (s) *s = 0;
  return 0;
}

int getresgid(gid_t *r, gid_t *e, gid_t *s) {
  if (r) *r = 0;
  if (e) *e = 0;
  if (s) *s = 0;
  return 0;
}

int setuid(uid_t u) { (void)u; return 0; }
int seteuid(uid_t u) { (void)u; return 0; }
int setgid(gid_t g) { (void)g; return 0; }
int setegid(gid_t g) { (void)g; return 0; }
int setreuid(uid_t r, uid_t e) { (void)r; (void)e; return 0; }
int setregid(gid_t r, gid_t e) { (void)r; (void)e; return 0; }
int setresuid(uid_t r, uid_t e, uid_t s) { (void)r; (void)e; (void)s; return 0; }
int setresgid(gid_t r, gid_t e, gid_t s) { (void)r; (void)e; (void)s; return 0; }
int setgroups(size_t n, const gid_t *list) { (void)n; (void)list; return 0; }
int initgroups(const char *user, gid_t group) { (void)user; (void)group; return 0; }

static int owner_result(int rc) {
  if (rc != 0 && (errno == EPERM || errno == EINVAL)) {
    errno = 0;
    return 0;
  }
  return rc;
}

int chown(const char *path, uid_t u, gid_t g) {
  REAL(chown);
  return owner_result(real_chown(path, u, g));
}

int lchown(const char *path, uid_t u, gid_t g) {
  REAL(lchown);
  return owner_result(real_lchown(path, u, g));
}

int fchown(int fd, uid_t u, gid_t g) {
  REAL(fchown);
  return owner_result(real_fchown(fd, u, g));
}

int fchownat(int dirfd, const char *path, uid_t u, gid_t g, int flags) {
  REAL(fchownat);
  return owner_result(real_fchownat(dirfd, path, u, g, flags));
}

static struct passwd *as_root_user(struct passwd *pw) {
  if (pw && pw->pw_uid == real_uid()) {
    pw->pw_uid = 0;
    pw->pw_gid = 0;
  }
  return pw;
}

static struct group *as_root_group(struct group *gr) {
  if (gr && gr->gr_gid == real_gid()) gr->gr_gid = 0;
  return gr;
}

struct passwd *getpwuid(uid_t uid) {
  REAL(getpwuid);
  return as_root_user(real_getpwuid(uid == 0 ? real_uid() : uid));
}

struct passwd *getpwnam(const char *name) {
  REAL(getpwnam);
  return as_root_user(real_getpwnam(name));
}

struct passwd *getpwent(void) {
  REAL(getpwent);
  return as_root_user(real_getpwent());
}

int getpwuid_r(uid_t uid, struct passwd *pw, char *buf, size_t len, struct passwd **out) {
  REAL(getpwuid_r);
  int rc = real_getpwuid_r(uid == 0 ? real_uid() : uid, pw, buf, len, out);
  if (rc == 0) as_root_user(*out);
  return rc;
}

int getpwnam_r(const char *name, struct passwd *pw, char *buf, size_t len, struct passwd **out) {
  REAL(getpwnam_r);
  int rc = real_getpwnam_r(name, pw, buf, len, out);
  if (rc == 0) as_root_user(*out);
  return rc;
}

struct group *getgrgid(gid_t gid) {
  REAL(getgrgid);
  return as_root_group(real_getgrgid(gid == 0 ? real_gid() : gid));
}

struct group *getgrnam(const char *name) {
  REAL(getgrnam);
  return as_root_group(real_getgrnam(name));
}

int getgrgid_r(gid_t gid, struct group *gr, char *buf, size_t len, struct group **out) {
  REAL(getgrgid_r);
  int rc = real_getgrgid_r(gid == 0 ? real_gid() : gid, gr, buf, len, out);
  if (rc == 0) as_root_group(*out);
  return rc;
}

int getgrnam_r(const char *name, struct group *gr, char *buf, size_t len, struct group **out) {
  REAL(getgrnam_r);
  int rc = real_getgrnam_r(name, gr, buf, len, out);
  if (rc == 0) as_root_group(*out);
  return rc;
}

static int shown_mode(int rc, struct stat *st) {
  if (rc == 0 && S_ISDIR(st->st_mode) && (st->st_mode & 0777) == 0700) st->st_mode = (st->st_mode & ~0777) | 0755;
  return rc;
}

int stat(const char *path, struct stat *st) {
  REAL(stat);
  return shown_mode(real_stat(path, st), st);
}

int lstat(const char *path, struct stat *st) {
  REAL(lstat);
  return shown_mode(real_lstat(path, st), st);
}

int fstat(int fd, struct stat *st) {
  REAL(fstat);
  return shown_mode(real_fstat(fd, st), st);
}

int fstatat(int dirfd, const char *path, struct stat *st, int flags) {
  REAL(fstatat);
  return shown_mode(real_fstatat(dirfd, path, st, flags), st);
}

int statx(int dirfd, const char *path, int flags, unsigned int mask, struct statx *st) {
  REAL(statx);
  int rc = real_statx(dirfd, path, flags, mask, st);
  if (rc == 0 && S_ISDIR(st->stx_mode) && (st->stx_mode & 0777) == 0700) st->stx_mode = (st->stx_mode & ~0777) | 0755;
  return rc;
}

int stat64(const char *path, struct stat64 *st) {
  REAL(stat64);
  return shown_mode(real_stat64(path, st), (struct stat *)st);
}

int lstat64(const char *path, struct stat64 *st) {
  REAL(lstat64);
  return shown_mode(real_lstat64(path, st), (struct stat *)st);
}

int fstat64(int fd, struct stat64 *st) {
  REAL(fstat64);
  return shown_mode(real_fstat64(fd, st), (struct stat *)st);
}

int fstatat64(int dirfd, const char *path, struct stat64 *st, int flags) {
  REAL(fstatat64);
  return shown_mode(real_fstatat64(dirfd, path, st, flags), (struct stat *)st);
}
