// Part of libddnatv-hook.so, included by ddnatv-hook.c (one translation unit, so the
// parts share their static functions and are built with the same command as before).
//
// Opening, creating and inspecting files: open, fopen, temporary files, directories, the stat family.

#ifndef DDNATV_HOST_TEST

#ifndef O_TMPFILE
#define O_TMPFILE (020000000 | 00200000)
#endif

static inline int open_needs_mode(int flags) {
#ifdef O_TMPFILE
    return ((flags & O_CREAT) != 0) || ((flags & O_TMPFILE) == O_TMPFILE);
#else
    return (flags & O_CREAT) != 0;
#endif
}

// The app is root for the programs it runs, and root puts names in every directory and takes them out
// whatever the mode of the directory; the app does not. A directory that is the app's own can have lost
// its owner's rights: an earlier version left the directories dpkg makes (with mode 0) at mode 0 when the
// chmod that follows did not take effect, and a package can ship a read-only one (#44). When a call that
// makes or removes a name is refused in such a directory, the directory is given its owner's rights and the
// call is made once more. Returns 1 when the directory was repaired (the call can be made again).
static int give_dir_owner_rights(int dirfd, const char *name) {
    static int (*orig_fchmodat)(int, const char *, mode_t, int) = NULL;
    if (!name || !*name) return 0;
    char parent[PATH_MAX];
    size_t len = strlen(name);
    if (len >= sizeof(parent)) return 0;
    memcpy(parent, name, len + 1);
    while (len > 1 && parent[len - 1] == '/') parent[--len] = '\0';
    char *slash = strrchr(parent, '/');
    if (!slash) {
        strcpy(parent, ".");
    } else if (slash == parent) {
        parent[1] = '\0';
    } else {
        *slash = '\0';
    }
    struct stat st;
    if (real_fstatat(dirfd, parent, &st, 0) != 0 || !S_ISDIR(st.st_mode)) return 0;
    if (__atomic_load_n(&g_app_uid, __ATOMIC_RELAXED) == (uid_t)-1) load_app_ids();
    if (st.st_uid != __atomic_load_n(&g_app_uid, __ATOMIC_RELAXED)) return 0;
    if ((st.st_mode & 0700) == 0700) return 0;
    if (!orig_fchmodat) orig_fchmodat = (int (*)(int, const char *, mode_t, int))dlsym(RTLD_NEXT, "fchmodat");
    return orig_fchmodat && orig_fchmodat(dirfd, parent, (st.st_mode | 0700) & 07777, 0) == 0;
}

// For the calls that were refused (EACCES): whether the directory of `name` was repaired, so that the call
// can be made again. errno stays what the call left when it was not.
static int refused_in_repaired_dir(int dirfd, const char *name) {
    if (errno != EACCES) return 0;
    int saved = errno;
    int fixed = give_dir_owner_rights(dirfd, name);
    if (!fixed) errno = saved;
    return fixed;
}

static int dlopen_preload_deps(const char *path, void *(*orig_dlopen)(const char *, int), int depth);

// Hook dlopen and dlmopen
void *dlopen(const char *filename, int flags) {
    static void *(*orig_dlopen)(const char *, int) = NULL;
    if (!orig_dlopen) orig_dlopen = (void *(*)(const char *, int))dlsym(RTLD_NEXT, "dlopen");
    if (!filename) return orig_dlopen ? orig_dlopen(NULL, flags) : NULL;
    if (!orig_dlopen) return NULL;

    char buf[PATH_MAX];
    const char *target = rewrite_path(filename, buf, sizeof(buf));
    void *handle = orig_dlopen(target, flags);
    if (!handle && target[0] == '/' && !(flags & RTLD_NOLOAD)) {
        // Its dependencies may be in its RUNPATH, which ld.so looked for outside the rootfs
        int saved = errno;
        if (dlopen_preload_deps(target, orig_dlopen, 0) > 0) handle = orig_dlopen(target, flags);
        if (!handle) errno = saved;
    }
    return handle;
}

