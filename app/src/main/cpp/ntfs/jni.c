#include <android/log.h>
#include <errno.h>
#include <jni.h>
#include <linux/usbdevice_fs.h>
#include <pthread.h>
#include <sys/ioctl.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "types.h"
#include "logging.h"

#include "ntfsvol.h"
#include "parttab.h"
#include "usbms.h"

#define TAG "DroidDeckUsbDrive"

static __thread char last_error[256];

static void set_error(const char *fmt, ...) {
    va_list args;
    va_start(args, fmt);
    vsnprintf(last_error, sizeof(last_error), fmt, args);
    va_end(args);
}

typedef struct {
    usbms_dev *usb;
    pthread_mutex_t lock;
    char vendor[9];
    char product[17];
} drive;

typedef struct {
    ntfsvol_file *file;
    uint64_t size;
    uint8_t *buffer;
    size_t capacity;
} open_file;

static int drive_read(void *ctx, uint64_t lba, uint32_t count, void *buf) {
    drive *d = ctx;
    pthread_mutex_lock(&d->lock);
    int result = usbms_read(d->usb, lba, count, buf);
    if (result != 0) __android_log_print(ANDROID_LOG_WARN, TAG, "read lba %llu x%u: %s",
                                         (unsigned long long)lba, count, usbms_last_error(d->usb));
    pthread_mutex_unlock(&d->lock);
    return result;
}

static int log_to_logcat(const char *function, const char *file, int line, u32 level,
                         void *data, const char *format, va_list args) {
    (void)function;
    (void)file;
    (void)line;
    (void)data;
    int priority = level & (NTFS_LOG_LEVEL_ERROR | NTFS_LOG_LEVEL_PERROR | NTFS_LOG_LEVEL_CRITICAL)
        ? ANDROID_LOG_ERROR
        : level & NTFS_LOG_LEVEL_WARNING ? ANDROID_LOG_WARN : ANDROID_LOG_INFO;
    return __android_log_vprint(priority, TAG, format, args);
}

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)vm;
    (void)reserved;
    ntfs_log_set_handler(log_to_logcat);
    return JNI_VERSION_1_6;
}

typedef struct {
    char *data;
    size_t length;
    size_t capacity;
    int failed;
} json;

static void json_raw(json *j, const char *text, size_t length) {
    if (j->failed) return;
    if (j->length + length + 1 > j->capacity) {
        size_t grown = j->capacity ? j->capacity : 1024;
        while (grown < j->length + length + 1) grown *= 2;
        char *data = realloc(j->data, grown);
        if (data == NULL) {
            j->failed = 1;
            return;
        }
        j->data = data;
        j->capacity = grown;
    }
    memcpy(j->data + j->length, text, length);
    j->length += length;
    j->data[j->length] = '\0';
}

static void json_text(json *j, const char *text) {
    json_raw(j, text, strlen(text));
}

static void json_string(json *j, const char *value) {
    json_raw(j, "\"", 1);
    for (const unsigned char *p = (const unsigned char *)value; *p; p++) {
        char escaped[8];
        switch (*p) {
            case '"': json_raw(j, "\\\"", 2); break;
            case '\\': json_raw(j, "\\\\", 2); break;
            case '\n': json_raw(j, "\\n", 2); break;
            case '\r': json_raw(j, "\\r", 2); break;
            case '\t': json_raw(j, "\\t", 2); break;
            default:
                if (*p < 0x20) {
                    snprintf(escaped, sizeof(escaped), "\\u%04x", *p);
                    json_text(j, escaped);
                } else {
                    json_raw(j, (const char *)p, 1);
                }
        }
    }
    json_raw(j, "\"", 1);
}

static void json_number(json *j, long long value) {
    char number[32];
    snprintf(number, sizeof(number), "%lld", value);
    json_text(j, number);
}

static jbyteArray json_finish(JNIEnv *env, json *j) {
    jbyteArray out = NULL;
    if (!j->failed) {
        out = (*env)->NewByteArray(env, (jsize)j->length);
        if (out != NULL) (*env)->SetByteArrayRegion(env, out, 0, (jsize)j->length, (const jbyte *)j->data);
    } else {
        set_error("out of memory");
    }
    free(j->data);
    return out;
}

static char *utf8_path(JNIEnv *env, jbyteArray bytes) {
    jsize length = (*env)->GetArrayLength(env, bytes);
    char *path = malloc((size_t)length + 1);
    if (path == NULL) return NULL;
    (*env)->GetByteArrayRegion(env, bytes, 0, length, (jbyte *)path);
    path[length] = '\0';
    return path;
}

JNIEXPORT jstring JNICALL
Java_com_droiddeck_launcher_usbdrive_UsbDriveNative_lastError(JNIEnv *env, jclass clazz) {
    (void)clazz;
    return (*env)->NewStringUTF(env, last_error);
}

