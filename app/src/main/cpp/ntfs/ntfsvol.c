#include "ntfsvol.h"

#include <errno.h>
#include <linux/fs.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>

#include "types.h"
#include "attrib.h"
#include "device.h"
#include "dir.h"
#include "inode.h"
#include "layout.h"
#include "ntfstime.h"
#include "unistr.h"
#include "volume.h"

#define GRANULE (64 * 1024)
#define SLOTS 64
#define BYPASS (256 * 1024)

typedef struct {
    uint64_t index;
    int valid;
    uint8_t *data;
} granule;

typedef struct {
    ntfsvol_read_fn read;
    void *ctx;
    uint32_t block_size;
    uint64_t start;
    uint64_t bytes;
    s64 pos;
    granule cache[SLOTS];
} part_dev;

struct ntfsvol {
    pthread_mutex_t lock;
    ntfs_volume *v;
    part_dev *pd;
    ntfsvol_file *files;
};

struct ntfsvol_file {
    ntfsvol *vol;
    ntfs_inode *ni;
    ntfs_attr *na;
    ntfsvol_file *prev;
    ntfsvol_file *next;
};

static int part_open(struct ntfs_device *dev, int flags) {
    (void)flags;
    if (NDevOpen(dev)) {
        errno = EBUSY;
        return -1;
    }
    NDevSetReadOnly(dev);
    NDevSetOpen(dev);
    return 0;
}

static int part_close(struct ntfs_device *dev) {
    if (!NDevOpen(dev)) {
        errno = EBADF;
        return -1;
    }
    NDevClearOpen(dev);
    return 0;
}

static s64 part_pread(struct ntfs_device *dev, void *buf, s64 count, s64 offset) {
    part_dev *pd = dev->d_private;
    if (offset < 0 || count < 0) {
        errno = EINVAL;
        return -1;
    }
    if ((uint64_t)offset >= pd->bytes || count == 0) return 0;
    if ((uint64_t)count > pd->bytes - (uint64_t)offset) count = (s64)(pd->bytes - (uint64_t)offset);

    uint32_t bs = pd->block_size;
    if (count >= BYPASS && offset % bs == 0 && count % bs == 0) {
        if (pd->read(pd->ctx, pd->start + (uint64_t)offset / bs, (uint32_t)(count / bs), buf) != 0) {
            errno = EIO;
            return -1;
        }
        return count;
    }

    uint8_t *out = buf;
    s64 done = 0;
    while (done < count) {
        uint64_t at = (uint64_t)(offset + done);
        uint64_t index = at / GRANULE;
        granule *g = &pd->cache[index % SLOTS];
        uint64_t base = index * GRANULE;
        uint64_t length = pd->bytes - base < GRANULE ? pd->bytes - base : GRANULE;
        if (!g->valid || g->index != index) {
            if (g->data == NULL && (g->data = malloc(GRANULE)) == NULL) {
                errno = ENOMEM;
                return done > 0 ? done : -1;
            }
            g->valid = 0;
            if (pd->read(pd->ctx, pd->start + base / bs, (uint32_t)(length / bs), g->data) != 0) {
                errno = EIO;
                return done > 0 ? done : -1;
            }
            g->index = index;
            g->valid = 1;
        }
        uint64_t within = at - base;
        uint64_t take = length - within;
        if (take > (uint64_t)(count - done)) take = (uint64_t)(count - done);
        memcpy(out + done, g->data + within, (size_t)take);
        done += (s64)take;
    }
    return done;
}

static s64 part_seek(struct ntfs_device *dev, s64 offset, int whence) {
    part_dev *pd = dev->d_private;
    s64 base;
    switch (whence) {
        case SEEK_SET: base = 0; break;
        case SEEK_CUR: base = pd->pos; break;
        case SEEK_END: base = (s64)pd->bytes; break;
        default: errno = EINVAL; return -1;
    }
    if (base + offset < 0) {
        errno = EINVAL;
        return -1;
    }
    pd->pos = base + offset;
    return pd->pos;
}

static s64 part_read(struct ntfs_device *dev, void *buf, s64 count) {
    part_dev *pd = dev->d_private;
    s64 got = part_pread(dev, buf, count, pd->pos);
    if (got > 0) pd->pos += got;
    return got;
}

static s64 part_write(struct ntfs_device *dev, const void *buf, s64 count) {
    (void)dev;
    (void)buf;
    (void)count;
    errno = EROFS;
    return -1;
}

static s64 part_pwrite(struct ntfs_device *dev, const void *buf, s64 count, s64 offset) {
    (void)dev;
    (void)buf;
    (void)count;
    (void)offset;
    errno = EROFS;
    return -1;
}

static int part_sync(struct ntfs_device *dev) {
    (void)dev;
    return 0;
}

static int part_stat(struct ntfs_device *dev, struct stat *st) {
    part_dev *pd = dev->d_private;
    memset(st, 0, sizeof(*st));
    st->st_mode = S_IFBLK | 0444;
    st->st_size = (off_t)pd->bytes;
    st->st_blksize = pd->block_size;
    return 0;
}