void *dlmopen(Lmid_t lmid, const char *filename, int flags) {
    static void *(*orig_dlmopen)(Lmid_t, const char *, int) = NULL;
    if (!orig_dlmopen) orig_dlmopen = (void *(*)(Lmid_t, const char *, int))dlsym(RTLD_NEXT, "dlmopen");
    if (!filename) return orig_dlmopen ? orig_dlmopen(lmid, NULL, flags) : NULL;

    char buf[PATH_MAX];
    const char *target = rewrite_path(filename, buf, sizeof(buf));
    return orig_dlmopen ? orig_dlmopen(lmid, target, flags) : NULL;
}

// Hook open
int open(const char *pathname, int flags, ...) {
    static int (*orig_open)(const char *, int, ...) = NULL;
    if (!orig_open) orig_open = (int (*)(const char *, int, ...))dlsym(RTLD_NEXT, "open");
    init_ddnatv_hook();
    char exe_buf[PATH_MAX];
    if (is_proc_self_exe(pathname)) {
        if (g_real_exe[0] != '\0') {
            pathname = g_real_exe;
        } else if (handle_proc_self_exe(exe_buf, sizeof(exe_buf)) > 0) {
            pathname = exe_buf;
        }
    }
    char buf[PATH_MAX];
    const char *target = rewrite_path(pathname, buf, sizeof(buf));
    if (open_needs_mode(flags)) {
        va_list args;
        va_start(args, flags);
        mode_t mode = va_arg(args, mode_t);
        va_end(args);
        int fd = orig_open(target, flags, mode);
        if (fd < 0 && (flags & O_CREAT) && refused_in_repaired_dir(AT_FDCWD, target)) fd = orig_open(target, flags, mode);
        return fd;
    }
    return orig_open(target, flags);
}

// Hook openat
int openat(int dirfd, const char *pathname, int flags, ...) {
    static int (*orig_openat)(int, const char *, int, ...) = NULL;
    if (!orig_openat) orig_openat = (int (*)(int, const char *, int, ...))dlsym(RTLD_NEXT, "openat");
    init_ddnatv_hook();
    DDNATV_AT_REMAP(dirfd, pathname);
    char exe_buf[PATH_MAX];
    if (is_proc_self_exe(pathname)) {
        if (g_real_exe[0] != '\0') {
            pathname = g_real_exe;
        } else if (handle_proc_self_exe(exe_buf, sizeof(exe_buf)) > 0) {
            pathname = exe_buf;
        }
    }
    char buf[PATH_MAX];
    const char *target = (pathname && pathname[0] == '/') ? rewrite_path(pathname, buf, sizeof(buf)) : pathname;
    if (open_needs_mode(flags)) {
        va_list args;
        va_start(args, flags);
        mode_t mode = va_arg(args, mode_t);
        va_end(args);
        int fd = orig_openat(dirfd, target, flags, mode);
        if (fd < 0 && (flags & O_CREAT) && refused_in_repaired_dir(dirfd, target)) fd = orig_openat(dirfd, target, flags, mode);
        return fd;
    }
    return orig_openat(dirfd, target, flags);
}

// Hook creat
int creat(const char *pathname, mode_t mode) {
    return open(pathname, O_CREAT | O_WRONLY | O_TRUNC, mode);
}

// Fortified open variants used by GNU tar and other coreutils compiled with _FORTIFY_SOURCE=2/3
int __open_2(const char *pathname, int flags) {
    return open(pathname, flags);
}

int __openat_2(int dirfd, const char *pathname, int flags) {
    return openat(dirfd, pathname, flags);
}

// openat2/open_how local definition: avoids depending on <linux/openat2.h>,
// which old NDK/cross headers may lack. Layout matches UAPI ver0
// (flags, mode, resolve = 3 x __u64); extra fields are never read here.
struct ddnatv_open_how {
    uint64_t flags;
    uint64_t mode;
    uint64_t resolve;
};
#ifndef RESOLVE_BENEATH
#define RESOLVE_BENEATH 0x08
#endif
#ifndef __NR_openat2
#define __NR_openat2 437
#endif
#ifndef __NR_fchmodat2
#define __NR_fchmodat2 452
#endif

