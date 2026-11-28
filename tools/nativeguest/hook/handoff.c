// Part of libddnatv-hook.so, included by ddnatv-hook.c after exec.c.
//
// Exec handoff. Android counts every process in the app's cgroup against its child-process limit,
// but not the app's own processes (docs/phantom-processes.md). A program a guest starts with
// fork() + execve() can instead run as the main process of one of the app's worker processes: the
// forked child sends the exec to the app (DD_SPAWN, an abstract socket) with its open fds, records
// the worker's pid in a table it shares with its parent, and exits. For the parent nothing changes:
// wait*(), kill() and SIGCHLD treat the pid fork() returned as that program until it exits; its exit
// status reaches the table from the program's hook, or for a signal from the app's report (see
// ddh_check_gone()), and the parent gets SIGCHLD.
//
// Only programs named in DD_HANDOFF (basenames separated by ':', or "*") are handed off. The child's
// side runs where vfork() children run too, so it allocates nothing and writes no globals.

#define DDH_SLOTS 1024
#define DDH_FREE 0
#define DDH_CLAIMED 3
#define DDH_HANDED 1
#define DDH_EXITED 2
#define DDH_TAKEN 4       // its exit is being reported to one waiter
#define DDH_NO_STATUS INT32_MIN
#define DDH_MAGIC 0x6464686f
#define DDH_TABLE_FD 800

