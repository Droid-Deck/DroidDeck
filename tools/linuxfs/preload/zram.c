#define _GNU_SOURCE
#pragma GCC optimize("no-stack-protector", "no-tree-loop-distribute-patterns")
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <sched.h>
#include <signal.h>
#include <stdint.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#if defined(__i386__)
#include <asm/ldt.h>
#endif
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
#define ZR_STACK (128UL << 10)
#define ZR_THREAD (CLONE_VM | CLONE_FS | CLONE_FILES | CLONE_SIGHAND | CLONE_THREAD | CLONE_SYSVSEM | \
                   CLONE_SETTLS | CLONE_PARENT_SETTID | CLONE_CHILD_CLEARTID)
#define ZR_TEXT(x) #x
#define ZR_NR(x) ZR_TEXT(x)
#define ZR_CALL(n, a, b, c, d, e, f, ...) \
  zr_call((long)(n), (long)(a), (long)(b), (long)(c), (long)(d), (long)(e), (long)(f))
#define zr_sys(...) ZR_CALL(__VA_ARGS__, 0, 0, 0, 0, 0, 0, 0)

#if defined(__aarch64__)
#define ZR_MMAP SYS_mmap
__asm__(".pushsection .text\n"
        ".p2align 2\n"
        ".hidden zr_call\n"
        ".type zr_call, %function\n"
        "zr_call:\n"
        "  mov x8, x0\n"
        "  mov x0, x1\n"
        "  mov x1, x2\n"
        "  mov x2, x3\n"
        "  mov x3, x4\n"
        "  mov x4, x5\n"
        "  mov x5, x6\n"
        "  svc #0\n"
        "  ret\n"
        ".size zr_call, . - zr_call\n"
        ".hidden zr_clone\n"
        ".type zr_clone, %function\n"
        "zr_clone:\n"
        "  mov x5, x2\n"
        "  mov x2, x3\n"
        "  mov x3, x4\n"
        "  mov x4, x5\n"
        "  mov x8, #" ZR_NR(SYS_clone) "\n"
        "  svc #0\n"
        "  cbnz x0, 1f\n"
        "  mov x29, xzr\n"
        "  mov x30, xzr\n"
        "  bl zr_thread\n"
        "  mov x0, xzr\n"
        "  mov x8, #" ZR_NR(SYS_exit) "\n"
        "  svc #0\n"
        "  brk #0\n"
        "1:\n"
        "  ret\n"
        ".size zr_clone, . - zr_clone\n"
        ".popsection\n");
#elif defined(__x86_64__)
#define ZR_MMAP SYS_mmap
__asm__(".pushsection .text\n"
        ".p2align 4\n"
        ".hidden zr_call\n"
        ".type zr_call, @function\n"
        "zr_call:\n"
        "  mov %rdi, %rax\n"
        "  mov %rsi, %rdi\n"
        "  mov %rdx, %rsi\n"
        "  mov %rcx, %rdx\n"
        "  mov %r8, %r10\n"
        "  mov %r9, %r8\n"
        "  mov 8(%rsp), %r9\n"
        "  syscall\n"
        "  ret\n"
        ".size zr_call, . - zr_call\n"
        ".hidden zr_clone\n"
        ".type zr_clone, @function\n"
        "zr_clone:\n"
        "  mov %rdx, %r10\n"
        "  mov %rcx, %rdx\n"
        "  mov $" ZR_NR(SYS_clone) ", %eax\n"
        "  syscall\n"
        "  test %rax, %rax\n"
        "  jnz 1f\n"
        "  xor %ebp, %ebp\n"
        "  call zr_thread\n"
        "  xor %edi, %edi\n"
        "  mov $" ZR_NR(SYS_exit) ", %eax\n"
        "  syscall\n"
        "  hlt\n"
        "1:\n"
        "  ret\n"
        ".size zr_clone, . - zr_clone\n"
        ".popsection\n");
