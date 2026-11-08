// Part of libddnatv-hook.so, included by ddnatv-hook.c (one translation unit, so the
// parts share their static functions and are built with the same command as before).
//
// Other file operations: access, chmod, unlink, mkdir, rename, readlink, extended attributes,
// links, times, truncate, statfs, the working directory, realpath and scandir.

#ifndef DDNATV_HOST_TEST

// Hook faccessat
int faccessat(int dirfd, const char *pathname, int mode, int flags) {
    init_ddnatv_hook();
    if (!pathname) {
        errno = EFAULT;
        return -1;
    }

    struct stat st;
    int stat_flags = (flags & AT_SYMLINK_NOFOLLOW) ? AT_SYMLINK_NOFOLLOW : 0;
    if (fstatat(dirfd, pathname, &st, stat_flags) != 0) {
        return -1;
    }

    if (mode == F_OK) {
        return 0;
    }

    if (mode & X_OK) {
        if (S_ISDIR(st.st_mode)) return 0;
        if ((st.st_mode & 0111) != 0) return 0;
        if (strstr(pathname, "/bin/") || strstr(pathname, "/sbin/")) return 0;
        errno = EACCES;
        return -1;
    }

    return 0;
}

int faccessat2(int dirfd, const char *pathname, int mode, int flags) {
    return faccessat(dirfd, pathname, mode, flags);
}

// Hook access
int access(const char *pathname, int mode) {
    return faccessat(AT_FDCWD, pathname, mode, 0);
}

// Hook euidaccess and eaccess
int euidaccess(const char *pathname, int mode) {
    return faccessat(AT_FDCWD, pathname, mode, AT_EACCESS);
}

int eaccess(const char *pathname, int mode) {
    return faccessat(AT_FDCWD, pathname, mode, AT_EACCESS);
}

// Hook chmod
int chmod(const char *pathname, mode_t mode) {
    static int (*orig_chmod)(const char *, mode_t) = NULL;
    if (!orig_chmod) orig_chmod = (int (*)(const char *, mode_t))dlsym(RTLD_NEXT, "chmod");
    if (!orig_chmod) return -1;
    char buf[PATH_MAX];
    const char *target = rewrite_path(pathname, buf, sizeof(buf));
    int ret = orig_chmod(target, mode);
    if (ret != 0 && ((mode & 06000) != 0 || errno == EPERM || errno == EACCES || errno == ENOSYS)) {
        ret = orig_chmod(target, mode & 01777);
    }
    if (ret != 0 && (errno == EPERM || errno == EACCES)) {
        errno = 0;
        return 0;
    }
    return ret;
}

// Hook fchmod
int fchmod(int fd, mode_t mode) {
    static int (*orig_fchmod)(int, mode_t) = NULL;
    if (!orig_fchmod) orig_fchmod = (int (*)(int, mode_t))dlsym(RTLD_NEXT, "fchmod");
    if (!orig_fchmod) return -1;
    int ret = orig_fchmod(fd, mode);
    if (ret != 0 && ((mode & 06000) != 0 || errno == EPERM || errno == EACCES || errno == ENOSYS)) {
        ret = orig_fchmod(fd, mode & 01777);
    }
    if (ret != 0 && (errno == EPERM || errno == EACCES)) {
        errno = 0;
        return 0;
    }
    return ret;
}

// Hook fchmodat
int fchmodat(int dirfd, const char *pathname, mode_t mode, int flags) {
    static int (*orig_fchmodat)(int, const char *, mode_t, int) = NULL;
    if (!orig_fchmodat) orig_fchmodat = (int (*)(int, const char *, mode_t, int))dlsym(RTLD_NEXT, "fchmodat");
    if (!orig_fchmodat) return -1;
    char buf[PATH_MAX];
    const char *target = (pathname && pathname[0] == '/') ? rewrite_path(pathname, buf, sizeof(buf)) : pathname;
    int ret = orig_fchmodat(dirfd, target, mode, flags);
    if (ret != 0 && ((mode & 06000) != 0 || errno == EPERM || errno == EACCES || errno == ENOSYS)) {
        ret = orig_fchmodat(dirfd, target, mode & 01777, flags);
    }
    if (ret != 0 && (errno == EPERM || errno == EACCES)) {
        errno = 0;
        return 0;
    }
    return ret;
}

