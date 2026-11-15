#if defined(__aarch64__)
static void interruption_hook(const long *);
#define FP_SVC_TEST_HOOK interruption_hook
#endif
#include "../fastpath/fastpath.c"
#include "checks.h"
#include <sys/wait.h>
#include <linux/futex.h>
#include <signal.h>
#include <setjmp.h>

#if defined(__aarch64__)
static void (*interrupt_path)(void);
static void interruption_hook(const long *r) {
  (void)r;
  if (interrupt_path) {
    void (*hook)(void) = interrupt_path;
    interrupt_path = NULL;
    hook();
  }
}

struct snapshot { long x[32]; unsigned char q[32][16]; unsigned long nzcv, fpcr, fpsr; };
extern uint32_t svc_check_site;
extern void svc_register_probe(struct snapshot *, const long *);

__asm__(
  ".text\n.p2align 4\n.type svc_register_probe,%function\nsvc_register_probe:\n"
  "sub sp, sp, #1072\n"
  "stp x19, x20, [sp]\nstp x21, x22, [sp, #16]\nstp x23, x24, [sp, #32]\n"
  "stp x25, x26, [sp, #48]\nstp x27, x28, [sp, #64]\nstp x29, x30, [sp, #80]\n"
  "stp q8, q9, [sp, #96]\nstp q10, q11, [sp, #128]\n"
  "stp q12, q13, [sp, #160]\nstp q14, q15, [sp, #192]\n"
  "stp x0, x1, [sp, #224]\n"
  ".irp n,1,2,3,4,5,6,7,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28,29,30\n"
  "mov x\\n, #\\n\n.endr\n"
  ".irp n,0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28,29,30,31\n"
  "movi v\\n\\().16b, #0x5a\n.endr\n"
  "mov x0, #0xa0000000\nmsr nzcv, x0\n"
  "ldr x0, [sp, #232]\nldr x8, [x0, #48]\n"
  "ldp x4, x5, [x0, #32]\nldp x2, x3, [x0, #16]\nldp x0, x1, [x0]\n"
  ".global svc_check_site\nsvc_check_site:\nsvc #0\n"
  ".irp n,0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28,29,30\n"
  "str x\\n, [sp, #(256 + \\n * 8)]\n.endr\n"
  ".irp n,0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28,29,30,31\n"
  "str q\\n, [sp, #(512 + \\n * 16)]\n.endr\n"
  "mrs x0, nzcv\nstr x0, [sp, #1024]\nmrs x0, fpcr\nstr x0, [sp, #1032]\n"
  "mrs x0, fpsr\nstr x0, [sp, #1040]\n"
  "ldr x0, [sp, #224]\nadd x1, sp, #256\nmov x2, #792\nbl memcpy\n"
  "ldp q8, q9, [sp, #96]\nldp q10, q11, [sp, #128]\n"
  "ldp q12, q13, [sp, #160]\nldp q14, q15, [sp, #192]\n"
  "ldp x19, x20, [sp]\nldp x21, x22, [sp, #16]\nldp x23, x24, [sp, #32]\n"
  "ldp x25, x26, [sp, #48]\nldp x27, x28, [sp, #64]\nldp x29, x30, [sp, #80]\n"
  "add sp, sp, #1072\nret\n.size svc_register_probe, .-svc_register_probe\n"
);

