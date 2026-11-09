// Part of libddnatv-hook.so, included by ddnatv-hook.c (one translation unit, so the
// parts share their static functions and are built with the same command as before).
//
// Path translation: Ubuntu paths (/usr, /etc, ...) mapped into the rootfs, Android's own mounts
// left alone, and the library directories of the rootfs.

// ---- Path translation -------------------------------------------------------------------
// Pure string functions (no syscalls), so tests/native can check them directly. They run on
// every file-system call, so they avoid snprintf and compute the rootfs prefix length once.

static char g_ddnatv_root[PATH_MAX] = {0};
static size_t g_ddnatv_root_len = 0;

static void ddnatv_set_root(const char *root, size_t len) {
    if (!root) len = 0;
    if (len > sizeof(g_ddnatv_root) - 1) len = sizeof(g_ddnatv_root) - 1;
    if (len > 0) memcpy(g_ddnatv_root, root, len);
    g_ddnatv_root[len] = '\0';
    g_ddnatv_root_len = len;
}

// Mounts that proot used to provide: "src:dst" pairs from DDNATV_BINDS ('|' separated), longest dst first.
// A path under dst is read from src; src equal to dst keeps a host path that would otherwise be
// looked up in the rootfs.
struct ddnatv_bind {
    char *src, *dst;
    size_t src_len, dst_len;
    int identity;
    int order; // position in DDNATV_BINDS: the earlier of two mounts of one folder is its guest path
};
// as many as the session has: a fixed table dropped what came after its 128th entry
static struct ddnatv_bind *g_binds = NULL;
static int g_nbinds = 0;
static char *g_binds_raw = NULL;
// The session's host name (DDNATV_HOSTNAME), what proot put in uname(): Android's kernel says "localhost"
static char g_hostname[65];
// A Flatpak sandbox's: the session's rootfs (DDNATV_LINK_ROOT), whose hook stored absolute guest
// link targets as paths in it, as the kernel follows them there; in the sandbox they are guest paths
static char g_link_root[PATH_MAX];
static size_t g_link_root_len = 0;

static int ddnatv_bind_longer_first(const void *a, const void *b) {
    const struct ddnatv_bind *x = a, *y = b;
    return (y->dst_len > x->dst_len) - (y->dst_len < x->dst_len);
}

static void ddnatv_load_binds(const char *spec) {
    if (!spec || !spec[0]) return;
    char *raw = strdup(spec);
    char *copy = strdup(spec);
    if (!raw || !copy) { free(raw); free(copy); return; }
    char *save = NULL;
    size_t count = 1;
    for (const char *c = spec; *c; c++) count += *c == '|';
    g_binds = calloc(count, sizeof(*g_binds));
    if (!g_binds) { free(raw); free(copy); return; }
    for (char *entry = strtok_r(copy, "|", &save); entry && (size_t)g_nbinds < count; entry = strtok_r(NULL, "|", &save)) {
        char *colon = strchr(entry, ':');
        const char *src = entry;
        const char *dst = colon ? colon + 1 : entry;
        if (colon) *colon = '\0';
        if (src[0] != '/' || dst[0] != '/' || strcmp(dst, "/") == 0) continue;
        struct ddnatv_bind *b = &g_binds[g_nbinds++];
        b->src = strdup(src);
        b->dst = strdup(dst);
        b->src_len = strlen(src);
        b->dst_len = strlen(dst);
        while (b->dst_len > 1 && b->dst[b->dst_len - 1] == '/') b->dst[--b->dst_len] = '\0';
        while (b->src_len > 1 && b->src[b->src_len - 1] == '/') b->src[--b->src_len] = '\0';
        b->identity = strcmp(b->src, b->dst) == 0;
        b->order = g_nbinds - 1;
    }
    free(copy);
    qsort(g_binds, (size_t)g_nbinds, sizeof(g_binds[0]), ddnatv_bind_longer_first);
    g_binds_raw = raw;
}

// Appends s[0..len) to out[0..used) and NUL-terminates, truncating like snprintf.
// Returns the new length.
static size_t ddnatv_append(char *out, size_t outsz, size_t used, const char *s, size_t len) {
    if (outsz == 0) return 0;
    size_t room = used < outsz - 1 ? outsz - 1 - used : 0;
    if (len > room) len = room;
    if (len > 0) memcpy(out + used, s, len);
    out[used + len] = '\0';
    return used + len;
}

