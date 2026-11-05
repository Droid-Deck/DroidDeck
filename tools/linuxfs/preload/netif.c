/*
 * Ported from WinNative (maxjivi05, feature/wayland-gamescope, 2026-09-18/19), GPL-3.0.
 */

#define _GNU_SOURCE 1
#include <arpa/inet.h>
#include <dlfcn.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <ifaddrs.h>
#include <linux/if_addr.h>
#include <linux/if_link.h>
#include <linux/netlink.h>
#include <linux/rtnetlink.h>
#include <limits.h>
#include <net/if.h>
#include <net/if_arp.h>
#include <netpacket/packet.h>
#include <netinet/in.h>
#include <pthread.h>
#include <stdarg.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#define LINK_FILE "/etc/droiddeck-net"
#define LO_NAME "lo"
#define LO_INDEX 1
#define LO_MTU 65536
#define MAX_ADDRS 16
#define FRESH_NS 1000000000LL

struct link_addr {
    int family;
    unsigned char bytes[16];
    unsigned prefix;
};

struct link {
    char name[IFNAMSIZ];
    unsigned index;
    int mtu;
    unsigned char mac[6];
    struct link_addr addrs[MAX_ADDRS];
    int addr_count;
    struct link_addr gateways[2];
    int gateway_count;
};

static int parse_addr(const char *text, struct link_addr *out) {
    memset(out, 0, sizeof(*out));
    if (inet_pton(AF_INET, text, out->bytes) == 1) out->family = AF_INET;
    else if (inet_pton(AF_INET6, text, out->bytes) == 1) out->family = AF_INET6;
    else return 0;
    return 1;
}

typedef FILE *(*fopen_fn)(const char *, const char *);

static int read_link(struct link *link) {
    static fopen_fn real_fopen;
    if (real_fopen == NULL) real_fopen = (fopen_fn) dlsym(RTLD_NEXT, "fopen");
    FILE *f = real_fopen ? real_fopen(LINK_FILE, "re") : NULL;
    if (f == NULL) return 0;

    memset(link, 0, sizeof(*link));
    char line[160], text[64];
    unsigned m[6], prefix;
    while (fgets(line, sizeof(line), f)) {
        if (sscanf(line, "if %15s %u %d", link->name, &link->index, &link->mtu) == 3) continue;
        if (sscanf(line, "mac %2x:%2x:%2x:%2x:%2x:%2x", m, m + 1, m + 2, m + 3, m + 4, m + 5) == 6) {
            for (int i = 0; i < 6; i++) link->mac[i] = (unsigned char) m[i];
        } else if (sscanf(line, "addr %63s %u", text, &prefix) == 2) {
            struct link_addr addr;
            if (link->addr_count < MAX_ADDRS && parse_addr(text, &addr)
                    && prefix <= (addr.family == AF_INET ? 32u : 128u)) {
                addr.prefix = prefix;
                link->addrs[link->addr_count++] = addr;
            }
        } else if (sscanf(line, "gw %63s", text) == 1) {
            struct link_addr addr;
            if (link->gateway_count < 2 && parse_addr(text, &addr)) {
                link->gateways[link->gateway_count++] = addr;
            }
        }
    }
    fclose(f);
    return link->name[0] != '\0' && link->index > LO_INDEX && link->addr_count > 0;
}

static long long now_ns(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC_COARSE, &ts);
    return ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

static int fresh(long long *at) {
    long long then = __atomic_load_n(at, __ATOMIC_RELAXED);
    return then != 0 && now_ns() - then < FRESH_NS;
}

static pthread_mutex_t link_lock = PTHREAD_MUTEX_INITIALIZER;
static struct link link_read;
static int link_ok;
static long long link_at;

static int load_link(struct link *link) {
    pthread_mutex_lock(&link_lock);
    if (!fresh(&link_at)) {
        link_ok = read_link(&link_read);
        __atomic_store_n(&link_at, now_ns(), __ATOMIC_RELAXED);
    }
    int ok = link_ok;
    if (ok) *link = link_read;
    pthread_mutex_unlock(&link_lock);
    return ok;
}

static void link_lock_before_fork(void) { pthread_mutex_lock(&link_lock); }
static void link_unlock_after_fork(void) { pthread_mutex_unlock(&link_lock); }
static void link_reset_after_fork(void) { pthread_mutex_init(&link_lock, NULL); }

__attribute__((constructor)) static void install_link_fork_handlers(void) {
    pthread_atfork(link_lock_before_fork, link_unlock_after_fork, link_reset_after_fork);
}

static long long ifaddrs_unusable_at, nameindex_unusable_at;

static void prefix_mask(int family, unsigned prefix, unsigned char *out) {
    int len = family == AF_INET ? 4 : 16;
    for (int i = 0; i < len; i++, prefix -= prefix < 8 ? prefix : 8) {
        out[i] = prefix >= 8 ? 0xff : (unsigned char) (0xff00 >> prefix);
    }
}

static int is_link_local(const struct link_addr *addr) {
    return addr->family == AF_INET6 && addr->bytes[0] == 0xfe && (addr->bytes[1] & 0xc0) == 0x80;
}

struct entry {
    struct ifaddrs ifa;
    struct sockaddr_storage addr, mask, extra;
    char name[IFNAMSIZ];
};

static struct entry *fill_entry(struct entry *e, const char *name, unsigned flags) {
    snprintf(e->name, sizeof(e->name), "%s", name);
    e->ifa.ifa_name = e->name;
    e->ifa.ifa_flags = flags;
    e->ifa.ifa_addr = (struct sockaddr *) &e->addr;
    e[-1].ifa.ifa_next = &e->ifa;
    return e;
}

static void fill_packet(struct entry *e, unsigned index, const unsigned char *mac) {
    struct sockaddr_ll *ll = (struct sockaddr_ll *) &e->addr;
    ll->sll_family = AF_PACKET;
    ll->sll_ifindex = (int) index;
    ll->sll_hatype = mac ? ARPHRD_ETHER : ARPHRD_LOOPBACK;
    ll->sll_halen = 6;
    if (mac) memcpy(ll->sll_addr, mac, 6);
}

static void fill_inet(struct entry *e, const struct link_addr *addr, unsigned index) {
    unsigned char mask[16];
    prefix_mask(addr->family, addr->prefix, mask);
    e->ifa.ifa_netmask = (struct sockaddr *) &e->mask;
    if (addr->family == AF_INET) {
        struct sockaddr_in *sin = (struct sockaddr_in *) &e->addr;
        struct sockaddr_in *smask = (struct sockaddr_in *) &e->mask;
        sin->sin_family = smask->sin_family = AF_INET;
        memcpy(&sin->sin_addr, addr->bytes, 4);
        memcpy(&smask->sin_addr, mask, 4);
        if (e->ifa.ifa_flags & IFF_BROADCAST) {
            struct sockaddr_in *bcast = (struct sockaddr_in *) &e->extra;
            bcast->sin_family = AF_INET;
            bcast->sin_addr.s_addr = sin->sin_addr.s_addr | ~smask->sin_addr.s_addr;
            e->ifa.ifa_broadaddr = (struct sockaddr *) bcast;
        }
    } else {
        struct sockaddr_in6 *sin6 = (struct sockaddr_in6 *) &e->addr;
        struct sockaddr_in6 *smask = (struct sockaddr_in6 *) &e->mask;
        sin6->sin6_family = smask->sin6_family = AF_INET6;
        memcpy(&sin6->sin6_addr, addr->bytes, 16);
        memcpy(&smask->sin6_addr, mask, 16);
        if (is_link_local(addr)) sin6->sin6_scope_id = index;
    }
}

static const struct link_addr LO_V4 = { AF_INET, { 127, 0, 0, 1 }, 8 };
static const struct link_addr LO_V6 = { AF_INET6, { [15] = 1 }, 128 };

static int real_list_usable(const struct ifaddrs *list) {
    for (const struct ifaddrs *e = list; e; e = e->ifa_next) {
        if (e->ifa_addr && e->ifa_addr->sa_family == AF_INET && !(e->ifa_flags & IFF_LOOPBACK)) {
            return 1;
        }
    }
    return 0;
}

typedef void (*freeifaddrs_fn)(struct ifaddrs *);
static freeifaddrs_fn real_freeifaddrs;

int getifaddrs(struct ifaddrs **out) {
    typedef int (*getifaddrs_fn)(struct ifaddrs **);
    static getifaddrs_fn real;
    if (real == NULL) real = (getifaddrs_fn) dlsym(RTLD_NEXT, "getifaddrs");
    if (real_freeifaddrs == NULL) real_freeifaddrs = (freeifaddrs_fn) dlsym(RTLD_NEXT, "freeifaddrs");
    struct ifaddrs *list = NULL;
    int asked = !fresh(&ifaddrs_unusable_at);
    int rc = -1, saved = errno;
    if (asked) {
        rc = real(&list);
        saved = errno;
        if (rc == 0 && real_list_usable(list)) {
            *out = list;
            return 0;
        }
    }

    struct link link;
    if (!load_link(&link)) {
        if (!asked) return real(out);
        if (rc == 0) *out = list;
        errno = saved;
        return rc;
    }
    if (asked) __atomic_store_n(&ifaddrs_unusable_at, now_ns(), __ATOMIC_RELAXED);
    if (rc == 0 && list) real_freeifaddrs(list);

    struct entry *block = calloc((size_t) link.addr_count + 5, sizeof(*block));
    if (block == NULL) {
        errno = ENOMEM;
        return -1;
    }
    unsigned lo_flags = IFF_UP | IFF_RUNNING | IFF_LOOPBACK;
    unsigned up_flags = IFF_UP | IFF_RUNNING | IFF_BROADCAST | IFF_MULTICAST;
    struct entry *e = block;
    fill_packet(fill_entry(++e, LO_NAME, lo_flags), LO_INDEX, NULL);
    fill_inet(fill_entry(++e, LO_NAME, lo_flags), &LO_V4, LO_INDEX);
    fill_inet(fill_entry(++e, LO_NAME, lo_flags), &LO_V6, LO_INDEX);
    fill_packet(fill_entry(++e, link.name, up_flags), link.index, link.mac);
    for (int i = 0; i < link.addr_count; i++) {
        fill_inet(fill_entry(++e, link.name, up_flags), &link.addrs[i], link.index);
    }
    block[1].ifa.ifa_data = &block[1].ifa;
    *out = &block[1].ifa;
    return 0;
}

void freeifaddrs(struct ifaddrs *list) {
    if (list == NULL) return;
    if (list->ifa_data == list) {
        free((struct entry *) list - 1);
        return;
    }
    if (real_freeifaddrs == NULL) real_freeifaddrs = (freeifaddrs_fn) dlsym(RTLD_NEXT, "freeifaddrs");
    real_freeifaddrs(list);
}

struct name_block {
    struct if_nameindex index[3];
    char names[2][IFNAMSIZ];
};

