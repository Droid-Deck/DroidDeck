/*
 * Lock files taken by hard link, which Android denies apps.
 *
 * libICE and libXau lock an authority file the classic way: create "<file>-c", then link() it to
 * "<file>-l" - the link either appears or fails because the other holder's is there. Android's
 * SELinux policy refuses hard links in an app's data, so the link fails for everyone, the lock is
 * never taken, and Plasma's session manager gives up at "Could not lock ICEAuthority file". With
 * no session manager there is no Plasma desktop.
 *
 * Only that convention is answered here: when the real link is refused and the new name ends in
 * "-l", a symlink stands in. Creating a symlink fails on an existing name just as a link does, so
 * the lock excludes as before, and the holder removes both names when it is done. Every other
 * link keeps the real answer: a program that links a file into place and then deletes the
 * original would be left with a dangling symlink.
 *
 * That pattern itself is answered for one caller that asks: with DROIDDECK_LINK_RENAME=1 (the
 * session sets it for localedef, which names a finished locale archive that way) a refused link
 * becomes a rename that will not replace an existing name, which is all the link promised there.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static int refused(int error) { return error == EPERM || error == EACCES; }

static int moves_instead(void) {
  const char *v = getenv("DROIDDECK_LINK_RENAME");
  return v != NULL && strcmp(v, "1") == 0;
}

static int lock_name(const char *path) {
  size_t n = path ? strlen(path) : 0;
  return n > 2 && strcmp(path + n - 2, "-l") == 0;
}

/* The answer to link(oldpath, newpath), given the real call's: a refused lock link becomes a symlink. */
__attribute__((visibility("hidden"))) int bl_link_lock(const char *oldpath, const char *newpath, int result) {
  /* libc's own symlink: dircache.c's wrapper may be holding its lock around this call. */
  static int (*real_symlink)(const char *, const char *);
  if (result == 0 || !refused(errno)) return result;
  if (moves_instead()) return renameat2(AT_FDCWD, oldpath, AT_FDCWD, newpath, RENAME_NOREPLACE);
  if (!lock_name(newpath)) return result;
  if (real_symlink == NULL) real_symlink = dlsym(RTLD_NEXT, "symlink");
  return real_symlink(oldpath, newpath);
}

/* 64-bit builds wrap link() in dircache.c, which hands its result to bl_link_lock. */
#if __SIZEOF_POINTER__ != 8
int link(const char *oldpath, const char *newpath) {
  static int (*real_link)(const char *, const char *);
  if (real_link == NULL) real_link = dlsym(RTLD_NEXT, "link");
  return bl_link_lock(oldpath, newpath, real_link(oldpath, newpath));
}
#endif

int linkat(int olddirfd, const char *oldpath, int newdirfd, const char *newpath, int flags) {
  static int (*real_linkat)(int, const char *, int, const char *, int);
  if (real_linkat == NULL) real_linkat = dlsym(RTLD_NEXT, "linkat");
  int result = real_linkat(olddirfd, oldpath, newdirfd, newpath, flags);
  if (result != 0 && refused(errno) && flags == 0 && moves_instead())
    return renameat2(olddirfd, oldpath, newdirfd, newpath, RENAME_NOREPLACE);
  /* A relative old name would resolve against the new name's directory as a symlink's target. */
  if (result != 0 && refused(errno) && lock_name(newpath) && oldpath[0] == '/' && flags == 0)
    return symlinkat(oldpath, newdirfd, newpath);
  return result;
}
