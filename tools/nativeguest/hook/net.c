// Part of libddnatv-hook.so, included by ddnatv-hook.c (one translation unit, so the
// parts share their static functions and are built with the same command as before).
//
// Name resolution: DNS packets, the network's DNS servers, the fallback resolver, getaddrinfo()
// and the resolver state, and DNS queries to loopback redirected to a real server.

// ---- DNS packets and servers -------------------------------------------------------------
// Pure functions (no syscalls apart from if_nametoindex), so tests/native can check them directly.

#define DDNATV_DNS_TYPE_A 1
#define DDNATV_DNS_TYPE_AAAA 28
#define DDNATV_DNS_MAX_SERVERS 8

typedef struct {
    struct sockaddr_storage addr;
    socklen_t len;
} ddnatv_dns_server_t;

// Fills `server` with the DNS server `text` (an IPv4 or IPv6 address, IPv6 optionally with a
// "%interface" scope) on port 53. Returns 0 for loopback and unspecified addresses (nothing answers
// there on Android), for a link-local IPv6 address without a scope, and for anything invalid.
static int dns_server_from_string(const char *text, ddnatv_dns_server_t *server) {
    memset(server, 0, sizeof(*server));
    if (!text) return 0;
    struct in_addr a4;
    if (inet_pton(AF_INET, text, &a4) == 1) {
        if ((ntohl(a4.s_addr) >> 24) == 127 || a4.s_addr == 0) return 0;
        struct sockaddr_in *sin = (struct sockaddr_in *)&server->addr;
        sin->sin_family = AF_INET;
        sin->sin_port = htons(53);
        sin->sin_addr = a4;
        server->len = sizeof(*sin);
        return 1;
    }
    char host[INET6_ADDRSTRLEN + IF_NAMESIZE + 2];
    if (strlen(text) >= sizeof(host)) return 0;
    strcpy(host, text);
    unsigned int scope = 0;
    char *pct = strchr(host, '%');
    if (pct) {
        *pct++ = '\0';
        if (*pct >= '0' && *pct <= '9') {
            scope = (unsigned int)strtoul(pct, NULL, 10);
        } else {
            scope = if_nametoindex(pct);
        }
    }
    struct in6_addr a6;
    if (inet_pton(AF_INET6, host, &a6) != 1) return 0;
    if (IN6_IS_ADDR_LOOPBACK(&a6) || IN6_IS_ADDR_UNSPECIFIED(&a6)) return 0;
    if (IN6_IS_ADDR_V4MAPPED(&a6) && (a6.s6_addr[12] == 127 || (a6.s6_addr[12] | a6.s6_addr[13] | a6.s6_addr[14] | a6.s6_addr[15]) == 0)) return 0;
    if (IN6_IS_ADDR_LINKLOCAL(&a6) && scope == 0) return 0;
    struct sockaddr_in6 *sin6 = (struct sockaddr_in6 *)&server->addr;
    sin6->sin6_family = AF_INET6;
    sin6->sin6_port = htons(53);
    sin6->sin6_addr = a6;
    sin6->sin6_scope_id = scope;
    server->len = sizeof(*sin6);
    return 1;
}

// The "nameserver" lines of a resolv.conf, in order: up to `max` usable servers, IPv4 and IPv6.
static int parse_nameservers(const char *text, ddnatv_dns_server_t *out, int max) {
    int count = 0;
    const char *line = text;
    while (line && *line && count < max) {
        const char *next = strchr(line, '\n');
        size_t len = next ? (size_t)(next - line) : strlen(line);
        char buf[256];
        if (len >= sizeof(buf)) len = sizeof(buf) - 1;
        memcpy(buf, line, len);
        buf[len] = '\0';
        char *p = buf;
        while (*p == ' ' || *p == '\t') p++;
        if (strncmp(p, "nameserver", 10) == 0 && (p[10] == ' ' || p[10] == '\t')) {
            p += 10;
            while (*p == ' ' || *p == '\t') p++;
            char *end = p;
            while (*end && *end != ' ' && *end != '\t' && *end != '\r' && *end != '#' && *end != ';') end++;
            *end = '\0';
            if (dns_server_from_string(p, &out[count])) count++;
        }
        line = next ? next + 1 : NULL;
    }
    return count;
}

static int dns_server_equal(const ddnatv_dns_server_t *a, const ddnatv_dns_server_t *b) {
    return a->len == b->len && memcmp(&a->addr, &b->addr, a->len) == 0;
}

// Builds a recursive query for one name and record type; returns its length, or -1 when the name
// is not a valid DNS name (an empty label, a label over 63 bytes, over 253 bytes in all).
static int dns_build_query(const char *name, int qtype, uint16_t txid, unsigned char *packet, size_t size) {
    if (!name || !packet || size < 12) return -1;
    size_t name_len = strlen(name);
    if (name_len > 0 && name[name_len - 1] == '.') name_len--; // "example.com." is absolute
    if (name_len == 0 || name_len > 253) return -1;
    memset(packet, 0, 12);
    packet[0] = (unsigned char)(txid >> 8);
    packet[1] = (unsigned char)(txid & 0xff);
    packet[2] = 0x01; // recursion desired
    packet[5] = 0x01; // one question
    size_t pos = 12;
    size_t start = 0;
    for (;;) {
        size_t end = start;
        while (end < name_len && name[end] != '.') end++;
        size_t len = end - start;
        if (len == 0 || len > 63 || pos + 1 + len + 1 + 4 > size) return -1;
        packet[pos++] = (unsigned char)len;
        memcpy(packet + pos, name + start, len);
        pos += len;
        if (end == name_len) break;
        start = end + 1;
    }
    packet[pos++] = 0;
    packet[pos++] = (unsigned char)(qtype >> 8);
    packet[pos++] = (unsigned char)(qtype & 0xff);
    packet[pos++] = 0x00;
    packet[pos++] = 0x01; // class IN
    return (int)pos;
}

// Offset after the (possibly compressed) name at `off`, or -1 when it is malformed.
static int dns_skip_name(const unsigned char *msg, int n, int off) {
    for (int labels = 0; labels < 128; labels++) {
        if (off >= n) return -1;
        unsigned char len = msg[off];
        if (len == 0) return off + 1;
        if ((len & 0xc0) == 0xc0) return off + 2 <= n ? off + 2 : -1;
        if (len & 0xc0) return -1;
        off += 1 + len;
    }
    return -1;
}