struct if_nameindex *if_nameindex(void) {
    typedef struct if_nameindex *(*if_nameindex_fn)(void);
    static if_nameindex_fn real;
    if (real == NULL) real = (if_nameindex_fn) dlsym(RTLD_NEXT, "if_nameindex");
    struct if_nameindex *list = NULL;
    int asked = !fresh(&nameindex_unusable_at);
    int saved = errno;
    if (asked) {
        list = real();
        saved = errno;
        if (list && list[0].if_index != 0) return list;
    }

    struct link link;
    struct name_block *block;
    if (!load_link(&link)) {
        if (!asked) return real();
        errno = saved;
        return list;
    }
    if (asked) __atomic_store_n(&nameindex_unusable_at, now_ns(), __ATOMIC_RELAXED);
    if ((block = calloc(1, sizeof(*block))) == NULL) {
        if (asked) errno = saved;
        return list;
    }
    if (list) if_freenameindex(list);
    snprintf(block->names[0], IFNAMSIZ, "%s", LO_NAME);
    snprintf(block->names[1], IFNAMSIZ, "%s", link.name);
    block->index[0] = (struct if_nameindex) { LO_INDEX, block->names[0] };
    block->index[1] = (struct if_nameindex) { link.index, block->names[1] };
    return block->index;
}

void if_freenameindex(struct if_nameindex *list) {
    typedef void (*if_freenameindex_fn)(struct if_nameindex *);
    if (list == NULL) return;
    if (list[0].if_name == ((struct name_block *) list)->names[0]) {
        free(list);
        return;
    }
    ((if_freenameindex_fn) dlsym(RTLD_NEXT, "if_freenameindex"))(list);
}

unsigned int if_nametoindex(const char *name) {
    typedef unsigned int (*if_nametoindex_fn)(const char *);
    unsigned int index = ((if_nametoindex_fn) dlsym(RTLD_NEXT, "if_nametoindex"))(name);
    if (index != 0 || name == NULL) return index;
    int saved = errno;
    struct link link;
    if (strcmp(name, LO_NAME) == 0) return LO_INDEX;
    if (load_link(&link) && strcmp(name, link.name) == 0) return link.index;
    errno = saved;
    return 0;
}

char *if_indextoname(unsigned int index, char name[IF_NAMESIZE]) {
    typedef char *(*if_indextoname_fn)(unsigned int, char *);
    char *found = ((if_indextoname_fn) dlsym(RTLD_NEXT, "if_indextoname"))(index, name);
    if (found || name == NULL) return found;
    int saved = errno;
    struct link link;
    if (index == LO_INDEX) return strcpy(name, LO_NAME);
    if (load_link(&link) && index == link.index) return strcpy(name, link.name);
    errno = saved;
    return NULL;
}

static int mac_unusable(const struct ifreq *ifr) {
    const unsigned char *a = (const unsigned char *) ifr->ifr_hwaddr.sa_data;
    if (ifr->ifr_hwaddr.sa_family != ARPHRD_ETHER) return 1;
    int rest = a[1] | a[2] | a[3] | a[4] | a[5];
    return (a[0] & 0x03) || (rest == 0 && (a[0] == 0 || a[0] == 0x02));
}

static const struct link_addr *first_v4(const struct link *link) {
    for (int i = 0; i < link->addr_count; i++) {
        if (link->addrs[i].family == AF_INET) return &link->addrs[i];
    }
    return NULL;
}

static int answer_ifreq(unsigned long request, struct ifreq *ifr, int real_rc) {
    char name[IFNAMSIZ + 1] = { 0 };
    memcpy(name, ifr->ifr_name, IFNAMSIZ);
    int loopback = strcmp(name, LO_NAME) == 0;
    struct link link;
    if (!load_link(&link) || (!loopback && strcmp(name, link.name) != 0)) return 0;

    if (request == SIOCGIFHWADDR) {
        if (real_rc == 0 && (loopback || !mac_unusable(ifr))) return 0;
        memset(&ifr->ifr_hwaddr, 0, sizeof(ifr->ifr_hwaddr));
        ifr->ifr_hwaddr.sa_family = loopback ? ARPHRD_LOOPBACK : ARPHRD_ETHER;
        if (!loopback) memcpy(ifr->ifr_hwaddr.sa_data, link.mac, 6);
        return 1;
    }
    if (real_rc == 0) return 0;

    const struct link_addr *v4 = loopback ? &LO_V4 : first_v4(&link);
    struct sockaddr_in *sin = (struct sockaddr_in *) &ifr->ifr_addr;
    unsigned char mask[16];
    switch (request) {
    case SIOCGIFFLAGS:
        ifr->ifr_flags = loopback ? IFF_UP | IFF_RUNNING | IFF_LOOPBACK
                                  : IFF_UP | IFF_RUNNING | IFF_BROADCAST | IFF_MULTICAST;
        return 1;
    case SIOCGIFMTU:
        ifr->ifr_mtu = loopback ? LO_MTU : link.mtu;
        return 1;
    case SIOCGIFINDEX:
        ifr->ifr_ifindex = loopback ? LO_INDEX : (int) link.index;
        return 1;
    case SIOCGIFADDR:
    case SIOCGIFNETMASK:
    case SIOCGIFBRDADDR:
        if (v4 == NULL) return 0;
        prefix_mask(AF_INET, v4->prefix, mask);
        memset(sin, 0, sizeof(*sin));
        sin->sin_family = AF_INET;
        for (int i = 0; i < 4; i++) {
            unsigned char *out = (unsigned char *) &sin->sin_addr + i;
            *out = request == SIOCGIFNETMASK ? mask[i]
                 : request == SIOCGIFADDR ? v4->bytes[i]
                 : (unsigned char) (v4->bytes[i] | ~mask[i]);
        }
        return 1;
    default:
        return 0;
    }
}

static int is_link_query(unsigned long request) {
    return request == SIOCGIFHWADDR || request == SIOCGIFFLAGS || request == SIOCGIFMTU
        || request == SIOCGIFINDEX || request == SIOCGIFADDR || request == SIOCGIFNETMASK
        || request == SIOCGIFBRDADDR;
}

void bl_kgsl_chip_id_fixup(unsigned long request, void *arg, int rc);
#include "../../quest/kgsl_compat.h"
int bl_kgsl_poll(int (*real)(int, unsigned long, void *), int fd, unsigned long request,
                 void *arg, int *rc) __attribute__((visibility("hidden")));
void bl_kgsl_track(int (*real)(int, unsigned long, void *), int fd, unsigned long request,
                   void *arg, int *rc) __attribute__((visibility("hidden")));
int bl_ntsync_ioctl(int fd, unsigned long request, void *arg, int *rc) __attribute__((visibility("hidden")));
int bl_drm_gem_close(int fd, unsigned long request, void *arg, int *rc) __attribute__((visibility("hidden")));
int bl_venc_ioctl(int fd, unsigned long request, void *arg, int *rc) __attribute__((visibility("hidden")));
int bl_vdec_ioctl(int fd, unsigned long request, void *arg, int *rc) __attribute__((visibility("hidden")));
int bl_winebus_rumble_ioctl(int (*real)(int, unsigned long, void *), int fd, unsigned long request,
                            void *arg, void *caller, int *rc) __attribute__((visibility("hidden")));
int bl_writewatch_ioctl(int (*real)(int, unsigned long, void *), int fd, unsigned long request, void *arg,
                        int *rc) __attribute__((visibility("hidden")));

int ioctl(int fd, unsigned long request, ...) {
    typedef int (*ioctl_fn)(int, unsigned long, void *);
    static ioctl_fn real;
    va_list ap;
    va_start(ap, request);
    void *arg = va_arg(ap, void *);
    va_end(ap);

    int rc;
    if (bl_ntsync_ioctl(fd, request, arg, &rc)) return rc;
    if (bl_venc_ioctl(fd, request, arg, &rc)) return rc;
    if (bl_vdec_ioctl(fd, request, arg, &rc)) return rc;
    if (bl_drm_gem_close(fd, request, arg, &rc)) return rc;
    if (real == NULL) real = (ioctl_fn) dlsym(RTLD_NEXT, "ioctl");
    if (bl_winebus_rumble_ioctl(real, fd, request, arg, __builtin_return_address(0), &rc)) return rc;
    if (bl_writewatch_ioctl(real, fd, request, arg, &rc)) return rc;
    if (!bl_kgsl_poll(real, fd, request, arg, &rc)) rc = quest_kgsl_ioctl(real, fd, request, arg);
    bl_kgsl_track(real, fd, request, arg, &rc);
    bl_kgsl_chip_id_fixup(request, arg, rc);
    if (arg == NULL || !is_link_query(request)) return rc;
    int saved = errno;
    if (answer_ifreq(request, (struct ifreq *) arg, rc)) return 0;
    errno = saved;
    return rc;
}

static void print_v6(FILE *out, const unsigned char *bytes) {
    for (int i = 0; i < 16; i++) fprintf(out, "%02x", bytes[i]);
}

static unsigned v6_scope(const struct link_addr *addr) {
    if (memcmp(addr, &LO_V6, sizeof(*addr)) == 0) return 0x10;
    return is_link_local(addr) ? 0x20 : 0x00;
}

static void write_table(FILE *out, const char *table, const struct link *link) {
    static const unsigned char zero[16];
    unsigned char mask[16];
    if (strcmp(table, "route") == 0) {
        fprintf(out, "Iface\tDestination\tGateway \tFlags\tRefCnt\tUse\tMetric\tMask\t\tMTU\tWindow\tIRTT\n");
        for (int i = 0; i < link->gateway_count; i++) {
            if (link->gateways[i].family != AF_INET) continue;
            unsigned gw;
            memcpy(&gw, link->gateways[i].bytes, 4);
            fprintf(out, "%s\t00000000\t%08X\t0003\t0\t0\t0\t00000000\t0\t0\t0\n", link->name, gw);
        }
        for (int i = 0; i < link->addr_count; i++) {
            if (link->addrs[i].family != AF_INET) continue;
            unsigned addr, net;
            prefix_mask(AF_INET, link->addrs[i].prefix, mask);
            memcpy(&addr, link->addrs[i].bytes, 4);
            memcpy(&net, mask, 4);
            fprintf(out, "%s\t%08X\t00000000\t0001\t0\t0\t0\t%08X\t0\t0\t0\n",
                    link->name, addr & net, net);
        }
    } else if (strcmp(table, "ipv6_route") == 0) {
        for (int i = 0; i < link->gateway_count; i++) {
            if (link->gateways[i].family != AF_INET6) continue;
            print_v6(out, zero);
            fprintf(out, " 00 ");
            print_v6(out, zero);
            fprintf(out, " 00 ");
            print_v6(out, link->gateways[i].bytes);
            fprintf(out, " 00000400 00000001 00000000 00000003 %8s\n", link->name);
        }
        for (int i = 0; i < link->addr_count; i++) {
            if (link->addrs[i].family != AF_INET6) continue;
            unsigned char net[16];
            prefix_mask(AF_INET6, link->addrs[i].prefix, mask);
            for (int b = 0; b < 16; b++) net[b] = link->addrs[i].bytes[b] & mask[b];
            print_v6(out, net);
            fprintf(out, " %02x ", link->addrs[i].prefix);
            print_v6(out, zero);
            fprintf(out, " 00 ");
            print_v6(out, zero);
            fprintf(out, " 00000100 00000001 00000000 00000001 %8s\n", link->name);
        }
    } else if (strcmp(table, "if_inet6") == 0) {
        print_v6(out, LO_V6.bytes);
        fprintf(out, " %02x %02x %02x 80 %8s\n", LO_INDEX, LO_V6.prefix, v6_scope(&LO_V6), LO_NAME);
        for (int i = 0; i < link->addr_count; i++) {
            if (link->addrs[i].family != AF_INET6) continue;
            print_v6(out, link->addrs[i].bytes);
            fprintf(out, " %02x %02x %02x 80 %8s\n", link->index, link->addrs[i].prefix,
                    v6_scope(&link->addrs[i]), link->name);
        }
    } else if (strcmp(table, "dev") == 0) {
        fprintf(out, "Inter-|   Receive                                                |  Transmit\n"
                     " face |bytes    packets errs drop fifo frame compressed multicast|"
                     "bytes    packets errs drop fifo colls carrier compressed\n");
        fprintf(out, "%6s: 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0\n", LO_NAME);
        fprintf(out, "%6s: 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0\n", link->name);
    } else if (strcmp(table, "arp") == 0) {
        fprintf(out, "IP address       HW type     Flags       HW address            Mask     Device\n");
    } else {
        fprintf(out, "1\n");
    }
}

