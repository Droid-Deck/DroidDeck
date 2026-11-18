#include <errno.h>
#include <sys/socket.h>

int dd_nl_socket(int type, int *fd) {
    (void)type;
    *fd = -1;
    errno = ENOSYS;
    return 1;
}

int dd_nl_owns(int fd) {
    (void)fd;
    return 0;
}

int dd_nl_bind(int fd, const struct sockaddr *addr, socklen_t len, int *rc) {
    (void)fd;
    (void)addr;
    (void)len;
    (void)rc;
    return 0;
}

int dd_nl_connect(int fd, const struct sockaddr *addr, socklen_t len, int *rc) {
    return dd_nl_bind(fd, addr, len, rc);
}

int dd_nl_getsockname(int fd, struct sockaddr *addr, socklen_t *len, int *rc) {
    (void)len;
    return dd_nl_bind(fd, addr, 0, rc);
}

int dd_nl_getpeername(int fd, struct sockaddr *addr, socklen_t *len, int *rc) {
    return dd_nl_getsockname(fd, addr, len, rc);
}

int dd_nl_setsockopt(int fd, int level, int option, const void *value, socklen_t len,
                     int (*real)(int, int, int, const void *, socklen_t), int *rc) {
    (void)fd;
    (void)level;
    (void)option;
    (void)value;
    (void)len;
    (void)real;
    (void)rc;
    return 0;
}

int dd_nl_getsockopt(int fd, int level, int option, void *value, socklen_t *len,
                     int (*real)(int, int, int, void *, socklen_t *), int *rc) {
    (void)fd;
    (void)level;
    (void)option;
    (void)value;
    (void)len;
    (void)real;
    (void)rc;
    return 0;
}
