#include <errno.h>
#include <jni.h>
#include <stdatomic.h>
#include <sys/syscall.h>
#include <unistd.h>

JNIEXPORT void JNICALL
Java_com_droiddeck_launcher_input_FakeInputWriter_nativeStoreFence(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    atomic_thread_fence(memory_order_release);
}

JNIEXPORT jint JNICALL
Java_com_droiddeck_launcher_session_ThreadSignals_tgkill(JNIEnv *env, jclass clazz, jint tgid, jint tid, jint signal) {
    (void)env;
    (void)clazz;
    return syscall(SYS_tgkill, tgid, tid, signal) == 0 ? 0 : errno;
}
