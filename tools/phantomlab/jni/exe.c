// libplabexe.so: an executable shipped as a "library" so the APK's lib directory holds it.
// Reports its identity and reads fd 3 when it is open, then exits with status 7.
#include <fcntl.h>
#include <poll.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

int main(int argc, char **argv) {
    char buf[128] = "";
    ssize_t n = -1;
    struct pollfd p = {.fd = 3, .events = POLLIN};
    if (poll(&p, 1, 500) > 0 && (p.revents & (POLLIN | POLLHUP))) n = read(3, buf, sizeof(buf) - 1);
    if (n > 0) buf[n] = 0;
    char ctx[128] = "";
    int fd = open("/proc/self/attr/current", O_RDONLY);
    if (fd >= 0) {
        ssize_t m = read(fd, ctx, sizeof(ctx) - 1);
        if (m > 0) ctx[m] = 0;
        close(fd);
    }
    dprintf(2, "plabexe uid=%d ctx=%s fd3=%s\n", getuid(), ctx, n > 0 ? buf : "none");
    return 7;
}
