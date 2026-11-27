#include "unix_listen.h"

#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

bool droiddeck_unix_path_fits(const char *dir, const char *name) {
    struct sockaddr_un addr;
    return strlen(dir) + 1 + strlen(name) < sizeof(addr.sun_path);
}

int droiddeck_unix_listen(const char *dir, const char *name, int backlog) {
    struct sockaddr_un addr = { .sun_family = AF_UNIX };
    int dir_fd = open(dir, O_PATH | O_DIRECTORY | O_CLOEXEC);
    if (dir_fd < 0) return -1;
    int written = snprintf(addr.sun_path, sizeof(addr.sun_path), "/proc/self/fd/%d/%s", dir_fd, name);
    if (written < 0 || (size_t)written >= sizeof(addr.sun_path)) {
        close(dir_fd);
        errno = ENAMETOOLONG;
        return -1;
    }
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd >= 0 && (bind(fd, (struct sockaddr *)&addr, sizeof(addr)) != 0 || listen(fd, backlog) != 0)) {
        int saved = errno;
        close(fd);
        fd = -1;
        errno = saved;
    }
    int saved = errno;
    close(dir_fd);
    errno = saved;
    return fd;
}
