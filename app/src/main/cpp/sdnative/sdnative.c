#include <errno.h>
#include <jni.h>
#include <limits.h>
#include <linux/futex.h>
#include <stdatomic.h>
#include <stdint.h>
#include <sys/syscall.h>
#include <unistd.h>

JNIEXPORT void JNICALL
Java_com_droiddeck_launcher_input_FakeInputWriter_nativeStoreFence(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    atomic_thread_fence(memory_order_release);
}

JNIEXPORT void JNICALL
Java_com_droiddeck_launcher_input_FakeInputWriter_nativeWake(JNIEnv *env, jclass clazz, jobject buffer, jint offset) {
    (void)clazz;
    uint8_t *base = (*env)->GetDirectBufferAddress(env, buffer);
    if (base) syscall(SYS_futex, base + offset, FUTEX_WAKE, INT_MAX, NULL, NULL, 0);
}

JNIEXPORT jint JNICALL
Java_com_droiddeck_launcher_session_ThreadSignals_tgkill(JNIEnv *env, jclass clazz, jint tgid, jint tid, jint signal) {
    (void)env;
    (void)clazz;
    return syscall(SYS_tgkill, tgid, tid, signal) == 0 ? 0 : errno;
}
