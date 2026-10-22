#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
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

    void release() {
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
