import errno
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

PRELOAD = Path(__file__).resolve().parents[1] / "linuxfs/preload"

SHIM = r"""
#define _GNU_SOURCE
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>
#include <unistd.h>

#ifndef SYS_close_range
#define SYS_close_range 436
#endif

FILE *fopen(const char *path, const char *mode) {
    static FILE *(*real)(const char *, const char *);
    if (!real) real = dlsym(RTLD_NEXT, "fopen");
    const char *snapshot = getenv("NETLINK_TEST_SNAPSHOT");
    if (snapshot && !strcmp(path, "/etc/droiddeck-net")) path = snapshot;
    return real(path, mode);
}

int close_range(unsigned int first, unsigned int last, int flags) {
    return syscall(SYS_close_range, (long)first, (long)last, (long)flags, 0L, 0L, 0L);
}

void closefrom(int first) {
    syscall(SYS_close_range, (long)first, (long)~0u, 0L, 0L, 0L, 0L);
}
"""

PROGRAM = r"""
#define _GNU_SOURCE
#include <arpa/inet.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/netlink.h>
#include <linux/rtnetlink.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>

static void require(int condition, const char *label) {
    if (!condition) {
        fprintf(stderr, "%s: errno=%d\n", label, errno);
        exit(1);
    }
}

static int route_socket(void) {
    int descriptor = socket(AF_NETLINK, SOCK_RAW, NETLINK_ROUTE);
    struct sockaddr_nl address = {.nl_family = AF_NETLINK};
    require(descriptor >= 0, "socket");
    require(bind(descriptor, (struct sockaddr *)&address, sizeof(address)) == 0, "bind");
    return descriptor;
}

static unsigned port_of(int descriptor) {
    struct sockaddr_nl address;
    socklen_t length = sizeof(address);
    require(getsockname(descriptor, (struct sockaddr *)&address, &length) == 0, "getsockname");
    require(length == sizeof(address) && address.nl_family == AF_NETLINK, "socket identity");
    return address.nl_pid;
}

static void send_link(int descriptor, int index, unsigned flags) {
    struct {
        struct nlmsghdr header;
        struct ifinfomsg info;
    } query = {
        .header = {.nlmsg_len = sizeof(query), .nlmsg_type = RTM_GETLINK,
                   .nlmsg_flags = NLM_F_REQUEST | flags, .nlmsg_seq = 42},
        .info = {.ifi_index = index}
    };
    struct sockaddr_nl destination = {.nl_family = AF_NETLINK};
    require(sendto(descriptor, &query, sizeof(query), 0,
                   (struct sockaddr *)&destination, sizeof(destination)) == sizeof(query), "send link");
}

static ssize_t receive(int descriptor, unsigned char *buffer, size_t capacity) {
    struct sockaddr_nl address;
    memset(&address, 0xff, sizeof(address));
    socklen_t length = sizeof(address);
    ssize_t received = recvfrom(descriptor, buffer, capacity, MSG_DONTWAIT,
                                (struct sockaddr *)&address, &length);
    require(received > 0, "recvfrom");
    require(length == sizeof(address) && address.nl_family == AF_NETLINK && address.nl_pid == 0,
            "kernel source");
    return received;
}

static void usable(int descriptor) {
    send_link(descriptor, 1, 0);
    unsigned port = port_of(descriptor);
    unsigned char buffer[4096];
    receive(descriptor, buffer, sizeof(buffer));
    struct nlmsghdr *header = (struct nlmsghdr *)buffer;
    require(header->nlmsg_type == RTM_NEWLINK && header->nlmsg_pid == port, "usable socket");
}

static void socket_info(int descriptor, int kind) {
    int value;
    socklen_t length = sizeof(value);
    require(getsockopt(descriptor, SOL_SOCKET, SO_TYPE, &value, &length) == 0
            && length == sizeof(value) && value == kind, "reported socket type");
    length = sizeof(value);
    require(getsockopt(descriptor, SOL_SOCKET, SO_DOMAIN, &value, &length) == 0
            && value == AF_NETLINK, "reported socket domain");
    length = sizeof(value);
    require(getsockopt(descriptor, SOL_SOCKET, SO_PROTOCOL, &value, &length) == 0
            && value == NETLINK_ROUTE, "reported socket protocol");
    length = 1;
    require(getsockopt(descriptor, SOL_SOCKET, SO_TYPE, &value, &length) == 0
            && length == 1, "short socket option");
    require(getsockopt(descriptor, SOL_SOCKET, SO_TYPE, NULL, &length) == -1
            && errno == EFAULT, "invalid socket option buffer");
}

static void socket_types_test(void) {
    const int kinds[] = {SOCK_RAW, SOCK_DGRAM};
    for (size_t index = 0; index < sizeof(kinds) / sizeof(kinds[0]); index++) {
        int descriptor = socket(AF_NETLINK, kinds[index], NETLINK_ROUTE);
        require(descriptor >= 0, "supported socket type");
        require(port_of(descriptor) == 0, "unbound port");
        require((fcntl(descriptor, F_GETFD) & FD_CLOEXEC) == 0, "caller cloexec flag");
        socket_info(descriptor, kinds[index]);
        usable(descriptor);
        close(descriptor);
        descriptor = socket(AF_NETLINK, kinds[index] | SOCK_CLOEXEC | SOCK_NONBLOCK, NETLINK_ROUTE);
        require(descriptor >= 0 && (fcntl(descriptor, F_GETFD) & FD_CLOEXEC)
                && (fcntl(descriptor, F_GETFL) & O_NONBLOCK), "requested socket flags");
        socket_info(descriptor, kinds[index]);
        close(descriptor);
    }
    require(socket(AF_NETLINK, SOCK_STREAM, NETLINK_ROUTE) == -1
            && errno == ESOCKTNOSUPPORT, "stream rejected");
    require(socket(AF_NETLINK, SOCK_SEQPACKET, NETLINK_ROUTE) == -1
            && errno == ESOCKTNOSUPPORT, "seqpacket rejected");
    require(socket(AF_NETLINK, SOCK_RAW | 0x40000000, NETLINK_ROUTE) == -1
            && errno == EINVAL, "unknown socket flag rejected");
    require(socket(AF_NETLINK, 15, NETLINK_ROUTE) == -1 && errno == EINVAL, "invalid socket type");
}

static void ports_test(int forked) {
    int descriptors[32];
    unsigned ports[32];
    for (int index = 0; index < 32; index++) {
        descriptors[index] = route_socket();
        ports[index] = port_of(descriptors[index]);
        require(ports[index] != 0, "bound port");
        for (int previous = 0; previous < index; previous++)
            require(ports[index] != ports[previous], "unique automatic port");
    }
    int duplicate = socket(AF_NETLINK, SOCK_DGRAM, NETLINK_ROUTE);
    require(duplicate >= 0, "explicit bind socket");
    struct sockaddr_nl address = {.nl_family = AF_NETLINK, .nl_pid = ports[0]};
    require(bind(duplicate, (struct sockaddr *)&address, sizeof(address)) == -1
            && errno == EADDRINUSE && port_of(duplicate) == 0, "duplicate explicit port");
    if (forked) {
        pid_t child = fork();
        require(child >= 0, "port fork");
        if (child == 0) {
            for (int index = 0; index < 32; index++) close(descriptors[index]);
            require(bind(duplicate, (struct sockaddr *)&address, sizeof(address)) == -1
                    && errno == EADDRINUSE, "cross process duplicate port");
            close(duplicate);
            _exit(0);
        }
        int status;
        require(waitpid(child, &status, 0) == child && WIFEXITED(status) && WEXITSTATUS(status) == 0,
                "port child");
    }
    int alias = dup(descriptors[0]);
    close(descriptors[0]);
    require(bind(duplicate, (struct sockaddr *)&address, sizeof(address)) == -1
            && errno == EADDRINUSE, "alias keeps port reserved");
    close(alias);
    require(bind(duplicate, (struct sockaddr *)&address, sizeof(address)) == 0
            && port_of(duplicate) == ports[0], "closed port reused");
    require(bind(duplicate, (struct sockaddr *)&address, sizeof(address)) == 0, "same port rebind");
    address.nl_pid++;
    require(bind(duplicate, (struct sockaddr *)&address, sizeof(address)) == -1
            && errno == EINVAL && port_of(duplicate) == ports[0], "different port rebind denied");
    usable(duplicate);
    close(duplicate);
    for (int index = 1; index < 32; index++) {
        usable(descriptors[index]);
        close(descriptors[index]);
    }
}

static void batch_test(int oversized) {
    int descriptor = route_socket();
    struct query {
        struct nlmsghdr header;
        struct ifinfomsg info;
    } queries[128];
    int count = oversized ? 128 : 40;
    for (int index = 0; index < count; index++) {
        memset(&queries[index], 0, sizeof(queries[index]));
        queries[index].header.nlmsg_len = sizeof(queries[index]);
        queries[index].header.nlmsg_type = RTM_GETLINK;
        queries[index].header.nlmsg_flags = NLM_F_REQUEST;
        queries[index].header.nlmsg_seq = index + 1;
        queries[index].info.ifi_index = 1;
    }
    struct iovec parts[2] = {
        {.iov_base = queries, .iov_len = 256},
        {.iov_base = (unsigned char *)queries + 256, .iov_len = count * sizeof(queries[0]) - 256}
    };
    struct sockaddr_nl address = {.nl_family = AF_NETLINK};
    struct msghdr message = {.msg_name = &address, .msg_namelen = sizeof(address),
                            .msg_iov = parts, .msg_iovlen = 2};
    ssize_t sent = sendmsg(descriptor, &message, 0);
    unsigned char buffer[8192];
    if (oversized) {
        require(sent == -1 && errno == EMSGSIZE, "oversized reply rejected");
        require(recv(descriptor, buffer, sizeof(buffer), MSG_DONTWAIT) == -1 && errno == EAGAIN,
                "no partial batch reply");
    } else {
        require(sent == count * sizeof(queries[0]), "large scatter gather request");
        int remaining = (int)receive(descriptor, buffer, sizeof(buffer));
        int replies = 0;
        for (struct nlmsghdr *header = (struct nlmsghdr *)buffer;
             NLMSG_OK(header, remaining); header = NLMSG_NEXT(header, remaining)) {
            require(header->nlmsg_type == RTM_NEWLINK && header->nlmsg_seq == (unsigned)replies + 1,
                    "batch reply sequence");
            replies++;
        }
        require(replies == count, "complete batch");
    }
    parts[0].iov_base = NULL;
    require(sendmsg(descriptor, &message, 0) == -1 && errno == EFAULT, "invalid iov base");
    parts[0].iov_base = queries;
    parts[0].iov_len = SIZE_MAX;
    require(sendmsg(descriptor, &message, 0) == -1 && errno == EMSGSIZE, "iov length overflow");
    require(sendmsg(descriptor, NULL, 0) == -1 && errno == EFAULT, "invalid message");
    usable(descriptor);
    close(descriptor);
}

static void inherited_test(int argc, char **argv) {
    require(argc == 6, "inherited arguments");
    int descriptor = atoi(argv[2]), closed = atoi(argv[4]), datagram = atoi(argv[5]);
    unsigned port = (unsigned)strtoul(argv[3], NULL, 10);
    require(fcntl(closed, F_GETFD) == -1 && errno == EBADF, "cloexec descriptor closed");
    require(port_of(descriptor) == port && !(fcntl(descriptor, F_GETFD) & FD_CLOEXEC),
            "inherited bound port");
    socket_info(descriptor, SOCK_RAW);
    socket_info(datagram, SOCK_DGRAM);
    require(fcntl(datagram, F_GETFL) & O_NONBLOCK, "inherited nonblocking flag");
    unsigned char buffer[4096];
    receive(descriptor, buffer, sizeof(buffer));
    require(((struct nlmsghdr *)buffer)->nlmsg_type == RTM_NEWLINK, "queued reply survives exec");
    send_link(descriptor, 9999, NLM_F_ACK);
    receive(descriptor, buffer, sizeof(buffer));
    require(((struct nlmsghdr *)buffer)->nlmsg_type == NLMSG_ERROR
            && (((struct nlmsghdr *)buffer)->nlmsg_flags & NLM_F_CAPPED), "cap ack survives exec");
    usable(descriptor);
    usable(datagram);
    int fresh = route_socket();
    require(port_of(fresh) != port && port_of(fresh) != port_of(datagram), "ports after exec");
    close(fresh);
    close(descriptor);
    close(datagram);
}

static void exec_test(const char *program, int forked) {
    int descriptor = route_socket();
    int enabled = 1;
    require(setsockopt(descriptor, SOL_NETLINK, NETLINK_CAP_ACK, &enabled, sizeof(enabled)) == 0,
            "inherited cap ack");
    unsigned port = port_of(descriptor);
    int alias = fcntl(descriptor, F_DUPFD, 100);
    int closed = fcntl(descriptor, F_DUPFD_CLOEXEC, 200);
    require(alias >= 100 && closed >= 200, "exec aliases");
    close(descriptor);
    int datagram = socket(AF_NETLINK, SOCK_DGRAM | SOCK_NONBLOCK, NETLINK_ROUTE);
    require(datagram >= 0, "exec datagram");
    send_link(alias, 1, 0);
    char descriptor_arg[32], port_arg[32], closed_arg[32], datagram_arg[32];
    snprintf(descriptor_arg, sizeof(descriptor_arg), "%d", alias);
    snprintf(port_arg, sizeof(port_arg), "%u", port);
    snprintf(closed_arg, sizeof(closed_arg), "%d", closed);
    snprintf(datagram_arg, sizeof(datagram_arg), "%d", datagram);
    char *arguments[] = {(char *)program, "inherited", descriptor_arg, port_arg, closed_arg, datagram_arg, NULL};
    if (forked) {
        pid_t child = fork();
        require(child >= 0, "exec fork");
        if (child > 0) {
            int status;
            require(waitpid(child, &status, 0) == child && WIFEXITED(status) && WEXITSTATUS(status) == 0,
                    "exec child");
            usable(alias);
            usable(datagram);
            close(alias);
            close(closed);
            close(datagram);
            return;
        }
    }
    execv(program, arguments);
    require(0, "exec");
}

static void cloexec_test(const char *program) {
    int descriptor = socket(AF_NETLINK, SOCK_RAW | SOCK_CLOEXEC, NETLINK_ROUTE);
    require(descriptor >= 0, "cloexec socket");
    struct sockaddr_nl address = {.nl_family = AF_NETLINK, .nl_pid = (unsigned)getpid()};
    require(bind(descriptor, (struct sockaddr *)&address, sizeof(address)) == 0, "cloexec bind");
    char port_arg[32];
    snprintf(port_arg, sizeof(port_arg), "%u", address.nl_pid);
    char *arguments[] = {(char *)program, "rebind_after_exec", port_arg, NULL};
    execv(program, arguments);
    require(0, "cloexec exec");
}

static void ack_test(const char *mode) {
    int descriptor = route_socket();
    int capped = !strcmp(mode, "capped");
    if (capped) {
        int enabled = 1;
        require(setsockopt(descriptor, SOL_NETLINK, NETLINK_CAP_ACK, &enabled, sizeof(enabled)) == 0,
                "cap ack");
    }
    int rejected = !strcmp(mode, "error") || capped;
    send_link(descriptor, rejected ? 9999 : 1, NLM_F_ACK);
    unsigned char buffer[4096];
    int remaining = (int)receive(descriptor, buffer, sizeof(buffer));
    int acknowledgements = 0, links = 0;
    for (struct nlmsghdr *header = (struct nlmsghdr *)buffer;
         NLMSG_OK(header, remaining); header = NLMSG_NEXT(header, remaining)) {
        require(header->nlmsg_seq == 42 && header->nlmsg_pid == port_of(descriptor), "ack identity");
        if (header->nlmsg_type == RTM_NEWLINK) links++;
        if (header->nlmsg_type != NLMSG_ERROR) continue;
        struct nlmsgerr *reply = NLMSG_DATA(header);
        require(reply->error == (rejected ? -ENODEV : 0), "ack error");
        require(reply->msg.nlmsg_type == RTM_GETLINK && reply->msg.nlmsg_seq == 42, "ack request");
        require(!!(header->nlmsg_flags & NLM_F_CAPPED) == (!rejected || capped), "ack capped flag");
        size_t extra = rejected && !capped ? sizeof(struct ifinfomsg) : 0;
        require(header->nlmsg_len == NLMSG_LENGTH(sizeof(*reply) + extra), "ack payload length");
        acknowledgements++;
    }
    require(acknowledgements == 1 && links == !rejected, "one acknowledgement");
    close(descriptor);
}

static void options_test(void) {
    int descriptor = route_socket();
    int group = RTNLGRP_LINK;
    require(setsockopt(descriptor, SOL_NETLINK, NETLINK_ADD_MEMBERSHIP, &group, sizeof(group)) == -1
            && errno == EACCES, "membership denied");
    require(setsockopt(descriptor, SOL_NETLINK, NETLINK_PKTINFO, &group, sizeof(group)) == -1
            && errno == ENOPROTOOPT, "unsupported option");
    require(setsockopt(descriptor, SOL_NETLINK, NETLINK_EXT_ACK, &group, sizeof(group)) == 0, "ext ack");
    struct timeval timeout = {.tv_usec = 30000};
    require(setsockopt(descriptor, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout)) == 0, "receive timeout");
    memset(&timeout, 0, sizeof(timeout));
    socklen_t length = sizeof(timeout);
    require(getsockopt(descriptor, SOL_SOCKET, SO_RCVTIMEO, &timeout, &length) == 0
            && timeout.tv_usec > 0, "timeout retained");
    unsigned char buffer[4096];
    require(recv(descriptor, buffer, sizeof(buffer), 0) == -1
            && (errno == EAGAIN || errno == EWOULDBLOCK), "receive times out");
    close(descriptor);
}

static void dump_test(void) {
    int descriptor = route_socket();
    send_link(descriptor, 0, NLM_F_DUMP | NLM_F_ACK);
    unsigned char buffer[4096];
    int remaining = (int)receive(descriptor, buffer, sizeof(buffer));
    int links = 0, done = 0;
    for (struct nlmsghdr *header = (struct nlmsghdr *)buffer;
         NLMSG_OK(header, remaining); header = NLMSG_NEXT(header, remaining)) {
        require(header->nlmsg_seq == 42 && (header->nlmsg_flags & NLM_F_MULTI), "dump header");
        if (header->nlmsg_type == RTM_NEWLINK) links++;
        else {
            require(header->nlmsg_type == NLMSG_DONE && *(int *)NLMSG_DATA(header) == 0,
                    "dump completion");
            done++;
        }
    }
    require(links == 2 && done == 1, "dump results");
    close(descriptor);
}

static void send_timeout_test(int nonblocking) {
    int descriptor = route_socket();
    if (nonblocking) {
        require(fcntl(descriptor, F_SETFL, O_NONBLOCK) == 0, "nonblocking");
    } else {
        struct timeval timeout = {.tv_usec = 30000};
        require(setsockopt(descriptor, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout)) == 0,
                "send timeout");
    }
    struct {
        struct nlmsghdr header;
        struct ifinfomsg info;
    } query = {
        .header = {.nlmsg_len = sizeof(query), .nlmsg_type = RTM_GETLINK,
                   .nlmsg_flags = NLM_F_REQUEST, .nlmsg_seq = 42},
        .info = {.ifi_index = 1}
    };
    for (int count = 0; count < 10000; count++) {
        ssize_t result = send(descriptor, &query, sizeof(query), 0);
        if (result == -1) {
            require(errno == EAGAIN || errno == EWOULDBLOCK, "full queue would block");
            close(descriptor);
            return;
        }
        require(result == sizeof(query), "queued send");
    }
    require(0, "queue filled");
}

static void connect_test(void) {
    int descriptor = route_socket();
    struct sockaddr_nl address = {.nl_family = AF_NETLINK};
    socklen_t length = sizeof(address);
    require(getpeername(descriptor, (struct sockaddr *)&address, &length) == 0
            && address.nl_family == AF_NETLINK && address.nl_pid == 0, "default kernel peer");
    require(connect(descriptor, (struct sockaddr *)&address, sizeof(address)) == 0, "connect kernel");
    memset(&address, 0xff, sizeof(address));
    length = sizeof(address);
    require(getpeername(descriptor, (struct sockaddr *)&address, &length) == 0
            && length == sizeof(address) && address.nl_family == AF_NETLINK
            && address.nl_pid == 0 && address.nl_groups == 0, "kernel peer");
    usable(descriptor);
    address.nl_pid = 1234;
    require(connect(descriptor, (struct sockaddr *)&address, sizeof(address)) == -1
            && errno == ECONNREFUSED, "userspace peer refused");
    address.nl_pid = 0;
    address.nl_groups = 1;
    require(connect(descriptor, (struct sockaddr *)&address, sizeof(address)) == -1
            && errno == EACCES, "multicast peer denied");
    struct sockaddr disconnect = {.sa_family = AF_UNSPEC};
    require(connect(descriptor, &disconnect, sizeof(disconnect)) == 0, "disconnect");
    length = sizeof(address);
    require(getpeername(descriptor, (struct sockaddr *)&address, &length) == 0
            && address.nl_family == AF_NETLINK && address.nl_pid == 0, "disconnected default peer");
    close(descriptor);
}

static void receive_test(void) {
    int descriptor = route_socket();
    send_link(descriptor, 1, 0);
    unsigned char buffer[4096];
    struct sockaddr_nl address;
    memset(&address, 0xff, sizeof(address));
    socklen_t length = sizeof(sa_family_t);
    require(recvfrom(descriptor, buffer, sizeof(buffer), MSG_PEEK,
                     (struct sockaddr *)&address, &length) > 0
            && length == sizeof(address) && address.nl_family == AF_NETLINK
            && address.nl_pid == (unsigned)-1, "truncated source");
    struct iovec part = {.iov_base = buffer, .iov_len = sizeof(buffer)};
    struct msghdr message = {.msg_name = &address, .msg_namelen = sizeof(address),
                            .msg_iov = &part, .msg_iovlen = 1};
    require(recvmsg(descriptor, &message, 0) > 0 && message.msg_namelen == sizeof(address)
            && address.nl_family == AF_NETLINK && address.nl_pid == 0, "recvmsg source");
    memset(&address, 0xff, sizeof(address));
    length = sizeof(address);
    require(recvfrom(descriptor, buffer, sizeof(buffer), MSG_DONTWAIT,
                     (struct sockaddr *)&address, &length) == -1 && errno == EAGAIN
            && address.nl_family == (unsigned short)-1, "failed receive preserves source");
    close(descriptor);
}

static void duplicate_test(const char *mode) {
    int descriptor = route_socket();
    unsigned port = port_of(descriptor);
    int duplicate;
    if (!strcmp(mode, "dup")) duplicate = dup(descriptor);
    else if (!strcmp(mode, "fcntl")) duplicate = fcntl(descriptor, F_DUPFD, 20);
    else if (!strcmp(mode, "fcntl_cloexec")) duplicate = fcntl(descriptor, F_DUPFD_CLOEXEC, 20);
    else {
        int target = open("/dev/null", O_RDONLY);
        require(target >= 0, "dup target");
        duplicate = !strcmp(mode, "dup2") ? dup2(descriptor, target) :
                dup3(descriptor, target, O_CLOEXEC);
    }
    require(duplicate >= 0 && port_of(duplicate) == port, "duplicate identity");
    require(dup2(descriptor, descriptor) == descriptor, "dup2 same fd");
    require(dup2(-1, descriptor) == -1 && errno == EBADF, "failed replacement");
    usable(descriptor);
    close(descriptor);
    usable(duplicate);
    close(duplicate);
    for (int pass = 0; pass < 16; pass++) {
        descriptor = route_socket();
        usable(descriptor);
        close(descriptor);
    }
}

static void replacement_test(int raw) {
    int descriptor = route_socket();
    int pair[2];
    require(socketpair(AF_UNIX, SOCK_DGRAM, 0, pair) == 0, "unix pair");
    int result = raw ? (int)syscall(SYS_dup3, (long)pair[0], (long)descriptor, 0L, 0L, 0L, 0L) :
            dup2(pair[0], descriptor);
    require(result == descriptor, "replace descriptor");
    struct sockaddr_storage address;
    socklen_t length = sizeof(address);
    require(getsockname(descriptor, (struct sockaddr *)&address, &length) == 0
            && address.ss_family == AF_UNIX, "replacement identity");
    const char payload[] = "unix payload";
    require(send(descriptor, payload, sizeof(payload), MSG_DONTWAIT) == sizeof(payload), "unix send");
    char buffer[64];
    require(recv(pair[1], buffer, sizeof(buffer), MSG_DONTWAIT) == sizeof(payload)
            && !memcmp(buffer, payload, sizeof(payload)), "unix receive");
    close(descriptor);
    close(pair[0]);
    close(pair[1]);
}

static void shared_test(int forked) {
    int descriptor = route_socket();
    if (forked) {
        pid_t child = fork();
        require(child >= 0, "fork");
        if (child == 0) {
            usable(descriptor);
            close(descriptor);
            _exit(0);
        }
        int status;
        require(waitpid(child, &status, 0) == child && WIFEXITED(status) && WEXITSTATUS(status) == 0,
                "child socket");
        usable(descriptor);
    } else {
        int alias = dup(descriptor);
        require(alias >= 0 && dup2(descriptor, alias) == alias, "replace same socket alias");
        int other = route_socket();
        require(dup2(descriptor, other) == other, "replace other socket");
        close(descriptor);
        usable(alias);
        close(alias);
        usable(other);
        close(other);
        return;
    }
    close(descriptor);
}

static void close_range_test(const char *mode) {
    int descriptor = route_socket();
    if (!strcmp(mode, "closefrom")) {
        void (*close_from)(int) = dlsym(RTLD_DEFAULT, "closefrom");
        require(close_from != NULL, "closefrom symbol");
        close_from(descriptor);
    } else {
        int (*close_range_fn)(unsigned int, unsigned int, int) = dlsym(RTLD_DEFAULT, "close_range");
        require(close_range_fn != NULL, "close_range symbol");
        if (!strcmp(mode, "cloexec")) {
            require(close_range_fn(descriptor, descriptor, 4) == 0, "close_range cloexec");
            usable(descriptor);
            close(descriptor);
        } else {
            require(close_range_fn(descriptor, descriptor, 1) == -1 && errno == EINVAL,
                    "invalid close_range");
            usable(descriptor);
            require(close_range_fn(descriptor, descriptor, 0) == 0, "close_range");
        }
    }
    for (int pass = 0; pass < 16; pass++) {
        descriptor = route_socket();
        usable(descriptor);
        close(descriptor);
    }
}

static void route_test(const char *destination) {
    int descriptor = route_socket();
    int family = strchr(destination, ':') ? AF_INET6 : AF_INET;
    int size = family == AF_INET ? 4 : 16;
    struct {
        struct nlmsghdr header;
        struct rtmsg info;
        struct rtattr attr;
        unsigned char destination[16];
    } query = {
        .header = {.nlmsg_type = RTM_GETROUTE, .nlmsg_flags = NLM_F_REQUEST, .nlmsg_seq = 42},
        .info = {.rtm_family = family, .rtm_dst_len = size * 8},
        .attr = {.rta_type = RTA_DST, .rta_len = RTA_LENGTH(size)}
    };
    query.header.nlmsg_len = NLMSG_LENGTH(sizeof(query.info)) + RTA_SPACE(size);
    require(inet_pton(family, destination, query.destination) == 1, "destination");
    struct sockaddr_nl address = {.nl_family = AF_NETLINK};
    require(sendto(descriptor, &query, query.header.nlmsg_len, 0,
                   (struct sockaddr *)&address, sizeof(address)) == query.header.nlmsg_len, "route send");
    unsigned char buffer[4096];
    receive(descriptor, buffer, sizeof(buffer));
    struct nlmsghdr *header = (struct nlmsghdr *)buffer;
    if (header->nlmsg_type == NLMSG_ERROR) {
        printf("error %d\n", ((struct nlmsgerr *)NLMSG_DATA(header))->error);
    } else {
        require(header->nlmsg_type == RTM_NEWROUTE, "route reply");
        struct rtmsg *info = NLMSG_DATA(header);
        int remaining = RTM_PAYLOAD(header);
        unsigned interface = 0;
        char gateway[INET6_ADDRSTRLEN] = "-", source[INET6_ADDRSTRLEN] = "-";
        for (struct rtattr *attr = RTM_RTA(info); RTA_OK(attr, remaining);
             attr = RTA_NEXT(attr, remaining)) {
            if (attr->rta_type == RTA_OIF) memcpy(&interface, RTA_DATA(attr), sizeof(interface));
            if (attr->rta_type == RTA_GATEWAY) inet_ntop(family, RTA_DATA(attr), gateway, sizeof(gateway));
            if (attr->rta_type == RTA_PREFSRC) inet_ntop(family, RTA_DATA(attr), source, sizeof(source));
        }
        printf("route %u %u %u %u %s %s\n", info->rtm_type, info->rtm_scope,
               info->rtm_table, interface, gateway, source);
    }
    close(descriptor);
}

int main(int argc, char **argv) {
    require(argc >= 2, "mode");
    if (!strcmp(argv[1], "ack") || !strcmp(argv[1], "error") || !strcmp(argv[1], "capped"))
        ack_test(argv[1]);
    else if (!strcmp(argv[1], "options")) options_test();
    else if (!strcmp(argv[1], "dump")) dump_test();
    else if (!strcmp(argv[1], "send_timeout")) send_timeout_test(0);
    else if (!strcmp(argv[1], "nonblocking")) send_timeout_test(1);
    else if (!strcmp(argv[1], "connect")) connect_test();
    else if (!strcmp(argv[1], "receive")) receive_test();
    else if (!strcmp(argv[1], "replace")) replacement_test(0);
    else if (!strcmp(argv[1], "raw_replace")) replacement_test(1);
    else if (!strcmp(argv[1], "shared")) shared_test(0);
    else if (!strcmp(argv[1], "fork")) shared_test(1);
    else if (!strcmp(argv[1], "socket_types")) socket_types_test();
    else if (!strcmp(argv[1], "ports")) ports_test(0);
    else if (!strcmp(argv[1], "cross_process_ports")) ports_test(1);
    else if (!strcmp(argv[1], "batch")) batch_test(0);
    else if (!strcmp(argv[1], "oversized_batch")) batch_test(1);
    else if (!strcmp(argv[1], "exec")) exec_test(argv[0], 0);
    else if (!strcmp(argv[1], "fork_exec")) exec_test(argv[0], 1);
    else if (!strcmp(argv[1], "inherited")) inherited_test(argc, argv);
    else if (!strcmp(argv[1], "all_cloexec")) cloexec_test(argv[0]);
    else if (!strcmp(argv[1], "rebind_after_exec")) {
        require(argc == 3, "rebind arguments");
        int descriptor = route_socket();
        require(port_of(descriptor) == (unsigned)strtoul(argv[2], NULL, 10), "exec releases closed ports");
        usable(descriptor);
        close(descriptor);
    }
    else if (!strcmp(argv[1], "range") || !strcmp(argv[1], "cloexec") || !strcmp(argv[1], "closefrom"))
        close_range_test(argv[1]);
    else if (!strcmp(argv[1], "route")) {
        require(argc >= 3, "route destination");
        route_test(argv[2]);
    } else duplicate_test(argv[1]);
    return 0;
}
"""

