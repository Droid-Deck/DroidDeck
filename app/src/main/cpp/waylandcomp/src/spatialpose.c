#define _POSIX_C_SOURCE 200809L
#include "spatialpose_shm.h"
#include <jni.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdatomic.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

static pthread_mutex_t spatial_lock = PTHREAD_MUTEX_INITIALIZER;
static struct droiddeck_spatialpose *shared;

JNIEXPORT jboolean JNICALL Java_com_droiddeck_launcher_wayland_WaylandCompositor_nativeSetSpatialFile(
        JNIEnv *env, jclass clazz, jstring path) {
    (void)clazz;
    pthread_mutex_lock(&spatial_lock);
    if (!shared && path) {
        const char *name = (*env)->GetStringUTFChars(env, path, NULL);
        if (name) {
            int fd = open(name, O_RDWR | O_CREAT | O_CLOEXEC, 0600);
            (*env)->ReleaseStringUTFChars(env, path, name);
            if (fd >= 0) {
                if (ftruncate(fd, sizeof(*shared)) == 0) {
                    void *p = mmap(NULL, sizeof(*shared), PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
                    if (p != MAP_FAILED) {
                        shared = p;
                        memset(shared, 0, sizeof(*shared));
                        shared->magic = DROIDDECK_SPATIALPOSE_MAGIC;
                        shared->version = DROIDDECK_SPATIALPOSE_VERSION;
                        shared->size = sizeof(*shared);
                    }
                }
                close(fd);
            }
        }
    }
    jboolean ready = shared != NULL;
    pthread_mutex_unlock(&spatial_lock);
    return ready;
}

JNIEXPORT void JNICALL Java_com_droiddeck_launcher_wayland_WaylandCompositor_nativePublishSpatial(
        JNIEnv *env, jclass clazz, jobject buffer) {
    (void)clazz;
    if (!buffer || (*env)->GetDirectBufferCapacity(env, buffer) != sizeof(*shared)) return;
    const struct droiddeck_spatialpose *src = (*env)->GetDirectBufferAddress(env, buffer);
    if (!src || src->magic != DROIDDECK_SPATIALPOSE_MAGIC || src->version != DROIDDECK_SPATIALPOSE_VERSION ||
        src->size != sizeof(*shared)) return;
    pthread_mutex_lock(&spatial_lock);
    if (shared) {
        unsigned seq = __atomic_load_n(&shared->seq, __ATOMIC_RELAXED);
        __atomic_store_n(&shared->seq, seq | 1u, __ATOMIC_RELAXED);
        __atomic_thread_fence(__ATOMIC_RELEASE);
        memcpy((char *)shared + 16, (const char *)src + 16, sizeof(*shared) - 16);
        __atomic_store_n(&shared->seq, (seq | 1u) + 1u, __ATOMIC_RELEASE);
    }
    pthread_mutex_unlock(&spatial_lock);
}
