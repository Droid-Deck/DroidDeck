// Part of libddnatv-hook.so, included by ddnatv-hook.c (one translation unit, so the
// parts share their static functions and are built with the same command as before).
//
// xz and zstd limited to one thread (threads of forked dpkg-deb children fail on Android).

#ifndef DDNATV_HOST_TEST

// Hook lzma multi-threaded routines to enforce single-threaded execution
// This prevents clone/pthread_create failures in forked dpkg-deb child processes on Android
typedef struct {
    const uint8_t *next_in;
    size_t avail_in;
    uint64_t total_in;
    uint8_t *next_out;
    size_t avail_out;
    uint64_t total_out;
    const void *allocator;
    void *internal;
    void *reserved_ptr1;
    void *reserved_ptr2;
    void *reserved_ptr3;
    void *reserved_ptr4;
    uint64_t reserved_int1;
    uint64_t reserved_int2;
    size_t reserved_int3;
    size_t reserved_int4;
    uint32_t reserved_enum1;
    uint32_t reserved_enum2;
} ddnatv_lzma_stream;

typedef struct {
    uint32_t flags;
    uint32_t threads;
    uint64_t block_size;
    uint32_t timeout;
    uint32_t preset;
    const void *filters;
    int check;
    uint64_t memlimit_threading;
    uint64_t memlimit_stop;
} ddnatv_lzma_mt;

int lzma_stream_decoder_mt(ddnatv_lzma_stream *strm, const ddnatv_lzma_mt *options) {
    static int (*orig_decoder)(ddnatv_lzma_stream *, uint64_t, uint32_t) = NULL;
    if (!orig_decoder) {
        orig_decoder = (int (*)(ddnatv_lzma_stream *, uint64_t, uint32_t))dlsym(RTLD_DEFAULT, "lzma_stream_decoder");
        if (!orig_decoder) {
            orig_decoder = (int (*)(ddnatv_lzma_stream *, uint64_t, uint32_t))dlsym(RTLD_NEXT, "lzma_stream_decoder");
        }
    }
    uint64_t memlimit = (options && options->memlimit_threading > 0) ? options->memlimit_threading : UINT64_MAX;
    uint32_t flags = options ? options->flags : 0;
    if (orig_decoder) {
        return orig_decoder(strm, memlimit, flags);
    }
    static int (*orig_mt)(ddnatv_lzma_stream *, const ddnatv_lzma_mt *) = NULL;
    if (!orig_mt) {
        orig_mt = (int (*)(ddnatv_lzma_stream *, const ddnatv_lzma_mt *))dlsym(RTLD_NEXT, "lzma_stream_decoder_mt");
    }
    return orig_mt ? orig_mt(strm, options) : -1;
}

int lzma_stream_encoder_mt(ddnatv_lzma_stream *strm, const ddnatv_lzma_mt *options) {
    // The native userland encodes on one thread. The single-thread encoders take the same settings: xz passes its
    // compression level as a filter chain (its preset field is unused), other programs a preset.
    // Honouring only the preset made `xz -T4 -9` (and -T0) compress like `xz -0`.
    if (options && options->filters) {
        static int (*orig_stream)(ddnatv_lzma_stream *, const void *, int) = NULL;
        if (!orig_stream) {
            orig_stream = (int (*)(ddnatv_lzma_stream *, const void *, int))dlsym(RTLD_DEFAULT, "lzma_stream_encoder");
            if (!orig_stream) {
                orig_stream = (int (*)(ddnatv_lzma_stream *, const void *, int))dlsym(RTLD_NEXT, "lzma_stream_encoder");
            }
        }
        if (orig_stream) return orig_stream(strm, options->filters, options->check);
    }
    static int (*orig_easy)(ddnatv_lzma_stream *, uint32_t, int) = NULL;
    if (!orig_easy) {
        orig_easy = (int (*)(ddnatv_lzma_stream *, uint32_t, int))dlsym(RTLD_DEFAULT, "lzma_easy_encoder");
        if (!orig_easy) {
            orig_easy = (int (*)(ddnatv_lzma_stream *, uint32_t, int))dlsym(RTLD_NEXT, "lzma_easy_encoder");
        }
    }
    if (orig_easy) {
        uint32_t preset = options ? options->preset : 6;
        int check = options ? options->check : 4; // LZMA_CHECK_CRC64
        return orig_easy(strm, preset, check);
    }
    static int (*orig_mt)(ddnatv_lzma_stream *, const ddnatv_lzma_mt *) = NULL;
    if (!orig_mt) {
        orig_mt = (int (*)(ddnatv_lzma_stream *, const ddnatv_lzma_mt *))dlsym(RTLD_NEXT, "lzma_stream_encoder_mt");
    }
    return orig_mt ? orig_mt(strm, options) : -1;
}

uint32_t lzma_cputhreads(void) {
    return 1;
}

int ZSTD_CCtx_setParameter(void *cctx, int param, int value) {
    static int (*orig_param)(void *, int, int) = NULL;
    if (!orig_param) {
        orig_param = (int (*)(void *, int, int))dlsym(RTLD_DEFAULT, "ZSTD_CCtx_setParameter");
        if (!orig_param) {
            orig_param = (int (*)(void *, int, int))dlsym(RTLD_NEXT, "ZSTD_CCtx_setParameter");
        }
    }
    if (param == 400 /* ZSTD_c_nbWorkers */ && value > 1) {
        value = 1;
    }
    return orig_param ? orig_param(cctx, param, value) : -1;
}

#endif /* !DDNATV_HOST_TEST */
