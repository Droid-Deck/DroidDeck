#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <link.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

static int mov_sysnum(uint32_t insn) {
  if ((insn & 0xffe0001fU) == 0xd2800008U ||
      (insn & 0xffe0001fU) == 0x52800008U)
    return (insn >> 5) & 0xffff;
  return -1;
}

static int scan(struct dl_phdr_info *info, size_t size, void *unused) {
  (void)size;
  (void)unused;
  const char *name = strrchr(info->dlpi_name, '/');
  name = name ? name + 1 : info->dlpi_name;
  if (strcmp(name, "libc.so.6") && strcmp(name, "ld-linux-aarch64.so.1")) return 0;
  unsigned total = 0, adjacent = 0, nearby = 0, counts[512] = {0};
  for (int i = 0; i < info->dlpi_phnum; i++) {
    const ElfW(Phdr) *ph = &info->dlpi_phdr[i];
    if (ph->p_type != PT_LOAD || !(ph->p_flags & PF_X)) continue;
    const uint32_t *text = (const uint32_t *)(info->dlpi_addr + ph->p_vaddr);
    for (size_t j = 0; j < ph->p_memsz / 4; j++) {
      if (text[j] != 0xd4000001U) continue;
      total++;
      int nr = j ? mov_sysnum(text[j - 1]) : -1;
      if (nr >= 0) {
        adjacent++;
        if (nr < 512) counts[nr]++;
      } else {
        for (size_t k = 2; k <= 8 && k <= j; k++)
          if (mov_sysnum(text[j - k]) >= 0) { nearby++; break; }
      }
    }
  }
  printf("{\"object\":\"%s\",\"svc\":%u,\"adjacent_mov_x8\":%u,\"nearby_only\":%u,\"adjacent_syscalls\":{",
         name, total, adjacent, nearby);
  int comma = 0;
  for (unsigned nr = 0; nr < 512; nr++)
    if (counts[nr]) { printf("%s\"%u\":%u", comma ? "," : "", nr, counts[nr]); comma = 1; }
  puts("}}");
  return 0;
}

int main(void) {
  long page = sysconf(_SC_PAGESIZE);
  int fd = open("/usr/lib/libc.so.6", O_RDONLY | O_CLOEXEC);
  if (fd < 0) { perror("libc"); return 1; }
  unsigned char *p = mmap(NULL, page, PROT_READ | PROT_EXEC, MAP_PRIVATE, fd, 0);
  if (p == MAP_FAILED) { perror("file mmap RX"); close(fd); return 1; }
  unsigned char before = p[0], changed = before ^ 1;
  errno = 0;
  int rwx = mprotect(p, page, PROT_READ | PROT_WRITE | PROT_EXEC), rwx_errno = errno;
  if (rwx == 0) {
    p[0] = changed;
    p[0] = before;
    if (mprotect(p, page, PROT_READ | PROT_EXEC)) { perror("restore RX"); return 1; }
  }
  int mem = open("/proc/self/mem", O_RDWR | O_CLOEXEC), mem_errno = mem < 0 ? errno : 0;
  ssize_t written = -1;
  int write_errno = 0, verified = 0, restored = 0;
  if (mem >= 0) {
    errno = 0;
    written = pwrite(mem, &changed, 1, (off_t)(uintptr_t)p);
    write_errno = errno;
    verified = written == 1 && *(volatile unsigned char *)p == changed;
    restored = pwrite(mem, &before, 1, (off_t)(uintptr_t)p) == 1 &&
               *(volatile unsigned char *)p == before;
    close(mem);
  }
  unsigned char disk = 0;
  int file_unchanged = pread(fd, &disk, 1, 0) == 1 && disk == before;
  munmap(p, page);
  close(fd);
  void *anon = mmap(NULL, page, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
  errno = 0;
  int execmem = anon == MAP_FAILED ? -1 : mprotect(anon, page, PROT_READ | PROT_EXEC);
  int execmem_errno = errno;
  if (anon != MAP_FAILED) munmap(anon, page);
  printf("{\"page_size\":%ld,\"rwx\":%d,\"rwx_errno\":%d,\"mem_open_errno\":%d,"
         "\"mem_written\":%ld,\"mem_write_errno\":%d,\"mem_verified\":%d,\"mem_restored\":%d,"
         "\"file_unchanged\":%d,\"execmem\":%d,\"execmem_errno\":%d}\n",
         page, rwx, rwx_errno, mem_errno, (long)written, write_errno, verified, restored,
         file_unchanged, execmem, execmem_errno);
  dl_iterate_phdr(scan, NULL);
  return 0;
}