// Copies the string src into out[0..outsz), truncated and NUL-terminated as strncpy() followed by
// out[outsz - 1] = '\0' leaves it, but writes only the bytes of the string: strncpy() fills the rest
// of a PATH_MAX buffer with zeros, 4 KB of writes for a path of 30 characters, and in a child that a
// shell has just forked every page written is a copy-on-write fault.
static size_t ddnatv_copy_cstr(char *out, size_t outsz, const char *src) {
    if (outsz == 0) return 0;
    size_t len = strnlen(src, outsz - 1);
    memcpy(out, src, len);
    out[len] = '\0';
    return len;
}

static inline int is_path_prefix(const char *path, const char *prefix, size_t prefix_len) {
    if (strncmp(path, prefix, prefix_len) != 0) return 0;
    return path[prefix_len] == '/' || path[prefix_len] == '\0';
}

static inline const char *get_rootfs_subpath(const char *target) {
    if (!target || g_ddnatv_root_len == 0) return NULL;
    if (strncmp(target, g_ddnatv_root, g_ddnatv_root_len) == 0 &&
        (target[g_ddnatv_root_len] == '/' || target[g_ddnatv_root_len] == '\0')) {
        return target + g_ddnatv_root_len;
    }
    return NULL;
}

// A guest path that a session mount puts outside the rootfs (/run/droiddeck, a game library): a
// program or script there is the guest's, and a script's "#!/bin/sh" is the rootfs shell. The
// kernel ran them with Android's /system/bin/sh, which found no /usr/bin/kwin_wayland.
static int is_mapped_guest_path(const char *path) {
    if (!path || path[0] != '/') return 0;
    for (int i = 0; i < g_nbinds; i++) {
        const struct ddnatv_bind *b = &g_binds[i];
        if (b->identity) continue;
        if (is_path_prefix(path, b->dst, b->dst_len)) return 1;
        // its real place too: a relative program is made absolute from the real working directory
        if (is_path_prefix(path, b->src, b->src_len) && get_rootfs_subpath(b->src) == NULL) return 1;
    }
    return 0;
}

// Android's own mounts and files, which must never be redirected into the rootfs.
// `path` starts with '/'; a prefix can only match when its second character does.
static int is_android_host_path(const char *path) {
    switch (path[1]) {
    case 'a': return is_path_prefix(path, "/apex", 5);
    case 'c': return is_path_prefix(path, "/config", 7);
    case 'd': return is_path_prefix(path, "/dev", 4) || is_path_prefix(path, "/d", 2) ||
                     is_path_prefix(path, "/data", 5);
    case 'l': return is_path_prefix(path, "/linkerconfig", 13);
    case 'o': return is_path_prefix(path, "/odm", 4) || is_path_prefix(path, "/oem", 4);
    case 'p': return is_path_prefix(path, "/proc", 5) || is_path_prefix(path, "/product", 8);
    case 's': return strcmp(path, "/sbin/su") == 0 || strcmp(path, "/su") == 0 ||
                     is_path_prefix(path, "/sys", 4) || is_path_prefix(path, "/system", 7) ||
                     is_path_prefix(path, "/system_ext", 11) || is_path_prefix(path, "/sdcard", 7) ||
                     is_path_prefix(path, "/storage", 8);
    case 'v': return is_path_prefix(path, "/vendor", 7);
    default:  return 0;
    }
}

static const char *rewrite_path_in_root(const char *path, char *buffer, size_t bufsize);

#if defined(__aarch64__)
static long raw_readlinkat(int dirfd, const char *path, char *buf, size_t size) {
    register long x8 __asm__("x8") = 78; // readlinkat, made directly: the hook's own readlink would map the path again
    register long x0 __asm__("x0") = dirfd;
    register long x1 __asm__("x1") = (long)path;
    register long x2 __asm__("x2") = (long)buf;
    register long x3 __asm__("x3") = (long)size;
    __asm__ volatile("svc #0" : "+r"(x0) : "r"(x8), "r"(x1), "r"(x2), "r"(x3) : "memory");
    return x0;
}
#endif

static __thread int t_bind_link_depth = 0;

