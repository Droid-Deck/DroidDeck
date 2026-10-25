from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

PRELOAD = Path(__file__).resolve().parents[1] / "linuxfs/preload"

PROGRAM = r"""
#include "WRITEWATCH"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>

#define PAGES 96

static int uffd, pagemap;
static uint64_t page;

static char *region(void) {
  char *m = mmap(NULL, PAGES * page, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
  madvise(m, PAGES * page, MADV_NOHUGEPAGE);
  struct uffdio_register reg = { .range = { (uintptr_t)m, PAGES * page }, .mode = UFFDIO_REGISTER_MODE_WP };
  if (m == MAP_FAILED || ioctl(uffd, UFFDIO_REGISTER, &reg)) exit(3);
  protect(uffd, (uintptr_t)m, (uintptr_t)m + PAGES * page);
  return m;
}

static long scan(char *m, int emulated, uint64_t flags, uint64_t vec_len, uint64_t max_pages, uint64_t from, uint64_t to,
                 struct page_region *vec, uint64_t *walk_end) {
  struct pm_scan_arg a = { .size = sizeof a, .flags = flags, .start = (uintptr_t)m + from * page, .end = (uintptr_t)m + to * page,
                           .vec = vec_len ? (uintptr_t)vec : 0, .vec_len = vec_len, .max_pages = max_pages,
                           .category_mask = PAGE_IS_WRITTEN, .return_mask = PAGE_IS_WRITTEN };
  long n = emulated ? emulate_scan(pagemap, uffd, &a) : ioctl(pagemap, PAGEMAP_SCAN, &a);
  *walk_end = (a.walk_end - (uintptr_t)m) / page;
  return n;
}

int main(int argc, char **argv) {
  page = (uint64_t)getpagesize();
  uffd = syscall(SYS_userfaultfd, O_CLOEXEC | O_NONBLOCK | UFFD_USER_MODE_ONLY);
  struct uffdio_api api = { .api = UFFD_API, .features = UFFD_FEATURE_WP_ASYNC | UFFD_FEATURE_WP_UNPOPULATED };
  pagemap = open("/proc/self/pagemap", O_RDONLY | O_CLOEXEC);
  if (uffd < 0 || ioctl(uffd, UFFDIO_API, &api) || pagemap < 0) { puts("unsupported"); return 0; }
  char *kernel = region(), *ours = region();
  struct page_region probe[1];
  uint64_t end;
  if (scan(kernel, 0, 0, 1, 0, 0, PAGES, probe, &end) < 0) { puts("unsupported"); return 0; }
  srand(atoi(argv[1]));
  int rounds = atoi(argv[2]), differing = 0;
  for (int r = 0; r < rounds; r++) {
    int writes = rand() % 24;
    for (int w = 0; w < writes; w++) {
      int p = rand() % PAGES;
      if (rand() % 4 == 0) {
        volatile char x = kernel[p * page] + ours[p * page];
        (void)x;
        continue;
      }
      kernel[p * page + rand() % page] = (char)r;
      ours[p * page + rand() % page] = (char)r;
    }
    uint64_t flags = rand() % 2 ? PM_SCAN_WP_MATCHING : 0;
    uint64_t vec_len = rand() % 3 == 0 ? 0 : 1 + rand() % 5;
    uint64_t max_pages = rand() % 3 == 0 ? 1 + rand() % 8 : 0;
    if (!vec_len) flags = PM_SCAN_WP_MATCHING, max_pages = 0;
    uint64_t from = rand() % PAGES, to = from + rand() % (PAGES - from + 1);
    struct page_region kv[8], ov[8];
    uint64_t kend, oend;
    long kn = scan(kernel, 0, flags, vec_len, max_pages, from, to, kv, &kend);
    long on = scan(ours, 1, flags, vec_len, max_pages, from, to, ov, &oend);
    int same = kn == on && (kn < 0 || kend == oend);
    for (long i = 0; same && i < kn; i++)
      same = kv[i].start - (uintptr_t)kernel == ov[i].start - (uintptr_t)ours
          && kv[i].end - (uintptr_t)kernel == ov[i].end - (uintptr_t)ours && kv[i].categories == ov[i].categories;
    struct page_region kall[PAGES], oall[PAGES];
    long ka = scan(kernel, 0, 0, PAGES, 0, 0, PAGES, kall, &kend), oa = scan(ours, 1, 0, PAGES, 0, 0, PAGES, oall, &oend);
    same = same && ka == oa;
    for (long i = 0; same && i < ka; i++)
      same = kall[i].start - (uintptr_t)kernel == oall[i].start - (uintptr_t)ours && kall[i].end - (uintptr_t)kernel == oall[i].end - (uintptr_t)ours;
    if (!same) {
      differing++;
      printf("round %d flags %llu vec %llu max %llu range %llu-%llu: kernel %ld end %llu, ours %ld end %llu\n", r,
             (unsigned long long)flags, (unsigned long long)vec_len, (unsigned long long)max_pages, (unsigned long long)from,
             (unsigned long long)to, kn, (unsigned long long)kend, on, (unsigned long long)oend);
    }
  }
  printf("rounds %d differing %d\n", rounds, differing);
  return 0;
}
"""


class WriteWatchPreloadTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if shutil.which("gcc") is None:
            raise unittest.SkipTest("gcc is not available")
        cls.work = Path(tempfile.mkdtemp())
        source = cls.work / "writewatch.c"
        source.write_text(PROGRAM.replace("WRITEWATCH", str(PRELOAD / "writewatch.c")))
        cls.program = cls.work / "writewatch"
        subprocess.run(["gcc", "-O1", "-Wall", "-o", str(cls.program), str(source)], check=True)

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.work, ignore_errors=True)

    def test_a_scan_android_refuses_answers_like_the_kernel_one(self):
        for seed in range(4):
            out = subprocess.run([str(self.program), str(seed), "400"], capture_output=True, text=True, timeout=60).stdout
            if out.strip() == "unsupported":
                self.skipTest("this kernel has no asynchronous userfaultfd write protection")
            self.assertTrue(out.rstrip().endswith("differing 0"), out)


if __name__ == "__main__":
    unittest.main()
