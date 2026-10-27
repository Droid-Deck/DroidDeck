#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cerrno>
#include <cstring>
#include <string>
#include <thread>
#include <vector>

#include "venc_proto.h"

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "DroidDeckVenc", __VA_ARGS__)

namespace {

struct Mapping {
    uint8_t *data = nullptr;
    size_t size = 0;
};

constexpr int32_t kColorFormatYuv420SemiPlanar = 21;
constexpr uint32_t kBufferFlagKeyFrame = 1;
constexpr uint32_t kBufferFlagCodecConfig = 2;
constexpr int kTouchWidth = 1920, kTouchHeight = 1080;  // InputSpace.kt
constexpr int kRemoteTouchId = 31;  // the compositor's last touch slot (MAX_TOUCH_POINTS 32)

int32_t avc_profile(int32_t v4l2) {
    switch (v4l2) {
    case 0: return 1;
    case 1: return 0x10000;
    case 2: return 2;
    case 3: return 4;
    case 4: return 8;
    default: return 0;
    }
}

struct DmaBufSync {
    uint64_t flags;
};
constexpr unsigned long kDmaBufIoctlSync = _IOW('b', 0, DmaBufSync);
constexpr uint64_t kDmaBufSyncWrite = 2, kDmaBufSyncStart = 0, kDmaBufSyncEnd = 4;
constexpr int32_t kColorFormatYuv420Planar = 19;

class Decoder {
public:
    ~Decoder() { release(); }

    void release() {
        if (codec_) {
            AMediaCodec_stop(codec_);
            AMediaCodec_delete(codec_);
            codec_ = nullptr;
        }
        if (bitstream_) munmap((void *)bitstream_, bitstream_size_);
        bitstream_ = nullptr;
        for (auto &c : captures_) drop(c);
    }

    bool configure(const vdec_config &c, int fd) {
        release();
        if (c.width < 16 || c.height < 16 || c.stride < c.width || c.rows < c.height || c.bitstreams == 0 ||
            c.bitstreams > VENC_MAX_BUFFERS || c.bitstream_size == 0) {
            LOG("bad decoder config: %ux%u stride %u rows %u, %u bitstreams", c.width, c.height, c.stride, c.rows,
                c.bitstreams);
            return false;
        }
        bitstream_size_ = (size_t)c.bitstreams * c.bitstream_size;
        void *p = mmap(nullptr, bitstream_size_, PROT_READ, MAP_SHARED, fd, 0);
        if (p == MAP_FAILED) {
            LOG("could not map the bitstream buffers: %s", strerror(errno));
            return false;
        }
        bitstream_ = (const uint8_t *)p;
        config_ = c;
        codec_ = create(c, true);
        if (!codec_) codec_ = create(c, false);
        if (!codec_) {
            LOG("no %s decoder for %ux%u", c.codec == VENC_HEVC ? "HEVC" : "H.264", c.width, c.height);
            release();
            return false;
        }
        src_stride_ = (int32_t)c.width;
        src_rows_ = (int32_t)c.height;
        src_width_ = (int32_t)c.width;
        src_height_ = (int32_t)c.height;
        src_planar_ = false;
        shown_ = false;
        using GetName = media_status_t (*)(AMediaCodec *, char **);
        using ReleaseName = void (*)(AMediaCodec *, char *);
        static auto get_name = (GetName)dlsym(RTLD_DEFAULT, "AMediaCodec_getName");
        static auto release_name = (ReleaseName)dlsym(RTLD_DEFAULT, "AMediaCodec_releaseName");
        char *name = nullptr;
        if (get_name && get_name(codec_, &name) != AMEDIA_OK) name = nullptr;
        LOG("decoding %s %ux%u with %s", c.codec == VENC_HEVC ? "HEVC" : "H.264", c.width, c.height,
            name ? name : "MediaCodec");
        if (name && release_name) release_name(codec_, name);
        return true;
    }