// A mount stand-in can hold symbolic links with absolute guest targets (/sys/class/hidraw/hidraw16 ->
// /sys/devices/droiddeck/...): proot follows them inside the guest, the kernel on Android's own /sys.
// A link met on the way to a deeper path is replaced by its target, mapped again; the last component is
// left as it is (lstat() and readlink() must see the link).
static const char *resolve_bind_links(const char *host, size_t src_len, char *buffer, size_t bufsize) {
#if defined(__aarch64__)
    size_t len = strlen(host);
    if (len >= PATH_MAX || t_bind_link_depth >= 8) return host;
    for (size_t i = src_len + 1; i < len; i++) {
        if (host[i] != '/') continue;
        char prefix[PATH_MAX];
        memcpy(prefix, host, i);
        prefix[i] = '\0';
        char target[PATH_MAX];
        long n = raw_readlinkat(AT_FDCWD, prefix, target, sizeof(target) - 1);
        if (n <= 0) continue;
        target[n] = '\0';
        if (target[0] != '/') continue;
        char guest[PATH_MAX];
        size_t used = ddnatv_append(guest, sizeof(guest), 0, target, (size_t)n);
        ddnatv_append(guest, sizeof(guest), used, host + i, len - i);
        t_bind_link_depth++;
        const char *mapped = rewrite_path_in_root(guest, buffer, bufsize);
        if (mapped != buffer) ddnatv_copy_cstr(buffer, bufsize, mapped);
        t_bind_link_depth--;
        return buffer;
    }
#else
    (void)src_len; (void)bufsize;
#endif
    return host == buffer ? buffer : host;
}

// Maps an absolute Ubuntu path into the rootfs. Returns `path` (possibly advanced past
// redundant leading "/" and "/." components) when it needs no change, else `buffer`.
static const char *rewrite_path_in_root(const char *path, char *buffer, size_t bufsize) {
    if (!path) return NULL;
    if (path[0] != '/' || g_ddnatv_root_len == 0) return path;

    // Strip multiple redundant leading slashes: //foo -> /foo
    while (path[0] == '/' && path[1] == '/') {
        path++;
    }

    // Strip leading /./: /./boot -> /boot
    while (path[0] == '/' && path[1] == '.' && (path[2] == '/' || path[2] == '\0')) {
        if (path[2] == '/') {
            path += 2;
        } else {
            path = "/";
            break;
        }
    }

    // Root directory
    if (path[1] == '\0') {
        ddnatv_append(buffer, bufsize, 0, g_ddnatv_root, g_ddnatv_root_len);
        return buffer;
    }

    for (int i = 0; i < g_nbinds; i++) {
        const struct ddnatv_bind *b = &g_binds[i];
        if (path[1] == b->dst[1] && is_path_prefix(path, b->dst, b->dst_len)) {
            if (b->identity) return path;
            size_t n = ddnatv_append(buffer, bufsize, 0, b->src, b->src_len);
            ddnatv_append(buffer, bufsize, n, path + b->dst_len, strlen(path + b->dst_len));
            return resolve_bind_links(buffer, b->src_len, buffer, bufsize);
        }
    }

    // Already inside The native userland rootfs
    if (get_rootfs_subpath(path) != NULL) {
        return path;
    }

    // The real place of a mounted folder, which getcwd() and /proc/self/fd links answer for it: Steam
    // made a game's path from one on the SD card library and found no sekiro.exe in the rootfs
    for (int i = 0; i < g_nbinds; i++) {
        const struct ddnatv_bind *b = &g_binds[i];
        if (!b->identity && path[1] == b->src[1] && is_path_prefix(path, b->src, b->src_len)) return path;
    }

    // POSIX shared memory (/dev/shm) redirection to The native userland rootfs tmp/shm
    if (strncmp(path, "/dev/shm", 8) == 0 && (path[8] == '/' || path[8] == '\0')) {
        size_t n = ddnatv_append(buffer, bufsize, 0, g_ddnatv_root, g_ddnatv_root_len);
        n = ddnatv_append(buffer, bufsize, n, "/tmp/shm", 8);
        ddnatv_append(buffer, bufsize, n, path + 8, strlen(path + 8));
        return buffer;
    }

    // Real host Android kernel & system mounts
    if (is_android_host_path(path)) {
        return path;
    }

    // Any other absolute path belongs to The native userland rootfs (/boot, /media, /snap, /etc, /usr, ...)
    size_t n = ddnatv_append(buffer, bufsize, 0, g_ddnatv_root, g_ddnatv_root_len);
    ddnatv_append(buffer, bufsize, n, path, strlen(path));
    return buffer;
}