#define CARRIER_TABLE 5

static const char *const net_tables[] = { "route", "ipv6_route", "if_inet6", "dev", "arp", "carrier" };
static long long net_denied_at[sizeof(net_tables) / sizeof(net_tables[0])];

#define CARRIER_SLOTS 8

static struct { char path[64]; long long at; } carrier_denied[CARRIER_SLOTS];

static int denied_recently(int table, const char *path) {
    if (table != CARRIER_TABLE) return fresh(&net_denied_at[table]);
    int hit = 0;
    pthread_mutex_lock(&link_lock);
    for (int i = 0; i < CARRIER_SLOTS && !hit; i++) {
        hit = strcmp(carrier_denied[i].path, path) == 0 && fresh(&carrier_denied[i].at);
    }
    pthread_mutex_unlock(&link_lock);
    return hit;
}

static void remember_denial(int table, const char *path) {
    if (table != CARRIER_TABLE) {
        __atomic_store_n(&net_denied_at[table], now_ns(), __ATOMIC_RELAXED);
        return;
    }
    if (strlen(path) >= sizeof(carrier_denied[0].path)) return;
    pthread_mutex_lock(&link_lock);
    int slot = 0;
    for (int i = 0; i < CARRIER_SLOTS; i++) {
        if (strcmp(carrier_denied[i].path, path) == 0) { slot = i; break; }
        if (carrier_denied[i].at < carrier_denied[slot].at) slot = i;
    }
    snprintf(carrier_denied[slot].path, sizeof(carrier_denied[slot].path), "%s", path);
    carrier_denied[slot].at = now_ns();
    pthread_mutex_unlock(&link_lock);
}

static int denied_table(const char *path) {
    if (strncmp(path, "/proc/net/", 10) == 0) {
        for (int i = 0; i < CARRIER_TABLE; i++) {
            if (strcmp(path + 10, net_tables[i]) == 0) return i;
        }
        return -1;
    }
    size_t len = strlen(path);
    if (strncmp(path, "/sys/class/net/", 15) == 0 && len > 8 && strcmp(path + len - 8, "/carrier") == 0) {
        return CARRIER_TABLE;
    }
    return -1;
}

static FILE *synthesize(const char *table) {
    struct link link;
    if (!load_link(&link)) return NULL;
    int fd = memfd_create("droiddeck-net", MFD_CLOEXEC);
    if (fd < 0) return NULL;
    FILE *out = fdopen(fd, "w+");
    if (out == NULL) {
        close(fd);
        return NULL;
    }
    write_table(out, table, &link);
    if (fflush(out) != 0) {
        fclose(out);
        return NULL;
    }
    rewind(out);
    return out;
}

int bl_status_without_tracer(const char *path, int flags, const void *caller) __attribute__((visibility("hidden")));

static FILE *open_stream(fopen_fn real, const char *path, const char *mode, const void *caller) {
    int status = mode && mode[0] == 'r' && !strchr(mode, '+')
            ? bl_status_without_tracer(path, O_RDONLY | O_CLOEXEC, caller) : -1;
    if (status >= 0) {
        FILE *copy = fdopen(status, "r");
        if (copy == NULL) close(status);
        return copy;
    }
    int table = path && mode && mode[0] == 'r' ? denied_table(path) : -1;
    FILE *f;
    if (table >= 0 && denied_recently(table, path) && (f = synthesize(net_tables[table])) != NULL) return f;
    f = real(path, mode);
    if (f || table < 0) return f;
    int saved = errno;
    if ((f = synthesize(net_tables[table])) != NULL) {
        if (saved == EACCES) remember_denial(table, path);
        return f;
    }
    errno = saved;
    return NULL;
}

FILE *fopen(const char *path, const char *mode) {
    static fopen_fn real;
    if (real == NULL) real = (fopen_fn) dlsym(RTLD_NEXT, "fopen");
    return open_stream(real, path, mode, __builtin_return_address(0));
}

FILE *fopen64(const char *path, const char *mode) {
    static fopen_fn real;
    if (real == NULL) real = (fopen_fn) dlsym(RTLD_NEXT, "fopen64");
    return open_stream(real, path, mode, __builtin_return_address(0));
}

#define NL_REQUEST_MAX 1024
#define NL_REPLY_MAX 4096

#ifndef IFF_LOWER_UP
#define IFF_LOWER_UP 0x10000
#endif

#ifndef IF_OPER_UP
#define IF_OPER_UP 6
#endif

#ifndef SO_DOMAIN
#define SO_DOMAIN 39
#endif

#ifndef SO_PROTOCOL
#define SO_PROTOCOL 38
#endif

struct nl_socket {
    int peer;
    int reservation;
    dev_t device;
    ino_t inode;
    ino_t peer_inode;
    ino_t reservation_inode;
    unsigned port;
    unsigned references;
    int type;
    int cap_ack;
    struct nl_socket *next;
};

struct nl_fd {
    int fd;
    struct nl_socket *socket;
    struct nl_fd *next;
};

#define NL_FD_PAGE_SIZE 1024
#define NL_FD_BUCKETS 256
#define NL_FD_WORD_BITS (sizeof(unsigned long) * CHAR_BIT)

struct nl_fd_page {
    unsigned number;
    unsigned long bits[NL_FD_PAGE_SIZE / NL_FD_WORD_BITS];
    struct nl_fd_page *next;
};

struct nl_metadata {
    uint64_t nonce;
    uint32_t port;
    uint32_t type;
    uint32_t cap_ack;
    char magic[12];
};

_Static_assert(sizeof(struct nl_metadata) == 32, "netlink socket metadata ABI");

struct nl_packet {
    struct nl_packet *next;
    size_t len;
    unsigned char data[];
};

struct nl_reply {
    unsigned char data[NL_REPLY_MAX];
    size_t len;
    int overflow;
};

static const char nl_client_magic[12] = "bl-nl-c-v1";
static const char nl_peer_magic[12] = "bl-nl-p-v1";
static const char nl_reservation_magic[12] = "bl-nl-r-v1";
static struct nl_socket *nl_sockets;
static struct nl_fd *nl_fds;
static struct nl_fd_page *nl_fd_pages[NL_FD_BUCKETS];
static unsigned nl_fd_count;
static int nl_fd_index_incomplete;
static unsigned nl_next_port = 0x80000000u;
static pthread_mutex_t nl_sockets_lock = PTHREAD_MUTEX_INITIALIZER;
static __thread unsigned nl_reply_port;
static __thread int nl_reply_capped;

static void nl_lock_before_fork(void) { pthread_mutex_lock(&nl_sockets_lock); }
static void nl_unlock_after_fork(void) { pthread_mutex_unlock(&nl_sockets_lock); }
static void nl_reset_after_fork(void) { pthread_mutex_init(&nl_sockets_lock, NULL); }
static void nl_restore_inherited(void);

__attribute__((constructor)) static void nl_sockets_init(void) {
    pthread_atfork(nl_lock_before_fork, nl_unlock_after_fork, nl_reset_after_fork);
    nl_restore_inherited();
}

static struct nl_fd_page *nl_fd_page_of(int fd) {
    unsigned number = (unsigned) fd / NL_FD_PAGE_SIZE;
    struct nl_fd_page *page =
            __atomic_load_n(&nl_fd_pages[number % NL_FD_BUCKETS], __ATOMIC_ACQUIRE);
    for (; page; page = page->next) {
        if (page->number == number) return page;
    }
    return NULL;
}

static int nl_tracked(int fd) {
    if (fd < 0 || __atomic_load_n(&nl_fd_count, __ATOMIC_ACQUIRE) == 0) return 0;
    struct nl_fd_page *page = nl_fd_page_of(fd);
    unsigned offset = (unsigned) fd % NL_FD_PAGE_SIZE;
    if (page && (__atomic_load_n(&page->bits[offset / NL_FD_WORD_BITS], __ATOMIC_ACQUIRE)
            & (1ul << (offset % NL_FD_WORD_BITS)))) return 1;
    return __atomic_load_n(&nl_fd_index_incomplete, __ATOMIC_ACQUIRE);
}

static void nl_register(struct nl_fd *entry) {
    struct nl_fd_page *page = nl_fd_page_of(entry->fd);
    if (page == NULL) {
        page = calloc(1, sizeof(*page));
        if (page) {
            page->number = (unsigned) entry->fd / NL_FD_PAGE_SIZE;
            unsigned bucket = page->number % NL_FD_BUCKETS;
            page->next = nl_fd_pages[bucket];
            __atomic_store_n(&nl_fd_pages[bucket], page, __ATOMIC_RELEASE);
        } else {
            __atomic_store_n(&nl_fd_index_incomplete, 1, __ATOMIC_RELEASE);
        }
    }
    entry->next = nl_fds;
    nl_fds = entry;
    if (page) {
        unsigned offset = (unsigned) entry->fd % NL_FD_PAGE_SIZE;
        __atomic_fetch_or(&page->bits[offset / NL_FD_WORD_BITS],
                          1ul << (offset % NL_FD_WORD_BITS), __ATOMIC_RELEASE);
    }
    __atomic_fetch_add(&nl_fd_count, 1, __ATOMIC_RELEASE);
}

static int nl_identity(int fd, dev_t device, ino_t inode) {
    struct stat status;
    return fstat(fd, &status) == 0 && S_ISSOCK(status.st_mode)
            && status.st_dev == device && status.st_ino == inode;
}