    void capture(const vdec_capture &c, int fd) {
        if (c.index >= VENC_MAX_BUFFERS) {
            close(fd);
            return;
        }
        Capture &cap = captures_[c.index];
        drop(cap);
        void *p = mmap(nullptr, c.size, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
        if (p == MAP_FAILED) {
            LOG("could not map capture buffer %u: %s", c.index, strerror(errno));
            close(fd);
            return;
        }
        cap = {fd, (uint8_t *)p, c.size};
    }

    bool decode(const vdec_packet &p, bool *frame) {
        *frame = false;
        if (!codec_ || p.capture >= VENC_MAX_BUFFERS || !captures_[p.capture].data || p.input >= config_.bitstreams ||
            p.bytes == 0 || p.bytes > config_.bitstream_size)
            return false;
        auto start = std::chrono::steady_clock::now();
        ssize_t in = AMediaCodec_dequeueInputBuffer(codec_, 20000);
        if (in < 0) return false;
        size_t capacity = 0;
        uint8_t *dst = AMediaCodec_getInputBuffer(codec_, (size_t)in, &capacity);
        if (!dst || capacity < p.bytes) {
            AMediaCodec_queueInputBuffer(codec_, (size_t)in, 0, 0, 0, 0);
            return false;
        }
        memcpy(dst, bitstream_ + (size_t)p.input * config_.bitstream_size, p.bytes);
        if (AMediaCodec_queueInputBuffer(codec_, (size_t)in, 0, p.bytes, (uint64_t)std::max<int64_t>(p.pts_us, 0), 0) !=
            AMEDIA_OK)
            return false;
        auto wait = std::chrono::milliseconds(shown_ ? 25 : 250);
        while (std::chrono::steady_clock::now() - start < wait) {
            AMediaCodecBufferInfo info;
            ssize_t o = AMediaCodec_dequeueOutputBuffer(codec_, &info, 2000);
            if (o == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
                read_format();
                continue;
            }
            if (o < 0) continue;
            size_t size = 0;
            uint8_t *data = AMediaCodec_getOutputBuffer(codec_, (size_t)o, &size);
            if (data && info.size > 0 && (size_t)info.offset + (size_t)info.size <= size)
                *frame = copy(data + info.offset, (size_t)info.size, captures_[p.capture]) || *frame;
            AMediaCodec_releaseOutputBuffer(codec_, (size_t)o, false);
            if (info.presentationTimeUs >= p.pts_us) break;
        }
        shown_ = shown_ || *frame;
        return true;
    }

private:
    struct Capture {
        int fd = -1;
        uint8_t *data = nullptr;
        size_t size = 0;
    };

    static void drop(Capture &c) {
        if (c.data) munmap(c.data, c.size);
        if (c.fd >= 0) close(c.fd);
        c = Capture();
    }

    AMediaCodec *create(const vdec_config &c, bool low_latency) {
        const char *mime = c.codec == VENC_HEVC ? "video/hevc" : "video/avc";
        AMediaCodec *codec = AMediaCodec_createDecoderByType(mime);
        if (!codec) return nullptr;
        AMediaFormat *f = AMediaFormat_new();
        AMediaFormat_setString(f, AMEDIAFORMAT_KEY_MIME, mime);
        AMediaFormat_setInt32(f, AMEDIAFORMAT_KEY_WIDTH, (int32_t)c.width);
        AMediaFormat_setInt32(f, AMEDIAFORMAT_KEY_HEIGHT, (int32_t)c.height);
        AMediaFormat_setInt32(f, AMEDIAFORMAT_KEY_COLOR_FORMAT, kColorFormatYuv420SemiPlanar);
        AMediaFormat_setInt32(f, AMEDIAFORMAT_KEY_MAX_INPUT_SIZE, (int32_t)c.bitstream_size);
        if (low_latency) {
            AMediaFormat_setInt32(f, "low-latency", 1);
            AMediaFormat_setInt32(f, "vendor.qti-ext-dec-low-latency.enable", 1);
            AMediaFormat_setInt32(f, "vendor.qti-ext-dec-picture-order.enable", 1);
            AMediaFormat_setInt32(f, "priority", 0);
            AMediaFormat_setFloat(f, "operating-rate", 120.0f);
        }
        media_status_t s = AMediaCodec_configure(codec, f, nullptr, nullptr, 0);
        AMediaFormat_delete(f);
        if (s == AMEDIA_OK) s = AMediaCodec_start(codec);
        if (s != AMEDIA_OK) {
            AMediaCodec_delete(codec);
            return nullptr;
        }
        return codec;
    }

    void read_format() {
        AMediaFormat *f = AMediaCodec_getOutputFormat(codec_);
        if (!f) return;
        int32_t v;
        if (AMediaFormat_getInt32(f, AMEDIAFORMAT_KEY_WIDTH, &v) && v > 0) src_width_ = v;
        if (AMediaFormat_getInt32(f, AMEDIAFORMAT_KEY_HEIGHT, &v) && v > 0) src_height_ = v;
        src_stride_ = AMediaFormat_getInt32(f, "stride", &v) && v >= src_width_ ? v : src_width_;
        src_rows_ = AMediaFormat_getInt32(f, "slice-height", &v) && v >= src_height_ ? v : src_height_;
        src_planar_ = AMediaFormat_getInt32(f, AMEDIAFORMAT_KEY_COLOR_FORMAT, &v) && v == kColorFormatYuv420Planar;
        LOG("decoder output %dx%d stride %d rows %d %s", src_width_, src_height_, src_stride_, src_rows_,
            src_planar_ ? "planar" : "semi-planar");
        AMediaFormat_delete(f);
    }

    bool copy(const uint8_t *src, size_t size, const Capture &cap) {
        size_t luma = (size_t)src_stride_ * src_rows_;
        size_t chroma = src_planar_ ? 2 * (size_t)(src_stride_ / 2) * (src_rows_ / 2) : (size_t)src_stride_ * (src_rows_ / 2);
        if (size < luma + chroma) return false;
        uint32_t w = std::min<uint32_t>((uint32_t)src_width_, config_.stride);
        uint32_t h = std::min<uint32_t>((uint32_t)src_height_, config_.rows);
        if ((size_t)config_.stride * config_.rows * 3 / 2 > cap.size) return false;
        DmaBufSync sync = {kDmaBufSyncStart | kDmaBufSyncWrite};
        ioctl(cap.fd, kDmaBufIoctlSync, &sync);
        uint8_t *y = cap.data, *uv = cap.data + (size_t)config_.stride * config_.rows;
        for (uint32_t row = 0; row < h; row++)
            memcpy(y + (size_t)row * config_.stride, src + (size_t)row * src_stride_, w);
        const uint8_t *su = src + luma;
        for (uint32_t row = 0; row < h / 2; row++) {
            uint8_t *d = uv + (size_t)row * config_.stride;
            if (!src_planar_) {
                memcpy(d, su + (size_t)row * src_stride_, w & ~1u);
                continue;
            }
            const uint8_t *u = su + (size_t)row * (src_stride_ / 2);
            const uint8_t *vv = su + (size_t)(src_stride_ / 2) * (src_rows_ / 2) + (size_t)row * (src_stride_ / 2);
            for (uint32_t x = 0; x < w / 2; x++) {
                d[2 * x] = u[x];
                d[2 * x + 1] = vv[x];
            }
        }
        sync.flags = kDmaBufSyncEnd | kDmaBufSyncWrite;
        ioctl(cap.fd, kDmaBufIoctlSync, &sync);
        return true;
    }

    AMediaCodec *codec_ = nullptr;
    vdec_config config_ = {};
    const uint8_t *bitstream_ = nullptr;
    size_t bitstream_size_ = 0;
    Capture captures_[VENC_MAX_BUFFERS];
    int32_t src_width_ = 0, src_height_ = 0, src_stride_ = 0, src_rows_ = 0;
    bool src_planar_ = false;
    bool shown_ = false;
};

class Session {
public:
    explicit Session(int fd) : fd_(fd) {}
    ~Session() {
        release();
        close(fd_);
    }