// A link target the session's hook stored as a path in its rootfs, given back as the guest path it
// stands for (see g_link_root). Rewrites target[0..len) in place; returns the new length.
static size_t link_root_guest_target(char *target, size_t len) {
    if (!g_link_root_len || len < g_link_root_len || memcmp(target, g_link_root, g_link_root_len) != 0) return len;
    if (len == g_link_root_len) { target[0] = '/'; target[1] = '\0'; return 1; }
    if (target[g_link_root_len] != '/') return len;
    memmove(target, target + g_link_root_len, len - g_link_root_len + 1);
    return len - g_link_root_len;
}

// The guest path a symbolic link at absolute guest path `path` leads to, followed in the guest's
// view: the kernel resolves a relative target next to the link's real place, and in a mount that is
// elsewhere (Flatpak's /run/user/0/wayland-0 -> ../../flatpak/wayland-0, where /run/flatpak/wayland-0
// is another mount). Returns `path` when it is no link.
static const char *follow_guest_link(const char *path, char *out, size_t outsz) {
#if defined(__aarch64__)
    const char *cur = path;
    for (int depth = 0; depth < 8; depth++) {
        char host_buf[PATH_MAX];
        const char *host = rewrite_path_in_root(cur, host_buf, sizeof(host_buf));
        // /dev/stdin and /dev/fd are mounts of /proc/self/fd, whose links ("pipe:[123]") only the kernel opens
        if (strncmp(host, "/proc/", 6) == 0) return cur;
        char target[PATH_MAX];
        long n = raw_readlinkat(AT_FDCWD, host, target, sizeof(target) - 1);
        if (n <= 0) return cur;
        target[n] = '\0';
        n = (long)link_root_guest_target(target, (size_t)n);
        char joined[PATH_MAX];
        if (target[0] == '/') {
            ddnatv_copy_cstr(joined, sizeof(joined), target);
        } else {
            const char *slash = strrchr(cur, '/');
            size_t dir_len = slash ? (size_t)(slash - cur) : 0;
            size_t used = ddnatv_append(joined, sizeof(joined), 0, cur, dir_len);
            used = ddnatv_append(joined, sizeof(joined), used, "/", 1);
            ddnatv_append(joined, sizeof(joined), used, target, strlen(target));
        }
        // lexically, as the guest sees it
        size_t len = 0;
        out[0] = '\0';
        for (const char *p = joined; *p;) {
            while (*p == '/') p++;
            const char *end = strchr(p, '/');
            size_t part = end ? (size_t)(end - p) : strlen(p);
            if (part == 0) break;
            if (part == 1 && p[0] == '.') {
            } else if (part == 2 && p[0] == '.' && p[1] == '.') {
                while (len > 0 && out[len - 1] != '/') len--;
                if (len > 0) len--;
                out[len] = '\0';
            } else if (len + 1 + part < outsz) {
                out[len++] = '/';
                memcpy(out + len, p, part);
                len += part;
                out[len] = '\0';
            }
            p += part;
        }
        if (len == 0) { out[0] = '/'; out[1] = '\0'; }
        cur = out;
    }
    return cur;
#else
    (void)out; (void)outsz;
    return path;
#endif
}