static long call_orig_syscall(long number, unsigned long a1, unsigned long a2, unsigned long a3, unsigned long a4, unsigned long a5, unsigned long a6) {
    static long (*orig_syscall)(long, unsigned long, unsigned long, unsigned long, unsigned long, unsigned long, unsigned long) = NULL;
    if (!orig_syscall) {
        orig_syscall = (long (*)(long, unsigned long, unsigned long, unsigned long, unsigned long, unsigned long, unsigned long))dlsym(RTLD_NEXT, "syscall");
    }
    return orig_syscall ? orig_syscall(number, a1, a2, a3, a4, a5, a6) : syscall(number, a1, a2, a3, a4, a5, a6);
}

// Hook openat2 (used by GNU tar 1.35+/gnulib for RESOLVE_BENEATH traversal).
// Rewrites absolute paths like openat, then delegates; on ENOSYS (kernels
// without openat2, e.g. Android < 5.6) emulates with plain openat instead of
// trusting every caller to implement the fallback itself.
int openat2(int dirfd, const char *pathname, struct ddnatv_open_how *how, size_t usize) {
    static int (*orig_openat2)(int, const char *, struct ddnatv_open_how *, size_t) = NULL;
    if (!orig_openat2) orig_openat2 = (int (*)(int, const char *, struct ddnatv_open_how *, size_t))dlsym(RTLD_NEXT, "openat2");
    if (!pathname) {
        errno = EFAULT;
        return -1;
    }
    char exe_buf[PATH_MAX];
    if (is_proc_self_exe(pathname)) {
        if (g_real_exe[0] != '\0') {
            pathname = g_real_exe;
        } else if (handle_proc_self_exe(exe_buf, sizeof(exe_buf)) > 0) {
            pathname = exe_buf;
        }
    }
    char buf[PATH_MAX];
    const char *target = (pathname[0] == '/') ? rewrite_path(pathname, buf, sizeof(buf)) : pathname;
    if (orig_openat2) {
        int ret = orig_openat2(dirfd, target, how, usize);
        if (ret >= 0 || errno != ENOSYS) return ret;
    }
#ifdef __NR_openat2
    long sret = call_orig_syscall(__NR_openat2, (unsigned long)dirfd, (unsigned long)target, (unsigned long)how, (unsigned long)usize, 0, 0);
    if (sret >= 0 || errno != ENOSYS) {
        return (int)sret;
    }
#endif
    if (!how || usize < 16) {
        errno = EINVAL;
        return -1;
    }
    int flags = (int)(how->flags & 0xffffffffu);
    mode_t mode = (mode_t)(how->mode & 07777u);

    // RESOLVE_BENEATH, RESOLVE_NO_SYMLINKS and the other resolve flags restrict every component of
    // the path, which a plain openat() cannot do (a symlink in the middle would still be followed).
    // Say so: callers such as gnulib's openat2 (GNU tar) then walk the path safely themselves.
    if (how->resolve != 0) {
        errno = ENOSYS;
        return -1;
    }

    if (open_needs_mode(flags)) {
        return openat(dirfd, target, flags, mode);
    }
    return openat(dirfd, target, flags);
}

// Hook fchmodat2 (used by GNU tar for lchmod-style symlink chmod).
// Delegates when the libc symbol exists, otherwise emulates: symlinks cannot
// be chmod'ed without kernel support, so skip them (return 0) like gnulib,
// and route everything else through fchmodat.
int fchmodat2(int dirfd, const char *pathname, mode_t mode, int flags) {
    static int (*orig_fchmodat2)(int, const char *, mode_t, int) = NULL;
    if (!orig_fchmodat2) orig_fchmodat2 = (int (*)(int, const char *, mode_t, int))dlsym(RTLD_NEXT, "fchmodat2");
    if (!pathname) {
        errno = EFAULT;
        return -1;
    }
    char buf[PATH_MAX];
    const char *target = (pathname[0] == '/') ? rewrite_path(pathname, buf, sizeof(buf)) : pathname;
    if (orig_fchmodat2) {
        int ret = orig_fchmodat2(dirfd, target, mode, flags);
        if (ret == 0 || errno != ENOSYS) return ret;
    }
    if ((flags & AT_SYMLINK_NOFOLLOW) != 0) {
        struct stat st;
        if (fstatat(dirfd, target, &st, AT_SYMLINK_NOFOLLOW) == 0 && S_ISLNK(st.st_mode)) {
            return 0;
        }
    }
    return fchmodat(dirfd, target, mode, 0);
}


