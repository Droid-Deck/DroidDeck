#include <errno.h>
#include <jni.h>
#include <stdatomic.h>
#include <sys/syscall.h>
#include <unistd.h>

#ifndef RENAME_NOREPLACE
#define RENAME_NOREPLACE 1
#endif

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

JNIEXPORT jint JNICALL
Java_com_droiddeck_launcher_session_DiagnosticAtomicFiles_nativeRenameNoReplace(
        JNIEnv *env, jclass clazz, jint olddirfd, jstring oldname, jint newdirfd, jstring newname);

int droiddeck_rename_noreplace(int olddirfd, const char *old_path, int newdirfd, const char *new_path) {
#ifdef SYS_renameat2
    int rc = syscall(SYS_renameat2, olddirfd, old_path, newdirfd, new_path, RENAME_NOREPLACE);
    return rc == 0 ? 0 : errno;
#else
    (void)olddirfd;
    (void)old_path;
    (void)newdirfd;
    (void)new_path;
    return ENOSYS;
#endif
}

JNIEXPORT jint JNICALL
Java_com_droiddeck_launcher_session_DiagnosticAtomicFiles_nativeRenameNoReplace(
        JNIEnv *env, jclass clazz, jint olddirfd, jstring oldname, jint newdirfd, jstring newname) {
    (void)clazz;
    const char *old_path = (*env)->GetStringUTFChars(env, oldname, NULL);
    if (!old_path) return ENOMEM;
    const char *new_path = (*env)->GetStringUTFChars(env, newname, NULL);
    if (!new_path) {
        (*env)->ReleaseStringUTFChars(env, oldname, old_path);
        return ENOMEM;
    }
    int result = droiddeck_rename_noreplace(olddirfd, old_path, newdirfd, new_path);
    (*env)->ReleaseStringUTFChars(env, newname, new_path);
    (*env)->ReleaseStringUTFChars(env, oldname, old_path);
    return result;
}
