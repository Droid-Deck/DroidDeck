#ifndef DROIDDECK_HUDFRAMES_H
#define DROIDDECK_HUDFRAMES_H

#include <stddef.h>
#include <stdint.h>

struct hud_frames;

struct hud_frames *hud_frames_open(const char *path);
void hud_frames_close(struct hud_frames *h);
int hud_frames_poll(struct hud_frames *h, int64_t *frametimes, int64_t *times, int max);
int hud_frames_info(const struct hud_frames *h, int32_t *info, char *engine, size_t engine_len);

#endif