static long probe(const long *args) {
  struct snapshot out = {0};
  unsigned long saved_fpcr, saved_fpsr;
  __asm__ volatile("mrs %0, fpcr\nmrs %1, fpsr" : "=r"(saved_fpcr), "=r"(saved_fpsr));
  unsigned long fpcr = saved_fpcr ^ (1UL << 22), fpsr = saved_fpsr | (1UL << 27);
  __asm__ volatile("msr fpcr, %0\nmsr fpsr, %1" : : "r"(fpcr), "r"(fpsr));
  errno = 123;
  svc_register_probe(&out, args);
  assert(errno == 123);
  for (int i = 1; i <= 30; i++) {
    long expected = i <= 5 ? args[i] : i == 8 ? args[6] : i;
    if (out.x[i] != expected) { fprintf(stderr, "x%d: %lx != %lx\n", i, out.x[i], expected); abort(); }
  }
  for (int i = 0; i < 32; i++)
    for (int j = 0; j < 16; j++) assert(out.q[i][j] == 0x5a);
  assert(out.nzcv == 0xa0000000UL);
  assert(out.fpcr == fpcr && out.fpsr == fpsr);
  __asm__ volatile("msr fpcr, %0\nmsr fpsr, %1" : : "r"(saved_fpcr), "r"(saved_fpsr));
  return out.x[0];
}

static void check(const long *args, long want) {
  long got = probe(args);
  if (got != want) { fprintf(stderr, "syscall %ld: got %ld, expected %ld\n", args[6], got, want); abort(); }
}

static long raw(long nr, long a, long b, long c, long d, long e, long f) {
  long args[] = {a, b, c, d, e, f, nr};
  return probe(args);
}

static void fault(long nr, long a, long b, long c, long d, long e, long f) {
  long args[] = {a, b, c, d, e, f, 0, 0, nr};
  long want = svc_original(args);
  long call[] = {a, b, c, d, e, f, nr};
  check(call, want);
}

static void readlink_boundaries(const char *path) {
  char expected[PATH_MAX + 8], actual[PATH_MAX + 8];
  long args[] = {AT_FDCWD, (long)path, (long)expected, PATH_MAX, 0, 0, 0, 0, SYS_readlinkat};
  long length = svc_original(args);
  assert(length > 0 && length < PATH_MAX - 2);
  for (long size = length - 1; size <= length + 2; size++) {
    memset(expected, 0x5a, sizeof expected); memset(actual, 0x5a, sizeof actual);
    args[3] = size;
    long want = svc_original(args);
    long got = raw(SYS_readlinkat, AT_FDCWD, (long)path, (long)actual, size, 0, 0);
    if (got != want || (want > 0 && memcmp(expected, actual, (size_t)want)))
      fprintf(stderr, "readlink %s size %ld: relay %ld, proot %ld\n", path, size, got, want);
    assert(got == want && (want <= 0 || !memcmp(expected, actual, (size_t)want)));
    for (size_t i = (size_t)size; i < sizeof actual; i++) assert(actual[i] == 0x5a);
  }
}

