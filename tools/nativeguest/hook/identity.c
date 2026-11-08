// Part of libddnatv-hook.so, included by ddnatv-hook.c (one translation unit, so the
// parts share their static functions and are built with the same command as before).
//
// Fake root: user and group ids, the shadow lock, and passwd/group lookups from the rootfs.

#ifndef DDNATV_HOST_TEST

// Fakeroot identity hooks for APT and DPKG to operate without superuser restrictions
uid_t getuid(void) { return 0; }
uid_t geteuid(void) { return 0; }
gid_t getgid(void) { return 0; }
gid_t getegid(void) { return 0; }

int setuid(uid_t uid) { (void)uid; return 0; }
int seteuid(uid_t uid) { (void)uid; return 0; }
int setgid(gid_t gid) { (void)gid; return 0; }
int setegid(gid_t gid) { (void)gid; return 0; }
int setreuid(uid_t ruid, uid_t euid) { (void)ruid; (void)euid; return 0; }
int setregid(gid_t rgid, gid_t egid) { (void)rgid; (void)egid; return 0; }
int setresuid(uid_t ruid, uid_t euid, uid_t suid) { (void)ruid; (void)euid; (void)suid; return 0; }
int setresgid(gid_t rgid, gid_t egid, gid_t sgid) { (void)rgid; (void)egid; (void)sgid; return 0; }

int getgroups(int size, gid_t list[]) {
    if (size > 0 && list != NULL) list[0] = 0;
    return 1;
}
// What programs built with _FORTIFY_SOURCE call when the buffer's size is known (the check is
// glibc's): without it the count came from the hook (1) and the list from the kernel (the app's
// groups, more than one), and sshd ended the login on "getgroups: Invalid argument"
extern void __chk_fail(void) __attribute__((__noreturn__));
int __getgroups_chk(int size, gid_t list[], size_t listlen) {
    if (size > 0 && (size_t)size * sizeof(gid_t) > listlen) __chk_fail();
    return getgroups(size, list);
}
int setgroups(size_t size, const gid_t *list) { (void)size; (void)list; return 0; }
int initgroups(const char *user, gid_t group) { (void)user; (void)group; return 0; }

int getgrouplist(const char *user, gid_t group, gid_t *groups, int *ngroups) {
    (void)user;
    if (!ngroups) {
        errno = EINVAL;
        return -1;
    }
    if (*ngroups > 0 && groups) {
        groups[0] = group;
        *ngroups = 1;
        return 1;
    }
    // Too small (a caller asking for the size first): -1 and the number needed, as glibc does.
    *ngroups = 1;
    return -1;
}

#endif /* !DDNATV_HOST_TEST */

static uid_t real_uid(void) {
#ifdef DDNATV_HOST_TEST
    return 10000;
#else
    static uid_t (*orig_getuid)(void) = NULL;
    if (!orig_getuid) orig_getuid = (uid_t (*)(void))dlsym(RTLD_NEXT, "getuid");
    return orig_getuid ? orig_getuid() : 0;
#endif
}

static gid_t real_gid(void) {
#ifdef DDNATV_HOST_TEST
    return 10000;
#else
    static gid_t (*orig_getgid)(void) = NULL;
    if (!orig_getgid) orig_getgid = (gid_t (*)(void))dlsym(RTLD_NEXT, "getgid");
    return orig_getgid ? orig_getgid() : 0;
#endif
}

#ifdef DDNATV_HOST_TEST
#define DDNATV_NSS_SYM(name) hook_##name
#define DDNATV_SHADOW_SYM(name) hook_##name
static int g_test_orig_nss_r_ret = 0;
static int g_test_orig_nss_r_found = 0;

int hook_link(const char *oldpath, const char *newpath) {
    int ret = link(oldpath, newpath);
    if (ret != 0 && (errno == EXDEV || errno == EPERM || errno == EACCES || errno == ENOTSUP || errno == ENOSYS)) {
        ret = ddnatv_emulate_hardlink(oldpath, newpath);
    }
    return ret;
}

int hook_linkat(int olddirfd, const char *oldpath, int newdirfd, const char *newpath, int flags) {
    int ret = linkat(olddirfd, oldpath, newdirfd, newpath, flags);
    if (ret != 0 && (errno == EXDEV || errno == EPERM || errno == EACCES || errno == ENOTSUP || errno == ENOSYS)) {
        ret = ddnatv_emulate_hardlinkat(olddirfd, oldpath, newdirfd, newpath);
    }
    return ret;
}

