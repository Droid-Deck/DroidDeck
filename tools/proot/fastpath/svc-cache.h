struct svc_cached_sites {
  uint64_t magic;
  uint32_t api, count;
  uint64_t offset[256];
};
static unsigned svc_cache_hits, svc_cache_misses;

static int svc_segment(const struct dl_phdr_info *info, uint64_t at, size_t length, unsigned flags) {
  for (int i = 0; i < info->dlpi_phnum; i++) {
    const ElfW(Phdr) *p = &info->dlpi_phdr[i];
    if (p->p_type == PT_LOAD && (p->p_flags & flags) == flags &&
        at >= p->p_vaddr && at - p->p_vaddr <= p->p_memsz &&
        length <= p->p_memsz - (at - p->p_vaddr)) return i + 1;
  }
  return 0;
}

static int svc_cache_name(const struct dl_phdr_info *info, char *path, size_t size) {
  const char *enabled = getenv("PROOT_FP_SVC_CACHE");
  if (enabled && strcmp(enabled, "1")) return 0;
  for (int i = 0; i < info->dlpi_phnum; i++) {
    const ElfW(Phdr) *p = &info->dlpi_phdr[i];
    if (p->p_type != PT_NOTE || !svc_segment(info, p->p_vaddr, p->p_memsz, PF_R)) continue;
    const unsigned char *note = (const unsigned char *)(info->dlpi_addr + p->p_vaddr);
    for (size_t at = 0; p->p_memsz - at >= sizeof(ElfW(Nhdr));) {
      ElfW(Nhdr) h;
      memcpy(&h, note + at, sizeof h);
      at += sizeof h;
      size_t name = ((size_t)h.n_namesz + 3) & ~(size_t)3;
      size_t desc = ((size_t)h.n_descsz + 3) & ~(size_t)3;
      if (name > p->p_memsz - at || desc > p->p_memsz - at - name) break;
      if (h.n_type == NT_GNU_BUILD_ID && h.n_namesz == 4 && !memcmp(note + at, "GNU", 4) &&
          h.n_descsz && h.n_descsz <= 64) {
        char id[129];
        static const char hex[] = "0123456789abcdef";
        for (size_t j = 0; j < h.n_descsz; j++) {
          unsigned char b = note[at + name + j];
          id[2 * j] = hex[b >> 4];
          id[2 * j + 1] = hex[b & 15];
        }
        id[2 * h.n_descsz] = 0;
        int n = snprintf(path, size, "/tmp/.droiddeck-svc-v2-%d-%s", svc_android_api, id);
        return n > 0 && (size_t)n < size;
      }
      at += name + desc;
    }
  }
  return 0;
}

static int svc_cache_read(const struct dl_phdr_info *info, const char *path) {
  long fd = fp_open(AT_FDCWD, path, O_RDONLY | O_NOFOLLOW | O_CLOEXEC | O_NONBLOCK, 0);
  if (fd < 0) return 0;
  struct stat st;
  struct svc_cached_sites table;
  int valid = sc(SYS_fstat, fd, (long)&st, 0, 0, 0) == 0 && S_ISREG(st.st_mode) &&
              st.st_uid == (uid_t)sc(SYS_getuid, 0, 0, 0, 0, 0) && !(st.st_mode & 0077) &&
              st.st_size == sizeof table && sc(SYS_read, fd, (long)&table, sizeof table, 0, 0) == sizeof table;
  sc(SYS_close, fd, 0, 0, 0, 0);
  if (!valid || table.magic != 0x324356534444ULL || table.api != (unsigned)svc_android_api ||
      !table.count || table.count > 256 || table.count > 256 - svc_planned) return 0;
  for (unsigned i = 0; i < table.count; i++) {
    uint64_t offset = table.offset[i];
    int segment = svc_segment(info, offset, 4, PF_R | PF_X);
    if ((offset & 3) || (i && offset <= table.offset[i - 1]) || !segment) return 0;
    const ElfW(Phdr) *p = &info->dlpi_phdr[segment - 1];
    const uint32_t *text = (const uint32_t *)(info->dlpi_addr + p->p_vaddr);
    size_t at = (offset - p->p_vaddr) / 4;
    if (text[at] != 0xd4000001U || !svc_supported(svc_site_number(text, at))) return 0;
  }
  for (unsigned i = 0; i < table.count; i++)
    svc_plan[svc_planned++].site = (uint32_t *)(info->dlpi_addr + table.offset[i]);
  return 1;
}

static void svc_cache_write(const struct dl_phdr_info *info, const char *path, size_t first) {
  if (svc_planned == first || svc_planned == 256) return;
  struct svc_cached_sites table = {.magic = 0x324356534444ULL, .api = svc_android_api, .count = svc_planned - first};
  for (unsigned i = 0; i < table.count; i++)
    table.offset[i] = (uintptr_t)svc_plan[first + i].site - info->dlpi_addr;
  char temporary[256];
  int n = snprintf(temporary, sizeof temporary, "%s.part-%d", path, getpid());
  if (n <= 0 || n >= (int)sizeof temporary) return;
  long fd = fp_open(AT_FDCWD, temporary, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
  if (fd < 0) return;
  long written = sc(SYS_write, fd, (long)&table, sizeof table, 0, 0);
  sc(SYS_close, fd, 0, 0, 0, 0);
  if (written == sizeof table) fp_rename(AT_FDCWD, temporary, AT_FDCWD, path, 0);
  fp_unlink(AT_FDCWD, temporary, 0);
}
