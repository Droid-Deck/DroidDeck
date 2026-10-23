#include "usbms.h"

#include <errno.h>
#include <linux/usbdevice_fs.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <time.h>

#define CBW_SIGNATURE 0x43425355u
#define CSW_SIGNATURE 0x53425355u
#define CBW_LEN 31
#define CSW_LEN 13

/*
 * usbfs rejects a single bulk URB larger than 16 KiB on the kernels Android 9
 * shipped, so the data phase of one SCSI command is split across several
 * ioctls; the device sees one uninterrupted byte stream either way.
 */
#define BULK_CHUNK 16384
#define MAX_PHASE 65536

#define TIMEOUT_MS 5000

struct usbms_dev {
    int fd;
    int iface;
    uint8_t ep_in;
    uint8_t ep_out;
    uint32_t tag;
    uint32_t block_size;
    uint64_t blocks;
    char err[256];
};

static void fail(usbms_dev *dev, const char *fmt, ...) {
    va_list args;
    va_start(args, fmt);
    vsnprintf(dev->err, sizeof(dev->err), fmt, args);
    va_end(args);
}

static void put_le32(uint8_t *p, uint32_t v) {
    p[0] = (uint8_t)v;
    p[1] = (uint8_t)(v >> 8);
    p[2] = (uint8_t)(v >> 16);
    p[3] = (uint8_t)(v >> 24);
}

static uint32_t get_le32(const uint8_t *p) {
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}

static uint32_t get_be32(const uint8_t *p) {
    return ((uint32_t)p[0] << 24) | ((uint32_t)p[1] << 16) | ((uint32_t)p[2] << 8) | (uint32_t)p[3];
}

static uint64_t get_be64(const uint8_t *p) {
    return ((uint64_t)get_be32(p) << 32) | get_be32(p + 4);
}

static void put_be16(uint8_t *p, uint16_t v) {
    p[0] = (uint8_t)(v >> 8);
    p[1] = (uint8_t)v;
}

static void put_be32(uint8_t *p, uint32_t v) {
    p[0] = (uint8_t)(v >> 24);
    p[1] = (uint8_t)(v >> 16);
    p[2] = (uint8_t)(v >> 8);
    p[3] = (uint8_t)v;
}

static void put_be64(uint8_t *p, uint64_t v) {
    put_be32(p, (uint32_t)(v >> 32));
    put_be32(p + 4, (uint32_t)v);
}

static void nap(long ms) {
    struct timespec ts = {ms / 1000, (ms % 1000) * 1000000L};
    nanosleep(&ts, NULL);
}

static int bulk(usbms_dev *dev, uint8_t ep, void *buf, int len) {
    uint8_t *at = buf;
    int done = 0;
    while (done < len) {
        int want = len - done;
        if (want > BULK_CHUNK) want = BULK_CHUNK;
        struct usbdevfs_bulktransfer transfer = {
            .ep = ep,
            .len = (unsigned int)want,
            .timeout = TIMEOUT_MS,
            .data = at + done,
        };
        int moved = ioctl(dev->fd, USBDEVFS_BULK, &transfer);
        if (moved < 0) return -errno;
        done += moved;
        if (moved < want) break;
    }
    return done;
}

static void clear_halt(usbms_dev *dev, uint8_t ep) {
    unsigned int endpoint = ep;
    ioctl(dev->fd, USBDEVFS_CLEAR_HALT, &endpoint);
}

static void reset_recovery(usbms_dev *dev) {
    struct usbdevfs_ctrltransfer reset = {
        .bRequestType = 0x21,
        .bRequest = 0xFF,
        .wValue = 0,
        .wIndex = (uint16_t)dev->iface,
        .wLength = 0,
        .timeout = TIMEOUT_MS,
        .data = NULL,
    };
    ioctl(dev->fd, USBDEVFS_CONTROL, &reset);
    clear_halt(dev, dev->ep_in);
    clear_halt(dev, dev->ep_out);
}