static int nl_address(int fd, int peer, struct sockaddr_un *addr, struct nl_metadata *metadata,
                       const char *magic) {
    static int (*real_name)(int, struct sockaddr *, socklen_t *);
    static int (*real_peer)(int, struct sockaddr *, socklen_t *);
    if (real_name == NULL) real_name = (int (*)(int, struct sockaddr *, socklen_t *))
            dlsym(RTLD_NEXT, "getsockname");
    if (real_peer == NULL) real_peer = (int (*)(int, struct sockaddr *, socklen_t *))
            dlsym(RTLD_NEXT, "getpeername");
    socklen_t len = sizeof(*addr);
    memset(addr, 0, sizeof(*addr));
    int result = (peer ? real_peer : real_name)(fd, (struct sockaddr *) addr, &len);
    if (result != 0 || len != offsetof(struct sockaddr_un, sun_path) + 1 + sizeof(*metadata)
            || addr->sun_family != AF_UNIX || addr->sun_path[0] != '\0') return 0;
    memcpy(metadata, addr->sun_path + 1, sizeof(*metadata));
    return memcmp(metadata->magic, magic, sizeof(metadata->magic)) == 0;
}

static int nl_bind_address(int fd, struct nl_metadata *metadata, struct sockaddr_un *addr) {
    static int (*real_bind)(int, const struct sockaddr *, socklen_t);
    if (real_bind == NULL) real_bind = (int (*)(int, const struct sockaddr *, socklen_t))
            dlsym(RTLD_NEXT, "bind");
    memset(addr, 0, sizeof(*addr));
    addr->sun_family = AF_UNIX;
    memcpy(addr->sun_path + 1, metadata, sizeof(*metadata));
    return real_bind(fd, (const struct sockaddr *) addr,
                     offsetof(struct sockaddr_un, sun_path) + 1 + sizeof(*metadata));
}

static int nl_connect_address(int fd, const struct sockaddr_un *addr) {
    static int (*real_connect)(int, const struct sockaddr *, socklen_t);
    if (real_connect == NULL) real_connect = (int (*)(int, const struct sockaddr *, socklen_t))
            dlsym(RTLD_NEXT, "connect");
    return real_connect(fd, (const struct sockaddr *) addr,
                        offsetof(struct sockaddr_un, sun_path) + 1 + sizeof(struct nl_metadata));
}

static void nl_close_peer(struct nl_socket *socket) {
    if (socket->peer < 0) return;
    if (!nl_identity(socket->peer, socket->device, socket->peer_inode)) return;
    static int (*real_close)(int);
    if (real_close == NULL) real_close = (int (*)(int)) dlsym(RTLD_NEXT, "close");
    if (real_close) real_close(socket->peer);
}

static int nl_reserve_port(struct nl_socket *socket, unsigned port) {
    static int (*real_socket)(int, int, int);
    static int (*real_close)(int);
    if (real_socket == NULL) real_socket = (int (*)(int, int, int)) dlsym(RTLD_NEXT, "socket");
    if (real_close == NULL) real_close = (int (*)(int)) dlsym(RTLD_NEXT, "close");
    int descriptor = real_socket(AF_UNIX, SOCK_DGRAM, 0);
    if (descriptor < 0) return -1;
    struct nl_metadata metadata = { .port = port };
    struct sockaddr_un addr;
    struct stat status;
    memcpy(metadata.magic, nl_reservation_magic, sizeof(metadata.magic));
    if (nl_bind_address(descriptor, &metadata, &addr) != 0 || fstat(descriptor, &status) != 0) {
        int saved = errno;
        real_close(descriptor);
        errno = saved;
        return -1;
    }
    socket->reservation = descriptor;
    socket->reservation_inode = status.st_ino;
    return 0;
}

static void nl_close_reservation(struct nl_socket *socket) {
    if (socket->reservation < 0
            || !nl_identity(socket->reservation, socket->device, socket->reservation_inode)) return;
    static int (*real_close)(int);
    if (real_close == NULL) real_close = (int (*)(int)) dlsym(RTLD_NEXT, "close");
    if (real_close) real_close(socket->reservation);
    socket->reservation = -1;
}

static int nl_replay_packets(int peer, struct nl_packet *packets) {
    static ssize_t (*real_send)(int, const void *, size_t, int);
    if (real_send == NULL) real_send = (ssize_t (*)(int, const void *, size_t, int))
            dlsym(RTLD_NEXT, "send");
    int error = 0;
    while (packets) {
        struct nl_packet *packet = packets;
        packets = packet->next;
        if (real_send(peer, packet->data, packet->len, MSG_DONTWAIT | MSG_NOSIGNAL) < 0 && error == 0)
            error = errno;
        free(packet);
    }
    if (error) errno = error;
    return error ? -1 : 0;
}

static int nl_save_packets(int fd, struct nl_packet **packets) {
    static ssize_t (*real_recv)(int, void *, size_t, int);
    if (real_recv == NULL) real_recv = (ssize_t (*)(int, void *, size_t, int))
            dlsym(RTLD_NEXT, "recv");
    struct nl_packet **tail = packets;
    *packets = NULL;
    for (;;) {
        ssize_t len = real_recv(fd, NULL, 0, MSG_PEEK | MSG_TRUNC | MSG_DONTWAIT);
        if (len < 0) return errno == EAGAIN || errno == EWOULDBLOCK ? 0 : -1;
        if ((size_t) len > SIZE_MAX - sizeof(struct nl_packet)) {
            errno = EMSGSIZE;
            return -1;
        }
        struct nl_packet *packet = malloc(sizeof(*packet) + (size_t) len);
        if (packet == NULL) return -1;
        ssize_t received = real_recv(fd, packet->data, (size_t) len, MSG_DONTWAIT);
        if (received < 0) {
            int saved = errno;
            free(packet);
            errno = saved;
            return -1;
        }
        packet->len = (size_t) received;
        packet->next = NULL;
        *tail = packet;
        tail = &packet->next;
    }
}

static int nl_replace_peer(int fd, struct nl_socket *socket, unsigned port, int capped) {
    static int (*real_socket)(int, int, int);
    static int (*real_close)(int);
    if (real_socket == NULL) real_socket = (int (*)(int, int, int)) dlsym(RTLD_NEXT, "socket");
    if (real_close == NULL) real_close = (int (*)(int)) dlsym(RTLD_NEXT, "close");
    struct sockaddr_un client_addr, peer_addr;
    struct nl_metadata client, peer = { .port = port, .type = socket->type, .cap_ack = capped };
    if (!nl_address(fd, 0, &client_addr, &client, nl_client_magic)) {
        errno = EINVAL;
        return -1;
    }
    int descriptor = real_socket(AF_UNIX, SOCK_DGRAM | SOCK_CLOEXEC, 0);
    if (descriptor < 0) return -1;
    struct nl_packet *packets = NULL;
    struct stat status;
    if (fstat(descriptor, &status) != 0) goto failed;
    peer.nonce = status.st_ino;
    memcpy(peer.magic, nl_peer_magic, sizeof(peer.magic));
    if (nl_bind_address(descriptor, &peer, &peer_addr) != 0
            || nl_save_packets(fd, &packets) != 0) goto failed;
    if (nl_connect_address(fd, &peer_addr) != 0
            || nl_connect_address(descriptor, &client_addr) != 0) goto failed;
    struct timeval timeout;
    socklen_t timeout_len = sizeof(timeout);
    static int (*real_getopt)(int, int, int, void *, socklen_t *);
    static int (*real_setopt)(int, int, int, const void *, socklen_t);
    if (real_getopt == NULL) real_getopt = (int (*)(int, int, int, void *, socklen_t *))
            dlsym(RTLD_NEXT, "getsockopt");
    if (real_setopt == NULL) real_setopt = (int (*)(int, int, int, const void *, socklen_t))
            dlsym(RTLD_NEXT, "setsockopt");
    if (real_getopt(fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, &timeout_len) == 0)
        real_setopt(descriptor, SOL_SOCKET, SO_SNDTIMEO, &timeout, timeout_len);
    int send_buffer;
    socklen_t buffer_len = sizeof(send_buffer);
    if (real_getopt(fd, SOL_SOCKET, SO_SNDBUF, &send_buffer, &buffer_len) == 0) {
        send_buffer /= 2;
        real_setopt(descriptor, SOL_SOCKET, SO_SNDBUF, &send_buffer, sizeof(send_buffer));
    }
    int replayed = nl_replay_packets(descriptor, packets);
    int saved = errno;
    nl_close_peer(socket);
    socket->peer = descriptor;
    socket->peer_inode = status.st_ino;
    socket->port = port;
    socket->cap_ack = capped;
    errno = saved;
    return replayed;
failed: {
    int saved = errno;
    nl_replay_packets(socket->peer, packets);
    real_close(descriptor);
    errno = saved;
    return -1;
}
}

static void nl_release(struct nl_socket *socket) {
    if (--socket->references != 0) return;
    for (struct nl_socket **entry = &nl_sockets; *entry; entry = &(*entry)->next) {
        if (*entry != socket) continue;
        *entry = socket->next;
        break;
    }
    nl_close_peer(socket);
    nl_close_reservation(socket);
    free(socket);
}

static void nl_remove(struct nl_fd **entry) {
    struct nl_fd *removed = *entry;
    *entry = removed->next;
    struct nl_fd *alias;
    for (alias = nl_fds; alias; alias = alias->next) {
        if (alias->fd == removed->fd) break;
    }
    struct nl_fd_page *page = nl_fd_page_of(removed->fd);
    if (alias == NULL && page) {
        unsigned offset = (unsigned) removed->fd % NL_FD_PAGE_SIZE;
        __atomic_fetch_and(&page->bits[offset / NL_FD_WORD_BITS],
                           ~(1ul << (offset % NL_FD_WORD_BITS)), __ATOMIC_RELEASE);
    }
    __atomic_fetch_sub(&nl_fd_count, 1, __ATOMIC_RELEASE);
    nl_release(removed->socket);
    free(removed);
}

static struct nl_fd *nl_restore(int fd) {
    struct sockaddr_un client_addr, peer_addr;
    struct nl_metadata client, peer;
    struct stat status;
    if (!nl_address(fd, 0, &client_addr, &client, nl_client_magic)
            || !nl_address(fd, 1, &peer_addr, &peer, nl_peer_magic)
            || (peer.type != SOCK_RAW && peer.type != SOCK_DGRAM)
            || client.type != peer.type || peer.cap_ack > 1
            || fstat(fd, &status) != 0 || client.nonce != (uint64_t) status.st_ino) return NULL;
    struct nl_fd *entry = malloc(sizeof(*entry));
    if (entry == NULL) return NULL;
    struct nl_socket *socket;
    for (socket = nl_sockets; socket; socket = socket->next) {
        if (socket->device == status.st_dev && socket->inode == status.st_ino) break;
    }
    if (socket == NULL) {
        socket = calloc(1, sizeof(*socket));
        if (socket == NULL) {
            free(entry);
            return NULL;
        }
        socket->peer = -1;
        socket->reservation = -1;
        socket->device = status.st_dev;
        socket->inode = status.st_ino;
        socket->type = peer.type;
        if (nl_replace_peer(fd, socket, peer.port, peer.cap_ack) != 0) {
            free(socket);
            free(entry);
            return NULL;
        }
        socket->next = nl_sockets;
        nl_sockets = socket;
    }
    socket->references++;
    entry->fd = fd;
    entry->socket = socket;
    nl_register(entry);
    return entry;
}