JNIEXPORT jlong JNICALL
Java_com_droiddeck_launcher_usbdrive_UsbDriveNative_openDevice(JNIEnv *env, jclass clazz, jint fd,
                                                              jint iface, jint ep_in, jint ep_out) {
    (void)env;
    (void)clazz;
    drive *d = calloc(1, sizeof(*d));
    if (d == NULL) {
        set_error("out of memory");
        return 0;
    }
    d->usb = usbms_open(fd, iface, (uint8_t)ep_in, (uint8_t)ep_out);
    if (d->usb == NULL) {
        set_error("the drive did not answer as USB mass storage");
        free(d);
        return 0;
    }
    pthread_mutex_init(&d->lock, NULL);
    if (usbms_inquiry(d->usb, d->vendor, d->product) != 0) {
        d->vendor[0] = '\0';
        d->product[0] = '\0';
    }
    __android_log_print(ANDROID_LOG_INFO, TAG, "opened %s %s: %llu blocks of %u bytes", d->vendor, d->product,
                        (unsigned long long)usbms_blocks(d->usb), usbms_block_size(d->usb));
    return (jlong)(intptr_t)d;
}

JNIEXPORT void JNICALL
Java_com_droiddeck_launcher_usbdrive_UsbDriveNative_closeDevice(JNIEnv *env, jclass clazz, jlong handle) {
    (void)env;
    (void)clazz;
    drive *d = (drive *)(intptr_t)handle;
    if (d == NULL) return;
    usbms_close(d->usb);
    pthread_mutex_destroy(&d->lock);
    free(d);
}

/*
 * claimInterface(force = true) disconnected usb-storage/uas from the whole
 * disk, and releasing the interface does not undo that. Reconnecting lets
 * Android probe the disk again, so partitions it can mount come back.
 */
JNIEXPORT jboolean JNICALL
Java_com_droiddeck_launcher_usbdrive_UsbDriveNative_reattachKernelDriver(JNIEnv *env, jclass clazz, jint fd,
                                                                        jint iface) {
    (void)env;
    (void)clazz;
    struct usbdevfs_ioctl command = {.ifno = iface, .ioctl_code = USBDEVFS_CONNECT, .data = NULL};
    if (ioctl(fd, USBDEVFS_IOCTL, &command) < 0) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "reattaching the kernel driver failed: %s", strerror(errno));
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

JNIEXPORT jbyteArray JNICALL
Java_com_droiddeck_launcher_usbdrive_UsbDriveNative_describe(JNIEnv *env, jclass clazz, jlong handle) {
    (void)clazz;
    drive *d = (drive *)(intptr_t)handle;
    parttab_entry parts[PARTTAB_MAX];
    int count = parttab_scan(drive_read, d, usbms_block_size(d->usb), usbms_blocks(d->usb), parts, PARTTAB_MAX);
    if (count < 0) {
        set_error("the partition table could not be read");
        return NULL;
    }
    json j = {0};
    json_text(&j, "{\"vendor\":");
    json_string(&j, d->vendor);
    json_text(&j, ",\"product\":");
    json_string(&j, d->product);
    json_text(&j, ",\"blockSize\":");
    json_number(&j, usbms_block_size(d->usb));
    json_text(&j, ",\"blocks\":");
    json_number(&j, (long long)usbms_blocks(d->usb));
    json_text(&j, ",\"partitions\":[");
    for (int i = 0; i < count; i++) {
        if (i > 0) json_text(&j, ",");
        json_text(&j, "{\"index\":");
        json_number(&j, parts[i].index);
        json_text(&j, ",\"start\":");
        json_number(&j, (long long)parts[i].start);
        json_text(&j, ",\"blocks\":");
        json_number(&j, (long long)parts[i].blocks);
        json_text(&j, ",\"fs\":");
        json_string(&j, parts[i].fs);
        json_text(&j, ",\"label\":");
        json_string(&j, parts[i].label);
        json_text(&j, "}");
    }
    json_text(&j, "]}");
    return json_finish(env, &j);
}

JNIEXPORT jlong JNICALL
Java_com_droiddeck_launcher_usbdrive_UsbDriveNative_mount(JNIEnv *env, jclass clazz, jlong handle,
                                                         jlong start, jlong blocks) {
    (void)env;
    (void)clazz;
    drive *d = (drive *)(intptr_t)handle;
    char err[160];
    ntfsvol *vol = ntfsvol_mount(drive_read, d, usbms_block_size(d->usb), (uint64_t)start, (uint64_t)blocks,
                                 err, sizeof(err));
    if (vol == NULL) {
        set_error("%s", err);
        return 0;
    }
    return (jlong)(intptr_t)vol;
}

JNIEXPORT void JNICALL
Java_com_droiddeck_launcher_usbdrive_UsbDriveNative_unmount(JNIEnv *env, jclass clazz, jlong volume) {
    (void)env;
    (void)clazz;
    ntfsvol_unmount((ntfsvol *)(intptr_t)volume);
}