SNAPSHOT = """if wlan0 2 1500
mac 02:11:22:33:44:55
addr 192.168.1.10 24
addr 192.168.1.130 25
addr fe80::10 64
addr 2001:db8::10 64
gw 192.168.1.1
gw 2001:db8::1
"""


@unittest.skipUnless(shutil.which("cc"), "needs a C compiler")
class NetlinkPreloadTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.directory = Path(cls.temp.name)
        cls.preload = cls.directory / "libblsession.so"
        cls.shim = cls.directory / "libshim.so"
        cls.program = cls.directory / "program"
        (cls.directory / "shim.c").write_text(SHIM)
        (cls.directory / "program.c").write_text(PROGRAM)
        subprocess.run(["cc", "-shared", "-fPIC", "-O2", "-Wall", "-pthread",
                        "-o", str(cls.preload), *map(str, sorted(PRELOAD.glob("*.c"))), "-ldl"], check=True)
        subprocess.run(["cc", "-shared", "-fPIC", "-O2", "-Wall", "-o", str(cls.shim),
                        str(cls.directory / "shim.c"), "-ldl"], check=True)
        subprocess.run(["cc", "-O2", "-Wall", "-o", str(cls.program),
                        str(cls.directory / "program.c"), "-ldl"], check=True)
        cls.snapshot = cls.directory / "snapshot"
        cls.snapshot.write_text(SNAPSHOT)
        cls.direct = cls.directory / "direct"
        cls.direct.write_text("\n".join(line for line in SNAPSHOT.splitlines() if not line.startswith("gw ")) + "\n")
        cls.offline = cls.directory / "offline"
        cls.offline.write_text("")

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def run_program(self, mode, *arguments, snapshot=None):
        env = dict(os.environ, LD_PRELOAD=f"{self.preload}:{self.shim}",
                   NETLINK_TEST_SNAPSHOT=str(snapshot or self.snapshot),
                   BL_SYSV_DIR=str(self.directory / "sysv"))
        result = subprocess.run([str(self.program), mode, *arguments], env=env,
                                capture_output=True, text=True, timeout=5)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        return result.stdout.strip().split()

    def test_successful_queries_acknowledge_requests(self):
        self.run_program("ack")

    def test_failed_queries_return_only_one_error(self):
        self.run_program("error")

    def test_cap_ack_omits_the_request_payload(self):
        self.run_program("capped")

    def test_socket_types_flags_and_identity_match_netlink(self):
        self.run_program("socket_types")

    def test_more_than_eight_sockets_get_unique_ports_and_release_them(self):
        self.run_program("ports")

    def test_explicit_ports_cannot_collide_across_processes(self):
        self.run_program("cross_process_ports")

    def test_large_scatter_gather_requests_receive_all_their_replies(self):
        self.run_program("batch")

    def test_reply_overflow_and_invalid_iovecs_fail_without_unix_fallback(self):
        self.run_program("oversized_batch")

    def test_non_cloexec_sockets_and_queued_replies_survive_exec(self):
        self.run_program("exec")

    def test_fork_exec_does_not_break_the_parents_socket(self):
        self.run_program("fork_exec")

    def test_cloexec_sockets_release_their_ports_after_exec(self):
        self.run_program("all_cloexec")

    def test_subscription_is_denied_and_receive_timeout_is_applied(self):
        self.run_program("options")

    def test_dump_keeps_multipart_completion(self):
        self.run_program("dump")

    def test_full_reply_queues_respect_send_timeout_and_nonblocking_mode(self):
        for mode in ("send_timeout", "nonblocking"):
            with self.subTest(mode=mode):
                self.run_program(mode)

    def test_kernel_connect_and_disconnect(self):
        self.run_program("connect")

    def test_receive_sources_and_truncation(self):
        self.run_program("receive")

    def test_duplicates_share_the_socket_until_the_last_close(self):
        for mode in ("dup", "dup2", "dup3", "fcntl", "fcntl_cloexec"):
            with self.subTest(mode=mode):
                self.run_program(mode)

    def test_replacing_a_route_fd_does_not_intercept_unix_traffic(self):
        self.run_program("replace")

    def test_untracked_replacement_is_detected_by_socket_identity(self):
        self.run_program("raw_replace")

    def test_replacing_other_netlink_descriptors_preserves_shared_state(self):
        self.run_program("shared")

    def test_forked_sockets_remain_usable_in_both_processes(self):
        self.run_program("fork")

    def test_range_closes_release_slots_but_failures_and_cloexec_do_not(self):
        for mode in ("range", "cloexec", "closefrom"):
            with self.subTest(mode=mode):
                self.run_program(mode)

    def test_loopback_and_own_addresses_are_local_routes(self):
        for destination, source in (("127.0.0.2", "127.0.0.1"), ("::1", "::1"),
                                    ("192.168.1.10", "192.168.1.10"), ("2001:db8::10", "2001:db8::10")):
            with self.subTest(destination=destination):
                self.assertEqual(["route", "2", "254", "255", "1", "-", source],
                                 self.run_program("route", destination))

    def test_connected_routes_choose_the_longest_prefix_without_a_gateway(self):
        for destination, source in (("192.168.1.11", "192.168.1.10"),
                                    ("192.168.1.131", "192.168.1.130"),
                                    ("fe80::11", "fe80::10"), ("2001:db8::11", "2001:db8::10")):
            with self.subTest(destination=destination):
                self.assertEqual(["route", "1", "253", "254", "2", "-", source],
                                 self.run_program("route", destination, snapshot=self.direct))

    def test_other_destinations_use_a_default_gateway(self):
        self.assertEqual(["route", "1", "0", "254", "2", "192.168.1.1", "192.168.1.10"],
                         self.run_program("route", "8.8.8.8"))
        self.assertEqual(["route", "1", "0", "254", "2", "2001:db8::1", "2001:db8::10"],
                         self.run_program("route", "2001:db9::1"))

    def test_without_a_gateway_only_off_link_destinations_are_unreachable(self):
        self.assertEqual(["error", str(-errno.ENETUNREACH)],
                         self.run_program("route", "8.8.8.8", snapshot=self.direct))
        self.assertEqual(["route", "2", "254", "255", "1", "-", "127.0.0.1"],
                         self.run_program("route", "127.0.0.1", snapshot=self.offline))

    @unittest.skipUnless(shutil.which("ip"), "needs iproute2")
    def test_iproute2_dumps_and_lookups_accept_the_replies(self):
        env = dict(os.environ, LD_PRELOAD=f"{self.preload}:{self.shim}",
                   NETLINK_TEST_SNAPSHOT=str(self.snapshot),
                   BL_SYSV_DIR=str(self.directory / "sysv"))
        queries = ((["link", "show"], "wlan0"),
                   (["addr", "show"], "192.168.1.10/24"),
                   (["route", "show"], "default via 192.168.1.1 dev wlan0"),
                   (["route", "get", "127.0.0.1"], "local 127.0.0.1 dev lo"),
                   (["route", "get", "192.168.1.11"], "192.168.1.11 dev wlan0"),
                   (["-6", "route", "get", "fe80::11"], "fe80::11 dev wlan0"))
        for arguments, expected in queries:
            with self.subTest(arguments=arguments):
                result = subprocess.run([shutil.which("ip"), *arguments], env=env,
                                        capture_output=True, text=True, timeout=5)
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                self.assertIn(expected, result.stdout)


if __name__ == "__main__":
    unittest.main()