// On ENOENT, stat()/lstat()/readlink() retry /usr/bin <-> /bin and /usr/sbin <-> /sbin, for
// rootfs trees where bin and sbin are real directories. Returns 1 for the bin pair, 2 for the
// sbin pair (with the other spelling in `alt`), 0 when `sub` is in neither.
#define DDNATV_USRMERGE_BIN 1
#define DDNATV_USRMERGE_SBIN 2
static int usrmerge_alt_path(const char *sub, char *alt, size_t altsz) {
    const char *dir;
    const char *rest;
    int pair;
    if (strncmp(sub, "/usr/bin/", 9) == 0) {
        dir = "/bin/"; rest = sub + 9; pair = DDNATV_USRMERGE_BIN;
    } else if (strncmp(sub, "/bin/", 5) == 0) {
        dir = "/usr/bin/"; rest = sub + 5; pair = DDNATV_USRMERGE_BIN;
    } else if (strncmp(sub, "/usr/sbin/", 10) == 0) {
        dir = "/sbin/"; rest = sub + 10; pair = DDNATV_USRMERGE_SBIN;
    } else if (strncmp(sub, "/sbin/", 6) == 0) {
        dir = "/usr/sbin/"; rest = sub + 6; pair = DDNATV_USRMERGE_SBIN;
    } else {
        return 0;
    }
    size_t n = ddnatv_append(alt, altsz, 0, g_ddnatv_root, g_ddnatv_root_len);
    n = ddnatv_append(alt, altsz, n, dir, strlen(dir));
    ddnatv_append(alt, altsz, n, rest, strlen(rest));
    return pair;
}

// Whether postprocess_readlink() below can change a link's target of length target_len, read
// through linkpath: only a target that starts with the rootfs path (at least that long) or a relative
// one into /etc/alternatives (at least "../alternatives", 15 characters, from a directory next to it;
// any "../" under /etc/alternatives itself). lstat() of a link reads it only then, to report its
// rewritten length.
static int readlink_result_may_change(const char *linkpath, long long target_len) {
    if (g_ddnatv_root_len > 0 && target_len >= (long long)g_ddnatv_root_len) return 1;
    if (!linkpath || linkpath[0] != '/') return 0;
    if (strncmp(linkpath, "/etc/alternatives", 17) == 0) return target_len >= 2;
    return target_len >= 15;
}

// Helper for path normalization
static void normalize_path(char *path) {
    if (!path || !*path) return;
    int is_abs = (path[0] == '/');
    char *stack[128];
    int top = 0;

    char tmp[4096];
    strncpy(tmp, path, sizeof(tmp) - 1);
    tmp[sizeof(tmp) - 1] = '\0';

    char *saveptr = NULL;
    char *tok = strtok_r(tmp, "/", &saveptr);
    while (tok) {
        if (strcmp(tok, ".") == 0) {
            // ignore
        } else if (strcmp(tok, "..") == 0) {
            if (top > 0) top--;
        } else {
            if (top < 128) stack[top++] = tok;
        }
        tok = strtok_r(NULL, "/", &saveptr);
    }

    char *dst = path;
    if (is_abs) *dst++ = '/';
    for (int i = 0; i < top; i++) {
        size_t len = strlen(stack[i]);
        memcpy(dst, stack[i], len);
        dst += len;
        if (i + 1 < top) *dst++ = '/';
    }
    if (dst == path && is_abs) *dst++ = '/';
    *dst = '\0';
}

// /proc/self/fd/N, /proc/thread-self/fd/N and /proc/PID/fd/N: the real path of an open file. A program
// that finds a path this way (Bun and Zig programs do: their realpath() is an open and a readlink of
// this) goes on with system calls of its own, which the hook does not see, so a path inside the rootfs
// has to keep its real beginning there: "/home" for $DDNATV_ROOT/home is a path that does not exist
// for them ("ENOENT, realpath '/home'" in Kilo). It is what getcwd() answers, too.
static int is_proc_fd_link(const char *path) {
    if (!path || strncmp(path, "/proc/", 6) != 0) return 0;
    const char *p = path + 6;
    if (strncmp(p, "self/", 5) == 0) {
        p += 5;
    } else if (strncmp(p, "thread-self/", 12) == 0) {
        p += 12;
    } else {
        if (*p < '0' || *p > '9') return 0;
        while (*p >= '0' && *p <= '9') p++;
        if (*p != '/') return 0;
        p++;
    }
    // fd/N and nothing after it: a path beneath the link goes through it to somewhere else
    if (strncmp(p, "fd/", 3) != 0) return 0;
    p += 3;
    if (*p < '0' || *p > '9') return 0;
    while (*p >= '0' && *p <= '9') p++;
    return *p == '\0';
}

