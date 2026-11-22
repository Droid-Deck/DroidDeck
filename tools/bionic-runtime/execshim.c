// execshim: preloaded into the bionic Wine processes. From targetSdk 29 Android refuses execve() of files in the
// app's data directory, but the system linker still starts them: "/system/bin/linker64 <file> [args]". So an
// execve()/posix_spawn() of a dynamic ELF under /data whose interpreter is /system/bin/linker64 becomes a linker64
// start, and a "#!" script there becomes a start of its interpreter. Static ELFs (wine-preloader) and everything
// else pass through unchanged: where Android refuses them, Wine falls back to its loader, as on any failed exec.
// A program started that way sees /proc/self/exe as linker64; wineserver looks for its NLS files next to its own
// executable (server/unicode.c get_nls_dir), so realpath()/readlink() of /proc/self/exe answer with the program
// linker64 runs (the second /proc/self/cmdline entry).
#define _GNU_SOURCE
#include <dlfcn.h>
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#if __has_include(<spawn.h>)
#include <spawn.h>
#else  // old Android headers (e.g. Termux's): bionic's own definitions, both opaque pointers
typedef struct __posix_spawnattr *posix_spawnattr_t;
typedef struct __posix_spawn_file_actions *posix_spawn_file_actions_t;
#endif
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#define LINKER "/system/bin/linker64"

enum kind { PASS, VIA_LINKER, VIA_INTERP };

// What starting `path` needs; for scripts, the interpreter and its optional argument.
static enum kind classify(const char *path, char *resolved, char *interp, char *iarg) {
    if (!path || !realpath(path, resolved) || strncmp(resolved, "/data/", 6) != 0) return PASS;
    // non-blocking, so a FIFO without a writer cannot hang the start; anything but a regular file goes to libc
    int fd = open(resolved, O_RDONLY | O_CLOEXEC | O_NONBLOCK);
    if (fd < 0) return PASS;
    struct stat st;
    if (fstat(fd, &st) != 0 || !S_ISREG(st.st_mode)) {
        close(fd);
        return PASS;
    }
    enum kind k = PASS;
    unsigned char head[256];
    ssize_t n = pread(fd, head, sizeof head, 0);
    if (n >= (ssize_t)sizeof(Elf64_Ehdr) && memcmp(head, ELFMAG, SELFMAG) == 0 && head[EI_CLASS] == ELFCLASS64) {
        Elf64_Ehdr eh;
        memcpy(&eh, head, sizeof eh);
        for (int i = 0; i < eh.e_phnum && k == PASS; i++) {
            Elf64_Phdr ph;
            if (pread(fd, &ph, sizeof ph, (off_t)(eh.e_phoff + (Elf64_Off)i * eh.e_phentsize)) != sizeof ph) break;
            if (ph.p_type != PT_INTERP || ph.p_filesz == 0 || ph.p_filesz >= PATH_MAX) continue;
            char name[PATH_MAX] = {0};
            if (pread(fd, name, ph.p_filesz, (off_t)ph.p_offset) == (ssize_t)ph.p_filesz && strcmp(name, LINKER) == 0)
                k = VIA_LINKER;
        }
    } else if (n > 2 && head[0] == '#' && head[1] == '!') {
        char line[256];
        size_t len = 0;
        for (ssize_t i = 2; i < n && head[i] != '\n' && len < sizeof line - 1; i++) line[len++] = (char)head[i];
        line[len] = 0;
        char *p = line;
        while (*p == ' ' || *p == '\t') p++;
        char *end = p;
        while (*end && *end != ' ' && *end != '\t') end++;
        char *rest = *end ? end + 1 : end;
        *end = 0;
        while (*rest == ' ' || *rest == '\t') rest++;
        size_t rl = strlen(rest);
        while (rl && (rest[rl - 1] == ' ' || rest[rl - 1] == '\t' || rest[rl - 1] == '\r')) rest[--rl] = 0;
        if (*p) {
            strcpy(interp, p);
            strcpy(iarg, rest);
            k = VIA_INTERP;
        }
    }
    close(fd);
    return k;
}

static int argc_of(char *const argv[]) {
    int n = 0;
    while (argv && argv[n]) n++;
    return n;
}

// New argv for the rewritten start; *file is what to start. NULL when nothing changes.
static char **rewrite(const char *path, char *const argv[], const char **file, char *storage) {
    char *resolved = storage, *interp = storage + PATH_MAX, *iarg = storage + 2 * PATH_MAX;
    enum kind k = classify(path, resolved, interp, iarg);
    if (k == PASS) return NULL;
    int argc = argc_of(argv);
    // up to four leading entries, argc - 1 trailing ones and the terminator (argc may be 0)
    char **out = calloc((size_t)(argc > 0 ? argc - 1 : 0) + 5, sizeof *out);
    if (!out) return NULL;
    int j = 0;
    if (k == VIA_LINKER) {
        *file = LINKER;
        out[j++] = (char *)LINKER;
        out[j++] = resolved;
    } else {
        // the interpreter may itself live in app data
        char *iresolved = storage + 3 * PATH_MAX, *x = storage + 4 * PATH_MAX, *y = storage + 5 * PATH_MAX;
        if (classify(interp, iresolved, x, y) == VIA_LINKER) {
            *file = LINKER;
            out[j++] = (char *)LINKER;
            out[j++] = iresolved;
        } else {
            *file = interp;
            out[j++] = interp;
        }
        if (*iarg) out[j++] = iarg;
        out[j++] = resolved;
    }
    for (int i = 1; i < argc; i++) out[j++] = argv[i];
    out[j] = NULL;
    return out;
}

