#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/userfaultfd.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#ifndef UFFD_USER_MODE_ONLY
#define UFFD_USER_MODE_ONLY 1
#endif

static unsigned char *arena, *store;
static size_t page, size, *offsets;
static int *lengths, uffd;
static atomic_int finished;
static unsigned faults;
static int (*decode)(const char *, char *, int, int);

static void fail(const char *name) {
  perror(name);
  exit(1);
}

static double milliseconds(void) {
  struct timespec t;
  clock_gettime(CLOCK_MONOTONIC, &t);
  return t.tv_sec * 1000.0 + t.tv_nsec / 1000000.0;
}

static long resident_kb(void) {
  FILE *f = fopen("/proc/self/status", "r");
  if (!f) fail("status");
  char line[256];
  long value = -1;
  while (fgets(line, sizeof(line), f)) {
    if (sscanf(line, "VmRSS: %ld", &value) == 1) break;
  }
  fclose(f);
  return value;
}

static int open_pager(void) {
  int fd = (int)syscall(SYS_userfaultfd, O_CLOEXEC | O_NONBLOCK | UFFD_USER_MODE_ONLY);
  if (fd < 0) fail("userfaultfd");
  struct uffdio_api api = {.api = UFFD_API};
  if (ioctl(fd, UFFDIO_API, &api)) fail("UFFDIO_API");
  return fd;
}

static void register_range(int fd, void *address, size_t bytes) {
  struct uffdio_register range = {
    .range = {.start = (uintptr_t)address, .len = bytes},
    .mode = UFFDIO_REGISTER_MODE_MISSING,
  };
  if (ioctl(fd, UFFDIO_REGISTER, &range)) fail("UFFDIO_REGISTER");
}

