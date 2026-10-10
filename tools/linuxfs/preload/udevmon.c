/*
 * Ported from WinNative (maxjivi05, feature/wayland-gamescope, 2026-09-18/19), GPL-3.0, with the
 * symbol prefix changed to match this library. The reasoning below is the original author's.
 *
 * udev's netlink monitor, stood in for where the sandbox refuses the socket it is built on.
 *
 * The app sandbox denies a NETLINK_KOBJECT_UEVENT socket outright. libudev cannot create a
 * monitor without one, and SDL's HID layer cannot start without a monitor: every SDL_hid_init()
 * fails, the Steam client's controller code retries it hundreds of times a second, its main loop
 * stalls, and the client asserts out from under the running game. Refusing SDL the library
 * altogether (the earlier answer here) is what produced that failure.
 *
 * So the socket is answered instead: one end of a datagram socketpair stands in for the netlink
 * socket, its peer held open so it never reads as hung up, and the few calls libudev makes on
 * the socket that only a netlink one could answer are answered here. Nothing is ever written to
 * it, so the monitor is live and silent, which is the most the kernel would deliver in a
 * container anyway. The real socket is tried first, so this stays out of the way wherever a
 * monitor could genuinely be created.
 *
 * The stand-in can also carry events after all (bl_udevmon_inject, below): libfakeinput plugs
 * Steam's touch controller in and out of the client that way.
 *
 * Wine is left without one. Its HID bus reads the session's pads itself only when its SDL bus
 * cannot start, and that is the path inputudev.c describes them on. Given a monitor the SDL bus
 * comes up and takes the pads over from it.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <linux/netlink.h>
#include <pthread.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <endian.h>
#include <stdio.h>
#include <unistd.h>

struct stand_in {
  int fd;       /* the descriptor handed out, -1 when the slot is free */
  int peer;     /* the other end, kept open */
  ino_t inode;  /* identifies the socket after fd is closed and its number reused */
};

static struct stand_in stand_ins[16];
static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;

static void lock_before_fork(void) { pthread_mutex_lock(&lock); }
static void unlock_after_fork(void) { pthread_mutex_unlock(&lock); }
static void reset_after_fork(void) { pthread_mutex_init(&lock, NULL); }

__attribute__((constructor)) static void init_udevmon(void) {
  for (size_t i = 0; i < sizeof(stand_ins) / sizeof(stand_ins[0]); i++)
    stand_ins[i].fd = -1;
  pthread_atfork(lock_before_fork, unlock_after_fork, reset_after_fork);
}

static ino_t socket_inode(int fd) {
  struct stat st;
  return fstat(fd, &st) == 0 ? st.st_ino : 0;
}

/* Whether fd is one of the stand-in sockets. A slot whose descriptor was closed and reused by
 * something else is retired on sight, so a later socket with the same number is not mistaken. */
static int is_stand_in(int fd) {
  int found = 0;
  if (fd < 0)
    return 0;
  pthread_mutex_lock(&lock);
  for (size_t i = 0; i < sizeof(stand_ins) / sizeof(stand_ins[0]); i++) {
    if (stand_ins[i].fd != fd)
      continue;
    if (stand_ins[i].inode == socket_inode(fd)) {
      found = 1;
    } else {
      close(stand_ins[i].peer);
      stand_ins[i].fd = -1;
    }
    break;
  }
  pthread_mutex_unlock(&lock);
  return found;
}

static int is_wine_process(void) {
  static int known;
  if (known == 0) {
    char exe[PATH_MAX];
    ssize_t n = readlink("/proc/self/exe", exe, sizeof(exe) - 1);
    const char *name = exe;
    if (n > 0) {
      exe[n] = '\0';
      name = strrchr(exe, '/');
      name = name != NULL ? name + 1 : exe;
    }
    known = n > 0 && strncmp(name, "wine", 4) == 0 ? 1 : -1;
  }
  return known == 1;
}