    void run() {
        for (;;) {
            uint8_t buf[256];
            int fds[VENC_MAX_BUFFERS * 2];
            int nfds = 0;
            ssize_t n = receive(buf, sizeof buf, fds, &nfds);
            if (n <= 0) return;
            uint32_t type;
            memcpy(&type, buf, sizeof type);
            if (type == VENC_CONFIG && n == (ssize_t)sizeof(venc_config)) {
                venc_config c;
                memcpy(&c, buf, sizeof c);
                bool ok = configure(c, fds, nfds);
                for (int i = 0; i < nfds; i++) close(fds[i]);
                reply(ok ? 0 : -1, 0, false);
            } else if (type == VENC_FRAME && n == (ssize_t)sizeof(venc_frame)) {
                venc_frame f;
                memcpy(&f, buf, sizeof f);
                uint32_t bytes = 0;
                bool key = false;
                bool ok = encode(f, &bytes, &key);
                reply(ok ? 0 : -1, bytes, key);
            } else if (type == VENC_TOUCH && n == (ssize_t)sizeof(venc_touch)) {
                venc_touch t;
                memcpy(&t, buf, sizeof t);
                touch(t);
                for (int i = 0; i < nfds; i++) close(fds[i]);
            } else if (type == VENC_BITRATE && n == (ssize_t)sizeof(venc_bitrate)) {
                venc_bitrate b;
                memcpy(&b, buf, sizeof b);
                set_bitrate(b.bitrate);
                for (int i = 0; i < nfds; i++) close(fds[i]);
            } else if (type == VDEC_CONFIG && n == (ssize_t)sizeof(vdec_config)) {
                vdec_config c;
                memcpy(&c, buf, sizeof c);
                bool ok = nfds == 1 && decoder_.configure(c, fds[0]);
                for (int i = 0; i < nfds; i++) close(fds[i]);
                decoded(ok ? 0 : -1, false);
            } else if (type == VDEC_CAPTURE && n == (ssize_t)sizeof(vdec_capture)) {
                vdec_capture c;
                memcpy(&c, buf, sizeof c);
                if (nfds == 1) decoder_.capture(c, fds[0]);
                else for (int i = 0; i < nfds; i++) close(fds[i]);
            } else if (type == VDEC_PACKET && n == (ssize_t)sizeof(vdec_packet)) {
                vdec_packet p;
                memcpy(&p, buf, sizeof p);
                bool frame = false;
                bool ok = decoder_.decode(p, &frame);
                for (int i = 0; i < nfds; i++) close(fds[i]);
                decoded(ok ? 0 : -1, frame);
            } else {
                LOG("unexpected message %u of %zd bytes; closing", type, n);
                for (int i = 0; i < nfds; i++) close(fds[i]);
                return;
            }
        }
    }

private:
    ssize_t receive(uint8_t *buf, size_t len, int *fds, int *nfds) {
        char control[CMSG_SPACE(sizeof(int) * VENC_MAX_BUFFERS * 2)];
        iovec iov = {buf, len};
        msghdr m = {};
        m.msg_iov = &iov;
        m.msg_iovlen = 1;
        m.msg_control = control;
        m.msg_controllen = sizeof control;
        ssize_t n = recvmsg(fd_, &m, MSG_CMSG_CLOEXEC);
        for (cmsghdr *c = CMSG_FIRSTHDR(&m); n > 0 && c; c = CMSG_NXTHDR(&m, c)) {
            if (c->cmsg_level != SOL_SOCKET || c->cmsg_type != SCM_RIGHTS) continue;
            int count = (int)((c->cmsg_len - CMSG_LEN(0)) / sizeof(int));
            memcpy(fds, CMSG_DATA(c), sizeof(int) * std::min(count, VENC_MAX_BUFFERS * 2));
            *nfds = std::min(count, VENC_MAX_BUFFERS * 2);
        }
        return n;
    }