// Copies the first address of type `qtype` (4 bytes for A, 16 for AAAA) from the reply `msg` to the
// query `txid` and returns 0. Returns 1 when the reply says there is no such address (the name does
// not exist, or has no record of that type): asking other servers would only send the name to more
// of them. Returns -1 for anything that is worth asking another server about: a failure (SERVFAIL,
// REFUSED), a truncated, malformed or foreign reply.
static int dns_parse_answer(const unsigned char *msg, int n, uint16_t txid, int qtype, unsigned char *out) {
    if (!msg || n < 12) return -1;
    if (((msg[0] << 8) | msg[1]) != txid) return -1;
    if (!(msg[2] & 0x80)) return -1; // not a reply
    int rcode = msg[3] & 0x0f;
    if (rcode == 3) return 1;        // NXDOMAIN
    if (rcode != 0) return -1;
    int qdcount = (msg[4] << 8) | msg[5];
    int ancount = (msg[6] << 8) | msg[7];
    int off = 12;
    for (int q = 0; q < qdcount; q++) {
        off = dns_skip_name(msg, n, off);
        if (off < 0 || off + 4 > n) return -1;
        off += 4;
    }
    int want = qtype == DDNATV_DNS_TYPE_AAAA ? 16 : 4;
    for (int a = 0; a < ancount; a++) {
        off = dns_skip_name(msg, n, off);
        if (off < 0 || off + 10 > n) return -1;
        int rtype = (msg[off] << 8) | msg[off + 1];
        int rclass = (msg[off + 2] << 8) | msg[off + 3];
        int rdlen = (msg[off + 8] << 8) | msg[off + 9];
        off += 10;
        if (off + rdlen > n) return -1;
        if (rtype == qtype && rclass == 1 && rdlen == want) {
            memcpy(out, msg + off, (size_t)want);
            return 0;
        }
        off += rdlen;
    }
    return (msg[2] & 0x02) ? -1 : 1; // no such record, unless the reply was truncated
}

// ---- Sockets bound to a port the program chose ----------------------------------------------
// Which descriptors are TCP/UDP sockets that bind() gave a port the program asked for (not 0,
// where the kernel picks one). listen() reports only those to the app, which connects to a
// reported port to see whether it serves web pages (sockets.c). Descriptors past the bitmap
// are taken as not chosen: they are not reported.
#define DDNATV_CHOSEN_PORT_FDS 65536
static unsigned char g_chosen_port_fds[DDNATV_CHOSEN_PORT_FDS / 8];

static void set_chosen_port_fd(int fd, int chosen) {
    if (fd < 0 || fd >= DDNATV_CHOSEN_PORT_FDS) return;
    unsigned char bit = (unsigned char)(1u << (fd & 7));
    // Written only when it changes: a page of the bitmap is copied for a forked child only then
    if (chosen) {
        if (!(__atomic_load_n(&g_chosen_port_fds[fd >> 3], __ATOMIC_RELAXED) & bit))
            __atomic_fetch_or(&g_chosen_port_fds[fd >> 3], bit, __ATOMIC_RELAXED);
    } else if (__atomic_load_n(&g_chosen_port_fds[fd >> 3], __ATOMIC_RELAXED) & bit) {
        __atomic_fetch_and(&g_chosen_port_fds[fd >> 3], (unsigned char)~bit, __ATOMIC_RELAXED);
    }
}

static int is_chosen_port_fd(int fd) {
    if (fd < 0 || fd >= DDNATV_CHOSEN_PORT_FDS) return 0;
    return (__atomic_load_n(&g_chosen_port_fds[fd >> 3], __ATOMIC_RELAXED) >> (fd & 7)) & 1;
}

#ifndef DDNATV_HOST_TEST

// DNS resolution hooking and localhost DNS redirect
#define DNS_REDIRECT_MAX 64
static struct {
    int fd;
    struct sockaddr_storage orig;
    socklen_t orig_len;
    uint32_t active;
} g_dns_redirects[DNS_REDIRECT_MAX];
static pthread_mutex_t g_dns_redirect_mutex = PTHREAD_MUTEX_INITIALIZER;

static void record_dns_redirect(int fd, const struct sockaddr *orig, socklen_t orig_len) {
    if (fd < 0 || !orig) return;
    if (orig_len > sizeof(struct sockaddr_storage)) orig_len = sizeof(struct sockaddr_storage);
    pthread_mutex_lock(&g_dns_redirect_mutex);
    int target_idx = (fd >= 0 ? fd : -fd) % DNS_REDIRECT_MAX;
    g_dns_redirects[target_idx].fd = fd;
    memcpy(&g_dns_redirects[target_idx].orig, orig, orig_len);
    g_dns_redirects[target_idx].orig_len = orig_len;
    g_dns_redirects[target_idx].active = 1;
    pthread_mutex_unlock(&g_dns_redirect_mutex);
}

static void clear_dns_redirect(int fd) {
    if (fd < 0) return;
    int target_idx = (fd >= 0 ? fd : -fd) % DNS_REDIRECT_MAX;
    if (!g_dns_redirects[target_idx].active || g_dns_redirects[target_idx].fd != fd) {
        return;
    }
    pthread_mutex_lock(&g_dns_redirect_mutex);
    if (g_dns_redirects[target_idx].active && g_dns_redirects[target_idx].fd == fd) {
        g_dns_redirects[target_idx].active = 0;
        g_dns_redirects[target_idx].fd = -1;
        g_dns_redirects[target_idx].orig_len = 0;
    }
    pthread_mutex_unlock(&g_dns_redirect_mutex);
}

int close(int fd) {
    static int (*orig_close)(int) = NULL;
    if (!orig_close) orig_close = (int (*)(int))dlsym(RTLD_NEXT, "close");
    // The ARM translator's memory file (privileged.c): left open, as if it were closed
    if (g_kept_fd_count > 0 && is_kept_fd(fd)) return 0;
    clear_dns_redirect(fd);
    set_chosen_port_fd(fd, 0);
    return orig_close ? orig_close(fd) : -1;
}

int dup2(int oldfd, int newfd) {
    static int (*orig_dup2)(int, int) = NULL;
    if (!orig_dup2) orig_dup2 = (int (*)(int, int))dlsym(RTLD_NEXT, "dup2");
    if (oldfd != newfd) {
        clear_dns_redirect(newfd);
        set_chosen_port_fd(newfd, is_chosen_port_fd(oldfd));
    }
    return orig_dup2 ? orig_dup2(oldfd, newfd) : -1;
}

