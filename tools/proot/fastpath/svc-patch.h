static inline int svc_number(uint32_t insn) {
  if ((insn & 0xffe0001fU) == 0xd2800008U ||
      (insn & 0xffe0001fU) == 0x52800008U)
    return (insn >> 5) & 0xffff;
  return -1;
}

static inline int svc_argument_instruction(uint32_t insn) {
  if (insn == 0xd503201fU) return 1;
  if ((insn & 0xffc00000U) == 0x29000000U ||
      (insn & 0xffc00000U) == 0xa9000000U ||
      (insn & 0xffc00000U) == 0xb9000000U ||
      (insn & 0xffc00000U) == 0xf9000000U) return 1;
  if ((insn & 31) == 8) return 0;
  return (insn & 0xffe0ffe0U) == 0xaa0003e0U ||
         (insn & 0xffe0ffe0U) == 0x2a0003e0U ||
         (insn & 0xfffffc00U) == 0x93407c00U ||
         (insn & 0x7f800000U) == 0x52800000U ||
         (insn & 0x7f800000U) == 0x12800000U ||
         (insn & 0xffc00000U) == 0xb9400000U ||
         (insn & 0xffc00000U) == 0xf9400000U ||
         (insn & 0xffc00000U) == 0xb9800000U ||
         (insn & 0xff000000U) == 0x91000000U ||
         (insn & 0xff000000U) == 0xd1000000U ||
         (insn & 0xff000000U) == 0x11000000U ||
         (insn & 0xff000000U) == 0x51000000U;
}

static inline int svc_site_number(const uint32_t *text, size_t at) {
  for (size_t back = 1; back <= 8 && back <= at; back++) {
    uint32_t insn = text[at - back];
    int nr = svc_number(insn);
    if (nr >= 0) return nr;
    if (!svc_argument_instruction(insn)) break;
  }
  return -1;
}

static inline int svc_branch(uintptr_t from, uintptr_t to, uint32_t *out) {
  int64_t delta = (int64_t)to - (int64_t)from;
  if ((delta & 3) || delta < -0x8000000LL || delta >= 0x8000000LL) return 0;
  *out = 0x14000000U | ((uint32_t)(delta / 4) & 0x03ffffffU);
  return 1;
}

#if defined(__aarch64__) && !defined(FP_SVC_ENCODINGS_ONLY)
#include <asm/hwcap.h>
#include <link.h>
#include <sys/uio.h>
#include <signal.h>

static __thread uintptr_t svc_busy __attribute__((tls_model("initial-exec")));
static unsigned svc_sites, svc_pages;
static unsigned long svc_fast, svc_slow;
static unsigned long long svc_scan_ns, svc_patch_ns;

#include "svc-dispatch.h"
#include "svc-stack.h"

static long svc_original(const long *r) {
  register long x0 __asm__("x0") = r[0];
  register long x1 __asm__("x1") = r[1];
  register long x2 __asm__("x2") = r[2];
  register long x3 __asm__("x3") = r[3];
  register long x4 __asm__("x4") = r[4];
  register long x5 __asm__("x5") = r[5];
  register long x8 __asm__("x8") = r[8];
  __asm__ volatile("svc #0" : "+r"(x0) : "r"(x1), "r"(x2), "r"(x3), "r"(x4), "r"(x5), "r"(x8) : "memory", "cc");
  return x0;
}

__attribute__((used, visibility("hidden")))
long fp_svc_dispatch(const long *r) {
  int saved_errno = errno;
  long result = svc_blocked(r);
  uintptr_t frame = (uintptr_t)r;
  if (result == FP_SLOW && (!svc_busy || frame >= svc_busy)) {
    uintptr_t previous = svc_busy;
    svc_busy = frame;
    if (svc_stack_room(frame)) { result = svc_path_call(r); svc_busy = 0; }
    else svc_busy = previous;
  }
  if (result == FP_SLOW) {
    __atomic_fetch_add(&svc_slow, 1, __ATOMIC_RELAXED);
    result = svc_original(r);
  } else __atomic_fetch_add(&svc_fast, 1, __ATOMIC_RELAXED);
  errno = saved_errno;
  return result;
}