    void reply(int32_t status, uint32_t bytes, bool key) {
        venc_done d = {VENC_DONE, status, bytes, key ? 1u : 0u};
        send(fd_, &d, sizeof d, MSG_NOSIGNAL);
    }

    void decoded(int32_t status, bool frame) {
        vdec_result r = {VDEC_RESULT, status, frame ? 1u : 0u};
        send(fd_, &r, sizeof r, MSG_NOSIGNAL);
    }

    void release() {
        decoder_.release();
        if (codec_) {
            AMediaCodec_stop(codec_);
            AMediaCodec_delete(codec_);
            codec_ = nullptr;
        }
        for (auto &m : inputs_) munmap(m.data, m.size);
        for (auto &m : outputs_) munmap(m.data, m.size);
        inputs_.clear();
        outputs_.clear();
        header_.clear();
    }

    AMediaCodec *create(const venc_config &c, bool with_profile) {
        const char *mime = c.codec == VENC_HEVC ? "video/hevc" : "video/avc";
        AMediaCodec *codec = AMediaCodec_createEncoderByType(mime);
        if (!codec) return nullptr;
        float fps = c.fps_num ? (float)c.fps_den / (float)c.fps_num : 60.0f;
        AMediaFormat *f = AMediaFormat_new();
        AMediaFormat_setString(f, AMEDIAFORMAT_KEY_MIME, mime);
        AMediaFormat_setInt32(f, AMEDIAFORMAT_KEY_WIDTH, (int32_t)c.width);
        AMediaFormat_setInt32(f, AMEDIAFORMAT_KEY_HEIGHT, (int32_t)c.height);
        AMediaFormat_setInt32(f, AMEDIAFORMAT_KEY_COLOR_FORMAT, kColorFormatYuv420SemiPlanar);
        AMediaFormat_setInt32(f, AMEDIAFORMAT_KEY_BIT_RATE, (int32_t)std::max(c.bitrate, 500000u));
        AMediaFormat_setInt32(f, "bitrate-mode", c.vbr ? 1 : 2);
        AMediaFormat_setFloat(f, AMEDIAFORMAT_KEY_FRAME_RATE, fps);
        AMediaFormat_setFloat(f, "operating-rate", fps);
        AMediaFormat_setInt32(f, "priority", 0);
        AMediaFormat_setInt32(f, "latency", 1);
        AMediaFormat_setInt32(f, "max-bframes", 0);
        AMediaFormat_setInt32(f, "prepend-sps-pps-to-idr-frames", 1);
        float gop_seconds = c.gop && c.gop < 0xffff ? (float)c.gop / fps : 3600.0f;
        AMediaFormat_setFloat(f, AMEDIAFORMAT_KEY_I_FRAME_INTERVAL, std::max(gop_seconds, 0.1f));
        if (with_profile) {
            int32_t profile = c.codec == VENC_HEVC ? (c.profile == 2 ? 2 : 1) : avc_profile(c.profile);
            if (profile) AMediaFormat_setInt32(f, "profile", profile);
        }
        media_status_t s = AMediaCodec_configure(codec, f, nullptr, nullptr, AMEDIACODEC_CONFIGURE_FLAG_ENCODE);
        AMediaFormat_delete(f);
        if (s == AMEDIA_OK) s = AMediaCodec_start(codec);
        if (s != AMEDIA_OK) {
            AMediaCodec_delete(codec);
            return nullptr;
        }
        return codec;
    }