static struct nl_fd *nl_find(int fd) {
    if (fd < 0) return NULL;
    for (struct nl_fd **entry = &nl_fds; *entry; entry = &(*entry)->next) {
        if ((*entry)->fd != fd) continue;
        struct nl_socket *socket = (*entry)->socket;
        if (nl_identity(fd, socket->device, socket->inode)) return *entry;
        nl_remove(entry);
        break;
    }
    return NULL;
}

static void nl_discover(int fd) {
    int saved = errno;
    if (nl_tracked(fd)) {
        pthread_mutex_lock(&nl_sockets_lock);
        int found = nl_find(fd) != NULL;
        pthread_mutex_unlock(&nl_sockets_lock);
        if (found) {
            errno = saved;
            return;
        }
    }
    struct sockaddr_un addr;
    struct nl_metadata metadata;
    if (nl_address(fd, 0, &addr, &metadata, nl_client_magic)) {
        pthread_mutex_lock(&nl_sockets_lock);
        if (nl_find(fd) == NULL) nl_restore(fd);
        pthread_mutex_unlock(&nl_sockets_lock);
    }
    errno = saved;
}

static void nl_restore_inherited(void) {
    DIR *directory = opendir("/proc/self/fd");
    if (directory == NULL) return;
    int *descriptors = NULL;
    size_t count = 0, capacity = 0;
    struct dirent *entry;
    while ((entry = readdir(directory)) != NULL) {
        char *end;
        long descriptor = strtol(entry->d_name, &end, 10);
        if (end == entry->d_name || *end || descriptor < 0 || descriptor > INT_MAX) continue;
        if (descriptor == dirfd(directory)) continue;
        if (count == capacity) {
            size_t enlarged = capacity ? capacity * 2 : 16;
            int *allocated = realloc(descriptors, enlarged * sizeof(*descriptors));
            if (allocated == NULL) break;
            descriptors = allocated;
            capacity = enlarged;
        }
        descriptors[count++] = (int) descriptor;
    }
    closedir(directory);
    for (size_t index = 0; index < count; index++) {
        nl_discover(descriptors[index]);
    }
    for (size_t index = 0; index < count; index++) {
        int descriptor = descriptors[index];
        struct sockaddr_un addr;
        struct nl_metadata metadata;
        if (!nl_address(descriptor, 0, &addr, &metadata, nl_reservation_magic)) continue;
        struct stat status;
        if (fstat(descriptor, &status) != 0) continue;
        struct nl_socket *socket;
        for (socket = nl_sockets; socket; socket = socket->next) {
            if (socket->port == metadata.port) break;
        }
        if (socket) {
            socket->reservation = descriptor;
            socket->reservation_inode = status.st_ino;
        } else {
            static int (*real_close)(int);
            if (real_close == NULL) real_close = (int (*)(int)) dlsym(RTLD_NEXT, "close");
            real_close(descriptor);
        }
    }
    free(descriptors);
}

static int nl_nonblocking(int fd) {
    static int (*real_fcntl)(int, int, ...);
    int flags;
    if (real_fcntl == NULL) real_fcntl = (int (*)(int, int, ...)) dlsym(RTLD_NEXT, "fcntl");
    if (real_fcntl == NULL) return 0;
    flags = real_fcntl(fd, F_GETFL);
    return flags >= 0 && (flags & O_NONBLOCK) != 0;
}

static int nl_peer_of(int fd) {
    int peer = -1;
    if (!nl_tracked(fd)) return peer;
    pthread_mutex_lock(&nl_sockets_lock);
    struct nl_fd *entry = nl_find(fd);
    if (entry) {
        struct sockaddr_un addr;
        struct nl_metadata metadata;
        struct nl_socket *socket = entry->socket;
        if (nl_address(fd, 1, &addr, &metadata, nl_peer_magic)) {
            if ((metadata.nonce == socket->peer_inode
                    && nl_identity(socket->peer, socket->device, socket->peer_inode))
                    || nl_replace_peer(fd, socket, metadata.port, metadata.cap_ack) == 0) {
                if (socket->port != 0 && (socket->reservation < 0
                        || !nl_identity(socket->reservation, socket->device, socket->reservation_inode)))
                    nl_reserve_port(socket, socket->port);
                peer = socket->peer;
            }
        }
    }
    pthread_mutex_unlock(&nl_sockets_lock);
    return peer;
}

static unsigned nl_port_of(int fd) {
    unsigned port = 0;
    if (!nl_tracked(fd)) return port;
    pthread_mutex_lock(&nl_sockets_lock);
    struct nl_fd *entry = nl_find(fd);
    if (entry) port = entry->socket->port;
    pthread_mutex_unlock(&nl_sockets_lock);
    return port;
}

__attribute__((visibility("hidden"))) int bl_nl_socket(int type, int *fd);
__attribute__((visibility("hidden"))) int bl_nl_bind(int fd, const struct sockaddr *addr,
                                                     socklen_t len, int *rc);
__attribute__((visibility("hidden"))) int bl_nl_getsockname(int fd, struct sockaddr *addr,
                                                            socklen_t *len, int *rc);
__attribute__((visibility("hidden"))) int bl_nl_owns(int fd);
__attribute__((visibility("hidden"))) void bl_nl_closed(int fd);
__attribute__((visibility("hidden"))) void bl_nl_range_closed(unsigned int first, unsigned int last);
__attribute__((visibility("hidden"))) int bl_nl_before_dup(int fd, void **state);
__attribute__((visibility("hidden"))) void bl_nl_after_dup(void *state, int fd);
__attribute__((visibility("hidden"))) int bl_nl_connect(int fd, const struct sockaddr *addr,
                                                        socklen_t len, int *rc);
__attribute__((visibility("hidden"))) int bl_nl_getpeername(int fd, struct sockaddr *addr,
                                                            socklen_t *len, int *rc);
__attribute__((visibility("hidden"))) int bl_nl_setsockopt(int fd, int level, int option,
                                                          const void *value, socklen_t len,
                                                          int (*real)(int, int, int, const void *, socklen_t),
                                                          int *rc);
__attribute__((visibility("hidden"))) int bl_nl_getsockopt(int fd, int level, int option,
                                                          void *value, socklen_t *len,
                                                          int (*real)(int, int, int, void *, socklen_t *),
                                                          int *rc);
__attribute__((visibility("hidden"))) void bl_nl_received(struct msghdr *msg);
__attribute__((visibility("hidden"))) int bl_nl_recvmsg(int fd, struct msghdr *msg, int flags,
                                                        ssize_t (*real)(int, struct msghdr *, int),
                                                        ssize_t *rc);

int bl_nl_owns(int fd) { return nl_peer_of(fd) >= 0; }

int bl_nl_socket(int type, int *fd) {
    int kind = type & 0xf;
    *fd = -1;
    if (type & ~(0xf | SOCK_CLOEXEC | SOCK_NONBLOCK)) {
        errno = EINVAL;
        return 1;
    }
    if (kind != SOCK_RAW && kind != SOCK_DGRAM) {
        errno = kind >= 11 ? EINVAL : ESOCKTNOSUPPORT;
        return 1;
    }
    int pair[2];
    struct stat client_status, peer_status;
    struct nl_fd *entry = malloc(sizeof(*entry));
    struct nl_socket *socket = calloc(1, sizeof(*socket));
    if (entry == NULL || socket == NULL) {
        free(entry);
        free(socket);
        errno = ENOMEM;
        return 1;
    }
    int flags = type & (SOCK_CLOEXEC | SOCK_NONBLOCK);
    if (socketpair(AF_UNIX, SOCK_DGRAM | flags, 0, pair) != 0) {
        free(entry);
        free(socket);
        return 1;
    }
    if (fcntl(pair[1], F_SETFD, FD_CLOEXEC) != 0 || fcntl(pair[1], F_SETFL, 0) != 0
            || fstat(pair[0], &client_status) != 0 || fstat(pair[1], &peer_status) != 0)
        goto failed;
    struct nl_metadata client = { .nonce = client_status.st_ino, .type = kind };
    struct nl_metadata peer = { .nonce = peer_status.st_ino, .type = kind };
    struct sockaddr_un addr;
    memcpy(client.magic, nl_client_magic, sizeof(client.magic));
    memcpy(peer.magic, nl_peer_magic, sizeof(peer.magic));
    if (nl_bind_address(pair[0], &client, &addr) != 0
            || nl_bind_address(pair[1], &peer, &addr) != 0) goto failed;
    socket->peer = pair[1];
    socket->reservation = -1;
    socket->device = client_status.st_dev;
    socket->inode = client_status.st_ino;
    socket->peer_inode = peer_status.st_ino;
    socket->type = kind;
    socket->references = 1;
    entry->fd = pair[0];
    entry->socket = socket;
    pthread_mutex_lock(&nl_sockets_lock);
    socket->next = nl_sockets;
    nl_sockets = socket;
    nl_register(entry);
    pthread_mutex_unlock(&nl_sockets_lock);
    *fd = pair[0];
    return 1;
failed: {
        int saved = errno;
        close(pair[0]);
        close(pair[1]);
        free(entry);
        free(socket);
        errno = saved;
        return 1;
    }
}

static int nl_bind_port(int fd, unsigned requested) {
    int result = -1;
    pthread_mutex_lock(&nl_sockets_lock);
    struct nl_fd *entry = nl_find(fd);
    if (entry) {
        struct nl_socket *socket = entry->socket;
        if (socket->port != 0) {
            if (requested == socket->port) result = 0;
            else errno = EINVAL;
        } else {
            unsigned port = requested ? requested : (unsigned) getpid();
            for (;;) {
                struct nl_socket *other;
                for (other = nl_sockets; other; other = other->next) {
                    if (other != socket && other->port == port) break;
                }
                if (other == NULL) {
                    if (nl_reserve_port(socket, port) == 0) {
                        result = nl_replace_peer(fd, socket, port, socket->cap_ack);
                        if (result != 0) nl_close_reservation(socket);
                        break;
                    }
                    if (errno != EADDRINUSE || requested != 0) break;
                }
                if (requested != 0) {
                    errno = EADDRINUSE;
                    break;
                }
                port = nl_next_port++;
                if (port == 0) port = nl_next_port++;
            }
        }
    } else {
        errno = EBADF;
    }
    pthread_mutex_unlock(&nl_sockets_lock);
    return result;
}

static int nl_autobind(int fd) {
    return nl_port_of(fd) != 0 ? 0 : nl_bind_port(fd, 0);
}

int bl_nl_bind(int fd, const struct sockaddr *addr, socklen_t len, int *rc) {
    const struct sockaddr_nl *nl;
    if (addr && len >= sizeof(sa_family_t) && addr->sa_family == AF_NETLINK) nl_discover(fd);
    if (!bl_nl_owns(fd)) return 0;
    if (addr == NULL || len < sizeof(struct sockaddr_nl) || addr->sa_family != AF_NETLINK) {
        errno = EINVAL;
        *rc = -1;
        return 1;
    }
    nl = (const struct sockaddr_nl *) addr;
    if (nl->nl_groups != 0) {
        errno = EACCES;
        *rc = -1;
        return 1;
    }
    *rc = nl_bind_port(fd, nl->nl_pid);
    return 1;
}

