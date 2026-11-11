// Part of libddnatv-hook.so, included by ddnatv-hook.c (one translation unit, so the
// parts share their static functions and are built with the same command as before).
//
// Hard links: link() falls back to a copy that reports the link count of a real link, in every
// process of the rootfs.

// Emulated hard links. Android does not let apps create hard links, so link() falls back to a copy
// (ddnatv_emulate_hardlinkat). Programs that check the result through the link count, such as the
// lock files of shadow (link(file.PID, file.lock), then stat(file.PID).st_nlink == 2), and those
// that look for files linked to each other (tar, rsync -H, du, find -samefile), are shown what a
// real link would give: every copy of one emulated link reports the inode number of the original
// and the number of copies as its link count.
//
// The copies are recorded in a table that every process of the rootfs maps
// ($DDNATV_ROOT/var/lib/ddnatv/hardlinks), so "ln a b" in one process and "stat a" in the next
// agree, also after the app restarts. Copies are recorded by device and inode, so a relative name,
// an absolute name and an open descriptor of the same file all match, and with their modification
// time: a copy written to since (it no longer shares the original's content) or a new file that got
// the inode number of a removed copy (removed outside the hook, e.g. in root mode) do not match.
//
// Readers (every stat) take no lock: a sequence number that writers make odd while they change the
// table tells them to read again. Writers (link, unlink, rename) take an fcntl lock on the file.
// Without a rootfs (or when the file cannot be made) the table is private to the process and its
// children, as it was before.

#define DDNATV_HL_MAGIC 0x6b6e6c43u // "Clnk"
#define DDNATV_HL_VERSION 1u
#define DDNATV_HL_SLOTS 8192u      // a power of two
#define DDNATV_HL_MAX_USED 6144u   // at most 3/4 full, so that lookups stay short
#define DDNATV_HL_EMPTY 0u
#define DDNATV_HL_USED 1u
#define DDNATV_HL_DELETED 2u

typedef struct {
    uint64_t dev, ino;             // this copy
    int64_t mtime_sec, mtime_nsec; // its modification time when it was linked (compared in whole
                                   // seconds: stat() and statx() need not agree on the fraction)
    uint64_t group_dev, group_ino; // the original, whose inode number every copy reports
    uint32_t state;
    uint32_t nlink;                // copies in the group
    uint32_t pad[2];
} ddnatv_hl_slot_t;

typedef struct {
    uint32_t magic, version;
    uint32_t seq;                  // odd while a writer changes the table
    uint32_t used, deleted;
    uint32_t pad[11];
    ddnatv_hl_slot_t slots[DDNATV_HL_SLOTS];
} ddnatv_hl_table_t;

// The file and the mapping, in whole 64 KB: a size that is a multiple of every page size
#define DDNATV_HL_MAP_SIZE ((sizeof(ddnatv_hl_table_t) + 0xffff) & ~(size_t)0xffff)

static ddnatv_hl_table_t *g_hl_table = NULL; // set once, then never changed (until a test resets it)
static int g_hl_shared = 0;                  // mapped from the rootfs' file
static long g_hl_next_try = 0;               // when to look for the file again (monotonic seconds)
static pthread_mutex_t g_hardlinks_lock = PTHREAD_MUTEX_INITIALIZER;

#ifndef DDNATV_HOST_TEST
static void init_ddnatv_hook(void);
#endif

static size_t hl_table_path(char *buf, size_t size) {
#ifndef DDNATV_HOST_TEST
    // The rootfs is known once a path was rewritten; "ln a b" or "stat a" name only relative ones
    init_ddnatv_hook();
#endif
    if (g_ddnatv_root_len == 0) return 0;
    size_t n = ddnatv_append(buf, size, 0, g_ddnatv_root, g_ddnatv_root_len);
    static const char rel[] = "/var/lib/ddnatv/hardlinks";
    n = ddnatv_append(buf, size, n, rel, sizeof(rel) - 1);
    return n == g_ddnatv_root_len + sizeof(rel) - 1 ? n : 0;
}

static long hl_now_seconds(void) {
    struct timespec ts;
#ifdef CLOCK_MONOTONIC_COARSE
    if (clock_gettime(CLOCK_MONOTONIC_COARSE, &ts) == 0) return (long)ts.tv_sec;
#endif
    if (clock_gettime(CLOCK_MONOTONIC, &ts) == 0) return (long)ts.tv_sec;
    return 0;
}