#elif defined(__i386__)
#define ZR_MMAP SYS_mmap2
__asm__(".pushsection .text\n"
        ".p2align 4\n"
        ".hidden zr_call\n"
        ".type zr_call, @function\n"
        "zr_call:\n"
        "  push %ebp\n"
        "  push %edi\n"
        "  push %esi\n"
        "  push %ebx\n"
        "  mov 20(%esp), %eax\n"
        "  mov 24(%esp), %ebx\n"
        "  mov 28(%esp), %ecx\n"
        "  mov 32(%esp), %edx\n"
        "  mov 36(%esp), %esi\n"
        "  mov 40(%esp), %edi\n"
        "  mov 44(%esp), %ebp\n"
        "  int $0x80\n"
        "  pop %ebx\n"
        "  pop %esi\n"
        "  pop %edi\n"
        "  pop %ebp\n"
        "  ret\n"
        ".size zr_call, . - zr_call\n"
        ".hidden zr_clone\n"
        ".type zr_clone, @function\n"
        "zr_clone:\n"
        "  push %ebx\n"
        "  push %esi\n"
        "  push %edi\n"
        "  mov 16(%esp), %ebx\n"
        "  mov 20(%esp), %ecx\n"
        "  mov 24(%esp), %edi\n"
        "  mov 28(%esp), %edx\n"
        "  mov 32(%esp), %esi\n"
        "  mov $" ZR_NR(SYS_clone) ", %eax\n"
        "  int $0x80\n"
        "  test %eax, %eax\n"
        "  jnz 1f\n"
        "  xor %ebp, %ebp\n"
        "  call zr_thread\n"
        "  xor %ebx, %ebx\n"
        "  mov $" ZR_NR(SYS_exit) ", %eax\n"
        "  int $0x80\n"
        "  hlt\n"
        "1:\n"
        "  pop %edi\n"
        "  pop %esi\n"
        "  pop %ebx\n"
        "  ret\n"
        ".size zr_clone, . - zr_clone\n"
        ".popsection\n");
#else
#error "zram.c needs a raw system call for this architecture"
#endif

long zr_call(long n, long a, long b, long c, long d, long e, long f) __attribute__((visibility("hidden")));
long zr_clone(long flags, char *stack, int *child, int *parent, void *tls) __attribute__((visibility("hidden")));
void zr_thread(void) __attribute__((visibility("hidden")));

static unsigned long zr_page;
static unsigned zr_requests;
static int zr_stack_busy;
static int zr_tid;
static long zr_filters;
static char *zr_stack;
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

static int zr_prefix(const char *text, const char *prefix) {
  while (*prefix)
    if (*text++ != *prefix++) return 0;
  return 1;
}