int hook_stat(const char *path, struct stat *st) {
    int ret = stat(path, st);
    if (ret == 0) apply_stat_emulation(st);
    return ret;
}

int hook_unlink(const char *path) {
    dev_t hl_dev = 0;
    ino_t hl_ino = 0;
    int hardlinked = lookup_emulated_hardlink_at(AT_FDCWD, path, &hl_dev, &hl_ino);
    int ret = unlink(path);
    if (ret == 0 && hardlinked) unregister_emulated_hardlink(hl_dev, hl_ino);
    return ret;
}
#else
#define DDNATV_NSS_SYM(name) name
#define DDNATV_SHADOW_SYM(name) name
#endif

// Hook lckpwdf and ulckpwdf (shadow password database locking)
// In glibc, lckpwdf() attempts to open /etc/.pwd.lock directly using internal syscalls
// which fails with EROFS on Android because host /etc is mounted read-only.
static int g_pwd_lock_fd = -1;
static pthread_mutex_t g_pwd_lock_mutex = PTHREAD_MUTEX_INITIALIZER;

int DDNATV_SHADOW_SYM(lckpwdf)(void) {
#ifndef DDNATV_HOST_TEST
    init_ddnatv_hook();
    pthread_mutex_lock(&g_pwd_lock_mutex);
    if (g_pwd_lock_fd != -1) {
        pthread_mutex_unlock(&g_pwd_lock_mutex);
        return 0; // Already locked
    }
    char buf[PATH_MAX];
    const char *target = rewrite_path("/etc/.pwd.lock", buf, sizeof(buf));
    static int (*orig_open)(const char *, int, ...) = NULL;
    if (!orig_open) orig_open = (int (*)(const char *, int, ...))dlsym(RTLD_NEXT, "open");
    if (orig_open && target) {
        g_pwd_lock_fd = orig_open(target, O_WRONLY | O_CREAT | O_CLOEXEC, 0600);
        if (g_pwd_lock_fd >= 0) {
            struct flock fl;
            memset(&fl, 0, sizeof(fl));
            fl.l_type = F_WRLCK;
            fl.l_whence = SEEK_SET;
            fcntl(g_pwd_lock_fd, F_SETLK, &fl);
        }
    }
    if (g_pwd_lock_fd < 0) {
        g_pwd_lock_fd = -2; // In-memory lock held
    }
    pthread_mutex_unlock(&g_pwd_lock_mutex);
#endif
    return 0;
}

int DDNATV_SHADOW_SYM(ulckpwdf)(void) {
#ifndef DDNATV_HOST_TEST
    pthread_mutex_lock(&g_pwd_lock_mutex);
    if (g_pwd_lock_fd >= 0) {
        close(g_pwd_lock_fd);
    }
    g_pwd_lock_fd = -1;
    pthread_mutex_unlock(&g_pwd_lock_mutex);
#endif
    return 0;
}


