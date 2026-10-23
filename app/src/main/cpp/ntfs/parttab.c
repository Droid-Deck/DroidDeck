#include "parttab.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define GPT_MAX_ENTRIES 128
#define EBR_MAX_HOPS 64

static uint32_t le32(const uint8_t *p) {
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}

static uint64_t le64(const uint8_t *p) {
    return (uint64_t)le32(p) | ((uint64_t)le32(p + 4) << 32);
}

static int is_extended(uint8_t type) {
    return type == 0x05 || type == 0x0F || type == 0x85;
}

static void probe_fs(const uint8_t *boot, char fs[8]) {
    fs[0] = '\0';
    if (memcmp(boot + 3, "NTFS    ", 8) == 0) {
        strcpy(fs, "ntfs");
    } else if (memcmp(boot + 3, "EXFAT   ", 8) == 0) {
        strcpy(fs, "exfat");
    } else if (boot[510] == 0x55 && boot[511] == 0xAA &&
               (memcmp(boot + 82, "FAT32   ", 8) == 0 || memcmp(boot + 54, "FAT", 3) == 0)) {
        strcpy(fs, "fat");
    }
}

static void utf16_name(const uint8_t *src, int units, char *dst, size_t cap) {
    size_t out = 0;
    for (int i = 0; i < units; i++) {
        uint32_t c = (uint32_t)src[2 * i] | ((uint32_t)src[2 * i + 1] << 8);
        if (c == 0) break;
        if (c >= 0xD800 && c <= 0xDBFF && i + 1 < units) {
            uint32_t lo = (uint32_t)src[2 * i + 2] | ((uint32_t)src[2 * i + 3] << 8);
            if (lo >= 0xDC00 && lo <= 0xDFFF) {
                c = 0x10000 + ((c - 0xD800) << 10) + (lo - 0xDC00);
                i++;
            }
        }
        uint8_t enc[4];
        size_t n;
        if (c < 0x80) {
            enc[0] = (uint8_t)c;
            n = 1;
        } else if (c < 0x800) {
            enc[0] = (uint8_t)(0xC0 | (c >> 6));
            enc[1] = (uint8_t)(0x80 | (c & 0x3F));
            n = 2;
        } else if (c < 0x10000) {
            enc[0] = (uint8_t)(0xE0 | (c >> 12));
            enc[1] = (uint8_t)(0x80 | ((c >> 6) & 0x3F));
            enc[2] = (uint8_t)(0x80 | (c & 0x3F));
            n = 3;
        } else {
            enc[0] = (uint8_t)(0xF0 | (c >> 18));
            enc[1] = (uint8_t)(0x80 | ((c >> 12) & 0x3F));
            enc[2] = (uint8_t)(0x80 | ((c >> 6) & 0x3F));
            enc[3] = (uint8_t)(0x80 | (c & 0x3F));
            n = 4;
        }
        if (out + n + 1 > cap) break;
        memcpy(dst + out, enc, n);
        out += n;
    }
    dst[out] = '\0';
}

typedef struct {
    parttab_read_fn read;
    void *ctx;
    uint32_t block_size;
    uint64_t blocks;
    uint8_t *block;
    parttab_entry *out;
    int max;
    int count;
} scan_state;

static int add(scan_state *s, int index, uint64_t start, uint64_t length, const char *label) {
    if (s->count >= s->max) return 0;
    if (length == 0 || start >= s->blocks || length > s->blocks - start) return 0;
    parttab_entry *e = &s->out[s->count];
    memset(e, 0, sizeof(*e));
    e->index = index;
    e->start = start;
    e->blocks = length;
    if (label != NULL) snprintf(e->label, sizeof(e->label), "%s", label);
    if (s->read(s->ctx, start, 1, s->block) == 0) probe_fs(s->block, e->fs);
    s->count++;
    return 1;
}

