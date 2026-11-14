/*
 * The implementation behind the minimal libsndfile.
 *
 * Every operation fails: the port never plays a sound file, so the only
 * thing PulseAudio's core needs is something to link against.
 */

#include "sndfile_stub.h"

#include <errno.h>
#include <string.h>

int sf_errno = 0;

/* The error libsndfile would report for "this file cannot be opened". */
#define SF_ERR_NO_SUCH_FILE 2

static const char *const kErrorText[] = {
    "No error.",
    "System error.",
    "No such file or directory.",
    "Unsupported file format.",
};

const char *sf_error_number(int errnum) {
    if (errnum < 0 || (size_t) errnum >= sizeof(kErrorText) / sizeof(kErrorText[0]))
        return "Unknown error.";
    return kErrorText[errnum];
}

SNDFILE *sf_open(const char *path, int mode, SF_INFO *sfinfo) {
    (void) path;
    (void) mode;
    (void) sfinfo;
    sf_errno = SF_ERR_NO_SUCH_FILE;
    return NULL;
}

SNDFILE *sf_open_fd(int fd, int mode, SF_INFO *sfinfo, int closefd) {
    (void) fd;
    (void) mode;
    (void) sfinfo;
    (void) closefd;
    sf_errno = SF_ERR_NO_SUCH_FILE;
    return NULL;
}

int sf_close(SNDFILE *sndfile) {
    (void) sndfile;
    return 0;
}

int sf_command(SNDFILE *sndfile, int command, void *data, int datasize) {
    (void) sndfile;
    (void) datasize;

    switch (command) {
        case SFC_GET_CURRENT_SF_INFO: {
            /* An empty file is a valid answer: the format is unknown, and
             * the caller falls back to its defaults. */
            if (data)
                memset(data, 0, sizeof(SF_INFO));
            return 0;
        }
        case SFC_GET_FORMAT_INFO: {
            if (data)
                memset(data, 0, sizeof(SF_FORMAT_INFO));
            return 0;
        }
        case SFC_GET_FORMAT_MAJOR_COUNT: {
            /* No formats are known, so the caller's loop does nothing. */
            if (data)
                memset(data, 0, sizeof(int));
            return 0;
        }
        case SFC_GET_FORMAT_MAJOR: {
            if (data)
                memset(data, 0, sizeof(SF_FORMAT_INFO));
            return 0;
        }
        case SFC_GET_CHANNEL_MAP_INFO:
        case SFC_SET_CHANNEL_MAP_INFO:
            return 0;
        default:
            sf_errno = 3;
            return 1;
    }
}

const char *sf_get_string(SNDFILE *sndfile, int strtype) {
    (void) sndfile;
    (void) strtype;
    return NULL;
}

sf_count_t sf_read_raw(SNDFILE *sndfile, void *ptr, sf_count_t bytes) {
    (void) sndfile;
    (void) ptr;
    (void) bytes;
    return 0;
}

sf_count_t sf_write_raw(SNDFILE *sndfile, const void *ptr, sf_count_t bytes) {
    (void) sndfile;
    (void) ptr;
    (void) bytes;
    return 0;
}

sf_count_t sf_readf_short(SNDFILE *sndfile, short *ptr, sf_count_t frames) {
    (void) sndfile;
    (void) ptr;
    (void) frames;
    return 0;
}

sf_count_t sf_readf_int(SNDFILE *sndfile, int *ptr, sf_count_t frames) {
    (void) sndfile;
    (void) ptr;
    (void) frames;
    return 0;
}

sf_count_t sf_readf_float(SNDFILE *sndfile, float *ptr, sf_count_t frames) {
    (void) sndfile;
    (void) ptr;
    (void) frames;
    return 0;
}

sf_count_t sf_writef_short(SNDFILE *sndfile, const short *ptr, sf_count_t frames) {
    (void) sndfile;
    (void) ptr;
    (void) frames;
    return 0;
}

sf_count_t sf_writef_int(SNDFILE *sndfile, const int *ptr, sf_count_t frames) {
    (void) sndfile;
    (void) ptr;
    (void) frames;
    return 0;
}

sf_count_t sf_writef_float(SNDFILE *sndfile, const float *ptr, sf_count_t frames) {
    (void) sndfile;
    (void) ptr;
    (void) frames;
    return 0;
}