struct ddh_entry {
    int32_t child;    // the pid the parent got from fork()
    int32_t real;     // the worker's pid
    int32_t parent;
    int32_t state;
    int32_t status;   // wait status
    int32_t reaped;   // the parent has reaped the forked child itself
    int32_t pad[2];
    // The app's report of a program that died without exit() or _exit() (GuestWorkers.reportExit):
    // the wait status and the child it is for, in one 8-byte write. Only this side changes state.
    int32_t app[2] __attribute__((aligned(8)));
    int64_t child_start;  // the forked child's start time: tells it from a later process given its pid
    int32_t guessed;      // the status is the timeout's guess: set only while the entry is held (TAKEN)
    int32_t pad2;
    // When its worker was first seen gone, with the child that was for: child << 32 | seconds. Set by
    // compare-and-swap only, so a waiter that looked before the slot changed hands cannot overwrite it.
    uint64_t gone;
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
    // non-blocking: a FIFO with no reader must not hold up the call being traced (a kill())
    int fd = (int)syscall(SYS_openat, AT_FDCWD, setting, O_WRONLY | O_APPEND | O_CREAT | O_CLOEXEC | O_NONBLOCK, 0644);
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

// An fd's target that is one of Android's binder devices (directly or through binderfs).
static int ddh_is_binder(const char *target) {
    return strcmp(target, "/dev/binder") == 0 || strcmp(target, "/dev/hwbinder") == 0 ||
           strcmp(target, "/dev/vndbinder") == 0 || strncmp(target, "/dev/binderfs/", 14) == 0;
}

// The table the children write to, in a process of a session that hands programs off (DD_SPAWN).
// One thread makes it; a fork() while another thread is making it goes without (its child runs here).
static int g_ddh_preparing;

// In a forked child: the thread making the table did not come along, so the child may make its own.
static void ddh_forked(void) { __atomic_store_n(&g_ddh_preparing, 0, __ATOMIC_RELAXED); }

static void ddh_prepare(void) {
    if (__atomic_load_n(&g_ddh, __ATOMIC_ACQUIRE) || !getenv("DD_SPAWN")) return;
    if (__atomic_exchange_n(&g_ddh_preparing, 1, __ATOMIC_ACQ_REL)) return;
    if (g_ddh) {
        __atomic_store_n(&g_ddh_preparing, 0, __ATOMIC_RELEASE);
        return;
    }
    int fd = (int)syscall(SYS_memfd_create, "ddnatv-handoff", 1 /* MFD_CLOEXEC */);
    if (fd < 0) {
        ddh_prepare_failed("memfd_create");
        __atomic_store_n(&g_ddh_preparing, 0, __ATOMIC_RELEASE);
        return;
    }
    if (ftruncate(fd, sizeof(struct ddh_table)) != 0) {
        ddh_prepare_failed("ftruncate");
        close(fd);
        __atomic_store_n(&g_ddh_preparing, 0, __ATOMIC_RELEASE);
        return;
    }
    struct ddh_table *p = mmap(NULL, sizeof(struct ddh_table), PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    if (p == MAP_FAILED) {
        ddh_prepare_failed("mmap");
        close(fd);
        __atomic_store_n(&g_ddh_preparing, 0, __ATOMIC_RELEASE);
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
    p->magic = DDH_MAGIC;
    p->pad[0] = getpid();
    // A nonce that tells this table's fd from any other (inode numbers are rewritten by the hardlink emulation)
    if (syscall(SYS_getrandom, &p->pad[1], 2 * sizeof(int32_t), 0) != 2 * (long)sizeof(int32_t)) {
        p->pad[1] = (int32_t)time(NULL);
        p->pad[2] = (int32_t)(intptr_t)p;
    }
    g_ddh_fd = fd;
    g_ddh_owner = getpid();
    __atomic_store_n(&g_ddh, p, __ATOMIC_RELEASE);
    __atomic_store_n(&g_ddh_preparing, 0, __ATOMIC_RELEASE);
}

// Whether fd is still this table: a program may have put a file of its own at that number.
static int ddh_is_table(int fd, const struct ddh_table *t) {
    int32_t head[4];
    if (fd < 0 || syscall(SYS_pread64, fd, head, sizeof(head), 0) != (long)sizeof(head)) return 0;
    return head[0] == t->magic && head[1] == t->pad[0] && head[2] == t->pad[1] && head[3] == t->pad[2];
}

// The open fds from 3 on, ascending (as /proc lists them): how many, at most max; -1 without /proc.
static int ddh_list_fds(int *fds, int max) {
    int dir = (int)syscall(SYS_openat, AT_FDCWD, "/proc/self/fd", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (dir < 0) return -1;
    int n = 0;
    char buf[4096];
    long got;
    while ((got = syscall(SYS_getdents64, dir, buf, sizeof(buf))) > 0) {
        for (long off = 0; off < got;) {
            unsigned short reclen;
            memcpy(&reclen, buf + off + 16, sizeof(reclen));
            const char *name = buf + off + 19;
            off += reclen ? reclen : got;
            if (name[0] < '0' || name[0] > '9') continue;
            int fd = atoi(name);
            if (fd >= 3 && fd != dir && n < max) fds[n++] = fd;
        }
    }
    syscall(SYS_close, dir);
    return n;
}

#define DDH_LISTED_FDS 2048

// A table inherited across exec: the parent's if its owner is still our parent, else closed.
static void ddh_find_parent_table(void) {
    char path[64], target[64];
    pid_t parent = getppid();
    // The table a worker's program reports its exit to (DDH_TABLE), also for what it execs
    int report = -1;
    const char *spec = getenv("DDH_TABLE");
    if (spec && getenv("DDH_WORKER_PID") && atoi(getenv("DDH_WORKER_PID")) == getpid()) report = atoi(spec);
    static int open_fds[DDH_LISTED_FDS];
    int count = ddh_list_fds(open_fds, DDH_LISTED_FDS);
    for (int i = 0; i < count; i++) {
        int fd = open_fds[i];
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
    g_cloexec_kept_count = 0;
}

static void ddh_find_worker_binder(void) {
    const char *worker = getenv("DDH_WORKER_PID");
    if (!worker) return;
    g_ddh_worker = (pid_t)atoi(worker);
    char path[64], target[32];
    static int open_fds[DDH_LISTED_FDS];
    int count = ddh_list_fds(open_fds, DDH_LISTED_FDS);
    for (int i = 0; i < count && g_ddh_nbinder < 8; i++) {
        snprintf(path, sizeof(path), "/proc/self/fd/%d", open_fds[i]);
        long len = syscall(SYS_readlinkat, AT_FDCWD, path, target, sizeof(target) - 1);
        if (len <= 0) continue;
        target[len] = 0;
        if (ddh_is_binder(target)) g_ddh_binder[g_ddh_nbinder++] = open_fds[i];
    }
    if (getpid() == g_ddh_worker) {
        // The program closing every fd it did not open (gamescope does) would end the worker.
        for (int i = 0; i < g_ddh_nbinder; i++) {
            ddh_keep(g_ddh_binder[i]);
            g_cloexec_kept_fds[i] = g_ddh_binder[i];
        }
        g_cloexec_kept_count = g_ddh_nbinder;
    }
    ddh_drop_worker_binder();
}

// In the program a worker runs for a handed-off child (DDH_TABLE "FD:SLOT:PARENT:CHILD"): its exit
// status goes into the parent's table here; the app reports it from Android's exit record only when
// the program never got to exit() or _exit() (a signal).
static struct ddh_table *g_ddh_report;
static int g_ddh_report_slot = -1;
static pid_t g_ddh_report_parent;
static pid_t g_ddh_report_child;

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

static void ddh_register_group(void);
static void ddh_unregister_group(void);
static pid_t ddh_logical_pgrp(void);
static pid_t ddh_logical_group(pid_t real);
static long ddh_start_time(pid_t pid);

// From exit() (on_exit, arg 1) or _exit() (arg NULL) in a worker's own process.
// Only a handed-off child lingers: the app waits for the death of the programs it starts itself
// (the session's root) to tear everything down.
static void ddh_report_exit(int status, void *from_exit) {
    if (!g_ddh_report || getpid() != g_ddh_worker) return;
    // exit() flushes stdio after its on_exit() handlers: before the parent can see the exit here
    if (from_exit) fflush(NULL);
    struct ddh_entry *e = &g_ddh_report->e[g_ddh_report_slot];
    // Still this program's entry: the parent frees it when the forked child dies before handing off
    // (and a later child may have it by now).
    // From the state read (the forked child may still move it from CLAIMED to HANDED): a parent
    // that freed the entry meanwhile keeps it freed.
    int st = __atomic_load_n(&e->state, __ATOMIC_ACQUIRE);
    for (int waits = 0;;) {
        // TAKEN for a moment: the parent's check of a gone worker holds the entry (ddh_check_gone()) and
        // gives it back as it was when it is not the child it looked at. Wait that out, briefly.
        if (st == DDH_TAKEN && waits++ < 200) {
            struct timespec ts = {0, 1000 * 1000};
            syscall(SYS_nanosleep, &ts, NULL);
            st = __atomic_load_n(&e->state, __ATOMIC_ACQUIRE);
            continue;
        }
        if ((st != DDH_HANDED && st != DDH_CLAIMED) || e->parent != g_ddh_report_parent ||
            (g_ddh_report_child > 0 && e->child != g_ddh_report_child))
            break;
        e->status = (status & 0xff) << 8;
        if (__atomic_compare_exchange_n(&e->state, &st, DDH_EXITED, 0, __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE)) {
            if (g_ddh_report_parent > 0) syscall(SYS_kill, g_ddh_report_parent, SIGCHLD);
            break;
        }
    }
    ddh_linger(from_exit != NULL);
    ddh_unregister_group();
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
    int fd = -1, slot = -1, parent = 0, child = 0;
    if (sscanf(spec, "%d:%d:%d:%d", &fd, &slot, &parent, &child) < 3 || slot < 0 || slot >= DDH_SLOTS) return;
    struct ddh_table *t = mmap(NULL, sizeof(struct ddh_table), PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    if (t == MAP_FAILED || t->magic != DDH_MAGIC) return;
    g_ddh_report = t;
    g_ddh_report_slot = slot;
    g_ddh_report_parent = parent;
    g_ddh_report_child = child;
    on_exit(ddh_report_exit, (void *)1);
    ddh_register_group();
}

// In a program a worker started: the state its forked parent had, sent along with the exec. Not in
// the app's shell that execs it (DDH_WRAPPER): bash changes its signal mask (it unblocks SIGCHLD),
// and its exec keeps bash's.
static void ddh_apply_inherited(void) {
    if (getenv("DDH_WRAPPER")) return;
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
    // The blocked signals: the forked parent's, or none for a program the app started. A worker
    // execs from Android's main thread, which blocks SIGQUIT, SIGUSR1 and SIGPIPE.
    const char *blocked = getenv("DDH_SIGMASK");
    if (blocked) {
        unsigned long long bits = strtoull(blocked, NULL, 10);
        sigset_t set;
        sigemptyset(&set);
        for (int sig = 1; sig <= 64 && sig < NSIG; sig++) {
            if ((bits >> (sig - 1)) & 1) sigaddset(&set, sig);
        }
        sigprocmask(SIG_SETMASK, &set, NULL);
        unsetenv("DDH_SIGMASK");
    }
}

__attribute__((constructor)) static void ddh_init(void) {
    ddh_apply_inherited();
    ddh_find_worker_binder();
    ddh_setup_report();
    ddh_find_parent_table();
    ddh_prepare();
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
    if (b->p && b->len < b->cap) memcpy(b->p + b->len, s, n < b->cap - b->len ? n : b->cap - b->len);
    b->len += n;
}

static void ddh_puts(struct ddh_buf *b, const char *s) { ddh_put(b, s, strlen(s)); }

static void ddh_putint(struct ddh_buf *b, long v) {
    char tmp[24];
    int i = sizeof(tmp);
    int neg = v < 0;
    unsigned long u = neg ? 0UL - (unsigned long)v : (unsigned long)v;  // no overflow for LONG_MIN
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

// The fds the program would have inherited: every open fd without FD_CLOEXEC, but skip (the table).
static int ddh_open_fds(int *fds, int max, int skip) {
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
            if (fd == dir || fd == skip) continue;
            int flags = (int)syscall(SYS_fcntl, fd, F_GETFD);
            if (flags < 0 || (flags & FD_CLOEXEC)) continue;
            // A worker's binder fd, inherited from the worker this process runs in: it keeps that
            // worker alive and must not take the place of the new worker's own.
            char link[64], target[32];
            snprintf(link, sizeof(link), "/proc/self/fd/%d", fd);
            long tlen = syscall(SYS_readlinkat, AT_FDCWD, link, target, sizeof(target) - 1);
            if (tlen > 0) {
                target[tlen] = 0;
                if (ddh_is_binder(target)) continue;
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
// In a forked child: system calls only, as the hook's own open() and close() take locks another
// thread of the parent may have held at fork().
static const char *ddh_interpreter(const char *path, char *buf, size_t size) {
    char real[PATH_MAX];
    // non-blocking, and only a regular file: the path could name a FIFO
    int fd = (int)call_orig_syscall(SYS_openat, (unsigned long)AT_FDCWD, (unsigned long)rewrite_path(path, real, sizeof(real)),
                                    O_RDONLY | O_CLOEXEC | O_NONBLOCK, 0, 0, 0);
    if (fd < 0) return NULL;
    struct stat st;
    if (call_orig_syscall(SYS_fstat, (unsigned long)fd, (unsigned long)&st, 0, 0, 0, 0) != 0 || !S_ISREG(st.st_mode)) {
        syscall(SYS_close, fd);
        return NULL;
    }
    long n = call_orig_syscall(SYS_read, (unsigned long)fd, (unsigned long)buf, size - 1, 0, 0, 0);
    syscall(SYS_close, fd);
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

// Whether an exec of path would get as far as the program: an ELF file, or a script whose "#!"
// interpreter is there to run. Anything else fails in the exec, which must happen here then, so that
// the caller (posix_spawn() above all) gets its error rather than a worker's exit status.
static int ddh_startable(const char *path) {
    char real[PATH_MAX], head[256];
    int fd = (int)call_orig_syscall(SYS_openat, (unsigned long)AT_FDCWD, (unsigned long)rewrite_path(path, real, sizeof(real)),
                                    O_RDONLY | O_CLOEXEC | O_NONBLOCK, 0, 0, 0);
    if (fd < 0) return 0;
    long n = call_orig_syscall(SYS_read, (unsigned long)fd, (unsigned long)head, sizeof(head) - 1, 0, 0, 0);
    syscall(SYS_close, fd);
    if (n >= 4 && memcmp(head, "\177ELF", 4) == 0) return 1;
    if (n < 3 || head[0] != '#' || head[1] != '!') return 0;
    head[n] = 0;
    char *p = head + 2;
    while (*p == ' ' || *p == '\t') p++;
    char *end = p;
    while (*end && *end != ' ' && *end != '\t' && *end != '\n' && *end != '\r') end++;
    *end = 0;
    if (*p != '/') return 0;
    return call_orig_syscall(SYS_faccessat, (unsigned long)AT_FDCWD, (unsigned long)rewrite_path(p, real, sizeof(real)), X_OK, 0, 0, 0) == 0;
}

// Whether s is valid UTF-8: the app reads the request as UTF-8, and any other byte would reach the
// program changed (a file name in Latin-1, say), where a local exec passes it on as it is.
static int ddh_utf8(const char *s) {
    const unsigned char *p = (const unsigned char *)s;
    while (*p) {
        int n = *p < 0x80 ? 0 : (*p & 0xe0) == 0xc0 ? 1 : (*p & 0xf0) == 0xe0 ? 2 : (*p & 0xf8) == 0xf0 ? 3 : -1;
        if (n < 0 || (n == 1 && *p < 0xc2) || (n == 3 && *p > 0xf4)) return 0;
        unsigned int cp = n == 0 ? *p : *p & (0x3f >> n);
        for (int i = 1; i <= n; i++) {
            if ((p[i] & 0xc0) != 0x80) return 0;
            cp = (cp << 6) | (p[i] & 0x3f);
        }
        if ((n == 2 && (cp < 0x800 || (cp >= 0xd800 && cp <= 0xdfff))) || (n == 3 && (cp < 0x10000 || cp > 0x10ffff))) return 0;
        p += n + 1;
    }
    return 1;
}

static int ddh_all_utf8(char *const v[]) {
    for (int i = 0; v && v[i]; i++) {
        if (!ddh_utf8(v[i])) return 0;
    }
    return 1;
}

static int ddh_program_listed(const char *list, const char *filename, char *const argv[]) {
    char shebang[256];
    const char *interp;
    // "python3 SCRIPT" and "sh SCRIPT" are matched by the script's name too (and only an
    // interpreter's: "pidof wineserver" is not wineserver)
    const char *prog = ddh_base(filename);
    int interpreter = strcmp(prog, "sh") == 0 || strcmp(prog, "bash") == 0 || strcmp(prog, "dash") == 0 ||
                      strncmp(prog, "python", 6) == 0 || strncmp(prog, "perl", 4) == 0;
    const char *script = interpreter && argv && argv[0] && argv[1] && argv[1][0] != '-' ? ddh_base(argv[1]) : NULL;
    return ddh_listed(list, ddh_base(filename), argv) || (argv && ddh_listed(list, ddh_base(argv[0]), argv)) ||
           (script && ddh_listed(list, script, argv)) ||
           ((interp = ddh_interpreter(filename, shebang, sizeof(shebang))) && ddh_listed(list, interp, argv));
}

// Whether a posix_spawn() of this program should be handed off: glibc's spawn child makes the exec
// system call itself, past ddh_try(), so such a spawn is started with fork() and execve() instead.
static int ddh_spawn_wanted(const char *path, char *const argv[], char *const envp[]) {
    // the hook's exec ends these at once, its posix_spawn() runs them (exec.c): spawned as before
    static const char *const exec_stubs[] = {"mandb", "mandb.real", "update-mime-database", "update-desktop-database",
                                             "install-info", "install-sgmlcatalog"};
    for (size_t i = 0; path && i < sizeof(exec_stubs) / sizeof(exec_stubs[0]); i++) {
        if (strcmp(ddh_base(path), exec_stubs[i]) == 0) return 0;
    }
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
    // one look: the fields are read only once the table is seen published
    struct ddh_table *own = __atomic_load_n(&g_ddh, __ATOMIC_ACQUIRE);
    pid_t own_owner = own ? g_ddh_owner : 0;
    if (own && getpid() != own_owner) {
        table = own;
        table_fd = g_ddh_fd;
    } else if (g_ddh_up && getppid() == g_ddh_up->pad[0]) {
        table = g_ddh_up;
        table_fd = g_ddh_up_fd;
    } else {
        if (ddh_env(envp, "DD_HANDOFF_DEBUG")) {
            char why[160];
            snprintf(why, sizeof(why), "not a child its parent can track (table %d owner %d up %d up-owner %d ppid %d):",
                     own != NULL, (int)own_owner, g_ddh_up != NULL, g_ddh_up ? g_ddh_up->pad[0] : 0, (int)getppid());
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
    if (!ddh_startable(filename)) {
        ddh_debug(envp, "would not start:", filename);
        return;
    }
    if (!ddh_utf8(filename) || !ddh_all_utf8(argv) || !ddh_all_utf8(envp)) {
        ddh_debug(envp, "not all UTF-8:", filename);
        return;
    }

    int fds[DDH_MAX_FDS + 1];
    int nfds = ddh_open_fds(fds, DDH_MAX_FDS, table_fd);
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
    // Before the request: the app checks them before it reports an exit into this entry, which a
    // later handoff may have taken over by then.
    e->child = me;
    e->parent = parent;
    e->real = 0;
    // One look at the group: waitpid(0) and waitpid(-GROUP) pick the child by it, and the request
    // names the group it stands for from it (the parent can move this child meanwhile).
    pid_t real_group = getpgrp();
    e->pad[1] = real_group;
    e->status = DDH_NO_STATUS;
    __atomic_store_n((uint64_t *)e->app, 0, __ATOMIC_RELAXED);
    e->child_start = ddh_start_time(me);
    char cwd[PATH_MAX];
    // The guest's name for it, else the kernel's (the worker's cd takes either). A directory with no
    // name (removed) cannot be sent: the exec stays here, in it.
    if (!getcwd(cwd, sizeof(cwd))) {
        int err = errno;
        long n = call_orig_syscall(SYS_getcwd, (unsigned long)cwd, sizeof(cwd), 0, 0, 0, 0);
        // Outside this process's root (a program in Steam's runtime container): the rest is the path
        // from Android's root, where the worker runs
        static const char unreachable[] = "(unreachable)/";
        if (n > 0 && strncmp(cwd, unreachable, sizeof(unreachable) - 1) == 0)
            memmove(cwd, cwd + sizeof(unreachable) - 2, strlen(cwd + sizeof(unreachable) - 2) + 1);
        if (n <= 0 || cwd[0] != '/') {
            __atomic_store_n(&e->state, DDH_FREE, __ATOMIC_RELEASE);
            char why[160];
            snprintf(why, sizeof(why), "no name for the working directory (errno %d, then %ld/%d \"%.40s\"):", err, n,
                     n < 0 ? errno : 0, n > 0 ? cwd : "");
            ddh_debug(envp, why, filename);
            return;
        }
        char why[64];
        snprintf(why, sizeof(why), "kernel name for the working directory (errno %d):", err);
        ddh_debug(envp, why, cwd);
    }
    if (!ddh_utf8(cwd)) {
        __atomic_store_n(&e->state, DDH_FREE, __ATOMIC_RELEASE);
        ddh_debug(envp, "working directory not UTF-8:", cwd);
        return;
    }
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
    sigset_t blocked;
    pid_t anchor = getenv("DDH_WORKER_PID") ? (pid_t)atoi(getenv("DDH_WORKER_PID")) : parent;
    long parent_start = ddh_start_time(parent), anchor_start = ddh_start_time(anchor);
    pid_t group = ddh_logical_group(real_group);
    unsigned long blocked_bits = 0;
    if (sigprocmask(SIG_BLOCK, NULL, &blocked) == 0) {
        for (int sig = 1; sig <= 64 && sig < NSIG; sig++) {
            if (sigismember(&blocked, sig) == 1) blocked_bits |= 1UL << (sig - 1);
        }
    }

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
        ddh_puts(&b, ",\"pgid\":");
        ddh_putint(&b, (long)group);
        ddh_puts(&b, ",\"sigmask\":");
        ddh_putint(&b, (long)blocked_bits);
        ddh_puts(&b, ",\"anchor\":");
        ddh_putint(&b, (long)anchor);
        // their start times now, while both are surely the ones meant (see ddh_logical_parent)
        ddh_puts(&b, ",\"pstart\":");
        ddh_putint(&b, parent_start);
        ddh_puts(&b, ",\"astart\":");
        ddh_putint(&b, anchor_start);
        // the worker runs the program only once this side has its pid (the "go" below)
        ddh_puts(&b, ",\"ack\":true}");
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
                    if (r < 0 && errno == EINTR) continue;  // the program may already be running
                    if (r <= 0 || reply[got] == '\n') break;
                    got++;
                }
                reply[got] = 0;
                if (strncmp(reply, "pid ", 4) == 0) pid = atol(reply + 4);
                // Without it the app does not let the worker run the program, and it runs here.
                if (pid > 0 && syscall(SYS_sendto, s, "go\n", 3, MSG_NOSIGNAL, NULL, 0) != 3) pid = -1;
                // Then the app's last word, once the worker has taken the program on: "ok", or it
                // never runs there and runs here.
                if (pid > 0) {
                    char last[32];
                    size_t have = 0;
                    while (have < sizeof(last) - 1) {
                        long r = syscall(SYS_read, s, last + have, 1);
                        if (r < 0 && errno == EINTR) continue;
                        if (r <= 0 || last[have] == '\n') break;
                        have++;
                    }
                    last[have] = 0;
                    if (strcmp(last, "ok") != 0) pid = -1;
                }
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
    if (!__atomic_load_n(&g_ddh, __ATOMIC_ACQUIRE) || child <= 0) return NULL;
    pid_t me = getpid();
    for (int i = 0; i < DDH_SLOTS; i++) {
        struct ddh_entry *e = &g_ddh->e[i];
        int st = __atomic_load_n(&e->state, __ATOMIC_ACQUIRE);
        if ((st == DDH_HANDED || st == DDH_EXITED || st == DDH_TAKEN) && e->parent == me && e->child == child) return e;
    }
    return NULL;
}

// Takes e's exit for one waiter (EXITED to TAKEN): 0 when another waiter has it.
static int ddh_take(struct ddh_entry *e) {
    int expected = DDH_EXITED;
    return __atomic_compare_exchange_n(&e->state, &expected, DDH_TAKEN, 0, __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE);
}

// Gives a taken exit back, for a later wait (its forked child is not reaped yet).
static void ddh_untake(struct ddh_entry *e) { __atomic_store_n(&e->state, DDH_EXITED, __ATOMIC_RELEASE); }

// Whether a wait for pid (-1: any child, 0: one in our group, -G: one in group G) takes e's child.
static int ddh_selects(const struct ddh_entry *e, pid_t pid) {
    if (pid > 0) return e->child == pid;
    if (pid == -1) return 1;
    return e->pad[1] == (pid == 0 ? getpgrp() : -pid);
}

static void ddh_release(struct ddh_entry *e, int *status);

// A new child of ours got pid: an entry naming it for an earlier process (its forked child reaped
// early, as the wait above does, so the pid was free again) stands for a program the pid no longer
// reaches. The real child owns the pid from now on: kill() and wait() go to it, and that program's
// exit is dropped. Only after the pid space has wrapped around while that program runs. Told apart by
// start time, not by the reaped flag: another thread may have reaped that forked child and not yet
// marked it, and the new child may already have handed off a program of its own under the pid.
// Without both start times (the new child already reaped too) nothing is dropped: an entry of the
// new child's own must not be, and a stale one needs the pid space to wrap besides.
static void ddh_child_created(pid_t pid) {
    struct ddh_table *t = __atomic_load_n(&g_ddh, __ATOMIC_ACQUIRE);
    if (!t || pid <= 0) return;
    pid_t me = getpid();
    long start = -2;  // read once, when an entry names the pid
    for (int i = 0; i < DDH_SLOTS; i++) {
        struct ddh_entry *e = &t->e[i];
        int st = __atomic_load_n(&e->state, __ATOMIC_ACQUIRE);
        if ((st != DDH_HANDED && st != DDH_EXITED) || e->parent != me || e->child != pid) continue;
        if (start == -2) start = ddh_start_time(pid);
        if (e->child_start < 0 || start < 0 || e->child_start == start) continue;
        if (__atomic_compare_exchange_n(&e->state, &st, DDH_TAKEN, 0, __ATOMIC_ACQ_REL, __ATOMIC_RELAXED))
            ddh_release(e, NULL);
    }
}

// Takes e's exit for a wait for pid, checked again once taken: between finding e and taking it,
// another waiter may have released the slot and a later child of ours (or one of another group)
// have exited in it.
static int ddh_take_for(struct ddh_entry *e, pid_t pid) {
    if (!ddh_take(e)) return 0;
    if (e->parent == getpid() && ddh_selects(e, pid)) return 1;
    ddh_untake(e);
    return 0;
}

static int ddh_any_live(pid_t pid) {
    if (!__atomic_load_n(&g_ddh, __ATOMIC_ACQUIRE)) return 0;
    pid_t me = getpid();
    for (int i = 0; i < DDH_SLOTS; i++) {
        struct ddh_entry *e = &g_ddh->e[i];
        int st = __atomic_load_n(&e->state, __ATOMIC_ACQUIRE);
        if ((st == DDH_HANDED || st == DDH_EXITED || st == DDH_TAKEN || st == DDH_CLAIMED) && e->parent == me && ddh_selects(e, pid)) return 1;
    }
    return 0;
}

// The entry of child while it is still handing its program off (its request is with the app).
static struct ddh_entry *ddh_claiming(pid_t child) {
    if (!__atomic_load_n(&g_ddh, __ATOMIC_ACQUIRE) || child <= 0) return NULL;
    pid_t me = getpid();
    for (int i = 0; i < DDH_SLOTS; i++) {
        struct ddh_entry *e = &g_ddh->e[i];
        if (__atomic_load_n(&e->state, __ATOMIC_ACQUIRE) == DDH_CLAIMED && e->parent == me && e->child == child) return e;
    }
    return NULL;
}

// A worker that is gone with no status from the app (it crashed with the app): reported as killed.
// A worker that is gone: its program's end as the app reported it (a signal), else, with no report
// after a while (the app crashed with it), as killed.
static void ddh_check_gone(void) {
    struct ddh_table *t = __atomic_load_n(&g_ddh, __ATOMIC_ACQUIRE);
    if (!t) return;
    pid_t me = getpid();
    for (int i = 0; i < DDH_SLOTS; i++) {
        struct ddh_entry *e = &t->e[i];
        if (__atomic_load_n(&e->state, __ATOMIC_ACQUIRE) != DDH_HANDED || e->parent != me) continue;
        pid_t child = e->child;
        if (syscall(SYS_kill, e->real, 0) == 0 || errno != ESRCH) continue;
        int32_t report[2];
        uint64_t both = __atomic_load_n((uint64_t *)e->app, __ATOMIC_ACQUIRE);
        memcpy(report, &both, sizeof(report));
        int32_t status = SIGKILL;
        if (report[1] == child && report[1] != 0) {
            status = report[0];
        } else {
            // The app looks the status up for up to a second after the worker's death: wait longer.
            struct timespec now;
            clock_gettime(CLOCK_MONOTONIC, &now);
            // The time is kept with the child it is for: a slot taken over since by another child
            // starts its own count.
            uint64_t seen = __atomic_load_n(&e->gone, __ATOMIC_ACQUIRE);
            if ((uint32_t)(seen >> 32) != (uint32_t)child) {
                uint64_t mark = (uint64_t)(uint32_t)child << 32 | (uint32_t)(now.tv_sec + 1);
                __atomic_compare_exchange_n(&e->gone, &seen, mark, 0, __ATOMIC_ACQ_REL, __ATOMIC_RELAXED);
                continue;
            }
            if (now.tv_sec + 1 - (int64_t)(uint32_t)seen < 3) continue;
        }
        // Only this side moves HANDED to EXITED, the entry held meanwhile and still the child looked at:
        // a waiter may have finished with it and a new handoff taken the slot since the look above.
        int expected = DDH_HANDED;
        if (!__atomic_compare_exchange_n(&e->state, &expected, DDH_TAKEN, 0, __ATOMIC_ACQ_REL, __ATOMIC_RELAXED)) continue;
        if (e->child != child || e->parent != me) {
            __atomic_store_n(&e->state, DDH_HANDED, __ATOMIC_RELEASE);
            continue;
        }
        // a status the program wrote itself is kept
        int32_t none = DDH_NO_STATUS;
        if (__atomic_compare_exchange_n(&e->status, &none, status, 0, __ATOMIC_ACQ_REL, __ATOMIC_RELAXED) &&
            report[1] != child)
            e->guessed = 1;  // an app report that comes before the exit is taken wins (ddh_status)
        __atomic_store_n(&e->state, DDH_EXITED, __ATOMIC_RELEASE);
    }
}

static int ddh_status(const struct ddh_entry *e) {
    if (!e->guessed) return e->status;
    // SIGKILL was the timeout's guess (no report then): the app's report, if it has come since
    int32_t report[2];
    uint64_t both = __atomic_load_n((const uint64_t *)e->app, __ATOMIC_ACQUIRE);
    memcpy(report, &both, sizeof(report));
    return report[1] != 0 && report[1] == e->child ? report[0] : e->status;
}

static void ddh_release(struct ddh_entry *e, int *status) {
    if (status) *status = ddh_status(e);
    memset(e, 0, offsetof(struct ddh_entry, state));
    e->status = 0;
    e->reaped = 0;
    e->pad[0] = 0;
    e->pad[1] = 0;
    e->guessed = 0;
    __atomic_store_n(&e->gone, 0, __ATOMIC_RELAXED);
    __atomic_store_n((uint64_t *)e->app, 0, __ATOMIC_RELAXED);
    __atomic_store_n(&e->state, DDH_FREE, __ATOMIC_RELEASE);
}

typedef pid_t (*ddh_wait4_f)(pid_t, int *, int, struct rusage *);

static ddh_wait4_f ddh_real_wait4(void) {
    static ddh_wait4_f f;
    if (!f) f = (ddh_wait4_f)dlsym(RTLD_NEXT, "wait4");
    return f;
}

// A wait interrupted by a signal: as the real call, which a handler installed with SA_RESTART restarts.
// The app's and the program's SIGCHLD are the usual ones.
// The forked child of an entry whose program has exited: 1 once reaped (or already gone), 0 when it is
// still exiting and WNOHANG was asked for, or the wait was interrupted.
static int ddh_reap_shim(ddh_wait4_f real, pid_t child, int options) {
    pid_t r = real(child, NULL, options & WNOHANG, NULL);
    return r == child || (r < 0 && errno == ECHILD);
}

// The program of an exited entry a wait for pid takes: its pid and status, the entry released (or
// left for the next wait when keep), or 0 when none is ready. Every exited entry, not just the first:
// one whose forked child is still exiting is skipped (the caller's real wait reaps that exit, or a
// later pass does) and the next one may be ready.
static pid_t ddh_reap_exited(ddh_wait4_f real, pid_t pid, int *status, int keep) {
    struct ddh_table *t = __atomic_load_n(&g_ddh, __ATOMIC_ACQUIRE);
    for (int i = 0; t && i < DDH_SLOTS; i++) {
        struct ddh_entry *e = &t->e[i];
        if (__atomic_load_n(&e->state, __ATOMIC_ACQUIRE) != DDH_EXITED || !ddh_take_for(e, pid)) continue;
        pid_t child = e->child;
        if (!e->reaped && !ddh_reap_shim(real, child, WNOHANG)) {
            ddh_untake(e);
            continue;
        }
        e->reaped = 1;
        if (keep) {
            if (status) *status = ddh_status(e);
            ddh_untake(e);
        } else {
            ddh_release(e, status);
        }
        return child;
    }
    return 0;
}

// 20 ms, or -1 (EINTR) when a signal's handler interrupted it. The read of a timerfd restarts, as a
// wait does, after a handler installed with SA_RESTART; nanosleep() never does.
static int ddh_nap(void) {
    int fd = (int)syscall(SYS_timerfd_create, CLOCK_MONOTONIC, O_CLOEXEC);
    if (fd < 0) {
        struct timespec ts = {0, 20 * 1000 * 1000};
        nanosleep(&ts, NULL);
        return 0;
    }
    struct itimerspec when = {{0, 0}, {0, 20 * 1000 * 1000}};
    uint64_t ticks;
    long r = syscall(SYS_timerfd_settime, fd, 0, &when, NULL) == 0 ? syscall(SYS_read, fd, &ticks, sizeof(ticks)) : 0;
    int err = errno;
    syscall(SYS_close, fd);
    if (r < 0 && err == EINTR) {
        errno = EINTR;
        return -1;
    }
    return 0;
}

static pid_t ddh_wait(pid_t pid, int *status, int options, struct rusage *ru) {
    ddh_wait4_f real = ddh_real_wait4();
    if (!real) {
        errno = ENOSYS;
        return -1;
    }
    if (!__atomic_load_n(&g_ddh, __ATOMIC_ACQUIRE)) return real(pid, status, options, ru);
    // After a nap a signal interrupted, one more look before EINTR: the signal may be the SIGCHLD
    // that reports an exit.
    int interrupted = 0;
    for (;;) {
        ddh_check_gone();
        struct ddh_entry *e = pid > 0 ? ddh_find(pid) : NULL;
        if (pid > 0 && e) {
            if (!e->reaped && real(pid, NULL, WNOHANG, NULL) == pid) e->reaped = 1;
            if (ddh_take_for(e, pid)) {
                // the forked child is between its last write and its exit: reap it first, or a later
                // wait() would report its pid a second time (not blocking for WNOHANG: then later)
                if (!e->reaped && !ddh_reap_shim(real, pid, options)) {
                    ddh_untake(e);
                    return (options & WNOHANG) ? 0 : -1;
                }
                if (ru) memset(ru, 0, sizeof(*ru));
                ddh_release(e, status);
                return pid;
            }
            if (options & WNOHANG) return 0;
            if (interrupted) {
                errno = EINTR;
                return -1;
            }
            interrupted = ddh_nap() != 0;
            continue;
        }
        pid_t done = pid <= 0 ? ddh_reap_exited(real, pid, status, 0) : 0;
        if (done > 0) {
            if (ru) memset(ru, 0, sizeof(*ru));
            return done;
        }
        int live = pid <= 0 && ddh_any_live(pid);
        int wstatus = 0;
        pid_t r = real(pid, &wstatus, live ? options | WNOHANG : options, ru);
        if (r > 0) {
            struct ddh_entry *h = ddh_find(r);
            if (h && !h->reaped) {
                h->reaped = 1;  // the forked child that handed its program off
                continue;
            }
            // A forked child that died while handing its program off (a signal): its entry is done.
            if (!h && (h = ddh_claiming(r))) ddh_release(h, NULL);
            if (status) *status = wstatus;
            return r;
        }
        if (!live) return r;
        if (r < 0 && errno != ECHILD) return r;
        if (options & WNOHANG) return 0;
        if (interrupted) {
            errno = EINTR;
            return -1;
        }
        interrupted = ddh_nap() != 0;
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
    // A wait without exits never takes a forked child's exit for the program's.
    if (!__atomic_load_n(&g_ddh, __ATOMIC_ACQUIRE) || !(options & WEXITED) ||
        (idtype != P_PID && idtype != P_ALL && idtype != P_PGID))
        return real(idtype, id, info, options);
    if ((idtype == P_PID && (pid_t)id <= 0) || (idtype == P_PGID && (pid_t)id < 0)) {
        errno = EINVAL;
        return -1;
    }
    // As a waitpid() selector: a pid, -1 for any child, 0 for our group, -G for group G
    pid_t which = idtype == P_PID ? (pid_t)id : idtype == P_PGID ? (id == 0 ? 0 : -(pid_t)id) : -1;
    ddh_wait4_f w4 = ddh_real_wait4();
    // Each event is looked at (WNOWAIT) before it is taken: the forked child of a handed-off program
    // exits once the program has started, and that exit is reaped here and never reported. Any other
    // event is then taken from that child alone, so the caller gets the kernel's siginfo for it.
    int interrupted = 0;
    for (;;) {
        ddh_check_gone();
        int status = 0;
        pid_t r = ddh_reap_exited(w4, which, &status, options & WNOWAIT);
        if (r > 0) {
            if (info) {
                memset(info, 0, sizeof(*info));
                info->si_signo = SIGCHLD;
                info->si_pid = r;
                info->si_uid = getuid();
                if (WIFEXITED(status)) {
                    info->si_code = CLD_EXITED;
                    info->si_status = WEXITSTATUS(status);
                } else {
                    info->si_code = WCOREDUMP(status) ? CLD_DUMPED : CLD_KILLED;
                    info->si_status = WTERMSIG(status);
                }
            }
            return 0;
        }
        int live = ddh_any_live(which);
        siginfo_t seen;
        memset(&seen, 0, sizeof(seen));
        if (real(idtype, id, &seen, options | WNOWAIT | (live ? WNOHANG : 0)) < 0) {
            if (!live || errno != ECHILD) return -1;
        } else if (seen.si_pid > 0) {
            pid_t child = seen.si_pid;
            int exited = seen.si_code == CLD_EXITED || seen.si_code == CLD_KILLED || seen.si_code == CLD_DUMPED;
            struct ddh_entry *h = exited ? ddh_find(child) : NULL;
            if (h && !h->reaped) {
                if (w4 && w4(child, NULL, WNOHANG, NULL) == child) h->reaped = 1;  // the forked child that handed its program off
                continue;
            }
            if (options & WNOWAIT) {
                if (info) *info = seen;
                return 0;
            }
            siginfo_t got;
            memset(&got, 0, sizeof(got));
            if (real(P_PID, (id_t)child, &got, options | WNOHANG) < 0 || got.si_pid != child) continue;  // another waiter took it
            // A forked child that died while handing its program off (a signal): its entry is done.
            int died = got.si_code == CLD_EXITED || got.si_code == CLD_KILLED || got.si_code == CLD_DUMPED;
            if (died && !ddh_find(child) && (h = ddh_claiming(child))) ddh_release(h, NULL);
            if (info) *info = got;
            return 0;
        }
        if (!live || (options & WNOHANG)) {
            if (info) memset(info, 0, sizeof(*info));
            return 0;
        }
        if (interrupted) {
            errno = EINTR;
            return -1;
        }
        interrupted = ddh_nap() != 0;
    }
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

// A handed-off program runs in a session of its own (the worker's setsid()), out of the process
// group its forked parent's child was in, so a signal to that group (Steam ends a game with one)
// would miss it. Each one is listed here as GROUP.PID.START, GROUP being the group it stands for,
// and a signal to GROUP also goes to the group the program leads.
#define DDH_GROUPS "/run/droiddeck-handoff-groups"

static long ddh_start_time(pid_t pid) {
    char path[48], stat[512];
    snprintf(path, sizeof(path), "/proc/%d/stat", (int)pid);
    int fd = (int)syscall(SYS_openat, AT_FDCWD, path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return -1;
    long n = syscall(SYS_read, fd, stat, sizeof(stat) - 1);
    syscall(SYS_close, fd);
    if (n <= 0) return -1;
    stat[n] = 0;
    char *p = strrchr(stat, ')');
    // starttime is field 22; after ") " comes field 3
    for (int field = 2; p && field < 22; field++) p = strchr(p + 1, ' ');
    return p ? atol(p + 1) : -1;
}

// The group a process stands for: its worker's (DDH_PGID) while it is in the group its worker leads.
static pid_t ddh_logical_group(pid_t real) {
    const char *worker = getenv("DDH_WORKER_PID"), *group = getenv("DDH_PGID");
    if (worker && group && real == (pid_t)atoi(worker)) return (pid_t)atoi(group);
    return real;
}

static pid_t ddh_logical_pgrp(void) { return ddh_logical_group(getpgrp()); }

static char g_ddh_group_entry[96];
static int g_ddh_parent_entry;

static int ddh_open_groups(int create) {
    char dir[PATH_MAX];
    const char *real_dir = rewrite_path(DDH_GROUPS, dir, sizeof(dir));
    if (create) call_orig_syscall(SYS_mkdirat, (unsigned long)AT_FDCWD, (unsigned long)real_dir, 0755, 0, 0, 0);
    return (int)call_orig_syscall(SYS_openat, (unsigned long)AT_FDCWD, (unsigned long)real_dir,
                                  O_RDONLY | O_DIRECTORY | O_CLOEXEC, 0, 0, 0);
}

// Also P<PID>, "START PARENT ANCHOR": the process that forked this program's child, the parent it
// has in place of the worker's (see ddh_signal_reaper_tree()), and the worker's program that process
// descends from (DDH_ANCHOR), for when it is gone: Wine starts a process through a fork that exits.
static void ddh_register_group(void) {
    if (getpid() != g_ddh_worker) return;
    int dir = ddh_open_groups(1);
    if (dir < 0) return;
    long start = ddh_start_time(getpid());
    char name[96], text[96];
    const char *group = getenv("DDH_PGID");
    if (group) {
        snprintf(name, sizeof(name), "%d.%d.%ld", atoi(group), (int)getpid(), start);
        int fd = (int)call_orig_syscall(SYS_openat, (unsigned long)dir, (unsigned long)name, O_WRONLY | O_CREAT | O_CLOEXEC, 0644, 0, 0);
        if (fd >= 0) {
            syscall(SYS_close, fd);
            snprintf(g_ddh_group_entry, sizeof(g_ddh_group_entry), "%s", name);
        }
    }
    if (g_ddh_report_parent > 0) {
        snprintf(name, sizeof(name), "P%d", (int)getpid());
        // with their start times: a pid followed later may have been reused by then
        // the start times the request recorded (DDH_PSTART, DDH_ASTART): one read now could be a
        // reused pid's, the forking process having exited meanwhile
        const char *anchor_env = getenv("DDH_ANCHOR"), *pstart = getenv("DDH_PSTART"), *astart = getenv("DDH_ASTART");
        pid_t anchor = anchor_env ? (pid_t)atoi(anchor_env) : g_ddh_report_parent;
        int len = snprintf(text, sizeof(text), "%ld %d %d %ld %ld\n", start, (int)g_ddh_report_parent, (int)anchor,
                           pstart ? atol(pstart) : ddh_start_time(g_ddh_report_parent),
                           astart ? atol(astart) : ddh_start_time(anchor));
        int fd = (int)call_orig_syscall(SYS_openat, (unsigned long)dir, (unsigned long)name,
                                        O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644, 0, 0);
        if (fd >= 0) {
            call_orig_syscall(SYS_write, (unsigned long)fd, (unsigned long)text, (unsigned long)len, 0, 0, 0);
            syscall(SYS_close, fd);
            g_ddh_parent_entry = 1;
        }
    }
    syscall(SYS_close, dir);
}

static void ddh_unregister_group(void) {
    if ((!g_ddh_group_entry[0] && !g_ddh_parent_entry) || getpid() != g_ddh_worker) return;
    int dir = ddh_open_groups(0);
    if (dir < 0) return;
    if (g_ddh_group_entry[0]) call_orig_syscall(SYS_unlinkat, (unsigned long)dir, (unsigned long)g_ddh_group_entry, 0, 0, 0, 0);
    if (g_ddh_parent_entry) {
        char name[24];
        snprintf(name, sizeof(name), "P%d", (int)getpid());
        call_orig_syscall(SYS_unlinkat, (unsigned long)dir, (unsigned long)name, 0, 0, 0, 0);
    }
    syscall(SYS_close, dir);
    g_ddh_group_entry[0] = 0;
    g_ddh_parent_entry = 0;
}

// The process that forked pid's child (or, gone, the worker's program it descended from), when pid
// is a handed-off program; else 0.
static pid_t ddh_logical_parent(pid_t pid) {
    char path[PATH_MAX], text[96];
    const char *real_dir = rewrite_path(DDH_GROUPS, path, sizeof(path));
    if (real_dir != path) snprintf(path, sizeof(path), "%s", real_dir);
    size_t used = strlen(path);
    if (used + 16 >= sizeof(path)) return 0;
    snprintf(path + used, sizeof(path) - used, "/P%d", (int)pid);
    int fd = (int)call_orig_syscall(SYS_openat, (unsigned long)AT_FDCWD, (unsigned long)path, O_RDONLY | O_CLOEXEC, 0, 0, 0);
    if (fd < 0) return 0;
    long n = call_orig_syscall(SYS_read, (unsigned long)fd, (unsigned long)text, sizeof(text) - 1, 0, 0, 0);
    syscall(SYS_close, fd);
    if (n <= 0) return 0;
    text[n] = 0;
    long start = 0, parent_start = -1, anchor_start = -1;
    int parent = 0, anchor = 0;
    if (sscanf(text, "%ld %d %d %ld %ld", &start, &parent, &anchor, &parent_start, &anchor_start) < 5 ||
        ddh_start_time(pid) != start)
        return 0;
    // -1: it was gone already when the program started (Wine forks through one that exits at once)
    if (parent > 1 && parent_start >= 0 && ddh_start_time(parent) == parent_start) return (pid_t)parent;
    if (anchor > 1 && anchor_start >= 0 && ddh_start_time(anchor) == anchor_start) return (pid_t)anchor;
    return 0;
}

// Signals the groups of the programs standing for group; returns how many took it.
static int ddh_signal_stand_ins(pid_t group, int sig, pid_t done) {
    char dir[PATH_MAX];
    const char *real_dir = rewrite_path(DDH_GROUPS, dir, sizeof(dir));
    int fd = (int)call_orig_syscall(SYS_openat, (unsigned long)AT_FDCWD, (unsigned long)real_dir, O_RDONLY | O_DIRECTORY | O_CLOEXEC, 0, 0, 0);
    if (fd < 0) return 0;
    char prefix[24], entries[4096];
    int plen = snprintf(prefix, sizeof(prefix), "%d.", (int)group);
    int sent = 0;
    long n;
    while ((n = syscall(SYS_getdents64, fd, entries, sizeof(entries))) > 0) {
        for (long off = 0; off < n;) {
            unsigned short reclen;
            memcpy(&reclen, entries + off + 16, sizeof(reclen));
            const char *name = entries + off + 19;
            off += reclen ? reclen : n;
            if (strncmp(name, prefix, (size_t)plen) != 0) continue;
            int real = 0;
            long start = 0;
            if (sscanf(name + plen, "%d.%ld", &real, &start) != 2 || real <= 1) continue;
            if (ddh_start_time(real) != start) {
                call_orig_syscall(SYS_unlinkat, (unsigned long)fd, (unsigned long)name, 0, 0, 0, 0);  // gone, or its pid reused
                continue;
            }
            if (real == done || ddh_app_group(real)) continue;
            if (syscall(SYS_kill, -real, sig) == 0) sent++;
        }
    }
    syscall(SYS_close, fd);
    return sent;
}

static int ddh_kill_group(pid_t pid, int sig) {
    pid_t group = pid == 0 ? ddh_logical_pgrp() : -pid;
    pid_t own = pid == 0 ? getpgrp() : -pid;
    // The caller's own group last: the signal may end the caller.
    long ret = -1;
    int err = ESRCH;
    if (group != own && !ddh_app_group(group) && syscall(SYS_kill, -group, sig) == 0) ret = 0;
    if (ddh_signal_stand_ins(group, sig, own) > 0) ret = 0;
    if (syscall(SYS_kill, pid, sig) == 0) ret = 0;
    else err = errno;
    if (ret != 0) errno = err;
    return (int)ret;
}

// The kernel's parent of pid, from /proc/<pid>/stat; 0 when unknown.
static pid_t ddh_proc_parent(pid_t pid) {
    char path[48], stat[512];
    snprintf(path, sizeof(path), "/proc/%d/stat", (int)pid);
    int fd = (int)syscall(SYS_openat, AT_FDCWD, path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return 0;
    long n = syscall(SYS_read, fd, stat, sizeof(stat) - 1);
    syscall(SYS_close, fd);
    if (n <= 0) return 0;
    stat[n] = 0;
    char *p = strrchr(stat, ')');
    return p && p[1] == ' ' && p[2] && p[3] == ' ' ? (pid_t)atoi(p + 4) : 0;
}

// Steam's "reaper SteamLaunch AppId=N -- ...", which runs a game's launch.
static int ddh_is_steam_reaper(pid_t pid) {
    char path[48], cmd[128];
    snprintf(path, sizeof(path), "/proc/%d/cmdline", (int)pid);
    int fd = (int)syscall(SYS_openat, AT_FDCWD, path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return 0;
    long n = syscall(SYS_read, fd, cmd, sizeof(cmd) - 1);
    syscall(SYS_close, fd);
    if (n <= 0) return 0;
    cmd[n] = 0;
    size_t first = strlen(cmd);
    return (size_t)n > first + 1 && strcmp(ddh_base(cmd), "reaper") == 0 && strcmp(cmd + first + 1, "SteamLaunch") == 0;
}

static int ddh_descends_from(pid_t pid, pid_t ancestor) {
    for (int depth = 0; pid > 1 && depth < 64; depth++) {
        if (pid == ancestor) return 1;
        pid_t up = ddh_logical_parent(pid);
        pid = up > 0 ? up : ddh_proc_parent(pid);
    }
    return 0;
}

// Steam ends a game by signalling its launch's reaper, which ends every process under it; the
// handed-off ones are not under it, so they (and the groups they lead) get the signal here. All are
// found before any is signalled: one that exits takes its place in the chain of parents with it.
// SIGINT goes as SIGTERM: Wine takes SIGINT for a console's Ctrl+C, which windowed programs ignore.
static void ddh_signal_reaper_tree(pid_t reaper, int sig) {
    int dir = ddh_open_groups(0);
    if (dir < 0) return;
    pid_t found[256];
    int count = 0;
    char entries[4096];
    long n;
    while ((n = syscall(SYS_getdents64, dir, entries, sizeof(entries))) > 0) {
        for (long off = 0; off < n;) {
            unsigned short reclen;
            memcpy(&reclen, entries + off + 16, sizeof(reclen));
            const char *name = entries + off + 19;
            off += reclen ? reclen : n;
            if (name[0] != 'P' || name[1] < '1' || name[1] > '9') continue;
            pid_t pid = (pid_t)atoi(name + 1);
            if (count < 256 && ddh_logical_parent(pid) > 0 && ddh_descends_from(pid, reaper)) found[count++] = pid;
        }
    }
    syscall(SYS_close, dir);
    for (int i = 0; i < count; i++) syscall(SYS_kill, -found[i], sig == SIGINT ? SIGTERM : sig);
}

int killpg(int pgrp, int sig) {
    if (pgrp < 0) {
        errno = EINVAL;
        return -1;
    }
    return kill(-pgrp, sig);
}

int kill(pid_t pid, int sig) {
    if (__atomic_load_n(&g_ddh, __ATOMIC_ACQUIRE) && pid > 0) {
        // A child still handing its program off: the signal is for the program, which the forked
        // child (stopped or killed by it) could no longer hand off or account for. Up to 3 s for the
        // app's answer; a cold worker start takes well under one.
        struct ddh_entry *claim = ddh_claiming(pid);
        for (int i = 0; claim && i < 300 && __atomic_load_n(&claim->state, __ATOMIC_ACQUIRE) == DDH_CLAIMED; i++) {
            struct timespec ts = {0, 10 * 1000 * 1000};
            nanosleep(&ts, NULL);
        }
        struct ddh_entry *e = ddh_find(pid);
        if (e && __atomic_load_n(&e->state, __ATOMIC_ACQUIRE) == DDH_HANDED) pid = e->real;
    }
    // -1 is every process this one may signal, the app's own among them
    if (sig != 0 && (pid == -1 || (pid <= 0 && ddh_app_group(pid == 0 ? getpgrp() : -pid)))) {
        errno = EPERM;
        return -1;
    }
    if (pid < -1 || pid == 0) {
        if (sig != 0 && getenv("DD_HANDOFF_DEBUG")) {
            char line[96];
            int len = snprintf(line, sizeof(line), "ddnatv handoff: %d kill group %d (stands for %d), %d\n", (int)getpid(),
                               pid == 0 ? (int)getpgrp() : (int)-pid, pid == 0 ? (int)ddh_logical_pgrp() : (int)-pid, sig);
            if (len > 0) ddh_log(getenv("DD_HANDOFF_DEBUG"), line, (size_t)len);
        }
        return ddh_kill_group(pid, sig);
    }
    if (pid > 1 && (sig == SIGINT || sig == SIGTERM || sig == SIGKILL || sig == SIGHUP) && ddh_is_steam_reaper(pid))
        ddh_signal_reaper_tree(pid, sig);
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