int dup3(int oldfd, int newfd, int flags) {
    static int (*orig_dup3)(int, int, int) = NULL;
    if (!orig_dup3) orig_dup3 = (int (*)(int, int, int))dlsym(RTLD_NEXT, "dup3");
    if (oldfd != newfd) {
        clear_dns_redirect(newfd);
        set_chosen_port_fd(newfd, is_chosen_port_fd(oldfd));
    }
    return orig_dup3 ? orig_dup3(oldfd, newfd, flags) : -1;
}

static int get_dns_redirect(int fd, struct sockaddr_storage *orig, socklen_t *orig_len) {
    if (fd < 0) return 0;
    int found = 0;
    pthread_mutex_lock(&g_dns_redirect_mutex);
    int target_idx = (fd >= 0 ? fd : -fd) % DNS_REDIRECT_MAX;
    if (g_dns_redirects[target_idx].active && g_dns_redirects[target_idx].fd == fd) {
        if (orig) *orig = g_dns_redirects[target_idx].orig;
        if (orig_len) *orig_len = g_dns_redirects[target_idx].orig_len;
        found = 1;
    }
    pthread_mutex_unlock(&g_dns_redirect_mutex);
    return found;
}

// A DNS query to 127.0.0.0/8 or ::1 (or ::ffff:127.x), where nothing listens on Android.
static inline int is_loopback_dns(const struct sockaddr *addr, socklen_t addrlen) {
    if (!addr || addrlen < sizeof(sa_family_t)) return 0;
    if (addr->sa_family == AF_INET && addrlen >= sizeof(struct sockaddr_in)) {
        const struct sockaddr_in *sin = (const struct sockaddr_in *)addr;
        return (sin->sin_port == htons(53) && ((ntohl(sin->sin_addr.s_addr) >> 24) == 127));
    }
    if (addr->sa_family == AF_INET6 && addrlen >= sizeof(struct sockaddr_in6)) {
        const struct sockaddr_in6 *sin6 = (const struct sockaddr_in6 *)addr;
        if (sin6->sin6_port != htons(53)) return 0;
        return IN6_IS_ADDR_LOOPBACK(&sin6->sin6_addr) ||
               (IN6_IS_ADDR_V4MAPPED(&sin6->sin6_addr) && sin6->sin6_addr.s6_addr[12] == 127);
    }
    return 0;
}

static in_addr_t get_primary_dns(void) {
    static in_addr_t cached_dns = 0;
    static time_t last_check = 0;
    static pthread_mutex_t dns_mutex = PTHREAD_MUTEX_INITIALIZER;

    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    time_t now = ts.tv_sec;

    // Fast path: cached value valid within 5s TTL
    if (cached_dns != 0 && (now - last_check < 5)) {
        return cached_dns;
    }

    pthread_mutex_lock(&dns_mutex);
    if (cached_dns != 0 && (now - last_check < 5)) {
        in_addr_t val = cached_dns;
        pthread_mutex_unlock(&dns_mutex);
        return val;
    }
    last_check = now;

    in_addr_t new_dns = 0;
    init_ddnatv_hook();

    // 1. Check Android system property net.dns1 via Bionic libc
    static int (*orig_sys_prop_get)(const char *, char *) = NULL;
    static int prop_lookup_done = 0;
    if (!prop_lookup_done) {
        orig_sys_prop_get = (int (*)(const char *, char *))dlsym(RTLD_DEFAULT, "__system_property_get");
        prop_lookup_done = 1;
    }
    if (orig_sys_prop_get) {
        char prop_val[256];
        if (orig_sys_prop_get("net.dns1", prop_val) > 0 && prop_val[0] != '\0') {
            struct in_addr a;
            if (inet_aton(prop_val, &a)) {
                if ((ntohl(a.s_addr) >> 24) != 127 && a.s_addr != 0) {
                    new_dns = a.s_addr;
                }
            }
        }
    }

    // 2. Parse /etc/resolv.conf in The native userland rootfs
    char resolv_path[PATH_MAX];
    if (g_ddnatv_root[0] != '\0') {
        snprintf(resolv_path, sizeof(resolv_path), "%s/etc/resolv.conf", g_ddnatv_root);
    } else {
        snprintf(resolv_path, sizeof(resolv_path), "/etc/resolv.conf");
    }

    struct stat st;
    if (stat(resolv_path, &st) == 0) {
        if (new_dns == 0) {
            FILE *f = fopen(resolv_path, "r");
            if (f) {
                char line[256];
                while (fgets(line, sizeof(line), f)) {
                    char *p = line;
                    while (*p == ' ' || *p == '\t') p++;
                    if (strncmp(p, "nameserver", 10) == 0) {
                        p += 10;
                        while (*p == ' ' || *p == '\t') p++;
                        char *end = p;
                        while (*end && *end != ' ' && *end != '\t' && *end != '\r' && *end != '\n') end++;
                        *end = '\0';
                        struct in_addr a;
                        if (inet_aton(p, &a)) {
                            // Only accept valid, non-loopback DNS servers
                            if ((ntohl(a.s_addr) >> 24) != 127 && a.s_addr != 0) {
                                new_dns = a.s_addr;
                                break;
                            }
                        }
                    }
                }
                fclose(f);
            }
        }
    }

    if (new_dns == 0) {
        new_dns = inet_addr("8.8.8.8");
    }

    cached_dns = new_dns;
    pthread_mutex_unlock(&dns_mutex);
    return cached_dns;
}


// The DNS servers in the rootfs resolv.conf (which the app writes from Android's network settings),
// read again at most every 5 seconds.
static int get_dns_servers(ddnatv_dns_server_t *out, int max) {
    static ddnatv_dns_server_t cached[DDNATV_DNS_MAX_SERVERS];
    static int cached_count = 0;
    static time_t last_check = 0;
    static int checked = 0;
    static pthread_mutex_t servers_mutex = PTHREAD_MUTEX_INITIALIZER;

    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    pthread_mutex_lock(&servers_mutex);
    if (!checked || ts.tv_sec - last_check >= 5) {
        checked = 1;
        last_check = ts.tv_sec;
        init_ddnatv_hook();
        char path[PATH_MAX];
        snprintf(path, sizeof(path), "%s/etc/resolv.conf", g_ddnatv_root[0] ? g_ddnatv_root : "");
        char text[4096];
        ssize_t n = -1;
        int fd = open(path, O_RDONLY | O_CLOEXEC);
        if (fd >= 0) {
            n = read(fd, text, sizeof(text) - 1);
            close(fd);
        }
        text[n > 0 ? n : 0] = '\0';
        cached_count = parse_nameservers(text, cached, DDNATV_DNS_MAX_SERVERS);
    }
    int count = cached_count < max ? cached_count : max;
    memcpy(out, cached, (size_t)count * sizeof(cached[0]));
    pthread_mutex_unlock(&servers_mutex);
    return count;
}