JNIEXPORT jbyteArray JNICALL
Java_com_droiddeck_launcher_usbdrive_UsbDriveNative_volumeInfo(JNIEnv *env, jclass clazz, jlong volume) {
    (void)clazz;
    ntfsvol_info info;
    ntfsvol_get_info((ntfsvol *)(intptr_t)volume, &info);
    json j = {0};
    json_text(&j, "{\"label\":");
    json_string(&j, info.label);
    json_text(&j, ",\"size\":");
    json_number(&j, (long long)info.size);
    json_text(&j, ",\"free\":");
    json_number(&j, (long long)info.free);
    json_text(&j, ",\"dirty\":");
    json_text(&j, info.dirty ? "true" : "false");
    json_text(&j, "}");
    return json_finish(env, &j);
}

static int append_entry(void *ctx, const ntfsvol_entry *entry) {
    json *j = ctx;
    if (j->length > 1) json_text(j, ",");
    json_text(j, "{\"name\":");
    json_string(j, entry->name);
    json_text(j, ",\"dir\":");
    json_text(j, entry->is_dir ? "true" : "false");
    json_text(j, ",\"hidden\":");
    json_text(j, entry->hidden ? "true" : "false");
    json_text(j, ",\"size\":");
    json_number(j, (long long)entry->size);
    json_text(j, ",\"mtime\":");
    json_number(j, (long long)entry->mtime_ms);
    json_text(j, "}");
    return j->failed;
}

JNIEXPORT jbyteArray JNICALL
Java_com_droiddeck_launcher_usbdrive_UsbDriveNative_list(JNIEnv *env, jclass clazz, jlong volume,
                                                        jbyteArray path_bytes) {
    (void)clazz;
    char *path = utf8_path(env, path_bytes);
    if (path == NULL) {
        set_error("out of memory");
        return NULL;
    }
    json j = {0};
    json_text(&j, "[");
    int listed = ntfsvol_list((ntfsvol *)(intptr_t)volume, path, append_entry, &j);
    int saved = errno;
    free(path);
    if (listed != 0) {
        free(j.data);
        set_error("%s", strerror(saved));
        return NULL;
    }
    json_text(&j, "]");
    return json_finish(env, &j);
}

JNIEXPORT jlong JNICALL
Java_com_droiddeck_launcher_usbdrive_UsbDriveNative_openFile(JNIEnv *env, jclass clazz, jlong volume,
                                                            jbyteArray path_bytes) {
    (void)clazz;
    char *path = utf8_path(env, path_bytes);
    open_file *handle = calloc(1, sizeof(*handle));
    if (path == NULL || handle == NULL) {
        free(path);
        free(handle);
        set_error("out of memory");
        return 0;
    }
    handle->file = ntfsvol_open((ntfsvol *)(intptr_t)volume, path, &handle->size);
    int saved = errno;
    free(path);
    if (handle->file == NULL) {
        free(handle);
        set_error("%s", saved == EACCES ? "the file is encrypted by Windows" : strerror(saved));
        return 0;
    }
    return (jlong)(intptr_t)handle;
}

JNIEXPORT jlong JNICALL
Java_com_droiddeck_launcher_usbdrive_UsbDriveNative_fileSize(JNIEnv *env, jclass clazz, jlong file) {
    (void)env;
    (void)clazz;
    return (jlong)((open_file *)(intptr_t)file)->size;
}

JNIEXPORT jint JNICALL
Java_com_droiddeck_launcher_usbdrive_UsbDriveNative_read(JNIEnv *env, jclass clazz, jlong file, jlong offset,
                                                        jbyteArray buffer, jint start, jint length) {
    (void)clazz;
    open_file *handle = (open_file *)(intptr_t)file;
    if (length <= 0) return 0;
    if ((size_t)length > handle->capacity) {
        uint8_t *grown = realloc(handle->buffer, (size_t)length);
        if (grown == NULL) {
            set_error("out of memory");
            return -1;
        }
        handle->buffer = grown;
        handle->capacity = (size_t)length;
    }
    int64_t got = ntfsvol_read(handle->file, (uint64_t)offset, handle->buffer, (size_t)length);
    if (got < 0) {
        int saved = errno;
        set_error("%s", saved == EACCES ? "the file is encrypted by Windows" : strerror(saved));
        return -1;
    }
    if (got > 0) (*env)->SetByteArrayRegion(env, buffer, start, (jsize)got, (const jbyte *)handle->buffer);
    return (jint)got;
}

JNIEXPORT void JNICALL
Java_com_droiddeck_launcher_usbdrive_UsbDriveNative_closeFile(JNIEnv *env, jclass clazz, jlong file) {
    (void)env;
    (void)clazz;
    open_file *handle = (open_file *)(intptr_t)file;
    if (handle == NULL) return;
    ntfsvol_close(handle->file);
    free(handle->buffer);
    free(handle);
}
