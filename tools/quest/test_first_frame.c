/* Exercise the production vkp_render entrypoint without Android/Vulkan hardware.
 * The Python runner extracts that function verbatim into the included file. */
#include <assert.h>
#include <stddef.h>
#include <stdio.h>

struct vkp_draw { int unused; };
static int quest, framegen, cardboard, pass_result, base_result, pass_shown;
static int first_frames, pass_calls, base_calls, fallback_calls;
static int vkp_quest_layer_presentation(void) { return quest; }
static int vkp_framegen_active(void) { return framegen; }
static int vkp_cardboard_active(void) { return cardboard; }
static void vkp_apply_window_request(void) {}
static void sc_layer_hide_overlay(void) {}
static void sc_layer_hide(void) {}
static void droiddeck_log(const char *tag, const char *message, ...) {}
static void vkp_signal_first_frame(void) { first_frames++; }
static int vkp_base_black(int w, int h) { base_calls++; return base_result; }
static int sc_layer_present_pass(const struct vkp_draw *draws, int n, int w, int h) {
    pass_calls++;
    /* Production sc_layer_present_pass signals only after a content submission.
     * Its no-op results (off-screen geometry or pool exhaustion) also return 0. */
    if (pass_result == 0 && pass_shown) vkp_signal_first_frame();
    return pass_result;
}
static int render_impl(int w, int h, const struct vkp_draw *draws, int n,
                       const void *hdr, int *how) { fallback_calls++; return 17; }

#include "vkp_render_under_test.inc"

static void reset(void) {
    quest = 1;
    framegen = cardboard = pass_result = base_result = pass_shown = 0;
    first_frames = pass_calls = base_calls = fallback_calls = 0;
}

int main(void) {
    const struct vkp_draw draw = {0};
    reset();
    for (int i = 0; i < 3; i++) assert(vkp_render(1280, 720, NULL, 0) == 0);
    assert(base_calls == 3 && pass_calls == 0 && first_frames == 0);

    /* An accepted layer operation is not necessarily a displayed client frame. */
    reset();
    assert(vkp_render(1280, 720, &draw, 1) == 0);
    assert(pass_calls == 1 && base_calls == 1 && first_frames == 0);

    /* Content is the sole owner of the notification, with no second backdrop event. */
    reset(); pass_shown = 1;
    assert(vkp_render(1280, 720, &draw, 1) == 0);
    assert(first_frames == 1);
    assert(vkp_render(1280, 720, NULL, 0) == 0);
    assert(first_frames == 1);

    reset(); pass_result = -1;
    assert(vkp_render(1280, 720, &draw, 1) == -1);
    assert(first_frames == 0 && base_calls == 0);
    reset(); base_result = -1;
    assert(vkp_render(1280, 720, NULL, 0) == -1);
    assert(first_frames == 0);

    /* Existing routes remain responsible for their own notifications. */
    for (int mode = 0; mode < 3; mode++) {
        reset();
        if (mode == 0) quest = 0;
        if (mode == 1) framegen = 1;
        if (mode == 2) cardboard = 1;
        assert(vkp_render(1280, 720, &draw, 1) == 17);
        assert(fallback_calls == 1 && pass_calls == 0 && base_calls == 0);
        assert(first_frames == 0);
    }
    puts("PASS: Quest first-frame notification waits for client content");
    return 0;
}
