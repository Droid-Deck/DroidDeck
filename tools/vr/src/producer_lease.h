#pragma once
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#define DROIDDECK_PRODUCER_MAGIC 0x44504c31u
#define DROIDDECK_PRODUCER_ACQUIRE 1u
#define DROIDDECK_PRODUCER_READY 2u
#define DROIDDECK_PRODUCER_RELEASE 3u
#define DROIDDECK_PRODUCER_RELEASED 4u

struct droiddeck_producer_message {
    uint32_t magic;
    uint32_t command;
    uint64_t ticket;
};

static inline int64_t droiddeck_producer_now(void) {
    struct timespec current;
    clock_gettime(CLOCK_MONOTONIC, &current);
    return (int64_t)current.tv_sec * 1000000000LL + current.tv_nsec;
}

static inline int droiddeck_producer_path(char *path, size_t capacity, uint32_t pid) {
    const char *runtime = getenv("XDG_RUNTIME_DIR");
    int length = snprintf(path, capacity, "%s/droiddeck-steamvr/producer-%u.sock",
                          runtime && *runtime ? runtime : "/run/droiddeck", pid);
    return pid && length > 0 && (size_t)length < capacity;
}

static inline int droiddeck_producer_private_parent(const char *path) {
    char parent[sizeof(((struct sockaddr_un *)0)->sun_path)];
    if (strlen(path) >= sizeof(parent)) return 0;
    strcpy(parent, path);
    char *separator = strrchr(parent, '/');
    if (!separator) return 0;
    *separator = 0;
    struct stat metadata;
    return lstat(parent, &metadata) == 0 && S_ISDIR(metadata.st_mode) &&
           metadata.st_uid == getuid() && !(metadata.st_mode & 0077);
}

static inline int droiddeck_producer_peer(int descriptor, uint32_t expected_pid) {
    struct ucred peer;
    socklen_t length = sizeof(peer);
    return getsockopt(descriptor, SOL_SOCKET, SO_PEERCRED, &peer, &length) == 0 &&
           peer.uid == getuid() && (!expected_pid || (uint32_t)peer.pid == expected_pid);
}

static inline int droiddeck_producer_io(int descriptor, struct droiddeck_producer_message *message,
                                       int writing, int64_t deadline) {
    for (;;) {
        int64_t remaining = deadline - droiddeck_producer_now();
        if (remaining <= 0) return 0;
        struct pollfd ready = {descriptor, (short)(writing ? POLLOUT : POLLIN), 0};
        int result = poll(&ready, 1, (int)((remaining + 999999) / 1000000));
        if (result < 0 && errno == EINTR) continue;
        if (result <= 0 || !(ready.revents & ready.events)) return 0;
        ssize_t transferred = writing ? send(descriptor, message, sizeof(*message), MSG_NOSIGNAL | MSG_DONTWAIT)
                                      : recv(descriptor, message, sizeof(*message), MSG_TRUNC | MSG_DONTWAIT);
        if (transferred < 0 && (errno == EAGAIN || errno == EINTR)) continue;
        return transferred == (ssize_t)sizeof(*message) && message->magic == DROIDDECK_PRODUCER_MAGIC;
    }
}

struct droiddeck_producer_lease {
    int descriptor;
    uint64_t ticket;
};

static inline int droiddeck_producer_acquire(uint32_t pid, struct droiddeck_producer_lease *lease) {
    lease->descriptor = -1;
    lease->ticket = 0;
    struct sockaddr_un address;
    memset(&address, 0, sizeof(address));
    address.sun_family = AF_UNIX;
    if (!droiddeck_producer_path(address.sun_path, sizeof(address.sun_path), pid) ||
        !droiddeck_producer_private_parent(address.sun_path)) return 0;
    int descriptor = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
    if (descriptor < 0) return 0;
    int64_t deadline = droiddeck_producer_now() + 250000000LL;
    struct droiddeck_producer_message message = {DROIDDECK_PRODUCER_MAGIC, DROIDDECK_PRODUCER_ACQUIRE, 0};
    if (connect(descriptor, (struct sockaddr *)&address, sizeof(address)) != 0 ||
        !droiddeck_producer_peer(descriptor, pid) ||
        !droiddeck_producer_io(descriptor, &message, 1, deadline) ||
        !droiddeck_producer_io(descriptor, &message, 0, deadline) ||
        message.command != DROIDDECK_PRODUCER_READY || !message.ticket) {
        close(descriptor);
        return 0;
    }
    lease->descriptor = descriptor;
    lease->ticket = message.ticket;
    return 1;
}

