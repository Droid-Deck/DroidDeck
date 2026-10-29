#include <jni.h>
#include <errno.h>
#include <stdbool.h>
#include <stdint.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/resource.h>
#include <android/log.h>
#include <android/native_window_jni.h>
#include <android/hardware_buffer_jni.h>
#include "vk_present.h"
#include "droiddeck_ext.h"
#include "ahb_swapchain.h"
#include "sc_layer.h"
#include "effects_chain.h"
#include "cardboard.h"
#include "droiddeck_color.h"
#include "gpu_clock_pin.h"

extern int droiddeck_wayland_run(void);
extern void droiddeck_wayland_set_scale_mode(int mode, int alignment);
extern void droiddeck_wayland_send_pointer(int action, int x, int y);
extern void droiddeck_wayland_send_touch(int action, int pointer_id, int x, int y);
extern int  droiddeck_cursor_snapshot(int *out, int cap);
extern int  droiddeck_cursor_serial(void);
extern int droiddeck_hud_frame_times(float *out, int len, int64_t *total);
extern void droiddeck_wayland_send_key(int evdev, int state);
extern void droiddeck_wayland_send_scene_input(int type, int a, int b);
extern void droiddeck_wayland_vsync(int64_t frame_time_ns);
extern void droiddeck_stream_set(int on);
extern void droiddeck_stream_frame(AHardwareBuffer *ahb);
extern unsigned droiddeck_stream_frames(void);
extern void droiddeck_wayland_output_resized(void);
extern void droiddeck_wayland_set_output_refresh_rate(int mhz);
extern volatile int g_fps_limit;
extern volatile int g_release_unpaced;
extern volatile int g_render_on_commit;
extern volatile int g_hide_shell;
extern volatile int g_zero_copy;
extern volatile unsigned g_zero_copy_last;
extern volatile int g_ubwc;
extern volatile int g_no_render_node;
extern volatile int g_output_w, g_output_h;

#define TAG "DroidDeckWayland"

static JavaVM *g_jvm;
static volatile pid_t g_comp_tid;
static jclass g_compositor_cls;
static jmethodID g_on_first_frame;
static jmethodID g_on_game_surface;
static jmethodID g_on_game_frame;
static jmethodID g_on_game_program;
static jmethodID g_on_pointer_lock;
static jmethodID g_on_clipboard;
static jmethodID g_on_text_input;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    g_jvm = vm;
    JNIEnv *env;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) == JNI_OK) {
        jclass c = (*env)->FindClass(env, "com/droiddeck/launcher/wayland/WaylandCompositor");
        if (c) {
            g_compositor_cls = (*env)->NewGlobalRef(env, c);
            g_on_first_frame = (*env)->GetStaticMethodID(env, g_compositor_cls,
                                                         "onFirstFramePresented", "()V");
            g_on_game_surface = (*env)->GetStaticMethodID(env, g_compositor_cls, "onGameSurface",
                                                          "(Ljava/lang/String;Ljava/lang/String;)V");
            g_on_game_frame = (*env)->GetStaticMethodID(env, g_compositor_cls, "onGameFrame", "()V");
            g_on_game_program = (*env)->GetStaticMethodID(env, g_compositor_cls, "onGameProgram",
                                                          "(ILjava/lang/String;)V");
            if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); g_on_game_program = NULL; }
            g_on_pointer_lock = (*env)->GetStaticMethodID(env, g_compositor_cls, "onPointerLock", "(ZII)V");
            g_on_clipboard = (*env)->GetStaticMethodID(env, g_compositor_cls, "onClipboardText", "([B)V");
            g_on_text_input = (*env)->GetStaticMethodID(env, g_compositor_cls, "onTextInput",
                                                        "(ZLjava/lang/String;IIII)V");
        }
    }
    return JNI_VERSION_1_6;
}

