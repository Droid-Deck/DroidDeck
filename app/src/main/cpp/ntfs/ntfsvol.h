#ifndef DROIDDECK_NTFSVOL_H
#define DROIDDECK_NTFSVOL_H

#include <stddef.h>
#include <stdint.h>

typedef struct ntfsvol ntfsvol;
typedef struct ntfsvol_file ntfsvol_file;

typedef int (*ntfsvol_read_fn)(void *ctx, uint64_t lba, uint32_t count, void *buf);

typedef struct {
    const char *name;
    int is_dir;
    int hidden;
    uint64_t size;
    int64_t mtime_ms;
} ntfsvol_entry;

typedef int (*ntfsvol_entry_fn)(void *ctx, const ntfsvol_entry *entry);

typedef struct {
    char label[256];
    uint64_t size;
    uint64_t free;
    int dirty;
} ntfsvol_info;

/*
 * Mounts the NTFS volume spanning [start, start + blocks) of the device read
 * through read(). The volume is always read-only. Every call on a volume and
 * on its files is serialised by the volume's own lock, as libntfs-3g is not
 * thread-safe.
 */
ntfsvol *ntfsvol_mount(ntfsvol_read_fn read, void *ctx, uint32_t block_size,
                       uint64_t start, uint64_t blocks, char *err, size_t err_len);
void ntfsvol_unmount(ntfsvol *vol);

int ntfsvol_get_info(ntfsvol *vol, ntfsvol_info *info);

/* Returns 0, or -1 with errno set. fn returning non-zero stops the listing. */
int ntfsvol_list(ntfsvol *vol, const char *path, ntfsvol_entry_fn fn, void *ctx);

ntfsvol_file *ntfsvol_open(ntfsvol *vol, const char *path, uint64_t *size);
int64_t ntfsvol_read(ntfsvol_file *file, uint64_t offset, void *buf, size_t len);
void ntfsvol_close(ntfsvol_file *file);

#endif