    bool configure(const venc_config &c, const int *fds, int nfds) {
        release();
        if (c.inputs + c.outputs != (uint32_t)nfds || c.width < 64 || c.height < 64 || c.stride < c.width ||
            c.rows < c.height || (uint64_t)c.stride * c.rows * 3 / 2 > c.input_size) {
            LOG("bad config: %u+%u buffers for %d fds, %ux%u stride %u", c.inputs, c.outputs, nfds, c.width, c.height,
                c.stride);
            return false;
        }
        for (int i = 0; i < nfds; i++) {
            bool input = (uint32_t)i < c.inputs;
            size_t size = input ? c.input_size : c.output_size;
            void *p = mmap(nullptr, size, input ? PROT_READ : PROT_READ | PROT_WRITE, MAP_SHARED, fds[i], 0);
            if (p == MAP_FAILED) {
                LOG("could not map buffer %d: %s", i, strerror(errno));
                release();
                return false;
            }
            (input ? inputs_ : outputs_).push_back({(uint8_t *)p, size});
        }
        codec_ = create(c, true);
        if (!codec_) codec_ = create(c, false);
        if (!codec_) {
            LOG("no %s encoder for %ux%u", c.codec == VENC_HEVC ? "HEVC" : "H.264", c.width, c.height);
            release();
            return false;
        }
        config_ = c;
        dst_stride_ = (int32_t)c.width;
        dst_rows_ = (int32_t)c.height;
        using InputFormat = AMediaFormat *(*)(AMediaCodec *);
        static auto input_format = (InputFormat)dlsym(RTLD_DEFAULT, "AMediaCodec_getInputFormat");
        if (AMediaFormat *in = input_format ? input_format(codec_) : nullptr) {
            int32_t v;
            if (AMediaFormat_getInt32(in, "stride", &v) && v >= (int32_t)c.width) dst_stride_ = v;
            if (AMediaFormat_getInt32(in, "slice-height", &v) && v >= (int32_t)c.height) dst_rows_ = v;
            AMediaFormat_delete(in);
        }
        LOG("%s %ux%u at %.2f fps, %u kbit/s %s, input stride %d rows %d", c.codec == VENC_HEVC ? "HEVC" : "H.264",
            c.width, c.height, c.fps_num ? (double)c.fps_den / c.fps_num : 0.0, c.bitrate / 1000,
            c.vbr ? "VBR" : "CBR", dst_stride_, dst_rows_);
        return true;
    }