void fp_svc_entry(void) __attribute__((visibility("hidden")));
/* A replaced svc may have live values in every register, including NZCV and SIMD. */
__asm__(
  ".text\n.p2align 4\n.hidden fp_svc_entry\n.type fp_svc_entry,%function\nfp_svc_entry:\n"
  ".cfi_startproc\n.cfi_def_cfa sp, 16\n.cfi_return_column 16\n"
  "bti j\nsub sp, sp, #800\n.cfi_def_cfa_offset 816\n"
  "stp x0, x1, [sp, #0]\nstp x2, x3, [sp, #16]\n"
  "stp x4, x5, [sp, #32]\nstp x6, x7, [sp, #48]\n"
  "stp x8, x9, [sp, #64]\nstp x10, x11, [sp, #80]\n"
  "stp x12, x13, [sp, #96]\nstp x14, x15, [sp, #112]\n"
  "stp x18, x19, [sp, #128]\nstp x20, x21, [sp, #144]\n"
  "stp x22, x23, [sp, #160]\nstp x24, x25, [sp, #176]\n"
  "stp x26, x27, [sp, #192]\nstp x28, x29, [sp, #208]\n"
  "stp x30, x17, [sp, #224]\nmrs x0, nzcv\nstr x0, [sp, #240]\n"
  "ldr x0, [x17, #8]\nstr x0, [sp, #784]\n"
  ".cfi_offset 16, -32\n.cfi_offset 19, -680\n.cfi_offset 20, -672\n"
  ".cfi_offset 21, -664\n.cfi_offset 22, -656\n.cfi_offset 23, -648\n"
  ".cfi_offset 24, -640\n.cfi_offset 25, -632\n.cfi_offset 26, -624\n"
  ".cfi_offset 27, -616\n.cfi_offset 28, -608\n.cfi_offset 29, -600\n.cfi_offset 30, -592\n"
  ".cfi_offset 72, -432\n.cfi_offset 73, -416\n.cfi_offset 74, -400\n.cfi_offset 75, -384\n"
  ".cfi_offset 76, -368\n.cfi_offset 77, -352\n.cfi_offset 78, -336\n.cfi_offset 79, -320\n"
  "mrs x0, fpcr\nstr x0, [sp, #768]\nmrs x0, fpsr\nstr x0, [sp, #776]\n"
  "stp q0, q1, [sp, #256]\nstp q2, q3, [sp, #288]\n"
  "stp q4, q5, [sp, #320]\nstp q6, q7, [sp, #352]\n"
  "stp q8, q9, [sp, #384]\nstp q10, q11, [sp, #416]\n"
  "stp q12, q13, [sp, #448]\nstp q14, q15, [sp, #480]\n"
  "stp q16, q17, [sp, #512]\nstp q18, q19, [sp, #544]\n"
  "stp q20, q21, [sp, #576]\nstp q22, q23, [sp, #608]\n"
  "stp q24, q25, [sp, #640]\nstp q26, q27, [sp, #672]\n"
  "stp q28, q29, [sp, #704]\nstp q30, q31, [sp, #736]\n"
  "mov x0, sp\nbl fp_svc_dispatch\nstr x0, [sp]\n"
  "ldp q0, q1, [sp, #256]\nldp q2, q3, [sp, #288]\n"
  "ldp q4, q5, [sp, #320]\nldp q6, q7, [sp, #352]\n"
  "ldp q8, q9, [sp, #384]\nldp q10, q11, [sp, #416]\n"
  "ldp q12, q13, [sp, #448]\nldp q14, q15, [sp, #480]\n"
  "ldp q16, q17, [sp, #512]\nldp q18, q19, [sp, #544]\n"
  "ldp q20, q21, [sp, #576]\nldp q22, q23, [sp, #608]\n"
  "ldp q24, q25, [sp, #640]\nldp q26, q27, [sp, #672]\n"
  "ldp q28, q29, [sp, #704]\nldp q30, q31, [sp, #736]\n"
  "ldr x0, [sp, #768]\nmsr fpcr, x0\nldr x0, [sp, #776]\nmsr fpsr, x0\n"
  "ldr x0, [sp, #240]\nmsr nzcv, x0\n"
  "ldp x0, x1, [sp, #0]\nldp x2, x3, [sp, #16]\n"
  "ldp x4, x5, [sp, #32]\nldp x6, x7, [sp, #48]\n"
  "ldp x8, x9, [sp, #64]\nldp x10, x11, [sp, #80]\n"
  "ldp x12, x13, [sp, #96]\nldp x14, x15, [sp, #112]\n"
  "ldp x18, x19, [sp, #128]\nldp x20, x21, [sp, #144]\n"
  "ldp x22, x23, [sp, #160]\nldp x24, x25, [sp, #176]\n"
  "ldp x26, x27, [sp, #192]\nldp x28, x29, [sp, #208]\n"
  "ldr x30, [sp, #224]\nldr x16, [sp, #232]\n"
  "add sp, sp, #800\n.cfi_def_cfa_offset 16\nbr x16\n.cfi_endproc\n.size fp_svc_entry, .-fp_svc_entry\n"
);

