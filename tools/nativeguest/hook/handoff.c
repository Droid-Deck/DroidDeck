// Part of libddnatv-hook.so, included by ddnatv-hook.c after exec.c.
//
// Exec handoff. Android counts every process in the app's cgroup against its child-process limit,
// but not the app's own processes (docs/phantom-processes.md). A program a guest starts with
// fork() + execve() can instead run as the main process of one of the app's worker processes: the
// forked child sends the exec to the app (DD_SPAWN, an abstract socket) with its open fds, records
// the worker's pid in a table it shares with its parent, and exits. For the parent nothing changes:
// wait*(), kill() and SIGCHLD treat the pid fork() returned as that program until it exits; the app
// writes its exit status into the table and sends the parent SIGCHLD.
//
// Only programs named in DD_HANDOFF (basenames separated by ':', or "*") are handed off. The child's
// side runs where vfork() children run too, so it allocates nothing and writes no globals.

#define DDH_SLOTS 1024
#define DDH_FREE 0
#define DDH_CLAIMED 3
#define DDH_HANDED 1
#define DDH_EXITED 2
#define DDH_MAGIC 0x6464686f
#define DDH_TABLE_FD 800

struct ddh_entry {
    int32_t child;    // the pid the parent got from fork()
    int32_t real;     // the worker's pid
    int32_t parent;
    int32_t state;
    int32_t status;   // wait status, written by the app
    int32_t reaped;   // the parent has reaped the forked child itself
    int32_t pad[2];
};

struct ddh_table {
    int32_t magic;
    int32_t pad[15];
    struct ddh_entry e[DDH_SLOTS];
};

static struct ddh_table *g_ddh;   // this process's table, for the children it forks
static int g_ddh_fd = -1;
static pid_t g_ddh_owner;
// The parent's table, when this image was exec'd by a child the parent forked (a wrapper such as
// env or a script that execs its program): that exec can still be handed off.
static struct ddh_table *g_ddh_up;
static int g_ddh_up_fd = -1;

static const char *ddh_env(char *const envp[], const char *name) {
    size_t n = strlen(name);
    for (char *const *e = envp; e && *e; e++) {
        if (strncmp(*e, name, n) == 0 && (*e)[n] == '=') return *e + n + 1;
    }
    return NULL;
}

// DD_HANDOFF_DEBUG=1 writes the trace to stderr; an absolute path appends it to that file instead
// (stderr can be a pipe a program parses, or one nobody reads).
static void ddh_log(const char *setting, const char *line, size_t len) {
    if (!setting || len == 0) return;
    if (setting[0] != '/') {
        syscall(SYS_write, 2, line, len);
        return;
    }
    int fd = (int)syscall(SYS_openat, AT_FDCWD, setting, O_WRONLY | O_APPEND | O_CREAT | O_CLOEXEC, 0644);
    if (fd < 0) return;
    syscall(SYS_write, fd, line, len);
    syscall(SYS_close, fd);
}

// In the parent, before fork(): the table the children write to.
static void ddh_prepare_failed(const char *step) {
    const char *debug = getenv("DD_HANDOFF_DEBUG");
    if (!debug) return;
    char line[96];
    int len = snprintf(line, sizeof(line), "ddnatv handoff: %d no table: %s errno %d\n", (int)getpid(), step, errno);
    if (len > 0) ddh_log(debug, line, (size_t)len);
}

static void ddh_keep(int fd);