// Hook fopen
FILE *fopen(const char *pathname, const char *mode) {
    static FILE *(*orig_fopen)(const char *, const char *) = NULL;
    if (!orig_fopen) orig_fopen = (FILE *(*)(const char *, const char *))dlsym(RTLD_NEXT, "fopen");
    char buf[PATH_MAX];
    const char *target = rewrite_path(pathname, buf, sizeof(buf));
    return orig_fopen(target, mode);
}

// Hook freopen
FILE *freopen(const char *pathname, const char *mode, FILE *stream) {
    static FILE *(*orig_freopen)(const char *, const char *, FILE *) = NULL;
    if (!orig_freopen) orig_freopen = (FILE *(*)(const char *, const char *, FILE *))dlsym(RTLD_NEXT, "freopen");
    char buf[PATH_MAX];
    const char *target = rewrite_path(pathname, buf, sizeof(buf));
    return orig_freopen(target, mode, stream);
}

// Hook write
ssize_t write(int fd, const void *buf, size_t count) {
    static ssize_t (*orig_write)(int, const void *, size_t) = NULL;
    if (!orig_write) orig_write = (ssize_t (*)(int, const void *, size_t))dlsym(RTLD_NEXT, "write");
    return orig_write ? orig_write(fd, buf, count) : -1;
}

// Hook mkstemp
int mkstemp(char *template) {
    static int (*orig_mkstemp)(char *) = NULL;
    if (!orig_mkstemp) orig_mkstemp = (int (*)(char *))dlsym(RTLD_NEXT, "mkstemp");
    if (!template) { errno = EINVAL; return -1; }

    char buf[PATH_MAX];
    const char *target = rewrite_path(template, buf, sizeof(buf));
    if (target == template) {
        return orig_mkstemp ? orig_mkstemp(template) : -1;
    }

    char tmp_buf[PATH_MAX];
    strncpy(tmp_buf, target, sizeof(tmp_buf) - 1);
    tmp_buf[sizeof(tmp_buf) - 1] = '\0';

    int fd = orig_mkstemp ? orig_mkstemp(tmp_buf) : -1;
    if (fd >= 0) {
        size_t orig_len = strlen(template);
        size_t tmp_len = strlen(tmp_buf);
        if (orig_len >= 6 && tmp_len >= 6) {
            memcpy(template + orig_len - 6, tmp_buf + tmp_len - 6, 6);
        }
    }
    return fd;
}

// Hook mkostemp
int mkostemp(char *template, int flags) {
    static int (*orig_mkostemp)(char *, int) = NULL;
    if (!orig_mkostemp) orig_mkostemp = (int (*)(char *, int))dlsym(RTLD_NEXT, "mkostemp");
    if (!template) { errno = EINVAL; return -1; }

    char buf[PATH_MAX];
    const char *target = rewrite_path(template, buf, sizeof(buf));
    if (target == template) {
        return orig_mkostemp ? orig_mkostemp(template, flags) : -1;
    }

    char tmp_buf[PATH_MAX];
    strncpy(tmp_buf, target, sizeof(tmp_buf) - 1);
    tmp_buf[sizeof(tmp_buf) - 1] = '\0';

    int fd = orig_mkostemp ? orig_mkostemp(tmp_buf, flags) : -1;
    if (fd >= 0) {
        size_t orig_len = strlen(template);
        size_t tmp_len = strlen(tmp_buf);
        if (orig_len >= 6 && tmp_len >= 6) {
            memcpy(template + orig_len - 6, tmp_buf + tmp_len - 6, 6);
        }
    }
    return fd;
}