// Where a DNS query to a loopback address goes instead: the network's DNS server, in the family of
// the socket (an IPv6 socket gets the first IPv6 server, else the IPv4 one as ::ffff:a.b.c.d).
// Returns the length of the address written to `out`.
static socklen_t dns_redirect_target(const struct sockaddr *orig, struct sockaddr_storage *out) {
    memset(out, 0, sizeof(*out));
    if (orig->sa_family == AF_INET) {
        memcpy(out, orig, sizeof(struct sockaddr_in));
        ((struct sockaddr_in *)out)->sin_addr.s_addr = get_primary_dns();
        return sizeof(struct sockaddr_in);
    }
    struct sockaddr_in6 *dst = (struct sockaddr_in6 *)out;
    memcpy(dst, orig, sizeof(*dst));
    ddnatv_dns_server_t servers[DDNATV_DNS_MAX_SERVERS];
    int n = get_dns_servers(servers, DDNATV_DNS_MAX_SERVERS);
    for (int i = 0; i < n; i++) {
        if (servers[i].addr.ss_family == AF_INET6) {
            const struct sockaddr_in6 *s6 = (const struct sockaddr_in6 *)&servers[i].addr;
            dst->sin6_addr = s6->sin6_addr;
            dst->sin6_scope_id = s6->sin6_scope_id;
            return sizeof(*dst);
        }
    }
    in_addr_t v4 = get_primary_dns();
    memset(&dst->sin6_addr, 0, sizeof(dst->sin6_addr));
    dst->sin6_addr.s6_addr[10] = 0xff;
    dst->sin6_addr.s6_addr[11] = 0xff;
    memcpy(&dst->sin6_addr.s6_addr[12], &v4, 4);
    dst->sin6_scope_id = 0;
    return sizeof(*dst);
}

// Puts the loopback address that a DNS socket was redirected from back into the source address of a
// reply (`cap` is the size of the caller's buffer), so resolvers that check the sender accept it.
static void restore_dns_source(int fd, struct sockaddr *addr, socklen_t cap, socklen_t *len) {
    if (!addr || cap < sizeof(sa_family_t)) return;
    in_port_t port;
    if (addr->sa_family == AF_INET && cap >= sizeof(struct sockaddr_in)) {
        port = ((const struct sockaddr_in *)addr)->sin_port;
    } else if (addr->sa_family == AF_INET6 && cap >= sizeof(struct sockaddr_in6)) {
        port = ((const struct sockaddr_in6 *)addr)->sin6_port;
    } else {
        return;
    }
    if (port != htons(53)) return;
    struct sockaddr_storage orig;
    socklen_t orig_len = 0;
    if (!get_dns_redirect(fd, &orig, &orig_len) || orig.ss_family != addr->sa_family) return;
    memcpy(addr, &orig, orig_len < cap ? orig_len : cap);
    if (len) *len = orig_len;
}

#define DDNATV_ADDRINFO_MAGIC 0x434f5254

// One addrinfo for an IPv4 (4 bytes) or IPv6 (16 bytes) address; freed by the freeaddrinfo hook.
static struct addrinfo *alloc_addrinfo(const char *node, int family, const void *addr, int port, int socktype, int protocol) {
    struct addrinfo *ai = (struct addrinfo *)calloc(1, sizeof(struct addrinfo));
    if (!ai) return NULL;
    socklen_t len = family == AF_INET6 ? sizeof(struct sockaddr_in6) : sizeof(struct sockaddr_in);
    void *sa = calloc(1, len);
    if (!sa) {
        free(ai);
        return NULL;
    }
    if (family == AF_INET6) {
        struct sockaddr_in6 *sin6 = (struct sockaddr_in6 *)sa;
        sin6->sin6_family = AF_INET6;
        sin6->sin6_port = htons((uint16_t)port);
        memcpy(&sin6->sin6_addr, addr, 16);
    } else {
        struct sockaddr_in *sin = (struct sockaddr_in *)sa;
        sin->sin_family = AF_INET;
        sin->sin_port = htons((uint16_t)port);
        memcpy(&sin->sin_addr, addr, 4);
    }

    ai->ai_flags = DDNATV_ADDRINFO_MAGIC;
    ai->ai_family = family;
    ai->ai_socktype = socktype ? socktype : SOCK_STREAM;
    ai->ai_protocol = protocol;
    ai->ai_addrlen = len;
    ai->ai_addr = (struct sockaddr *)sa;
    ai->ai_canonname = node ? strdup(node) : NULL;
    ai->ai_next = NULL;
    return ai;
}

static uint16_t generate_dns_txid(void) {
    uint16_t txid = 0;
    static ssize_t (*libc_getrandom)(void *, size_t, unsigned int) = NULL;
    static int getrandom_checked = 0;
    if (!getrandom_checked) {
        libc_getrandom = (ssize_t (*)(void *, size_t, unsigned int))dlsym(RTLD_DEFAULT, "getrandom");
        getrandom_checked = 1;
    }
    if (libc_getrandom) {
        if (libc_getrandom(&txid, sizeof(txid), 1 /* GRND_NONBLOCK */) == sizeof(txid) && txid != 0) {
            return txid;
        }
    }
#if defined(SYS_getrandom) || defined(__NR_getrandom)
#ifndef __NR_getrandom
#define __NR_getrandom SYS_getrandom
#endif
    long r = syscall(__NR_getrandom, &txid, sizeof(txid), 1 /* GRND_NONBLOCK */);
    if (r == (long)sizeof(txid) && txid != 0) {
        return txid;
    }
#endif
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    uint32_t val = (uint32_t)(ts.tv_nsec ^ (ts.tv_sec << 16) ^ (uintptr_t)&txid);
    txid = (uint16_t)((val & 0xffff) ^ (val >> 16));
    if (txid == 0) txid = 0x5a6b;
    return txid;
}

static void add_dns_server(ddnatv_dns_server_t *list, int *count, int max, const ddnatv_dns_server_t *server) {
    if (*count >= max) return;
    for (int i = 0; i < *count; i++) {
        if (dns_server_equal(&list[i], server)) return;
    }
    list[(*count)++] = *server;
}

