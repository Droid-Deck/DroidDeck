#ifndef DROIDDECK_HUDFRAMES_H
#define DROIDDECK_HUDFRAMES_H

#include <stddef.h>
#include <stdint.h>

struct hud_frames;

struct hud_frames *hud_frames_open(const char *path);
void hud_frames_close(struct hud_frames *h);
/* New frames since the last poll, oldest first; -1 while the file holds no valid ring. */
int hud_frames_poll(struct hud_frames *h, int64_t *frametimes, int64_t *times, int max);
/* info: pid, output width, output height, refresh mHz, Steam focused. */
int hud_frames_info(const struct hud_frames *h, int32_t *info, char *engine, size_t engine_len);

#endif
