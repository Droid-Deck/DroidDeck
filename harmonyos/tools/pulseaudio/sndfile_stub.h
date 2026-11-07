/*
 * A minimal libsndfile for the HarmonyOS build of PulseAudio.
 *
 * PulseAudio's core links libsndfile so that it can play sound files as
 * streams. The port does not use that: the audio comes from the guest over
 * the Pulse protocol, never from a file. There is no libsndfile for musl,
 * so this provides the header and the symbols the core needs, and refuses
 * every operation.
 *
 * The names and the values follow libsndfile's sndfile.h, so PulseAudio's
 * own code compiles against it unchanged.
 */
#ifndef SNDFILE_H
#define SNDFILE_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef int64_t sf_count_t;

typedef struct SNDFILE_tag SNDFILE;

typedef struct SF_INFO {
    sf_count_t frames;
    int samplerate;
    int channels;
    int format;
    int sections;
    int seekable;
} SF_INFO;

typedef struct SF_FORMAT_INFO {
    int format;
    const char *name;
    const char *extension;
} SF_FORMAT_INFO;

/* Open modes. */
#define SFM_READ  0x10
#define SFM_WRITE 0x20
#define SFM_RDWR  0x30

/* Commands. */
#define SFC_GET_CURRENT_SF_INFO 0x1020
#define SFC_GET_CHANNEL_MAP_INFO 0x1021
#define SFC_SET_CHANNEL_MAP_INFO 0x1022
#define SFC_GET_FORMAT_INFO 0x1023
#define SFC_GET_FORMAT_MAJOR_COUNT 0x1024
#define SFC_GET_FORMAT_MAJOR 0x1025

/* Formats. */
#define SF_FORMAT_PCM_S8  0x0001
#define SF_FORMAT_PCM_16  0x0002
#define SF_FORMAT_PCM_24  0x0003
#define SF_FORMAT_PCM_32  0x0004
#define SF_FORMAT_PCM_U8  0x0005
#define SF_FORMAT_FLOAT   0x0006
#define SF_FORMAT_DOUBLE  0x0007
#define SF_FORMAT_ULAW    0x0010
#define SF_FORMAT_ALAW    0x0011
#define SF_FORMAT_VORBIS  0x0060
#define SF_FORMAT_OPUS    0x0064
#define SF_FORMAT_SUBMASK 0x0FFF

/* Container formats. */
#define SF_FORMAT_WAV     0x010000
#define SF_FORMAT_WAVEX   0x130000
#define SF_FORMAT_OGG     0x200000
#define SF_FORMAT_FLAC    0x230000
#define SF_FORMAT_ENDMASK 0x0FFF0000

/* Channel maps. */
#define SF_CHANNEL_MAP_INVALID 0
#define SF_CHANNEL_MAP_MONO 1
#define SF_CHANNEL_MAP_LEFT 2
#define SF_CHANNEL_MAP_RIGHT 3
#define SF_CHANNEL_MAP_CENTER 4
#define SF_CHANNEL_MAP_FRONT_LEFT 5
#define SF_CHANNEL_MAP_FRONT_RIGHT 6
#define SF_CHANNEL_MAP_FRONT_CENTER 7
#define SF_CHANNEL_MAP_REAR_CENTER 8
#define SF_CHANNEL_MAP_REAR_LEFT 9
#define SF_CHANNEL_MAP_REAR_RIGHT 10
#define SF_CHANNEL_MAP_LFE 11
#define SF_CHANNEL_MAP_FRONT_LEFT_OF_CENTER 12
#define SF_CHANNEL_MAP_FRONT_RIGHT_OF_CENTER 13
#define SF_CHANNEL_MAP_SIDE_LEFT 14
#define SF_CHANNEL_MAP_SIDE_RIGHT 15
#define SF_CHANNEL_MAP_TOP_CENTER 16
#define SF_CHANNEL_MAP_TOP_FRONT_LEFT 17
#define SF_CHANNEL_MAP_TOP_FRONT_CENTER 18
#define SF_CHANNEL_MAP_TOP_FRONT_RIGHT 19
#define SF_CHANNEL_MAP_TOP_REAR_LEFT 20
#define SF_CHANNEL_MAP_TOP_REAR_CENTER 21
#define SF_CHANNEL_MAP_TOP_REAR_RIGHT 22

/* Strings. */
#define SF_STR_TITLE 0x01
#define SF_STR_COPYRIGHT 0x02
#define SF_STR_SOFTWARE 0x03
#define SF_STR_ARTIST 0x04
#define SF_STR_COMMENT 0x05
#define SF_STR_DATE 0x06
#define SF_STR_ALBUM 0x07
#define SF_STR_LICENSE 0x08
#define SF_STR_TRACKNUMBER 0x09
#define SF_STR_GENRE 0x10

/* Endianness. */
#define SF_ENDIAN_LITTLE 0x100
#define SF_ENDIAN_BIG 0x200
#define SF_ENDIAN_CPU 0x000
#define SF_ENDIAN_FILE 0x100000

/** The last error, as libsndfile reports it. */
extern int sf_errno;

SNDFILE *sf_open(const char *path, int mode, SF_INFO *sfinfo);
SNDFILE *sf_open_fd(int fd, int mode, SF_INFO *sfinfo, int closefd);
int sf_close(SNDFILE *sndfile);
int sf_command(SNDFILE *sndfile, int command, void *data, int datasize);
const char *sf_error_number(int errnum);
const char *sf_get_string(SNDFILE *sndfile, int strtype);
sf_count_t sf_read_raw(SNDFILE *sndfile, void *ptr, sf_count_t bytes);
sf_count_t sf_write_raw(SNDFILE *sndfile, const void *ptr, sf_count_t bytes);
sf_count_t sf_readf_short(SNDFILE *sndfile, short *ptr, sf_count_t frames);
sf_count_t sf_readf_int(SNDFILE *sndfile, int *ptr, sf_count_t frames);
sf_count_t sf_readf_float(SNDFILE *sndfile, float *ptr, sf_count_t frames);
sf_count_t sf_writef_short(SNDFILE *sndfile, const short *ptr, sf_count_t frames);
sf_count_t sf_writef_int(SNDFILE *sndfile, const int *ptr, sf_count_t frames);
sf_count_t sf_writef_float(SNDFILE *sndfile, const float *ptr, sf_count_t frames);

#ifdef __cplusplus
}
#endif

#endif /* SNDFILE_H */