// Group lookup helper: reads $DDNATV_ROOT/etc/group or falls back to known entries
static int ddnatv_lookup_group(const char *search_name, gid_t search_gid, int by_name,
                               struct group *grp, char *buf, size_t buflen) {
    if (!grp || !buf || buflen < 64) {
        return ERANGE;
    }

    int found = 0;
    char found_name[64] = {0};
    char found_pass[32] = {0};
    gid_t found_gid = 0;
    char found_members[256] = {0};

    // 1. Try reading /etc/group from The native userland rootfs
#ifndef DDNATV_HOST_TEST
    char path_buf[PATH_MAX];
    const char *grp_path = rewrite_path("/etc/group", path_buf, sizeof(path_buf));
    static int (*orig_open)(const char *, int, ...) = NULL;
    if (!orig_open) orig_open = (int (*)(const char *, int, ...))dlsym(RTLD_NEXT, "open");
    int fd = (orig_open && grp_path) ? orig_open(grp_path, O_RDONLY | O_CLOEXEC) : -1;
    if (fd >= 0) {
        char file_buf[8192];
        ssize_t n = read(fd, file_buf, sizeof(file_buf) - 1);
        close(fd);
        if (n > 0) {
            file_buf[n] = '\0';
            char *line = file_buf;
            while (*line && !found) {
                char *next_line = strchr(line, '\n');
                if (next_line) {
                    *next_line = '\0';
                }
                size_t llen = strlen(line);
                if (llen > 0 && line[llen - 1] == '\r') line[llen - 1] = '\0';

                // format: name:pass:gid:members
                char *p1 = strchr(line, ':');
                if (p1) {
                    *p1 = '\0';
                    char *p2 = strchr(p1 + 1, ':');
                    if (p2) {
                        *p2 = '\0';
                        char *p3 = strchr(p2 + 1, ':');
                        if (p3) {
                            *p3 = '\0';
                            const char *gname = line;
                            const char *gpass = p1 + 1;
                            gid_t ggid = (gid_t)strtoul(p2 + 1, NULL, 10);
                            const char *gmem = p3 + 1;

                            if (by_name && search_name && strcmp(gname, search_name) == 0) {
                                snprintf(found_name, sizeof(found_name), "%s", gname);
                                snprintf(found_pass, sizeof(found_pass), "%s", gpass);
                                found_gid = ggid;
                                snprintf(found_members, sizeof(found_members), "%s", gmem);
                                found = 1;
                            } else if (!by_name && ggid == search_gid) {
                                snprintf(found_name, sizeof(found_name), "%s", gname);
                                snprintf(found_pass, sizeof(found_pass), "%s", gpass);
                                found_gid = ggid;
                                snprintf(found_members, sizeof(found_members), "%s", gmem);
                                found = 1;
                            }
                        }
                    }
                }
                if (!next_line) break;
                line = next_line + 1;
            }
        }
    }
#endif

    // 2. Fallbacks for well-known groups if absent from file
    if (!found) {
        if (by_name) {
            if (!search_name) return ENOENT;
            if (strcmp(search_name, "root") == 0 || strcmp(search_name, "ddnatv") == 0) {
                snprintf(found_name, sizeof(found_name), "%s", search_name);
                snprintf(found_pass, sizeof(found_pass), "x");
                found_gid = 0;
                snprintf(found_members, sizeof(found_members), "%s", search_name);
                found = 1;
            } else if (strcmp(search_name, "_ssh") == 0) {
                snprintf(found_name, sizeof(found_name), "_ssh");
                snprintf(found_pass, sizeof(found_pass), "x");
                found_gid = 100;
                found = 1;
            } else if (strcmp(search_name, "ssh") == 0) {
                snprintf(found_name, sizeof(found_name), "ssh");
                snprintf(found_pass, sizeof(found_pass), "x");
                found_gid = 101;
                found = 1;
            } else if (strcmp(search_name, "nogroup") == 0 || strcmp(search_name, "nobody") == 0) {
                snprintf(found_name, sizeof(found_name), "%s", search_name);
                snprintf(found_pass, sizeof(found_pass), "x");
                found_gid = 65534;
                found = 1;
            }
        } else {
            if (search_gid == 0 || search_gid == real_gid()) {
                snprintf(found_name, sizeof(found_name), "root");
                snprintf(found_pass, sizeof(found_pass), "x");
                found_gid = search_gid;
                snprintf(found_members, sizeof(found_members), "root");
                found = 1;
            }
        }
    }

    if (!found) {
        return ENOENT;
    }

    // 3. Assemble struct group into caller's buf
    size_t mem_ptr_count = 1;
    char *m_copy = found_members;
    while (*m_copy) {
        if (*m_copy == ',') mem_ptr_count++;
        m_copy++;
    }
    if (found_members[0] != '\0') mem_ptr_count++;

    size_t mem_array_bytes = (mem_ptr_count + 1) * sizeof(char *);
    mem_array_bytes = (mem_array_bytes + sizeof(void *) - 1) & ~(sizeof(void *) - 1);

    size_t name_len = strlen(found_name) + 1;
    size_t pass_len = strlen(found_pass) + 1;
    size_t mem_str_len = strlen(found_members) + 1;

    if (mem_array_bytes + name_len + pass_len + mem_str_len > buflen) {
        return ERANGE;
    }

    char **mem_array = (char **)buf;
    char *str_cursor = buf + mem_array_bytes;

    grp->gr_name = str_cursor;
    memcpy(str_cursor, found_name, name_len);
    str_cursor += name_len;

    grp->gr_passwd = str_cursor;
    memcpy(str_cursor, found_pass, pass_len);
    str_cursor += pass_len;

    grp->gr_gid = found_gid;

    size_t m_idx = 0;
    if (found_members[0] != '\0') {
        char *m_tok = str_cursor;
        memcpy(str_cursor, found_members, mem_str_len);
        str_cursor += mem_str_len;

        char *curr = m_tok;
        while (curr && *curr) {
            char *comma = strchr(curr, ',');
            if (comma) {
                *comma = '\0';
            }
            if (*curr) {
                mem_array[m_idx++] = curr;
            }
            curr = comma ? comma + 1 : NULL;
        }
    }
    mem_array[m_idx] = NULL;
    grp->gr_mem = mem_array;

    return 0;
}