// libc's functions: bound when the library loads (single-threaded), so execve() in a post-fork child finds them bound.
// A call that comes before that (another library's constructor) binds through an atomic slot: concurrent binders
// store the same pointer, and readers never see a torn one.
typedef ssize_t (*readlink_fn)(const char *, char *, size_t);
typedef char *(*realpath_fn)(const char *, char *);
typedef int (*execve_fn)(const char *, char *const[], char *const[]);
typedef int (*spawn_fn)(pid_t *, const char *, const posix_spawn_file_actions_t *, const posix_spawnattr_t *,
                        char *const[], char *const[]);
static void *slot_readlink, *slot_realpath, *slot_execve, *slot_spawn, *slot_spawnp;

static void *bound(void **slot, const char *name) {
    void *p = __atomic_load_n(slot, __ATOMIC_ACQUIRE);
    if (!p) {
        p = dlsym(RTLD_NEXT, name);
        __atomic_store_n(slot, p, __ATOMIC_RELEASE);
    }
    return p;
}
#define real_readlink ((readlink_fn)bound(&slot_readlink, "readlink"))
#define real_realpath ((realpath_fn)bound(&slot_realpath, "realpath"))
#define real_execve ((execve_fn)bound(&slot_execve, "execve"))
#define real_spawn ((spawn_fn)bound(&slot_spawn, "posix_spawn"))
#define real_spawnp ((spawn_fn)bound(&slot_spawnp, "posix_spawnp"))

__attribute__((constructor)) static void bind_libc(void) {
    (void)real_readlink;
    (void)real_realpath;
    (void)real_execve;
    (void)real_spawn;
    (void)real_spawnp;
}

// The program linker64 runs, when this process is such a start; 0 otherwise.
static int linker_program(char *out, size_t n) {
    if (!real_readlink) return 0;
    char link[PATH_MAX];
    ssize_t l = real_readlink("/proc/self/exe", link, sizeof link - 1);
    if (l <= 0) return 0;
    link[l] = 0;
    const char *base = strrchr(link, '/');  // /system/bin/linker64 is a link into /apex/com.android.runtime/bin
    if (strcmp(base ? base + 1 : link, "linker64") != 0) return 0;
    int fd = open("/proc/self/cmdline", O_RDONLY | O_CLOEXEC);
    if (fd < 0) return 0;
    char buf[2 * PATH_MAX];
    ssize_t got = read(fd, buf, sizeof buf - 1);
    close(fd);
    if (got <= 0) return 0;
    buf[got] = 0;
    size_t first = strlen(buf);
    if ((ssize_t)first + 1 >= got || !buf[first + 1] || strlen(buf + first + 1) >= n) return 0;
    strcpy(out, buf + first + 1);
    return 1;
}

char *realpath(const char *path, char *resolved) {
    if (!real_realpath) {
        errno = ENOSYS;
        return NULL;
    }
    char prog[PATH_MAX];
    if (path && strcmp(path, "/proc/self/exe") == 0 && linker_program(prog, sizeof prog)) return real_realpath(prog, resolved);
    return real_realpath(path, resolved);
}

ssize_t readlink(const char *path, char *buf, size_t size) {
    char prog[PATH_MAX], full[PATH_MAX];
    if (path && strcmp(path, "/proc/self/exe") == 0 && linker_program(prog, sizeof prog)) {
        if (real_realpath && real_realpath(prog, full)) {
            size_t l = strlen(full);
            if (l > size) l = size;
            memcpy(buf, full, l);
            return (ssize_t)l;
        }
    }
    if (!real_readlink) {
        errno = ENOSYS;
        return -1;
    }
    return real_readlink(path, buf, size);
}

int execve(const char *path, char *const argv[], char *const envp[]) {
    execve_fn real = real_execve;
    if (!real) {
        errno = ENOSYS;
        return -1;
    }
    char storage[6 * PATH_MAX];  // stack only: this may run in posix_spawn's vfork-like child (where it passes)
    const char *file = path;
    char **args = rewrite(path, argv, &file, storage);
    int ret = real(file, args ? args : argv, envp);
    int saved = errno;
    free(args);
    errno = saved;
    return ret;
}

int posix_spawn(pid_t *pid, const char *path, const posix_spawn_file_actions_t *actions,
                const posix_spawnattr_t *attr, char *const argv[], char *const envp[]) {
    spawn_fn real = real_spawn;
    if (!real) return ENOSYS;
    char storage[6 * PATH_MAX];
    const char *file = path;
    // file actions can change the child's directory before it starts: a relative path is resolved there by libc, so
    // it is left to libc instead of being resolved here against the parent's directory
    char **args = (actions && path && path[0] != '/') ? NULL : rewrite(path, argv, &file, storage);
    int ret = real(pid, file, actions, attr, args ? args : argv, envp);
    free(args);
    return ret;
}

int posix_spawnp(pid_t *pid, const char *file, const posix_spawn_file_actions_t *actions,
                 const posix_spawnattr_t *attr, char *const argv[], char *const envp[]) {
    if (file && strchr(file, '/')) return posix_spawn(pid, file, actions, attr, argv, envp);
    spawn_fn real = real_spawnp;
    if (!real) return ENOSYS;
    return real(pid, file, actions, attr, argv, envp);
}