static int hl_open_file(const char *path, int create) {
    return (int)syscall(SYS_openat, AT_FDCWD, path, O_RDWR | O_CLOEXEC | (create ? O_CREAT : 0), 0600);
}

// Takes the writers' lock on the table's file (blocking); -1 when there is no file to lock.
static int hl_lock_file(void) {
    if (!g_hl_shared) return -1;
    char path[PATH_MAX];
    if (!hl_table_path(path, sizeof(path))) return -1;
    int fd = hl_open_file(path, 0);
    if (fd < 0) return -1;
    struct flock fl;
    memset(&fl, 0, sizeof(fl));
    fl.l_type = F_WRLCK;
    fl.l_whence = SEEK_SET;
    while (fcntl(fd, F_SETLKW, &fl) != 0 && errno == EINTR) {
    }
    return fd;
}

static void hl_unlock_file(int fd) {
    if (fd >= 0) syscall(SYS_close, fd); // closing the file drops the lock
}

static void hl_init_header(ddnatv_hl_table_t *t) {
    memset(t, 0, sizeof(*t));
    t->version = DDNATV_HL_VERSION;
    __atomic_store_n(&t->magic, DDNATV_HL_MAGIC, __ATOMIC_RELEASE);
}

// The size of an open file. Not fstat(): this library's own fstat() looks into the table, which
// would wait for the lock that the caller holds.
static off_t hl_file_size(int fd) {
    return lseek(fd, 0, SEEK_END);
}