// Passwd lookup helper: reads $DDNATV_ROOT/etc/passwd or falls back to known entries
static int ddnatv_lookup_passwd(const char *search_name, uid_t search_uid, int by_name,
                                struct passwd *pwd, char *buf, size_t buflen) {
    if (!pwd || !buf || buflen < 128) {
        return ERANGE;
    }

    int found = 0;
    char found_name[64] = {0};
    char found_pass[32] = {0};
    uid_t found_uid = 0;
    gid_t found_gid = 0;
    char found_gecos[64] = {0};
    char found_dir[128] = {0};
    char found_shell[64] = {0};

    // 1. Try reading /etc/passwd from The native userland rootfs
#ifndef DDNATV_HOST_TEST
    char path_buf[PATH_MAX];
    const char *pwd_path = rewrite_path("/etc/passwd", path_buf, sizeof(path_buf));
    static int (*orig_open)(const char *, int, ...) = NULL;
    if (!orig_open) orig_open = (int (*)(const char *, int, ...))dlsym(RTLD_NEXT, "open");
    int fd = (orig_open && pwd_path) ? orig_open(pwd_path, O_RDONLY | O_CLOEXEC) : -1;
    if (fd >= 0) {
        char file_buf[8192];
        ssize_t n = read(fd, file_buf, sizeof(file_buf) - 1);
        close(fd);
        if (n > 0) {
            file_buf[n] = '\0';
            char *line = file_buf;
            while (*line && !found) {
                char *next_line = strchr(line, '\n');
                if (next_line) {
                    *next_line = '\0';
                }
                size_t llen = strlen(line);
                if (llen > 0 && line[llen - 1] == '\r') line[llen - 1] = '\0';

                // format: name:pass:uid:gid:gecos:dir:shell
                char *f[7] = {0};
                char *curr = line;
                int f_idx = 0;
                while (curr && f_idx < 7) {
                    f[f_idx++] = curr;
                    char *col = strchr(curr, ':');
                    if (col && f_idx < 7) {
                        *col = '\0';
                        curr = col + 1;
                    } else {
                        break;
                    }
                }
                if (f_idx >= 7) {
                    const char *uname = f[0];
                    const char *upass = f[1];
                    uid_t uuid = (uid_t)strtoul(f[2], NULL, 10);
                    gid_t ugid = (gid_t)strtoul(f[3], NULL, 10);
                    const char *ugecos = f[4];
                    const char *udir = f[5];
                    const char *ushell = f[6];

                    if (by_name && search_name && strcmp(uname, search_name) == 0) {
                        snprintf(found_name, sizeof(found_name), "%s", uname);
                        snprintf(found_pass, sizeof(found_pass), "%s", upass);
                        found_uid = uuid;
                        found_gid = ugid;
                        snprintf(found_gecos, sizeof(found_gecos), "%s", ugecos);
                        snprintf(found_dir, sizeof(found_dir), "%s", udir);
                        snprintf(found_shell, sizeof(found_shell), "%s", ushell);
                        found = 1;
                    } else if (!by_name && uuid == search_uid) {
                        snprintf(found_name, sizeof(found_name), "%s", uname);
                        snprintf(found_pass, sizeof(found_pass), "%s", upass);
                        found_uid = uuid;
                        found_gid = ugid;
                        snprintf(found_gecos, sizeof(found_gecos), "%s", ugecos);
                        snprintf(found_dir, sizeof(found_dir), "%s", udir);
                        snprintf(found_shell, sizeof(found_shell), "%s", ushell);
                        found = 1;
                    }
                }
                if (!next_line) break;
                line = next_line + 1;
            }
        }
    }
#endif

    // 2. Fallbacks for well-known users if absent from file
    if (!found) {
        if (by_name) {
            if (!search_name) return ENOENT;
            if (strcmp(search_name, "root") == 0) {
                snprintf(found_name, sizeof(found_name), "root");
                snprintf(found_pass, sizeof(found_pass), "x");
                found_uid = 0;
                found_gid = 0;
                snprintf(found_gecos, sizeof(found_gecos), "root");
                snprintf(found_dir, sizeof(found_dir), "/root");
                snprintf(found_shell, sizeof(found_shell), "/bin/bash");
                found = 1;
            } else if (strcmp(search_name, "ddnatv") == 0) {
                snprintf(found_name, sizeof(found_name), "ddnatv");
                snprintf(found_pass, sizeof(found_pass), "x");
                found_uid = 0;
                found_gid = 0;
                snprintf(found_gecos, sizeof(found_gecos), "The native userland");
                snprintf(found_dir, sizeof(found_dir), "/home/ddnatv");
                snprintf(found_shell, sizeof(found_shell), "/bin/bash");
                found = 1;
            } else if (strcmp(search_name, "_ssh") == 0) {
                snprintf(found_name, sizeof(found_name), "_ssh");
                snprintf(found_pass, sizeof(found_pass), "x");
                found_uid = 100;
                found_gid = 100;
                snprintf(found_gecos, sizeof(found_gecos), "OpenSSH daemon");
                snprintf(found_dir, sizeof(found_dir), "/run/sshd");
                snprintf(found_shell, sizeof(found_shell), "/usr/sbin/nologin");
                found = 1;
            } else if (strcmp(search_name, "sshd") == 0) {
                snprintf(found_name, sizeof(found_name), "sshd");
                snprintf(found_pass, sizeof(found_pass), "x");
                found_uid = 101;
                found_gid = 101;
                snprintf(found_gecos, sizeof(found_gecos), "Privilege-separated SSH");
                snprintf(found_dir, sizeof(found_dir), "/run/sshd");
                snprintf(found_shell, sizeof(found_shell), "/usr/sbin/nologin");
                found = 1;
            } else if (strcmp(search_name, "nobody") == 0) {
                snprintf(found_name, sizeof(found_name), "nobody");
                snprintf(found_pass, sizeof(found_pass), "x");
                found_uid = 65534;
                found_gid = 65534;
                snprintf(found_gecos, sizeof(found_gecos), "nobody");
                snprintf(found_dir, sizeof(found_dir), "/nonexistent");
                snprintf(found_shell, sizeof(found_shell), "/usr/sbin/nologin");
                found = 1;
            }
        } else {
            if (search_uid == 0 || search_uid == real_uid()) {
                snprintf(found_name, sizeof(found_name), "root");
                snprintf(found_pass, sizeof(found_pass), "x");
                found_uid = search_uid;
                found_gid = 0;
                snprintf(found_gecos, sizeof(found_gecos), "root");
                snprintf(found_dir, sizeof(found_dir), "/home");
                snprintf(found_shell, sizeof(found_shell), "/bin/bash");
                found = 1;
            }
        }
    }

    if (!found) {
        return ENOENT;
    }

    // 3. Assemble struct passwd into caller's buf
    size_t name_len = strlen(found_name) + 1;
    size_t pass_len = strlen(found_pass) + 1;
    size_t gecos_len = strlen(found_gecos) + 1;
    size_t dir_len = strlen(found_dir) + 1;
    size_t shell_len = strlen(found_shell) + 1;

    size_t total_str_bytes = name_len + pass_len + gecos_len + dir_len + shell_len;
    if (total_str_bytes > buflen) {
        return ERANGE;
    }

    char *str_cursor = buf;

    pwd->pw_name = str_cursor;
    memcpy(str_cursor, found_name, name_len);
    str_cursor += name_len;

    pwd->pw_passwd = str_cursor;
    memcpy(str_cursor, found_pass, pass_len);
    str_cursor += pass_len;

    pwd->pw_uid = found_uid;
    pwd->pw_gid = found_gid;

    pwd->pw_gecos = str_cursor;
    memcpy(str_cursor, found_gecos, gecos_len);
    str_cursor += gecos_len;

    pwd->pw_dir = str_cursor;
    memcpy(str_cursor, found_dir, dir_len);
    str_cursor += dir_len;

    pwd->pw_shell = str_cursor;
    memcpy(str_cursor, found_shell, shell_len);
    str_cursor += shell_len;

    return 0;
}

