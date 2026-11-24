// Part of libddnatv-hook.so, included by ddnatv-hook.c (one translation unit, so the
// parts share their static functions and are built with the same command as before).
//
// Start-up (DDNATV_ROOT, certificate and time zone defaults), /proc/self/exe, rewrite_path() and
// the short names of Unix sockets.

#ifndef DDNATV_HOST_TEST

static char g_real_exe[PATH_MAX] = {0};
// DDNATV_EXEC: the app's libddexec.so. A targetSdk 29+ app may not execve() its data files; this
// executable maps one and starts it as the kernel would ("libddexec.so <file> <argv0> [args...]").
static char g_exec_loader[PATH_MAX] = {0};
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
    const char *exec_loader = getenv("DDNATV_EXEC");
    if (exec_loader && exec_loader[0] == '/' && strlen(exec_loader) < sizeof(g_exec_loader))
        ddnatv_copy_cstr(g_exec_loader, sizeof(g_exec_loader), exec_loader);
    const char *hostname = getenv("DDNATV_HOSTNAME");
    if (hostname && hostname[0] && strlen(hostname) < sizeof(g_hostname)) ddnatv_copy_cstr(g_hostname, sizeof(g_hostname), hostname);
    const char *link_root = getenv("DDNATV_LINK_ROOT");
    if (link_root && link_root[0] == '/' && strlen(link_root) < sizeof(g_link_root)) {
        g_link_root_len = ddnatv_kernel_path(link_root, strlen(link_root), g_link_root, sizeof(g_link_root));
        while (g_link_root_len > 1 && g_link_root[g_link_root_len - 1] == '/') g_link_root[--g_link_root_len] = 0;
    }
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

        const char *curr_locpath = getenv("LOCPATH");
        if (!curr_locpath || curr_locpath[0] == '\0') {
            char locpath_buf[PATH_MAX];
            snprintf(locpath_buf, sizeof(locpath_buf), "%s/usr/lib/locale", g_ddnatv_root);
            setenv("LOCPATH", locpath_buf, 1);
        }

        if (!getenv("GCONV_PATH")) {
            char gconv_buf[PATH_MAX];
            snprintf(gconv_buf, sizeof(gconv_buf), "%s/usr/lib/aarch64-linux-gnu/gconv", g_ddnatv_root);
            setenv("GCONV_PATH", gconv_buf, 0);
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
static void compact_loader_args(int argc, char **argv);

__attribute__((constructor(103))) static void init_ddnatv_hook_early(int argc, char **argv) {
    init_ddnatv_hook();
    compact_loader_args(argc, argv);
    // The kernel names the process after what it executed, the rootfs ld.so: process monitors, and
    // the app's own checks of /proc/<pid>/comm (steam, wineserver), saw only "ld-linux-aarch64.so.1"
    const char *name = program_invocation_short_name;
    if (g_ddnatv_root[0] != '\0' && name && name[0] && strncmp(name, "ld-linux", 8) != 0) {
        prctl(PR_SET_NAME, (unsigned long)name, 0, 0, 0);
    }
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

static void exe_from_cmdline(const char *cmdline_path, char *out, size_t outsz) {
    int fd = open(cmdline_path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return;
    char cmdline[8192];
    ssize_t n = read(fd, cmdline, sizeof(cmdline) - 1);
    close(fd);
    if (n <= 0) return;
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
            ddnatv_copy_cstr(out, outsz, args[i + 2]);
            return;
        }
    }
    if (ac > 0 && args[0] && args[0][0] != '\0') ddnatv_copy_cstr(out, outsz, args[0]);
}

static ssize_t copy_guest_exe(const char *real_exe, char *buf, size_t bufsiz) {
    char guest[PATH_MAX];
    char back[PATH_MAX];
    const char *out_path = guest_path_of_host_dir(real_exe, guest, sizeof(guest));
    if (!out_path || strcmp(rewrite_path_in_root(out_path, back, sizeof(back)), real_exe) != 0) out_path = real_exe;
    size_t len = strlen(out_path);
    size_t copy_len = (len < bufsiz) ? len : bufsiz;
    memcpy(buf, out_path, copy_len);
    return (ssize_t)copy_len;
}

static ssize_t handle_proc_self_exe(char *buf, size_t bufsiz) {
    if (!buf || bufsiz == 0) {
        errno = EINVAL;
        return -1;
    }
    init_ddnatv_hook();
    if (g_real_exe[0] == '\0') exe_from_cmdline("/proc/self/cmdline", g_real_exe, sizeof(g_real_exe));
    if (g_real_exe[0] != '\0') return copy_guest_exe(g_real_exe, buf, bufsiz);
    return -1;
}

static int is_ld_linux(const char *path);

static ssize_t (*real_readlinkat_fn(void))(int, const char *, char *, size_t) {
    static ssize_t (*fn)(int, const char *, char *, size_t) = NULL;
    if (!fn) fn = (ssize_t (*)(int, const char *, char *, size_t))dlsym(RTLD_NEXT, "readlinkat");
    return fn;
}

static char *read_proc_file(const char *path, size_t *len, int *whole) {
    *len = 0;
    *whole = 0;
    int fd = (int)syscall(SYS_openat, AT_FDCWD, path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return NULL;
    size_t cap = 65536, used = 0;
    char *text = malloc(cap + 1);
    ssize_t got = 0;
    while (text && (got = (ssize_t)syscall(SYS_read, fd, text + used, cap - used)) > 0) {
        used += (size_t)got;
        if (used == cap) {
            char *bigger = cap < (8u << 20) ? realloc(text, cap * 2 + 1) : NULL;
            if (!bigger) break;
            text = bigger;
            cap *= 2;
        }
    }
    syscall(SYS_close, fd);
    if (!text) return NULL;
    text[used] = '\0';
    *len = used;
    *whole = got == 0;
    return text;
}

static int proc_env_value(pid_t pid, const char *key, char *out, size_t outsz) {
    char path[64];
    snprintf(path, sizeof(path), "/proc/%d/environ", (int)pid);
    size_t used, keylen = strlen(key);
    int whole;
    char *env = read_proc_file(path, &used, &whole);
    int found = 0;
    if (env) {
        for (char *e = env; e < env + used; e += strlen(e) + 1) {
            if (strncmp(e, key, keylen) == 0 && e[keylen] == '=' && e[keylen + 1] != '\0') {
                ddnatv_copy_cstr(out, outsz, e + keylen + 1);
                found = 1;
                break;
            }
        }
        free(env);
    }
    return found;
}

static int is_loader_option(const char *arg) {
    return strcmp(arg, "--preload") == 0 || strcmp(arg, "--library-path") == 0 || strcmp(arg, "--argv0") == 0;
}

static int is_exec_loader(const char *path) {
    const char *base = strrchr(path, '/');
    return strcmp(base ? base + 1 : path, "libddexec.so") == 0;
}

static size_t next_arg(const char *text, size_t pos) {
    return pos + strlen(text + pos) + 1;
}

static uintptr_t *execfn_entry(int argc, char **argv) {
    if (argv[argc]) return NULL;
    char **env = argv + argc + 1;
    while (*env) env++;
    for (uintptr_t *aux = (uintptr_t *)(env + 1); aux[0] != AT_NULL; aux += 2) {
        if (aux[0] == AT_EXECFN) return aux + 1;
    }
    return NULL;
}

static void compact_loader_args(int argc, char **argv) {
    if (g_ddnatv_root[0] == '\0' || argc < 1 || !argv || !argv[0]) return;
    size_t len;
    int whole;
    char *text = read_proc_file("/proc/self/cmdline", &len, &whole);
    size_t *at = text && whole && len > 0 && text[len - 1] == '\0' ? malloc(sizeof(size_t) * (size_t)argc) : NULL;
    size_t pos = 0, program = 0;
    if (at && is_exec_loader(text)) {
        pos = next_arg(text, pos);
        if (pos < len) pos = next_arg(text, pos);
    }
    int ok = at && pos < len && is_ld_linux(text + pos), named = 0;
    if (ok) pos = next_arg(text, pos);
    while (ok && pos < len && is_loader_option(text + pos)) {
        size_t value = next_arg(text, pos);
        ok = value < len;
        if (ok && strcmp(text + pos, "--argv0") == 0) {
            at[0] = value;
            named = 1;
        }
        if (ok) pos = next_arg(text, value);
    }
    int count = 0;
    if (ok && named && pos < len) {
        program = pos;
        pos = next_arg(text, pos);
        for (count = 1; pos < len && count < argc; count++) {
            at[count] = pos;
            pos = next_arg(text, pos);
        }
    }
    char *base = count == argc && pos == len ? argv[0] - at[0] : NULL;
    for (int i = 1; base && i < argc; i++) {
        if (argv[i] != base + at[i]) base = NULL;
    }
    if (base && memcmp(base, text, len) != 0) base = NULL;
    uintptr_t *execfn = base ? execfn_entry(argc, argv) : NULL;
    char *execfn_text = NULL;
    if (execfn && *execfn >= (uintptr_t)base && *execfn < (uintptr_t)base + len) {
        execfn_text = strdup(text + (*execfn - (uintptr_t)base));
        if (!execfn_text) base = NULL;
    }
    if (base) {
        char *old_name = argv[0];
        size_t name_len = strlen(old_name);
        char *name = program_invocation_name, *short_name = program_invocation_short_name;
        size_t used = 0;
        for (int i = 0; i < argc; i++) {
            size_t n = strlen(text + at[i]) + 1;
            memcpy(base + used, text + at[i], n);
            argv[i] = base + used;
            used += n;
        }
        memset(base + used, 0, len - used);
        if (name >= old_name && name <= old_name + name_len) program_invocation_name = argv[0] + (name - old_name);
        if (short_name >= old_name && short_name <= old_name + name_len) {
            program_invocation_short_name = argv[0] + (short_name - old_name);
        }
        if (g_real_exe[0] == '\0') ddnatv_copy_cstr(g_real_exe, sizeof(g_real_exe), text + program);
        if (execfn_text) *execfn = (uintptr_t)execfn_text;
    }
    free(at);
    free(text);
}

// Overlay filesystems report a different device to stat() than in /proc/<pid>/maps; the path, as the
// kernel resolved it, then stands in for the device.
static int proc_maps_file(pid_t pid, dev_t dev, ino_t ino, const char *resolved) {
    char path[64];
    snprintf(path, sizeof(path), "/proc/%d/maps", (int)pid);
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return 0;
    char chunk[4096], line[1024];
    size_t len = 0;
    ssize_t got;
    int found = 0;
    while (!found && (got = read(fd, chunk, sizeof(chunk))) > 0) {
        for (ssize_t i = 0; i < got && !found; i++) {
            if (chunk[i] != '\n') {
                if (len < sizeof(line) - 1) line[len++] = chunk[i];
                continue;
            }
            line[len] = '\0';
            len = 0;
            unsigned long long offset, inode;
            unsigned int major_nr, minor_nr;
            int name_at = 0;
            if (sscanf(line, "%*s %*s %llx %x:%x %llu %n", &offset, &major_nr, &minor_nr, &inode, &name_at) == 4 &&
                offset == 0 && inode == (unsigned long long)ino &&
                (makedev(major_nr, minor_nr) == dev || (resolved && name_at > 0 && strcmp(line + name_at, resolved) == 0))) {
                found = 1;
            }
        }
    }
    close(fd);
    return found;
}

// The kernel's /proc/<pid>/exe of every other guest program is the loader that started it. KWin
// grants privileged Wayland interfaces by matching that path to a desktop file's Exec, so it is the
// program the loader runs, as for the calling process. A process can set its environment and
// arguments, so the program they name counts only if that process has the file mapped from its start.
static ssize_t handle_proc_other_exe(const char *path, char *buf, size_t bufsiz) {
    if (!path || !buf || bufsiz == 0 || strncmp(path, "/proc/", 6) != 0) return -1;
    const char *p = path + 6;
    if (*p < '1' || *p > '9') return -1;
    while (*p >= '0' && *p <= '9') p++;
    if (strcmp(p, "/exe") != 0) return -1;
    pid_t pid = (pid_t)atoi(path + 6);
    if (pid == getpid()) return -1;
    init_ddnatv_hook();
    if (g_ddnatv_root[0] == '\0') return -1;
    ssize_t (*real_readlinkat)(int, const char *, char *, size_t) = real_readlinkat_fn();
    static int (*real_fstatat)(int, const char *, struct stat *, int) = NULL;
    if (!real_fstatat) real_fstatat = (int (*)(int, const char *, struct stat *, int))dlsym(RTLD_NEXT, "fstatat");
    if (!real_readlinkat || !real_fstatat) return -1;
    char loader[PATH_MAX];
    ssize_t n = real_readlinkat(AT_FDCWD, path, loader, sizeof(loader) - 1);
    if (n <= 0) return -1;
    loader[n] = '\0';
    if (!is_exec_loader(loader) && !is_ld_linux(loader)) return -1;

    char real_exe[PATH_MAX] = "";
    if (!proc_env_value(pid, "DDNATV_REAL_EXE", real_exe, sizeof(real_exe))) {
        char cmdline[64];
        snprintf(cmdline, sizeof(cmdline), "/proc/%d/cmdline", (int)pid);
        exe_from_cmdline(cmdline, real_exe, sizeof(real_exe));
    }
    static char *(*real_realpath)(const char *, char *) = NULL;
    if (!real_realpath) real_realpath = (char *(*)(const char *, char *))dlsym(RTLD_NEXT, "realpath");
    struct stat st;
    char resolved[PATH_MAX];
    if (real_exe[0] != '/' || real_fstatat(AT_FDCWD, real_exe, &st, 0) != 0) return -1;
    if (!real_realpath || !real_realpath(real_exe, resolved)) resolved[0] = '\0';
    if (!proc_maps_file(pid, st.st_dev, st.st_ino, resolved[0] ? resolved : NULL)) return -1;
    return copy_guest_exe(real_exe, buf, bufsiz);
}

// readlinkat(<descriptor for /proc/<pid>>, "exe"), as the absolute path would be answered.
static ssize_t handle_proc_exe_at(int dirfd, const char *pathname, char *buf, size_t bufsiz) {
    if (!pathname || pathname[0] == '/' || dirfd == AT_FDCWD || strcmp(pathname, "exe") != 0) return -1;
    ssize_t (*real_readlinkat)(int, const char *, char *, size_t) = real_readlinkat_fn();
    if (!real_readlinkat) return -1;
    char link[64], dir[PATH_MAX], full[PATH_MAX];
    snprintf(link, sizeof(link), "/proc/self/fd/%d", dirfd);
    ssize_t n = real_readlinkat(AT_FDCWD, link, dir, sizeof(dir) - 1);
    if (n <= 0) return -1;
    dir[n] = '\0';
    if (strncmp(dir, "/proc/", 6) != 0 || snprintf(full, sizeof(full), "%s/exe", dir) >= (int)sizeof(full)) return -1;
    if (is_proc_self_exe(full)) return handle_proc_self_exe(buf, bufsiz);
    return handle_proc_other_exe(full, buf, bufsiz);
}

static const char *rewrite_path(const char *path, char *buffer, size_t bufsize) {
    if (!path) return NULL;
    // Relative paths must NEVER be rewritten to rootfs root
    init_ddnatv_hook();
    if (path[0] != '/') {
        const char *walked = map_at(AT_FDCWD, path, buffer, bufsize);
        return walked ? walked : path;
    }
    return rewrite_path_in_root(path, buffer, bufsize);
}

// rewrite_path() for calls that follow a symbolic link in the last component. In a mount whose
// source is elsewhere the kernel would follow the link's absolute guest target on Android's own
// tree (a Flatpak app's /app/bin/PINBALL.DAT -> /app/extra/Pinball/PINBALL.DAT), so the link is
// followed in the guest's view first. The rootfs's own links were made relative already.
static const char *rewrite_path_follow(const char *path, char *buffer, size_t bufsize) {
    if (!path) return NULL;
    if (path[0] != '/') return rewrite_path(path, buffer, bufsize);
    init_ddnatv_hook();
    int moved = 0;
    for (int i = 0; i < g_nbinds; i++) {
        const struct ddnatv_bind *b = &g_binds[i];
        if (path[1] == b->dst[1] && is_path_prefix(path, b->dst, b->dst_len)) { moved = !b->identity; break; }
    }
    char from_source[PATH_MAX];
    if (!moved) {
        // or a mount's real place, as /proc/self/exe and getcwd() give it (a Flatpak app's checkout)
        const struct ddnatv_bind *best = NULL;
        for (int i = 0; i < g_nbinds; i++) {
            const struct ddnatv_bind *b = &g_binds[i];
            if (b->identity || !is_path_prefix(path, b->src, b->src_len)) continue;
            if (!best || b->src_len > best->src_len) best = b;
        }
        if (!best) {
            const char *host = rewrite_path_in_root(path, buffer, bufsize);
#if defined(__aarch64__)
            // A link in the rootfs with an absolute guest target that the hook did not write (one made
            // under proot, or copied from Proton's template prefix, as Wine's "z:" -> "/") is followed
            // by the kernel on Android's own tree: Wine found no drive for any path and gave games
            // \\?\unix\ names, which an NW.js game could not load its DLLs from.
            if (is_android_host_path(path)) return host;
            char target[PATH_MAX];
            long n = raw_readlinkat(AT_FDCWD, host, target, sizeof(target) - 1);
            if (n <= 0 || target[0] != '/') return host;
            target[n] = '\0';
            if (is_android_host_path(target) ||
                (g_ddnatv_root_len && strncmp(target, g_ddnatv_root, g_ddnatv_root_len) == 0)) return host;
            char guest_path[PATH_MAX];
            ddnatv_copy_cstr(guest_path, sizeof(guest_path), path);
            char guest_target[PATH_MAX];
            const char *followed = follow_guest_link(guest_path, guest_target, sizeof(guest_target));
            char mapped[PATH_MAX];
            ddnatv_copy_cstr(buffer, bufsize, rewrite_path_in_root(followed, mapped, sizeof(mapped)));
            return buffer;
#else
            return host;
#endif
        }
        size_t n = ddnatv_append(from_source, sizeof(from_source), 0, best->dst, best->dst_len);
        ddnatv_append(from_source, sizeof(from_source), n, path + best->src_len, strlen(path + best->src_len));
        path = from_source;
    }
    char guest[PATH_MAX];
    const char *followed = follow_guest_link(path, guest, sizeof(guest));
    char mapped[PATH_MAX];
    const char *host = rewrite_path_in_root(followed, mapped, sizeof(mapped));
    // the result may be `followed` or `path` itself, which can live here
    ddnatv_copy_cstr(buffer, bufsize, host);
    return buffer;
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
