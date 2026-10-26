from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


@unittest.skipUnless(shutil.which("cc"), "C compiler required")
class AndroidVolumeTest(unittest.TestCase):
    def test_missing_or_invalid_initial_state_recovers_without_restarting_the_sink(self):
        header = Path(__file__).resolve().parents[1] / "aaudio-sink/android-volume.h"
        harness = r'''
#define _GNU_SOURCE
#include <assert.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdarg.h>
#include <stdlib.h>
#include <string.h>
#include <sys/time.h>
#include <unistd.h>
#define PA_VOLUME_NORM 65536U
#define PA_USEC_PER_MSEC 1000
#define PA_SINK_INIT 0
#define PA_SUBSCRIPTION_EVENT_SINK 1
#define PA_SUBSCRIPTION_EVENT_CHANGE 2
#define PA_MIN(a,b) ((a)<(b)?(a):(b))
#define pa_xnew0(type,n) ((type*)calloc(n,sizeof(type)))
#define pa_xstrdup strdup
#define pa_xfree free
#define pa_log_warn(...) ((void)0)
typedef struct { unsigned value; } pa_cvolume;
typedef struct pa_time_event { int value; } pa_time_event;
typedef struct pa_mainloop_api { void (*time_free)(pa_time_event*); } pa_mainloop_api;
typedef struct { pa_mainloop_api *mainloop; } pa_core;
typedef struct pa_sink {
    void *userdata; int state; bool muted; unsigned n_volume_steps, index;
    struct { unsigned channels; } sample_spec;
    pa_cvolume real_volume, reference_volume, soft_volume;
    pa_core *core;
    void (*set_volume)(struct pa_sink*), (*set_mute)(struct pa_sink*);
} pa_sink;
struct android_volume;
struct userdata { struct android_volume *android_volume; };
static int restarts, frees;
static char *pa_sprintf_malloc(const char *format, ...) {
    va_list ap; va_start(ap,format); char *result; assert(vasprintf(&result,format,ap)>=0); va_end(ap); return result;
}
static void pa_cvolume_set(pa_cvolume *volume, unsigned channels, unsigned value) { volume->value=value; }
static unsigned pa_cvolume_max(const pa_cvolume *volume) { return volume->value; }
static void pa_cvolume_reset(pa_cvolume *volume, unsigned channels) { volume->value=PA_VOLUME_NORM; }
static bool pa_cvolume_equal(const pa_cvolume *a, const pa_cvolume *b) { return a->value==b->value; }
static void pa_sink_volume_changed(pa_sink *sink, const pa_cvolume *volume) { sink->real_volume=*volume; }
static void pa_sink_mute_changed(pa_sink *sink, bool muted) { sink->muted=muted; }
static void pa_sink_set_set_volume_callback(pa_sink *sink, void (*callback)(pa_sink*)) { sink->set_volume=callback; }
static void pa_sink_set_set_mute_callback(pa_sink *sink, void (*callback)(pa_sink*)) { sink->set_mute=callback; }
static void pa_subscription_post(pa_core *core, int event, unsigned index) {}
static uint64_t pa_rtclock_now(void) { return 0; }
static void pa_core_rttime_restart(pa_core *core, pa_time_event *event, uint64_t time) { restarts++; }
static pa_time_event *pa_core_rttime_new(pa_core *core, uint64_t time,
    void (*callback)(pa_mainloop_api*,pa_time_event*,const struct timeval*,void*), void *userdata) {
    return calloc(1,sizeof(pa_time_event));
}
static void free_timer(pa_time_event *event) { frees++; free(event); }
#include "android-volume.h"
static void state(const char *directory, const char *text) {
    char *path=pa_sprintf_malloc("%s/volume.state",directory);
    FILE *f=fopen(path,"w"); assert(f); fputs(text,f); fclose(f); free(path);
}
int main(int argc, char **argv) {
    pa_mainloop_api api={.time_free=free_timer}; pa_core core={.mainloop=&api};
    struct userdata userdata={0};
    pa_sink sink={.userdata=&userdata,.core=&core,.state=1,.sample_spec={.channels=2},.real_volume={PA_VOLUME_NORM}};
    userdata.android_volume=android_volume_new(&sink,argv[1]);
    assert(userdata.android_volume && userdata.android_volume->timer && sink.set_volume && sink.set_mute);
    state(argv[1],"invalid\n");
    android_volume_refresh(&api,userdata.android_volume->timer,NULL,userdata.android_volume);
    assert(sink.real_volume.value==PA_VOLUME_NORM && restarts==1);
    state(argv[1],"3 15 1\n");
    android_volume_refresh(&api,userdata.android_volume->timer,NULL,userdata.android_volume);
    assert(sink.real_volume.value==3*PA_VOLUME_NORM/15 && sink.muted && sink.n_volume_steps==16);
    assert(sink.soft_volume.value==PA_VOLUME_NORM && restarts==2);
    sink.real_volume.value=2*PA_VOLUME_NORM/3; sink.set_volume(&sink);
    char *request=pa_sprintf_malloc("%s/volume.request",argv[1]);
    FILE *f=fopen(request,"r"); unsigned level; assert(f && fscanf(f,"%u",&level)==1 && level==10); fclose(f); free(request);
    android_volume_free(userdata.android_volume); assert(frees==1);
    return 0;
}
'''
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "pulsecore").mkdir()
            for name in ("core-rtclock.h", "core-subscribe.h"):
                (root / "pulsecore" / name).touch()
            source = root / "test.c"
            source.write_text(harness)
            binary = root / "test"
            subprocess.run(["cc", "-std=c11", "-I", str(root), "-I", str(header.parent),
                            str(source), "-o", str(binary)], check=True, capture_output=True)
            subprocess.run([str(binary), str(root)], check=True, capture_output=True)