#ifndef DDNATV_HOST_TEST
// The shadow database (/etc/shadow) of the rootfs. libc reads the real /etc/shadow of the machine,
// which Android does not have: pam_unix's account check for root found no entry and answered
// "Authentication failure", so chfn, chsh and `adduser --gecos` (the installation of PostgreSQL)
// failed. The fields are "name:hash:lastchange:min:max:warn:inactive:expire:flag", numbers empty
// when unset (libc stores -1 then).
static int ddnatv_lookup_shadow(const char *name, struct spwd *sp, char *buf, size_t buflen) {
    if (!name || !sp || !buf) return EINVAL;
    char path_buf[PATH_MAX];
    const char *path = rewrite_path("/etc/shadow", path_buf, sizeof(path_buf));
    static int (*orig_open)(const char *, int, ...) = NULL;
    if (!orig_open) orig_open = (int (*)(const char *, int, ...))dlsym(RTLD_NEXT, "open");
    int fd = (orig_open && path) ? orig_open(path, O_RDONLY | O_CLOEXEC) : -1;
    if (fd < 0) return ENOENT;
    FILE *f = fdopen(fd, "r");
    if (!f) {
        close(fd);
        return ENOENT;
    }
    size_t name_len = strlen(name);
    char line[1024];
    int err = ENOENT;
    while (fgets(line, sizeof(line), f)) {
        if (strncmp(line, name, name_len) != 0 || line[name_len] != ':') continue;
        line[strcspn(line, "\r\n")] = '\0';
        char *fields[9] = {0};
        int n = 0;
        char *cursor = line;
        while (n < 9) {
            fields[n++] = cursor;
            char *colon = strchr(cursor, ':');
            if (!colon) break;
            *colon = '\0';
            cursor = colon + 1;
        }
        size_t need = strlen(fields[0]) + 1 + strlen(fields[1] ? fields[1] : "") + 1;
        if (n < 2 || need > buflen) {
            err = n < 2 ? ENOENT : ERANGE;
            break;
        }
        char *out = buf;
        sp->sp_namp = out;
        memcpy(out, fields[0], strlen(fields[0]) + 1);
        out += strlen(fields[0]) + 1;
        sp->sp_pwdp = out;
        memcpy(out, fields[1], strlen(fields[1]) + 1);
        long number[6];
        for (int i = 0; i < 6; i++) {
            const char *v = (2 + i < n) ? fields[2 + i] : NULL;
            number[i] = (v && v[0] != '\0') ? strtol(v, NULL, 10) : -1;
        }
        sp->sp_lstchg = number[0];
        sp->sp_min = number[1];
        sp->sp_max = number[2];
        sp->sp_warn = number[3];
        sp->sp_inact = number[4];
        sp->sp_expire = number[5];
        sp->sp_flag = (8 < n && fields[8][0] != '\0') ? strtoul(fields[8], NULL, 10) : (unsigned long)-1;
        err = 0;
        break;
    }
    fclose(f);
    return err;
}