// Hook unlink
int unlink(const char *pathname) {
    static int (*orig_unlink)(const char *) = NULL;
    if (!orig_unlink) orig_unlink = (int (*)(const char *))dlsym(RTLD_NEXT, "unlink");
    char buf[PATH_MAX];
    const char *target = rewrite_path(pathname, buf, sizeof(buf));
    unlink_mapped_unix_socket(target);
    dev_t hl_dev = 0;
    ino_t hl_ino = 0;
    int hardlinked = lookup_emulated_hardlink_at(AT_FDCWD, target, &hl_dev, &hl_ino);
    int ret = orig_unlink(target);
    if (ret != 0 && refused_in_repaired_dir(AT_FDCWD, target)) {
        // The name could not be looked up while its directory was closed
        hardlinked = lookup_emulated_hardlink_at(AT_FDCWD, target, &hl_dev, &hl_ino);
        ret = orig_unlink(target);
    }
    if (ret == 0 && hardlinked) unregister_emulated_hardlink(hl_dev, hl_ino);
    return ret;
}

// Hook unlinkat
int unlinkat(int dirfd, const char *pathname, int flags) {
    static int (*orig_unlinkat)(int, const char *, int) = NULL;
    if (!orig_unlinkat) orig_unlinkat = (int (*)(int, const char *, int))dlsym(RTLD_NEXT, "unlinkat");
    char buf[PATH_MAX];
    const char *target = (pathname && pathname[0] == '/') ? rewrite_path(pathname, buf, sizeof(buf)) : pathname;
    dev_t hl_dev = 0;
    ino_t hl_ino = 0;
    int hardlinked = 0;
    if (target) {
        unlink_mapped_unix_socket(target);
        hardlinked = lookup_emulated_hardlink_at(dirfd, target, &hl_dev, &hl_ino);
    }
    int ret = orig_unlinkat ? orig_unlinkat(dirfd, target, flags) : -1;
    if (ret != 0 && orig_unlinkat && refused_in_repaired_dir(dirfd, target)) {
        hardlinked = target ? lookup_emulated_hardlink_at(dirfd, target, &hl_dev, &hl_ino) : 0;
        ret = orig_unlinkat(dirfd, target, flags);
    }
    if (ret == 0 && hardlinked) unregister_emulated_hardlink(hl_dev, hl_ino);
    return ret;
}

// Hook rmdir
int rmdir(const char *pathname) {
    static int (*orig_rmdir)(const char *) = NULL;
    if (!orig_rmdir) orig_rmdir = (int (*)(const char *))dlsym(RTLD_NEXT, "rmdir");
    char buf[PATH_MAX];
    const char *target = rewrite_path(pathname, buf, sizeof(buf));
    int ret = orig_rmdir(target);
    if (ret != 0 && refused_in_repaired_dir(AT_FDCWD, target)) ret = orig_rmdir(target);
    return ret;
}

// The app is root for the programs it runs, and root enters and writes every directory whatever its
// mode; the app does not. dpkg makes each new directory with mode 0 and sets the mode a moment later,
// and a chmod that fails is reported as done (see chmod), which left directories nobody could put a
// file in: "Permission denied" in /usr/share/applications and others (#44). So a directory always
// keeps its owner's read, write and search rights.
#define NEW_DIR_OWNER_RIGHTS S_IRWXU

// Hook mkdir
int mkdir(const char *pathname, mode_t mode) {
    static int (*orig_mkdir)(const char *, mode_t) = NULL;
    if (!orig_mkdir) orig_mkdir = (int (*)(const char *, mode_t))dlsym(RTLD_NEXT, "mkdir");
    char buf[PATH_MAX];
    const char *target = rewrite_path(pathname, buf, sizeof(buf));
    int ret = orig_mkdir(target, mode | NEW_DIR_OWNER_RIGHTS);
    if (ret != 0 && refused_in_repaired_dir(AT_FDCWD, target)) ret = orig_mkdir(target, mode | NEW_DIR_OWNER_RIGHTS);
    return ret;
}

// Hook mkdirat
int mkdirat(int dirfd, const char *pathname, mode_t mode) {
    static int (*orig_mkdirat)(int, const char *, mode_t) = NULL;
    if (!orig_mkdirat) orig_mkdirat = (int (*)(int, const char *, mode_t))dlsym(RTLD_NEXT, "mkdirat");
    char buf[PATH_MAX];
    const char *target = (pathname && pathname[0] == '/') ? rewrite_path(pathname, buf, sizeof(buf)) : pathname;
    int ret = orig_mkdirat(dirfd, target, mode | NEW_DIR_OWNER_RIGHTS);
    if (ret != 0 && refused_in_repaired_dir(dirfd, target)) ret = orig_mkdirat(dirfd, target, mode | NEW_DIR_OWNER_RIGHTS);
    return ret;
}

static void ensure_dir_exists(const char *path) {
    if (!path || path[0] == '\0') return;
    char tmp[PATH_MAX];
    strncpy(tmp, path, sizeof(tmp) - 1);
    tmp[sizeof(tmp) - 1] = '\0';
    for (char *p = tmp + 1; *p; p++) {
        if (*p == '/') {
            *p = '\0';
            mkdir(tmp, 0755);
            *p = '/';
        }
    }
    mkdir(tmp, 0755);
}