// Looks one address of `name` up itself, for when glibc found none: asks the network's DNS servers,
// then public ones (IPv4 first, then IPv6, for IPv6-only networks without IPv4 translation), and
// stops at the first that answers. `qtype` is DDNATV_DNS_TYPE_A (4 bytes into `out`) or
// DDNATV_DNS_TYPE_AAAA (16 bytes). `*server` is -1 to ask them in turn, or the index of the only
// server to ask; on return it holds the server that answered (-1 when none did).
static int dns_lookup(const char *name, int qtype, unsigned char *out, int *server) {
    if (!name || !out) return -1;
    int family = qtype == DDNATV_DNS_TYPE_AAAA ? AF_INET6 : AF_INET;
    if (inet_pton(family, name, out) == 1) return 0;

    static const char *const k_public_dns[] = {
        "8.8.8.8", "1.1.1.1", "9.9.9.9", "2001:4860:4860::8888", "2606:4700:4700::1111",
    };
    ddnatv_dns_server_t servers[DDNATV_DNS_MAX_SERVERS + 6];
    int max = (int)(sizeof(servers) / sizeof(servers[0]));
    int count = 0;
    ddnatv_dns_server_t s;
    // the network's own servers in resolv.conf's order first, its IPv6 ones too: the first IPv4 one
    // (get_primary_dns) can be the app's public fallback, which must not see private names first
    ddnatv_dns_server_t network[DDNATV_DNS_MAX_SERVERS];
    int network_count = get_dns_servers(network, DDNATV_DNS_MAX_SERVERS);
    for (int i = 0; i < network_count; i++) add_dns_server(servers, &count, max, &network[i]);
    char primary[INET_ADDRSTRLEN];
    in_addr_t p_dns = get_primary_dns(); // also covers net.dns1 of older Android versions
    if (inet_ntop(AF_INET, &p_dns, primary, sizeof(primary)) && dns_server_from_string(primary, &s)) {
        add_dns_server(servers, &count, max, &s);
    }
    for (size_t i = 0; i < sizeof(k_public_dns) / sizeof(k_public_dns[0]); i++) {
        if (dns_server_from_string(k_public_dns[i], &s)) add_dns_server(servers, &count, max, &s);
    }

    unsigned char packet[512];
    uint16_t txid = generate_dns_txid();
    int packet_len = dns_build_query(name, qtype, txid, packet, sizeof(packet));
    if (packet_len < 0) return -1;

    int only = *server;
    *server = -1;
    for (int i = (only >= 0 ? only : 0); i < count && (only < 0 || i == only); i++) {
        int sock = socket(servers[i].addr.ss_family, SOCK_DGRAM | SOCK_CLOEXEC, 0);
        if (sock < 0) continue;

        struct timeval tv;
        tv.tv_sec = 0;
        tv.tv_usec = 600000; // 600ms fast failover
        setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

        // A connected socket only receives datagrams from that server.
        if (connect(sock, (const struct sockaddr *)&servers[i].addr, servers[i].len) != 0 ||
            send(sock, packet, (size_t)packet_len, 0) != packet_len) {
            close(sock);
            continue;
        }
        unsigned char resp[1500];
        ssize_t n = recv(sock, resp, sizeof(resp), 0);
        close(sock);
        int result = n > 0 ? dns_parse_answer(resp, (int)n, txid, qtype, out) : -1;
        if (result >= 0) {
            *server = i;
            return result == 0 ? 0 : -1;
        }
    }
    return -1;
}

// Whether the network's DNS servers are all IPv6 ones, as on an IPv6-only mobile network.
static int network_dns_is_ipv6_only(void) {
    ddnatv_dns_server_t servers[DDNATV_DNS_MAX_SERVERS];
    int n = get_dns_servers(servers, DDNATV_DNS_MAX_SERVERS);
    for (int i = 0; i < n; i++) {
        if (servers[i].addr.ss_family != AF_INET6) return 0;
    }
    return n > 0;
}

// glibc reads /etc/services with an open() of its own, which the path hooks never see, so a service
// name was looked up where Android has no such file: getaddrinfo(host, "https") failed with
// EAI_SERVICE. The rootfs's file is read here when glibc finds nothing. 0, ENOENT or ERANGE.
static int ddnatv_lookup_service(const char *name, int port, const char *proto,
                                 struct servent *out, char *buf, size_t buflen) {
    char path_buf[PATH_MAX];
    const char *path = rewrite_path("/etc/services", path_buf, sizeof(path_buf));
    FILE *f = path ? fopen(path, "re") : NULL;
    if (!f) return ENOENT;
    char line[512];
    int result = ENOENT;
    while (result == ENOENT && fgets(line, sizeof(line), f)) {
        char *hash = strchr(line, '#');
        if (hash) *hash = '\0';
        char *save = NULL;
        char *svc = strtok_r(line, " \t\r\n", &save);
        char *pp = svc ? strtok_r(NULL, " \t\r\n", &save) : NULL;
        char *slash = pp ? strchr(pp, '/') : NULL;
        if (!slash) continue;
        *slash = '\0';
        const char *svc_proto = slash + 1;
        int svc_port = atoi(pp);
        if (proto && strcmp(proto, svc_proto) != 0) continue;
        char *aliases[16];
        int naliases = 0;
        int matched = name ? strcmp(svc, name) == 0 : svc_port == port;
        for (char *a = strtok_r(NULL, " \t\r\n", &save); a && naliases < 15; a = strtok_r(NULL, " \t\r\n", &save)) {
            aliases[naliases++] = a;
            if (name && strcmp(a, name) == 0) matched = 1;
        }
        if (!matched) continue;
        // buf holds the alias pointers, then the strings
        size_t need = sizeof(char *) * (size_t)(naliases + 1) + strlen(svc) + 1 + strlen(svc_proto) + 1;
        for (int i = 0; i < naliases; i++) need += strlen(aliases[i]) + 1;
        if (need > buflen) { result = ERANGE; break; }
        char **list = (char **)(void *)buf;
        char *p = buf + sizeof(char *) * (size_t)(naliases + 1);
        out->s_name = strcpy(p, svc); p += strlen(svc) + 1;
        out->s_proto = strcpy(p, svc_proto); p += strlen(svc_proto) + 1;
        for (int i = 0; i < naliases; i++) { list[i] = strcpy(p, aliases[i]); p += strlen(aliases[i]) + 1; }
        list[naliases] = NULL;
        out->s_aliases = list;
        out->s_port = (int)htons((uint16_t)svc_port);
        result = 0;
    }
    fclose(f);
    return result;
}