int getspnam_r(const char *name, struct spwd *spbuf, char *buf, size_t buflen, struct spwd **result) {
    static int (*orig_getspnam_r)(const char *, struct spwd *, char *, size_t, struct spwd **) = NULL;
    if (!orig_getspnam_r) orig_getspnam_r = (int (*)(const char *, struct spwd *, char *, size_t, struct spwd **))dlsym(RTLD_NEXT, "getspnam_r");
    int ret = ENOENT;
    if (orig_getspnam_r) {
        ret = orig_getspnam_r(name, spbuf, buf, buflen, result);
        if ((ret == 0 && result && *result) || ret == ERANGE) return ret;
    }
    int err = ddnatv_lookup_shadow(name, spbuf, buf, buflen);
    if (err == 0) {
        if (result) *result = spbuf;
        return 0;
    }
    if (result) *result = NULL;
    return err == ENOENT ? (ret != 0 ? ret : ENOENT) : err;
}

struct spwd *getspnam(const char *name) {
    static struct spwd entry;
    static char entry_buf[1024];
    struct spwd *found = NULL;
    int err = getspnam_r(name, &entry, entry_buf, sizeof(entry_buf), &found);
    if (err != 0) {
        errno = err;
        return NULL;
    }
    return found;
}
#endif /* !DDNATV_HOST_TEST */