// Hook inotify_add_watch
int inotify_add_watch(int fd, const char *pathname, uint32_t mask) {
    static int (*orig_inotify_add_watch)(int, const char *, uint32_t) = NULL;
    if (!orig_inotify_add_watch) orig_inotify_add_watch = (int (*)(int, const char *, uint32_t))dlsym(RTLD_NEXT, "inotify_add_watch");
    if (!pathname) return -1;
    char buf[PATH_MAX];
    const char *target = rewrite_path(pathname, buf, sizeof(buf));
    if (!orig_inotify_add_watch) return -1;
    int wd = orig_inotify_add_watch(fd, target, mask);
    if (wd < 0 && errno == ENOENT && (mask & IN_ONLYDIR)) {
        ensure_dir_exists(target);
        wd = orig_inotify_add_watch(fd, target, mask);
    }
    return wd;
}

// Hook rename
int rename(const char *oldpath, const char *newpath) {
    static int (*orig_rename)(const char *, const char *) = NULL;
    if (!orig_rename) orig_rename = (int (*)(const char *, const char *))dlsym(RTLD_NEXT, "rename");
    char buf1[PATH_MAX];
    char buf2[PATH_MAX];
    const char *target_old = rewrite_path(oldpath, buf1, sizeof(buf1));
    const char *target_new = rewrite_path(newpath, buf2, sizeof(buf2));
    dev_t hl_dev = 0;
    ino_t hl_ino = 0;
    int hardlinked = lookup_emulated_hardlink_at(AT_FDCWD, target_new, &hl_dev, &hl_ino);
    int ret = orig_rename(target_old, target_new);
    if (ret != 0 && errno == EACCES) {
        // Both directories: the old name is taken out of one, the new put in the other
        int refused = errno;
        int fixed_old = refused_in_repaired_dir(AT_FDCWD, target_old);
        errno = refused;
        int fixed_new = refused_in_repaired_dir(AT_FDCWD, target_new);
        if (fixed_old || fixed_new) {
            hardlinked = lookup_emulated_hardlink_at(AT_FDCWD, target_new, &hl_dev, &hl_ino);
            ret = orig_rename(target_old, target_new);
        }
    }
    if (ret == 0 && hardlinked) unregister_emulated_hardlink(hl_dev, hl_ino);
    return ret;
}

// Hook renameat
int renameat(int olddirfd, const char *oldpath, int newdirfd, const char *newpath) {
    static int (*orig_renameat)(int, const char *, int, const char *) = NULL;
    if (!orig_renameat) orig_renameat = (int (*)(int, const char *, int, const char *))dlsym(RTLD_NEXT, "renameat");
    char buf1[PATH_MAX];
    char buf2[PATH_MAX];
    const char *target_old = (oldpath && oldpath[0] == '/') ? rewrite_path(oldpath, buf1, sizeof(buf1)) : oldpath;
    const char *target_new = (newpath && newpath[0] == '/') ? rewrite_path(newpath, buf2, sizeof(buf2)) : newpath;
    dev_t hl_dev = 0;
    ino_t hl_ino = 0;
    int hardlinked = lookup_emulated_hardlink_at(newdirfd, target_new, &hl_dev, &hl_ino);
    int ret = orig_renameat(olddirfd, target_old, newdirfd, target_new);
    if (ret != 0 && errno == EACCES) {
        int refused = errno;
        int fixed_old = refused_in_repaired_dir(olddirfd, target_old);
        errno = refused;
        int fixed_new = refused_in_repaired_dir(newdirfd, target_new);
        if (fixed_old || fixed_new) {
            hardlinked = lookup_emulated_hardlink_at(newdirfd, target_new, &hl_dev, &hl_ino);
            ret = orig_renameat(olddirfd, target_old, newdirfd, target_new);
        }
    }
    if (ret == 0 && hardlinked) unregister_emulated_hardlink(hl_dev, hl_ino);
    return ret;
}

