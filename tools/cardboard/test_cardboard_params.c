#include "cardboard.h"
#include <math.h>
#include <stdio.h>
#include <string.h>

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
    CHECK(near(pc[0], 1) && near(pc[1], 2) && near(pc[2], 3) && near(pc[3], 0.032f));
    CHECK(near(pc[4], 4) && near(pc[5], 5) && near(pc[6], 6) && near(pc[7], 3.0f));
    CHECK(near(pc[8], 7) && near(pc[9], 8) && near(pc[10], 9) && near(pc[11], 2.0f));
    CHECK(near(pc[12], 0.34f) && near(pc[13], 0.55f) && near(pc[14], 1.0f) && near(pc[15], 0.006f));
    CHECK(near(pc[16], 0.05f) && near(pc[17], 1920.0f / 1080.0f) && near(pc[18], 0.0f) && near(pc[19], 1200.0f / 1080.0f));

    q.source = CARDBOARD_SOURCE_SBS;
    cardboard_push_constants(&q, pose, 3840, 1080, 2400, 1080, pc);
    CHECK(near(pc[17], 1920.0f / 1080.0f) && near(pc[18], 1.0f));
    q.mono = 1;
    cardboard_push_constants(&q, pose, 3840, 1080, 1920, 1080, pc);
    CHECK(near(pc[3], 0.0f) && near(pc[19], 1920.0f / 1080.0f) && near(pc[20], 1.0f));
    q.mono = 0;
    cardboard_push_constants(&q, pose, 3840, 1080, 1920, 1080, pc);
    CHECK(near(pc[3], 0.032f) && near(pc[19], 960.0f / 1080.0f) && near(pc[20], 0.0f));
    cardboard_push_constants(&q, pose, 0, 0, 0, 0, pc);
    CHECK(isfinite(pc[17]) && isfinite(pc[19]));

    // Narrow optical profiles and the lower SteamVR slider values must reach
    // the projection unchanged, rather than all becoming a 30-degree view.
    const float glasses_fovs[] = {22.0f, 23.3f, 24.0f, 25.8f, 29.0f};
    for (unsigned i = 0; i < sizeof(glasses_fovs) / sizeof(glasses_fovs[0]); i++) {
        q.fov_deg = glasses_fovs[i];
        cardboard_clamp(&q);
        CHECK(near(q.fov_deg, glasses_fovs[i]));
        cardboard_push_constants(&q, pose, 3840, 1080, 3840, 1080, pc);
        CHECK(near(pc[14], tanf(glasses_fovs[i] * 0.5f * (float)M_PI / 180.0f)));
    }

    const struct cardboard_params screen = {
        .enabled = 1, .source = CARDBOARD_SOURCE_SCREEN, .ipd_m = 0.064f,
        .screen_dist_m = 3.0f, .screen_width_m = 2.0f, .fov_deg = 25.8f,
    };
    const int source_sizes[][2] = {{1280, 720}, {1600, 900}, {1920, 1080}};
    const int panel_heights[] = {1080, 1200};
    for (unsigned panel = 0; panel < sizeof(panel_heights) / sizeof(panel_heights[0]); panel++) {
        float native[24];
        const int height = panel_heights[panel];
        cardboard_push_constants(&screen, pose, 1920, 1080, 3840, height, native);
        for (unsigned size = 0; size < sizeof(source_sizes) / sizeof(source_sizes[0]); size++) {
            cardboard_push_constants(&screen, pose, source_sizes[size][0], source_sizes[size][1], 3840, height, pc);
            for (unsigned field = 0; field < 24; field++) CHECK(near(pc[field], native[field]));
            CHECK(near(pc[17], 16.0f / 9.0f));
            CHECK(near(pc[18], CARDBOARD_SOURCE_SCREEN));
            CHECK(near(pc[19], 1920.0f / height));
            CHECK(near(pc[20], 0.0f));
        }
    }
    q.source = CARDBOARD_SOURCE_FLAT;
    cardboard_clamp(&q);
    CHECK(q.source == CARDBOARD_SOURCE_FLAT);
    for (unsigned panel = 0; panel < sizeof(panel_heights) / sizeof(panel_heights[0]); panel++) {
        for (unsigned size = 0; size < sizeof(source_sizes) / sizeof(source_sizes[0]); size++) {
            cardboard_push_constants(&q, pose, source_sizes[size][0], source_sizes[size][1], 3840, panel_heights[panel], pc);
            CHECK(near(pc[17], 16.0f / 9.0f));
            CHECK(near(pc[18], CARDBOARD_SOURCE_FLAT));
            CHECK(near(pc[19], 1920.0f / panel_heights[panel]));
            CHECK(near(pc[20], 0.0f));
        }
    }
    q.fov_deg = 0.0f;
    cardboard_clamp(&q);
    CHECK(q.fov_deg > 0.0f && q.fov_deg < glasses_fovs[0]);

    const float translation[3] = {0.25f, -0.3f, 0.5f};
    q.source = CARDBOARD_SOURCE_SCREEN;
    cardboard_push_constants(&q, pose, 1920, 1080, 3840, 1200, pc);
    float original[24]; memcpy(original, pc, sizeof(pc));
    cardboard_push_translation(&q, translation, pc);
    for (int i = 0; i < 21; ++i) CHECK(near(pc[i], original[i]));
    for (int i = 0; i < 3; ++i) CHECK(near(pc[21+i], translation[i]));
    q.source = CARDBOARD_SOURCE_SBS;
    cardboard_push_translation(&q, translation, pc);
    CHECK(near(pc[21], 0) && near(pc[22], 0) && near(pc[23], 0));
    q.source = CARDBOARD_SOURCE_FLAT;
    cardboard_push_translation(&q, translation, pc);
    CHECK(near(pc[21], 0) && near(pc[22], 0) && near(pc[23], 0));
    q.source = CARDBOARD_SOURCE_SCREEN;
    const float zero[3] = {0};
    cardboard_push_translation(&q, zero, pc);
    for (int i = 0; i < 24; ++i) CHECK(near(pc[i], original[i]));
    if (failures) return 1;
    printf("PASS: cardboard params\n");
    return 0;
}