void droiddeck_on_first_frame(void) {
    if (!g_jvm || !g_compositor_cls || !g_on_first_frame) return;
    JNIEnv *env = NULL;
    int attached = 0;
    if ((*g_jvm)->GetEnv(g_jvm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        if ((*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL) != JNI_OK) return;
        attached = 1;
    }
    (*env)->CallStaticVoidMethod(env, g_compositor_cls, g_on_first_frame);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    if (attached) (*g_jvm)->DetachCurrentThread(g_jvm);
    __android_log_print(ANDROID_LOG_INFO, TAG, "first client frame presented -> notified app");
}

static __thread JNIEnv *t_env;
static __thread int t_attached;

static JNIEnv *thread_env(void) {
    if (t_env) return t_env;
    if (!g_jvm) return NULL;
    JNIEnv *env = NULL;
    if ((*g_jvm)->GetEnv(g_jvm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        if ((*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL) != JNI_OK) return NULL;
        t_attached = 1;
    }
    return t_env = env;
}

void droiddeck_on_game_surface(const char *window, const char *gpu) {
    JNIEnv *env;
    if (!g_compositor_cls || !g_on_game_surface || !(env = thread_env())) return;
    jstring jw = window ? (*env)->NewStringUTF(env, window) : NULL;
    jstring jg = gpu ? (*env)->NewStringUTF(env, gpu) : NULL;
    (*env)->CallStaticVoidMethod(env, g_compositor_cls, g_on_game_surface, jw, jg);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    if (jw) (*env)->DeleteLocalRef(env, jw);
    if (jg) (*env)->DeleteLocalRef(env, jg);
}

void droiddeck_on_game_program(int pid, const char *program) {
    JNIEnv *env;
    if (!g_compositor_cls || !g_on_game_program || !(env = thread_env())) return;
    jstring jp = (*env)->NewStringUTF(env, program ? program : "");
    (*env)->CallStaticVoidMethod(env, g_compositor_cls, g_on_game_program, (jint)pid, jp);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    if (jp) (*env)->DeleteLocalRef(env, jp);
}

void droiddeck_on_game_frame(void) {
    JNIEnv *env;
    if (!g_compositor_cls || !g_on_game_frame || !(env = thread_env())) return;
    (*env)->CallStaticVoidMethod(env, g_compositor_cls, g_on_game_frame);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
}

void droiddeck_on_pointer_lock(int locked, int x, int y) {
    JNIEnv *env;
    if (!g_compositor_cls || !g_on_pointer_lock || !(env = thread_env())) return;
    (*env)->CallStaticVoidMethod(env, g_compositor_cls, g_on_pointer_lock, (jboolean)(locked != 0), (jint)x, (jint)y);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
}

void droiddeck_on_clipboard_text(const char *utf8, int len) {
    JNIEnv *env;
    if (!g_compositor_cls || !g_on_clipboard || !(env = thread_env())) return;
    jbyteArray arr = (*env)->NewByteArray(env, len);
    if (!arr) { (*env)->ExceptionClear(env); return; }
    (*env)->SetByteArrayRegion(env, arr, 0, len, (const jbyte *)utf8);
    (*env)->CallStaticVoidMethod(env, g_compositor_cls, g_on_clipboard, arr);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    (*env)->DeleteLocalRef(env, arr);
}

void droiddeck_on_text_input(int enabled, const char *program, int x, int y, int w, int h) {
    JNIEnv *env;
    if (!g_compositor_cls || !g_on_text_input || !(env = thread_env())) return;
    jstring jp = (*env)->NewStringUTF(env, program ? program : "");
    (*env)->CallStaticVoidMethod(env, g_compositor_cls, g_on_text_input, (jboolean)(enabled != 0), jp,
                                 (jint)x, (jint)y, (jint)w, (jint)h);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    if (jp) (*env)->DeleteLocalRef(env, jp);
}

#define COMPOSITOR_NICE (-8)

static void *comp_thread(void *arg) {
    (void)arg;
    __android_log_print(ANDROID_LOG_INFO, TAG, "compositor thread starting");
    pthread_setname_np(pthread_self(), "wl-compositor");
    const id_t tid = (id_t)gettid();
    g_comp_tid = (pid_t)tid;
    errno = 0;
    const int before = getpriority(PRIO_PROCESS, tid);
    int refused = 0;
    if (before > COMPOSITOR_NICE && setpriority(PRIO_PROCESS, tid, COMPOSITOR_NICE) != 0) refused = errno ? errno : -1;
    const int after = getpriority(PRIO_PROCESS, tid);
    if (refused)
        droiddeck_log("perf", "compositor thread %d \"wl-compositor\": stays at nice %d, a higher priority was refused (%s)",
                   (int)tid, after, refused > 0 ? strerror(refused) : "?");
    else
        droiddeck_log("perf", "compositor thread %d \"wl-compositor\": nice %d -> %d", (int)tid, before, after);
    droiddeck_wayland_run();
    __android_log_print(ANDROID_LOG_INFO, TAG, "compositor thread exited");
    if (t_attached) (*g_jvm)->DetachCurrentThread(g_jvm);
    t_env = NULL; t_attached = 0;
    return NULL;
}

static void set_runtime_dir(JNIEnv *env, jstring xdgRuntimeDir) {
    if (!xdgRuntimeDir) return;
    const char *dir = (*env)->GetStringUTFChars(env, xdgRuntimeDir, NULL);
    if (dir) {
        setenv("XDG_RUNTIME_DIR", dir, 1);
        (*env)->ReleaseStringUTFChars(env, xdgRuntimeDir, dir);
    }
}

static void start_thread(void) {
    pthread_t t;
    if (pthread_create(&t, NULL, comp_thread, NULL) == 0)
        pthread_detach(t);
    else
        __android_log_print(ANDROID_LOG_ERROR, TAG, "pthread_create failed");
}

JNIEXPORT jint JNICALL
Java_com_droiddeck_launcher_wayland_WaylandCompositor_nativeCompositorTid(JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
    return (jint)g_comp_tid;
}

JNIEXPORT void JNICALL
Java_com_droiddeck_launcher_wayland_WaylandCompositor_nativeStart(JNIEnv *env, jclass clazz,
                                                             jstring xdgRuntimeDir) {
    set_runtime_dir(env, xdgRuntimeDir);
    start_thread();
}

static char *dup_jstr(JNIEnv *env, jstring s) {
    if (!s) return NULL;
    const char *c = (*env)->GetStringUTFChars(env, s, NULL);
    char *out = c ? strdup(c) : NULL;
    if (c) (*env)->ReleaseStringUTFChars(env, s, c);
    return out;
}

JNIEXPORT void JNICALL
Java_com_droiddeck_launcher_wayland_WaylandCompositor_nativeStartWithSurface(
        JNIEnv *env, jclass clazz, jobject surface, jstring xdgRuntimeDir,
        jstring driverPath, jstring libraryName, jstring nativeLibDir) {
    set_runtime_dir(env, xdgRuntimeDir);
    char *dp = dup_jstr(env, driverPath);
    char *ln = dup_jstr(env, libraryName);
    char *nl = dup_jstr(env, nativeLibDir);
    vk_present_set_driver(dp, ln, nl);
    free(dp); free(ln); free(nl);
    if (surface) {
        ANativeWindow *win = ANativeWindow_fromSurface(env, surface);
        vk_present_set_window(win);
        __android_log_print(ANDROID_LOG_INFO, TAG, "output window bound (%p)", (void *)win);
    }
    start_thread();
}

JNIEXPORT void JNICALL
Java_com_droiddeck_launcher_wayland_WaylandCompositor_nativeSendPointer(
        JNIEnv *env, jclass clazz, jint action, jint x, jint y) {
    droiddeck_wayland_send_pointer(action, x, y);
}

JNIEXPORT void JNICALL
Java_com_droiddeck_launcher_wayland_WaylandCompositor_nativeSendTouch(
        JNIEnv *env, jclass clazz, jint action, jint pointer_id, jint x, jint y) {
    droiddeck_wayland_send_touch(action, pointer_id, x, y);
}

JNIEXPORT jint JNICALL
Java_com_droiddeck_launcher_wayland_WaylandCompositor_nativeCursorSnapshot(
        JNIEnv *env, jclass clazz, jintArray out) {
    if (!out) return 0;
    jint cap = (*env)->GetArrayLength(env, out);
    jint *buf = (*env)->GetIntArrayElements(env, out, NULL);
    if (!buf) return 0;
    int n = droiddeck_cursor_snapshot((int *)buf, (int)cap);
    (*env)->ReleaseIntArrayElements(env, out, buf, 0);
    return n;
}

JNIEXPORT jlong JNICALL
Java_com_droiddeck_launcher_wayland_WaylandCompositor_nativeHudFrameTimes(JNIEnv *env, jclass clazz, jfloatArray out) {
    (void)clazz;
    if (!out) return 0;
    jint cap = (*env)->GetArrayLength(env, out);
    float *buf = cap > 0 ? malloc(sizeof(float) * (size_t)cap) : NULL;
    if (!buf) return 0;
    int64_t total = 0;
    int n = droiddeck_hud_frame_times(buf, cap, &total);
    if (n > 0) (*env)->SetFloatArrayRegion(env, out, 0, n, buf);
    free(buf);
    return (jlong)total;
}

JNIEXPORT jint JNICALL
Java_com_droiddeck_launcher_wayland_WaylandCompositor_nativeCursorSerial(JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
    return droiddeck_cursor_serial();
}

JNIEXPORT void JNICALL
Java_com_droiddeck_launcher_wayland_WaylandCompositor_nativeSendKey(
        JNIEnv *env, jclass clazz, jint evdev, jint state) {
    droiddeck_wayland_send_key(evdev, state);
}

JNIEXPORT void JNICALL
Java_com_droiddeck_launcher_wayland_WaylandCompositor_nativeSendSceneInput(
        JNIEnv *env, jclass clazz, jint type, jint a, jint b) {
    droiddeck_wayland_send_scene_input(type, a, b);
}

JNIEXPORT void JNICALL
Java_com_droiddeck_launcher_wayland_WaylandCompositor_nativeVsync(JNIEnv *env, jclass clazz, jlong frameTimeNanos) {
    droiddeck_wayland_vsync((int64_t)frameTimeNanos);
}

JNIEXPORT void JNICALL
Java_com_droiddeck_launcher_wayland_WaylandCompositor_nativeSetHideShell(JNIEnv *env, jclass clazz, jboolean hide) {
    g_hide_shell = hide ? 1 : 0;
}

JNIEXPORT void JNICALL
Java_com_droiddeck_launcher_wayland_WaylandCompositor_nativeSetZeroCopy(JNIEnv *env, jclass clazz, jboolean on) {
    int live = droiddeck_get_display() != NULL;
    if (!live) g_zero_copy = on ? 1 : 0;
    droiddeck_host_zero_copy(on ? 1 : 0, live);
    __android_log_print(ANDROID_LOG_INFO, TAG, "zero-copy layer mode %s%s", on ? "on" : "off", live ? " (live)" : "");
}

JNIEXPORT jint JNICALL
Java_com_droiddeck_launcher_wayland_WaylandCompositor_nativeZeroCopyLastFrameAgeMs(JNIEnv *env, jclass clazz) {
    return (jint)ahb_swapchain_last_frame_age_ms();
}

JNIEXPORT jint JNICALL
Java_com_droiddeck_launcher_wayland_WaylandCompositor_nativeZeroCopyFrames(JNIEnv *env, jclass clazz) {
    return (jint)g_zero_copy_last;
}

JNIEXPORT void JNICALL
Java_com_droiddeck_launcher_wayland_WaylandCompositor_nativeSetUbwc(JNIEnv *env, jclass clazz, jboolean on) {
    g_ubwc = on ? 1 : 0;
    __android_log_print(ANDROID_LOG_INFO, TAG, "compressed (UBWC) game buffers %s", on ? "on" : "off");
}

JNIEXPORT void JNICALL
Java_com_droiddeck_launcher_wayland_WaylandCompositor_nativeSetNoRenderNode(JNIEnv *env, jclass clazz, jboolean on) {
    g_no_render_node = on ? 1 : 0;
    if (on) __android_log_print(ANDROID_LOG_INFO, TAG, "debug: advertising no DRM device (main device 0:0)");
}

JNIEXPORT void JNICALL
Java_com_droiddeck_launcher_wayland_WaylandCompositor_nativeSetLayerFrameRate(JNIEnv *env, jclass clazz, jfloat hz) {
    (void)env; (void)clazz;
    sc_layer_set_frame_rate((float)hz);
}

JNIEXPORT void JNICALL
Java_com_droiddeck_launcher_wayland_WaylandCompositor_nativeSetOutputRefreshRate(JNIEnv *env, jclass clazz, jfloat hz) {
    droiddeck_wayland_set_output_refresh_rate(hz > 1.0f ? (int)(hz * 1000.0f + 0.5f) : 0);
}

extern void vkp_reset_first_frame(void);
JNIEXPORT void JNICALL
Java_com_droiddeck_launcher_wayland_WaylandCompositor_nativeResetFirstFrame(JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
    vkp_reset_first_frame();
}

JNIEXPORT void JNICALL
Java_com_droiddeck_launcher_wayland_WaylandCompositor_nativeSetOutputSize(JNIEnv *env, jclass clazz, jint w, jint h) {
    g_output_w = w > 0 ? w : 0;
    g_output_h = h > 0 ? h : 0;
    vkp_cardboard_set_render_height(h);
}

JNIEXPORT void JNICALL
Java_com_droiddeck_launcher_wayland_WaylandCompositor_nativeResizeOutput(JNIEnv *env, jclass clazz, jint w, jint h) {
    (void)env; (void)clazz;
    if (w <= 0 || h <= 0) return;
    g_output_w = w;
    g_output_h = h;
    vkp_cardboard_set_render_height(h);
    droiddeck_wayland_output_resized();
}

JNIEXPORT void JNICALL
Java_com_droiddeck_launcher_wayland_WaylandCompositor_nativeSetScaleMode(JNIEnv *env, jclass clazz, jint mode, jint alignment) {
    droiddeck_wayland_set_scale_mode(mode, alignment);
    __android_log_print(ANDROID_LOG_INFO, TAG, "scale mode %d alignment %d", mode, alignment);
}


JNIEXPORT jboolean JNICALL
Java_com_droiddeck_launcher_wayland_WaylandCompositor_nativeGamePictureRect(JNIEnv *env, jclass clazz, jfloatArray out) {
    if (!out || (*env)->GetArrayLength(env, out) < 4) return JNI_FALSE;
    float rect[4];
    if (!vkp_game_picture_rect(rect)) return JNI_FALSE;
    (*env)->SetFloatArrayRegion(env, out, 0, 4, rect);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_droiddeck_launcher_wayland_WaylandCompositor_nativeSetUpscaler(JNIEnv *env, jclass clazz, jint mode) {
    vkp_effects_set_scaling(mode);
}

JNIEXPORT void JNICALL
Java_com_droiddeck_launcher_wayland_WaylandCompositor_nativeSetUpscaleSharpness(JNIEnv *env, jclass clazz, jint sharpness) {
    vkp_effects_set_upscale_sharpness(sharpness);
}

JNIEXPORT void JNICALL
Java_com_droiddeck_launcher_wayland_WaylandCompositor_nativeSetCas(JNIEnv *env, jclass clazz, jboolean enabled, jint sharpness) {
    vkp_effects_set_cas(enabled ? 1 : 0, sharpness);
}

JNIEXPORT void JNICALL
Java_com_droiddeck_launcher_wayland_WaylandCompositor_nativeSetHdr(JNIEnv *env, jclass clazz, jboolean enabled) {
    vkp_effects_set_hdr(enabled ? 1 : 0);
}

JNIEXPORT void JNICALL
Java_com_droiddeck_launcher_wayland_WaylandCompositor_nativeSetDeband(JNIEnv *env, jclass clazz, jboolean enabled, jint strength) {
    vkp_effects_set_deband(enabled ? 1 : 0, strength);
}

JNIEXPORT void JNICALL
Java_com_droiddeck_launcher_wayland_WaylandCompositor_nativeSetScreenEffects(JNIEnv *env, jclass clazz, jfloat brightness,
        jfloat contrast, jfloat gamma, jfloat saturation, jboolean fxaa, jboolean toon, jboolean crt, jboolean ntsc) {
    vkp_effects_set_screen(brightness, contrast, gamma, saturation, fxaa ? 1 : 0, toon ? 1 : 0, crt ? 1 : 0, ntsc ? 1 : 0);
}

JNIEXPORT void JNICALL
Java_com_droiddeck_launcher_wayland_WaylandCompositor_nativeSetCardboard(JNIEnv *env, jclass clazz, jboolean enabled,
        jint source, jfloat ipd, jfloat dist, jfloat width, jfloat k1, jfloat k2, jfloat fov, jfloat inset, jfloat chroma,
        jboolean mono, jfloat hz) {
    struct cardboard_params p = {.enabled = enabled ? 1 : 0, .source = source, .ipd_m = ipd, .screen_dist_m = dist,
                                 .screen_width_m = width, .k1 = k1, .k2 = k2, .fov_deg = fov, .lens_inset = inset,
                                 .chroma = chroma, .mono = mono ? 1 : 0, .display_hz = hz};
    vkp_cardboard_set(&p);
}

JNIEXPORT void JNICALL
Java_com_droiddeck_launcher_wayland_WaylandCompositor_nativeSetCardboardPose(JNIEnv *env, jclass clazz, jfloatArray rot,
        jboolean live) {
    float m[9];
    if (!rot || (*env)->GetArrayLength(env, rot) < 9) return;
    (*env)->GetFloatArrayRegion(env, rot, 0, 9, m);
    vkp_cardboard_set_pose(m, live ? 1 : 0);
}

JNIEXPORT void JNICALL
Java_com_droiddeck_launcher_wayland_WaylandCompositor_nativeSetCardboardPoseFile(JNIEnv *env, jclass clazz, jstring path) {
    if (!path) return;
    const char *p = (*env)->GetStringUTFChars(env, path, NULL);
    if (!p) return;
    vkp_cardboard_set_pose_file(p);
    (*env)->ReleaseStringUTFChars(env, path, p);
}

JNIEXPORT void JNICALL
Java_com_droiddeck_launcher_wayland_WaylandCompositor_nativeLogDisplay(JNIEnv *env, jclass clazz, jstring message) {
    char *s = dup_jstr(env, message);
    if (s) droiddeck_log("display", "%s", s);
    free(s);
}

JNIEXPORT void JNICALL
Java_com_droiddeck_launcher_wayland_WaylandCompositor_nativeLogPerf(JNIEnv *env, jclass clazz, jstring message) {
    char *s = dup_jstr(env, message);
    if (s) droiddeck_log("perf", "%s", s);
    free(s);
}


JNIEXPORT void JNICALL
Java_com_droiddeck_launcher_wayland_WaylandCompositor_nativeSetHdrRequest(JNIEnv *env, jclass clazz, jint mode,
        jstring source, jboolean dxvkHdr, jboolean zeroCopyForced) {
    char *s = dup_jstr(env, source);
    droiddeck_color_set_request((int)mode, s, dxvkHdr ? 1 : 0, zeroCopyForced ? 1 : 0);
    free(s);
}

JNIEXPORT void JNICALL
Java_com_droiddeck_launcher_wayland_WaylandCompositor_nativeSetHdrDisplay(JNIEnv *env, jclass clazz, jint id, jstring name,
        jstring formats, jboolean hdr10, jfloat maxLum, jfloat maxAvg, jfloat minLum, jboolean ratioAvailable,
        jfloat ratio, jint api) {
    char *n = dup_jstr(env, name), *f = dup_jstr(env, formats);
    droiddeck_color_set_display((int)id, n, f, hdr10 ? 1 : 0, (float)maxLum, (float)maxAvg, (float)minLum,
                             ratioAvailable ? 1 : 0, (float)ratio, (int)api);
    free(n); free(f);
}

JNIEXPORT void JNICALL
Java_com_droiddeck_launcher_wayland_WaylandCompositor_nativeHdrSdrRatioSample(JNIEnv *env, jclass clazz, jfloat ratio,
        jboolean listener) {
    droiddeck_color_ratio_sample((float)ratio, listener ? 1 : 0);
}

JNIEXPORT jint JNICALL
Java_com_droiddeck_launcher_wayland_WaylandCompositor_nativeHdrLastFrameAgeMs(JNIEnv *env, jclass clazz) {
    return (jint)droiddeck_color_last_frame_age_ms();
}

JNIEXPORT jint JNICALL
Java_com_droiddeck_launcher_wayland_WaylandCompositor_nativeHdrGateState(JNIEnv *env, jclass clazz) {
    return (jint)droiddeck_color_gate_state();
}

JNIEXPORT void JNICALL
Java_com_droiddeck_launcher_wayland_WaylandCompositor_nativeHdrSessionEnd(JNIEnv *env, jclass clazz) {
    droiddeck_color_session_end();
}

JNIEXPORT jboolean JNICALL
Java_com_droiddeck_launcher_wayland_WaylandCompositor_nativeHdrOnScreen(JNIEnv *env, jclass clazz) {
    return droiddeck_color_hdr_on_screen() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_droiddeck_launcher_wayland_WaylandCompositor_nativeLogColor(JNIEnv *env, jclass clazz, jstring message) {
    char *s = dup_jstr(env, message);
    if (s) droiddeck_log("color", "%s", s);
    free(s);
}

JNIEXPORT void JNICALL
Java_com_droiddeck_launcher_wayland_WaylandCompositor_nativeLog(JNIEnv *env, jclass clazz, jstring area, jstring message) {
    char *a = dup_jstr(env, area), *s = dup_jstr(env, message);
    if (s) droiddeck_log(a && a[0] ? a : "app", "%s", s);
    free(a);
    free(s);
}

JNIEXPORT void JNICALL
Java_com_droiddeck_launcher_wayland_WaylandCompositor_nativeSetHdrSdrWhite(JNIEnv *env, jclass clazz, jfloat nits) {
    droiddeck_color_set_sdr_white((float)nits);
}

JNIEXPORT void JNICALL
Java_com_droiddeck_launcher_wayland_WaylandCompositor_nativeSetHdrOutput(JNIEnv *env, jclass clazz, jboolean on) {
    if (droiddeck_get_display()) droiddeck_host_hdr_output(on ? 1 : 0);
    else droiddeck_color_set_output(on ? 1 : 0);
}

JNIEXPORT jboolean JNICALL
Java_com_droiddeck_launcher_wayland_WaylandCompositor_nativeHdrOutput(JNIEnv *env, jclass clazz) {
    return droiddeck_color_output() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_droiddeck_launcher_wayland_WaylandCompositor_nativeHdrEnvSample(JNIEnv *env, jclass clazz, jint thermal,
                                                                   jfloat headroom, jint brightness, jint mode) {
    droiddeck_color_env_sample((int)thermal, (float)headroom != (float)headroom ? -1.0f : (float)headroom,
                            (int)brightness, (int)mode);
}

JNIEXPORT void JNICALL
Java_com_droiddeck_launcher_wayland_WaylandCompositor_nativeSetHdrHighestRatio(JNIEnv *env, jclass clazz, jfloat ratio) {
    droiddeck_color_set_highest_ratio((float)ratio == (float)ratio ? (float)ratio : -1.0f);
}

JNIEXPORT jfloat JNICALL
Java_com_droiddeck_launcher_wayland_WaylandCompositor_nativeHdrScreenHeadroom(JNIEnv *env, jclass clazz) {
    return (jfloat)droiddeck_color_screen_headroom(NULL, 0);
}

JNIEXPORT jstring JNICALL
Java_com_droiddeck_launcher_wayland_WaylandCompositor_nativeHdrScreenHeadroomWhy(JNIEnv *env, jclass clazz) {
    char why[200];
    droiddeck_color_screen_headroom(why, sizeof(why));
    return (*env)->NewStringUTF(env, why);
}

JNIEXPORT void JNICALL
Java_com_droiddeck_launcher_wayland_WaylandCompositor_nativeHdrNoteHeadroomRequest(JNIEnv *env, jclass clazz, jfloat ratio) {
    droiddeck_color_note_headroom_request((float)ratio);
}

JNIEXPORT jint JNICALL
Java_com_droiddeck_launcher_wayland_WaylandCompositor_nativeHdrState(JNIEnv *env, jclass clazz) {
    return (jint)droiddeck_color_hdr_state();
}

JNIEXPORT jboolean JNICALL
Java_com_droiddeck_launcher_wayland_WaylandCompositor_nativeHdrToneMappedOnScreen(JNIEnv *env, jclass clazz) {
    return droiddeck_color_tonemapped_on_screen() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_droiddeck_launcher_wayland_WaylandCompositor_nativeSetLookName(JNIEnv *env, jclass clazz, jstring name) {
    char *s = dup_jstr(env, name);
    vkp_effects_set_look(s);
    free(s);
}

JNIEXPORT void JNICALL
Java_com_droiddeck_launcher_wayland_WaylandCompositor_nativeSetRenderOnCommit(JNIEnv *env, jclass clazz, jboolean on) {
    g_render_on_commit = on ? 1 : 0;
    __android_log_print(ANDROID_LOG_INFO, TAG, "render on commit %s", on ? "on" : "off");
}

JNIEXPORT void JNICALL
Java_com_droiddeck_launcher_wayland_WaylandCompositor_nativeSetFpsLimit(JNIEnv *env, jclass clazz, jint fps) {
    g_fps_limit = fps > 0 ? fps : 0;
    g_release_unpaced = fps < 0;
    __android_log_print(ANDROID_LOG_INFO, TAG, "fps limit %d", fps);
}

JNIEXPORT void JNICALL
Java_com_droiddeck_launcher_wayland_WaylandCompositor_nativeSetSurface(
        JNIEnv *env, jclass clazz, jobject surface) {
    if (surface) {
        vk_present_set_window(ANativeWindow_fromSurface(env, surface));
    } else {
        vk_present_set_window(NULL);
    }
}


static char *dup_bytes(JNIEnv *env, jbyteArray arr, int *len) {
    *len = 0;
    if (!arr) return NULL;
    jsize n = (*env)->GetArrayLength(env, arr);
    char *buf = malloc((size_t)n + 1);
    if (!buf) return NULL;
    if (n) (*env)->GetByteArrayRegion(env, arr, 0, n, (jbyte *)buf);
    buf[n] = 0;
    *len = (int)n;
    return buf;
}

JNIEXPORT jboolean JNICALL
Java_com_droiddeck_launcher_wayland_WaylandCompositor_nativeWaitSurface(
        JNIEnv *env, jclass clazz, jint timeout_ms) {
    return vkp_wait_window_request(timeout_ms) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_droiddeck_launcher_wayland_WaylandCompositor_nativeSetClipboardText(JNIEnv *env, jclass clazz, jbyteArray utf8) {
    int len;
    char *buf = dup_bytes(env, utf8, &len);
    droiddeck_host_clipboard_text(buf ? buf : "", len);
    free(buf);
}

JNIEXPORT void JNICALL
Java_com_droiddeck_launcher_wayland_WaylandCompositor_nativeTextInputCommit(JNIEnv *env, jclass clazz, jbyteArray utf8) {
    int len;
    char *buf = dup_bytes(env, utf8, &len);
    if (buf && len) droiddeck_host_text_commit(buf, len);
    free(buf);
}

JNIEXPORT void JNICALL
Java_com_droiddeck_launcher_wayland_WaylandCompositor_nativeTextInputPreedit(JNIEnv *env, jclass clazz, jbyteArray utf8,
                                                                        jint cursorBegin, jint cursorEnd) {
    int len;
    char *buf = dup_bytes(env, utf8, &len);
    droiddeck_host_text_preedit(buf ? buf : "", len, cursorBegin, cursorEnd);
    free(buf);
}

JNIEXPORT void JNICALL
Java_com_droiddeck_launcher_wayland_WaylandCompositor_nativeTextInputDelete(JNIEnv *env, jclass clazz, jint before, jint after) {
    droiddeck_host_text_delete(before, after);
}

extern void adrenotools_set_turbo(bool turbo);
JNIEXPORT void JNICALL
Java_com_droiddeck_launcher_wayland_WaylandCompositor_nativeSetGpuTurbo(JNIEnv *env, jclass clazz, jboolean on) {
    (void)clazz;
    if (!gpu_clock_pin_set(on == JNI_TRUE)) {
        int saved_errno = errno;
        jclass error = (*env)->FindClass(env, "java/lang/IllegalStateException");
        if (error) (*env)->ThrowNew(env, error, strerror(saved_errno));
    }
    // Clear a legacy global pin when recovering its marker after an app upgrade or crash.
    if (on != JNI_TRUE) adrenotools_set_turbo(false);
}

JNIEXPORT jint JNICALL
Java_com_droiddeck_launcher_wayland_WaylandCompositor_nativeRaisePriority(JNIEnv *env, jclass clazz, jint tid, jint nice) {
    (void)env; (void)clazz;
    errno = 0;
    int before = getpriority(PRIO_PROCESS, (id_t)tid);
    if (before == -1 && errno != 0) return 100;
    if (before > nice) setpriority(PRIO_PROCESS, (id_t)tid, nice);
    errno = 0;
    int after = getpriority(PRIO_PROCESS, (id_t)tid);
    return after == -1 && errno != 0 ? 100 : after;
}

JNIEXPORT void JNICALL
Java_com_droiddeck_launcher_wayland_WaylandCompositor_nativeSetCardboardSpatialPose(JNIEnv *env, jclass clazz,
        jfloatArray rot, jfloatArray pos, jboolean live) {
    (void)clazz;
    if (!rot || !pos || (*env)->GetArrayLength(env, rot) != 9 || (*env)->GetArrayLength(env, pos) != 3) return;
    float m[9], p[3];
    (*env)->GetFloatArrayRegion(env, rot, 0, 9, m);
    (*env)->GetFloatArrayRegion(env, pos, 0, 3, p);
    vkp_cardboard_set_spatial_pose(m, p, live ? 1 : 0);
}

/* The Xbox Cloud Gaming native player: its decoded frames are the whole scene (no Wayland clients). */
JNIEXPORT void JNICALL
Java_com_droiddeck_launcher_wayland_WaylandCompositor_nativeStreamMode(JNIEnv *env, jclass clazz, jboolean on) {
    droiddeck_stream_set(on ? 1 : 0);
}

JNIEXPORT void JNICALL
Java_com_droiddeck_launcher_wayland_WaylandCompositor_nativeStreamFrame(JNIEnv *env, jclass clazz, jobject hardwareBuffer) {
    if (!hardwareBuffer) return;
    AHardwareBuffer *ahb = AHardwareBuffer_fromHardwareBuffer(env, hardwareBuffer);
    if (ahb) droiddeck_stream_frame(ahb);
}

JNIEXPORT jint JNICALL
Java_com_droiddeck_launcher_wayland_WaylandCompositor_nativeStreamFrames(JNIEnv *env, jclass clazz) {
    return (jint)droiddeck_stream_frames();
}
