#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <signal.h>
#include <stdint.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <unistd.h>


#ifndef MADV_PAGEOUT
#define MADV_PAGEOUT 21
#endif
#ifndef ZR_CTL
#define ZR_CTL "/tmp/.dd-zram"
#endif
#define ZR_SWAPPED (1ULL << 62)
#define ZR_PRESENT (1ULL << 63)
#define ZR_BATCH 512
#define ZR_CHUNK (1UL << 20)
#define ZR_SLOTS 32768UL
#define ZR_PROBES 16
#define ZR_HEAT_MAX 3
#define ZR_SKIP 32U
#define ZR_INLINE_KB (32UL << 10)
#define ZR_INLINE_CHUNKS 512UL
#define ZR_FAILED (~0UL)
#define ZR_FUTEX_WAIT 0
#define ZR_FUTEX_WAKE 1

static long (*zr_sys)(long, ...);
static unsigned long zr_page;
static int zr_busy;
static int zr_pending;
static int zr_worker;
static char zr_maps[8192];
static uint64_t zr_entries[ZR_BATCH];
static struct iovec zr_iov[ZR_BATCH];
static char zr_sink[ZR_BATCH];
static struct zr_slot {
  unsigned long key;
  uint16_t gen;
  uint16_t pushed;
  uint16_t kept;
  uint8_t heat;
  uint8_t skip;
} zr_slots[ZR_SLOTS];
static uint16_t zr_gen = 1;
static unsigned long zr_cursor;
static unsigned long zr_visits;

static int zr_open(const char *path) {
  return (int)zr_sys(SYS_openat, AT_FDCWD, path, O_RDONLY | O_CLOEXEC, 0);
}

static int zr_mode(unsigned long *want) {
  char text[24];
  long n = 0;
  int fd = zr_open(ZR_CTL ".hold");
  if (fd >= 0) {
    n = zr_sys(SYS_read, fd, text, sizeof(text));
    zr_sys(SYS_close, fd);
  }
  if (n < 1) {
    fd = zr_open(ZR_CTL);
    if (fd < 0) return -1;
    n = zr_sys(SYS_read, fd, text, sizeof(text));
    zr_sys(SYS_close, fd);
  }
  if (n < 1 || text[0] < '0' || text[0] > '3') return -1;
  unsigned long kb = 0;
  for (long i = 2; i < n && text[i] >= '0' && text[i] <= '9'; i++)
    kb = kb * 10 + (unsigned long)(text[i] - '0');
  *want = kb / (zr_page / 1024);
  if (text[0] >= '2' && *want == 0) return -1;
  return text[0] - '0';
}

static unsigned long zr_hex(const char **p) {
  unsigned long v = 0;
  for (;; (*p)++) {
    char c = **p;
    if (c >= '0' && c <= '9') v = v * 16 + (unsigned long)(c - '0');
    else if (c >= 'a' && c <= 'f') v = v * 16 + (unsigned long)(c - 'a' + 10);
    else return v;
  }
}

static int zr_eligible(const char *line, int compress, unsigned long *start, unsigned long *end) {
  const char *p = line;
  *start = zr_hex(&p);
  if (*p++ != '-') return 0;
  *end = zr_hex(&p);
  if (*p++ != ' ' || p[0] != 'r' || p[3] != 'p') return 0;
  if (!compress) return 1;
  if (p[1] != 'w') return 0;
  for (int field = 0; field < 4; field++) {
    while (*p && *p != ' ') p++;
    while (*p == ' ') p++;
  }
  return *p == 0 || strncmp(p, "[heap]", 6) == 0 || strncmp(p, "[anon:", 6) == 0;
}