static char *zr_find(char *text, char c, size_t n) {
  for (; n > 0; n--, text++)
    if (*text == c) return text;
  return NULL;
}

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
  return *p == 0 || zr_prefix(p, "[heap]") || zr_prefix(p, "[anon:");
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
  } while (got == -EINTR);
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
  zr_slots[home] = (struct zr_slot){.key = key};
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
    if (n == -EINTR) continue;
    if (n <= 0) break;
    have += (size_t)n;
    zr_maps[have] = 0;
    char *line = zr_maps;
    char *nl;
    while (done < want && zr_visits > 0 && (nl = zr_find(line, '\n', have - (size_t)(line - zr_maps))) != NULL) {
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
    for (size_t i = 0; i < have; i++) zr_maps[i] = line[i];
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

static void zr_drain(int worker) {
  unsigned seen;
  do {
    seen = __atomic_load_n(&zr_requests, __ATOMIC_ACQUIRE);
    zr_request(worker);
  } while (!__atomic_compare_exchange_n(&zr_requests, &seen, 0, 0, __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE));
}

void zr_thread(void) {
  zr_sys(SYS_prctl, PR_SET_NAME, "zr-worker");
  zr_drain(1);
}

static void *zr_tls(void *desc) {
#if defined(__aarch64__)
  (void)desc;
  return __builtin_thread_pointer();
#elif defined(__x86_64__)
  (void)desc;
  void *self;
  __asm__("mov %%fs:0, %0" : "=r"(self));
  return self;
#else
  struct user_desc *entry = desc;
  unsigned short selector;
  __asm__("mov %%gs, %0" : "=r"(selector));
  entry->entry_number = selector >> 3;
  return zr_sys(SYS_get_thread_area, entry) == 0 ? entry : NULL;
#endif
}

static long zr_field(const char *text, const char *key) {
  for (const char *line = text; *line; line++) {
    if ((line == text || line[-1] == '\n') && zr_prefix(line, key)) {
      long value = 0;
      for (const char *k = key; *k; k++) line++;
      while (*line == ' ' || *line == '\t') line++;
      for (; *line >= '0' && *line <= '9'; line++) value = value * 10 + (*line - '0');
      return value;
    }
  }
  return -1;
}

static int zr_status(long *threads, long *filters) {
  int fd = zr_open("/proc/self/status");
  if (fd < 0) return 0;
  long have = 0, n;
  while (have < (long)sizeof(zr_maps) - 1 &&
         ((n = zr_sys(SYS_read, fd, zr_maps + have, sizeof(zr_maps) - 1 - (size_t)have)) > 0 || n == -EINTR))
    if (n > 0) have += n;
  zr_sys(SYS_close, fd);
  zr_maps[have] = 0;
  *threads = zr_field(zr_maps, "Threads:");
  *filters = zr_field(zr_maps, "Seccomp_filters:");
  return have > 0;
}

static int zr_start(void) {
  long threads, filters;
  if (!zr_status(&threads, &filters) || threads < 2 || filters > zr_filters) return 0;
  if (zr_stack == NULL) {
    long base = zr_sys(ZR_MMAP, 0, ZR_STACK, PROT_READ | PROT_WRITE,
                       MAP_PRIVATE | MAP_ANONYMOUS | MAP_STACK | MAP_NORESERVE, -1, 0);
    if ((unsigned long)base > -4096UL) return 0;
    zr_stack = (char *)base;
  }
  struct { long sec, nsec; } wait = {0, 5000000};
  for (int i = 0; i < 20 && __atomic_load_n(&zr_stack_busy, __ATOMIC_ACQUIRE); i++)
    zr_sys(SYS_futex, &zr_stack_busy, ZR_FUTEX_WAIT, 1, &wait, NULL, 0);
  if (__atomic_load_n(&zr_stack_busy, __ATOMIC_ACQUIRE)) return 0;
  uint64_t desc[4] = {0};
  void *tls = zr_tls(desc);
  if (tls == NULL) return 0;
  __atomic_store_n(&zr_stack_busy, 1, __ATOMIC_RELEASE);
  uint64_t all = ~0ULL, previous;
  zr_sys(SYS_rt_sigprocmask, SIG_SETMASK, &all, &previous, sizeof(all));
  long tid = zr_clone(ZR_THREAD, zr_stack + ZR_STACK, &zr_stack_busy, &zr_tid, tls);
  zr_sys(SYS_rt_sigprocmask, SIG_SETMASK, &previous, NULL, sizeof(previous));
  if (tid > 0) return 1;
  __atomic_store_n(&zr_stack_busy, 0, __ATOMIC_RELEASE);
  return 0;
}

static void zr_handler(int sig, siginfo_t *info, void *context) {
  (void)sig;
  (void)context;
  if (info == NULL || info->si_code != SI_USER) return;
  int saved = errno;
  if (__atomic_fetch_add(&zr_requests, 1, __ATOMIC_ACQ_REL) == 0 && !zr_start()) zr_drain(0);
  errno = saved;
}

static void zr_child(void) {
  __atomic_store_n(&zr_requests, 0, __ATOMIC_RELEASE);
  __atomic_store_n(&zr_stack_busy, 0, __ATOMIC_RELEASE);
}

__attribute__((constructor)) static void zr_init(void) {
  int fd = zr_open(ZR_CTL);
  if (fd < 0) return;
  zr_sys(SYS_close, fd);
  long page = sysconf(_SC_PAGESIZE);
  zr_page = page > 0 ? (unsigned long)page : 4096UL;
  long threads;
  if (!zr_status(&threads, &zr_filters)) zr_filters = -1;
  struct sigaction old;
  if (sigaction(SIGURG, NULL, &old) != 0) return;
  if ((old.sa_flags & SA_SIGINFO) || old.sa_handler != SIG_DFL) return;
  struct sigaction sa;
  memset(&sa, 0, sizeof(sa));
  sa.sa_sigaction = zr_handler;
  sa.sa_flags = SA_SIGINFO | SA_RESTART;
  sigemptyset(&sa.sa_mask);
  if (sigaction(SIGURG, &sa, NULL) == 0) pthread_atfork(NULL, NULL, zr_child);
}
