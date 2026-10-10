// Drives Java_com_termux_terminal_JNI_createSubprocess against a mock JNIEnv that tracks local
// references the way ART does, so the JNI hygiene of app/src/main/cpp/termux/termux.c can be
// checked on the host. Built and run by tools/tests/test_termux_jni.py.
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "termux.c"

#define LOCAL_REF_LIMIT 512

static int live_refs;
static int peak_refs;
static int overflowed;
static int mismatched_releases;
static int unreleased_chars;

typedef struct { const void* owner; char* chars; } Pinned;
static Pinned pinned[8];

static jsize mock_array_length(JNIEnv* TERMUX_UNUSED(env), jarray array)
{
    return *(jsize*) array;
}

static jobject mock_get_element(JNIEnv* TERMUX_UNUSED(env), jobjectArray TERMUX_UNUSED(array), jsize TERMUX_UNUSED(index))
{
    if (++live_refs > peak_refs) peak_refs = live_refs;
    // ART aborts the process here: "local reference table overflow (max=512)".
    if (live_refs > LOCAL_REF_LIMIT) overflowed = 1;
    return (jobject) malloc(1);
}

static void mock_delete_local_ref(JNIEnv* TERMUX_UNUSED(env), jobject ref)
{
    live_refs--;
    free(ref);
}

static const char* mock_get_utf(JNIEnv* TERMUX_UNUSED(env), jstring string, jboolean* TERMUX_UNUSED(copy))
{
    for (size_t i = 0; i < sizeof(pinned) / sizeof(pinned[0]); i++) {
        if (pinned[i].owner == NULL) {
            pinned[i].owner = string;
            pinned[i].chars = strdup("x");
            return pinned[i].chars;
        }
    }
    abort();
}

static void mock_release_utf(JNIEnv* TERMUX_UNUSED(env), jstring string, const char* chars)
{
    for (size_t i = 0; i < sizeof(pinned) / sizeof(pinned[0]); i++) {
        if (pinned[i].owner != NULL && pinned[i].chars == chars) {
            // Releasing chars against some other string is a JNI contract violation.
            if (pinned[i].owner != string) mismatched_releases++;
            free(pinned[i].chars);
            pinned[i].owner = NULL;
            return;
        }
    }
    mismatched_releases++;
}

static jclass mock_find_class(JNIEnv* TERMUX_UNUSED(env), const char* TERMUX_UNUSED(name)) { return NULL; }
static jint mock_throw_new(JNIEnv* TERMUX_UNUSED(env), jclass TERMUX_UNUSED(c), const char* message)
{
    fprintf(stderr, "thrown: %s\n", message);
    return 0;
}

static void* mock_get_critical(JNIEnv* TERMUX_UNUSED(env), jarray array, jboolean* TERMUX_UNUSED(copy))
{
    return array;
}

static void mock_release_critical(JNIEnv* TERMUX_UNUSED(env), jarray TERMUX_UNUSED(array), void* TERMUX_UNUSED(p), jint TERMUX_UNUSED(mode)) {}

int main(int argc, char** argv)
{
    if (argc != 3) return 2;
    jsize arg_count = atoi(argv[1]);
    jsize env_count = atoi(argv[2]);

    // The struct is JNINativeInterface in the NDK's header and JNINativeInterface_ in the JDK's.
    __typeof__(**(JNIEnv*) 0) table = {
        .GetArrayLength = mock_array_length,
        .GetObjectArrayElement = mock_get_element,
        .DeleteLocalRef = mock_delete_local_ref,
        .GetStringUTFChars = mock_get_utf,
        .ReleaseStringUTFChars = mock_release_utf,
        .FindClass = mock_find_class,
        .ThrowNew = mock_throw_new,
        .GetPrimitiveArrayCritical = mock_get_critical,
        .ReleasePrimitiveArrayCritical = mock_release_critical,
    };
    const __typeof__(table)* env = &table;

    // Arrays are modelled as their length; the caller's own references are not counted.
    jsize args = arg_count, envs = env_count;
    jint pid_slot[1] = {0};
    jstring cmd = (jstring) "cmd", cwd = (jstring) "cwd";

    Java_com_termux_terminal_JNI_createSubprocess((JNIEnv*) &env, NULL, cmd, cwd,
        arg_count ? (jobjectArray) &args : NULL, env_count ? (jobjectArray) &envs : NULL,
        (jintArray) pid_slot, 24, 80);

    for (size_t i = 0; i < sizeof(pinned) / sizeof(pinned[0]); i++) if (pinned[i].owner) unreleased_chars++;
    printf("peak_refs=%d live_refs=%d overflowed=%d mismatched_releases=%d unreleased_chars=%d\n",
        peak_refs, live_refs, overflowed, mismatched_releases, unreleased_chars);
    return 0;
}