// Hook mkstemps
int mkstemps(char *template, int suffixlen) {
    static int (*orig_mkstemps)(char *, int) = NULL;
    if (!orig_mkstemps) orig_mkstemps = (int (*)(char *, int))dlsym(RTLD_NEXT, "mkstemps");
    if (!template) { errno = EINVAL; return -1; }

    char buf[PATH_MAX];
    const char *target = rewrite_path(template, buf, sizeof(buf));
    if (target == template) {
        return orig_mkstemps ? orig_mkstemps(template, suffixlen) : -1;
    }

    char tmp_buf[PATH_MAX];
    strncpy(tmp_buf, target, sizeof(tmp_buf) - 1);
    tmp_buf[sizeof(tmp_buf) - 1] = '\0';

    int fd = orig_mkstemps ? orig_mkstemps(tmp_buf, suffixlen) : -1;
    if (fd >= 0) {
        size_t orig_len = strlen(template);
        size_t tmp_len = strlen(tmp_buf);
        if (orig_len >= (size_t)(suffixlen + 6) && tmp_len >= (size_t)(suffixlen + 6)) {
            memcpy(template + orig_len - suffixlen - 6, tmp_buf + tmp_len - suffixlen - 6, 6);
        }
    }
    return fd;
}

// Hook mkostemps
int mkostemps(char *template, int suffixlen, int flags) {
    static int (*orig_mkostemps)(char *, int, int) = NULL;
    if (!orig_mkostemps) orig_mkostemps = (int (*)(char *, int, int))dlsym(RTLD_NEXT, "mkostemps");
    if (!template) { errno = EINVAL; return -1; }

    char buf[PATH_MAX];
    const char *target = rewrite_path(template, buf, sizeof(buf));
    if (target == template) {
        return orig_mkostemps ? orig_mkostemps(template, suffixlen, flags) : -1;
    }

    char tmp_buf[PATH_MAX];
    strncpy(tmp_buf, target, sizeof(tmp_buf) - 1);
    tmp_buf[sizeof(tmp_buf) - 1] = '\0';

    int fd = orig_mkostemps ? orig_mkostemps(tmp_buf, suffixlen, flags) : -1;
    if (fd >= 0) {
        size_t orig_len = strlen(template);
        size_t tmp_len = strlen(tmp_buf);
        if (orig_len >= (size_t)(suffixlen + 6) && tmp_len >= (size_t)(suffixlen + 6)) {
            memcpy(template + orig_len - suffixlen - 6, tmp_buf + tmp_len - suffixlen - 6, 6);
        }
    }
    return fd;
}

// Hook mkdtemp
char *mkdtemp(char *template) {
    static char *(*orig_mkdtemp)(char *) = NULL;
    if (!orig_mkdtemp) orig_mkdtemp = (char *(*)(char *))dlsym(RTLD_NEXT, "mkdtemp");
    if (!template) { errno = EINVAL; return NULL; }

    char buf[PATH_MAX];
    const char *target = rewrite_path(template, buf, sizeof(buf));
    if (target == template) {
        return orig_mkdtemp ? orig_mkdtemp(template) : NULL;
    }

    char tmp_buf[PATH_MAX];
    strncpy(tmp_buf, target, sizeof(tmp_buf) - 1);
    tmp_buf[sizeof(tmp_buf) - 1] = '\0';

    char *res = orig_mkdtemp ? orig_mkdtemp(tmp_buf) : NULL;
    if (res) {
        size_t orig_len = strlen(template);
        size_t tmp_len = strlen(tmp_buf);
        if (orig_len >= 6 && tmp_len >= 6) {
            memcpy(template + orig_len - 6, tmp_buf + tmp_len - 6, 6);
        }
        return template;
    }
    return NULL;
}

// Hook tmpfile
FILE *tmpfile(void) {
    init_ddnatv_hook();
    char template_buf[PATH_MAX];
    if (g_ddnatv_root[0] != '\0') {
        snprintf(template_buf, sizeof(template_buf), "%s/tmp/tmpfile.XXXXXX", g_ddnatv_root);
    } else {
        snprintf(template_buf, sizeof(template_buf), "/tmp/tmpfile.XXXXXX");
    }
    int fd = mkstemp(template_buf);
    if (fd < 0) return NULL;
    unlink(template_buf);
    FILE *f = fdopen(fd, "w+b");
    if (!f) {
        int saved_errno = errno;
        close(fd);
        errno = saved_errno;
    }
    return f;
}

