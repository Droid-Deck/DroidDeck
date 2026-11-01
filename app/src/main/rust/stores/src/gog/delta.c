#include "xdelta3.h"

typedef struct { size_t used; } dd_budget;
typedef struct { size_t size; } dd_allocation;

static void *dd_alloc(void *opaque, size_t count, usize_t size) {
    dd_budget *budget = opaque;
    if (size && count > (62u * 1024u * 1024u) / size) return NULL;
    size_t n = count * size;
    if (n > 62u * 1024u * 1024u - budget->used) return NULL;
    dd_allocation *allocation = malloc(sizeof(*allocation) + n);
    if (!allocation) return NULL;
    allocation->size = n;
    budget->used += n;
    return allocation + 1;
}

static void dd_free(void *opaque, void *ptr) {
    if (!ptr) return;
    dd_allocation *allocation = (dd_allocation *)ptr - 1;
    ((dd_budget *)opaque)->used -= allocation->size;
    free(allocation);
}

int dd_gog_delta(const char *source, const char *patch, const char *target,
                 int (*cancel)(void *), void *opaque) {
    FILE *old = NULL, *delta = NULL, *out = NULL;
    xd3_stream stream = {0};
    xd3_source src = {0};
    xd3_config config = {0};
    dd_budget budget = {0};
    unsigned char *input = NULL, *block = NULL;
    int result = -1, configured = 0;
    old = fopen(source, "rb"); delta = fopen(patch, "rb"); out = fopen(target, "wb");
    if (!old || !delta || !out) goto done;
    input = malloc(64 * 1024); block = malloc(1024 * 1024);
    if (!input || !block) goto done;
    config.winsize = 64 * 1024;
    config.alloc = dd_alloc; config.freef = dd_free; config.opaque = &budget;
    if (xd3_config_stream(&stream, &config)) goto done;
    configured = 1;
    src.blksize = 1024 * 1024; src.curblk = block; src.curblkno = 0;
    src.onblk = fread(block, 1, src.blksize, old);
    if (xd3_set_source(&stream, &src)) goto done;
    for (;;) {
        size_t n = fread(input, 1, 64 * 1024, delta);
        if (ferror(delta)) goto done;
        if (feof(delta)) xd3_set_flags(&stream, XD3_FLUSH);
        xd3_avail_input(&stream, input, n);
        int code;
        while ((code = xd3_decode_input(&stream)) != XD3_INPUT) {
            if (cancel && cancel(opaque)) { result = 130; goto done; }
            switch (code) {
                case XD3_OUTPUT:
                    if (fwrite(stream.next_out, 1, stream.avail_out, out) != stream.avail_out) goto done;
                    xd3_consume_output(&stream); break;
                case XD3_GETSRCBLK:
                    if (fseeko(old, (off_t)(src.getblkno * src.blksize), SEEK_SET)) goto done;
                    src.curblkno = src.getblkno;
                    src.onblk = fread(block, 1, src.blksize, old);
                    if (ferror(old)) goto done;
                    break;
                case XD3_GOTHEADER: case XD3_WINSTART: case XD3_WINFINISH: break;
                default: goto done;
            }
        }
        if (feof(delta)) break;
    }
    if (xd3_close_stream(&stream) || fflush(out)) goto done;
    result = 0;
done:
    if (configured) xd3_free_stream(&stream);
    if (old) fclose(old);
    if (delta) fclose(delta);
    if (out && fclose(out)) result = -1;
    free(input); free(block);
    return result;
}
