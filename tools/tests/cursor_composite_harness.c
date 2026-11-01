#include <assert.h>
#include <stdio.h>

#include "cursor_constants.inc"
#include "cursor_scene_positions.inc"
#include "cursor_clip_to_eye.inc"

int main(void) {
    int out[2];
    int dx, sx, dw;

    assert(cursor_scene_positions(1920, CARDBOARD_SOURCE_SCREEN, 100, 0, out) == 1);
    assert(out[0] == 100);

    assert(cursor_scene_positions(1920, CARDBOARD_SOURCE_FLAT, 100, 0, out) == 1);
    assert(out[0] == 100);

    assert(cursor_scene_positions(1, CARDBOARD_SOURCE_SBS, 0, 0, out) == 1);

    assert(cursor_scene_positions(3840, CARDBOARD_SOURCE_SBS, 100, 0, out) == 2);
    assert(out[0] == 100 && out[1] == 2020);

    assert(cursor_scene_positions(3840, CARDBOARD_SOURCE_SBS, 2000, 0, out) == 2);
    assert(out[0] == 2000 && out[1] == 80);

    assert(cursor_scene_positions(3840, CARDBOARD_SOURCE_SBS, 1919, 0, out) == 2);
    assert(out[0] == 1919 && out[1] == 3839);

    assert(cursor_scene_positions(3840, CARDBOARD_SOURCE_SBS, 1920, 0, out) == 2);
    assert(out[0] == 1920 && out[1] == 0);

    /* A hotspot that pushes the image across the seam must not move the mirror to the
     * other eye: the pointer decides the eye, the hotspot only shifts the image. */
    assert(cursor_scene_positions(1920, CARDBOARD_SOURCE_SBS, 960, 32, out) == 2);
    assert(out[0] == 928 && out[1] == -32);

    assert(cursor_scene_positions(1920, CARDBOARD_SOURCE_SBS, 959, 32, out) == 2);
    assert(out[0] == 927 && out[1] == 1887);

    /* Each copy is cropped to its own eye, so nothing bleeds into the other eye's half. */
    assert(cursor_clip_to_eye(1920, -1, 100, 32, &dx, &sx, &dw) == 1);
    assert(dx == 100 && sx == 0 && dw == 32);

    assert(cursor_clip_to_eye(1920, 0, 100, 32, &dx, &sx, &dw) == 1);
    assert(dx == 100 && sx == 0 && dw == 32);

    assert(cursor_clip_to_eye(1920, 0, 950, 32, &dx, &sx, &dw) == 1);
    assert(dx == 950 && sx == 0 && dw == 10);

    assert(cursor_clip_to_eye(1920, 1, 950, 32, &dx, &sx, &dw) == 1);
    assert(dx == 960 && sx == 10 && dw == 22);

    assert(cursor_clip_to_eye(1920, 1, 960, 32, &dx, &sx, &dw) == 1);
    assert(dx == 960 && sx == 0 && dw == 32);

    assert(cursor_clip_to_eye(1920, 0, 928, 32, &dx, &sx, &dw) == 1);
    assert(dx == 928 && sx == 0 && dw == 32);

    assert(cursor_clip_to_eye(1920, 1, 928, 32, &dx, &sx, &dw) == 0);
    assert(cursor_clip_to_eye(1920, 0, -32, 32, &dx, &sx, &dw) == 0);

    puts("PASS: cursor stereo placement");
    return 0;
}
