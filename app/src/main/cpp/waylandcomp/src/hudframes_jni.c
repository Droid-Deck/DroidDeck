#include <jni.h>
#include <stdint.h>
#include <stdlib.h>

#include "hudframes.h"

JNIEXPORT jlong JNICALL
Java_com_droiddeck_launcher_hud_HudFrames_nativeOpen(JNIEnv *env, jclass clazz, jstring path) {
    (void)clazz;
    const char *p = path ? (*env)->GetStringUTFChars(env, path, NULL) : NULL;
    if (!p) return 0;
    struct hud_frames *h = hud_frames_open(p);
    (*env)->ReleaseStringUTFChars(env, path, p);
    return (jlong)(intptr_t)h;
}

JNIEXPORT void JNICALL
Java_com_droiddeck_launcher_hud_HudFrames_nativeClose(JNIEnv *env, jclass clazz, jlong handle) {
    (void)env; (void)clazz;
    hud_frames_close((struct hud_frames *)(intptr_t)handle);
}

JNIEXPORT jint JNICALL
Java_com_droiddeck_launcher_hud_HudFrames_nativePoll(JNIEnv *env, jclass clazz, jlong handle,
                                                     jlongArray frametimes, jlongArray times) {
    (void)clazz;
    struct hud_frames *h = (struct hud_frames *)(intptr_t)handle;
    if (!h || !frametimes || !times) return -1;
    jsize max = (*env)->GetArrayLength(env, frametimes);
    jsize max2 = (*env)->GetArrayLength(env, times);
    if (max2 < max) max = max2;
    if (max <= 0) return -1;
    int64_t *ft = malloc(sizeof(int64_t) * (size_t)max);
    int64_t *tm = malloc(sizeof(int64_t) * (size_t)max);
    int n = ft && tm ? hud_frames_poll(h, ft, tm, max) : -1;
    if (n > 0) {
        (*env)->SetLongArrayRegion(env, frametimes, 0, n, (const jlong *)ft);
        (*env)->SetLongArrayRegion(env, times, 0, n, (const jlong *)tm);
    }
    free(ft);
    free(tm);
    return n;
}

JNIEXPORT jstring JNICALL
Java_com_droiddeck_launcher_hud_HudFrames_nativeInfo(JNIEnv *env, jclass clazz, jlong handle, jintArray info) {
    (void)clazz;
    const struct hud_frames *h = (const struct hud_frames *)(intptr_t)handle;
    if (!h || !info || (*env)->GetArrayLength(env, info) < 5) return NULL;
    int32_t values[5];
    char engine[41];
    if (!hud_frames_info(h, values, engine, sizeof(engine))) return NULL;
    (*env)->SetIntArrayRegion(env, info, 0, 5, (const jint *)values);
    return (*env)->NewStringUTF(env, engine);
}