int getservbyname_r(const char *name, const char *proto, struct servent *result_buf, char *buf, size_t buflen,
                    struct servent **result) {
    static int (*orig)(const char *, const char *, struct servent *, char *, size_t, struct servent **) = NULL;
    if (!orig) orig = (int (*)(const char *, const char *, struct servent *, char *, size_t, struct servent **))dlsym(RTLD_NEXT, "getservbyname_r");
    int ret = orig ? orig(name, proto, result_buf, buf, buflen, result) : ENOENT;
    if (ret == 0 && result && *result) return 0;
    if (!name || !result_buf || !buf) return ret;
    int err = ddnatv_lookup_service(name, 0, proto, result_buf, buf, buflen);
    if (result) *result = err == 0 ? result_buf : NULL;
    return err == ERANGE ? ERANGE : 0;
}

int getservbyport_r(int port, const char *proto, struct servent *result_buf, char *buf, size_t buflen,
                    struct servent **result) {
    static int (*orig)(int, const char *, struct servent *, char *, size_t, struct servent **) = NULL;
    if (!orig) orig = (int (*)(int, const char *, struct servent *, char *, size_t, struct servent **))dlsym(RTLD_NEXT, "getservbyport_r");
    int ret = orig ? orig(port, proto, result_buf, buf, buflen, result) : ENOENT;
    if (ret == 0 && result && *result) return 0;
    if (!result_buf || !buf) return ret;
    int err = ddnatv_lookup_service(NULL, ntohs((uint16_t)port), proto, result_buf, buf, buflen);
    if (result) *result = err == 0 ? result_buf : NULL;
    return err == ERANGE ? ERANGE : 0;
}

struct servent *getservbyname(const char *name, const char *proto) {
    static __thread struct servent entry;
    static __thread char buf[1024];
    struct servent *found = NULL;
    return getservbyname_r(name, proto, &entry, buf, sizeof(buf), &found) == 0 ? found : NULL;
}

struct servent *getservbyport(int port, const char *proto) {
    static __thread struct servent entry;
    static __thread char buf[1024];
    struct servent *found = NULL;
    return getservbyport_r(port, proto, &entry, buf, sizeof(buf), &found) == 0 ? found : NULL;
}

static int synthesize_fallback_addrinfo(const char *node, const char *service,
                                        const struct addrinfo *hints,
                                        struct addrinfo **res) {
    if (!node || !res) return EAI_NONAME;

    int socktype = (hints && hints->ai_socktype) ? hints->ai_socktype : SOCK_STREAM;
    int protocol = (hints && hints->ai_protocol) ? hints->ai_protocol
        : socktype == SOCK_DGRAM ? IPPROTO_UDP : socktype == SOCK_STREAM ? IPPROTO_TCP : 0;
    int family = hints ? hints->ai_family : AF_UNSPEC;
    if (family != AF_UNSPEC && family != AF_INET && family != AF_INET6) return EAI_FAMILY;

    // As glibc: no service is port 0, a number is that port, a name comes from /etc/services
    int port = 0;
    if (service && *service) {
        char *end = NULL;
        errno = 0;
        long p = strtol(service, &end, 10);
        if (end && *end == '\0' && errno == 0) {
            if (p < 0 || p > 65535) return EAI_SERVICE;
            port = (int)p;
        } else {
            struct servent se, *found = NULL;
            char se_buf[1024];
            if (getservbyname_r(service, socktype == SOCK_DGRAM ? "udp" : "tcp", &se, se_buf, sizeof(se_buf), &found) != 0 || !found)
                return EAI_SERVICE;
            port = ntohs((uint16_t)found->s_port);
        }
    }

    // The address families the caller asked for: an IPv6-only caller must not get an IPv4 address,
    // and AF_UNSPEC gets both, IPv6 first on a network whose DNS servers are all IPv6 ones. The second
    // lookup only asks the server that answered the first, and is skipped when none answered.
    int want4 = family == AF_UNSPEC || family == AF_INET;
    int want6 = family == AF_UNSPEC || family == AF_INET6;
    int v6_first = want6 && (!want4 || network_dns_is_ipv6_only());
    unsigned char resolved[16];
    int server = -1;
    struct addrinfo *first = NULL;
    struct addrinfo *second = NULL;
    if (dns_lookup(node, v6_first ? DDNATV_DNS_TYPE_AAAA : DDNATV_DNS_TYPE_A, resolved, &server) == 0) {
        first = alloc_addrinfo(node, v6_first ? AF_INET6 : AF_INET, resolved, port, socktype, protocol);
    }
    if (want4 && want6 && server >= 0 &&
        dns_lookup(node, v6_first ? DDNATV_DNS_TYPE_A : DDNATV_DNS_TYPE_AAAA, resolved, &server) == 0) {
        second = alloc_addrinfo(node, v6_first ? AF_INET : AF_INET6, resolved, port, socktype, protocol);
    }
    if (first && second) {
        first->ai_next = second;
    }
    struct addrinfo *ai = first ? first : second;
    if (ai) {
        *res = ai;
        return 0;
    }

    return EAI_NONAME;
}

int getaddrinfo(const char *node, const char *service,
                const struct addrinfo *hints,
                struct addrinfo **res) {
    static int (*orig_getaddrinfo)(const char *, const char *, const struct addrinfo *, struct addrinfo **) = NULL;
    if (!orig_getaddrinfo) orig_getaddrinfo = (int (*)(const char *, const char *, const struct addrinfo *, struct addrinfo **))dlsym(RTLD_NEXT, "getaddrinfo");

    struct addrinfo mod_hints;
    if (hints) {
        mod_hints = *hints;
        // On Android, unprivileged apps are blocked by SELinux from querying netlink routing interfaces.
        // Glibc's AI_ADDRCONFIG flag causes getaddrinfo to fail or return no addresses when netlink fails.
        // Clearing AI_ADDRCONFIG ensures normal, robust DNS resolution.
        mod_hints.ai_flags &= ~AI_ADDRCONFIG;
    } else {
        memset(&mod_hints, 0, sizeof(mod_hints));
        mod_hints.ai_family = AF_UNSPEC;
        mod_hints.ai_flags = 0;
    }

    int ret = orig_getaddrinfo ? orig_getaddrinfo(node, service, &mod_hints, res) : EAI_FAIL;
    // A numeric-only lookup must never turn into a DNS query.
    if ((ret != 0 || !res || !*res) && node && !(hints && (hints->ai_flags & AI_NUMERICHOST))) {
        int fallback = synthesize_fallback_addrinfo(node, service, hints, res);
        // When the fallback finds nothing either, glibc's own error (EAI_AGAIN: try again later,
        // EAI_NONAME: no such name) is the more precise one.
        // A request the fallback rejects outright (no such service, an unsupported family) is that
        // error whatever glibc's was
        if (fallback == 0 || ret == 0 || fallback == EAI_SERVICE || fallback == EAI_FAMILY) ret = fallback;
    }
    return ret;
}