static int svc_emit(uint32_t *code, uint32_t *site, uint32_t *branch) {
  uint32_t back;
  if (!svc_branch((uintptr_t)site, (uintptr_t)code, branch) ||
      !svc_branch((uintptr_t)(code + 5), (uintptr_t)(site + 1), &back)) return 0;
  code[0] = 0xa9bf47f0U; /* stp x16, x17, [sp, #-16]! */
  code[1] = 0x580000f0U; /* ldr x16, literal at +32 */
  code[2] = 0x10000051U; /* adr x17, +8 */
  code[3] = 0xd61f0200U; /* br x16 */
  code[4] = 0xa8c147f0U; /* ldp x16, x17, [sp], #16 */
  code[5] = back;
  uintptr_t entry = (uintptr_t)fp_svc_entry;
  uintptr_t resume = (uintptr_t)(site + 1);
  memcpy(code + 6, &resume, sizeof resume);
  memcpy(code + 8, &entry, sizeof entry);
  return 1;
}

struct svc_site { uint32_t *site; uint32_t branch; };
static struct svc_site svc_plan[256];
static size_t svc_planned;

#include "svc-cache.h"

static int svc_object(struct dl_phdr_info *info, size_t size, void *unused) {
  (void)size;
  (void)unused;
  const char *name = strrchr(info->dlpi_name, '/');
  name = name ? name + 1 : info->dlpi_name;
  if (strcmp(name, "libc.so.6") && strcmp(name, "ld-linux-aarch64.so.1")) return 0;
  char cache[224];
  int cached = svc_cache_name(info, cache, sizeof cache);
  if (cached && svc_cache_read(info, cache)) { svc_cache_hits++; return 0; }
  if (cached) svc_cache_misses++;
  size_t first = svc_planned;
  for (int i = 0; i < info->dlpi_phnum; i++) {
    const ElfW(Phdr) *ph = &info->dlpi_phdr[i];
    if (ph->p_type != PT_LOAD || !(ph->p_flags & PF_X)) continue;
    uint32_t *text = (uint32_t *)(info->dlpi_addr + ph->p_vaddr);
    for (size_t j = 1; j < ph->p_memsz / 4 && svc_planned < 256; j++) {
      if (text[j] != 0xd4000001U) continue;
      int nr = svc_site_number(text, j);
      if (!svc_supported(nr)) continue;
      svc_plan[svc_planned++].site = &text[j];
    }
  }
  if (cached) svc_cache_write(info, cache, first);
  return 0;
}