// Hook renameat2
int renameat2(int olddirfd, const char *oldpath, int newdirfd, const char *newpath, unsigned int flags) {
    static int (*orig_renameat2)(int, const char *, int, const char *, unsigned int) = NULL;
    if (!orig_renameat2) {
        orig_renameat2 = (int (*)(int, const char *, int, const char *, unsigned int))dlsym(RTLD_NEXT, "renameat2");
        if (!orig_renameat2) return renameat(olddirfd, oldpath, newdirfd, newpath);
    }
    char buf1[PATH_MAX];
    char buf2[PATH_MAX];
    const char *target_old = (oldpath && oldpath[0] == '/') ? rewrite_path(oldpath, buf1, sizeof(buf1)) : oldpath;
    const char *target_new = (newpath && newpath[0] == '/') ? rewrite_path(newpath, buf2, sizeof(buf2)) : newpath;
    dev_t hl_dev = 0;
    ino_t hl_ino = 0;
    // RENAME_EXCHANGE keeps both files, so nothing is replaced.
    int hardlinked = !(flags & RENAME_EXCHANGE) && lookup_emulated_hardlink_at(newdirfd, target_new, &hl_dev, &hl_ino);
    int ret = orig_renameat2(olddirfd, target_old, newdirfd, target_new, flags);
    if (ret != 0 && errno == EACCES) {
        int refused = errno;
        int fixed_old = refused_in_repaired_dir(olddirfd, target_old);
        errno = refused;
        int fixed_new = refused_in_repaired_dir(newdirfd, target_new);
        if (fixed_old || fixed_new) {
            hardlinked = !(flags & RENAME_EXCHANGE) && lookup_emulated_hardlink_at(newdirfd, target_new, &hl_dev, &hl_ino);
            ret = orig_renameat2(olddirfd, target_old, newdirfd, target_new, flags);
        }
    }
    if (ret == 0 && hardlinked) unregister_emulated_hardlink(hl_dev, hl_ino);
    return ret;
}

// Hook readlink
ssize_t readlink(const char *pathname, char *buf, size_t bufsiz) {
    if (is_proc_self_exe(pathname)) {
        ssize_t exe_len = handle_proc_self_exe(buf, bufsiz);
        if (exe_len >= 0) return exe_len;
    }
    static ssize_t (*orig_readlink)(const char *, char *, size_t) = NULL;
    if (!orig_readlink) orig_readlink = (ssize_t (*)(const char *, char *, size_t))dlsym(RTLD_NEXT, "readlink");
    char pbuf[PATH_MAX];
    const char *target = rewrite_path(pathname, pbuf, sizeof(pbuf));
    ssize_t ret = orig_readlink ? orig_readlink(target, buf, bufsiz) : -1;
    if (ret < 0 && errno == ENOENT) {
        char alt[PATH_MAX];
        const char *retry = usrmerge_retry_path(target, alt, sizeof(alt));
        if (retry) ret = orig_readlink(retry, buf, bufsiz);
    }
    return postprocess_readlink(pathname, buf, ret, bufsiz);
}

// Hook readlinkat
ssize_t readlinkat(int dirfd, const char *pathname, char *buf, size_t bufsiz) {
    if (is_proc_self_exe(pathname)) {
        ssize_t exe_len = handle_proc_self_exe(buf, bufsiz);
        if (exe_len >= 0) return exe_len;
    }
    static ssize_t (*orig_readlinkat)(int, const char *, char *, size_t) = NULL;
    if (!orig_readlinkat) orig_readlinkat = (ssize_t (*)(int, const char *, char *, size_t))dlsym(RTLD_NEXT, "readlinkat");
    char pbuf[PATH_MAX];
    const char *target = (pathname && pathname[0] == '/') ? rewrite_path(pathname, pbuf, sizeof(pbuf)) : pathname;
    ssize_t ret = orig_readlinkat ? orig_readlinkat(dirfd, target, buf, bufsiz) : -1;
    if (ret < 0 && errno == ENOENT && pathname && pathname[0] == '/') {
        char alt[PATH_MAX];
        const char *retry = usrmerge_retry_path(target, alt, sizeof(alt));
        if (retry) ret = orig_readlinkat(dirfd, retry, buf, bufsiz);
    }
    return postprocess_readlink(pathname, buf, ret, bufsiz);
}

// Fortified readlink variants used by dpkg / update-alternatives
ssize_t __readlink_chk(const char *pathname, char *buf, size_t bufsiz, size_t buflen) {
    return readlink(pathname, buf, bufsiz);
}

ssize_t __readlinkat_chk(int dirfd, const char *pathname, char *buf, size_t bufsiz, size_t buflen) {
    return readlinkat(dirfd, pathname, buf, bufsiz);
}

// Ubuntu's programs find Android's SELinux (enforcing) through libselinux and then ask its policy
// questions it has no answers for. The one that mattered: PAM's pam_rootok, which lets root
// change its own entries, asks "may this user be root" and was refused, so chfn, chsh and
// `adduser --gecos` (the installation of PostgreSQL) failed with "PAM: Authentication failure".
// Ubuntu does not use SELinux, so for its programs it is off, as its labels are hidden below.
int is_selinux_enabled(void) { return 0; }