#ifndef __USE_FILE_OFFSET64
// Programs built with _LARGEFILE64_SOURCE call this name. With _FILE_OFFSET_BITS=64 (the 32-bit ARM
// build) the headers already export the definition above as tmpfile64.
FILE *tmpfile64(void) {
    return tmpfile();
}
#endif

// Hook opendir
DIR *opendir(const char *name) {
    static DIR *(*orig_opendir)(const char *) = NULL;
    if (!orig_opendir) orig_opendir = (DIR *(*)(const char *))dlsym(RTLD_NEXT, "opendir");
    char buf[PATH_MAX];
    const char *target = rewrite_path(name, buf, sizeof(buf));
    return orig_opendir(target);
}

// Hook stat
int stat(const char *pathname, struct stat *statbuf) {
    static int (*orig_stat)(const char *, struct stat *) = NULL;
    if (!orig_stat) orig_stat = (int (*)(const char *, struct stat *))dlsym(RTLD_NEXT, "stat");
    char buf[PATH_MAX];
    const char *target = rewrite_path(pathname, buf, sizeof(buf));
    int ret = orig_stat ? orig_stat(target, statbuf) : -1;
    if (ret != 0 && errno == ENOENT) {
        char alt[PATH_MAX];
        const char *retry = usrmerge_retry_path(target, alt, sizeof(alt));
        if (retry) ret = orig_stat(retry, statbuf);
    }
    if (ret == 0) apply_stat_emulation(statbuf);
    return ret;
}

// Hook lstat
int lstat(const char *pathname, struct stat *statbuf) {
    static int (*orig_lstat)(const char *, struct stat *) = NULL;
    if (!orig_lstat) orig_lstat = (int (*)(const char *, struct stat *))dlsym(RTLD_NEXT, "lstat");
    char buf[PATH_MAX];
    const char *target = rewrite_path(pathname, buf, sizeof(buf));
    int ret = orig_lstat ? orig_lstat(target, statbuf) : -1;
    char alt[PATH_MAX];
    if (ret != 0 && errno == ENOENT) {
        const char *retry = usrmerge_retry_path(target, alt, sizeof(alt));
        if (retry) {
            ret = orig_lstat(retry, statbuf);
            if (ret == 0) target = retry;
        }
    }
    if (ret == 0 && statbuf && S_ISLNK(statbuf->st_mode) && g_ddnatv_root[0] != '\0' && target &&
        readlink_result_may_change(pathname, (long long)statbuf->st_size)) {
        char link_buf[PATH_MAX];
        static ssize_t (*orig_readlink)(const char *, char *, size_t) = NULL;
        if (!orig_readlink) orig_readlink = (ssize_t (*)(const char *, char *, size_t))dlsym(RTLD_NEXT, "readlink");
        ssize_t llen = orig_readlink ? orig_readlink(target, link_buf, sizeof(link_buf) - 1) : -1;
        if (llen > 0) {
            ssize_t post_len = postprocess_readlink(pathname, link_buf, llen, sizeof(link_buf));
            if (post_len > 0) {
                statbuf->st_size = post_len;
            }
        }
    }
    if (ret == 0) apply_stat_emulation(statbuf);
    return ret;
}

