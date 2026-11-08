// Part of libddnatv-hook.so, included by ddnatv-hook.c.
//
// shm_open() and shm_unlink() of glibc open /dev/shm/NAME with a system call of their own, which
// the path hooks never see and Android has no /dev/shm for. They go through open() and unlink()
// here, which map /dev/shm.

#ifndef DDNATV_HOST_TEST

static int posix_shm_path(const char *name, char *path, size_t size) {
    if (!name) { errno = EINVAL; return -1; }
    while (*name == '/') name++;
    size_t len = strlen(name);
    if (len == 0 || len > NAME_MAX || strchr(name, '/') || strcmp(name, ".") == 0 || strcmp(name, "..") == 0) {
        errno = len > NAME_MAX ? ENAMETOOLONG : EINVAL;
        return -1;
    }
    if (snprintf(path, size, "/dev/shm/%s", name) >= (int)size) { errno = ENAMETOOLONG; return -1; }
    return 0;
}

int shm_open(const char *name, int oflag, mode_t mode) {
    char path[PATH_MAX];
    if (posix_shm_path(name, path, sizeof(path)) < 0) return -1;
    return open(path, (oflag & (O_ACCMODE | O_CREAT | O_EXCL | O_TRUNC)) | O_NOFOLLOW | O_CLOEXEC, mode);
}

int shm_unlink(const char *name) {
    char path[PATH_MAX];
    if (posix_shm_path(name, path, sizeof(path)) < 0) return -1;
    return unlink(path);
}

#endif /* !DDNATV_HOST_TEST */

#ifndef DDNATV_HOST_TEST
// glibc's posix_spawn child resets every signal handler to its default, the hook's SIGSYS handler
// included, before it runs the file actions, and "close everything from N" is close_range(), which
// Android's seccomp filter answers with SIGSYS: the child died of it (Python's subprocess with
// close_fds does this). The descriptors that are open now are closed one by one instead.
int posix_spawn_file_actions_addclosefrom_np(posix_spawn_file_actions_t *actions, int from) {
    if (!actions || from < 0) return EINVAL;
    DIR *dir = opendir("/proc/self/fd");
    if (!dir) return errno ? errno : ENOENT;
    int self = dirfd(dir);
    int ret = 0;
    struct dirent *entry;
    while (ret == 0 && (entry = readdir(dir)) != NULL) {
        if (entry->d_name[0] < '0' || entry->d_name[0] > '9') continue;
        int fd = atoi(entry->d_name);
        if (fd < from || fd == self) continue;
        ret = posix_spawn_file_actions_addclose(actions, fd);
    }
    closedir(dir);
    return ret;
}
#endif