/* Returns 0 on success, 1 when the device reported a check condition, -1 on a transport error. */
static int transact(usbms_dev *dev, const uint8_t *cdb, int cdb_len, void *data, int data_len, int data_in) {
    uint8_t cbw[CBW_LEN];
    memset(cbw, 0, sizeof(cbw));
    put_le32(cbw, CBW_SIGNATURE);
    put_le32(cbw + 4, ++dev->tag);
    put_le32(cbw + 8, (uint32_t)data_len);
    cbw[12] = data_in ? 0x80 : 0x00;
    cbw[13] = 0;
    cbw[14] = (uint8_t)cdb_len;
    memcpy(cbw + 15, cdb, (size_t)cdb_len);

    int sent = bulk(dev, dev->ep_out, cbw, CBW_LEN);
    if (sent != CBW_LEN) {
        if (sent == -EPIPE) clear_halt(dev, dev->ep_out);
        fail(dev, "command transport failed (%d)", sent);
        reset_recovery(dev);
        return -1;
    }

    if (data_len > 0) {
        int moved = bulk(dev, data_in ? dev->ep_in : dev->ep_out, data, data_len);
        if (moved < 0) {
            if (moved == -EPIPE) {
                clear_halt(dev, data_in ? dev->ep_in : dev->ep_out);
            } else {
                fail(dev, "data transport failed (%d)", moved);
                reset_recovery(dev);
                return -1;
            }
        }
    }

    uint8_t csw[CSW_LEN];
    int got = bulk(dev, dev->ep_in, csw, CSW_LEN);
    if (got == -EPIPE) {
        clear_halt(dev, dev->ep_in);
        got = bulk(dev, dev->ep_in, csw, CSW_LEN);
    }
    if (got != CSW_LEN || get_le32(csw) != CSW_SIGNATURE) {
        fail(dev, "status transport failed (%d)", got);
        reset_recovery(dev);
        return -1;
    }
    if (get_le32(csw + 4) != dev->tag) {
        fail(dev, "status tag mismatch");
        reset_recovery(dev);
        return -1;
    }
    if (csw[12] == 2) {
        fail(dev, "device reported a phase error");
        reset_recovery(dev);
        return -1;
    }
    return csw[12] == 0 ? 0 : 1;
}

/* Fills sense[18]; sense key is sense[2] & 0x0F, ASC/ASCQ are sense[12]/sense[13]. */
static int request_sense(usbms_dev *dev, uint8_t *sense) {
    uint8_t cdb[6] = {0x03, 0, 0, 0, 18, 0};
    memset(sense, 0, 18);
    return transact(dev, cdb, sizeof(cdb), sense, 18, 1);
}

static int command(usbms_dev *dev, const uint8_t *cdb, int cdb_len, void *data, int data_len, int data_in) {
    for (int attempt = 0; attempt < 5; attempt++) {
        int status = transact(dev, cdb, cdb_len, data, data_len, data_in);
        if (status == 0) return 0;
        if (status < 0) return -1;

        uint8_t sense[18];
        if (request_sense(dev, sense) != 0) {
            fail(dev, "command 0x%02x failed and sense could not be read", cdb[0]);
            return -1;
        }
        uint8_t key = sense[2] & 0x0F;
        uint8_t asc = sense[12];
        /* Unit attention follows every plug-in and media change; not ready with
         * "becoming ready" means the drive is still spinning up. */
        if (key == 0x06 || (key == 0x02 && asc == 0x04)) {
            nap(300);
            continue;
        }
        fail(dev, "command 0x%02x failed (sense key 0x%02x asc 0x%02x ascq 0x%02x)", cdb[0], key, asc, sense[13]);
        return -1;
    }
    fail(dev, "command 0x%02x kept reporting it was not ready", cdb[0]);
    return -1;
}

static int unit_ready(usbms_dev *dev) {
    uint8_t cdb[6] = {0x00, 0, 0, 0, 0, 0};
    return command(dev, cdb, sizeof(cdb), NULL, 0, 1);
}

int usbms_inquiry(usbms_dev *dev, char vendor[9], char product[17]) {
    uint8_t cdb[6] = {0x12, 0, 0, 0, 36, 0};
    uint8_t data[36];
    memset(data, 0, sizeof(data));
    if (command(dev, cdb, sizeof(cdb), data, sizeof(data), 1) != 0) return -1;
    memcpy(vendor, data + 8, 8);
    vendor[8] = '\0';
    memcpy(product, data + 16, 16);
    product[16] = '\0';
    for (int i = 7; i >= 0 && vendor[i] == ' '; i--) vendor[i] = '\0';
    for (int i = 15; i >= 0 && product[i] == ' '; i--) product[i] = '\0';
    return 0;
}

