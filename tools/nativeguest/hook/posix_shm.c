// Part of libddnatv-hook.so, included by ddnatv-hook.c.
//
// shm_open(), shm_unlink() and the named semaphores of glibc open /dev/shm/NAME with system calls
// of their own, which the path hooks never see and Android has no /dev/shm for. They go through
// open(), renameat2() and unlink() here, which map /dev/shm.

#ifndef DDNATV_HOST_TEST

static int posix_shm_path(const char *name, const char *prefix, char *path, size_t size) {
    if (!name) { errno = EINVAL; return -1; }
    while (*name == '/') name++;
    size_t len = strlen(name) + strlen(prefix);
    if (len == strlen(prefix) || len > NAME_MAX || strchr(name, '/') || strcmp(name, ".") == 0 || strcmp(name, "..") == 0) {
        errno = len > NAME_MAX ? ENAMETOOLONG : EINVAL;
        return -1;
    }
    if (snprintf(path, size, "/dev/shm/%s%s", prefix, name) >= (int)size) { errno = ENAMETOOLONG; return -1; }
    return 0;
}

int shm_open(const char *name, int oflag, mode_t mode) {
    char path[PATH_MAX];
    if (posix_shm_path(name, "", path, sizeof(path)) < 0) return -1;
    return open(path, (oflag & (O_ACCMODE | O_CREAT | O_EXCL | O_TRUNC)) | O_NOFOLLOW | O_CLOEXEC, mode);
}

int shm_unlink(const char *name) {
    char path[PATH_MAX];
    if (posix_shm_path(name, "", path, sizeof(path)) < 0) return -1;
    return unlink(path);
}

struct posix_sem {
    dev_t dev;
    ino_t ino;
    sem_t *addr;
    unsigned int refs;
};
// A child can open a semaphore while another thread held malloc's lock at fork; keep the registry in BSS.
#define POSIX_SEM_SLOTS 1024
static struct posix_sem g_posix_sems[POSIX_SEM_SLOTS];
static pthread_mutex_t g_posix_sems_lock = PTHREAD_MUTEX_INITIALIZER;

static void posix_sems_unlock(void) { pthread_mutex_unlock(&g_posix_sems_lock); }

static void posix_sems_hold(void) { pthread_mutex_lock(&g_posix_sems_lock); }

__attribute__((constructor)) static void posix_sems_across_fork(void) {
    pthread_atfork(posix_sems_hold, posix_sems_unlock, posix_sems_unlock);
}

static void close_keeping_errno(int fd) {
    int saved = errno;
    close(fd);
    errno = saved;
}

