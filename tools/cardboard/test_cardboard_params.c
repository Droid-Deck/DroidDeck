/* Host test for the cardboard pass's pure helpers (waylandcomp/src/cardboard.c, built with
 * CARDBOARD_HOST_TEST): clamping and the push-constant layout cardboard.frag reads. */
#include "cardboard.h"
#include <math.h>
#include <stdio.h>

static int failures;
#define CHECK(c) do { if (!(c)) { printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #c); failures++; } } while (0)
static int near(float a, float b) { return fabsf(a - b) < 1e-4f; }

int main(void) {
    struct cardboard_params p = {.enabled = 7, .source = 9, .ipd_m = 1.0f, .screen_dist_m = 0.0f,
                                 .screen_width_m = 100.0f, .k1 = 5.0f, .k2 = -5.0f, .fov_deg = 500.0f,
                                 .lens_inset = 1.0f, .chroma = -1.0f};
    cardboard_clamp(&p);
    CHECK(p.enabled == 1);
    CHECK(p.source == CARDBOARD_SOURCE_SCREEN);
    CHECK(near(p.ipd_m, 0.1f));
    CHECK(near(p.screen_dist_m, 0.5f));
    CHECK(near(p.screen_width_m, 60.0f));
    CHECK(near(p.k1, 2.0f) && near(p.k2, -1.0f));
    CHECK(near(p.fov_deg, 140.0f));
    CHECK(near(p.lens_inset, 0.25f) && near(p.chroma, 0.0f));

    struct cardboard_params q = {.enabled = 1, .source = CARDBOARD_SOURCE_SCREEN, .ipd_m = 0.064f, .screen_dist_m = 3.0f,
                                 .screen_width_m = 4.0f, .k1 = 0.34f, .k2 = 0.55f, .fov_deg = 90.0f,
                                 .lens_inset = 0.05f, .chroma = 0.006f};
    const float pose[9] = {1, 2, 3, 4, 5, 6, 7, 8, 9};
    float pc[24];
    cardboard_push_constants(&q, pose, 1920, 1080, 2400, 1080, pc);
    /* rot rows with the scalars in w */
    CHECK(near(pc[0], 1) && near(pc[1], 2) && near(pc[2], 3) && near(pc[3], 0.032f));
    CHECK(near(pc[4], 4) && near(pc[5], 5) && near(pc[6], 6) && near(pc[7], 3.0f));
    CHECK(near(pc[8], 7) && near(pc[9], 8) && near(pc[10], 9) && near(pc[11], 2.0f));
    /* lens: k1, k2, tan(fov/2), chroma */
    CHECK(near(pc[12], 0.34f) && near(pc[13], 0.55f) && near(pc[14], 1.0f) && near(pc[15], 0.006f));
    /* view: inset, source aspect, mode, eye viewport aspect (half the panel) */
    CHECK(near(pc[16], 0.05f) && near(pc[17], 1920.0f / 1080.0f) && near(pc[18], 0.0f) && near(pc[19], 1200.0f / 1080.0f));

    /* A side-by-side source: each eye's aspect is half the frame's. */
    q.source = CARDBOARD_SOURCE_SBS;
    cardboard_push_constants(&q, pose, 3840, 1080, 2400, 1080, pc);
    CHECK(near(pc[17], 1920.0f / 1080.0f) && near(pc[18], 1.0f));
    /* Unknown sizes fall back instead of dividing by zero. */
    cardboard_push_constants(&q, pose, 0, 0, 0, 0, pc);
    CHECK(isfinite(pc[17]) && isfinite(pc[19]));

    if (failures) return 1;
    printf("PASS: cardboard params\n");
    return 0;
}