// The guest path of a folder that a session mount puts outside the rootfs, for what the kernel
// reports as its real place (getcwd(), /proc/self/cwd and fd links). Wine found no DOS drive for
// Sekiro's folder on the SD card library ("starting in the Windows directory"), and the game quit.
// Rewrites buf[0..len) in place when the guest path fits in cap; returns the new length.
static size_t guest_path_of_mount_source(char *buf, size_t len, size_t cap) {
    const struct ddnatv_bind *best = NULL;
    for (int i = 0; i < g_nbinds; i++) {
        const struct ddnatv_bind *b = &g_binds[i];
        if (b->identity || get_rootfs_subpath(b->src) != NULL || len < b->src_len) continue;
        if (memcmp(buf, b->src, b->src_len) != 0 || (len > b->src_len && buf[b->src_len] != '/')) continue;
        if (!best || b->src_len > best->src_len || (b->src_len == best->src_len && b->order < best->order)) best = b;
    }
    if (!best) return len;
    size_t rest = len - best->src_len;
    if (best->dst_len + rest + 1 > cap) return len;
    memmove(buf + best->dst_len, buf + best->src_len, rest);
    memcpy(buf, best->dst, best->dst_len);
    buf[best->dst_len + rest] = '\0';
    return best->dst_len + rest;
}

static ssize_t postprocess_readlink(const char *linkpath, char *buf, ssize_t ret, size_t bufsiz) {
    if (ret <= 0) return ret;
    if ((size_t)ret < bufsiz) {
        buf[ret] = '\0';
        ret = (ssize_t)link_root_guest_target(buf, (size_t)ret);
    }
    ret = (ssize_t)guest_path_of_mount_source(buf, (size_t)ret, bufsiz);

    // 1. Strip g_ddnatv_root if target starts with it
    if (g_ddnatv_root_len > 0 && !is_proc_fd_link(linkpath)) {
        size_t rlen = g_ddnatv_root_len;
        if ((size_t)ret >= rlen && memcmp(buf, g_ddnatv_root, rlen) == 0 &&
            ((size_t)ret == rlen || buf[rlen] == '/')) {
            size_t new_len = ret - rlen;
            if (new_len == 0) {
                buf[0] = '/';
                return 1;
            } else {
                memmove(buf, buf + rlen, new_len);
                return (ssize_t)new_len;
            }
        }
    }

    // 2. Normalize relative alternatives links. The result can only start with /etc/alternatives
    // (or the link be in it) when "alternatives" is in the target or in the link's own path: the
    // normalized path is made of their components. Most relative links have neither (the ones of
    // /usr/lib and /usr/share/doc), and these skip the copies and the normalization, which were a
    // tenth of what "cp -a" of a tree of them cost.
    if (linkpath && linkpath[0] == '/' && (size_t)ret < PATH_MAX && ret >= 2 && buf[0] == '.' && buf[1] == '.' &&
        (memmem(buf, (size_t)ret, "alternatives", 12) != NULL || strstr(linkpath, "alternatives") != NULL)) {
        char link_copy[PATH_MAX];
        memcpy(link_copy, buf, ret);
        link_copy[ret] = '\0';

        if (strncmp(link_copy, "..", 2) == 0) {
            char parent[PATH_MAX];
            snprintf(parent, sizeof(parent), "%s", linkpath);
            char *last_slash = strrchr(parent, '/');
            if (last_slash) {
                if (last_slash == parent) {
                    *(last_slash + 1) = '\0';
                } else {
                    *last_slash = '\0';
                }
            }
            char combined[PATH_MAX * 2];
            snprintf(combined, sizeof(combined), "%s/%s", parent, link_copy);
            normalize_path(combined);

            if (strncmp(combined, "/etc/alternatives", 17) == 0 ||
                strncmp(linkpath, "/etc/alternatives", 17) == 0) {
                size_t clen = strlen(combined);
                if (clen <= bufsiz) {
                    memcpy(buf, combined, clen);
                    return (ssize_t)clen;
                }
            }
        }
    }

    return ret;
}

// Android's SELinux label, which the xattr hooks keep away from Ubuntu programs.
static const char k_selinux_xattr[] = "security.selinux";

static int is_selinux_xattr(const char *name) {
    return name != NULL && strcmp(name, k_selinux_xattr) == 0;
}

