import pathlib
import subprocess
import tempfile
import unittest


class DirectAudioWatchdogTest(unittest.TestCase):
    def test_recovery_threshold_callback_race_and_forced_exit(self):
        source = pathlib.Path('tools/directaudio/sink/module-directaudio-native-sink.c').read_text()
        watchdog = source[source.index('static void watchdog('):source.index('static void close_output(')]
        callback = source[source.index('static void record_callback('):
                          source.index('static aaudio_data_callback_result_t out_cb(')]
        constants = '\n'.join(line for line in source.splitlines()
                              if line.startswith(('#define DA_FREEZE_GAP_NS ', '#define DA_CB_STALL_NS ',
                                                  '#define DA_HOST_RESTART_STALLS ')))
        harness = r'''
#include <assert.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <unistd.h>
#include <string.h>
typedef void AAudioStream;
struct userdata {
    bool watchdog_on;
    AAudioStream *aq;
    uint64_t wd_last_tick_ns, last_cb_ns;
    unsigned int cb_count, wd_last_cb;
    uint64_t stall_state, cb_seen_ns, cb_streak_ns;
};
static uint64_t clock_ns = 10000000000ull;
static unsigned int reopens;
static bool callback_race, burst_after_reopen;
static struct userdata *current;
static uint64_t now_ns(void) { return clock_ns; }
#define pa_log(...) ((void)0)
CALLBACK
static void callback(struct userdata *u) {
    record_callback(u);
    __atomic_add_fetch(&u->cb_count, 1, __ATOMIC_SEQ_CST);
}
static void request_reopen(struct userdata *u, const char *why) {
    reopens++;
    if (burst_after_reopen)
        for (int i = 0; i < 6; i++) callback(u);
}
static bool exchange(uint64_t *value, uint64_t *expected, uint64_t next,
                     int weak, int success, int failure) {
    if (callback_race) {
        struct userdata *u = current;
        u->cb_streak_ns = clock_ns - DA_CB_STALL_NS;
        u->cb_seen_ns = clock_ns;
        callback(u);
    }
    return __atomic_compare_exchange_n(value, expected, next, weak, success, failure);
}
#define __atomic_compare_exchange_n exchange
WATCHDOG
static void cleanup(void) { _exit(99); }
static void tick(struct userdata *u) {
    clock_ns += 1000000000ull;
    watchdog(u);
}
int main(int argc, char **argv) {
    struct userdata state = {.watchdog_on = true, .aq = (void *)1};
    struct userdata *u = &state;
    current = u;
    watchdog(u);
    if (argc > 1 && strcmp(argv[1], "burst") == 0) {
        atexit(cleanup);
        burst_after_reopen = true;
        for (int i = 0; i < 20; i++) tick(u);
        return 42;
    }
    if (argc > 1 && strcmp(argv[1], "zero") == 0) {
        callback_race = true;
        tick(u);
        assert(reopens == 0 && (uint32_t) u->stall_state == 0);
        return 0;
    }
    tick(u);
    assert(reopens == 1 && (uint32_t) u->stall_state == 1);
    tick(u);
    assert(reopens == 2 && (uint32_t) u->stall_state == 2);
    if (argc > 1 && strcmp(argv[1], "sustained") == 0) {
        for (int i = 0; i < 6; i++) callback(u);
        assert((uint32_t) u->stall_state == 2);
        for (int i = 0; i < 12; i++) {
            clock_ns += 100000000ull;
            callback(u);
        }
        assert((uint32_t) u->stall_state == 0);
        return 0;
    }
    if (argc > 1 && strcmp(argv[1], "exit") == 0) {
        atexit(cleanup);
        tick(u);
        return 42;
    }
    callback_race = true;
    tick(u);
    assert(reopens == 2 && (uint32_t) u->stall_state == 0);
    callback_race = false;
    tick(u);
    assert(reopens == 2 && (uint32_t) u->stall_state == 0);
    tick(u);
    assert(reopens == 3 && (uint32_t) u->stall_state == 1);
    u->watchdog_on = false;
    tick(u);
    assert(reopens == 3 && (uint32_t) u->stall_state == 1);
    return 0;
}
'''.replace('CALLBACK', callback).replace('WATCHDOG', watchdog)
        with tempfile.TemporaryDirectory() as folder:
            path = pathlib.Path(folder)
            (path / 'watchdog.c').write_text(constants + '\n' + harness)
            subprocess.run(['cc', '-std=c11', '-Werror', str(path / 'watchdog.c'),
                            '-o', str(path / 'watchdog')], check=True, capture_output=True)
            subprocess.run([str(path / 'watchdog')], check=True, timeout=5)
            subprocess.run([str(path / 'watchdog'), 'zero'], check=True, timeout=5)
            result = subprocess.run([str(path / 'watchdog'), 'exit'], timeout=5)
            self.assertEqual(1, result.returncode)
            subprocess.run([str(path / 'watchdog'), 'sustained'], check=True, timeout=5)
            result = subprocess.run([str(path / 'watchdog'), 'burst'], timeout=5)
            self.assertEqual(1, result.returncode)
