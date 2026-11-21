#include <assert.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <unistd.h>

#include "../../app/src/main/cpp/waylandcomp/src/unix_listen.h"

int main(int argc, char **argv) {
    assert(argc == 3);
    const char *dir = argv[1];
    int expect_fits = atoi(argv[2]);
    assert(droiddeck_unix_path_fits(dir, "wayland-0") == (expect_fits != 0));

    int listener = droiddeck_unix_listen(dir, "wayland-0", 4);
    assert(listener >= 0);

    char path[4096];
    snprintf(path, sizeof(path), "%s/wayland-0", dir);
    struct stat st;
    assert(stat(path, &st) == 0 && S_ISSOCK(st.st_mode));

    int dir_fd = open(dir, O_PATH | O_DIRECTORY | O_CLOEXEC);
    assert(dir_fd >= 0);
    struct sockaddr_un addr = { .sun_family = AF_UNIX };
    snprintf(addr.sun_path, sizeof(addr.sun_path), "/proc/self/fd/%d/wayland-0", dir_fd);
    int client = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    assert(client >= 0 && connect(client, (struct sockaddr *)&addr, sizeof(addr)) == 0);
    int peer = accept(listener, NULL, NULL);
    assert(peer >= 0);
    assert(write(client, "x", 1) == 1);
    char got = 0;
    assert(read(peer, &got, 1) == 1 && got == 'x');

    assert(droiddeck_unix_listen(dir, "wayland-0", 4) < 0);
    assert(droiddeck_unix_listen("/nonexistent-dir", "wayland-0", 4) < 0);
    puts("PASS: unix listen");
    return 0;
}