int usbms_capacity(usbms_dev *dev, uint64_t *blocks, uint32_t *block_size) {
    uint8_t cdb10[10] = {0x25, 0, 0, 0, 0, 0, 0, 0, 0, 0};
    uint8_t data10[8];
    if (command(dev, cdb10, sizeof(cdb10), data10, sizeof(data10), 1) != 0) return -1;
    uint32_t last = get_be32(data10);
    uint32_t size = get_be32(data10 + 4);

    /* READ CAPACITY(10) saturates at 2 TiB with 512-byte blocks. */
    if (last == 0xFFFFFFFFu) {
        uint8_t cdb16[16];
        memset(cdb16, 0, sizeof(cdb16));
        cdb16[0] = 0x9E;
        cdb16[1] = 0x10;
        put_be32(cdb16 + 10, 32);
        uint8_t data16[32];
        if (command(dev, cdb16, sizeof(cdb16), data16, sizeof(data16), 1) != 0) return -1;
        *blocks = get_be64(data16) + 1;
        *block_size = get_be32(data16 + 8);
    } else {
        *blocks = (uint64_t)last + 1;
        *block_size = size;
    }
    if (*block_size < 512 || *block_size > 4096 || (*block_size & (*block_size - 1)) != 0) {
        fail(dev, "unsupported logical block size %u", *block_size);
        return -1;
    }
    return 0;
}

static int transfer_blocks(usbms_dev *dev, uint64_t lba, uint32_t count, void *buf, int writing) {
    uint8_t *at = buf;
    uint32_t per_command = MAX_PHASE / dev->block_size;
    if (per_command == 0) per_command = 1;

    while (count > 0) {
        uint32_t now = count < per_command ? count : per_command;
        int data_len = (int)(now * dev->block_size);
        uint8_t cdb[16];
        int cdb_len;
        memset(cdb, 0, sizeof(cdb));
        if (lba > 0xFFFFFFFFu || now > 0xFFFFu) {
            cdb[0] = writing ? 0x8A : 0x88;
            put_be64(cdb + 2, lba);
            put_be32(cdb + 10, now);
            cdb_len = 16;
        } else {
            cdb[0] = writing ? 0x2A : 0x28;
            put_be32(cdb + 2, (uint32_t)lba);
            put_be16(cdb + 7, (uint16_t)now);
            cdb_len = 10;
        }
        if (command(dev, cdb, cdb_len, at, data_len, !writing) != 0) return -1;
        at += data_len;
        lba += now;
        count -= now;
    }
    return 0;
}

int usbms_read(usbms_dev *dev, uint64_t lba, uint32_t count, void *buf) {
    return transfer_blocks(dev, lba, count, buf, 0);
}

int usbms_write(usbms_dev *dev, uint64_t lba, uint32_t count, const void *buf) {
    return transfer_blocks(dev, lba, count, (void *)buf, 1);
}

usbms_dev *usbms_open(int fd, int iface, uint8_t ep_in, uint8_t ep_out) {
    usbms_dev *dev = calloc(1, sizeof(*dev));
    if (dev == NULL) return NULL;
    dev->fd = fd;
    dev->iface = iface;
    dev->ep_in = ep_in;
    dev->ep_out = ep_out;
    dev->block_size = 512;

    for (int attempt = 0; attempt < 10; attempt++) {
        if (unit_ready(dev) == 0) break;
        if (attempt == 9) {
            fail(dev, "drive never became ready");
            free(dev);
            return NULL;
        }
        nap(300);
    }
    if (usbms_capacity(dev, &dev->blocks, &dev->block_size) != 0) {
        free(dev);
        return NULL;
    }
    return dev;
}

void usbms_close(usbms_dev *dev) {
    free(dev);
}

uint32_t usbms_block_size(const usbms_dev *dev) {
    return dev->block_size;
}

uint64_t usbms_blocks(const usbms_dev *dev) {
    return dev->blocks;
}

const char *usbms_last_error(const usbms_dev *dev) {
    return dev->err[0] ? dev->err : "no error recorded";
}