static int scan_gpt(scan_state *s) {
    if (s->read(s->ctx, 1, 1, s->block) != 0) return -1;
    if (memcmp(s->block, "EFI PART", 8) != 0) return 0;

    uint64_t entries_lba = le64(s->block + 72);
    uint32_t count = le32(s->block + 80);
    uint32_t entry_size = le32(s->block + 84);
    if (entry_size < 128 || entry_size > 1024 || (entry_size & 7) != 0) return 0;
    if (count > GPT_MAX_ENTRIES) count = GPT_MAX_ENTRIES;

    uint64_t bytes = (uint64_t)count * entry_size;
    uint32_t span = (uint32_t)((bytes + s->block_size - 1) / s->block_size);
    if (span == 0 || entries_lba >= s->blocks || span > s->blocks - entries_lba) return 0;

    uint8_t *table = malloc((size_t)span * s->block_size);
    if (table == NULL) return -1;
    if (s->read(s->ctx, entries_lba, span, table) != 0) {
        free(table);
        return -1;
    }

    static const uint8_t empty[16] = {0};
    /* E3C9E316-0B5C-4DB8-817D-F92DF00215AE: the Microsoft Reserved partition
     * Windows puts before every data partition; it never holds a filesystem. */
    static const uint8_t reserved[16] = {0x16, 0xE3, 0xC9, 0xE3, 0x5C, 0x0B, 0xB8, 0x4D,
                                         0x81, 0x7D, 0xF9, 0x2D, 0xF0, 0x02, 0x15, 0xAE};
    for (uint32_t i = 0; i < count; i++) {
        const uint8_t *e = table + (size_t)i * entry_size;
        if (memcmp(e, empty, 16) == 0 || memcmp(e, reserved, 16) == 0) continue;
        uint64_t first = le64(e + 32);
        uint64_t last = le64(e + 40);
        if (last < first) continue;
        char label[160];
        utf16_name(e + 56, 36, label, sizeof(label));
        add(s, (int)i + 1, first, last - first + 1, label);
    }
    free(table);
    return 1;
}

static void scan_ebr(scan_state *s, uint64_t extended_start, int *next_index) {
    uint64_t ebr = extended_start;
    for (int hop = 0; hop < EBR_MAX_HOPS && ebr < s->blocks; hop++) {
        if (s->read(s->ctx, ebr, 1, s->block) != 0) return;
        if (s->block[510] != 0x55 || s->block[511] != 0xAA) return;
        const uint8_t *logical = s->block + 446;
        const uint8_t *link = s->block + 462;
        uint8_t logical_type = logical[4];
        uint64_t logical_start = ebr + le32(logical + 8);
        uint64_t logical_length = le32(logical + 12);
        uint8_t link_type = link[4];
        uint64_t link_offset = le32(link + 8);
        if (logical_type != 0) add(s, (*next_index)++, logical_start, logical_length, NULL);
        if (!is_extended(link_type) || link_offset == 0) return;
        /* Every EBR follows the previous one on disk; a link backwards is a corrupt or looping chain. */
        uint64_t next = extended_start + link_offset;
        if (next <= ebr) return;
        ebr = next;
    }
}

static int scan_mbr(scan_state *s, const uint8_t *mbr) {
    uint8_t table[64];
    memcpy(table, mbr + 446, sizeof(table));
    int next_logical = 5;
    for (int i = 0; i < 4; i++) {
        const uint8_t *p = table + 16 * i;
        uint8_t type = p[4];
        uint64_t start = le32(p + 8);
        uint64_t length = le32(p + 12);
        if (type == 0) continue;
        if (is_extended(type)) {
            scan_ebr(s, start, &next_logical);
        } else {
            add(s, i + 1, start, length, NULL);
        }
    }
    return 1;
}

int parttab_scan(parttab_read_fn read, void *ctx, uint32_t block_size, uint64_t blocks,
                 parttab_entry *out, int max) {
    if (block_size < 512 || blocks < 2) return -1;
    scan_state s = {
        .read = read,
        .ctx = ctx,
        .block_size = block_size,
        .blocks = blocks,
        .out = out,
        .max = max,
        .count = 0,
    };
    s.block = malloc(block_size);
    uint8_t *mbr = malloc(block_size);
    if (s.block == NULL || mbr == NULL) {
        free(s.block);
        free(mbr);
        return -1;
    }
    if (read(ctx, 0, 1, mbr) != 0) {
        free(s.block);
        free(mbr);
        return -1;
    }

    char whole[8];
    probe_fs(mbr, whole);
    int result;
    if (whole[0] != '\0' && strcmp(whole, "fat") != 0) {
        /* A boot sector at LBA 0 also ends in 0x55AA, so it is checked before the MBR. */
        add(&s, 0, 0, blocks, NULL);
        result = s.count;
    } else if (mbr[510] != 0x55 || mbr[511] != 0xAA) {
        result = 0;
    } else {
        int protective = 0;
        for (int i = 0; i < 4; i++) {
            if (mbr[446 + 16 * i + 4] == 0xEE) protective = 1;
        }
        int gpt = protective ? scan_gpt(&s) : 0;
        if (gpt < 0) {
            result = -1;
        } else if (gpt == 0) {
            if (strcmp(whole, "fat") == 0 && mbr[446 + 4] == 0 && mbr[462 + 4] == 0) {
                add(&s, 0, 0, blocks, NULL);
            } else {
                scan_mbr(&s, mbr);
            }
            result = s.count;
        } else {
            result = s.count;
        }
    }
    free(s.block);
    free(mbr);
    return result;
}