// The table the children write to: at start when DD_SPAWN is set, else at the first fork() (a
// shell that exports DD_SPAWN itself has no sign of it in its own environment).
static void ddh_prepare(int any) {
    if (g_ddh || (!any && !getenv("DD_SPAWN"))) return;
    int fd = (int)syscall(SYS_memfd_create, "ddnatv-handoff", 1 /* MFD_CLOEXEC */);
    if (fd < 0) {
        ddh_prepare_failed("memfd_create");
        return;
    }
    if (ftruncate(fd, sizeof(struct ddh_table)) != 0) {
        ddh_prepare_failed("ftruncate");
        close(fd);
        return;
    }
    void *p = mmap(NULL, sizeof(struct ddh_table), PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    if (p == MAP_FAILED) {
        ddh_prepare_failed("mmap");
        close(fd);
        return;
    }
    // Out of the way of the numbers programs redirect, and kept open when a child closes every
    // inherited fd before exec (Python's subprocess does): the child needs it for its handoff.
    int high = (int)syscall(SYS_fcntl, fd, F_DUPFD_CLOEXEC, DDH_TABLE_FD);
    if (high >= 0) {
        syscall(SYS_close, fd);
        fd = high;
    }
    ddh_keep(fd);
    g_ddh = p;
    g_ddh->magic = DDH_MAGIC;
    g_ddh->pad[0] = getpid();
    // A nonce that tells this table's fd from any other (inode numbers are rewritten by the hardlink emulation)
    if (syscall(SYS_getrandom, &g_ddh->pad[1], 2 * sizeof(int32_t), 0) != 2 * (long)sizeof(int32_t)) {
        g_ddh->pad[1] = (int32_t)time(NULL);
        g_ddh->pad[2] = (int32_t)(intptr_t)p;
    }
    g_ddh_fd = fd;
    g_ddh_owner = getpid();
}

// Whether fd is still this table: a program may have put a file of its own at that number.
static int ddh_is_table(int fd, const struct ddh_table *t) {
    int32_t head[4];
    if (fd < 0 || syscall(SYS_pread64, fd, head, sizeof(head), 0) != (long)sizeof(head)) return 0;
    return head[0] == t->magic && head[1] == t->pad[0] && head[2] == t->pad[1] && head[3] == t->pad[2];
}

// A table inherited across exec: the parent's if its owner is still our parent, else closed.
static void ddh_find_parent_table(void) {
    char path[64], target[64];
    pid_t parent = getppid();
    // The table a worker's program reports its exit to (DDH_TABLE), also for what it execs
    int report = -1;
    const char *spec = getenv("DDH_TABLE");
    if (spec && getenv("DDH_WORKER_PID") && atoi(getenv("DDH_WORKER_PID")) == getpid()) report = atoi(spec);
    for (int fd = 3; fd < 1024; fd++) {
        if (fd == report) {
            ddh_keep(fd);
            continue;
        }
        int n = snprintf(path, sizeof(path), "/proc/self/fd/%d", fd);
        if (n <= 0) continue;
        long len = syscall(SYS_readlinkat, AT_FDCWD, path, target, sizeof(target) - 1);
        if (len <= 0) continue;
        target[len] = 0;
        if (strncmp(target, "/memfd:ddnatv-handoff", 21) != 0) continue;
        struct ddh_table *t = mmap(NULL, sizeof(struct ddh_table), PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
        if (t != MAP_FAILED && t->magic == DDH_MAGIC && t->pad[0] == parent && !g_ddh_up) {
            g_ddh_up = t;
            g_ddh_up_fd = fd;
            syscall(SYS_fcntl, fd, F_SETFD, FD_CLOEXEC);
            ddh_keep(fd);
            continue;
        }
        if (t != MAP_FAILED) munmap(t, sizeof(struct ddh_table));
        syscall(SYS_close, fd);
    }
}

// The worker's binder fds (DDH_WORKER_PID names the worker's process). Only that process may hold
// them: a child holding a copy keeps the worker alive in the system's eyes after its program exits.
static int g_ddh_binder[8];
static int g_ddh_nbinder;
static pid_t g_ddh_worker;

static void ddh_unkeep(int fd) {
    for (int i = 0; i < g_kept_fd_count; i++) {
        if (g_kept_fds[i] != fd) continue;
        memmove(&g_kept_fds[i], &g_kept_fds[i + 1], (size_t)(g_kept_fd_count - i - 1) * sizeof(int));
        g_kept_fd_count--;
        return;
    }
}

static void ddh_keep(int fd) {
    if (g_kept_fd_count >= DDNATV_MAX_KEPT_FDS || is_kept_fd(fd)) return;
    int i = g_kept_fd_count;
    while (i > 0 && g_kept_fds[i - 1] > fd) {
        g_kept_fds[i] = g_kept_fds[i - 1];
        i--;
    }
    g_kept_fds[i] = fd;
    g_kept_fd_count++;
}

static void ddh_drop_worker_binder(void) {
    if (g_ddh_nbinder == 0 || getpid() == g_ddh_worker) return;
    for (int i = 0; i < g_ddh_nbinder; i++) {
        ddh_unkeep(g_ddh_binder[i]);
        syscall(SYS_close, g_ddh_binder[i]);
    }
    g_ddh_nbinder = 0;
    g_kept_from_cloexec = 0;
}

static void ddh_find_worker_binder(void) {
    const char *worker = getenv("DDH_WORKER_PID");
    if (!worker) return;
    g_ddh_worker = (pid_t)atoi(worker);
    char path[64], target[32];
    for (int fd = 3; fd < 4096 && g_ddh_nbinder < 8; fd++) {
        snprintf(path, sizeof(path), "/proc/self/fd/%d", fd);
        long len = syscall(SYS_readlinkat, AT_FDCWD, path, target, sizeof(target) - 1);
        if (len <= 0) continue;
        target[len] = 0;
        if (strstr(target, "binder")) g_ddh_binder[g_ddh_nbinder++] = fd;
    }
    if (getpid() == g_ddh_worker) {
        // The program closing every fd it did not open (gamescope does) would end the worker.
        for (int i = 0; i < g_ddh_nbinder; i++) ddh_keep(g_ddh_binder[i]);
        if (g_ddh_nbinder > 0) g_kept_from_cloexec = 1;
    }
    ddh_drop_worker_binder();
}

// In the program a worker runs for a handed-off child (DDH_TABLE "FD:SLOT:PARENT"): its exit status
// goes into the parent's table here; the app reports it from Android's exit record only when the
// program never got to exit() or _exit() (a signal).
static struct ddh_table *g_ddh_report;
static int g_ddh_report_slot = -1;
static pid_t g_ddh_report_parent;

// The line of /proc/PID/cgroup that names the app's per-process cgroup ("...:/uid_U/pid_P").
static int ddh_cgroup_line(pid_t pid, const char *want, char *out, size_t size) {
    char path[48], buf[512];
    snprintf(path, sizeof(path), "/proc/%d/cgroup", (int)pid);
    int fd = (int)syscall(SYS_openat, AT_FDCWD, path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return 0;
    long n = syscall(SYS_read, fd, buf, sizeof(buf) - 1);
    syscall(SYS_close, fd);
    if (n <= 0) return 0;
    buf[n] = 0;
    if (want) return strstr(buf, want) != NULL;
    char mine[32];
    snprintf(mine, sizeof(mine), "/pid_%d\n", (int)pid);
    char *at = strstr(buf, mine);
    if (!at) return 0;
    char *start = at;
    while (start > buf && start[-1] != '\n') start--;
    size_t len = (size_t)(at - start) + strlen(mine);
    if (len + 1 > size) return 0;
    memcpy(out, start, len);
    out[len] = 0;
    return 1;
}

// A live process (not a zombie) in the cgroup that line names.
static int ddh_cgroup_member(pid_t pid, const char *line) {
    char path[48], stat[256];
    snprintf(path, sizeof(path), "/proc/%d/stat", (int)pid);
    int fd = (int)syscall(SYS_openat, AT_FDCWD, path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return 0;
    long n = syscall(SYS_read, fd, stat, sizeof(stat) - 1);
    syscall(SYS_close, fd);
    if (n <= 0) return 0;
    stat[n] = 0;
    char *paren = strrchr(stat, ')');
    if (!paren || paren[1] != ' ' || paren[2] == 'Z' || paren[2] == 'X') return 0;
    return ddh_cgroup_line(pid, line, NULL, 0);
}

static pid_t ddh_find_cgroup_member(const char *line, pid_t self) {
    int dir = (int)syscall(SYS_openat, AT_FDCWD, "/proc", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (dir < 0) return 0;
    char entries[8192];
    pid_t found = 0;
    long n;
    while (!found && (n = syscall(SYS_getdents64, dir, entries, sizeof(entries))) > 0) {
        for (long off = 0; off < n && !found;) {
            unsigned short reclen;
            memcpy(&reclen, entries + off + 16, sizeof(reclen));
            const char *name = entries + off + 19;
            off += reclen ? reclen : n;
            if (name[0] < '1' || name[0] > '9') continue;
            pid_t pid = (pid_t)atoi(name);
            if (pid != self && ddh_cgroup_member(pid, line)) found = pid;
        }
    }
    syscall(SYS_close, dir);
    return found;
}

static void ddh_linger_note(const char *what, long value) {
    const char *debug = getenv("DD_HANDOFF_DEBUG");
    if (!debug) return;
    char text[160];
    int len = snprintf(text, sizeof(text), "ddnatv handoff: %d linger: %s %ld\n", (int)getpid(), what, value);
    if (len > 0) ddh_log(debug, text, (size_t)len);
}

// Android kills every process in a worker's cgroup when the worker's own process ends: a program's
// daemon (wineserver forks it and exits) or a job it left running would die with it. So the program's
// process stays, as if it had exited (no fds but the worker's binder, no signals), until they are gone.
static void ddh_linger(int flush) {
    char line[160];
    pid_t self = getpid();
    // Not with other threads, which would go on running the program's code meanwhile.
    int dir = (int)syscall(SYS_openat, AT_FDCWD, "/proc/self/task", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (dir < 0) return;
    char entries[512];
    int threads = 0;
    long n = syscall(SYS_getdents64, dir, entries, sizeof(entries));
    for (long off = 0; off < n;) {
        unsigned short reclen;
        memcpy(&reclen, entries + off + 16, sizeof(reclen));
        threads += entries[off + 19] >= '1' && entries[off + 19] <= '9';
        off += reclen ? reclen : n;
    }
    syscall(SYS_close, dir);
    if (threads != 1) {
        ddh_linger_note("threads", threads);
        return;
    }
    if (!ddh_cgroup_line(self, NULL, line, sizeof(line))) {
        ddh_linger_note("no cgroup line", 0);
        return;
    }
    pid_t member = ddh_find_cgroup_member(line, self);
    ddh_linger_note("cgroup member", member);
    if (member <= 0) return;
    if (flush) fflush(NULL);
    sigset_t all;
    sigfillset(&all);
    sigprocmask(SIG_SETMASK, &all, NULL);
    close_range(0, ~0U, 0);
    while (member > 0) {
        while (syscall(SYS_wait4, -1, NULL, WNOHANG, NULL) > 0) {
        }
        struct timespec second = {1, 0};
        nanosleep(&second, NULL);
        if (!ddh_cgroup_member(member, line)) member = ddh_find_cgroup_member(line, self);
    }
}

// From exit() (on_exit, arg 1) or _exit() (arg NULL) in a worker's own process.
// Only a handed-off child lingers: the app waits for the death of the programs it starts itself
// (the session's root) to tear everything down.
static void ddh_report_exit(int status, void *from_exit) {
    if (!g_ddh_report || getpid() != g_ddh_worker) return;
    struct ddh_entry *e = &g_ddh_report->e[g_ddh_report_slot];
    e->status = (status & 0xff) << 8;
    __atomic_store_n(&e->state, DDH_EXITED, __ATOMIC_RELEASE);
    if (g_ddh_report_parent > 0) syscall(SYS_kill, g_ddh_report_parent, SIGCHLD);
    ddh_linger(from_exit != NULL);
}

// A program that leaves with _exit() skips on_exit(): a daemon's first process does, once it has
// forked the daemon (wineserver), and its parent waits for that status.
__attribute__((noreturn)) static void ddh_real_exit(const char *name, int status) {
    void (*real)(int) = (void (*)(int))dlsym(RTLD_NEXT, name);
    if (real) real(status);
    syscall(SYS_exit_group, status);
    __builtin_unreachable();
}

void _exit(int status) {
    ddh_report_exit(status, NULL);
    ddh_real_exit("_exit", status);
}

void _Exit(int status) {
    ddh_report_exit(status, NULL);
    ddh_real_exit("_Exit", status);
}

static void ddh_setup_report(void) {
    const char *spec = getenv("DDH_TABLE");
    if (!spec || g_ddh_worker != getpid()) return;
    int fd = -1, slot = -1, parent = 0;
    if (sscanf(spec, "%d:%d:%d", &fd, &slot, &parent) != 3 || slot < 0 || slot >= DDH_SLOTS) return;
    struct ddh_table *t = mmap(NULL, sizeof(struct ddh_table), PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    if (t == MAP_FAILED || t->magic != DDH_MAGIC) return;
    g_ddh_report = t;
    g_ddh_report_slot = slot;
    g_ddh_report_parent = parent;
    on_exit(ddh_report_exit, (void *)1);
}

// In a program a worker started: the state its forked parent had, sent along with the exec.
static void ddh_apply_inherited(void) {
    const char *ign = getenv("DDH_SIGIGN");
    if (ign) {
        unsigned long mask = strtoul(ign, NULL, 10);
        for (int sig = 1; sig < 32; sig++) {
            if (sig == SIGKILL || sig == SIGSTOP) continue;
            struct sigaction sa;
            if (sigaction(sig, NULL, &sa) != 0) continue;
            int want = (mask >> (sig - 1)) & 1;
            if (want && sa.sa_handler != SIG_IGN) signal(sig, SIG_IGN);
            else if (!want && sa.sa_handler == SIG_IGN) signal(sig, SIG_DFL);
        }
        unsetenv("DDH_SIGIGN");
    }
    const char *nofile = getenv("DDH_NOFILE");
    if (nofile) {
        struct rlimit lim;
        if (getrlimit(RLIMIT_NOFILE, &lim) == 0) {
            rlim_t want = (rlim_t)strtoull(nofile, NULL, 10);
            lim.rlim_cur = want < lim.rlim_max ? want : lim.rlim_max;
            setrlimit(RLIMIT_NOFILE, &lim);
        }
        unsetenv("DDH_NOFILE");
    }
    const char *mask = getenv("DDH_UMASK");
    if (mask) {
        umask((mode_t)strtoul(mask, NULL, 10));
        unsetenv("DDH_UMASK");
    }
}

__attribute__((constructor)) static void ddh_init(void) {
    ddh_apply_inherited();
    ddh_find_worker_binder();
    ddh_setup_report();
    ddh_find_parent_table();
    ddh_prepare(0);
}

static int ddh_has_arg(char *const argv[], const char *prefix, size_t len) {
    for (int i = 1; argv && argv[i]; i++) {
        if (strncmp(argv[i], prefix, len) == 0) return 1;
    }
    return 0;
}

// An entry is a basename, or "name+prefix" for that program only when an argument starts with prefix.
static int ddh_listed(const char *list, const char *name, char *const argv[]) {
    if (!list || !name || !*name) return 0;
    if (strcmp(list, "*") == 0) return 1;
    size_t n = strlen(name);
    for (const char *p = list; *p;) {
        const char *end = strchr(p, ':');
        size_t len = end ? (size_t)(end - p) : strlen(p);
        const char *plus = memchr(p, '+', len);
        size_t base = plus ? (size_t)(plus - p) : len;
        if (base == n && strncmp(p, name, n) == 0 &&
            (!plus || ddh_has_arg(argv, plus + 1, len - base - 1))) return 1;
        if (!end) break;
        p = end + 1;
    }
    return 0;
}

static const char *ddh_base(const char *path) {
    const char *s = path ? strrchr(path, '/') : NULL;
    return s ? s + 1 : path;
}

struct ddh_buf {
    char *p;
    size_t len, cap;
};

static void ddh_put(struct ddh_buf *b, const char *s, size_t n) {
    if (b->p && b->len + n <= b->cap) memcpy(b->p + b->len, s, n);
    b->len += n;
}

static void ddh_puts(struct ddh_buf *b, const char *s) { ddh_put(b, s, strlen(s)); }

static void ddh_putint(struct ddh_buf *b, long v) {
    char tmp[24];
    int i = sizeof(tmp);
    int neg = v < 0;
    unsigned long u = neg ? (unsigned long)-v : (unsigned long)v;
    do {
        tmp[--i] = (char)('0' + u % 10);
        u /= 10;
    } while (u);
    if (neg) tmp[--i] = '-';
    ddh_put(b, tmp + i, sizeof(tmp) - i);
}

static void ddh_putstr(struct ddh_buf *b, const char *s) {
    static const char hex[] = "0123456789abcdef";
    ddh_put(b, "\"", 1);
    for (const unsigned char *c = (const unsigned char *)s; *c; c++) {
        if (*c == '"' || *c == '\\') {
            char esc[2] = {'\\', (char)*c};
            ddh_put(b, esc, 2);
        } else if (*c < 0x20) {
            char esc[6] = {'\\', 'u', '0', '0', hex[*c >> 4], hex[*c & 15]};
            ddh_put(b, esc, 6);
        } else {
            ddh_put(b, (const char *)c, 1);
        }
    }
    ddh_put(b, "\"", 1);
}

static void ddh_putarray(struct ddh_buf *b, char *const v[]) {
    ddh_put(b, "[", 1);
    for (int i = 0; v && v[i]; i++) {
        if (i) ddh_put(b, ",", 1);
        ddh_putstr(b, v[i]);
    }
    ddh_put(b, "]", 1);
}

#define DDH_MAX_FDS 240
#define DDH_FDS_PER_CHUNK 60

// The fds the program would have inherited: every open fd without FD_CLOEXEC.
static int ddh_open_fds(int *fds, int max) {
    int n = 0;
    int dir = (int)syscall(SYS_openat, AT_FDCWD, "/proc/self/fd", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (dir < 0) return -1;
    char buf[4096];
    for (;;) {
        long got = syscall(SYS_getdents64, dir, buf, sizeof(buf));
        if (got <= 0) break;
        for (long off = 0; off < got;) {
            struct {
                uint64_t ino;
                int64_t off;
                unsigned short reclen;
                unsigned char type;
                char name[];
            } *d = (void *)(buf + off);
            off += d->reclen;
            if (d->name[0] < '0' || d->name[0] > '9') continue;
            int fd = 0;
            for (const char *c = d->name; *c; c++) fd = fd * 10 + (*c - '0');
            if (fd == dir) continue;
            int flags = (int)syscall(SYS_fcntl, fd, F_GETFD);
            if (flags < 0 || (flags & FD_CLOEXEC)) continue;
            // A worker's binder fd, inherited from the worker this process runs in: it keeps that
            // worker alive and must not take the place of the new worker's own.
            char link[64], target[32];
            snprintf(link, sizeof(link), "/proc/self/fd/%d", fd);
            long tlen = syscall(SYS_readlinkat, AT_FDCWD, link, target, sizeof(target) - 1);
            if (tlen > 0) {
                target[tlen] = 0;
                if (strstr(target, "binder")) continue;
            }
            if (n == max) {
                syscall(SYS_close, dir);
                return -1;
            }
            fds[n++] = fd;
        }
    }
    syscall(SYS_close, dir);
    return n;
}

// The interpreter a script names on its "#!" line ("/usr/bin/env NAME" gives NAME), into buf.
static const char *ddh_interpreter(const char *path, char *buf, size_t size) {
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return NULL;
    long n = read(fd, buf, size - 1);
    close(fd);
    if (n < 3 || buf[0] != '#' || buf[1] != '!') return NULL;
    buf[n] = 0;
    char *p = buf + 2;
    while (*p == ' ' || *p == '\t') p++;
    char *end = p;
    while (*end && *end != ' ' && *end != '\t' && *end != '\n') end++;
    char *word = NULL;
    if (*end == ' ' || *end == '\t') {
        word = end + 1;
        while (*word == ' ' || *word == '\t') word++;
    }
    *end = 0;
    const char *name = ddh_base(p);
    if (strcmp(name, "env") == 0 && word && *word) {
        end = word;
        while (*end && *end != ' ' && *end != '\t' && *end != '\n') end++;
        *end = 0;
        return word;
    }
    return name;
}

static int ddh_program_listed(const char *list, const char *filename, char *const argv[]) {
    char shebang[256];
    const char *interp;
    // "python3 SCRIPT" and "sh SCRIPT" are matched by the script's name too
    const char *script = argv && argv[0] && argv[1] && argv[1][0] != '-' ? ddh_base(argv[1]) : NULL;
    return ddh_listed(list, ddh_base(filename), argv) || (argv && ddh_listed(list, ddh_base(argv[0]), argv)) ||
           (script && ddh_listed(list, script, argv)) ||
           ((interp = ddh_interpreter(filename, shebang, sizeof(shebang))) && ddh_listed(list, interp, argv));
}

// Whether a posix_spawn() of this program should be handed off: glibc's spawn child makes the exec
// system call itself, past ddh_try(), so such a spawn is started with fork() and execve() instead.
static int ddh_spawn_wanted(const char *path, char *const argv[], char *const envp[]) {
    const char *list = ddh_env(envp, "DD_HANDOFF");
    return path && ddh_env(envp, "DD_SPAWN") && list && ddh_program_listed(list, path, argv);
}

// In a forked child at execve(): hands the program to a worker and exits, or returns to exec it here.
static void ddh_debug(char *const envp[], const char *what, const char *filename) {
    const char *debug = ddh_env(envp, "DD_HANDOFF_DEBUG");
    if (!debug) return;
    char line[512];
    struct ddh_buf b = {line, 0, sizeof(line) - 1};
    ddh_puts(&b, "ddnatv handoff: ");
    ddh_putint(&b, getpid());
    ddh_puts(&b, " ");
    ddh_puts(&b, what);
    ddh_puts(&b, " ");
    ddh_puts(&b, filename ? filename : "?");
    ddh_puts(&b, "\n");
    if (b.len > b.cap) b.len = b.cap;
    ddh_log(debug, line, b.len);
}

static void ddh_try(const char *filename, char *const argv[], char *const envp[]) {
    struct ddh_table *table;
    int table_fd;
    if (g_ddh && getpid() != g_ddh_owner) {
        table = g_ddh;
        table_fd = g_ddh_fd;
    } else if (g_ddh_up && getppid() == g_ddh_up->pad[0]) {
        table = g_ddh_up;
        table_fd = g_ddh_up_fd;
    } else {
        if (ddh_env(envp, "DD_HANDOFF_DEBUG")) {
            char why[160];
            snprintf(why, sizeof(why), "not a child its parent can track (table %d owner %d up %d up-owner %d ppid %d):",
                     g_ddh != NULL, (int)g_ddh_owner, g_ddh_up != NULL, g_ddh_up ? g_ddh_up->pad[0] : 0, (int)getppid());
            ddh_debug(envp, why, filename);
        }
        return;
    }
    const char *sock = ddh_env(envp, "DD_SPAWN");
    const char *list = ddh_env(envp, "DD_HANDOFF");
    if (!sock || !list) return;
    if (access(filename, X_OK) != 0) {
        ddh_debug(envp, "not executable here:", filename);
        return;
    }
    if (!ddh_is_table(table_fd, table)) {
        ddh_debug(envp, "table fd lost:", filename);
        return;
    }
    // Whatever this exec becomes can still hand off its own exec (a wrapper, a script).
    syscall(SYS_fcntl, table_fd, F_SETFD, 0);
    if (!ddh_program_listed(list, filename, argv)) {
        ddh_debug(envp, "not listed:", filename);
        return;
    }

    int fds[DDH_MAX_FDS + 1];
    int nfds = ddh_open_fds(fds, DDH_MAX_FDS);
    if (nfds < 0) return;

    int slot = -1;
    for (int i = 0; i < DDH_SLOTS; i++) {
        int expected = DDH_FREE;
        if (__atomic_compare_exchange_n(&table->e[i].state, &expected, DDH_CLAIMED, 0, __ATOMIC_ACQ_REL, __ATOMIC_RELAXED)) {
            slot = i;
            break;
        }
    }
    if (slot < 0) return;
    struct ddh_entry *e = &table->e[slot];
    pid_t me = getpid(), parent = getppid();
    char cwd[PATH_MAX];
    if (!getcwd(cwd, sizeof(cwd))) strcpy(cwd, "/");
    // What the program would have inherited here and a worker does not have: ignored signals, the
    // open-files limit, the umask (ddh_apply_inherited() restores them in the program).
    unsigned long ignored = 0;
    for (int sig = 1; sig < 32; sig++) {
        struct sigaction sa;
        if (sigaction(sig, NULL, &sa) == 0 && sa.sa_handler == SIG_IGN) ignored |= 1UL << (sig - 1);
    }
    struct rlimit nofile = {0, 0};
    getrlimit(RLIMIT_NOFILE, &nofile);
    mode_t mask = umask(0);
    umask(mask);

    struct ddh_buf b = {0};
    for (int pass = 0; pass < 2; pass++) {
        b.len = 0;
        ddh_puts(&b, "{\"file\":");
        ddh_putstr(&b, filename);
        ddh_puts(&b, ",\"argv\":");
        ddh_putarray(&b, argv);
        ddh_puts(&b, ",\"envlist\":");
        ddh_putarray(&b, envp);
        ddh_puts(&b, ",\"cwd\":");
        ddh_putstr(&b, cwd);
        ddh_puts(&b, ",\"fds\":[");
        for (int i = 0; i < nfds; i++) {
            if (i) ddh_put(&b, ",", 1);
            ddh_putint(&b, fds[i]);
        }
        ddh_puts(&b, "],\"fdchunks\":");
        ddh_putint(&b, DDH_FDS_PER_CHUNK);
        ddh_puts(&b, ",\"table\":");
        ddh_putint(&b, nfds);
        ddh_puts(&b, ",\"slot\":");
        ddh_putint(&b, slot);
        ddh_puts(&b, ",\"parent\":");
        ddh_putint(&b, parent);
        ddh_puts(&b, ",\"child\":");
        ddh_putint(&b, me);
        ddh_puts(&b, ",\"sigign\":");
        ddh_putint(&b, (long)ignored);
        ddh_puts(&b, ",\"nofile\":");
        ddh_putint(&b, (long)nofile.rlim_cur);
        ddh_puts(&b, ",\"umask\":");
        ddh_putint(&b, (long)mask);
        ddh_puts(&b, "}");
        if (pass == 0) {
            b.cap = b.len + 4;
            b.p = mmap(NULL, b.cap, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
            if (b.p == MAP_FAILED) {
                __atomic_store_n(&e->state, DDH_FREE, __ATOMIC_RELEASE);
                return;
            }
        }
    }
    fds[nfds] = table_fd;

    long pid = -1;
    int s = (int)syscall(SYS_socket, AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    struct sockaddr_un addr = {.sun_family = AF_UNIX};
    size_t namelen = strlen(sock);
    if (s >= 0 && namelen + 1 < sizeof(addr.sun_path)) {
        memcpy(addr.sun_path + 1, sock, namelen);
        socklen_t alen = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + namelen);
        if (syscall(SYS_connect, s, &addr, alen) == 0) {
            uint32_t len = (uint32_t)b.len;
            unsigned char header[4] = {(unsigned char)(len >> 24), (unsigned char)(len >> 16), (unsigned char)(len >> 8), (unsigned char)len};
            long sent = 0;
            while (sent < (long)(b.len + 4)) {
                long more = sent < 4 ? syscall(SYS_sendto, s, header + sent, 4 - sent, MSG_NOSIGNAL, NULL, 0)
                                     : syscall(SYS_sendto, s, b.p + (sent - 4), b.len + 4 - sent, MSG_NOSIGNAL, NULL, 0);
                if (more <= 0) break;
                sent += more;
            }
            // Then the fds, at most DDH_FDS_PER_CHUNK with each byte: the app's LocalSocket takes
            // no more than 64 fds in one read.
            for (int at = 0; sent == (long)(b.len + 4) && at < nfds + 1; at += DDH_FDS_PER_CHUNK) {
                int count = nfds + 1 - at < DDH_FDS_PER_CHUNK ? nfds + 1 - at : DDH_FDS_PER_CHUNK;
                char marker = 'F';
                struct iovec iov = {&marker, 1};
                char control[CMSG_SPACE(sizeof(int) * DDH_FDS_PER_CHUNK)];
                struct msghdr msg = {.msg_iov = &iov, .msg_iovlen = 1, .msg_control = control,
                                     .msg_controllen = CMSG_SPACE(sizeof(int) * count)};
                struct cmsghdr *cm = CMSG_FIRSTHDR(&msg);
                cm->cmsg_level = SOL_SOCKET;
                cm->cmsg_type = SCM_RIGHTS;
                cm->cmsg_len = CMSG_LEN(sizeof(int) * count);
                memcpy(CMSG_DATA(cm), fds + at, sizeof(int) * count);
                if (syscall(SYS_sendmsg, s, &msg, MSG_NOSIGNAL) != 1) sent = -1;
            }
            if (sent == (long)(b.len + 4)) {
                char reply[64];
                size_t got = 0;
                while (got < sizeof(reply) - 1) {
                    long r = syscall(SYS_read, s, reply + got, 1);
                    if (r <= 0 || reply[got] == '\n') break;
                    got++;
                }
                reply[got] = 0;
                if (strncmp(reply, "pid ", 4) == 0) pid = atol(reply + 4);
            }
        }
    }
    if (s >= 0) syscall(SYS_close, s);
    munmap(b.p, b.cap);
    if (pid <= 0) {
        __atomic_store_n(&e->state, DDH_FREE, __ATOMIC_RELEASE);
        ddh_debug(envp, "spawner refused", filename);
        return;
    }
    e->child = me;
    e->parent = parent;
    e->real = (int32_t)pid;
    e->reaped = 0;
    int expected = DDH_CLAIMED;
    // the app may already have written DDH_EXITED for a program that ended at once
    __atomic_compare_exchange_n(&e->state, &expected, DDH_HANDED, 0, __ATOMIC_ACQ_REL, __ATOMIC_RELAXED);
    syscall(SYS_exit_group, 0);
}

// ---- the parent's side -----------------------------------------------------------------------

static struct ddh_entry *ddh_find(pid_t child) {
    if (!g_ddh || child <= 0) return NULL;
    pid_t me = getpid();
    for (int i = 0; i < DDH_SLOTS; i++) {
        struct ddh_entry *e = &g_ddh->e[i];
        int st = __atomic_load_n(&e->state, __ATOMIC_ACQUIRE);
        if ((st == DDH_HANDED || st == DDH_EXITED) && e->parent == me && e->child == child) return e;
    }
    return NULL;
}

static int ddh_any_live(void) {
    if (!g_ddh) return 0;
    pid_t me = getpid();
    for (int i = 0; i < DDH_SLOTS; i++) {
        struct ddh_entry *e = &g_ddh->e[i];
        int st = __atomic_load_n(&e->state, __ATOMIC_ACQUIRE);
        if ((st == DDH_HANDED || st == DDH_EXITED) && e->parent == me) return 1;
    }
    return 0;
}

static struct ddh_entry *ddh_exited(void) {
    if (!g_ddh) return NULL;
    pid_t me = getpid();
    for (int i = 0; i < DDH_SLOTS; i++) {
        struct ddh_entry *e = &g_ddh->e[i];
        if (__atomic_load_n(&e->state, __ATOMIC_ACQUIRE) == DDH_EXITED && e->parent == me) return e;
    }
    return NULL;
}

// A worker that is gone with no status from the app (it crashed with the app): reported as killed.
static void ddh_check_gone(void) {
    if (!g_ddh) return;
    pid_t me = getpid();
    for (int i = 0; i < DDH_SLOTS; i++) {
        struct ddh_entry *e = &g_ddh->e[i];
        if (__atomic_load_n(&e->state, __ATOMIC_ACQUIRE) != DDH_HANDED || e->parent != me) continue;
        if (syscall(SYS_kill, e->real, 0) == 0 || errno != ESRCH) continue;
        if (e->pad[0] == 0) {
            e->pad[0] = 1;  // give the app time to write the status
            continue;
        }
        if (++e->pad[0] > 50) {
            e->status = SIGKILL;
            __atomic_store_n(&e->state, DDH_EXITED, __ATOMIC_RELEASE);
        }
    }
}

static void ddh_release(struct ddh_entry *e, int *status) {
    if (status) *status = e->status;
    memset(e, 0, offsetof(struct ddh_entry, state));
    e->status = 0;
    e->reaped = 0;
    e->pad[0] = 0;
    __atomic_store_n(&e->state, DDH_FREE, __ATOMIC_RELEASE);
}

typedef pid_t (*ddh_wait4_f)(pid_t, int *, int, struct rusage *);

static ddh_wait4_f ddh_real_wait4(void) {
    static ddh_wait4_f f;
    if (!f) f = (ddh_wait4_f)dlsym(RTLD_NEXT, "wait4");
    return f;
}

static int ddh_nap(void) {
    struct timespec ts = {0, 20 * 1000 * 1000};
    return nanosleep(&ts, NULL);
}

static pid_t ddh_wait(pid_t pid, int *status, int options, struct rusage *ru) {
    ddh_wait4_f real = ddh_real_wait4();
    if (!real) {
        errno = ENOSYS;
        return -1;
    }
    if (!g_ddh) return real(pid, status, options, ru);
    for (;;) {
        ddh_check_gone();
        struct ddh_entry *e = pid > 0 ? ddh_find(pid) : NULL;
        if (pid > 0 && e) {
            if (!e->reaped && real(pid, NULL, WNOHANG, NULL) == pid) e->reaped = 1;
            if (__atomic_load_n(&e->state, __ATOMIC_ACQUIRE) == DDH_EXITED) {
                if (ru) memset(ru, 0, sizeof(*ru));
                ddh_release(e, status);
                return pid;
            }
            if (options & WNOHANG) return 0;
            if (ddh_nap() != 0) return -1;
            continue;
        }
        if (pid <= 0 && (e = ddh_exited())) {
            pid_t child = e->child;
            if (!e->reaped && real(child, NULL, WNOHANG, NULL) == child) e->reaped = 1;
            if (ru) memset(ru, 0, sizeof(*ru));
            ddh_release(e, status);
            return child;
        }
        int live = pid <= 0 && ddh_any_live();
        int wstatus = 0;
        pid_t r = real(pid, &wstatus, live ? options | WNOHANG : options, ru);
        if (r > 0) {
            struct ddh_entry *h = ddh_find(r);
            if (h && !h->reaped) {
                h->reaped = 1;  // the forked child that handed its program off
                continue;
            }
            if (status) *status = wstatus;
            return r;
        }
        if (!live) return r;
        if (r < 0 && errno != ECHILD) return r;
        if (options & WNOHANG) return 0;
        if (ddh_nap() != 0) return -1;
    }
}

pid_t wait4(pid_t pid, int *status, int options, struct rusage *ru) { return ddh_wait(pid, status, options, ru); }
pid_t waitpid(pid_t pid, int *status, int options) { return ddh_wait(pid, status, options, NULL); }
pid_t wait(int *status) { return ddh_wait(-1, status, 0, NULL); }
pid_t wait3(int *status, int options, struct rusage *ru) { return ddh_wait(-1, status, options, ru); }

int waitid(idtype_t idtype, id_t id, siginfo_t *info, int options) {
    typedef int (*f_t)(idtype_t, id_t, siginfo_t *, int);
    static f_t real;
    if (!real) real = (f_t)dlsym(RTLD_NEXT, "waitid");
    if (!real) {
        errno = ENOSYS;
        return -1;
    }
    if (!g_ddh || !(options & WEXITED) || (idtype != P_PID && idtype != P_ALL) || !ddh_any_live())
        return real(idtype, id, info, options);
    // Only exits are asked for here: answer through ddh_wait and describe the result as waitid does.
    int status = 0;
    pid_t r = ddh_wait(idtype == P_PID ? (pid_t)id : -1, &status, options & WNOHANG, NULL);
    if (r < 0) return -1;
    if (info) {
        memset(info, 0, sizeof(*info));
        if (r > 0) {
            info->si_signo = SIGCHLD;
            info->si_pid = r;
            info->si_code = WIFEXITED(status) ? CLD_EXITED : CLD_KILLED;
            info->si_status = WIFEXITED(status) ? WEXITSTATUS(status) : WTERMSIG(status);
        }
    }
    return 0;
}

// Whether pgid is zygote's process group, which every process of the app is in until it starts a
// session of its own: signalling it would reach the app itself.
static int ddh_app_group(pid_t pgid) {
    char path[32], name[16] = "";
    snprintf(path, sizeof(path), "/proc/%d/cmdline", (int)pgid);
    int fd = (int)syscall(SYS_openat, AT_FDCWD, path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return 0;
    long n = syscall(SYS_read, fd, name, sizeof(name) - 1);
    syscall(SYS_close, fd);
    return n > 0 && strncmp(name, "zygote", 6) == 0;
}

int kill(pid_t pid, int sig) {
    if (g_ddh && pid > 0) {
        struct ddh_entry *e = ddh_find(pid);
        if (e && __atomic_load_n(&e->state, __ATOMIC_ACQUIRE) == DDH_HANDED) pid = e->real;
    }
    // -1 is every process this one may signal, the app's own among them
    if (sig != 0 && (pid == -1 || (pid <= 0 && ddh_app_group(pid == 0 ? getpgrp() : -pid)))) {
        errno = EPERM;
        return -1;
    }
    if (sig != 0 && getenv("DD_HANDOFF_DEBUG")) {
        char line[160], comm[32] = "?";
        int fd = (int)syscall(SYS_openat, AT_FDCWD, "/proc/self/comm", O_RDONLY | O_CLOEXEC);
        if (fd >= 0) {
            long n = syscall(SYS_read, fd, comm, sizeof(comm) - 1);
            if (n > 0) comm[n - 1] = 0;
            syscall(SYS_close, fd);
        }
        int len = snprintf(line, sizeof(line), "ddnatv handoff: %d (%s) kill(%d, %d)\n", (int)getpid(), comm, (int)pid, sig);
        if (len > 0) ddh_log(getenv("DD_HANDOFF_DEBUG"), line, (size_t)len);
    }
    return (int)syscall(SYS_kill, pid, sig);
}
