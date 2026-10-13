#ifndef DROIDDECK_PROOT_STATX_MNT_ID_H
#define DROIDDECK_PROOT_STATX_MNT_ID_H

#include <errno.h>
#include <fcntl.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>

static inline bool droiddeck_proot_statx_fill_mnt_id(pid_t pid, int fd,
                                                     int flags, const char *path,
                                                     unsigned int mask,
                                                     struct statx *buf) {
  if (fd < 0 || !(flags & AT_EMPTY_PATH) || !path || path[0] != '\0'
      || !(mask & STATX_MNT_ID) || (buf->stx_mask & STATX_MNT_ID)
      || !(buf->stx_mask & STATX_INO))
    return false;

  char fdinfo_path[64];
  int n = snprintf(fdinfo_path, sizeof fdinfo_path, "/proc/%ld/fdinfo/%d", (long)pid, fd);
  if (n < 0 || (size_t)n >= sizeof fdinfo_path) return false;

  FILE *fdinfo = fopen(fdinfo_path, "r");
  if (!fdinfo) return false;

  bool have_mnt_id = false, have_ino = false;
  uint64_t mnt_id = 0, ino = 0;
  char line[256];
  while (fgets(line, sizeof line, fdinfo)) {
    const char *value = NULL;
    uint64_t *field = NULL;
    if (strncmp(line, "mnt_id:", 7) == 0) {
      value = line + 7;
      field = &mnt_id;
      have_mnt_id = true;
    } else if (strncmp(line, "ino:", 4) == 0) {
      value = line + 4;
      field = &ino;
      have_ino = true;
    }
    if (!value) continue;

    errno = 0;
    char *end = NULL;
    unsigned long long parsed = strtoull(value, &end, 10);
    if (errno || end == value || (*end && *end != '\n' && *end != '\r')) {
      if (field == &mnt_id) have_mnt_id = false;
      else have_ino = false;
      continue;
    }
    *field = (uint64_t)parsed;
  }
  bool read_error = ferror(fdinfo);
  fclose(fdinfo);

  /* Verify this fd still names the inode whose statx result we are completing. */
  if (read_error || !have_mnt_id || !have_ino || mnt_id == 0 || ino != buf->stx_ino)
    return false;

  buf->stx_mnt_id = mnt_id;
  buf->stx_mask |= STATX_MNT_ID;
  return true;
}

#endif
