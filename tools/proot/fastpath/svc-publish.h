static void svc_publish(int mem, size_t page) {
  for (size_t first = 0; first < svc_planned;) {
    uint32_t *relay = svc_relay_page((uintptr_t)svc_plan[first].site, page);
    if (!relay) { first++; continue; }
    size_t end = first;
    for (; end < svc_planned && (end - first + 1) * 40 <= page; end++) {
      uint32_t *code = relay + (end - first) * 10;
      if (!svc_emit(code, svc_plan[end].site, &svc_plan[end].branch)) break;
    }
    __builtin___clear_cache((char *)relay, (char *)relay + page);
    if (end == first || mprotect(relay, page, PROT_READ | PROT_EXEC)) {
      munmap(relay, page);
      first++;
      continue;
    }
    unsigned patched = 0;
    for (size_t i = first; i < end; i++) {
      struct svc_site *s = &svc_plan[i];
      long written = sc(SYS_pwrite64, mem, (long)&s->branch, sizeof s->branch, (long)s->site, 0);
      if (written != sizeof s->branch) continue;
      __builtin___clear_cache((char *)s->site, (char *)(s->site + 1));
      patched++;
    }
    if (patched) { svc_sites += patched; svc_pages++; }
    else munmap(relay, page);
    first = end;
  }
}