int bl_nl_getsockname(int fd, struct sockaddr *addr, socklen_t *len, int *rc) {
    struct sockaddr_nl nl;
    socklen_t size;
    if (!bl_nl_owns(fd)) return 0;
    if (addr == NULL || len == NULL) {
        errno = EFAULT;
        *rc = -1;
        return 1;
    }
    memset(&nl, 0, sizeof(nl));
    nl.nl_family = AF_NETLINK;
    nl.nl_pid = nl_port_of(fd);
    size = *len < sizeof(nl) ? *len : (socklen_t) sizeof(nl);
    memcpy(addr, &nl, size);
    *len = sizeof(nl);
    *rc = 0;
    return 1;
}

void bl_nl_closed(int fd) {
    if (!nl_tracked(fd)) return;
    bl_nl_range_closed((unsigned int) fd, (unsigned int) fd);
}

void bl_nl_range_closed(unsigned int first, unsigned int last) {
    if (__atomic_load_n(&nl_fd_count, __ATOMIC_ACQUIRE) == 0) return;
    pthread_mutex_lock(&nl_sockets_lock);
    struct nl_fd **entry = &nl_fds;
    while (*entry) {
        if ((unsigned int) (*entry)->fd >= first && (unsigned int) (*entry)->fd <= last)
            nl_remove(entry);
        else
            entry = &(*entry)->next;
    }
    pthread_mutex_unlock(&nl_sockets_lock);
}

int bl_nl_before_dup(int fd, void **state) {
    *state = NULL;
    if (!nl_tracked(fd)) return 0;
    pthread_mutex_lock(&nl_sockets_lock);
    struct nl_fd *entry = nl_find(fd);
    if (entry) {
        struct nl_fd *reserved = malloc(sizeof(*reserved));
        if (reserved == NULL) {
            pthread_mutex_unlock(&nl_sockets_lock);
            errno = ENOMEM;
            return -1;
        }
        reserved->socket = entry->socket;
        entry->socket->references++;
        *state = reserved;
    }
    pthread_mutex_unlock(&nl_sockets_lock);
    return 0;
}

void bl_nl_after_dup(void *state, int fd) {
    int saved = errno;
    struct nl_fd *reserved = state;
    if (reserved == NULL && !nl_tracked(fd)) return;
    pthread_mutex_lock(&nl_sockets_lock);
    if (reserved && fd >= 0) {
        reserved->fd = fd;
        nl_register(reserved);
    }
    if (fd >= 0) {
        for (struct nl_fd **entry = &nl_fds; *entry; entry = &(*entry)->next) {
            if ((*entry)->fd != fd || *entry == reserved) continue;
            nl_remove(entry);
            break;
        }
    }
    if (reserved && fd < 0) {
        nl_release(reserved->socket);
        free(reserved);
    }
    pthread_mutex_unlock(&nl_sockets_lock);
    errno = saved;
}

int dup(int fd) {
    static int (*real)(int);
    void *state;
    if (real == NULL) real = (int (*)(int)) dlsym(RTLD_NEXT, "dup");
    if (bl_nl_before_dup(fd, &state) != 0) return -1;
    int result = real(fd);
    bl_nl_after_dup(state, result);
    return result;
}

static int nl_destination(const struct sockaddr *addr, socklen_t len) {
    if (addr == NULL || len < sizeof(struct sockaddr_nl)) return -EINVAL;
    if (addr->sa_family != AF_NETLINK) return -EAFNOSUPPORT;
    const struct sockaddr_nl *destination = (const struct sockaddr_nl *) addr;
    if (destination->nl_groups != 0) return -EACCES;
    return destination->nl_pid == 0 ? 0 : -ECONNREFUSED;
}

int bl_nl_connect(int fd, const struct sockaddr *addr, socklen_t len, int *rc) {
    if (addr && len >= sizeof(sa_family_t) && addr->sa_family == AF_NETLINK) nl_discover(fd);
    if (!bl_nl_owns(fd)) return 0;
    int disconnect = addr && len >= sizeof(sa_family_t) && addr->sa_family == AF_UNSPEC;
    int error = disconnect ? 0 : nl_destination(addr, len);
    if (error) {
        errno = addr == NULL ? EFAULT : -error;
        *rc = -1;
        return 1;
    }
    *rc = disconnect ? 0 : nl_autobind(fd);
    return 1;
}

static void nl_source_name(struct sockaddr *addr, socklen_t *len) {
    struct sockaddr_nl source;
    memset(&source, 0, sizeof(source));
    source.nl_family = AF_NETLINK;
    socklen_t capacity = *len < sizeof(source) ? *len : (socklen_t) sizeof(source);
    if (capacity > 0) memcpy(addr, &source, capacity);
    *len = sizeof(source);
}

int bl_nl_getpeername(int fd, struct sockaddr *addr, socklen_t *len, int *rc) {
    if (!bl_nl_owns(fd)) return 0;
    if (addr == NULL || len == NULL) {
        errno = EFAULT;
        *rc = -1;
    } else {
        nl_source_name(addr, len);
        *rc = 0;
    }
    return 1;
}

int bl_nl_setsockopt(int fd, int level, int option, const void *value, socklen_t len,
                     int (*real)(int, int, int, const void *, socklen_t), int *rc) {
    if (level == SOL_NETLINK) nl_discover(fd);
    int peer = nl_peer_of(fd);
    if (peer < 0) return 0;
    if (level != SOL_NETLINK) {
        *rc = real(fd, level, option, value, len);
        if (*rc == 0 && level == SOL_SOCKET
                && (option == SO_SNDTIMEO || option == SO_SNDBUF))
            *rc = real(peer, level, option, value, len);
        return 1;
    }
    if (option != NETLINK_CAP_ACK && option != NETLINK_EXT_ACK) {
        errno = option == NETLINK_ADD_MEMBERSHIP || option == NETLINK_DROP_MEMBERSHIP
                ? EACCES : ENOPROTOOPT;
        *rc = -1;
    } else if (value == NULL || len < sizeof(int)) {
        errno = value == NULL ? EFAULT : EINVAL;
        *rc = -1;
    } else {
        if (option == NETLINK_CAP_ACK) {
            int enabled;
            memcpy(&enabled, value, sizeof(enabled));
            pthread_mutex_lock(&nl_sockets_lock);
            struct nl_fd *entry = nl_find(fd);
            if (entry && nl_replace_peer(fd, entry->socket, entry->socket->port, enabled != 0) != 0) {
                pthread_mutex_unlock(&nl_sockets_lock);
                *rc = -1;
                return 1;
            }
            pthread_mutex_unlock(&nl_sockets_lock);
        }
        *rc = 0;
    }
    return 1;
}

int bl_nl_getsockopt(int fd, int level, int option, void *value, socklen_t *len,
                     int (*real)(int, int, int, void *, socklen_t *), int *rc) {
    if (level != SOL_SOCKET || (option != SO_TYPE && option != SO_DOMAIN && option != SO_PROTOCOL))
        return 0;
    if (!nl_tracked(fd)) {
        if (option != SO_DOMAIN) return 0;
        *rc = real(fd, level, option, value, len);
        if (*rc != 0) return 1;
        int domain = AF_UNIX;
        if (value && len && *len >= sizeof(domain)) memcpy(&domain, value, sizeof(domain));
        if (domain != AF_UNIX) return 1;
        nl_discover(fd);
        if (!nl_tracked(fd)) return 1;
    }
    pthread_mutex_lock(&nl_sockets_lock);
    struct nl_fd *entry = nl_find(fd);
    if (entry == NULL) {
        pthread_mutex_unlock(&nl_sockets_lock);
        return 0;
    }
    int result = option == SO_TYPE ? entry->socket->type :
            option == SO_DOMAIN ? AF_NETLINK : NETLINK_ROUTE;
    pthread_mutex_unlock(&nl_sockets_lock);
    if (len == NULL || (value == NULL && *len > 0)) {
        errno = EFAULT;
        *rc = -1;
    } else if (*len > INT_MAX) {
        errno = EINVAL;
        *rc = -1;
    } else {
        socklen_t capacity = *len < sizeof(result) ? *len : (socklen_t) sizeof(result);
        if (capacity) memcpy(value, &result, capacity);
        *len = capacity;
        *rc = 0;
    }
    return 1;
}

void bl_nl_received(struct msghdr *msg) {
    for (struct cmsghdr *control = CMSG_FIRSTHDR(msg); control; control = CMSG_NXTHDR(msg, control)) {
        if (control->cmsg_len < CMSG_LEN(0)) break;
        if (control->cmsg_level != SOL_SOCKET || control->cmsg_type != SCM_RIGHTS) continue;
        size_t count = (control->cmsg_len - CMSG_LEN(0)) / sizeof(int);
        for (size_t index = 0; index < count; index++) {
            int descriptor;
            memcpy(&descriptor, CMSG_DATA(control) + index * sizeof(descriptor), sizeof(descriptor));
            nl_discover(descriptor);
        }
    }
}

int bl_nl_recvmsg(int fd, struct msghdr *msg, int flags,
                  ssize_t (*real)(int, struct msghdr *, int), ssize_t *rc) {
    ssize_t result;
    socklen_t capacity = 0;
    if (!bl_nl_owns(fd)) return 0;
    if (msg && msg->msg_name) capacity = msg->msg_namelen;
    result = real(fd, msg, flags);
    if (result >= 0 && msg && msg->msg_name) {
        msg->msg_namelen = capacity;
        nl_source_name(msg->msg_name, &msg->msg_namelen);
    }
    *rc = result;
    return 1;
}

ssize_t recvfrom(int fd, void *buffer, size_t length, int flags,
                 struct sockaddr *addr, socklen_t *addrlen) {
    static ssize_t (*real)(int, void *, size_t, int, struct sockaddr *, socklen_t *);
    if (real == NULL)
        real = (ssize_t (*)(int, void *, size_t, int, struct sockaddr *, socklen_t *))
                dlsym(RTLD_NEXT, "recvfrom");
    int owned = bl_nl_owns(fd);
    socklen_t capacity = owned && addr && addrlen ? *addrlen : 0;
    ssize_t result = real(fd, buffer, length, flags, addr, addrlen);
    if (result >= 0 && owned && addr && addrlen) {
        *addrlen = capacity;
        nl_source_name(addr, addrlen);
    }
    return result;
}

static unsigned char *reply_start(struct nl_reply *out, uint16_t type, uint16_t flags,
                                  uint32_t seq, size_t payload) {
    size_t offset = NLMSG_ALIGN(out->len);
    struct nlmsghdr *header;
    if (payload > sizeof(out->data)
            || offset + NLMSG_SPACE(payload) > sizeof(out->data)) {
        out->overflow = 1;
        return NULL;
    }
    header = (struct nlmsghdr *) (out->data + offset);
    memset(header, 0, NLMSG_SPACE(payload));
    header->nlmsg_len = NLMSG_LENGTH(payload);
    header->nlmsg_type = type;
    header->nlmsg_flags = flags;
    header->nlmsg_seq = seq;
    header->nlmsg_pid = nl_reply_port;
    out->len = offset + NLMSG_ALIGN(header->nlmsg_len);
    return (unsigned char *) header;
}