static uint32_t *svc_relay_page(uintptr_t site, size_t page) {
  uintptr_t base = site & ~(uintptr_t)(page - 1);
  for (uintptr_t delta = page; delta < 0x7ff0000UL; delta += 0x10000UL) {
    for (int direction = -1; direction <= 1; direction += 2) {
      uintptr_t at = direction < 0 ? base - delta : base + delta;
      if (at < page) continue;
      void *p = mmap((void *)at, page, PROT_READ | PROT_WRITE,
                     MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED_NOREPLACE, -1, 0);
      if (p == (void *)at) return p;
      if (p != MAP_FAILED) munmap(p, page);
    }
  }
  return NULL;
}

#include "svc-publish.h"

static int svc_single_thread(void) {
  struct entry { uint64_t ino; int64_t off; unsigned short len; unsigned char type; char name[]; };
  char buf[4096] __attribute__((aligned(8)));
  long fd = sc(SYS_openat, AT_FDCWD, (long)"/proc/self/task", O_RDONLY | O_DIRECTORY | O_CLOEXEC, 0, 0);
  if (fd < 0) return 0;
  int count = 0;
  long n;
  while ((n = sc(SYS_getdents64, fd, (long)buf, sizeof buf, 0, 0)) > 0) {
    for (long at = 0; at < n;) {
      const struct entry *e = (const struct entry *)(buf + at);
      if (n - at < (long)offsetof(struct entry, name) + 1 ||
          e->len < offsetof(struct entry, name) + 1 || e->len > n - at) { count = -1; goto done; }
      if (e->name[0] >= '0' && e->name[0] <= '9' && ++count > 1) goto done;
      at += e->len;
    }
  }
  if (n < 0) count = -1;
done:
  sc(SYS_close, fd, 0, 0, 0, 0);
  return count == 1;
}

static void svc_stats(void) {
  char line[256];
  int n = snprintf(line, sizeof line, "fastpath-svc[%d]: %u sites, %u relay pages, %lu fast, %lu to proot; scan %llu us, patch %llu us, android %d, cache %u hit %u miss\n",
                   getpid(), svc_sites, svc_pages, svc_fast, svc_slow, svc_scan_ns / 1000, svc_patch_ns / 1000, svc_android_api, svc_cache_hits, svc_cache_misses);
  if (n > 0 && write(2, line, n < (int)sizeof line ? n : (int)sizeof line - 1) < 0) {}
}

static unsigned long long svc_clock(void) {
  struct timespec t;
  if (sc(SYS_clock_gettime, CLOCK_MONOTONIC, (long)&t, 0, 0, 0) < 0) return 0;
  return (unsigned long long)t.tv_sec * 1000000000ULL + t.tv_nsec;
}

static void svc_child(void) { svc_busy = 0; }

__attribute__((constructor)) static void svc_patch_init(void) {
  const char *enabled = getenv("PROOT_FP_PATCH_SVC");
  if ((enabled && strcmp(enabled, "1")) || !fp_ready()) return;
  if (getauxval(AT_HWCAP) & HWCAP_SVE) return;
#ifdef HWCAP2_SME
  if (getauxval(AT_HWCAP2) & HWCAP2_SME) return;
#endif
  if (!svc_single_thread()) return;
  if (pthread_atfork(NULL, NULL, svc_child)) return;
  svc_android_api = svc_host_android_api();
  size_t page = (size_t)sysconf(_SC_PAGESIZE);
  if (page < 4096 || (page & (page - 1))) return;
  int mem = (int)sc(SYS_openat, AT_FDCWD, (long)"/proc/self/mem", O_RDWR | O_CLOEXEC, 0, 0);
  if (mem < 0) return;
  unsigned long long start = svc_clock();
  dl_iterate_phdr(svc_object, NULL);
  svc_scan_ns = svc_clock() - start;
  start = svc_clock();
  svc_publish(mem, page);
  sc(SYS_close, mem, 0, 0, 0, 0);
  svc_patch_ns = svc_clock() - start;
  if (getenv("PROOT_FP_STATS")) atexit(svc_stats);
}
#endif