// Extended attributes take the same paths as stat(). Without these hooks coreutils read
// security.selinux with the Ubuntu path itself: `ls -l` printed an error line for every file
// Android lacks (/etc/os-release) and showed Android's own label for the ones it has
// (/etc/passwd). Ubuntu does not use SELinux, so its label is hidden altogether: `ls -l` looks as
// on Ubuntu and cp/tar/rsync do not try to copy Android's labels.
ssize_t getxattr(const char *path, const char *name, void *value, size_t size) {
    static ssize_t (*orig_getxattr)(const char *, const char *, void *, size_t) = NULL;
    if (!orig_getxattr) orig_getxattr = (ssize_t (*)(const char *, const char *, void *, size_t))dlsym(RTLD_NEXT, "getxattr");
    if (is_selinux_xattr(name)) {
        errno = ENODATA;
        return -1;
    }
    char pbuf[PATH_MAX];
    return orig_getxattr ? orig_getxattr(rewrite_path(path, pbuf, sizeof(pbuf)), name, value, size) : -1;
}

ssize_t lgetxattr(const char *path, const char *name, void *value, size_t size) {
    static ssize_t (*orig_lgetxattr)(const char *, const char *, void *, size_t) = NULL;
    if (!orig_lgetxattr) orig_lgetxattr = (ssize_t (*)(const char *, const char *, void *, size_t))dlsym(RTLD_NEXT, "lgetxattr");
    if (is_selinux_xattr(name)) {
        errno = ENODATA;
        return -1;
    }
    char pbuf[PATH_MAX];
    return orig_lgetxattr ? orig_lgetxattr(rewrite_path(path, pbuf, sizeof(pbuf)), name, value, size) : -1;
}

ssize_t listxattr(const char *path, char *list, size_t size) {
    static ssize_t (*orig_listxattr)(const char *, char *, size_t) = NULL;
    if (!orig_listxattr) orig_listxattr = (ssize_t (*)(const char *, char *, size_t))dlsym(RTLD_NEXT, "listxattr");
    char pbuf[PATH_MAX];
    ssize_t ret = orig_listxattr ? orig_listxattr(rewrite_path(path, pbuf, sizeof(pbuf)), list, size) : -1;
    return size > 0 ? drop_selinux_xattr(list, ret) : ret;
}

ssize_t llistxattr(const char *path, char *list, size_t size) {
    static ssize_t (*orig_llistxattr)(const char *, char *, size_t) = NULL;
    if (!orig_llistxattr) orig_llistxattr = (ssize_t (*)(const char *, char *, size_t))dlsym(RTLD_NEXT, "llistxattr");
    char pbuf[PATH_MAX];
    ssize_t ret = orig_llistxattr ? orig_llistxattr(rewrite_path(path, pbuf, sizeof(pbuf)), list, size) : -1;
    return size > 0 ? drop_selinux_xattr(list, ret) : ret;
}

ssize_t fgetxattr(int fd, const char *name, void *value, size_t size) {
    static ssize_t (*orig_fgetxattr)(int, const char *, void *, size_t) = NULL;
    if (!orig_fgetxattr) orig_fgetxattr = (ssize_t (*)(int, const char *, void *, size_t))dlsym(RTLD_NEXT, "fgetxattr");
    if (is_selinux_xattr(name)) {
        errno = ENODATA;
        return -1;
    }
    return orig_fgetxattr ? orig_fgetxattr(fd, name, value, size) : -1;
}

ssize_t flistxattr(int fd, char *list, size_t size) {
    static ssize_t (*orig_flistxattr)(int, char *, size_t) = NULL;
    if (!orig_flistxattr) orig_flistxattr = (ssize_t (*)(int, char *, size_t))dlsym(RTLD_NEXT, "flistxattr");
    ssize_t ret = orig_flistxattr ? orig_flistxattr(fd, list, size) : -1;
    return size > 0 ? drop_selinux_xattr(list, ret) : ret;
}

int setxattr(const char *path, const char *name, const void *value, size_t size, int flags) {
    static int (*orig_setxattr)(const char *, const char *, const void *, size_t, int) = NULL;
    if (!orig_setxattr) orig_setxattr = (int (*)(const char *, const char *, const void *, size_t, int))dlsym(RTLD_NEXT, "setxattr");
    char pbuf[PATH_MAX];
    return orig_setxattr ? orig_setxattr(rewrite_path(path, pbuf, sizeof(pbuf)), name, value, size, flags) : -1;
}