static void *serve(void *unused) {
  (void)unused;
  unsigned char *scratch = mmap(NULL, page, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
  if (scratch == MAP_FAILED) fail("scratch");
  struct pollfd p = {.fd = uffd, .events = POLLIN};
  while (!atomic_load(&finished)) {
    int ready = poll(&p, 1, 100);
    if (ready < 0 && errno == EINTR) continue;
    if (ready < 0 || (p.revents & (POLLERR | POLLHUP | POLLNVAL))) fail("pager poll");
    if (!ready) continue;
    struct uffd_msg msg;
    ssize_t n = read(uffd, &msg, sizeof(msg));
    if (n < 0 && errno == EAGAIN) continue;
    if (n != sizeof(msg) || msg.event != UFFD_EVENT_PAGEFAULT) fail("pager event");
    uintptr_t address = msg.arg.pagefault.address & ~(page - 1);
    if (address < (uintptr_t)arena || address >= (uintptr_t)arena + size) fail("pager address");
    size_t index = (address - (uintptr_t)arena) / page;
    if (lengths[index] == 0) memcpy(scratch, store + offsets[index], page);
    else if (decode((char *)store + offsets[index], (char *)scratch, lengths[index], (int)page) != (int)page)
      fail("decompression");
    struct uffdio_copy copy = {.dst = address, .src = (uintptr_t)scratch, .len = page};
    if (ioctl(uffd, UFFDIO_COPY, &copy)) fail("UFFDIO_COPY");
    faults++;
  }
  munmap(scratch, page);
  return NULL;
}

static uint64_t checksum(void) {
  uint64_t sum = 0;
  for (size_t i = 0; i < size; i++) sum = sum * 31 + arena[i];
  return sum;
}

static void kernel_access_probe(void) {
  pid_t child = fork();
  if (child < 0) fail("fork");
  if (!child) {
    alarm(2);
    int fd = open_pager(), pipefd[2];
    unsigned char *buffer = mmap(NULL, page, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (buffer == MAP_FAILED || pipe(pipefd)) _exit(1);
    memset(buffer, 0x5a, page);
    register_range(fd, buffer, page);
    if (madvise(buffer, page, MADV_DONTNEED)) _exit(1);
    errno = 0;
    ssize_t n = write(pipefd[1], buffer, page);
    _exit(n < 0 && errno == EFAULT ? 42 : 1);
  }
  int status;
  if (waitpid(child, &status, 0) < 0) fail("waitpid");
  if (WIFSIGNALED(status)) printf("kernel_access_signal=%d\n", WTERMSIG(status));
  else printf("kernel_access_exit=%d (42=EFAULT)\n", WEXITSTATUS(status));
}

int main(int argc, char **argv) {
  if (argc > 2) return 2;
  char *end = NULL;
  long mb = argc == 2 ? strtol(argv[1], &end, 10) : 64;
  if (mb < 1 || mb > 256 || (end && (*end || end == argv[1]))) return 2;
  alarm(30);
  page = (size_t)sysconf(_SC_PAGESIZE);
  size = (size_t)mb << 20;
  if (!page || size % page) return 2;
  void *codec = dlopen("liblz4.so.1", RTLD_NOW | RTLD_LOCAL);
  if (!codec) { fprintf(stderr, "%s\n", dlerror()); return 1; }
  int (*encode)(const char *, char *, int, int) = dlsym(codec, "LZ4_compress_default");
  int (*bound)(int) = dlsym(codec, "LZ4_compressBound");
  decode = dlsym(codec, "LZ4_decompress_safe");
  if (!encode || !decode || !bound) return 1;
  arena = mmap(NULL, size, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
  store = mmap(NULL, size, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
  if (arena == MAP_FAILED || store == MAP_FAILED) fail("arena");
  size_t count = size / page;
  offsets = calloc(count, sizeof(*offsets));
  lengths = calloc(count, sizeof(*lengths));
  char *scratch = malloc((size_t)bound((int)page));
  if (!offsets || !lengths || !scratch) fail("metadata");
  uint32_t random = 123456789;
  for (size_t i = 0; i < size; i++) {
    if ((i / page) % 2) {
      random ^= random << 13; random ^= random >> 17; random ^= random << 5;
      arena[i] = (unsigned char)random;
    } else arena[i] = (unsigned char)(i / page);
  }
  double start = milliseconds();
  uint64_t before = checksum();
  double baseline_ms = milliseconds() - start;
  long before_kb = resident_kb();
  uffd = open_pager();
  register_range(uffd, arena, size);
  start = milliseconds();
  size_t used = 0;
  for (size_t i = 0; i < count; i++) {
    int n = encode((char *)arena + i * page, scratch, (int)page, bound((int)page));
    if (n <= 0) fail("compression");
    offsets[i] = used;
    if ((size_t)n >= page) {
      memcpy(store + used, arena + i * page, page);
      used += page;
    } else {
      lengths[i] = n;
      memcpy(store + used, scratch, (size_t)n);
      used += (size_t)n;
    }
  }
  if (madvise(arena, size, MADV_DONTNEED)) fail("eviction");
  double compress_ms = milliseconds() - start;
  long compressed_kb = resident_kb();
  pthread_t pager;
  if (pthread_create(&pager, NULL, serve, NULL)) fail("pthread_create");
  start = milliseconds();
  uint64_t after = checksum();
  double restore_ms = milliseconds() - start;
  int writable = 1;
  for (size_t i = 0; i < size; i += page) {
    unsigned char value = arena[i] ^ 0xff;
    arena[i] = value;
    if (*(volatile unsigned char *)(arena + i) != value) writable = 0;
  }
  atomic_store(&finished, 1);
  pthread_join(pager, NULL);
  printf("arena_mb=%ld compressed_bytes=%zu rss_before_kb=%ld rss_compressed_kb=%ld faults=%u intact=%d writable=%d\n",
         mb, used, before_kb, compressed_kb, faults, before == after, writable);
  printf("baseline_scan_ms=%.3f compress_ms=%.3f restore_scan_ms=%.3f\n", baseline_ms, compress_ms, restore_ms);
  close(uffd);
  munmap(arena, size); munmap(store, size);
  free(offsets); free(lengths); free(scratch);
  dlclose(codec);
  kernel_access_probe();
  return before == after && writable && faults == count ? 0 : 1;
}