static void reply_attr(struct nl_reply *out, unsigned char *message, int type,
                       const void *data, size_t len) {
    struct nlmsghdr *header = (struct nlmsghdr *) message;
    size_t base = (size_t) (message - out->data);
    size_t offset = NLMSG_ALIGN(header->nlmsg_len);
    size_t total = RTA_ALIGN(RTA_LENGTH(len));
    struct rtattr *attr;
    if (base + offset + total > sizeof(out->data)) {
        out->overflow = 1;
        return;
    }
    attr = (struct rtattr *) (message + offset);
    attr->rta_type = (unsigned short) type;
    attr->rta_len = (unsigned short) RTA_LENGTH(len);
    if (len > 0) memcpy(RTA_DATA(attr), data, len);
    header->nlmsg_len = (unsigned) (offset + total);
    out->len = base + NLMSG_ALIGN(header->nlmsg_len);
}

static void reply_done(struct nl_reply *out, uint32_t seq) {
    unsigned char *message = reply_start(out, NLMSG_DONE, NLM_F_MULTI, seq, sizeof(int));
    int code = 0;
    if (message == NULL) return;
    memcpy(NLMSG_DATA((struct nlmsghdr *) message), &code, sizeof(code));
}

static void reply_error(struct nl_reply *out, const struct nlmsghdr *request, int error) {
    int capped = error == 0 || nl_reply_capped;
    size_t extra = capped ? 0 : request->nlmsg_len - NLMSG_HDRLEN;
    unsigned char *message = reply_start(out, NLMSG_ERROR, capped ? NLM_F_CAPPED : 0,
                                         request->nlmsg_seq, sizeof(struct nlmsgerr) + extra);
    struct nlmsgerr *reply;
    if (message == NULL) return;
    reply = (struct nlmsgerr *) NLMSG_DATA((struct nlmsghdr *) message);
    memset(reply, 0, sizeof(*reply));
    reply->error = error;
    reply->msg = *request;
    if (extra) memcpy(reply + 1, NLMSG_DATA(request), extra);
}

static void network_of(const struct link_addr *addr, unsigned char *out) {
    unsigned char mask[16];
    int len = addr->family == AF_INET ? 4 : 16;
    prefix_mask(addr->family, addr->prefix, mask);
    for (int i = 0; i < len; i++) out[i] = (unsigned char) (addr->bytes[i] & mask[i]);
}

static void broadcast_of(const struct link_addr *addr, unsigned char *out) {
    unsigned char mask[16];
    prefix_mask(AF_INET, addr->prefix, mask);
    for (int i = 0; i < 4; i++) out[i] = (unsigned char) (addr->bytes[i] | (unsigned char) ~mask[i]);
}

static const struct link_addr *first_addr(const struct link *link, int family) {
    for (int i = 0; i < link->addr_count; i++) {
        if (link->addrs[i].family == family) return &link->addrs[i];
    }
    return NULL;
}

static void reply_link(struct nl_reply *out, const struct link *link, int loopback,
                       uint32_t seq, uint16_t flags) {
    struct ifinfomsg info;
    const char *name;
    unsigned mtu;
    unsigned char state = IF_OPER_UP;
    unsigned char *message;
    memset(&info, 0, sizeof(info));
    info.ifi_family = AF_UNSPEC;
    info.ifi_type = loopback ? ARPHRD_LOOPBACK : ARPHRD_ETHER;
    info.ifi_index = loopback ? LO_INDEX : (link ? (int) link->index : 2);
    info.ifi_flags = IFF_UP | IFF_RUNNING | IFF_LOWER_UP
            | (loopback ? IFF_LOOPBACK : (IFF_BROADCAST | IFF_MULTICAST));
    info.ifi_change = 0xffffffff;
    message = reply_start(out, RTM_NEWLINK, flags, seq, sizeof(info));
    if (message == NULL) return;
    memcpy(NLMSG_DATA((struct nlmsghdr *) message), &info, sizeof(info));
    name = loopback ? LO_NAME : (link ? link->name : "eth0");
    mtu = loopback ? LO_MTU : (unsigned) (link ? link->mtu : 1500);
    reply_attr(out, message, IFLA_IFNAME, name, strlen(name) + 1);
    reply_attr(out, message, IFLA_MTU, &mtu, sizeof(mtu));
    reply_attr(out, message, IFLA_OPERSTATE, &state, sizeof(state));
    if (!loopback && link && (link->mac[0] | link->mac[1] | link->mac[2]
                              | link->mac[3] | link->mac[4] | link->mac[5])) {
        reply_attr(out, message, IFLA_ADDRESS, link->mac, 6);
    }
}

static void reply_addr(struct nl_reply *out, const struct link_addr *addr, unsigned index,
                       int loopback, uint32_t seq, uint16_t flags) {
    struct ifaddrmsg info;
    int size = addr->family == AF_INET ? 4 : 16;
    unsigned char *message;
    memset(&info, 0, sizeof(info));
    info.ifa_family = (unsigned char) addr->family;
    info.ifa_prefixlen = (unsigned char) addr->prefix;
    info.ifa_flags = IFA_F_PERMANENT;
    info.ifa_scope = (unsigned char) (loopback ? RT_SCOPE_HOST
            : (is_link_local(addr) ? RT_SCOPE_LINK : RT_SCOPE_UNIVERSE));
    info.ifa_index = index;
    message = reply_start(out, RTM_NEWADDR, flags, seq, sizeof(info));
    if (message == NULL) return;
    memcpy(NLMSG_DATA((struct nlmsghdr *) message), &info, sizeof(info));
    reply_attr(out, message, IFA_LOCAL, addr->bytes, size);
    reply_attr(out, message, IFA_ADDRESS, addr->bytes, size);
    if (!loopback && addr->family == AF_INET && addr->prefix <= 30) {
        unsigned char broadcast[4];
        broadcast_of(addr, broadcast);
        reply_attr(out, message, IFA_BROADCAST, broadcast, 4);
    }
}

static void reply_route(struct nl_reply *out, int family, int destination_length,
                        const unsigned char *destination, const unsigned char *gateway,
                        const unsigned char *source, unsigned index, int protocol, int scope,
                        int type, uint32_t seq, uint16_t flags) {
    struct rtmsg info;
    int size = family == AF_INET ? 4 : 16;
    unsigned char *message;
    memset(&info, 0, sizeof(info));
    info.rtm_family = (unsigned char) family;
    info.rtm_dst_len = (unsigned char) destination_length;
    info.rtm_table = type == RTN_LOCAL ? RT_TABLE_LOCAL : RT_TABLE_MAIN;
    info.rtm_protocol = (unsigned char) protocol;
    info.rtm_scope = (unsigned char) scope;
    info.rtm_type = (unsigned char) type;
    message = reply_start(out, RTM_NEWROUTE, flags, seq, sizeof(info));
    if (message == NULL) return;
    memcpy(NLMSG_DATA((struct nlmsghdr *) message), &info, sizeof(info));
    if (destination_length > 0 && destination) reply_attr(out, message, RTA_DST, destination, size);
    if (gateway) reply_attr(out, message, RTA_GATEWAY, gateway, size);
    if (source) reply_attr(out, message, RTA_PREFSRC, source, size);
    reply_attr(out, message, RTA_OIF, &index, sizeof(index));
}

static void reply_addrs(struct nl_reply *out, const struct link *link, int family, uint32_t seq) {
    if (family == AF_UNSPEC || family == AF_INET)
        reply_addr(out, &LO_V4, LO_INDEX, 1, seq, NLM_F_MULTI);
    if (family == AF_UNSPEC || family == AF_INET6)
        reply_addr(out, &LO_V6, LO_INDEX, 1, seq, NLM_F_MULTI);
    if (link == NULL) return;
    for (int i = 0; i < link->addr_count; i++) {
        if (family != AF_UNSPEC && link->addrs[i].family != family) continue;
        reply_addr(out, &link->addrs[i], link->index, 0, seq, NLM_F_MULTI);
    }
}

static void reply_routes(struct nl_reply *out, const struct link *link, int family, uint32_t seq) {
    if (link == NULL) return;
    for (int pass = 0; pass < 2; pass++) {
        int current = pass == 0 ? AF_INET : AF_INET6;
        const struct link_addr *source;
        if (family != AF_UNSPEC && family != current) continue;
        source = first_addr(link, current);
        for (int i = 0; i < link->gateway_count; i++) {
            if (link->gateways[i].family != current) continue;
            reply_route(out, current, 0, NULL, link->gateways[i].bytes,
                        source ? source->bytes : NULL, link->index, RTPROT_STATIC,
                        RT_SCOPE_UNIVERSE, RTN_UNICAST, seq, NLM_F_MULTI);
        }
        for (int i = 0; i < link->addr_count; i++) {
            unsigned char network[16];
            if (link->addrs[i].family != current) continue;
            network_of(&link->addrs[i], network);
            reply_route(out, current, link->addrs[i].prefix, network, NULL,
                        link->addrs[i].bytes, link->index, RTPROT_KERNEL,
                        RT_SCOPE_LINK, RTN_UNICAST, seq, NLM_F_MULTI);
        }
    }
}

static const struct rtattr *request_attr(const struct nlmsghdr *request, size_t offset, int type) {
    int length;
    const struct rtattr *attr;
    if (request->nlmsg_len < NLMSG_HDRLEN + offset) return NULL;
    length = (int) (request->nlmsg_len - NLMSG_HDRLEN - offset);
    attr = (const struct rtattr *) ((const unsigned char *) NLMSG_DATA(request) + offset);
    for (; RTA_OK(attr, length); attr = RTA_NEXT(attr, length)) {
        if ((int) attr->rta_type == type) return attr;
    }
    return NULL;
}

static int address_matches(const unsigned char *destination, const struct link_addr *addr) {
    unsigned char mask[16];
    int size = addr->family == AF_INET ? 4 : 16;
    prefix_mask(addr->family, addr->prefix, mask);
    for (int index = 0; index < size; index++) {
        if ((destination[index] & mask[index]) != (addr->bytes[index] & mask[index])) return 0;
    }
    return 1;
}