static int part_ioctl(struct ntfs_device *dev, unsigned long request, void *argp) {
    part_dev *pd = dev->d_private;
    switch (request) {
        case BLKGETSIZE64:
            *(uint64_t *)argp = pd->bytes;
            return 0;
        case BLKGETSIZE:
            *(unsigned long *)argp = (unsigned long)(pd->bytes / 512);
            return 0;
        case BLKSSZGET:
        case BLKBSZGET:
            *(int *)argp = (int)pd->block_size;
            return 0;
        case BLKBSZSET:
            return 0;
        default:
            errno = EOPNOTSUPP;
            return -1;
    }
}

static struct ntfs_device_operations part_ops = {
    .open = part_open,
    .close = part_close,
    .seek = part_seek,
    .read = part_read,
    .write = part_write,
    .pread = part_pread,
    .pwrite = part_pwrite,
    .sync = part_sync,
    .stat = part_stat,
    .ioctl = part_ioctl,
};

static void free_part(part_dev *pd) {
    if (pd == NULL) return;
    for (int i = 0; i < SLOTS; i++) free(pd->cache[i].data);
    free(pd);
}

static const char *mount_error(int err) {
    switch (err) {
        case EINVAL: return "not a valid NTFS volume";
        case EIO: return "the drive could not be read";
        case EPERM: return "Windows left the volume hibernated";
        case EOPNOTSUPP: return "this NTFS version is not supported";
        case ENOMEM: return "out of memory";
        default: return strerror(err);
    }
}

ntfsvol *ntfsvol_mount(ntfsvol_read_fn read, void *ctx, uint32_t block_size,
                       uint64_t start, uint64_t blocks, char *err, size_t err_len) {
    part_dev *pd = calloc(1, sizeof(*pd));
    ntfsvol *vol = calloc(1, sizeof(*vol));
    if (pd == NULL || vol == NULL) {
        snprintf(err, err_len, "out of memory");
        free(pd);
        free(vol);
        return NULL;
    }
    pd->read = read;
    pd->ctx = ctx;
    pd->block_size = block_size;
    pd->start = start;
    pd->bytes = blocks * block_size;

    struct ntfs_device *dev = ntfs_device_alloc("usb", 0, &part_ops, pd);
    if (dev == NULL) {
        snprintf(err, err_len, "out of memory");
        free_part(pd);
        free(vol);
        return NULL;
    }
    ntfs_volume *v = ntfs_device_mount(dev, NTFS_MNT_RDONLY);
    if (v == NULL) {
        int saved = errno;
        snprintf(err, err_len, "%s", mount_error(saved));
        if (NDevOpen(dev)) part_close(dev);
        ntfs_device_free(dev);
        free_part(pd);
        free(vol);
        return NULL;
    }
    pthread_mutex_init(&vol->lock, NULL);
    vol->v = v;
    vol->pd = pd;
    return vol;
}

static void release_file(ntfsvol_file *file) {
    if (file->na != NULL) ntfs_attr_close(file->na);
    if (file->ni != NULL) ntfs_inode_close(file->ni);
    file->na = NULL;
    file->ni = NULL;
}

void ntfsvol_unmount(ntfsvol *vol) {
    if (vol == NULL) return;
    pthread_mutex_lock(&vol->lock);
    for (ntfsvol_file *file = vol->files; file != NULL; file = file->next) {
        release_file(file);
        file->vol = NULL;
    }
    vol->files = NULL;
    ntfs_umount(vol->v, FALSE);
    free_part(vol->pd);
    pthread_mutex_unlock(&vol->lock);
    pthread_mutex_destroy(&vol->lock);
    free(vol);
}

int ntfsvol_get_info(ntfsvol *vol, ntfsvol_info *info) {
    memset(info, 0, sizeof(*info));
    pthread_mutex_lock(&vol->lock);
    ntfs_volume *v = vol->v;
    if (v->vol_name != NULL) snprintf(info->label, sizeof(info->label), "%s", v->vol_name);
    info->size = (uint64_t)v->nr_clusters * v->cluster_size;
    if (ntfs_volume_get_free_space(v) == 0 && v->free_clusters > 0) {
        info->free = (uint64_t)v->free_clusters * v->cluster_size;
    }
    info->dirty = (v->flags & VOLUME_IS_DIRTY) != 0;
    pthread_mutex_unlock(&vol->lock);
    return 0;
}

typedef struct {
    char *name;
    MFT_REF mref;
    unsigned dt_type;
} raw_entry;

typedef struct {
    raw_entry *items;
    size_t count;
    size_t capacity;
    int failed;
} collected;