static void path_checks(void) {
  char work[] = "/tmp/proot-expanded-check-XXXXXX";
  assert(mkdtemp(work));
  int cwd = open(".", O_RDONLY | O_DIRECTORY);
  assert(cwd >= 0 && chdir(work) == 0);
  assert(raw(SYS_mkdirat, AT_FDCWD, (long)"dir", 0700, 0, 0, 0) == 0);
  long fd = raw(SYS_openat, AT_FDCWD, (long)"dir/file", O_CREAT | O_RDWR | O_EXCL, 0600, 0, 0);
  assert(fd >= 0 && write((int)fd, "hello", 5) == 5);
  struct stat st;
  struct statx sx;
  assert(raw(SYS_newfstatat, AT_FDCWD, (long)"dir/file", (long)&st, 0, 0, 0) == 0 && st.st_size == 5);
  assert(raw(SYS_statx, AT_FDCWD, (long)"dir/file", 0, STATX_BASIC_STATS, (long)&sx, 0) == 0 && sx.stx_size == 5);
  assert(raw(SYS_faccessat, AT_FDCWD, (long)"dir/file", R_OK, 0, 0, 0) == 0);
  assert(raw(SYS_symlinkat, (long)"file", AT_FDCWD, (long)"dir/link", 0, 0, 0) == 0);
  readlink_boundaries("dir/link");
  readlink_boundaries("/proc/self/exe");
  char text[PATH_MAX];
  memset(text, '#', sizeof text);
  assert(raw(SYS_readlinkat, AT_FDCWD, (long)"dir/link", (long)text, sizeof text, 0, 0) == 4);
  assert(!memcmp(text, "file#", 5));
  assert(raw(SYS_fchmodat, AT_FDCWD, (long)"dir/file", 0640, 0, 0, 0) == 0);
  struct timespec times[] = {{1000, 0}, {2000, 0}};
  assert(raw(SYS_truncate, (long)"dir/file", 11, 0, 0, 0, 0) == 0);
  assert(raw(SYS_utimensat, AT_FDCWD, (long)"dir/file", (long)times, 0, 0, 0) == 0);
  assert(raw(SYS_newfstatat, AT_FDCWD, (long)"dir/file", (long)&st, 0, 0, 0) == 0);
  assert(st.st_size == 11 && (st.st_mode & 0777) == 0640 && st.st_mtime == 2000);
  assert(raw(SYS_renameat, AT_FDCWD, (long)"dir/file", AT_FDCWD, (long)"dir/moved", 0, 0) == 0);
  assert(raw(SYS_renameat2, AT_FDCWD, (long)"dir/moved", AT_FDCWD, (long)"dir/file", RENAME_NOREPLACE, 0) == 0);
  struct statfs fs;
  assert(raw(SYS_statfs, (long)"dir/file", (long)&fs, 0, 0, 0, 0) == 0);
  long x = raw(SYS_getxattr, (long)"dir/file", (long)"user.proot-missing", (long)text, sizeof text, 0, 0);
  assert(x == -ENODATA || x == -EOPNOTSUPP);
  x = raw(SYS_lgetxattr, (long)"dir/link", (long)"user.proot-missing", (long)text, sizeof text, 0, 0);
  assert(x == -ENODATA || x == -EOPNOTSUPP);
  assert(raw(SYS_listxattr, (long)"dir/file", (long)text, sizeof text, 0, 0, 0) >= 0);
  assert(raw(SYS_llistxattr, (long)"dir/link", (long)text, sizeof text, 0, 0, 0) >= 0);
  int in = inotify_init1(IN_CLOEXEC);
  assert(in >= 0 && raw(SYS_inotify_add_watch, in, (long)"dir", IN_CREATE, 0, 0, 0) >= 0);
  close(in);
  long n = raw(SYS_getcwd, (long)text, sizeof text, 0, 0, 0, 0);
  assert(n == (long)strlen(work) + 1 && !strcmp(text, work));
  size_t page = sysconf(_SC_PAGESIZE);
  char *guard = mmap(NULL, page * 2, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
  assert(guard != MAP_FAILED && mprotect(guard + page, page, PROT_NONE) == 0);
  strcpy(guard + page - 2, ".");
  assert(raw(SYS_newfstatat, AT_FDCWD, (long)(guard + page - 2), (long)&st, 0, 0, 0) == 0);
  long bad = (long)(guard + page);
  fault(SYS_openat, AT_FDCWD, bad, O_RDONLY, 0, 0, 0);
  fault(SYS_newfstatat, AT_FDCWD, (long)"dir/file", bad, 0, 0, 0);
  fault(SYS_statx, AT_FDCWD, (long)"dir/file", 0, STATX_BASIC_STATS, bad, 0);
  fault(SYS_readlinkat, AT_FDCWD, (long)"dir/link", bad, 16, 0, 0);
  fault(SYS_getcwd, bad, PATH_MAX, 0, 0, 0, 0);
  fault(SYS_utimensat, AT_FDCWD, (long)"missing", bad, 0, 0, 0);
  fault(SYS_inotify_add_watch, -1, (long)"missing", IN_CREATE, 0, 0, 0);
  fault(SYS_renameat2, AT_FDCWD, (long)"dir/file", AT_FDCWD, (long)"dir/nope", 0x80000000, 0);
  assert(munmap(guard, page * 2) == 0);
  close((int)fd);
  assert(raw(SYS_unlinkat, AT_FDCWD, (long)"dir/link", 0, 0, 0, 0) == 0);
  assert(raw(SYS_unlinkat, AT_FDCWD, (long)"dir/file", 0, 0, 0, 0) == 0);
  assert(raw(SYS_unlinkat, AT_FDCWD, (long)"dir", AT_REMOVEDIR, 0, 0, 0) == 0);
  assert(fchdir(cwd) == 0 && close(cwd) == 0 && rmdir(work) == 0);
  puts("PASS: path families, dirfds, flags, symlinks, protected pointers, page boundary");
}

static void robust_checks(void) {
  if (!svc_android_api) return;
  long original = 0;
  size_t size = 0;
  assert(raw(SYS_get_robust_list, 0, (long)&original, (long)&size, 0, 0, 0) == 0);
  struct robust_list_head head = {0};
  long set_result = raw(SYS_set_robust_list, (long)&head, sizeof head, 0, 0, 0, 0);
  assert(set_result == 0 || set_result == -ENOSYS);
  long raw_head = 0;
  long direct[] = {0, (long)&raw_head, (long)&size, 0, 0, 0, 0, 0, SYS_get_robust_list};
  assert(svc_original(direct) == 0 && raw_head == (long)&head);
  long got = 0;
  assert(raw(SYS_get_robust_list, gettid(), (long)&got, (long)&size, 0, 0, 0) == 0);
  assert(got == (long)&head && size == sizeof head);
  assert(raw(SYS_get_robust_list, 0, 1, (long)&size, 0, 0, 0) == -EFAULT);
  assert(raw(SYS_set_robust_list, 0, sizeof head, 0, 0, 0, 0) == set_result);
  long cleared = raw(SYS_get_robust_list, 0, (long)&got, (long)&size, 0, 0, 0);
  assert(cleared == -ENOSYS || (cleared == 0 && got == 0));
  direct[0] = original; direct[1] = sizeof head; direct[8] = SYS_set_robust_list;
  assert(svc_original(direct) == set_result);
  assert(raw(SYS_get_robust_list, 0, (long)&got, (long)&size, 0, 0, 0) == 0 && original == got);
  assert(raw(SYS_faccessat2, AT_FDCWD, (long)"/usr/lib/libc.so.6", R_OK, 0, 0, 0) == -ENOSYS);
  if (svc_android_api < 35) assert(raw(SYS_clone3, 0, 0, 0, 0, 0, 0) == -ENOSYS);
  int api = svc_android_api;
  svc_android_api = 35;
  assert(!svc_supported(SYS_clone3));
  svc_android_api = 0;
  assert(!svc_supported(SYS_clone3) && !svc_supported(SYS_set_robust_list));
  svc_android_api = api;
  assert(!svc_supported(SYS_set_robust_list) && !svc_supported(SYS_get_robust_list));
  puts("PASS: mixed relay/raw robust-list callers, errors, faccessat2, clone3 platform guard");
}

static void *thread_check(void *unused) {
  (void)unused;
  struct stat st;
  if (svc_android_api) {
    long head;
    size_t size;
    assert(raw(SYS_get_robust_list, 0, (long)&head, (long)&size, 0, 0, 0) == 0 && head && size == 3 * sizeof(long));
    struct robust_list_head private = {0};
    long result = raw(SYS_set_robust_list, (long)&private, sizeof private, 0, 0, 0, 0);
    assert(result == 0 || result == -ENOSYS);
    for (int i = 0; i < 10; i++) {
      long got;
      sched_yield();
      assert(raw(SYS_get_robust_list, 0, (long)&got, (long)&size, 0, 0, 0) == 0 && got == (long)&private);
    }
    assert(raw(SYS_set_robust_list, head, sizeof private, 0, 0, 0, 0) == result);
  }
  const long args[] = {AT_FDCWD, (long)"/usr/lib/libc.so.6", (long)&st, 0, 0, 0, SYS_newfstatat};
  for (int i = 0; i < 100; i++) check(args, 0);
  return NULL;
}

static pthread_barrier_t barrier;
static void *thread_probe(void *unused) {
  pthread_barrier_wait(&barrier);
  thread_check(unused);
  pthread_barrier_wait(&barrier);
  return NULL;
}

static volatile sig_atomic_t signals_seen;
static void on_signal(int signal) {
  (void)signal;
  int saved = errno;
  struct stat st;
  assert(raw(SYS_newfstatat, AT_FDCWD, (long)"/usr/lib/libc.so.6", (long)&st, 0, 0, 0) == 0);
  signals_seen++;
  errno = saved;
}

static sigjmp_buf interrupted;
static void jump_signal(int signal) { (void)signal; siglongjmp(interrupted, 1); }
static void jump_path(void) { assert(raise(SIGUSR2) == 0); abort(); }
static void exit_path(void) { pthread_exit(PTHREAD_CANCELED); }
static void mark_cleanup(void *arg) { *(int *)arg = 1; }
struct unwind_check { int cleaned; int (*stat)(int, const char *, struct stat *, int); };
static void *unwind_worker(void *arg) {
  struct unwind_check *test = arg;
  struct stat st;
  pthread_cleanup_push(mark_cleanup, &test->cleaned);
  interrupt_path = exit_path;
  test->stat(AT_FDCWD, "/usr/lib/libc.so.6", &st, 0);
  pthread_cleanup_pop(1);
  abort();
}

static void interruption_checks(void) {
  void *libc = dlopen("libc.so.6", RTLD_NOW);
  struct unwind_check test = {.stat = dlsym(libc, "fstatat")};
  assert(test.stat);
  struct sigaction old, action = {.sa_handler = jump_signal};
  sigemptyset(&action.sa_mask);
  assert(sigaction(SIGUSR2, &action, &old) == 0);
  sigset_t deliver, old_mask;
  sigemptyset(&deliver); sigaddset(&deliver, SIGUSR2);
  assert(pthread_sigmask(SIG_UNBLOCK, &deliver, &old_mask) == 0);
  if (!sigsetjmp(interrupted, 1)) {
    struct stat st;
    interrupt_path = jump_path;
    test.stat(AT_FDCWD, "/usr/lib/libc.so.6", &st, 0);
    abort();
  }
  assert(svc_busy);
  struct stat st;
  unsigned long before = svc_fast;
  assert(test.stat(AT_FDCWD, "/usr/lib/libc.so.6", &st, 0) == 0);
  assert(!svc_busy && svc_fast > before);
  assert(sigaction(SIGUSR2, &old, NULL) == 0 && pthread_sigmask(SIG_SETMASK, &old_mask, NULL) == 0);
  pthread_t thread;
  void *result;
  assert(pthread_create(&thread, NULL, unwind_worker, &test) == 0);
  assert(pthread_join(thread, &result) == 0 && result == PTHREAD_CANCELED && test.cleaned);
  assert(dlclose(libc) == 0);
  puts("PASS: signal siglongjmp recovery, forced unwind through patched libc with caller cleanup");
}

static void *small_stack_worker(void *unused) {
  size_t consume = (size_t)(uintptr_t)unused - 48 * 1024;
  volatile char *padding = __builtin_alloca(consume);
  padding[0] = padding[consume - 1] = 1;
  struct stat st;
  unsigned long before = svc_fast;
  for (int i = 0; i < 20; i++)
    assert(raw(SYS_newfstatat, AT_FDCWD, (long)"/usr/lib/libc.so.6", (long)&st, 0, 0, 0) == 0);
  assert(svc_fast == before && !svc_busy);
  return NULL;
}

static void small_stack_checks(void) {
  pthread_attr_t attr;
  pthread_t thread;
  size_t size = PTHREAD_STACK_MIN;
  if (size < 128 * 1024) size = 128 * 1024;
  assert(pthread_attr_init(&attr) == 0 && pthread_attr_setstacksize(&attr, size) == 0);
  assert(pthread_create(&thread, &attr, small_stack_worker, (void *)(uintptr_t)size) == 0);
  assert(pthread_join(thread, NULL) == 0 && pthread_attr_destroy(&attr) == 0);
  puts("PASS: limited remaining thread stack delegates path calls to PRoot");
}

struct signal_sender { pthread_t target; int done; };
static void *send_signals(void *arg) {
  struct signal_sender *sender = arg;
  const struct timespec delay = {0, 100000};
  for (int i = 0; i < 100; i++) {
    assert(pthread_kill(sender->target, SIGUSR1) == 0);
    nanosleep(&delay, NULL);
  }
  __atomic_store_n(&sender->done, 1, __ATOMIC_RELEASE);
  return NULL;
}

static void *cancel_worker(void *unused) {
  (void)unused;
  struct stat st;
  for (;;) {
    assert(raw(SYS_newfstatat, AT_FDCWD, (long)"/usr/lib/libc.so.6", (long)&st, 0, 0, 0) == 0);
    pthread_testcancel();
  }
}

struct cancel_open { const char *path; int (*open)(const char *, int, ...); int ready, cleaned; };
static void cancel_cleanup(void *arg) { ((struct cancel_open *)arg)->cleaned = 1; }
static void *cancel_fifo(void *arg) {
  struct cancel_open *test = arg;
  pthread_cleanup_push(cancel_cleanup, test);
  __atomic_store_n(&test->ready, 1, __ATOMIC_RELEASE);
  int fd = test->open(test->path, O_RDONLY);
  if (fd >= 0) close(fd);
  pthread_cleanup_pop(1);
  return NULL;
}

static void signal_checks(void) {
  stack_t old_stack, stack = {.ss_sp = malloc(SIGSTKSZ), .ss_size = SIGSTKSZ};
  assert(stack.ss_sp && sigaltstack(&stack, &old_stack) == 0);
  struct sigaction old, action = {.sa_handler = on_signal, .sa_flags = SA_ONSTACK};
  sigemptyset(&action.sa_mask);
  assert(sigaction(SIGUSR1, &action, &old) == 0);
  sigset_t deliver, old_mask;
  sigemptyset(&deliver);
  sigaddset(&deliver, SIGUSR1);
  assert(pthread_sigmask(SIG_UNBLOCK, &deliver, &old_mask) == 0);
  struct signal_sender sender = {.target = pthread_self()};
  pthread_t thread;
  assert(pthread_create(&thread, NULL, send_signals, &sender) == 0);
  struct stat st;
  for (int i = 0; i < 2000 || !__atomic_load_n(&sender.done, __ATOMIC_ACQUIRE); i++)
    assert(raw(SYS_newfstatat, AT_FDCWD, (long)"/usr/lib/libc.so.6", (long)&st, 0, 0, 0) == 0);
  assert(pthread_join(thread, NULL) == 0 && signals_seen > 0);
  assert(sigaction(SIGUSR1, &old, NULL) == 0 && sigaltstack(&old_stack, NULL) == 0);
  assert(pthread_sigmask(SIG_SETMASK, &old_mask, NULL) == 0);
  free(stack.ss_sp);
  void *result;
  assert(pthread_create(&thread, NULL, cancel_worker, NULL) == 0);
  assert(pthread_cancel(thread) == 0 && pthread_join(thread, &result) == 0 && result == PTHREAD_CANCELED);
  char fifo[] = "/tmp/proot-expanded-fifo-XXXXXX";
  int fd = mkstemp(fifo);
  assert(fd >= 0 && close(fd) == 0 && unlink(fifo) == 0 && mkfifo(fifo, 0600) == 0);
  void *libc = dlopen("libc.so.6", RTLD_NOW);
  struct cancel_open test = {.path = fifo, .open = dlsym(libc, "open")};
  assert(test.open && pthread_create(&thread, NULL, cancel_fifo, &test) == 0);
  while (!__atomic_load_n(&test.ready, __ATOMIC_ACQUIRE)) sched_yield();
  struct timespec deadline = {0, 20000000};
  nanosleep(&deadline, NULL);
  assert(pthread_cancel(thread) == 0 && clock_gettime(CLOCK_REALTIME, &deadline) == 0);
  deadline.tv_sec += 2;
  assert(pthread_timedjoin_np(thread, &result, &deadline) == 0 && result == PTHREAD_CANCELED && test.cleaned);
  assert(unlink(fifo) == 0 && dlclose(libc) == 0);
  for (int i = 0; i < 32; i++) {
    assert(pthread_create(&thread, NULL, thread_check, NULL) == 0);
    assert(pthread_join(thread, NULL) == 0);
  }
  puts("PASS: signal reentry on alternate stack, deferred/FIFO cancellation with cleanup, thread churn");
}

int main(void) {
  assert(fp_ready());
  const char *mode = getenv("PROOT_FP_PATCH_SVC");
  if (!svc_sites && mode && !strcmp(mode, "0")) {
    assert(setenv("PROOT_FP_PATCH_SVC", "1", 1) == 0);
    svc_patch_init();
  }
  printf("platform: android %d, patched %u sites\n", svc_android_api, svc_sites);
  assert(svc_sites > 0);
  assert(svc_single_thread());
  size_t page = sysconf(_SC_PAGESIZE);
  uint32_t *relay = svc_relay_page((uintptr_t)&svc_check_site, page), branch;
  assert(relay && svc_emit(relay, &svc_check_site, &branch));
  __builtin___clear_cache((char *)relay, (char *)relay + 40);
  assert(mprotect(relay, page, PROT_READ | PROT_EXEC) == 0);
  int mem = open("/proc/self/mem", O_RDWR | O_CLOEXEC);
  assert(mem >= 0 && pwrite(mem, &branch, 4, (off_t)(uintptr_t)&svc_check_site) == 4);
  close(mem);
  __builtin___clear_cache((char *)&svc_check_site, (char *)(&svc_check_site + 1));
  long args[] = {0, 1, 2, 3, 4, 5, SYS_getpid};
  check(args, getpid());
  struct stat st;
  args[0] = AT_FDCWD; args[1] = (long)"/usr/lib/libc.so.6"; args[2] = (long)&st;
  args[3] = 0; args[6] = SYS_newfstatat;
  check(args, 0);
  assert(st.st_size > 0);
  args[1] = (long)"/usr/lib/proot-svc-check-does-not-exist";
  check(args, -ENOENT);
  args[1] = 0;
  check(args, -EFAULT);
  args[1] = (long)"/usr/lib/libc.so.6"; args[2] = 0;
  check(args, -EFAULT);
  args[1] = (long)"/proc/self/exe"; args[2] = (long)&st;
  check(args, 0);
  args[3] = 0x40000000;
  check(args, -EINVAL);
  path_checks();
  robust_checks();
  pthread_t threads[4];
  assert(pthread_barrier_init(&barrier, NULL, 5) == 0);
  for (int i = 0; i < 4; i++) assert(pthread_create(&threads[i], NULL, thread_probe, NULL) == 0);
  pthread_barrier_wait(&barrier);
  assert(!svc_single_thread());
  pthread_barrier_wait(&barrier);
  for (int i = 0; i < 4; i++) assert(pthread_join(threads[i], NULL) == 0);
  assert(pthread_barrier_destroy(&barrier) == 0);
  pid_t pid = fork();
  assert(pid >= 0);
  if (!pid) { thread_check(NULL); _exit(0); }
  int status;
  assert(waitpid(pid, &status, 0) == pid && WIFEXITED(status) && WEXITSTATUS(status) == 0);
  signal_checks();
  interruption_checks();
  small_stack_checks();
  puts("PASS: raw results, errno, x1-x30, q0-q31, NZCV, FPCR/FPSR, 4 threads, fork");
  return 0;
}
#else
int main(void) { return 77; }
#endif