// Hook fstatat
int fstatat(int dirfd, const char *pathname, struct stat *statbuf, int flags) {
    static int (*orig_fstatat)(int, const char *, struct stat *, int) = NULL;
    if (!orig_fstatat) orig_fstatat = (int (*)(int, const char *, struct stat *, int))dlsym(RTLD_NEXT, "fstatat");
    DDNATV_AT_REMAP(dirfd, pathname);
    char buf[PATH_MAX];
    const char *target = (pathname && pathname[0] == '/') ? rewrite_path(pathname, buf, sizeof(buf)) : pathname;
    int ret = orig_fstatat ? orig_fstatat(dirfd, target, statbuf, flags) : -1;
    char alt[PATH_MAX];
    if (ret != 0 && errno == ENOENT && pathname && pathname[0] == '/') {
        const char *retry = usrmerge_retry_path(target, alt, sizeof(alt));
        if (retry) {
            ret = orig_fstatat(dirfd, retry, statbuf, flags);
            if (ret == 0) target = retry;
        }
    }
    if (ret == 0 && statbuf && (flags & AT_SYMLINK_NOFOLLOW) && S_ISLNK(statbuf->st_mode) && g_ddnatv_root[0] != '\0' && target &&
        readlink_result_may_change(pathname, (long long)statbuf->st_size)) {
        char link_buf[PATH_MAX];
        static ssize_t (*orig_readlinkat)(int, const char *, char *, size_t) = NULL;
        if (!orig_readlinkat) orig_readlinkat = (ssize_t (*)(int, const char *, char *, size_t))dlsym(RTLD_NEXT, "readlinkat");
        ssize_t llen = orig_readlinkat ? orig_readlinkat(dirfd, target, link_buf, sizeof(link_buf) - 1) : -1;
        if (llen > 0) {
            ssize_t post_len = postprocess_readlink(pathname, link_buf, llen, sizeof(link_buf));
            if (post_len > 0) {
                statbuf->st_size = post_len;
            }
        }
    }
    if (ret == 0) apply_stat_emulation(statbuf);
    return ret;
}

// Hook fstat
int fstat(int fd, struct stat *statbuf) {
    static int (*orig_fstat)(int, struct stat *) = NULL;
    if (!orig_fstat) orig_fstat = (int (*)(int, struct stat *))dlsym(RTLD_NEXT, "fstat");
    int ret = orig_fstat ? orig_fstat(fd, statbuf) : -1;
    if (ret == 0) apply_stat_emulation(statbuf);
    return ret;
}

// Glibc __xstat compatibility hooks
int __xstat(int ver, const char *pathname, struct stat *statbuf) {
    static int (*orig___xstat)(int, const char *, struct stat *) = NULL;
    if (!orig___xstat) orig___xstat = (int (*)(int, const char *, struct stat *))dlsym(RTLD_NEXT, "__xstat");
    char buf[PATH_MAX];
    const char *target = rewrite_path(pathname, buf, sizeof(buf));
    return orig___xstat ? orig___xstat(ver, target, statbuf) : stat(target, statbuf);
}

int __xstat64(int ver, const char *pathname, struct stat64 *statbuf) {
    static int (*orig___xstat64)(int, const char *, struct stat64 *) = NULL;
    if (!orig___xstat64) orig___xstat64 = (int (*)(int, const char *, struct stat64 *))dlsym(RTLD_NEXT, "__xstat64");
    char buf[PATH_MAX];
    const char *target = rewrite_path(pathname, buf, sizeof(buf));
    return orig___xstat64 ? orig___xstat64(ver, target, statbuf) : stat(target, (struct stat *)statbuf);
}

int __lxstat(int ver, const char *pathname, struct stat *statbuf) {
    static int (*orig___lxstat)(int, const char *, struct stat *) = NULL;
    if (!orig___lxstat) orig___lxstat = (int (*)(int, const char *, struct stat *))dlsym(RTLD_NEXT, "__lxstat");
    char buf[PATH_MAX];
    const char *target = rewrite_path(pathname, buf, sizeof(buf));
    return orig___lxstat ? orig___lxstat(ver, target, statbuf) : lstat(target, statbuf);
}

int __lxstat64(int ver, const char *pathname, struct stat64 *statbuf) {
    static int (*orig___lxstat64)(int, const char *, struct stat64 *) = NULL;
    if (!orig___lxstat64) orig___lxstat64 = (int (*)(int, const char *, struct stat64 *))dlsym(RTLD_NEXT, "__lxstat64");
    char buf[PATH_MAX];
    const char *target = rewrite_path(pathname, buf, sizeof(buf));
    return orig___lxstat64 ? orig___lxstat64(ver, target, statbuf) : lstat(target, (struct stat *)statbuf);
}