int lsetxattr(const char *path, const char *name, const void *value, size_t size, int flags) {
    static int (*orig_lsetxattr)(const char *, const char *, const void *, size_t, int) = NULL;
    if (!orig_lsetxattr) orig_lsetxattr = (int (*)(const char *, const char *, const void *, size_t, int))dlsym(RTLD_NEXT, "lsetxattr");
    char pbuf[PATH_MAX];
    return orig_lsetxattr ? orig_lsetxattr(rewrite_path(path, pbuf, sizeof(pbuf)), name, value, size, flags) : -1;
}

int removexattr(const char *path, const char *name) {
    static int (*orig_removexattr)(const char *, const char *) = NULL;
    if (!orig_removexattr) orig_removexattr = (int (*)(const char *, const char *))dlsym(RTLD_NEXT, "removexattr");
    char pbuf[PATH_MAX];
    return orig_removexattr ? orig_removexattr(rewrite_path(path, pbuf, sizeof(pbuf)), name) : -1;
}

int lremovexattr(const char *path, const char *name) {
    static int (*orig_lremovexattr)(const char *, const char *) = NULL;
    if (!orig_lremovexattr) orig_lremovexattr = (int (*)(const char *, const char *))dlsym(RTLD_NEXT, "lremovexattr");
    char pbuf[PATH_MAX];
    return orig_lremovexattr ? orig_lremovexattr(rewrite_path(path, pbuf, sizeof(pbuf)), name) : -1;
}

// Hook symlink
int symlink(const char *target, const char *linkpath) {
    static int (*orig_symlink)(const char *, const char *) = NULL;
    if (!orig_symlink) orig_symlink = (int (*)(const char *, const char *))dlsym(RTLD_NEXT, "symlink");
    char pbuf[PATH_MAX], tbuf[PATH_MAX];
    const char *newlink = rewrite_path(linkpath, pbuf, sizeof(pbuf));
    const char *newtarget = (target && target[0] == '/') ? rewrite_path(target, tbuf, sizeof(tbuf)) : target;
    int ret = orig_symlink ? orig_symlink(newtarget, newlink) : -1;
    if (ret != 0 && orig_symlink && refused_in_repaired_dir(AT_FDCWD, newlink)) ret = orig_symlink(newtarget, newlink);
    return ret;
}

// Hook symlinkat
int symlinkat(const char *target, int newdirfd, const char *linkpath) {
    static int (*orig_symlinkat)(const char *, int, const char *) = NULL;
    if (!orig_symlinkat) orig_symlinkat = (int (*)(const char *, int, const char *))dlsym(RTLD_NEXT, "symlinkat");
    char pbuf[PATH_MAX], tbuf[PATH_MAX];
    const char *newlink = (linkpath && linkpath[0] == '/') ? rewrite_path(linkpath, pbuf, sizeof(pbuf)) : linkpath;
    const char *newtarget = (target && target[0] == '/') ? rewrite_path(target, tbuf, sizeof(tbuf)) : target;
    int ret = orig_symlinkat ? orig_symlinkat(newtarget, newdirfd, newlink) : -1;
    if (ret != 0 && orig_symlinkat && refused_in_repaired_dir(newdirfd, newlink)) ret = orig_symlinkat(newtarget, newdirfd, newlink);
    return ret;
}

// Hook linkat
int linkat(int olddirfd, const char *oldpath, int newdirfd, const char *newpath, int flags) {
    static int (*orig_linkat)(int, const char *, int, const char *, int) = NULL;
    if (!orig_linkat) orig_linkat = (int (*)(int, const char *, int, const char *, int))dlsym(RTLD_NEXT, "linkat");
    char obuf[PATH_MAX], nbuf[PATH_MAX];
    const char *rold = (oldpath && oldpath[0] == '/') ? rewrite_path(oldpath, obuf, sizeof(obuf)) : oldpath;
    const char *rnew = (newpath && newpath[0] == '/') ? rewrite_path(newpath, nbuf, sizeof(nbuf)) : newpath;
    int ret = orig_linkat ? orig_linkat(olddirfd, rold, newdirfd, rnew, flags) : -1;
    if (ret != 0 && orig_linkat && refused_in_repaired_dir(newdirfd, rnew)) ret = orig_linkat(olddirfd, rold, newdirfd, rnew, flags);
    if (ret != 0 && (errno == EXDEV || errno == EPERM || errno == EACCES || errno == ENOTSUP || errno == ENOSYS)) {
        ret = ddnatv_emulate_hardlinkat(olddirfd, rold, newdirfd, rnew);
    }
    return ret;
}

// Hook link
int link(const char *oldpath, const char *newpath) {
    return linkat(AT_FDCWD, oldpath, AT_FDCWD, newpath, 0);
}