// Removes "security.selinux" from a listxattr() result (NUL-separated names); returns the new length.
static ssize_t drop_selinux_xattr(char *list, ssize_t len) {
    if (!list || len <= 0) return len;
    ssize_t out = 0;
    ssize_t i = 0;
    while (i < len) {
        size_t name_len = strnlen(list + i, (size_t)(len - i));
        ssize_t entry = (ssize_t)name_len + 1;
        if (entry > len - i) entry = len - i;
        if (name_len != sizeof(k_selinux_xattr) - 1 || memcmp(list + i, k_selinux_xattr, name_len) != 0) {
            memmove(list + out, list + i, (size_t)entry);
            out += entry;
        }
        i += entry;
    }
    return out;
}

// Library directories of the rootfs, in the order ld.so searches them. ld.so tries every
// directory for every library, so the multiarch ones that hold nearly all libraries come first
// (as in glibc's own default order). Keep in sync with Environment.kt, 00-env.sh and su.sh.
static const char *const k_rootfs_lib_dirs[] = {
    "/lib/aarch64-linux-gnu", "/usr/lib/aarch64-linux-gnu",
    "/lib/arm-linux-gnueabihf", "/usr/lib/arm-linux-gnueabihf",
    "/lib", "/usr/lib", "/usr/local/lib",
    "/usr/lib/systemd", "/lib/systemd",
    "/usr/lib/aarch64-linux-gnu/systemd", "/lib/aarch64-linux-gnu/systemd",
    "/usr/lib/arm-linux-gnueabihf/systemd", "/lib/arm-linux-gnueabihf/systemd",
};

// Whether the ':'-separated list holds the directory dir[0..dir_len) as one of its entries.
static int lib_dir_list_has(const char *list, const char *dir, size_t dir_len) {
    if (!list) return 0;
    for (const char *p = list; *p;) {
        const char *end = strchr(p, ':');
        size_t len = end ? (size_t)(end - p) : strlen(p);
        if (len == dir_len && memcmp(p, dir, len) == 0) return 1;
        if (!end) break;
        p = end + 1;
    }
    return 0;
}

// Appends the rootfs library directories as a ':'-separated list (with a leading ':' when
// `separate` is set), leaving out those that `have` (a list too, or NULL) names already: the
// caller's LD_LIBRARY_PATH is usually this very list, and ld.so spends time on every directory of
// the list it is given, in every program that is started, and tries it again for each library that
// is not found. Returns the new length.
static size_t append_rootfs_lib_dirs_missing_from(char *out, size_t outsz, size_t used, int separate, const char *have) {
    for (size_t i = 0; i < sizeof(k_rootfs_lib_dirs) / sizeof(k_rootfs_lib_dirs[0]); i++) {
        // a Flatpak sandbox mounts /usr from its runtime
        char buf[PATH_MAX];
        const char *full = rewrite_path_in_root(k_rootfs_lib_dirs[i], buf, sizeof(buf));
        size_t n = strlen(full);
        if (have && have[0] != '\0' && lib_dir_list_has(have, full, n)) continue;
        // whole directories only: a cut-off one would be searched in the wrong place
        if (used + (separate ? 1 : 0) + n >= outsz) continue;
        if (separate) used = ddnatv_append(out, outsz, used, ":", 1);
        separate = 1;
        used = ddnatv_append(out, outsz, used, full, n);
    }
    return used;
}

// All of them.
static size_t append_rootfs_lib_dirs(char *out, size_t outsz, size_t used, int separate) {
    return append_rootfs_lib_dirs_missing_from(out, outsz, used, separate, NULL);
}

#ifndef DDNATV_HOST_TEST
// Relative *at() calls. A program that walks a path one component at a time (systemd's chase(), used
// by libudev; fts) opens "/" and then "sys", "class", ... relative to the last descriptor, and those
// names never pass through rewrite_path(): "/" is the rootfs, whose own sys/ is empty, so the stand-ins
// mounted at /sys/class/hidraw and the host's /sys were out of reach. The directories on the way to a
// mount (every proper ancestor of a mount's guest path) are recorded by device and inode; a name given
// relative to one of them is joined to its guest path and mapped like an absolute one.
struct ddnatv_at_dir {
    unsigned long dev, ino;
    char *guest;
};
static struct ddnatv_at_dir *g_at_dirs = NULL;
static int g_n_at_dirs = 0;
static pthread_once_t g_at_once = PTHREAD_ONCE_INIT;