void freeaddrinfo(struct addrinfo *res) {
    static void (*orig_freeaddrinfo)(struct addrinfo *) = NULL;
    if (!orig_freeaddrinfo) orig_freeaddrinfo = (void (*)(struct addrinfo *))dlsym(RTLD_NEXT, "freeaddrinfo");

    if (!res) return;
    if (res->ai_flags == DDNATV_ADDRINFO_MAGIC) {
        struct addrinfo *curr = res;
        while (curr) {
            struct addrinfo *next = curr->ai_next;
            if (curr->ai_canonname) free(curr->ai_canonname);
            if (curr->ai_addr) free(curr->ai_addr);
            free(curr);
            curr = next;
        }
        return;
    }
    if (orig_freeaddrinfo) orig_freeaddrinfo(res);
}

// gethostbyname() and gethostbyname2() read /etc/resolv.conf with glibc's own open(), on Android's
// /etc, so they asked no DNS server and only /etc/hosts answered. When glibc finds nothing, the
// name is looked up with getaddrinfo() above, which knows the network's servers; a name DNS does
// not have still gets no address (HOST_NOT_FOUND).
static int hostent_from_getaddrinfo(const char *name, int af, struct hostent *ret, char *buf, size_t buflen,
                                    struct hostent **result, int *herr) {
    *result = NULL;
    if (!name || (af != AF_INET && af != AF_INET6)) { *herr = NO_RECOVERY; return EINVAL; }
    struct addrinfo hints;
    memset(&hints, 0, sizeof(hints));
    hints.ai_family = af;
    hints.ai_socktype = SOCK_STREAM;
    hints.ai_flags = AI_CANONNAME;
    struct addrinfo *res = NULL;
    int rc = getaddrinfo(name, NULL, &hints, &res);
    if (rc != 0 || !res) {
        *herr = (rc == EAI_NONAME || rc == EAI_NODATA || rc == EAI_ADDRFAMILY) ? HOST_NOT_FOUND
              : rc == EAI_AGAIN ? TRY_AGAIN : NO_RECOVERY;
        if (res) freeaddrinfo(res);
        return rc == EAI_AGAIN ? EAGAIN : ENOENT;
    }
    size_t addr_len = af == AF_INET ? sizeof(struct in_addr) : sizeof(struct in6_addr);
    int count = 0;
    for (struct addrinfo *a = res; a && count < 16; a = a->ai_next) if (a->ai_family == af) count++;
    const char *canon = res->ai_canonname ? res->ai_canonname : name;
    size_t canon_len = strlen(canon) + 1;
    size_t need = sizeof(char *) * (size_t)(count + 1) + sizeof(char *) + addr_len * (size_t)count + canon_len + sizeof(void *);
    if (count == 0 || need > buflen) {
        freeaddrinfo(res);
        if (count == 0) { *herr = NO_DATA; return ENOENT; }
        *herr = NETDB_INTERNAL;
        return ERANGE;
    }
    // pointers first, aligned, then the addresses and the name
    char *p = buf + ((sizeof(void *) - ((uintptr_t)buf % sizeof(void *))) % sizeof(void *));
    char **addr_list = (char **)p;
    p += sizeof(char *) * (size_t)(count + 1);
    char **aliases = (char **)p;
    p += sizeof(char *);
    aliases[0] = NULL;
    int i = 0;
    for (struct addrinfo *a = res; a && i < count; a = a->ai_next) {
        if (a->ai_family != af) continue;
        const void *src = af == AF_INET ? (const void *)&((struct sockaddr_in *)a->ai_addr)->sin_addr
                                        : (const void *)&((struct sockaddr_in6 *)a->ai_addr)->sin6_addr;
        memcpy(p, src, addr_len);
        addr_list[i++] = p;
        p += addr_len;
    }
    addr_list[i] = NULL;
    memcpy(p, canon, canon_len);
    ret->h_name = p;
    ret->h_aliases = aliases;
    ret->h_addrtype = af;
    ret->h_length = (int)addr_len;
    ret->h_addr_list = addr_list;
    freeaddrinfo(res);
    *result = ret;
    *herr = 0;
    return 0;
}

int gethostbyname2_r(const char *name, int af, struct hostent *ret, char *buf, size_t buflen,
                     struct hostent **result, int *h_errnop) {
    static int (*orig)(const char *, int, struct hostent *, char *, size_t, struct hostent **, int *) = NULL;
    if (!orig) orig = (int (*)(const char *, int, struct hostent *, char *, size_t, struct hostent **, int *))dlsym(RTLD_NEXT, "gethostbyname2_r");
    int herr = 0;
    int rc = orig ? orig(name, af, ret, buf, buflen, result, &herr) : ENOSYS;
    if (rc == 0 && result && *result) { if (h_errnop) *h_errnop = 0; return 0; }
    if (rc == ERANGE) { if (h_errnop) *h_errnop = herr; return rc; }
    int fallback_herr = 0;
    struct hostent *found = NULL;
    rc = hostent_from_getaddrinfo(name, af, ret, buf, buflen, &found, &fallback_herr);
    if (result) *result = found;
    if (h_errnop) *h_errnop = fallback_herr;
    return rc;
}

int gethostbyname_r(const char *name, struct hostent *ret, char *buf, size_t buflen,
                    struct hostent **result, int *h_errnop) {
    return gethostbyname2_r(name, AF_INET, ret, buf, buflen, result, h_errnop);
}

static __thread struct hostent t_hostent;
static __thread char t_hostent_buf[4096];

struct hostent *gethostbyname2(const char *name, int af) {
    struct hostent *result = NULL;
    int herr = 0;
    gethostbyname2_r(name, af, &t_hostent, t_hostent_buf, sizeof(t_hostent_buf), &result, &herr);
    h_errno = result ? 0 : herr;
    return result;
}

struct hostent *gethostbyname(const char *name) {
    return gethostbyname2(name, AF_INET);
}