// Hook utime / utimes / lutimes / futimesat / utimensat
int utime(const char *filename, const struct utimbuf *times) {
    static int (*orig_utime)(const char *, const struct utimbuf *) = NULL;
    if (!orig_utime) orig_utime = (int (*)(const char *, const struct utimbuf *))dlsym(RTLD_NEXT, "utime");
    char buf[PATH_MAX];
    const char *target = rewrite_path(filename, buf, sizeof(buf));
    return orig_utime ? orig_utime(target, times) : -1;
}

int utimes(const char *filename, const struct timeval times[2]) {
    static int (*orig_utimes)(const char *, const struct timeval[2]) = NULL;
    if (!orig_utimes) orig_utimes = (int (*)(const char *, const struct timeval[2]))dlsym(RTLD_NEXT, "utimes");
    char buf[PATH_MAX];
    const char *target = rewrite_path(filename, buf, sizeof(buf));
    return orig_utimes ? orig_utimes(target, times) : -1;
}

int lutimes(const char *filename, const struct timeval times[2]) {
    static int (*orig_lutimes)(const char *, const struct timeval[2]) = NULL;
    if (!orig_lutimes) orig_lutimes = (int (*)(const char *, const struct timeval[2]))dlsym(RTLD_NEXT, "lutimes");
    char buf[PATH_MAX];
    const char *target = rewrite_path(filename, buf, sizeof(buf));
    return orig_lutimes ? orig_lutimes(target, times) : -1;
}

int futimesat(int dirfd, const char *pathname, const struct timeval times[2]) {
    static int (*orig_futimesat)(int, const char *, const struct timeval[2]) = NULL;
    if (!orig_futimesat) orig_futimesat = (int (*)(int, const char *, const struct timeval[2]))dlsym(RTLD_NEXT, "futimesat");
    char buf[PATH_MAX];
    const char *target = (pathname && pathname[0] == '/') ? rewrite_path(pathname, buf, sizeof(buf)) : pathname;
    return orig_futimesat ? orig_futimesat(dirfd, target, times) : -1;
}

int utimensat(int dirfd, const char *pathname, const struct timespec times[2], int flags) {
    static int (*orig_utimensat)(int, const char *, const struct timespec[2], int) = NULL;
    if (!orig_utimensat) orig_utimensat = (int (*)(int, const char *, const struct timespec[2], int))dlsym(RTLD_NEXT, "utimensat");
    char buf[PATH_MAX];
    const char *target = (pathname && pathname[0] == '/') ? rewrite_path(pathname, buf, sizeof(buf)) : pathname;
    return orig_utimensat ? orig_utimensat(dirfd, target, times, flags) : -1;
}

// Hook truncate
int truncate(const char *path, off_t length) {
    static int (*orig_truncate)(const char *, off_t) = NULL;
    if (!orig_truncate) orig_truncate = (int (*)(const char *, off_t))dlsym(RTLD_NEXT, "truncate");
    char buf[PATH_MAX];
    const char *target = rewrite_path(path, buf, sizeof(buf));
    return orig_truncate(target, length);
}

// Hook statfs / statfs64 / statvfs / statvfs64
int statfs(const char *path, struct statfs *buf) {
    static int (*orig_statfs)(const char *, struct statfs *) = NULL;
    if (!orig_statfs) orig_statfs = (int (*)(const char *, struct statfs *))dlsym(RTLD_NEXT, "statfs");
    char pbuf[PATH_MAX];
    const char *target = rewrite_path(path, pbuf, sizeof(pbuf));
    return orig_statfs ? orig_statfs(target, buf) : -1;
}

int statvfs(const char *path, struct statvfs *buf) {
    static int (*orig_statvfs)(const char *, struct statvfs *) = NULL;
    if (!orig_statvfs) orig_statvfs = (int (*)(const char *, struct statvfs *))dlsym(RTLD_NEXT, "statvfs");
    char pbuf[PATH_MAX];
    const char *target = rewrite_path(path, pbuf, sizeof(pbuf));
    return orig_statvfs ? orig_statvfs(target, buf) : -1;
}

// Hook chdir
int chdir(const char *path) {
    static int (*orig_chdir)(const char *) = NULL;
    if (!orig_chdir) orig_chdir = (int (*)(const char *))dlsym(RTLD_NEXT, "chdir");
    char buf[PATH_MAX];
    const char *target = rewrite_path(path, buf, sizeof(buf));
    return orig_chdir ? orig_chdir(target) : -1;
}

// Hook fchdir
int fchdir(int fd) {
    static int (*orig_fchdir)(int) = NULL;
    if (!orig_fchdir) orig_fchdir = (int (*)(int))dlsym(RTLD_NEXT, "fchdir");
    return orig_fchdir ? orig_fchdir(fd) : -1;
}