static void zr_read_back(int pagemap, unsigned long start, unsigned long end) {
  pid_t self = (pid_t)zr_sys(SYS_getpid);
  struct iovec local = {zr_sink, 0};
  for (unsigned long addr = start; addr < end;) {
    unsigned long count = (end - addr) / zr_page;
    if (count > ZR_BATCH) count = ZR_BATCH;
    if (zr_sys(SYS_lseek, pagemap, (long)(addr / zr_page * sizeof(uint64_t)), SEEK_SET) < 0) return;
    long got = zr_sys(SYS_read, pagemap, zr_entries, count * sizeof(uint64_t));
    if (got < (long)sizeof(uint64_t)) return;
    unsigned long n = 0;
    for (unsigned long i = 0; i < (unsigned long)got / sizeof(uint64_t); i++) {
      if (!(zr_entries[i] & ZR_SWAPPED)) continue;
      zr_iov[n].iov_base = (void *)(addr + i * zr_page);
      zr_iov[n++].iov_len = 1;
    }
    for (unsigned long done = 0; done < n;) {
      local.iov_len = n - done;
      long r = zr_sys(SYS_process_vm_readv, self, &local, 1UL, zr_iov + done, n - done, 0UL);
      done += r > 0 ? (unsigned long)r : 1;
    }
    addr += (unsigned long)got / sizeof(uint64_t) * zr_page;
  }
}

static unsigned long zr_count(int pagemap, unsigned long start, unsigned long end, uint64_t bit) {
  unsigned long count = 0;
  long size = (long)((end - start) / zr_page * sizeof(uint64_t));
  long got;
  do {
    if (zr_sys(SYS_lseek, pagemap, (long)(start / zr_page * sizeof(uint64_t)), SEEK_SET) < 0) return ZR_FAILED;
    got = zr_sys(SYS_read, pagemap, zr_entries, size);
  } while (got < 0 && errno == EINTR);
  if (got != size) return ZR_FAILED;
  for (long i = 0; i < got / (long)sizeof(uint64_t); i++)
    if (zr_entries[i] & bit) count++;
  return count;
}

static struct zr_slot *zr_slot(unsigned long addr, int create) {
  unsigned long key = addr / zr_page + 1;
  unsigned long home = key * 2654435761UL % ZR_SLOTS;
  for (unsigned long i = 0; i < ZR_PROBES; i++) {
    struct zr_slot *slot = &zr_slots[(home + i) % ZR_SLOTS];
    if (slot->key == key) return slot;
    if (slot->key == 0 && !create) return NULL;
    if (slot->key == 0) {
      slot->key = key;
      return slot;
    }
  }
  if (!create) return NULL;
  memset(&zr_slots[home], 0, sizeof(zr_slots[home]));
  zr_slots[home].key = key;
  return &zr_slots[home];
}

static unsigned long zr_trim(int pagemap, unsigned long start, unsigned long end,
                             unsigned long want, int force, unsigned long lo, unsigned long hi) {
  unsigned long done = 0;
  for (unsigned long addr = start, next; addr < end && done < want; addr = next) {
    next = (addr | (ZR_CHUNK - 1)) + 1;
    if (next > end || next < addr) next = end;
    if (next <= lo || addr >= hi) continue;
    if (zr_visits == 0) break;
    zr_visits--;
    zr_cursor = next;
    if (addr < (unsigned long)(zr_slots + ZR_SLOTS) && next > (unsigned long)zr_slots) continue;
    unsigned long present = zr_count(pagemap, addr, next, ZR_PRESENT);
    if (present == ZR_FAILED) continue;
    struct zr_slot *slot = zr_slot(addr, 0);
    if (slot != NULL && slot->gen == zr_gen && slot->pushed > 0) {
      if (present > slot->kept && (present - slot->kept) * 4 > slot->pushed) {
        if (slot->heat < ZR_HEAT_MAX) slot->heat++;
        slot->skip = (uint8_t)(ZR_SKIP << slot->heat > 255 ? 255 : ZR_SKIP << slot->heat);
        slot->pushed = 0;
      } else if (slot->heat > 0) {
        slot->heat--;
      }
    }
    if (slot != NULL && slot->skip > 0 && !force) {
      slot->skip--;
      continue;
    }
    if (present == 0) continue;
    unsigned long from = addr < lo ? lo : addr;
    unsigned long left = present;
    while (from < next && done + present - left < want) {
      unsigned long budget = want - done - (present - left);
      unsigned long i = (from - addr) / zr_page;
      for (; i < (next - addr) / zr_page && budget > 0; i++)
        if (zr_entries[i] & ZR_PRESENT) budget--;
      unsigned long to = addr + i * zr_page;
      zr_sys(SYS_madvise, from, to - from, MADV_PAGEOUT);
      from = to;
      unsigned long now = zr_count(pagemap, addr, next, ZR_PRESENT);
      if (now == ZR_FAILED) break;
      left = now < present ? now : present;
    }
    if (from < next) zr_cursor = from;
    if (left >= present) continue;
    if (slot == NULL) slot = zr_slot(addr, 1);
    if (slot->gen != zr_gen) slot->pushed = 0;
    slot->gen = zr_gen;
    slot->pushed = (uint16_t)(slot->pushed + present - left);
    slot->kept = (uint16_t)left;
    done += present - left;
  }
  return done;
}