// Whether a resolver state names a server other than loopback. glibc keeps IPv6 servers in the
// extension (their nsaddr_list entry has family 0), so an IPv6-only resolv.conf counts too.
// The network's own servers, IPv6 ones too (an IPv6-only network has no IPv4 server to ask), for a
// state glibc left with none it can use: it read no /etc/resolv.conf. glibc frees the IPv6 entries.
static void fill_fallback_servers(res_state statp) {
    ddnatv_dns_server_t servers[DDNATV_DNS_MAX_SERVERS];
    int n = get_dns_servers(servers, DDNATV_DNS_MAX_SERVERS);
    for (int i = 0; i < MAXNS; i++) {
        free(statp->_u._ext.nsaddrs[i]);
        statp->_u._ext.nsaddrs[i] = NULL;
    }
    int count = 0;
    for (int i = 0; i < n && count < MAXNS; i++) {
        if (servers[i].addr.ss_family == AF_INET) {
            memcpy(&statp->nsaddr_list[count], &servers[i].addr, sizeof(struct sockaddr_in));
            statp->nsaddr_list[count].sin_port = htons(53);
            statp->_u._ext.nsaddrs[count] = NULL;
            count++;
        } else if (servers[i].addr.ss_family == AF_INET6) {
            struct sockaddr_in6 *six = malloc(sizeof(*six));
            if (!six) continue;
            memcpy(six, &servers[i].addr, sizeof(*six));
            six->sin6_port = htons(53);
            memset(&statp->nsaddr_list[count], 0, sizeof(statp->nsaddr_list[count]));
            statp->_u._ext.nsaddrs[count] = six;
            count++;
        }
    }
    if (count == 0) {
        statp->nsaddr_list[0].sin_family = AF_INET;
        statp->nsaddr_list[0].sin_port = htons(53);
        statp->nsaddr_list[0].sin_addr.s_addr = get_primary_dns();
        statp->nsaddr_list[1].sin_family = AF_INET;
        statp->nsaddr_list[1].sin_port = htons(53);
        inet_pton(AF_INET, "8.8.8.8", &statp->nsaddr_list[1].sin_addr);
        count = 2;
    }
    statp->nscount = count;
}

static int res_state_has_usable_server(res_state statp) {
    for (int i = 0; i < statp->nscount && i < MAXNS; i++) {
        if (statp->nsaddr_list[i].sin_family == AF_INET) {
            in_addr_t a = statp->nsaddr_list[i].sin_addr.s_addr;
            if ((ntohl(a) >> 24) != 127 && a != 0) return 1;
        } else if (statp->_u._ext.nsaddrs[i] != NULL) {
            const struct in6_addr *a6 = &statp->_u._ext.nsaddrs[i]->sin6_addr;
            if (!IN6_IS_ADDR_LOOPBACK(a6) && !IN6_IS_ADDR_UNSPECIFIED(a6)) return 1;
        }
    }
    return 0;
}

int res_init(void) {
    static int (*orig_res_init)(void) = NULL;
    // glibc's symbols are __res_init and __res_ninit (resolv.h makes the names macros): looked up as
    // res_init and res_ninit they were never found, the state stayed without RES_INIT, and Chromium
    // (Steam's store) failed to read a DNS configuration
    if (!orig_res_init) orig_res_init = (int (*)(void))dlsym(RTLD_NEXT, "__res_init");
    if (!orig_res_init) orig_res_init = (int (*)(void))dlsym(RTLD_NEXT, "res_init");
    int ret = orig_res_init ? orig_res_init() : 0;
    res_state statp = __res_state();
    if (statp) {
        if (!res_state_has_usable_server(statp)) fill_fallback_servers(statp);
        statp->retrans = 1;
        statp->retry = 2;
    }
    return ret;
}

int res_ninit(res_state statp) {
    static int (*orig_res_ninit)(res_state) = NULL;
    if (!orig_res_ninit) orig_res_ninit = (int (*)(res_state))dlsym(RTLD_NEXT, "__res_ninit");
    if (!orig_res_ninit) orig_res_ninit = (int (*)(res_state))dlsym(RTLD_NEXT, "res_ninit");
    if (statp) {
        memset(statp, 0, sizeof(*statp));
        statp->_vcsock = -1;
        for (int i = 0; i < MAXNS; i++) {
            statp->_u._ext.nssocks[i] = -1;
        }
    }
    int ret = orig_res_ninit ? orig_res_ninit(statp) : 0;
    if (statp) {
        if (!res_state_has_usable_server(statp)) fill_fallback_servers(statp);
        statp->retrans = 1;
        statp->retry = 2;
    }
    return ret;
}

int res_nquery(res_state statp, const char *dname, int class, int type,
               unsigned char *answer, int anslen) {
    static int (*orig_res_nquery)(res_state, const char *, int, int, unsigned char *, int) = NULL;
    if (!orig_res_nquery) orig_res_nquery = (int (*)(res_state, const char *, int, int, unsigned char *, int))dlsym(RTLD_NEXT, "res_nquery");
    // DNS SRV queries are optional for APT mirrors and frequently cause delays or issues
    if (type == 33 /* T_SRV */) {
        h_errno = NO_DATA;
        return -1;
    }
    return orig_res_nquery ? orig_res_nquery(statp, dname, class, type, answer, anslen) : -1;
}

int res_query(const char *dname, int class, int type,
              unsigned char *answer, int anslen) {
    static int (*orig_res_query)(const char *, int, int, unsigned char *, int) = NULL;
    if (!orig_res_query) orig_res_query = (int (*)(const char *, int, int, unsigned char *, int))dlsym(RTLD_NEXT, "res_query");
    if (type == 33 /* T_SRV */) {
        h_errno = NO_DATA;
        return -1;
    }
    return orig_res_query ? orig_res_query(dname, class, type, answer, anslen) : -1;
}

int res_nsearch(res_state statp, const char *dname, int class, int type,
                unsigned char *answer, int anslen) {
    static int (*orig_res_nsearch)(res_state, const char *, int, int, unsigned char *, int) = NULL;
    if (!orig_res_nsearch) orig_res_nsearch = (int (*)(res_state, const char *, int, int, unsigned char *, int))dlsym(RTLD_NEXT, "res_nsearch");
    if (type == 33 /* T_SRV */) {
        h_errno = NO_DATA;
        return -1;
    }
    return orig_res_nsearch ? orig_res_nsearch(statp, dname, class, type, answer, anslen) : -1;
}

int res_search(const char *dname, int class, int type,
               unsigned char *answer, int anslen) {
    static int (*orig_res_search)(const char *, int, int, unsigned char *, int) = NULL;
    if (!orig_res_search) orig_res_search = (int (*)(const char *, int, int, unsigned char *, int))dlsym(RTLD_NEXT, "res_search");
    if (type == 33 /* T_SRV */) {
        h_errno = NO_DATA;
        return -1;
    }
    return orig_res_search ? orig_res_search(dname, class, type, answer, anslen) : -1;
}

#endif /* !DDNATV_HOST_TEST */