// Hook getcwd
char *getcwd(char *buf, size_t size) {
    static char *(*orig_getcwd)(char *, size_t) = NULL;
    if (!orig_getcwd) orig_getcwd = (char *(*)(char *, size_t))dlsym(RTLD_NEXT, "getcwd");
    return orig_getcwd ? orig_getcwd(buf, size) : NULL;
}

char *__getcwd_chk(char *buf, size_t size, size_t buflen) {
    return getcwd(buf, size);
}

// Hook realpath and canonicalize_file_name
char *realpath(const char *path, char *resolved_path) {
    static char *(*orig_realpath)(const char *, char *) = NULL;
    if (!orig_realpath) orig_realpath = (char *(*)(const char *, char *))dlsym(RTLD_NEXT, "realpath");
    if (!path) {
        errno = EINVAL;
        return NULL;
    }
    init_ddnatv_hook();
    if (is_proc_self_exe(path)) {
        char tmp[PATH_MAX];
        ssize_t n = handle_proc_self_exe(tmp, sizeof(tmp) - 1);
        if (n > 0) {
            tmp[n] = '\0';
            if (resolved_path) {
                strncpy(resolved_path, tmp, PATH_MAX - 1);
                resolved_path[PATH_MAX - 1] = '\0';
                return resolved_path;
            } else {
                return strdup(tmp);
            }
        }
    }
    char pbuf[PATH_MAX];
    const char *target = rewrite_path(path, pbuf, sizeof(pbuf));
    char *res = orig_realpath ? orig_realpath(target, resolved_path) : NULL;
    if (res && g_ddnatv_root[0] != '\0') {
        size_t rlen = g_ddnatv_root_len;
        if (strncmp(res, g_ddnatv_root, rlen) == 0 && (res[rlen] == '/' || res[rlen] == '\0')) {
            size_t rem_len = strlen(res + rlen);
            if (rem_len == 0) {
                res[0] = '/';
                res[1] = '\0';
            } else {
                memmove(res, res + rlen, rem_len + 1);
            }
        }
    }
    return res;
}

char *__realpath_chk(const char *path, char *resolved_path, size_t resolved_len) {
    return realpath(path, resolved_path);
}

char *canonicalize_file_name(const char *path) {
    return realpath(path, NULL);
}

// Hook scandir and scandir64
int scandir(const char *dirp, struct dirent ***namelist,
            int (*filter)(const struct dirent *),
            int (*compar)(const struct dirent **, const struct dirent **)) {
    static int (*orig_scandir)(const char *, struct dirent ***,
                               int (*)(const struct dirent *),
                               int (*)(const struct dirent **, const struct dirent **)) = NULL;
    if (!orig_scandir) orig_scandir = (int (*)(const char *, struct dirent ***,
                               int (*)(const struct dirent *),
                               int (*)(const struct dirent **, const struct dirent **)))dlsym(RTLD_NEXT, "scandir");
    char buf[PATH_MAX];
    const char *target = rewrite_path(dirp, buf, sizeof(buf));
    return orig_scandir ? orig_scandir(target, namelist, filter, compar) : -1;
}

#if defined(__LP64__)
// Export 64-bit Large File Support (LFS) symbol aliases on 64-bit platforms so that
// calls from libraries compiled with LFS (such as libstdc++ calling fopen64) are intercepted.
__asm__(
    ".globl open64\n"        ".set open64, open\n"
    ".globl openat64\n"      ".set openat64, openat\n"
    ".globl __open64_2\n"    ".set __open64_2, __open_2\n"
    ".globl __openat64_2\n"  ".set __openat64_2, __openat_2\n"
    ".globl creat64\n"       ".set creat64, creat\n"
    ".globl fopen64\n"     ".set fopen64, fopen\n"
    ".globl freopen64\n"   ".set freopen64, freopen\n"
    ".globl stat64\n"      ".set stat64, stat\n"
    ".globl lstat64\n"     ".set lstat64, lstat\n"
    ".globl fstatat64\n"   ".set fstatat64, fstatat\n"
    ".globl truncate64\n"  ".set truncate64, truncate\n"
    ".globl statfs64\n"    ".set statfs64, statfs\n"
    ".globl statvfs64\n"   ".set statvfs64, statvfs\n"
    ".globl scandir64\n"   ".set scandir64, scandir\n"
    ".globl mkstemp64\n"   ".set mkstemp64, mkstemp\n"
    ".globl mkostemp64\n"  ".set mkostemp64, mkostemp\n"
    ".globl mkstemps64\n"  ".set mkstemps64, mkstemps\n"
    ".globl mkostemps64\n" ".set mkostemps64, mkostemps\n"
    ".globl tmpfile64\n"   ".set tmpfile64, tmpfile\n"
);
#endif

#endif /* !DDNATV_HOST_TEST */