static unsigned long zr_walk(int mode, unsigned long want, unsigned long lo, unsigned long hi) {
  int maps = zr_open("/proc/self/maps");
  if (maps < 0) return 0;
  int pagemap = mode == 1 ? -1 : zr_open("/proc/self/pagemap");
  if (mode != 1 && pagemap < 0) {
    zr_sys(SYS_close, maps);
    return 0;
  }
  unsigned long done = 0;
  size_t have = 0;
  while (done < want && zr_visits > 0) {
    long n = zr_sys(SYS_read, maps, zr_maps + have, sizeof(zr_maps) - 1 - have);
    if (n < 0 && errno == EINTR) continue;
    if (n <= 0) break;
    have += (size_t)n;
    zr_maps[have] = 0;
    char *line = zr_maps;
    char *nl;
    while (done < want && zr_visits > 0 && (nl = memchr(line, '\n', have - (size_t)(line - zr_maps))) != NULL) {
      *nl = 0;
      unsigned long s, e;
      if (zr_eligible(line, mode != 0, &s, &e) && e > s) {
        if (mode == 0) zr_read_back(pagemap, s, e);
        else if (mode == 1) zr_sys(SYS_madvise, s, e - s, MADV_PAGEOUT);
        else done += zr_trim(pagemap, s, e, want - done, mode == 3, lo, hi);
      }
      line = nl + 1;
    }
    have -= (size_t)(line - zr_maps);
    memmove(zr_maps, line, have);
    if (have == sizeof(zr_maps) - 1) have = 0;
  }
  if (pagemap >= 0) zr_sys(SYS_close, pagemap);
  zr_sys(SYS_close, maps);
  return done;
}