    static void touch(const venc_touch &t) {
        using SendTouch = void (*)(int, int, int, int);
        static SendTouch send_touch = [] {
            void *wayland = dlopen("libdroiddeckwayland.so", RTLD_NOW | RTLD_NOLOAD);
            auto fn = wayland ? (SendTouch)dlsym(wayland, "droiddeck_wayland_send_touch") : nullptr;
            if (!fn) LOG("remote touch unavailable: %s", dlerror());
            return fn;
        }();
        if (!send_touch || t.action > VENC_TOUCH_UP) return;
        int x = (int)((uint64_t)std::min(t.x, 65535u) * (kTouchWidth - 1) / 65535);
        int y = (int)((uint64_t)std::min(t.y, 65535u) * (kTouchHeight - 1) / 65535);
        send_touch((int)t.action, kRemoteTouchId, x, y);
    }

    void set_bitrate(uint32_t bitrate) {
        if (!codec_) return;
        AMediaFormat *p = AMediaFormat_new();
        AMediaFormat_setInt32(p, "video-bitrate", (int32_t)std::max(bitrate, 500000u));
        AMediaCodec_setParameters(codec_, p);
        AMediaFormat_delete(p);
    }

    bool encode(const venc_frame &f, uint32_t *bytes, bool *key) {
        if (!codec_ || f.input >= inputs_.size() || f.output >= outputs_.size()) return false;
        if (f.keyframe) {
            AMediaFormat *p = AMediaFormat_new();
            AMediaFormat_setInt32(p, "request-sync", 0);
            AMediaCodec_setParameters(codec_, p);
            AMediaFormat_delete(p);
        }
        ssize_t in = AMediaCodec_dequeueInputBuffer(codec_, 100000);
        if (in < 0) return false;
        size_t capacity = 0;
        uint8_t *dst = AMediaCodec_getInputBuffer(codec_, (size_t)in, &capacity);
        size_t need = (size_t)dst_stride_ * dst_rows_ + (size_t)dst_stride_ * ((config_.height + 1) / 2);
        if (!dst || capacity < need) return false;
        const uint8_t *src = inputs_[f.input].data;
        for (uint32_t y = 0; y < config_.height; y++)
            memcpy(dst + (size_t)y * dst_stride_, src + (size_t)y * config_.stride, config_.width);
        const uint8_t *src_uv = src + (size_t)config_.stride * config_.rows;
        uint8_t *dst_uv = dst + (size_t)dst_stride_ * dst_rows_;
        for (uint32_t y = 0; y < (config_.height + 1) / 2; y++)
            memcpy(dst_uv + (size_t)y * dst_stride_, src_uv + (size_t)y * config_.stride, config_.width);
        if (AMediaCodec_queueInputBuffer(codec_, (size_t)in, 0, need, (uint64_t)std::max<int64_t>(f.pts_us, 0), 0) !=
            AMEDIA_OK)
            return false;
        Mapping &out = outputs_[f.output];
        for (int tries = 0; tries < 20; tries++) {
            AMediaCodecBufferInfo info;
            ssize_t o = AMediaCodec_dequeueOutputBuffer(codec_, &info, 20000);
            if (o == AMEDIACODEC_INFO_TRY_AGAIN_LATER || o == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED ||
                o == AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED)
                continue;
            if (o < 0) return false;
            size_t size = 0;
            uint8_t *data = AMediaCodec_getOutputBuffer(codec_, (size_t)o, &size);
            const uint8_t *payload = data ? data + info.offset : nullptr;
            size_t length = data ? (size_t)info.size : 0;
            if (info.flags & kBufferFlagCodecConfig) {
                header_.assign(payload, payload + length);
                AMediaCodec_releaseOutputBuffer(codec_, (size_t)o, false);
                continue;
            }
            *key = (info.flags & kBufferFlagKeyFrame) != 0;
            bool prepend = *key && !header_.empty() &&
                           (length < header_.size() || memcmp(payload, header_.data(), header_.size()) != 0);
            size_t total = length + (prepend ? header_.size() : 0);
            bool fits = payload && total <= out.size;
            if (fits) {
                if (prepend) memcpy(out.data, header_.data(), header_.size());
                memcpy(out.data + (prepend ? header_.size() : 0), payload, length);
                *bytes = (uint32_t)total;
            }
            AMediaCodec_releaseOutputBuffer(codec_, (size_t)o, false);
            return fits;
        }
        return false;
    }