static int reply_route_get(struct nl_reply *out, const struct link *link,
                           const struct nlmsghdr *request) {
    if (request->nlmsg_len < NLMSG_LENGTH(sizeof(struct rtmsg))) return -EINVAL;
    const struct rtmsg *info = (const struct rtmsg *) NLMSG_DATA(request);
    int family = info->rtm_family;
    if (family != AF_INET && family != AF_INET6) return -EAFNOSUPPORT;
    int size = family == AF_INET ? 4 : 16;
    const struct rtattr *destination_attr = request_attr(request, sizeof(struct rtmsg), RTA_DST);
    unsigned char zero[16] = { 0 };
    if (destination_attr && RTA_PAYLOAD(destination_attr) < (size_t) size) return -EINVAL;
    const unsigned char *destination = destination_attr ? RTA_DATA(destination_attr) : zero;
    const struct link_addr *loopback = family == AF_INET ? &LO_V4 : &LO_V6;
    const struct link_addr *source = NULL;
    const struct link_addr *gateway = NULL;
    int local = address_matches(destination, loopback);
    if (local) source = loopback;
    for (int index = 0; link && !local && index < link->addr_count; index++) {
        const struct link_addr *addr = &link->addrs[index];
        if (addr->family != family) continue;
        if (memcmp(destination, addr->bytes, size) == 0) {
            source = addr;
            local = 1;
            break;
        }
        if (address_matches(destination, addr) && (!source || addr->prefix > source->prefix))
            source = addr;
    }
    if (source) {
        reply_route(out, family, size * 8, destination, NULL, source->bytes,
                    local ? LO_INDEX : link->index, RTPROT_KERNEL,
                    local ? RT_SCOPE_HOST : RT_SCOPE_LINK,
                    local ? RTN_LOCAL : RTN_UNICAST, request->nlmsg_seq, 0);
        return 0;
    }
    for (int i = 0; link && i < link->gateway_count; i++) {
        if (link->gateways[i].family == family) {
            gateway = &link->gateways[i];
            break;
        }
    }
    if (gateway == NULL) return -ENETUNREACH;
    source = first_addr(link, family);
    for (int index = 0; index < link->addr_count; index++) {
        if (link->addrs[index].family == family && !is_link_local(&link->addrs[index])) {
            source = &link->addrs[index];
            break;
        }
    }
    reply_route(out, family, size * 8, destination,
                gateway->bytes, source ? source->bytes : NULL, link->index, RTPROT_STATIC,
                RT_SCOPE_UNIVERSE, RTN_UNICAST, request->nlmsg_seq, 0);
    return 0;
}

static void reply_request(const struct nlmsghdr *request, const struct link *link,
                          struct nl_reply *out) {
    int dump = (request->nlmsg_flags & NLM_F_DUMP) != 0;
    int error = 0;
    uint16_t flags = dump ? NLM_F_MULTI : 0;
    switch (request->nlmsg_type) {
    case RTM_GETLINK: {
        int index = 0;
        if (request->nlmsg_len >= NLMSG_LENGTH(sizeof(struct ifinfomsg)))
            index = ((const struct ifinfomsg *) NLMSG_DATA(request))->ifi_index;
        if (!dump && index == 0) {
            const struct rtattr *name_attr = request_attr(request, sizeof(struct ifinfomsg), IFLA_IFNAME);
            size_t length = name_attr ? RTA_PAYLOAD(name_attr) : 0;
            const char *name = name_attr ? RTA_DATA(name_attr) : NULL;
            if (length == 0 || length > IFNAMSIZ || memchr(name, '\0', length) == NULL)
                error = -EINVAL;
            else if (strcmp(name, LO_NAME) == 0)
                index = LO_INDEX;
            else if (link && strcmp(name, link->name) == 0)
                index = (int) link->index;
            else
                error = -ENODEV;
        }
        if (dump) {
            reply_link(out, NULL, 1, request->nlmsg_seq, flags);
            if (link) reply_link(out, link, 0, request->nlmsg_seq, flags);
            reply_done(out, request->nlmsg_seq);
        } else if (!error && (index == LO_INDEX || (link && index == (int) link->index))) {
            reply_link(out, link, index == LO_INDEX, request->nlmsg_seq, 0);
        } else if (!error) {
            error = -ENODEV;
        }
        break;
    }
    case RTM_GETADDR: {
        int family = AF_UNSPEC;
        if (request->nlmsg_len >= NLMSG_LENGTH(sizeof(struct ifaddrmsg)))
            family = ((const struct ifaddrmsg *) NLMSG_DATA(request))->ifa_family;
        if (dump) {
            reply_addrs(out, link, family, request->nlmsg_seq);
            reply_done(out, request->nlmsg_seq);
        } else {
            error = -EOPNOTSUPP;
        }
        break;
    }
    case RTM_GETROUTE: {
        int family = AF_UNSPEC;
        if (request->nlmsg_len >= NLMSG_LENGTH(sizeof(struct rtmsg)))
            family = ((const struct rtmsg *) NLMSG_DATA(request))->rtm_family;
        if (dump) {
            reply_routes(out, link, family, request->nlmsg_seq);
            reply_done(out, request->nlmsg_seq);
        } else {
            error = reply_route_get(out, link, request);
        }
        break;
    }
    case RTM_GETNEIGH:
        if (dump) reply_done(out, request->nlmsg_seq);
        else error = -EOPNOTSUPP;
        break;
    default:
        error = -EPERM;
        break;
    }
    if (error || (!dump && (request->nlmsg_flags & NLM_F_ACK)))
        reply_error(out, request, error);
}

static void build_reply(const void *data, size_t len, struct nl_reply *out) {
    struct link link;
    int have = load_link(&link);
    size_t offset = 0;
    out->len = 0;
    out->overflow = 0;
    while (offset + sizeof(struct nlmsghdr) <= len) {
        const struct nlmsghdr *request =
                (const struct nlmsghdr *) ((const unsigned char *) data + offset);
        if (request->nlmsg_len < sizeof(struct nlmsghdr) || offset + request->nlmsg_len > len)
            break;
        reply_request(request, have ? &link : NULL, out);
        offset += NLMSG_ALIGN(request->nlmsg_len);
    }
}

static ssize_t send_raw(int fd, const void *data, size_t len, int extra) {
    static ssize_t (*real)(int, const void *, size_t, int);
    if (real == NULL) real = (ssize_t (*)(int, const void *, size_t, int)) dlsym(RTLD_NEXT, "send");
    if (real == NULL) {
        errno = ENOSYS;
        return -1;
    }
    return real(fd, data, len, MSG_NOSIGNAL | extra);
}

static int deliver(int fd, const void *data, size_t len, int flags, ssize_t *rc) {
    struct nl_reply reply;
    int peer = nl_peer_of(fd);
    int extra = 0;
    if (peer < 0) return 0;
    if (nl_autobind(fd) != 0) {
        *rc = -1;
        return 1;
    }
    peer = nl_peer_of(fd);
    if (peer < 0) {
        errno = EBADF;
        *rc = -1;
        return 1;
    }
    nl_reply_port = nl_port_of(fd);
    pthread_mutex_lock(&nl_sockets_lock);
    struct nl_fd *entry = nl_find(fd);
    nl_reply_capped = entry && entry->socket->cap_ack;
    pthread_mutex_unlock(&nl_sockets_lock);
    build_reply(data, len, &reply);
    if (reply.overflow) {
        errno = EMSGSIZE;
        *rc = -1;
        return 1;
    }
    if (reply.len == 0) {
        struct nlmsghdr *request = (struct nlmsghdr *) data;
        if (len < sizeof(struct nlmsghdr)) {
            errno = EINVAL;
            *rc = -1;
            return 1;
        }
        reply.len = 0;
        nl_reply_capped = 1;
        reply_error(&reply, request, -EOPNOTSUPP);
    }
    if ((flags & MSG_DONTWAIT) || nl_nonblocking(fd)) extra = MSG_DONTWAIT;
    *rc = send_raw(peer, reply.data, reply.len, extra) < 0 ? -1 : (ssize_t) len;
    return 1;
}

static int collect(const struct msghdr *msg, unsigned char *small, size_t capacity,
                    unsigned char **buffer, size_t *length) {
    size_t used = 0;
    if (msg == NULL || (msg->msg_iovlen && msg->msg_iov == NULL)) {
        errno = EFAULT;
        return 0;
    }
    if (msg->msg_iovlen > IOV_MAX) {
        errno = EMSGSIZE;
        return 0;
    }
    for (size_t index = 0; index < msg->msg_iovlen; index++) {
        const struct iovec *part = &msg->msg_iov[index];
        if (part->iov_len && part->iov_base == NULL) {
            errno = EFAULT;
            return 0;
        }
        if (part->iov_len > (size_t) SSIZE_MAX - used) {
            errno = EMSGSIZE;
            return 0;
        }
        used += part->iov_len;
    }
    if (used == 0) {
        errno = EINVAL;
        return 0;
    }
    *buffer = used <= capacity ? small : malloc(used);
    if (*buffer == NULL) {
        errno = ENOMEM;
        return 0;
    }
    *length = used;
    used = 0;
    for (size_t index = 0; index < msg->msg_iovlen; index++) {
        const struct iovec *part = &msg->msg_iov[index];
        if (part->iov_len) memcpy(*buffer + used, part->iov_base, part->iov_len);
        used += part->iov_len;
    }
    return 1;
}

static ssize_t sendmsg_request(int fd, const struct msghdr *msg, int flags) {
    unsigned char small[NL_REQUEST_MAX];
    unsigned char *buffer;
    size_t length = 0;
    if (!collect(msg, small, sizeof(small), &buffer, &length)) {
        return -1;
    }
    ssize_t result;
    int handled = deliver(fd, buffer, length, flags, &result);
    int saved = errno;
    if (buffer != small) free(buffer);
    if (!handled) {
        saved = EBADF;
        result = -1;
    }
    errno = saved;
    return result;
}

ssize_t sendmsg(int fd, const struct msghdr *msg, int flags) {
    static ssize_t (*real)(int, const struct msghdr *, int);
    if (bl_nl_owns(fd)) {
        int error = msg && msg->msg_name ? nl_destination(msg->msg_name, msg->msg_namelen) : 0;
        if (error) {
            errno = -error;
            return -1;
        }
        return sendmsg_request(fd, msg, flags);
    }
    if (real == NULL) real = (ssize_t (*)(int, const struct msghdr *, int)) dlsym(RTLD_NEXT, "sendmsg");
    if (real == NULL) {
        errno = ENOSYS;
        return -1;
    }
    return real(fd, msg, flags);
}

ssize_t sendto(int fd, const void *buffer, size_t length, int flags,
               const struct sockaddr *addr, socklen_t addrlen) {
    static ssize_t (*real)(int, const void *, size_t, int, const struct sockaddr *, socklen_t);
    ssize_t rc;
    if (bl_nl_owns(fd)) {
        int error = addr ? nl_destination(addr, addrlen) : 0;
        if (error) {
            errno = -error;
            return -1;
        }
        if (deliver(fd, buffer, length, flags, &rc)) return rc;
    }
    if (real == NULL)
        real = (ssize_t (*)(int, const void *, size_t, int, const struct sockaddr *, socklen_t))
                dlsym(RTLD_NEXT, "sendto");
    if (real == NULL) {
        errno = ENOSYS;
        return -1;
    }
    return real(fd, buffer, length, flags, addr, addrlen);
}

ssize_t send(int fd, const void *buffer, size_t length, int flags) {
    static ssize_t (*real)(int, const void *, size_t, int);
    ssize_t rc;
    if (bl_nl_owns(fd) && deliver(fd, buffer, length, flags, &rc)) return rc;
    if (real == NULL) real = (ssize_t (*)(int, const void *, size_t, int)) dlsym(RTLD_NEXT, "send");
    if (real == NULL) {
        errno = ENOSYS;
        return -1;
    }
    return real(fd, buffer, length, flags);
}