static sem_t *posix_sem_map(int fd) {
    struct stat st;
    if (fstat(fd, &st) < 0) return SEM_FAILED;
    sem_t *sem = SEM_FAILED;
    posix_sems_hold();
    struct posix_sem *free = NULL;
    for (size_t i = 0; i < POSIX_SEM_SLOTS; i++) {
        struct posix_sem *e = &g_posix_sems[i];
        if (e->refs && e->dev == st.st_dev && e->ino == st.st_ino) {
            e->refs++;
            sem = e->addr;
            break;
        }
        if (!e->refs) free = e;
    }
    if (sem == SEM_FAILED) {
        if (!free) {
            errno = ENOMEM;
        } else {
            void *addr = mmap(NULL, sizeof(sem_t), PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
            if (addr != MAP_FAILED) {
                *free = (struct posix_sem){st.st_dev, st.st_ino, addr, 1};
                sem = addr;
            }
        }
    }
    posix_sems_unlock();
    return sem;
}

static sem_t *posix_sem_open_fd(int fd) {
    if (fd < 0) return SEM_FAILED;
    sem_t *sem = posix_sem_map(fd);
    close_keeping_errno(fd);
    return sem;
}

sem_t *sem_open(const char *name, int oflag, ...) {
    char path[PATH_MAX];
    if (posix_shm_path(name, "sem.", path, sizeof(path)) < 0) return SEM_FAILED;
    if (!(oflag & O_CREAT)) return posix_sem_open_fd(open(path, O_RDWR | O_NOFOLLOW | O_CLOEXEC));
    va_list args;
    va_start(args, oflag);
    mode_t mode = va_arg(args, mode_t);
    unsigned int value = va_arg(args, unsigned int);
    va_end(args);
    static unsigned int serial;
    for (;;) {
        if (!(oflag & O_EXCL)) {
            int fd = open(path, O_RDWR | O_NOFOLLOW | O_CLOEXEC);
            if (fd >= 0 || errno != ENOENT) return posix_sem_open_fd(fd);
        }
        if (value > SEM_VALUE_MAX) { errno = EINVAL; return SEM_FAILED; }
        sem_t initial;
        memset(&initial, 0, sizeof(initial));
        sem_init(&initial, 1, value);
        char temp[PATH_MAX];
        int fd;
        do {
            snprintf(temp, sizeof(temp), "/dev/shm/.sem-%d-%u", (int)getpid(), __atomic_fetch_add(&serial, 1, __ATOMIC_RELAXED));
            fd = open(temp, O_RDWR | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, mode);
        } while (fd < 0 && errno == EEXIST);
        if (fd < 0) return SEM_FAILED;
        errno = EIO;
        sem_t *sem = write(fd, &initial, sizeof(initial)) == (ssize_t)sizeof(initial) ? posix_sem_map(fd) : SEM_FAILED;
        close_keeping_errno(fd);
        if (sem != SEM_FAILED && renameat2(AT_FDCWD, temp, AT_FDCWD, path, RENAME_NOREPLACE) == 0) return sem;
        int saved = errno;
        if (sem != SEM_FAILED) sem_close(sem);
        unlink(temp);
        errno = saved;
        if (errno != EEXIST || (oflag & O_EXCL)) return SEM_FAILED;
    }
}

int sem_close(sem_t *sem) {
    posix_sems_hold();
    struct posix_sem *found = NULL;
    for (size_t i = 0; i < POSIX_SEM_SLOTS; i++) {
        struct posix_sem *e = &g_posix_sems[i];
        if (e->refs && e->addr == sem) { found = e; break; }
    }
    if (found && --found->refs == 0) {
        munmap(found->addr, sizeof(sem_t));
        *found = (struct posix_sem){0};
    }
    posix_sems_unlock();
    if (!found) { errno = EINVAL; return -1; }
    return 0;
}

int sem_unlink(const char *name) {
    char path[PATH_MAX];
    if (posix_shm_path(name, "sem.", path, sizeof(path)) < 0) return -1;
    return unlink(path);
}

#endif /* !DDNATV_HOST_TEST */

#ifndef DDNATV_HOST_TEST
// glibc's posix_spawn child resets every signal handler to its default, the hook's SIGSYS handler
// included, before it runs the file actions, and "close everything from N" is close_range(), which
// Android's seccomp filter answers with SIGSYS: the child died of it (Python's subprocess with
// close_fds does this). It becomes one close per descriptor, added when the program is spawned so
// that descriptors opened in between and the ones earlier actions make are closed too; the targets
// of actions added after it stay open, as they would. A close of a descriptor that is not open is
// no error to glibc.
#define CLOSEFROM_SLOTS 64
#define CLOSEFROM_KEEP 32
static struct closefrom_entry {
    posix_spawn_file_actions_t *actions;
    int from;          // -1: no close-from yet, only targets of actions seen
    int highest;       // the highest descriptor an action before close-from makes
    int nkeep;
    int keep[CLOSEFROM_KEEP];
    int closed_to;     // the descriptors up to this one are closed by actions already added
    int bad_source;    // a dup2 after close-from duplicates a descriptor it closed: glibc's EBADF
} g_closefrom[CLOSEFROM_SLOTS];
static pthread_mutex_t g_closefrom_lock = PTHREAD_MUTEX_INITIALIZER;

static struct closefrom_entry *closefrom_entry(posix_spawn_file_actions_t *actions, int create) {
    struct closefrom_entry *free_slot = NULL;
    for (int i = 0; i < CLOSEFROM_SLOTS; i++) {
        if (g_closefrom[i].actions == actions) return &g_closefrom[i];
        if (!g_closefrom[i].actions && !free_slot) free_slot = &g_closefrom[i];
    }
    if (!create || !free_slot) return NULL;
    memset(free_slot, 0, sizeof(*free_slot));
    free_slot->actions = actions;
    free_slot->from = -1;
    free_slot->highest = -1;
    free_slot->closed_to = -1;
    return free_slot;
}

static void closefrom_note_target(posix_spawn_file_actions_t *actions, int fd) {
    pthread_mutex_lock(&g_closefrom_lock);
    struct closefrom_entry *e = closefrom_entry(actions, 1);
    if (e) {
        if (e->from < 0) {
            if (fd > e->highest) e->highest = fd;
        } else if (e->nkeep < CLOSEFROM_KEEP) {
            e->keep[e->nkeep++] = fd;
        }
    }
    pthread_mutex_unlock(&g_closefrom_lock);
}

int posix_spawn_file_actions_addclosefrom_np(posix_spawn_file_actions_t *actions, int from) {
    if (!actions || from < 0) return EINVAL;
    pthread_mutex_lock(&g_closefrom_lock);
    struct closefrom_entry *e = closefrom_entry(actions, 1);
    if (e && (e->from < 0 || from < e->from)) e->from = from;
    pthread_mutex_unlock(&g_closefrom_lock);
    return e ? 0 : ENOMEM;
}

int posix_spawn_file_actions_addopen(posix_spawn_file_actions_t *actions, int fd, const char *path, int oflag, mode_t mode) {
    static int (*orig)(posix_spawn_file_actions_t *, int, const char *, int, mode_t) = NULL;
    if (!orig) orig = (int (*)(posix_spawn_file_actions_t *, int, const char *, int, mode_t))dlsym(RTLD_NEXT, "posix_spawn_file_actions_addopen");
    int ret = orig ? orig(actions, fd, path, oflag, mode) : ENOSYS;
    if (ret == 0) closefrom_note_target(actions, fd);
    return ret;
}

int posix_spawn_file_actions_adddup2(posix_spawn_file_actions_t *actions, int fd, int newfd) {
    static int (*orig)(posix_spawn_file_actions_t *, int, int) = NULL;
    if (!orig) orig = (int (*)(posix_spawn_file_actions_t *, int, int))dlsym(RTLD_NEXT, "posix_spawn_file_actions_adddup2");
    int ret = orig ? orig(actions, fd, newfd) : ENOSYS;
    if (ret == 0) {
        pthread_mutex_lock(&g_closefrom_lock);
        struct closefrom_entry *e = closefrom_entry(actions, 0);
        if (e && e->from >= 0 && fd >= e->from) {
            int reopened = 0;
            for (int k = 0; k < e->nkeep; k++) reopened |= e->keep[k] == fd;
            if (!reopened) e->bad_source = 1;
        }
        pthread_mutex_unlock(&g_closefrom_lock);
        closefrom_note_target(actions, newfd);
    }
    return ret;
}

int posix_spawn_file_actions_init(posix_spawn_file_actions_t *actions) {
    static int (*orig)(posix_spawn_file_actions_t *) = NULL;
    if (!orig) orig = (int (*)(posix_spawn_file_actions_t *))dlsym(RTLD_NEXT, "posix_spawn_file_actions_init");
    pthread_mutex_lock(&g_closefrom_lock);
    struct closefrom_entry *e = closefrom_entry(actions, 0);
    if (e) e->actions = NULL;
    pthread_mutex_unlock(&g_closefrom_lock);
    return orig ? orig(actions) : ENOSYS;
}

int posix_spawn_file_actions_destroy(posix_spawn_file_actions_t *actions) {
    static int (*orig)(posix_spawn_file_actions_t *) = NULL;
    if (!orig) orig = (int (*)(posix_spawn_file_actions_t *))dlsym(RTLD_NEXT, "posix_spawn_file_actions_destroy");
    pthread_mutex_lock(&g_closefrom_lock);
    struct closefrom_entry *e = closefrom_entry(actions, 0);
    if (e) e->actions = NULL;
    pthread_mutex_unlock(&g_closefrom_lock);
    return orig ? orig(actions) : ENOSYS;
}

static int apply_closefrom(posix_spawn_file_actions_t *actions) {
    pthread_mutex_lock(&g_closefrom_lock);
    struct closefrom_entry *e = closefrom_entry(actions, 0);
    if (!e || e->from < 0) {
        pthread_mutex_unlock(&g_closefrom_lock);
        return 0;
    }
    if (e->bad_source) {
        pthread_mutex_unlock(&g_closefrom_lock);
        return EBADF;
    }
    int highest = e->highest;
    DIR *dir = opendir("/proc/self/fd");
    if (dir) {
        int self = dirfd(dir);
        struct dirent *entry;
        while ((entry = readdir(dir)) != NULL) {
            if (entry->d_name[0] < '0' || entry->d_name[0] > '9') continue;
            int fd = atoi(entry->d_name);
            if (fd != self && fd > highest) highest = fd;
        }
        closedir(dir);
    }
    int start = e->from > e->closed_to + 1 ? e->from : e->closed_to + 1;
    for (int fd = start; fd <= highest; fd++) {
        int kept = 0;
        for (int k = 0; k < e->nkeep; k++) kept |= e->keep[k] == fd;
        if (!kept && posix_spawn_file_actions_addclose(actions, fd) != 0) break;
        e->closed_to = fd;
    }
    pthread_mutex_unlock(&g_closefrom_lock);
    return 0;
}
#else
static int apply_closefrom(posix_spawn_file_actions_t *actions) { (void)actions; return 0; }
#endif