static void zr_ack(long value, int trim) {
  const char *suffix = trim ? ".trim." : ".ack.";
  char path[sizeof(ZR_CTL) + sizeof(".trim.") + 20];
  char digits[20];
  size_t n = 0, d = 0;
  for (const char *p = ZR_CTL; *p; p++) path[n++] = *p;
  for (const char *p = suffix; *p; p++) path[n++] = *p;
  long pid = zr_sys(SYS_getpid);
  do digits[d++] = (char)('0' + pid % 10); while ((pid /= 10) > 0);
  while (d > 0) path[n++] = digits[--d];
  path[n] = 0;
  int fd = (int)zr_sys(SYS_openat, AT_FDCWD, path, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
  if (fd < 0) return;
  for (d = 0; value >= 0 && (d == 0 || value > 0); value /= 10) digits[d++] = (char)('0' + value % 10);
  for (n = 0; d > 0;) path[n++] = digits[--d];
  if (n > 0) zr_sys(SYS_write, fd, path, n);
  zr_sys(SYS_close, fd);
}

static void zr_request(int worker) {
  unsigned long want = 0;
  int mode = zr_mode(&want);
  if (mode < 0) return;
  if (!worker && want > ZR_INLINE_KB / (zr_page / 1024)) want = ZR_INLINE_KB / (zr_page / 1024);
  zr_visits = worker ? ZR_FAILED : ZR_INLINE_CHUNKS;
  if (mode > 0) zr_ack(-1, mode > 1);
  if (mode < 2) {
    zr_walk(mode, 1, 0, 0);
    zr_gen++;
    if (mode == 1) zr_ack(1, 0);
    return;
  }
  unsigned long from = zr_cursor;
  unsigned long done = zr_walk(mode, want, from, ~0UL);
  if (done < want && from > 0) done += zr_walk(mode, want - done, 0, from);
  zr_ack((long)(done * (zr_page / 1024)), 1);
}

static void *zr_worker_main(void *unused) {
  (void)unused;
  prctl(PR_SET_NAME, "zr-worker", 0, 0, 0);
  for (;;) {
    while (!__atomic_exchange_n(&zr_pending, 0, __ATOMIC_SEQ_CST))
      zr_sys(SYS_futex, &zr_pending, ZR_FUTEX_WAIT, 0, NULL, NULL, 0);
    zr_request(1);
  }
  return NULL;
}

static void zr_handler(int sig, siginfo_t *info, void *context) {
  (void)sig;
  (void)context;
  if (info == NULL || info->si_code != SI_USER) return;
  int saved = errno;
  __atomic_store_n(&zr_pending, 1, __ATOMIC_SEQ_CST);
  if (__atomic_load_n(&zr_worker, __ATOMIC_ACQUIRE)) {
    zr_sys(SYS_futex, &zr_pending, ZR_FUTEX_WAKE, 1, NULL, NULL, 0);
  } else while (__atomic_load_n(&zr_pending, __ATOMIC_SEQ_CST)
                && !__atomic_exchange_n(&zr_busy, 1, __ATOMIC_SEQ_CST)) {
    __atomic_store_n(&zr_pending, 0, __ATOMIC_SEQ_CST);
    zr_request(0);
    __atomic_store_n(&zr_busy, 0, __ATOMIC_SEQ_CST);
  }
  errno = saved;
}

static void zr_child(void) {
  __atomic_store_n(&zr_busy, 0, __ATOMIC_SEQ_CST);
  __atomic_store_n(&zr_pending, 0, __ATOMIC_SEQ_CST);
  __atomic_store_n(&zr_worker, 0, __ATOMIC_RELEASE);
}

__attribute__((constructor)) static void zr_init(void) {
  void *libc = dlopen("libc.so.6", RTLD_LAZY | RTLD_NOLOAD);
  if (libc == NULL) return;
  zr_sys = (long (*)(long, ...))dlsym(libc, "syscall");
  if (zr_sys == NULL) return;
  int fd = zr_open(ZR_CTL);
  if (fd < 0) return;
  zr_sys(SYS_close, fd);
  long page = sysconf(_SC_PAGESIZE);
  zr_page = page > 0 ? (unsigned long)page : 4096UL;
  struct sigaction old;
  if (sigaction(SIGURG, NULL, &old) != 0) return;
  if ((old.sa_flags & SA_SIGINFO) || old.sa_handler != SIG_DFL) return;
  struct sigaction sa;
  memset(&sa, 0, sizeof(sa));
  sa.sa_sigaction = zr_handler;
  sa.sa_flags = SA_SIGINFO | SA_RESTART;
  sigemptyset(&sa.sa_mask);
  if (sigaction(SIGURG, &sa, NULL) == 0) {
    pthread_atfork(NULL, NULL, zr_child);
    pthread_attr_t attr;
    if (pthread_attr_init(&attr) == 0) {
      pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
      pthread_t thread;
      sigset_t blocked;
      sigset_t previous;
      if (sigfillset(&blocked) == 0 &&
          pthread_sigmask(SIG_SETMASK, &blocked, &previous) == 0) {
        if (pthread_create(&thread, &attr, zr_worker_main, NULL) == 0)
          __atomic_store_n(&zr_worker, 1, __ATOMIC_RELEASE);
        pthread_sigmask(SIG_SETMASK, &previous, NULL);
      }
      pthread_attr_destroy(&attr);
    }
  }
}
