// Part of libddnatv-hook.so, included by ddnatv-hook.c (one translation unit, so the
// parts share their static functions and are built with the same command as before).
//
// Start-up (DDNATV_ROOT, certificate and time zone defaults), /proc/self/exe, rewrite_path() and
// the short names of Unix sockets.

#ifndef DDNATV_HOST_TEST

static char g_real_exe[PATH_MAX] = {0};
// DDNATV_PRELOAD: guest paths of libraries every program loads before the hook (what
// /etc/ld.so.preload does for programs run by the rootfs's own ld.so), and the same as host paths.
static char *g_preload_guest = NULL;
static char *g_preload_host = NULL;
static int g_initialized = 0;

static void init_ddnatv_hook(void) {
    if (g_initialized) return;
    const char *root = getenv("DDNATV_ROOT");
    if (root && strlen(root) > 0) {
        size_t len = strlen(root);
        if (len > sizeof(g_ddnatv_root) - 1) len = sizeof(g_ddnatv_root) - 1;
        if (root[len - 1] == '/') len--;
        ddnatv_set_root(root, len);
    } else {
        Dl_info info;
        if (dladdr((void *)init_ddnatv_hook, &info) && info.dli_fname) {
            const char *p = strstr(info.dli_fname, "/usr/lib/libddnatv-hook");
            if (p && p > info.dli_fname) {
                size_t len = p - info.dli_fname;
                if (len < sizeof(g_ddnatv_root)) {
                    ddnatv_set_root(info.dli_fname, len);
                }
            }
        }
    }
    ddnatv_load_binds(getenv("DDNATV_BINDS"));
    const char *hostname = getenv("DDNATV_HOSTNAME");
    if (hostname && hostname[0] && strlen(hostname) < sizeof(g_hostname)) ddnatv_copy_cstr(g_hostname, sizeof(g_hostname), hostname);
    const char *preload = getenv("DDNATV_PRELOAD");
    if (preload && preload[0] && g_ddnatv_root_len > 0) {
        size_t libs = 1;
        for (const char *p = preload; *p; p++) libs += *p == ':';
        size_t cap = libs * (PATH_MAX + 1);
        char *host = malloc(cap);
        if (host) {
            size_t used = 0;
            host[0] = '\0';
            char *copy = strdup(preload);
            char *save = NULL;
            for (char *lib = copy ? strtok_r(copy, ":", &save) : NULL; lib; lib = strtok_r(NULL, ":", &save)) {
                if (lib[0] != '/') continue;
                char mapped_buf[PATH_MAX];
                const char *mapped = rewrite_path_in_root(lib, mapped_buf, sizeof(mapped_buf));
                size_t mapped_len = strlen(mapped);
                if (used + mapped_len + 2 > cap) break;
                if (used) host[used++] = ':';
                used = ddnatv_append(host, cap, used, mapped, mapped_len);
            }
            free(copy);
            g_preload_host = host;
            g_preload_guest = strdup(preload);
        }
    }
    const char *curr_real_exe = getenv("DDNATV_REAL_EXE");
    if (curr_real_exe && curr_real_exe[0] != '\0') {
        ddnatv_copy_cstr(g_real_exe, sizeof(g_real_exe), curr_real_exe);
    }
    if (g_ddnatv_root[0] != '\0') {
        const char *curr_tzdir = getenv("TZDIR");
        if (!curr_tzdir || curr_tzdir[0] == '\0') {
            char tzdir_buf[PATH_MAX];
            snprintf(tzdir_buf, sizeof(tzdir_buf), "%s/usr/share/zoneinfo", g_ddnatv_root);
            setenv("TZDIR", tzdir_buf, 0);
        }

        const char *curr_cert = getenv("SSL_CERT_FILE");
        if (!curr_cert || curr_cert[0] == '\0' || strcmp(curr_cert, "/etc/ssl/certs/ca-certificates.crt") == 0) {
            char cert_buf[PATH_MAX];
            snprintf(cert_buf, sizeof(cert_buf), "%s/etc/ssl/certs/ca-certificates.crt", g_ddnatv_root);
            setenv("SSL_CERT_FILE", cert_buf, 1);
        }

        const char *curr_cert_dir = getenv("SSL_CERT_DIR");
        if (!curr_cert_dir || curr_cert_dir[0] == '\0' || strcmp(curr_cert_dir, "/etc/ssl/certs") == 0) {
            char cert_dir_buf[PATH_MAX];
            snprintf(cert_dir_buf, sizeof(cert_dir_buf), "%s/etc/ssl/certs:/system/etc/security/cacerts", g_ddnatv_root);
            setenv("SSL_CERT_DIR", cert_dir_buf, 1);
        }

        const char *curr_curl = getenv("CURL_CA_BUNDLE");
        if (!curr_curl || curr_curl[0] == '\0' || strcmp(curr_curl, "/etc/ssl/certs/ca-certificates.crt") == 0) {
            char curl_buf[PATH_MAX];
            snprintf(curl_buf, sizeof(curl_buf), "%s/etc/ssl/certs/ca-certificates.crt", g_ddnatv_root);
            setenv("CURL_CA_BUNDLE", curl_buf, 1);
        }

        const char *curr_godebug = getenv("GODEBUG");
        if (!curr_godebug || curr_godebug[0] == '\0') {
            setenv("GODEBUG", "netdns=cgo", 0);
        } else if (strstr(curr_godebug, "netdns") == NULL) {
            char godebug_buf[512];
            snprintf(godebug_buf, sizeof(godebug_buf), "%s,netdns=cgo", curr_godebug);
            setenv("GODEBUG", godebug_buf, 1);
        }
    }
    g_initialized = 1;
}