// Maps the table. Readers (create = 0) get NULL while no process has made the file yet, and look
// for it again a second later; writers make it, or fall back to a table of this process. Readers
// never wait for the lock (a stat() while it is held, or in a child forked while another thread
// held it, would wait forever): they find no table then.
static ddnatv_hl_table_t *hl_table(int create) {
    ddnatv_hl_table_t *t = __atomic_load_n(&g_hl_table, __ATOMIC_ACQUIRE);
    if (t) return t;
    if (!create && __atomic_load_n(&g_hl_next_try, __ATOMIC_RELAXED) > hl_now_seconds()) return NULL;
    int saved_errno = errno;
    if (create) {
        pthread_mutex_lock(&g_hardlinks_lock);
    } else if (pthread_mutex_trylock(&g_hardlinks_lock) != 0) {
        errno = saved_errno;
        return NULL;
    }
    t = g_hl_table;
    if (!t) {
        char path[PATH_MAX];
        path[0] = '\0';
        int have_path = hl_table_path(path, sizeof(path)) != 0;
        int fd = have_path ? hl_open_file(path, 0) : -1;
        if (fd < 0 && create && path[0]) {
            // var/lib/ddnatv may not exist yet
            char dir[PATH_MAX];
            size_t n = ddnatv_append(dir, sizeof(dir), 0, path, strlen(path) - strlen("/hardlinks"));
            if (n > 0) syscall(SYS_mkdirat, AT_FDCWD, dir, 0700);
            fd = hl_open_file(path, 1);
        }
        if (fd >= 0) {
            off_t size = hl_file_size(fd);
            int ok = size >= 0;
            if (ok && (size_t)size < DDNATV_HL_MAP_SIZE) {
                // New (or an older, shorter format): made by a writer, under the lock
                ok = 0;
                if (create) {
                    struct flock fl;
                    memset(&fl, 0, sizeof(fl));
                    fl.l_type = F_WRLCK;
                    fl.l_whence = SEEK_SET;
                    while (fcntl(fd, F_SETLKW, &fl) != 0 && errno == EINTR) {
                    }
                    size = hl_file_size(fd);
                    ok = size >= 0 &&
                         ((size_t)size >= DDNATV_HL_MAP_SIZE || ftruncate(fd, DDNATV_HL_MAP_SIZE) == 0);
                }
            }
            if (ok) {
                void *m = mmap(NULL, DDNATV_HL_MAP_SIZE, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
                if (m != MAP_FAILED) {
                    t = (ddnatv_hl_table_t *)m;
                    if (t->magic != DDNATV_HL_MAGIC || t->version != DDNATV_HL_VERSION) {
                        if (create) {
                            // Still under the lock taken above when the file was new; otherwise take it
                            struct flock fl;
                            memset(&fl, 0, sizeof(fl));
                            fl.l_type = F_WRLCK;
                            fl.l_whence = SEEK_SET;
                            while (fcntl(fd, F_SETLKW, &fl) != 0 && errno == EINTR) {
                            }
                            if (t->magic != DDNATV_HL_MAGIC || t->version != DDNATV_HL_VERSION) hl_init_header(t);
                        } else {
                            munmap(m, DDNATV_HL_MAP_SIZE);
                            t = NULL;
                        }
                    }
                    if (t) g_hl_shared = 1;
                }
            }
            syscall(SYS_close, fd); // the mapping stays; also drops the lock
        }
        if (!t && create) {
            // No rootfs file: this process and its children share a table
            void *m = mmap(NULL, DDNATV_HL_MAP_SIZE, PROT_READ | PROT_WRITE, MAP_SHARED | MAP_ANONYMOUS, -1, 0);
            if (m != MAP_FAILED) {
                t = (ddnatv_hl_table_t *)m;
                hl_init_header(t);
                g_hl_shared = 0;
            }
        }
        if (t) {
            __atomic_store_n(&g_hl_table, t, __ATOMIC_RELEASE);
        } else if (have_path) {
            // No table yet: looked for again a second later (without a rootfs there is none to wait for)
            __atomic_store_n(&g_hl_next_try, hl_now_seconds() + 1, __ATOMIC_RELAXED);
        }
    }
    pthread_mutex_unlock(&g_hardlinks_lock);
    errno = saved_errno;
    return t;
}

// Whether any emulated link may exist; what the stat() hooks check first.
static inline int hardlinks_maybe_any(void) {
    ddnatv_hl_table_t *t = hl_table(0);
    return t && __atomic_load_n(&t->used, __ATOMIC_RELAXED) > 0;
}

static inline uint32_t hl_hash(uint64_t dev, uint64_t ino) {
    uint64_t h = ino * 0x9E3779B97F4A7C15ull ^ (dev + 0x632BE59BD9B4E019ull) * 0xC2B2AE3D27D4EB4Full;
    return (uint32_t)(h >> 40) & (DDNATV_HL_SLOTS - 1);
}

// The slot of a copy, or -1. Writers only (under the locks).
static long hl_find_locked(ddnatv_hl_table_t *t, uint64_t dev, uint64_t ino) {
    uint32_t i = hl_hash(dev, ino);
    for (uint32_t n = 0; n < DDNATV_HL_SLOTS; n++, i = (i + 1) & (DDNATV_HL_SLOTS - 1)) {
        ddnatv_hl_slot_t *s = &t->slots[i];
        if (s->state == DDNATV_HL_EMPTY) return -1;
        if (s->state == DDNATV_HL_USED && s->ino == ino && s->dev == dev) return (long)i;
    }
    return -1;
}

// Writers: the process' mutex, the file's lock, then the sequence number made odd.
static int hl_write_begin(ddnatv_hl_table_t *t) {
    pthread_mutex_lock(&g_hardlinks_lock);
    int fd = hl_lock_file();
    uint32_t seq = __atomic_load_n(&t->seq, __ATOMIC_RELAXED);
    // An odd number left by a writer that died: the lock was given up with it
    if (!(seq & 1)) seq++;
    __atomic_store_n(&t->seq, seq, __ATOMIC_RELAXED);
    __atomic_thread_fence(__ATOMIC_RELEASE);
    return fd;
}

static void hl_write_end(ddnatv_hl_table_t *t, int fd) {
    __atomic_store_n(&t->seq, __atomic_load_n(&t->seq, __ATOMIC_RELAXED) + 1, __ATOMIC_RELEASE);
    hl_unlock_file(fd);
    pthread_mutex_unlock(&g_hardlinks_lock);
}

// Rebuilds the table without the slots of removed copies, which make every lookup longer.
static void hl_compact_locked(ddnatv_hl_table_t *t) {
    ddnatv_hl_slot_t *keep = (ddnatv_hl_slot_t *)malloc(sizeof(ddnatv_hl_slot_t) * DDNATV_HL_MAX_USED);
    if (!keep) return;
    uint32_t n = 0;
    for (uint32_t i = 0; i < DDNATV_HL_SLOTS && n < DDNATV_HL_MAX_USED; i++) {
        if (t->slots[i].state == DDNATV_HL_USED) keep[n++] = t->slots[i];
    }
    memset(t->slots, 0, sizeof(t->slots));
    for (uint32_t k = 0; k < n; k++) {
        uint32_t i = hl_hash(keep[k].dev, keep[k].ino);
        while (t->slots[i].state != DDNATV_HL_EMPTY) i = (i + 1) & (DDNATV_HL_SLOTS - 1);
        t->slots[i] = keep[k];
    }
    t->used = n;
    t->deleted = 0;
    free(keep);
}

static int hl_put_locked(ddnatv_hl_table_t *t, const struct stat *st, uint64_t group_dev, uint64_t group_ino) {
    long i = hl_find_locked(t, st->st_dev, st->st_ino);
    if (i < 0) {
        if (t->used >= DDNATV_HL_MAX_USED) return -1;
        if (t->used + t->deleted >= DDNATV_HL_SLOTS - DDNATV_HL_SLOTS / 8) hl_compact_locked(t);
        uint32_t j = hl_hash(st->st_dev, st->st_ino);
        while (t->slots[j].state == DDNATV_HL_USED) j = (j + 1) & (DDNATV_HL_SLOTS - 1);
        if (t->slots[j].state == DDNATV_HL_DELETED) t->deleted--;
        i = (long)j;
        t->used++;
    }
    ddnatv_hl_slot_t *s = &t->slots[i];
    s->dev = st->st_dev;
    s->ino = st->st_ino;
    s->mtime_sec = st->st_mtim.tv_sec;
    s->mtime_nsec = st->st_mtim.tv_nsec;
    s->group_dev = group_dev;
    s->group_ino = group_ino;
    s->nlink = 1;
    s->state = DDNATV_HL_USED;
    return 0;
}

static void hl_remove_locked(ddnatv_hl_table_t *t, long i) {
    t->slots[i].state = DDNATV_HL_DELETED;
    t->used--;
    t->deleted++;
}

// Gives every copy of a group its size; a group of one is a plain file again.
static void hl_update_group_locked(ddnatv_hl_table_t *t, uint64_t group_dev, uint64_t group_ino) {
    uint32_t n = 0;
    long last = -1;
    for (uint32_t i = 0; i < DDNATV_HL_SLOTS; i++) {
        ddnatv_hl_slot_t *s = &t->slots[i];
        if (s->state == DDNATV_HL_USED && s->group_ino == group_ino && s->group_dev == group_dev) {
            n++;
            last = (long)i;
        }
    }
    if (n == 1) {
        hl_remove_locked(t, last);
        return;
    }
    for (uint32_t i = 0; i < DDNATV_HL_SLOTS && n > 1; i++) {
        ddnatv_hl_slot_t *s = &t->slots[i];
        if (s->state == DDNATV_HL_USED && s->group_ino == group_ino && s->group_dev == group_dev) s->nlink = n;
    }
}

// Removes the copy in slot i. A group reports the inode number of one of its copies (the original);
// when that copy goes, the group reports another one's: the number of a removed file can be given to
// a new file, which must not show up as linked to the copies that are left.
static void hl_remove_copy_locked(ddnatv_hl_table_t *t, long i) {
    uint64_t group_dev = t->slots[i].group_dev, group_ino = t->slots[i].group_ino;
    int reported = t->slots[i].dev == group_dev && t->slots[i].ino == group_ino;
    hl_remove_locked(t, i);
    if (reported) {
        uint64_t new_dev = 0, new_ino = 0;
        int found = 0;
        for (uint32_t j = 0; j < DDNATV_HL_SLOTS; j++) {
            ddnatv_hl_slot_t *s = &t->slots[j];
            if (s->state != DDNATV_HL_USED || s->group_ino != group_ino || s->group_dev != group_dev) continue;
            if (!found) {
                new_dev = s->dev;
                new_ino = s->ino;
                found = 1;
            }
            s->group_dev = new_dev;
            s->group_ino = new_ino;
        }
        if (found) {
            group_dev = new_dev;
            group_ino = new_ino;
        }
    }
    hl_update_group_locked(t, group_dev, group_ino);
}

// Records that the file [copy] is an emulated hard link of [old].
static void register_emulated_hardlink(const struct stat *old, const struct stat *copy) {
    ddnatv_hl_table_t *t = hl_table(1);
    if (!t) return;
    int saved_errno = errno;
    int fd = hl_write_begin(t);
    uint64_t group_dev = old->st_dev, group_ino = old->st_ino;
    long i = hl_find_locked(t, old->st_dev, old->st_ino);
    if (i >= 0 && t->slots[i].mtime_sec == old->st_mtim.tv_sec) {
        // Linking a copy again: the new copy joins the same group
        group_dev = t->slots[i].group_dev;
        group_ino = t->slots[i].group_ino;
    } else {
        // A stale slot of an earlier file with this inode number
        if (i >= 0) hl_remove_copy_locked(t, i);
        hl_put_locked(t, old, group_dev, group_ino);
    }
    if (hl_put_locked(t, copy, group_dev, group_ino) == 0 || hl_find_locked(t, old->st_dev, old->st_ino) >= 0) {
        hl_update_group_locked(t, group_dev, group_ino);
    }
    hl_write_end(t, fd);
    errno = saved_errno;
}

// Forgets a copy whose name was removed; the last copy of a group goes back to a plain file.
static void unregister_emulated_hardlink(dev_t dev, ino_t ino) {
    ddnatv_hl_table_t *t = hl_table(0);
    if (!t || __atomic_load_n(&t->used, __ATOMIC_RELAXED) == 0) return;
    int saved_errno = errno;
    int fd = hl_write_begin(t);
    long i = hl_find_locked(t, dev, ino);
    if (i >= 0) hl_remove_copy_locked(t, i);
    hl_write_end(t, fd);
    errno = saved_errno;
}

// Reads the slot of a copy without a lock: 1 and [out] when there is one.
static int hl_read(ddnatv_hl_table_t *t, uint64_t dev, uint64_t ino, ddnatv_hl_slot_t *out) {
    for (int tries = 0; tries < 64; tries++) {
        uint32_t seq = __atomic_load_n(&t->seq, __ATOMIC_ACQUIRE);
        if (seq & 1) {
            sched_yield();
            continue;
        }
        int found = 0;
        uint32_t i = hl_hash(dev, ino);
        for (uint32_t n = 0; n < DDNATV_HL_SLOTS; n++, i = (i + 1) & (DDNATV_HL_SLOTS - 1)) {
            const volatile ddnatv_hl_slot_t *s = &t->slots[i];
            uint32_t state = s->state;
            if (state == DDNATV_HL_EMPTY) break;
            if (state == DDNATV_HL_USED && s->ino == ino && s->dev == dev) {
                memcpy(out, (const void *)s, sizeof(*out));
                found = 1;
                break;
            }
        }
        __atomic_thread_fence(__ATOMIC_ACQUIRE);
        if (__atomic_load_n(&t->seq, __ATOMIC_RELAXED) == seq) return found;
    }
    return 0; // a writer that does not finish: as if there were no links
}

// Makes a stat() result of an emulated hard link look like a real one: the inode number of the
// original and the number of copies as the link count.
static void apply_emulated_hardlink(struct stat *st) {
    if (!st || !hardlinks_maybe_any()) return;
    ddnatv_hl_slot_t s;
    if (!hl_read(g_hl_table, st->st_dev, st->st_ino, &s)) return;
    if (s.mtime_sec != (int64_t)st->st_mtim.tv_sec) return;
    if (s.nlink > (uint32_t)st->st_nlink) st->st_nlink = (nlink_t)s.nlink;
    st->st_ino = (ino_t)s.group_ino;
}

// The app's own user and group, which the hook presents as root: getuid() and getpwuid() say so,
// and stat() does as well, so that a program that compares a file's owner with getuid() sees the
// files it made as its own (git's "dubious ownership", sshd's /run/sshd, tmux's socket directory,
// ssh's ~/.ssh/config). -1 until known; a test sets them.
static uid_t g_app_uid = (uid_t)-1;
static gid_t g_app_gid = (gid_t)-1;

static void load_app_ids(void) {
#ifdef DDNATV_HOST_TEST
    uid_t uid = getuid();
    gid_t gid = getgid();
#else
    // The real ones (getuid() and syscall() are the hook's own, which answer 0)
    static uid_t (*orig_getuid)(void) = NULL;
    static gid_t (*orig_getgid)(void) = NULL;
    if (!orig_getuid) orig_getuid = (uid_t (*)(void))dlsym(RTLD_NEXT, "getuid");
    if (!orig_getgid) orig_getgid = (gid_t (*)(void))dlsym(RTLD_NEXT, "getgid");
    if (!orig_getuid || !orig_getgid) return;
    uid_t uid = orig_getuid();
    gid_t gid = orig_getgid();
#endif
    __atomic_store_n(&g_app_gid, gid, __ATOMIC_RELAXED);
    __atomic_store_n(&g_app_uid, uid, __ATOMIC_RELAXED);
}

static void present_owner_as_root(uid_t *uid, gid_t *gid) {
    if (__atomic_load_n(&g_app_uid, __ATOMIC_RELAXED) == (uid_t)-1) load_app_ids();
    uid_t app_uid = __atomic_load_n(&g_app_uid, __ATOMIC_RELAXED);
    gid_t app_gid = __atomic_load_n(&g_app_gid, __ATOMIC_RELAXED);
    if (app_uid == (uid_t)-1) return;
    if (uid && *uid == app_uid) *uid = 0;
    if (gid && *gid == app_gid) *gid = 0;
}

// What the stat() family returns: emulated hard links as real ones, the app's files as root's.
static void apply_stat_emulation(struct stat *st) {
    if (!st) return;
    apply_emulated_hardlink(st);
    present_owner_as_root(&st->st_uid, &st->st_gid);
}

static int real_fstatat(int dirfd, const char *path, struct stat *st, int flags) {
#ifdef DDNATV_HOST_TEST
    return fstatat(dirfd, path, st, flags);
#else
    static int (*orig_fstatat)(int, const char *, struct stat *, int) = NULL;
    if (!orig_fstatat) orig_fstatat = (int (*)(int, const char *, struct stat *, int))dlsym(RTLD_NEXT, "fstatat");
    return orig_fstatat ? orig_fstatat(dirfd, path, st, flags) : fstatat(dirfd, path, st, flags);
#endif
}

// Before a name is removed or replaced (unlink, rename over it): whether it is an emulated hard
// link, and which. Costs nothing until the process has made one.
static int lookup_emulated_hardlink_at(int dirfd, const char *path, dev_t *dev, ino_t *ino) {
    if (!path || !hardlinks_maybe_any()) return 0;
    int saved_errno = errno;
    struct stat st;
    int found = 0;
    if (real_fstatat(dirfd, path, &st, AT_SYMLINK_NOFOLLOW) == 0) {
        ddnatv_hl_slot_t s;
        found = hl_read(g_hl_table, st.st_dev, st.st_ino, &s);
        *dev = st.st_dev;
        *ino = st.st_ino;
    }
    errno = saved_errno;
    return found;
}

static int ddnatv_emulate_hardlinkat(int olddirfd, const char *rold, int newdirfd, const char *rnew) {
    if (!rold || !rnew) {
        errno = EINVAL;
        return -1;
    }
    struct stat st;
#ifdef DDNATV_HOST_TEST
    if (fstatat(olddirfd, rold, &st, 0) != 0) {
        return -1;
    }
#else
    static int (*orig_fstatat)(int, const char *, struct stat *, int) = NULL;
    if (!orig_fstatat) orig_fstatat = (int (*)(int, const char *, struct stat *, int))dlsym(RTLD_NEXT, "fstatat");
    if (orig_fstatat) {
        if (orig_fstatat(olddirfd, rold, &st, 0) != 0) return -1;
    } else {
        if (fstatat(olddirfd, rold, &st, 0) != 0) return -1;
    }
#endif
    if (S_ISDIR(st.st_mode)) {
        errno = EPERM;
        return -1;
    }

    // Clean up stale dangling symlink or stale dead-process lock file at rnew
#ifndef DDNATV_HOST_TEST
    struct stat lst;
    int have_lst = 0;
    if (orig_fstatat) {
        if (orig_fstatat(newdirfd, rnew, &lst, AT_SYMLINK_NOFOLLOW) == 0) have_lst = 1;
    } else {
        if (fstatat(newdirfd, rnew, &lst, AT_SYMLINK_NOFOLLOW) == 0) have_lst = 1;
    }
    if (have_lst) {
        if (S_ISLNK(lst.st_mode)) {
            unlinkat(newdirfd, rnew, 0);
        } else if (strstr(rnew, ".lock")) {
            static int (*orig_openat)(int, const char *, int, ...) = NULL;
            if (!orig_openat) orig_openat = (int (*)(int, const char *, int, ...))dlsym(RTLD_NEXT, "openat");
            int rfd = orig_openat ? orig_openat(newdirfd, rnew, O_RDONLY | O_CLOEXEC)
                                  : openat(newdirfd, rnew, O_RDONLY | O_CLOEXEC);
            if (rfd >= 0) {
                char pbuf[32] = {0};
                ssize_t pr = read(rfd, pbuf, sizeof(pbuf) - 1);
                close(rfd);
                if (pr > 0) {
                    pid_t lpid = (pid_t)atoi(pbuf);
                    if (lpid > 0 && kill(lpid, 0) == -1 && errno == ESRCH) {
                        unlinkat(newdirfd, rnew, 0);
                    }
                } else {
                    unlinkat(newdirfd, rnew, 0);
                }
            }
        }
    }
#endif

#ifdef DDNATV_HOST_TEST
    int fd_new = openat(newdirfd, rnew, O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC, st.st_mode & 0777);
    if (fd_new < 0) return -1;
    int fd_old = openat(olddirfd, rold, O_RDONLY | O_CLOEXEC);
#else
    static int (*orig_openat)(int, const char *, int, ...) = NULL;
    if (!orig_openat) orig_openat = (int (*)(int, const char *, int, ...))dlsym(RTLD_NEXT, "openat");
    int fd_new = orig_openat ? orig_openat(newdirfd, rnew, O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC, st.st_mode & 0777)
                             : openat(newdirfd, rnew, O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC, st.st_mode & 0777);
    if (fd_new < 0) return -1;
    int fd_old = orig_openat ? orig_openat(olddirfd, rold, O_RDONLY | O_CLOEXEC)
                             : openat(olddirfd, rold, O_RDONLY | O_CLOEXEC);
#endif
    if (fd_old < 0) {
        int err = errno;
        close(fd_new);
        unlinkat(newdirfd, rnew, 0);
        errno = err;
        return -1;
    }

    ssize_t sent = 0;
    int copy_failed = 0;
    while (sent < st.st_size) {
        ssize_t s = sendfile(fd_new, fd_old, NULL, (size_t)(st.st_size - sent));
        if (s > 0) {
            sent += s;
        } else {
            break;
        }
    }
    if (sent < st.st_size) {
        char cbuf[16384];
        ssize_t nread;
        while ((nread = read(fd_old, cbuf, sizeof(cbuf))) > 0) {
            ssize_t nwritten = 0;
            while (nwritten < nread) {
                ssize_t w = write(fd_new, cbuf + nwritten, nread - nwritten);
                if (w < 0) {
                    copy_failed = 1;
                    break;
                }
                nwritten += w;
            }
            if (copy_failed) break;
        }
        if (nread < 0) copy_failed = 1;
    }

    fchmod(fd_new, st.st_mode & 0777);
    // A hard link shares the times of the original (tar, dpkg and make compare them).
    const struct timespec times[2] = { st.st_atim, st.st_mtim };
    futimens(fd_new, times);
    struct stat st_new;
    memset(&st_new, 0, sizeof(st_new));
#ifndef DDNATV_HOST_TEST
    static int (*orig_fstat)(int, struct stat *) = NULL;
    if (!orig_fstat) orig_fstat = (int (*)(int, struct stat *))dlsym(RTLD_NEXT, "fstat");
    if (orig_fstat) orig_fstat(fd_new, &st_new);
    else fstat(fd_new, &st_new);
#else
    fstat(fd_new, &st_new);
#endif

    close(fd_old);
    close(fd_new);

    if (copy_failed) {
        unlinkat(newdirfd, rnew, 0);
        errno = EIO;
        return -1;
    }

    register_emulated_hardlink(&st, &st_new);
    return 0;
}

static inline int ddnatv_emulate_hardlink(const char *rold, const char *rnew) {
    return ddnatv_emulate_hardlinkat(AT_FDCWD, rold, AT_FDCWD, rnew);
}