static struct group s_grp_storage;
static char s_grp_buf[2048];
static struct passwd s_pwd_storage;
static char s_pwd_buf[2048];

struct group *DDNATV_NSS_SYM(getgrgid)(gid_t gid) {
#ifndef DDNATV_HOST_TEST
    static struct group *(*orig_getgrgid)(gid_t) = NULL;
    if (!orig_getgrgid) orig_getgrgid = (struct group *(*)(gid_t))dlsym(RTLD_NEXT, "getgrgid");
    struct group *res = orig_getgrgid ? orig_getgrgid(gid) : NULL;
    if (res) return res;
#endif

    int err = ddnatv_lookup_group(NULL, gid, 0, &s_grp_storage, s_grp_buf, sizeof(s_grp_buf));
    if (err == 0) return &s_grp_storage;
    return NULL;
}

struct group *DDNATV_NSS_SYM(getgrnam)(const char *name) {
#ifndef DDNATV_HOST_TEST
    static struct group *(*orig_getgrnam)(const char *) = NULL;
    if (!orig_getgrnam) orig_getgrnam = (struct group *(*)(const char *))dlsym(RTLD_NEXT, "getgrnam");
    struct group *res = orig_getgrnam ? orig_getgrnam(name) : NULL;
    if (res) return res;
#endif

    int err = ddnatv_lookup_group(name, 0, 1, &s_grp_storage, s_grp_buf, sizeof(s_grp_buf));
    if (err == 0) return &s_grp_storage;
    return NULL;
}

int DDNATV_NSS_SYM(getgrgid_r)(gid_t gid, struct group *grp, char *buf, size_t buflen, struct group **result) {
#ifndef DDNATV_HOST_TEST
    static int (*orig_getgrgid_r)(gid_t, struct group *, char *, size_t, struct group **) = NULL;
    if (!orig_getgrgid_r) orig_getgrgid_r = (int (*)(gid_t, struct group *, char *, size_t, struct group **))dlsym(RTLD_NEXT, "getgrgid_r");
    int ret = orig_getgrgid_r ? orig_getgrgid_r(gid, grp, buf, buflen, result) : ENOENT;
#else
    int ret = g_test_orig_nss_r_ret;
    if (result) *result = (ret == 0 && g_test_orig_nss_r_found) ? grp : NULL;
#endif
    if (ret == 0 && result && *result != NULL) return 0;
    if (ret != 0 && ret != ENOENT) {
        if (result) *result = NULL;
        return ret;
    }

    int err = ddnatv_lookup_group(NULL, gid, 0, grp, buf, buflen);
    if (err == 0) {
        if (result) *result = grp;
        return 0;
    }
    if (result) *result = NULL;
    return (err == ERANGE) ? ERANGE : 0;
}

int DDNATV_NSS_SYM(getgrnam_r)(const char *name, struct group *grp, char *buf, size_t buflen, struct group **result) {
#ifndef DDNATV_HOST_TEST
    static int (*orig_getgrnam_r)(const char *, struct group *, char *, size_t, struct group **) = NULL;
    if (!orig_getgrnam_r) orig_getgrnam_r = (int (*)(const char *, struct group *, char *, size_t, struct group **))dlsym(RTLD_NEXT, "getgrnam_r");
    int ret = orig_getgrnam_r ? orig_getgrnam_r(name, grp, buf, buflen, result) : ENOENT;
#else
    int ret = g_test_orig_nss_r_ret;
    if (result) *result = (ret == 0 && g_test_orig_nss_r_found) ? grp : NULL;
#endif
    if (ret == 0 && result && *result != NULL) return 0;
    if (ret != 0 && ret != ENOENT) {
        if (result) *result = NULL;
        return ret;
    }

    int err = ddnatv_lookup_group(name, 0, 1, grp, buf, buflen);
    if (err == 0) {
        if (result) *result = grp;
        return 0;
    }
    if (result) *result = NULL;
    return (err == ERANGE) ? ERANGE : 0;
}

// sshd's privilege-separated user is root's ids: The native userland cannot drop privileges (setuid() and
// setgid() only pretend to), and sshd's unprivileged part checks after dropping them that it cannot
// take them back, which ended it ("permanently_set_uid: was able to restore old [e]gid"). Like apt's
// sandbox user (APT::Sandbox::User "root"), it then runs as the rest does.
static struct passwd *present_privsep_user_as_root(struct passwd *pwd) {
    if (pwd && pwd->pw_name && strcmp(pwd->pw_name, "sshd") == 0) {
        pwd->pw_uid = 0;
        pwd->pw_gid = 0;
    }
    return pwd;
}