    int fd_;
    AMediaCodec *codec_ = nullptr;
    venc_config config_ = {};
    int32_t dst_stride_ = 0, dst_rows_ = 0;
    std::vector<Mapping> inputs_, outputs_;
    std::vector<uint8_t> header_;
    Decoder decoder_;
};

std::atomic<int> g_listener{-1};

void serve(int listener) {
    for (;;) {
        int fd = accept4(listener, nullptr, nullptr, SOCK_CLOEXEC);
        if (fd < 0) {
            if (errno == EINTR) continue;
            return;
        }
        std::thread([fd] {
            Session s(fd);
            s.run();
        }).detach();
    }
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_droiddeck_launcher_session_VideoEncodeBridge_nativeStart(JNIEnv *env, jobject, jstring jpath) {
    if (g_listener.load() >= 0) return JNI_TRUE;
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    sockaddr_un addr = {};
    addr.sun_family = AF_UNIX;
    bool fits = strlen(path) < sizeof addr.sun_path;
    if (fits) strcpy(addr.sun_path, path);
    int fd = fits ? socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0) : -1;
    if (fd >= 0) {
        unlink(path);
        if (bind(fd, (sockaddr *)&addr, sizeof addr) != 0 || listen(fd, 4) != 0) {
            LOG("could not listen on %s: %s", path, strerror(errno));
            close(fd);
            fd = -1;
        }
    }
    env->ReleaseStringUTFChars(jpath, path);
    if (fd < 0) return JNI_FALSE;
    g_listener.store(fd);
    std::thread(serve, fd).detach();
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_droiddeck_launcher_session_VideoEncodeBridge_nativeStop(JNIEnv *, jobject) {
    int fd = g_listener.exchange(-1);
    if (fd >= 0) {
        shutdown(fd, SHUT_RDWR);
        close(fd);
    }
}
