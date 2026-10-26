#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <linux/fs.h>
#include <linux/userfaultfd.h>
#include <stdint.h>
#include <sys/ioctl.h>
#include <sys/syscall.h>
#include <unistd.h>

#define WINE_ARM64_USERFAULTFD 374
#define PM_UFFD_WP (1ULL << 57)
#define PM_SWAP (1ULL << 62)
#define PM_PRESENT (1ULL << 63)

#if defined(PAGEMAP_SCAN) && defined(UFFD_USER_MODE_ONLY)
static int ww_uffd = -1;
static int scan_blocked;
#endif

__attribute__((visibility("hidden"))) int bl_writewatch_syscall(long number, const long *args, long (*real)(long, ...), long *rc) {
#if defined(__aarch64__) && defined(PAGEMAP_SCAN) && defined(UFFD_USER_MODE_ONLY)
  if (number != WINE_ARM64_USERFAULTFD || (args[0] & ~(long)(O_CLOEXEC | O_NONBLOCK | UFFD_USER_MODE_ONLY))) return 0;
  *rc = real(SYS_userfaultfd, args[0]);
  if (*rc >= 0) __atomic_store_n(&ww_uffd, (int)*rc, __ATOMIC_RELEASE);
  return 1;
#else
  (void)number;
  (void)args;
  (void)real;
  (void)rc;
  return 0;
#endif
}

#if defined(PAGEMAP_SCAN) && defined(UFFD_USER_MODE_ONLY)
static int protect(int uffd, uint64_t start, uint64_t end) {
  if (start == end) return 0;
  struct uffdio_writeprotect wp = { .range = { start, end - start }, .mode = UFFDIO_WRITEPROTECT_MODE_WP };
  return ioctl(uffd, UFFDIO_WRITEPROTECT, &wp);
}

static long emulate_scan(int fd, int uffd, struct pm_scan_arg *a) {
  uint64_t page = (uint64_t)getpagesize();
  if (a->size < sizeof(*a) || (a->flags & ~(uint64_t)PM_SCAN_WP_MATCHING) || a->category_inverted || a->category_anyof_mask
      || a->category_mask != PAGE_IS_WRITTEN || (a->return_mask & ~(uint64_t)PAGE_IS_WRITTEN) || a->end < a->start
      || ((a->start | a->end) & (page - 1)) || (a->vec_len && !a->vec)) {
    errno = EINVAL;
    return -1;
  }
  struct page_region *vec = (struct page_region *)(uintptr_t)a->vec;
  uint64_t regions = 0, pages = 0, addr = a->start, wp_start = 0, wp_end = 0;
  uint64_t entries[512];
  int wp = (a->flags & PM_SCAN_WP_MATCHING) != 0;
  while (addr < a->end) {
    uint64_t want = (a->end - addr) / page;
    if (want > 512) want = 512;
    ssize_t got = pread(fd, entries, want * sizeof(entries[0]), (off_t)(addr / page * sizeof(entries[0])));
    if (got < 0) return -1;
    if (got == 0) break;
    for (uint64_t i = 0; i < (uint64_t)got / sizeof(entries[0]); i++, addr += page) {
      uint64_t e = entries[i];
      if (!(e & (PM_PRESENT | PM_SWAP)) || (e & PM_UFFD_WP)) continue;
      if (a->max_pages && pages >= a->max_pages) goto done;
      if (a->vec_len) {
        if (regions && vec[regions - 1].end == addr) {
          vec[regions - 1].end += page;
        } else {
          if (regions == a->vec_len) goto done;
          vec[regions].start = addr;
          vec[regions].end = addr + page;
          vec[regions].categories = PAGE_IS_WRITTEN & a->return_mask;
          regions++;
        }
      }
      pages++;
      if (!wp) continue;
      if (wp_end != addr) {
        if (protect(uffd, wp_start, wp_end) < 0) return -1;
        wp_start = addr;
      }
      wp_end = addr + page;
    }
  }
done:
  if (wp && protect(uffd, wp_start, wp_end) < 0) return -1;
  a->walk_end = addr < a->end ? addr : a->end;
  return (long)regions;
}
#endif

__attribute__((visibility("hidden"))) int bl_writewatch_ioctl(int (*real)(int, unsigned long, void *), int fd, unsigned long request,
                                                             void *arg, int *rc) {
#if defined(PAGEMAP_SCAN) && defined(UFFD_USER_MODE_ONLY)
  if (request != PAGEMAP_SCAN) return 0;
  int uffd = __atomic_load_n(&ww_uffd, __ATOMIC_ACQUIRE);
  if (uffd < 0) return 0;
  if (!__atomic_load_n(&scan_blocked, __ATOMIC_RELAXED)) {
    *rc = real(fd, request, arg);
    if (*rc >= 0 || errno != EACCES) return 1;
    __atomic_store_n(&scan_blocked, 1, __ATOMIC_RELAXED);
  }
  *rc = (int)emulate_scan(fd, uffd, (struct pm_scan_arg *)arg);
  return 1;
#else
  (void)real;
  (void)fd;
  (void)request;
  (void)arg;
  (void)rc;
  return 0;
#endif
}