static int make_stand_in(int type) {
  int sv[2];
  int saved = errno;
  int flags = SOCK_DGRAM | (type & (SOCK_CLOEXEC | SOCK_NONBLOCK));
  if (socketpair(AF_UNIX, flags, 0, sv) != 0) {
    errno = saved;
    return -1;
  }
  /* The peer is ours alone: it must not follow the socket into a child. */
  fcntl(sv[1], F_SETFD, FD_CLOEXEC);
  pthread_mutex_lock(&lock);
  for (size_t i = 0; i < sizeof(stand_ins) / sizeof(stand_ins[0]); i++) {
    if (stand_ins[i].fd == -1) {
      stand_ins[i].fd = sv[0];
      stand_ins[i].peer = sv[1];
      stand_ins[i].inode = socket_inode(sv[0]);
      pthread_mutex_unlock(&lock);
      return sv[0];
    }
  }
  pthread_mutex_unlock(&lock);
  close(sv[0]);
  close(sv[1]);
  errno = saved;
  return -1;
}

int socket(int domain, int type, int protocol) {
  static int (*real_socket)(int, int, int);
  int fd;

  if (real_socket == NULL) real_socket = dlsym(RTLD_NEXT, "socket");
  if (real_socket == NULL) {
    errno = ENOSYS;
    return -1;
  }
  fd = real_socket(domain, type, protocol);
  if (fd >= 0 || domain != AF_NETLINK || protocol != NETLINK_KOBJECT_UEVENT)
    return fd;
  int refused = errno;
  if (is_wine_process()) {
    errno = refused;
    return -1;
  }
  return make_stand_in(type);
}

/* For net.c's bind(): whether fd is a stand-in, which accepts any netlink address as bound. */
__attribute__((visibility("hidden"))) int bl_udevmon_stand_in(int fd) {
  return is_stand_in(fd);
}

int getsockname(int fd, struct sockaddr *addr, socklen_t *len) {
  static int (*real_getsockname)(int, struct sockaddr *, socklen_t *);
  struct sockaddr_nl nl;

  if (is_stand_in(fd)) {
    if (addr == NULL || len == NULL) {
      errno = EFAULT;
      return -1;
    }
    memset(&nl, 0, sizeof(nl));
    nl.nl_family = AF_NETLINK;
    nl.nl_pid = (unsigned)getpid();
    memcpy(addr, &nl, *len < sizeof(nl) ? *len : sizeof(nl));
    *len = sizeof(nl);
    return 0;
  }
  if (real_getsockname == NULL) real_getsockname = dlsym(RTLD_NEXT, "getsockname");
  if (real_getsockname == NULL) {
    errno = ENOSYS;
    return -1;
  }
  return real_getsockname(fd, addr, len);
}

int setsockopt(int fd, int level, int optname, const void *optval, socklen_t optlen) {
  static int (*real_setsockopt)(int, int, int, const void *, socklen_t);

  if (is_stand_in(fd)) {
    /* Group membership and packet filters only mean something on a socket that receives; this
     * one never does, so they are accepted as set. The rest is genuine on a unix socket. */
    if (level == SOL_NETLINK)
      return 0;
    if (level == SOL_SOCKET && (optname == SO_ATTACH_FILTER || optname == SO_DETACH_FILTER))
      return 0;
  }
  if (real_setsockopt == NULL) real_setsockopt = dlsym(RTLD_NEXT, "setsockopt");
  if (real_setsockopt == NULL) {
    errno = ENOSYS;
    return -1;
  }
  return real_setsockopt(fd, level, optname, optval, optlen);
}

/* udevd's message to the "udev" multicast group (systemd, device-monitor.c): this header, then the
 * properties as NUL-separated KEY=VALUE strings. */
struct monitor_netlink_header {
  char prefix[8];
  unsigned magic;
  unsigned header_size;
  unsigned properties_off;
  unsigned properties_len;
  unsigned filter_subsystem_hash;
  unsigned filter_devtype_hash;
  unsigned filter_tag_bloom_hi;
  unsigned filter_tag_bloom_lo;
};

/* systemd's string_hash32: MurmurHash2, seed 0. */
static unsigned murmur_hash2(const char *key, size_t len) {
  const unsigned m = 0x5bd1e995;
  unsigned h = (unsigned)len;
  const unsigned char *data = (const unsigned char *)key;
  while (len >= 4) {
    unsigned k;
    memcpy(&k, data, 4);
    k *= m;
    k ^= k >> 24;
    k *= m;
    h *= m;
    h ^= k;
    data += 4;
    len -= 4;
  }
  switch (len) {
  case 3: h ^= data[2] << 16; /* fallthrough */
  case 2: h ^= data[1] << 8; /* fallthrough */
  case 1: h ^= data[0]; h *= m;
  }
  h ^= h >> 13;
  h *= m;
  h ^= h >> 15;
  return h;
}