int __fxstatat(int ver, int dirfd, const char *pathname, struct stat *statbuf, int flags) {
    static int (*orig___fxstatat)(int, int, const char *, struct stat *, int) = NULL;
    if (!orig___fxstatat) orig___fxstatat = (int (*)(int, int, const char *, struct stat *, int))dlsym(RTLD_NEXT, "__fxstatat");
    char buf[PATH_MAX];
    const char *target = (pathname && pathname[0] == '/') ? rewrite_path(pathname, buf, sizeof(buf)) : pathname;
    return orig___fxstatat ? orig___fxstatat(ver, dirfd, target, statbuf, flags) : fstatat(dirfd, target, statbuf, flags);
}

int __fxstatat64(int ver, int dirfd, const char *pathname, struct stat64 *statbuf, int flags) {
    static int (*orig___fxstatat64)(int, int, const char *, struct stat64 *, int) = NULL;
    if (!orig___fxstatat64) orig___fxstatat64 = (int (*)(int, int, const char *, struct stat64 *, int))dlsym(RTLD_NEXT, "__fxstatat64");
    char buf[PATH_MAX];
    const char *target = (pathname && pathname[0] == '/') ? rewrite_path(pathname, buf, sizeof(buf)) : pathname;
    return orig___fxstatat64 ? orig___fxstatat64(ver, dirfd, target, statbuf, flags) : fstatat(dirfd, target, (struct stat *)statbuf, flags);
}

// Hook statx
int statx(int dirfd, const char *pathname, int flags, unsigned int mask, struct statx *statxbuf) {
    // Rust's standard library asks whether statx exists by calling it with a NULL path and buffer
    // (EFAULT means it does). glibc's header declares both as nonnull, so the compiler would drop
    // the NULL check below and read the path: every Rust program (rg, rustc, cargo...) crashed.
    __asm__ volatile("" : "+r"(pathname), "+r"(statxbuf));
    static int (*orig_statx)(int, const char *, int, unsigned int, struct statx *) = NULL;
    if (!orig_statx) orig_statx = (int (*)(int, const char *, int, unsigned int, struct statx *))dlsym(RTLD_NEXT, "statx");
    DDNATV_AT_REMAP(dirfd, pathname);
    char buf[PATH_MAX];
    const char *target = (pathname && pathname[0] == '/') ? rewrite_path(pathname, buf, sizeof(buf)) : pathname;
    int ret = orig_statx ? orig_statx(dirfd, target, flags, mask, statxbuf) : -1;
    if (ret == 0 && statxbuf && hardlinks_maybe_any()) {
        const unsigned int needed = STATX_INO | STATX_NLINK | STATX_MTIME;
        struct statx full;
        const struct statx *sx = statxbuf;
        if ((statxbuf->stx_mask & needed) != needed) {
            // The caller asked for less ("stat -c %h" for the link count only): what an emulated
            // link is recognized by
            int saved_errno = errno;
            sx = orig_statx(dirfd, target, flags, needed, &full) == 0 && (full.stx_mask & needed) == needed ? &full : NULL;
            errno = saved_errno;
        }
        if (sx) {
            struct stat st;
            memset(&st, 0, sizeof(st));
            st.st_dev = makedev(sx->stx_dev_major, sx->stx_dev_minor);
            st.st_ino = sx->stx_ino;
            st.st_nlink = sx->stx_nlink;
            st.st_mtim.tv_sec = sx->stx_mtime.tv_sec;
            st.st_mtim.tv_nsec = sx->stx_mtime.tv_nsec;
            apply_emulated_hardlink(&st);
            if (st.st_ino != sx->stx_ino || st.st_nlink != sx->stx_nlink) {
                statxbuf->stx_ino = st.st_ino;
                statxbuf->stx_nlink = st.st_nlink;
                statxbuf->stx_mask |= STATX_INO | STATX_NLINK;
            }
        }
    }
    if (ret == 0 && statxbuf) {
        uid_t uid = (uid_t)statxbuf->stx_uid;
        gid_t gid = (gid_t)statxbuf->stx_gid;
        present_owner_as_root((statxbuf->stx_mask & STATX_UID) ? &uid : NULL, (statxbuf->stx_mask & STATX_GID) ? &gid : NULL);
        statxbuf->stx_uid = (uint32_t)uid;
        statxbuf->stx_gid = (uint32_t)gid;
    }
    return ret;
}

#endif /* !DDNATV_HOST_TEST */