struct passwd *DDNATV_NSS_SYM(getpwuid)(uid_t uid) {
#ifndef DDNATV_HOST_TEST
    static struct passwd *(*orig_getpwuid)(uid_t) = NULL;
    if (!orig_getpwuid) orig_getpwuid = (struct passwd *(*)(uid_t))dlsym(RTLD_NEXT, "getpwuid");
    struct passwd *res = orig_getpwuid ? orig_getpwuid(uid) : NULL;
    if (res) return res;
#endif

    int err = ddnatv_lookup_passwd(NULL, uid, 0, &s_pwd_storage, s_pwd_buf, sizeof(s_pwd_buf));
    if (err == 0) return &s_pwd_storage;
    return NULL;
}

struct passwd *DDNATV_NSS_SYM(getpwnam)(const char *name) {
#ifndef DDNATV_HOST_TEST
    static struct passwd *(*orig_getpwnam)(const char *) = NULL;
    if (!orig_getpwnam) orig_getpwnam = (struct passwd *(*)(const char *))dlsym(RTLD_NEXT, "getpwnam");
    struct passwd *res = orig_getpwnam ? orig_getpwnam(name) : NULL;
    if (res) return present_privsep_user_as_root(res);
#endif

    int err = ddnatv_lookup_passwd(name, 0, 1, &s_pwd_storage, s_pwd_buf, sizeof(s_pwd_buf));
    if (err == 0) return present_privsep_user_as_root(&s_pwd_storage);
    return NULL;
}

int DDNATV_NSS_SYM(getpwuid_r)(uid_t uid, struct passwd *pwd, char *buf, size_t buflen, struct passwd **result) {
#ifndef DDNATV_HOST_TEST
    static int (*orig_getpwuid_r)(uid_t, struct passwd *, char *, size_t, struct passwd **) = NULL;
    if (!orig_getpwuid_r) orig_getpwuid_r = (int (*)(uid_t, struct passwd *, char *, size_t, struct passwd **))dlsym(RTLD_NEXT, "getpwuid_r");
    int ret = orig_getpwuid_r ? orig_getpwuid_r(uid, pwd, buf, buflen, result) : ENOENT;
#else
    int ret = g_test_orig_nss_r_ret;
    if (result) *result = (ret == 0 && g_test_orig_nss_r_found) ? pwd : NULL;
#endif
    if (ret == 0 && result && *result != NULL) return 0;
    if (ret != 0 && ret != ENOENT) {
        if (result) *result = NULL;
        return ret;
    }

    int err = ddnatv_lookup_passwd(NULL, uid, 0, pwd, buf, buflen);
    if (err == 0) {
        if (result) *result = pwd;
        return 0;
    }
    if (result) *result = NULL;
    return (err == ERANGE) ? ERANGE : 0;
}

int DDNATV_NSS_SYM(getpwnam_r)(const char *name, struct passwd *pwd, char *buf, size_t buflen, struct passwd **result) {
#ifndef DDNATV_HOST_TEST
    static int (*orig_getpwnam_r)(const char *, struct passwd *, char *, size_t, struct passwd **) = NULL;
    if (!orig_getpwnam_r) orig_getpwnam_r = (int (*)(const char *, struct passwd *, char *, size_t, struct passwd **))dlsym(RTLD_NEXT, "getpwnam_r");
    int ret = orig_getpwnam_r ? orig_getpwnam_r(name, pwd, buf, buflen, result) : ENOENT;
#else
    int ret = g_test_orig_nss_r_ret;
    if (result) *result = (ret == 0 && g_test_orig_nss_r_found) ? pwd : NULL;
#endif
    if (ret == 0 && result && *result != NULL) {
        present_privsep_user_as_root(*result);
        return 0;
    }
    if (ret != 0 && ret != ENOENT) {
        if (result) *result = NULL;
        return ret;
    }

    int err = ddnatv_lookup_passwd(name, 0, 1, pwd, buf, buflen);
    if (err == 0) {
        present_privsep_user_as_root(pwd);
        if (result) *result = pwd;
        return 0;
    }
    if (result) *result = NULL;
    return (err == ERANGE) ? ERANGE : 0;
}