// Before the program runs: init_ddnatv_hook() sets variables, and setenv() may move the block the
// environment is kept in. A program that had changed its environment (putenv, setenv) and passed it
// to execv() before its first call into the hook handed the exec code a block that was freed by the
// time it was read.
__attribute__((constructor(103))) static void init_ddnatv_hook_early(void) {
    init_ddnatv_hook();
}

void tzset(void) {
    static void (*orig_tzset)(void) = NULL;
    if (!orig_tzset) orig_tzset = (void (*)(void))dlsym(RTLD_NEXT, "tzset");
    init_ddnatv_hook();
    if (g_ddnatv_root[0] != '\0') {
        const char *curr_tzdir = getenv("TZDIR");
        if (!curr_tzdir || curr_tzdir[0] == '\0') {
            char tzdir_buf[PATH_MAX];
            snprintf(tzdir_buf, sizeof(tzdir_buf), "%s/usr/share/zoneinfo", g_ddnatv_root);
            setenv("TZDIR", tzdir_buf, 0);
        }
    }
    if (orig_tzset) orig_tzset();
}

static int is_proc_self_exe(const char *path) {
    if (!path) return 0;
    if (strcmp(path, "/proc/self/exe") == 0 ||
        strcmp(path, "/proc/thread-self/exe") == 0) {
        return 1;
    }
    if (strncmp(path, "/proc/", 6) == 0) {
        const char *p = path + 6;
        while (*p >= '0' && *p <= '9') p++;
        if (strcmp(p, "/exe") == 0) {
            pid_t pid = (pid_t)atoi(path + 6);
            if (pid == getpid()) {
                return 1;
            }
        }
    }
    return 0;
}

static ssize_t handle_proc_self_exe(char *buf, size_t bufsiz) {
    if (!buf || bufsiz == 0) {
        errno = EINVAL;
        return -1;
    }
    init_ddnatv_hook();
    if (g_real_exe[0] == '\0') {
        int fd = open("/proc/self/cmdline", O_RDONLY);
        if (fd >= 0) {
            char cmdline[8192];
            ssize_t n = read(fd, cmdline, sizeof(cmdline) - 1);
            close(fd);
            if (n > 0) {
                cmdline[n] = '\0';
                char *args[64] = {0};
                int ac = 0;
                char *p = cmdline;
                while (p < cmdline + n && ac < 64) {
                    args[ac++] = p;
                    p += strlen(p) + 1;
                }
                for (int i = 0; i < ac; i++) {
                    if (strcmp(args[i], "--argv0") == 0 && i + 2 < ac) {
                        strncpy(g_real_exe, args[i + 2], sizeof(g_real_exe) - 1);
                        g_real_exe[sizeof(g_real_exe) - 1] = '\0';
                        break;
                    }
                }
                if (g_real_exe[0] == '\0' && ac > 0 && args[0] && args[0][0] != '\0') {
                    strncpy(g_real_exe, args[0], sizeof(g_real_exe) - 1);
                    g_real_exe[sizeof(g_real_exe) - 1] = '\0';
                }
            }
        }
    }

    if (g_real_exe[0] != '\0') {
        char stripped[PATH_MAX];
        const char *out_path = g_real_exe;
        if (g_ddnatv_root[0] != '\0') {
            size_t rlen = g_ddnatv_root_len;
            if (strncmp(g_real_exe, g_ddnatv_root, rlen) == 0 &&
                (g_real_exe[rlen] == '/' || g_real_exe[rlen] == '\0')) {
                out_path = g_real_exe + rlen;
                if (out_path[0] == '\0') out_path = "/";
            }
        }
        strncpy(stripped, out_path, sizeof(stripped) - 1);
        stripped[sizeof(stripped) - 1] = '\0';

        size_t len = strlen(stripped);
        size_t copy_len = (len < bufsiz) ? len : bufsiz;
        memcpy(buf, stripped, copy_len);
        return (ssize_t)copy_len;
    }
    return -1;
}