static inline int droiddeck_producer_release(struct droiddeck_producer_lease *lease) {
    if (lease->descriptor < 0) return 0;
    int64_t deadline = droiddeck_producer_now() + 250000000LL;
    struct droiddeck_producer_message message = {DROIDDECK_PRODUCER_MAGIC, DROIDDECK_PRODUCER_RELEASE, lease->ticket};
    int released = droiddeck_producer_io(lease->descriptor, &message, 1, deadline) &&
                   droiddeck_producer_io(lease->descriptor, &message, 0, deadline) &&
                   message.command == DROIDDECK_PRODUCER_RELEASED && message.ticket == lease->ticket;
    close(lease->descriptor);
    lease->descriptor = -1;
    return released;
}

#ifdef DROIDDECK_PRODUCER_SERVER
#include <pthread.h>

struct droiddeck_producer_server {
    int listener;
    pthread_t thread;
    int (*acquire)(int64_t deadline);
    void (*release)(void);
    void (*quarantine)(void);
    char path[sizeof(((struct sockaddr_un *)0)->sun_path)];
};

static void *droiddeck_producer_serve(void *argument) {
    struct droiddeck_producer_server *server = argument;
    uint64_t ticket = 0;
    for (;;) {
        int connection = accept4(server->listener, NULL, NULL, SOCK_CLOEXEC | SOCK_NONBLOCK);
        if (connection < 0) {
            if (errno == EINTR) continue;
            break;
        }
        struct droiddeck_producer_message message;
        int64_t deadline = droiddeck_producer_now() + 150000000LL;
        if (!droiddeck_producer_peer(connection, 0) ||
            !droiddeck_producer_io(connection, &message, 0, deadline) ||
            message.command != DROIDDECK_PRODUCER_ACQUIRE || message.ticket || !server->acquire(deadline)) {
            close(connection);
            continue;
        }
        uint64_t granted = ++ticket;
        message = (struct droiddeck_producer_message){DROIDDECK_PRODUCER_MAGIC, DROIDDECK_PRODUCER_READY, granted};
        if (!droiddeck_producer_io(connection, &message, 1, droiddeck_producer_now() + 100000000LL)) {
            server->release();
            close(connection);
            continue;
        }
        int valid = 1;
        while (valid) {
            struct pollfd request = {connection, POLLIN, 0};
            int ready = poll(&request, 1, -1);
            if (ready < 0 && errno == EINTR) continue;
            if (ready <= 0 || !(request.revents & POLLIN)) { valid = 0; break; }
            valid = droiddeck_producer_io(connection, &message, 0, droiddeck_producer_now() + 100000000LL) &&
                    message.command == DROIDDECK_PRODUCER_RELEASE && message.ticket == granted;
            break;
        }
        if (!valid) {
            server->quarantine();
            close(connection);
            break;
        }
        server->release();
        message = (struct droiddeck_producer_message){DROIDDECK_PRODUCER_MAGIC, DROIDDECK_PRODUCER_RELEASED, granted};
        droiddeck_producer_io(connection, &message, 1, droiddeck_producer_now() + 100000000LL);
        close(connection);
    }
    return NULL;
}

static int droiddeck_producer_start(struct droiddeck_producer_server *server) {
    struct sockaddr_un address;
    memset(&address, 0, sizeof(address));
    address.sun_family = AF_UNIX;
    if (!droiddeck_producer_path(address.sun_path, sizeof(address.sun_path), getpid()) ||
        !droiddeck_producer_private_parent(address.sun_path)) return 0;
    struct stat existing;
    if (lstat(address.sun_path, &existing) == 0) {
        if (!S_ISSOCK(existing.st_mode) || existing.st_uid != getuid()) return 0;
        int probe = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
        if (probe < 0) return 0;
        int connected = connect(probe, (struct sockaddr *)&address, sizeof(address));
        int error = errno;
        close(probe);
        if (connected == 0 || error != ECONNREFUSED || unlink(address.sun_path) != 0) return 0;
    } else if (errno != ENOENT) return 0;
    server->listener = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0);
    if (server->listener < 0) return 0;
    if (bind(server->listener, (struct sockaddr *)&address, sizeof(address)) != 0) {
        close(server->listener);
        return 0;
    }
    if (chmod(address.sun_path, 0600) == 0 && listen(server->listener, 4) == 0 &&
        pthread_create(&server->thread, NULL, droiddeck_producer_serve, server) == 0) {
        strcpy(server->path, address.sun_path);
        return 1;
    }
    close(server->listener);
    unlink(address.sun_path);
    return 0;
}
#endif