/* Sends a device event to every stand-in in this process, as udevd would; returns how many got it.
 * For libfakeinput (found with dlsym), which knows when its devices come and go. */
__attribute__((visibility("default"))) int bl_udevmon_inject(const char *action, const char *devpath,
                                                             const char *subsystem, const char *devname,
                                                             unsigned major, unsigned minor) {
  static unsigned long long seqnum = 900000;
  char props[1024];
  int used = snprintf(props, sizeof(props), "ACTION=%s%cDEVPATH=%s%cSUBSYSTEM=%s%cDEVNAME=%s%cMAJOR=%u%cMINOR=%u%cSEQNUM=%llu",
                      action, 0, devpath, 0, subsystem, 0, devname, 0, major, 0, minor, 0,
                      __atomic_add_fetch(&seqnum, 1, __ATOMIC_RELAXED));
  if (used <= 0 || (size_t)used >= sizeof(props)) return -1;
  used++; /* the last string's NUL */
  struct monitor_netlink_header header;
  memset(&header, 0, sizeof(header));
  memcpy(header.prefix, "libudev", 8);
  header.magic = htobe32(0xfeedcafe);
  header.header_size = sizeof(header);
  header.properties_off = sizeof(header);
  header.properties_len = (unsigned)used;
  header.filter_subsystem_hash = htobe32(murmur_hash2(subsystem, strlen(subsystem)));
  char message[sizeof(header) + sizeof(props)];
  memcpy(message, &header, sizeof(header));
  memcpy(message + sizeof(header), props, (size_t)used);
  int sent = 0;
  pthread_mutex_lock(&lock);
  for (size_t i = 0; i < sizeof(stand_ins) / sizeof(stand_ins[0]); i++) {
    if (stand_ins[i].fd == -1) continue;
    if (send(stand_ins[i].peer, message, sizeof(header) + (size_t)used, MSG_DONTWAIT | MSG_NOSIGNAL) > 0) sent++;
  }
  pthread_mutex_unlock(&lock);
  return sent;
}

/* For ntsync.c's recvmsg(), which owns the symbol: 1 when fd is a stand-in, answered here (*result
 * is what recvmsg returns), 0 otherwise. A message is dressed as udevd's: libudev drops one that
 * does not come from the udev multicast group with root credentials. The sender address goes in
 * the room msg_namelen had before the call; a unix socket sets it to 0. */
__attribute__((visibility("hidden"))) int bl_udevmon_recvmsg(int fd, struct msghdr *msg, int flags,
                                                             ssize_t (*real_recvmsg)(int, struct msghdr *, int),
                                                             ssize_t *result) {
  if (!is_stand_in(fd)) return 0;
  size_t control_room = msg->msg_controllen;
  socklen_t name_room = msg->msg_namelen;
  ssize_t n = real_recvmsg(fd, msg, flags);
  *result = n;
  if (n < 0) return 1;
  if (msg->msg_name != NULL && name_room >= sizeof(struct sockaddr_nl)) {
    struct sockaddr_nl nl;
    memset(&nl, 0, sizeof(nl));
    nl.nl_family = AF_NETLINK;
    nl.nl_groups = 2; /* udev's group */
    memcpy(msg->msg_name, &nl, sizeof(nl));
    msg->msg_namelen = sizeof(nl);
  }
  if (msg->msg_control != NULL && control_room >= CMSG_SPACE(sizeof(struct ucred))) {
    struct cmsghdr *cmsg = (struct cmsghdr *)msg->msg_control;
    cmsg->cmsg_level = SOL_SOCKET;
    cmsg->cmsg_type = SCM_CREDENTIALS;
    cmsg->cmsg_len = CMSG_LEN(sizeof(struct ucred));
    struct ucred cred = {.pid = 1, .uid = 0, .gid = 0};
    memcpy(CMSG_DATA(cmsg), &cred, sizeof(cred));
    msg->msg_controllen = CMSG_SPACE(sizeof(struct ucred));
  }
  return 1;
}