static const char *rewrite_path(const char *path, char *buffer, size_t bufsize) {
    if (!path) return NULL;
    // Relative paths must NEVER be rewritten to rootfs root
    if (path[0] != '/') return path;
    init_ddnatv_hook();
    return rewrite_path_in_root(path, buffer, bufsize);
}

// Whether the rootfs keeps bin (pair DDNATV_USRMERGE_BIN) or sbin as a symlink into /usr, as
// Ubuntu 24.04 does. Checked once per process with one readlink().
static int rootfs_pair_is_merged(int pair) {
    static int known[3] = { 0, -1, -1 };
    if (known[pair] >= 0) return known[pair];
    static ssize_t (*orig_readlink)(const char *, char *, size_t) = NULL;
    if (!orig_readlink) orig_readlink = (ssize_t (*)(const char *, char *, size_t))dlsym(RTLD_NEXT, "readlink");
    const char *dir = (pair == DDNATV_USRMERGE_BIN) ? "/bin" : "/sbin";
    const char *usr_dir = (pair == DDNATV_USRMERGE_BIN) ? "usr/bin" : "usr/sbin";
    char path[PATH_MAX];
    char link[16];
    size_t n = ddnatv_append(path, sizeof(path), 0, g_ddnatv_root, g_ddnatv_root_len);
    ddnatv_append(path, sizeof(path), n, dir, strlen(dir));
    int saved_errno = errno;
    ssize_t len = orig_readlink ? orig_readlink(path, link, sizeof(link) - 1) : -1;
    errno = saved_errno;
    int merged = 0;
    if (len > 0) {
        link[len] = '\0';
        size_t ulen = strlen(usr_dir);
        merged = strncmp(link, usr_dir, ulen) == 0 && (link[ulen] == '\0' || (link[ulen] == '/' && link[ulen + 1] == '\0'));
    }
    known[pair] = merged;
    return merged;
}

// The other spelling of a missing rootfs path under bin/sbin, or NULL when there is none or both
// spellings reach the same directory (the retry could only fail again).
static const char *usrmerge_retry_path(const char *target, char *alt, size_t altsz) {
    const char *sub = get_rootfs_subpath(target);
    if (!sub) return NULL;
    int pair = usrmerge_alt_path(sub, alt, altsz);
    if (pair == 0 || rootfs_pair_is_merged(pair)) return NULL;
    return alt;
}

static const char *rewrite_unix_socket_path(const char *sun_path, char *out_buf, size_t out_size) {
    if (!sun_path || out_size == 0) return sun_path;
    // Abstract sockets start with null byte (\0); leave untouched
    if (sun_path[0] == '\0') {
        return sun_path;
    }

    char full[PATH_MAX];
    const char *rw = (sun_path[0] == '/') ? rewrite_path(sun_path, full, sizeof(full)) : sun_path;
    size_t rw_len = strlen(rw);

    // If rewritten path fits safely in sockaddr_un.sun_path (usually 108 bytes)
    if (rw_len < out_size) {
        strncpy(out_buf, rw, out_size - 1);
        out_buf[out_size - 1] = '\0';
        return out_buf;
    }

    // Path exceeds sockaddr_un 108-byte limit!
    // Deterministically hash the path into /tmp to guarantee it fits safely (< 80 bytes)
    // while remaining 100% deterministic between server and client.
    unsigned long long hash = 14695981039346656037ULL;
    for (const char *p = rw; *p; p++) {
        hash ^= (unsigned char)(*p);
        hash *= 1099511628211ULL;
    }

    init_ddnatv_hook();
    if (g_ddnatv_root[0] != '\0') {
        snprintf(out_buf, out_size, "%s/tmp/.ctx_sock_%016llx", g_ddnatv_root, hash);
    } else {
        snprintf(out_buf, out_size, "/tmp/.ctx_sock_%016llx", hash);
    }
    out_buf[out_size - 1] = '\0';
    return out_buf;
}

static void unlink_mapped_unix_socket(const char *target) {
    if (!target) return;
    size_t t_len = strlen(target);
    if (t_len >= 107) {
        unsigned long long hash = 14695981039346656037ULL;
        for (const char *p = target; *p; p++) {
            hash ^= (unsigned char)(*p);
            hash *= 1099511628211ULL;
        }
        char short_path[PATH_MAX];
        init_ddnatv_hook();
        if (g_ddnatv_root[0] != '\0') {
            snprintf(short_path, sizeof(short_path), "%s/tmp/.ctx_sock_%016llx", g_ddnatv_root, hash);
        } else {
            snprintf(short_path, sizeof(short_path), "/tmp/.ctx_sock_%016llx", hash);
        }
        static int (*orig_unlink)(const char *) = NULL;
        if (!orig_unlink) orig_unlink = (int (*)(const char *))dlsym(RTLD_NEXT, "unlink");
        if (orig_unlink) orig_unlink(short_path);
    }
}

#endif /* !DDNATV_HOST_TEST */
