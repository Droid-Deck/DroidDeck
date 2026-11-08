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