#if defined(__aarch64__)
static long raw_fstatat(int dirfd, const char *path, struct stat *st, int flags) {
    register long x8 __asm__("x8") = 79; // newfstatat
    register long x0 __asm__("x0") = dirfd;
    register long x1 __asm__("x1") = (long)path;
    register long x2 __asm__("x2") = (long)st;
    register long x3 __asm__("x3") = flags;
    __asm__ volatile("svc #0" : "+r"(x0) : "r"(x8), "r"(x1), "r"(x2), "r"(x3) : "memory");
    return x0;
}
#endif

static void ddnatv_add_at_dir(const char *guest) {
#if defined(__aarch64__)
    for (int i = 0; i < g_n_at_dirs; i++) if (strcmp(g_at_dirs[i].guest, guest) == 0) return;
    char buf[PATH_MAX];
    const char *host = rewrite_path_in_root(guest, buf, sizeof(buf));
    struct stat st;
    if (raw_fstatat(AT_FDCWD, host, &st, 0) != 0 || !S_ISDIR(st.st_mode)) return;
    struct ddnatv_at_dir *grown = realloc(g_at_dirs, sizeof(*grown) * (size_t)(g_n_at_dirs + 1));
    if (!grown) return;
    g_at_dirs = grown;
    g_at_dirs[g_n_at_dirs].dev = (unsigned long)st.st_dev;
    g_at_dirs[g_n_at_dirs].ino = (unsigned long)st.st_ino;
    g_at_dirs[g_n_at_dirs].guest = strdup(guest);
    if (g_at_dirs[g_n_at_dirs].guest) g_n_at_dirs++;
#else
    (void)guest;
#endif
}

static void ddnatv_build_at_dirs(void) {
    if (g_nbinds == 0) return;
    ddnatv_add_at_dir("/");
    for (int i = 0; i < g_nbinds; i++) {
        char guest[PATH_MAX];
        ddnatv_copy_cstr(guest, sizeof(guest), g_binds[i].dst);
        for (char *slash = strrchr(guest, '/'); slash && slash > guest; slash = strrchr(guest, '/')) {
            *slash = '\0';
            ddnatv_add_at_dir(guest);
        }
    }
}

// The absolute host path for `rel` given relative to `dirfd`, or NULL when the call needs no change.
static const char *map_at(int dirfd, const char *rel, char *buf, size_t size) {
#if defined(__aarch64__)
    if (dirfd == AT_FDCWD || !rel || rel[0] == '\0' || rel[0] == '/' || g_nbinds == 0) return NULL;
    pthread_once(&g_at_once, ddnatv_build_at_dirs);
    if (g_n_at_dirs == 0) return NULL;
    struct stat st;
    if (raw_fstatat(dirfd, "", &st, AT_EMPTY_PATH) != 0) return NULL;
    for (int i = 0; i < g_n_at_dirs; i++) {
        if (g_at_dirs[i].ino != (unsigned long)st.st_ino || g_at_dirs[i].dev != (unsigned long)st.st_dev) continue;
        char guest[PATH_MAX];
        const char *base = g_at_dirs[i].guest;
        size_t used = ddnatv_append(guest, sizeof(guest), 0, base, strlen(base));
        if (used > 1) used = ddnatv_append(guest, sizeof(guest), used, "/", 1);
        ddnatv_append(guest, sizeof(guest), used, rel, strlen(rel));
        const char *mapped = rewrite_path_in_root(guest, buf, size);
        if (mapped != buf) ddnatv_copy_cstr(buf, size, mapped);
        return buf;
    }
#else
    (void)dirfd; (void)rel; (void)buf; (void)size;
#endif
    return NULL;
}

#define DDNATV_AT_REMAP_NAMED(dirfd, pathname, buf)                               \
    char buf[PATH_MAX];                                                           \
    if (map_at((dirfd), (pathname), buf, sizeof(buf))) {                          \
        (dirfd) = AT_FDCWD;                                                       \
        (pathname) = buf;                                                         \
    }
#define DDNATV_AT_REMAP(dirfd, pathname) DDNATV_AT_REMAP_NAMED(dirfd, pathname, ddnatv_at_buf)
#endif /* !DDNATV_HOST_TEST */