static int collect(void *dirent, const ntfschar *name, const int name_len, const int name_type,
                   const s64 pos, const MFT_REF mref, const unsigned dt_type) {
    (void)pos;
    collected *c = dirent;
    if (name_type == FILE_NAME_DOS) return 0;
    if (MREF(mref) < FILE_first_user) return 0;
    char *utf8 = NULL;
    if (ntfs_ucstombs(name, name_len, &utf8, 0) < 0 || utf8 == NULL) return 0;
    if (strcmp(utf8, ".") == 0 || strcmp(utf8, "..") == 0) {
        free(utf8);
        return 0;
    }
    if (c->count == c->capacity) {
        size_t grown = c->capacity ? c->capacity * 2 : 64;
        raw_entry *items = realloc(c->items, grown * sizeof(*items));
        if (items == NULL) {
            free(utf8);
            c->failed = ENOMEM;
            return -1;
        }
        c->items = items;
        c->capacity = grown;
    }
    c->items[c->count++] = (raw_entry){utf8, mref, dt_type};
    return 0;
}

static int64_t to_millis(ntfs_time t) {
    struct timespec ts = ntfs2timespec(t);
    return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

static ntfs_inode *resolve(ntfs_volume *v, const char *path) {
    if (path == NULL || path[0] == '\0') path = "/";
    return ntfs_pathname_to_inode(v, NULL, path);
}

int ntfsvol_list(ntfsvol *vol, const char *path, ntfsvol_entry_fn fn, void *ctx) {
    pthread_mutex_lock(&vol->lock);
    ntfs_inode *dir = resolve(vol->v, path);
    if (dir == NULL) {
        int saved = errno;
        pthread_mutex_unlock(&vol->lock);
        errno = saved;
        return -1;
    }
    if (!(dir->mrec->flags & MFT_RECORD_IS_DIRECTORY)) {
        ntfs_inode_close(dir);
        pthread_mutex_unlock(&vol->lock);
        errno = ENOTDIR;
        return -1;
    }

    collected c = {0};
    s64 pos = 0;
    int listed = ntfs_readdir(dir, &pos, &c, collect);
    int saved = errno;
    ntfs_inode_close(dir);
    if (listed != 0 && c.failed == 0) c.failed = saved ? saved : EIO;

    int stopped = 0;
    for (size_t i = 0; i < c.count; i++) {
        raw_entry *raw = &c.items[i];
        if (!stopped && c.failed == 0) {
            ntfsvol_entry entry = {
                .name = raw->name,
                .is_dir = raw->dt_type == NTFS_DT_DIR,
            };
            ntfs_inode *ni = ntfs_inode_open(vol->v, raw->mref);
            if (ni != NULL) {
                entry.is_dir = (ni->mrec->flags & MFT_RECORD_IS_DIRECTORY) != 0;
                entry.size = entry.is_dir || ni->data_size < 0 ? 0 : (uint64_t)ni->data_size;
                entry.mtime_ms = to_millis(ni->last_data_change_time);
                entry.hidden = (ni->flags & (FILE_ATTR_HIDDEN | FILE_ATTR_SYSTEM)) != 0;
                ntfs_inode_close(ni);
            }
            if (fn(ctx, &entry) != 0) stopped = 1;
        }
        free(raw->name);
    }
    free(c.items);
    int failed = c.failed;
    pthread_mutex_unlock(&vol->lock);
    if (failed != 0) {
        errno = failed;
        return -1;
    }
    return 0;
}

ntfsvol_file *ntfsvol_open(ntfsvol *vol, const char *path, uint64_t *size) {
    ntfsvol_file *file = calloc(1, sizeof(*file));
    if (file == NULL) {
        errno = ENOMEM;
        return NULL;
    }
    pthread_mutex_lock(&vol->lock);
    file->ni = resolve(vol->v, path);
    if (file->ni == NULL) goto fail;
    if (file->ni->mrec->flags & MFT_RECORD_IS_DIRECTORY) {
        errno = EISDIR;
        goto fail;
    }
    file->na = ntfs_attr_open(file->ni, AT_DATA, AT_UNNAMED, 0);
    if (file->na == NULL) goto fail;
    *size = file->na->data_size < 0 ? 0 : (uint64_t)file->na->data_size;
    file->vol = vol;
    file->next = vol->files;
    if (vol->files != NULL) vol->files->prev = file;
    vol->files = file;
    pthread_mutex_unlock(&vol->lock);
    return file;

fail: {
        int saved = errno;
        release_file(file);
        pthread_mutex_unlock(&vol->lock);
        free(file);
        errno = saved;
        return NULL;
    }
}

int64_t ntfsvol_read(ntfsvol_file *file, uint64_t offset, void *buf, size_t len) {
    ntfsvol *vol = file->vol;
    if (vol == NULL) {
        errno = ENODEV;
        return -1;
    }
    pthread_mutex_lock(&vol->lock);
    s64 got = file->na == NULL ? -1 : ntfs_attr_pread(file->na, (s64)offset, (s64)len, buf);
    int saved = errno;
    pthread_mutex_unlock(&vol->lock);
    errno = saved;
    return got;
}

void ntfsvol_close(ntfsvol_file *file) {
    if (file == NULL) return;
    ntfsvol *vol = file->vol;
    if (vol != NULL) {
        pthread_mutex_lock(&vol->lock);
        release_file(file);
        if (file->prev != NULL) file->prev->next = file->next;
        else vol->files = file->next;
        if (file->next != NULL) file->next->prev = file->prev;
        pthread_mutex_unlock(&vol->lock);
    }
    free(file);
}
