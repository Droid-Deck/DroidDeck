#ifndef DROIDDECK_PARTTAB_H
#define DROIDDECK_PARTTAB_H

#include <stdint.h>

#define PARTTAB_MAX 32

typedef int (*parttab_read_fn)(void *ctx, uint64_t lba, uint32_t count, void *buf);

typedef struct {
    int index;
    uint64_t start;
    uint64_t blocks;
    char fs[8];
    char label[160];
} parttab_entry;

/*
 * Lists the partitions of a disk with an MBR, a GPT, or no table at all (a
 * filesystem written straight to LBA 0). start and blocks are in the device's
 * logical blocks. fs is "ntfs", "exfat", "fat" or "" after probing each boot
 * sector. Returns the number of entries or -1 when the disk cannot be read.
 */
int parttab_scan(parttab_read_fn read, void *ctx, uint32_t block_size, uint64_t blocks,
                 parttab_entry *out, int max);

#endif
