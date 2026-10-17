#pragma once
#include <EGL/eglext.h>
#include <GLES3/gl31.h>
#include <GLES2/gl2ext.h>
#include <android/hardware_buffer.h>
#include "xr_gpu_channel.h"

class QuestGpuFrames {
public:
    bool Start(const std::string &filename, EGLDisplay display) {
        const char *extensions = reinterpret_cast<const char *>(glGetString(GL_EXTENSIONS));
        const char *eglExtensions = eglQueryString(display, EGL_EXTENSIONS);
        if (!extensions || !eglExtensions || !strstr(extensions, "GL_EXT_external_buffer")
            || !strstr(eglExtensions, "EGL_ANDROID_get_native_client_buffer")) return false;
        storage_ = reinterpret_cast<PFNGLBUFFERSTORAGEEXTERNALEXTPROC>(eglGetProcAddress("glBufferStorageExternalEXT"));
        client_ = reinterpret_cast<PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC>(eglGetProcAddress("eglGetNativeClientBufferANDROID"));
        if (strstr(extensions, "GL_OES_texture_view")) view_ = reinterpret_cast<PFNGLTEXTUREVIEWOESPROC>(eglGetProcAddress("glTextureViewOES"));
        else if (strstr(extensions, "GL_EXT_texture_view")) view_ = reinterpret_cast<PFNGLTEXTUREVIEWOESPROC>(eglGetProcAddress("glTextureViewEXT"));
        if (!storage_ || !client_ || !view_ || filename.size() >= sizeof(sockaddr_un::sun_path)) return false;
        const char *source = "#version 310 es\nlayout(local_size_x=8,local_size_y=8)in;"
            "layout(std430,binding=0)readonly buffer Source{uint pixels[];};"
            "layout(rgba8,binding=0)writeonly uniform highp image2D target;uniform bool bgra;"
            "void main(){ivec2 p=ivec2(gl_GlobalInvocationID.xy),size=imageSize(target);"
            "if(any(greaterThanEqual(p,size)))return;vec4 value=unpackUnorm4x8(pixels[p.y*size.x+p.x]);"
            "imageStore(target,p,bgra?value.bgra:value);}";
        GLuint shader = glCreateShader(GL_COMPUTE_SHADER); glShaderSource(shader, 1, &source, nullptr); glCompileShader(shader);
        program_ = glCreateProgram(); glAttachShader(program_, shader); glLinkProgram(program_); glDeleteShader(shader);
        GLint linked{}; glGetProgramiv(program_, GL_LINK_STATUS, &linked);
        if (!linked) { Stop(); return false; }
        lock_ = open((filename + ".lock").c_str(), O_CREAT | O_RDWR | O_CLOEXEC | O_NOFOLLOW, 0600);
        struct stat status{};
        if (lock_ < 0 || fstat(lock_, &status) || !S_ISREG(status.st_mode) || status.st_uid != getuid()
            || flock(lock_, LOCK_EX | LOCK_NB)) { Stop(); return false; }
        if (lstat(filename.c_str(), &status) == 0) {
            if (!S_ISSOCK(status.st_mode) || status.st_uid != getuid() || unlink(filename.c_str())) { Stop(); return false; }
        } else if (errno != ENOENT) { Stop(); return false; }
        listener_ = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
        sockaddr_un address{}; address.sun_family = AF_UNIX; strcpy(address.sun_path, filename.c_str());
        if (listener_ < 0 || bind(listener_, reinterpret_cast<sockaddr *>(&address), sizeof(address))) { Stop(); return false; }
        filename_ = filename;
        if (chmod(filename.c_str(), 0600) || listen(listener_, 2)) { Stop(); return false; }
        return true;
    }

    bool Take(DroidDeckXrFrameHeader &header, GLuint &texture) {
        if (listener_ < 0) return false;
        if (peer_ < 0) {
            peer_ = accept4(listener_, nullptr, nullptr, SOCK_CLOEXEC | SOCK_NONBLOCK);
            if (peer_ < 0) return false;
            if (!xr_frame_peer(peer_)) { Disconnect(); return false; }
            configured_ = false; deadline_ = xr_frame_now() + 2000000000LL;
        }
        pollfd ready{peer_, POLLIN, 0};
        if (poll(&ready, 1, 0) <= 0) {
            if (!configured_ && xr_frame_now() > deadline_) Disconnect();
            return false;
        }
        if (!(ready.revents & POLLIN)) { Disconnect(); return false; }
        if (!configured_) {
            XrGpuRequest request;
            if (!xr_gpu_receive(peer_, &request, sizeof(request)) || request.magic != XR_GPU_MAGIC
                || request.version != XR_GPU_VERSION || !xr_gpu_dimensions(request.width, request.height)
                || !Allocate(request.width, request.height)) { Disconnect(); return false; }
            XrGpuPoolInfo info; info.width = width_; info.height = height_;
            for (unsigned index = 0; index < XR_GPU_SLOTS; index++) info.sizes[index] = sizes_[index];
            configured_ = xr_gpu_send(peer_, &info, sizeof(info), fds_.data(), fds_.size());
            for (int &fd : fds_) { if (fd >= 0) close(fd); fd = -1; }
            if (!configured_) Disconnect();
            return false;
        }
        XrGpuFrame frame;
        if (!xr_gpu_receive(peer_, &frame, sizeof(frame))) { Disconnect(); return false; }
        auto state = xr_gpu_frame_state(frame, width_, height_, lastFrame_, xr_frame_now());
        if (state == XrGpuFrameState::Invalid) {
            rejected_++; Disconnect(); return false;
        }
        lastFrame_ = frame.header.frame_id;
        if (state == XrGpuFrameState::Stale) {
            // No GPU read was submitted, so the producer can safely reclaim a dropped frame.
            dropped_++;
            if (!xr_gpu_send(peer_, &frame.header.frame_id, sizeof(frame.header.frame_id))) Disconnect();
            return false;
        }
        if (!Output()) { Disconnect(); return false; }
        glMemoryBarrier(GL_ALL_BARRIER_BITS);
        glUseProgram(program_); glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, buffers_[frame.slot]);
        glUniform1i(glGetUniformLocation(program_, "bgra"), !!(frame.flags & XR_GPU_BGRA));
        glBindImageTexture(0, raw_, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA8);
        glDispatchCompute((width_ + 7) / 8, (height_ + 7) / 8, 1);
        glMemoryBarrier(GL_TEXTURE_FETCH_BARRIER_BIT | GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
        // The producer may overwrite this slot only after the native GPU has finished reading it.
        glFinish();
        if (glGetError() != GL_NO_ERROR) { rejected_++; Disconnect(); return false; }
        if (!xr_gpu_send(peer_, &frame.header.frame_id, sizeof(frame.header.frame_id))) Disconnect();
        received_++;
        header = frame.header;
        texture = header.color_space == DROIDDECK_XR_COLOR_SRGB_RGBA8 ? srgb_ : raw_;
        return true;
    }

    uint64_t Received() const { return received_; }
    uint64_t Rejected() const { return rejected_; }
    uint64_t Dropped() const { return dropped_; }
    void Stop() {
        Disconnect(); DropPool();
        glDeleteTextures(1, &srgb_); glDeleteTextures(1, &raw_); srgb_ = raw_ = 0;
        if (program_) glDeleteProgram(program_); program_ = 0;
        if (listener_ >= 0) close(listener_); listener_ = -1;
        if (!filename_.empty()) unlink(filename_.c_str()); filename_.clear();
        if (lock_ >= 0) close(lock_); lock_ = -1;
    }
private:
    int listener_ = -1, peer_ = -1, lock_ = -1;
    std::string filename_;
    bool configured_ = false;
    int64_t deadline_{};
    uint32_t width_{}, height_{}, outputWidth_{}, outputHeight_{};
    uint64_t lastFrame_{}, received_{}, rejected_{}, dropped_{};
    std::array<AHardwareBuffer *, XR_GPU_SLOTS> hardware_{};
    std::array<GLuint, XR_GPU_SLOTS> buffers_{};
    std::array<int, XR_GPU_SLOTS> fds_{{-1, -1, -1}};
    std::array<uint64_t, XR_GPU_SLOTS> sizes_{};
    GLuint raw_{}, srgb_{}, program_{};
    PFNGLBUFFERSTORAGEEXTERNALEXTPROC storage_{};
    PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC client_{};
    PFNGLTEXTUREVIEWOESPROC view_{};

    void Disconnect() { if (peer_ >= 0) close(peer_); peer_ = -1; configured_ = false; lastFrame_ = 0; }
    void DropPool() {
        if (buffers_[0]) glFinish();
        glDeleteBuffers(buffers_.size(), buffers_.data()); buffers_.fill(0);
        for (auto *&buffer : hardware_) { if (buffer) AHardwareBuffer_release(buffer); buffer = nullptr; }
        for (int &fd : fds_) { if (fd >= 0) close(fd); fd = -1; }
    }
    static int AllocationFd(AHardwareBuffer *buffer, uint64_t bytes, uint64_t &allocation) {
        int pair[2];
        if (socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0, pair)) return -1;
        if (AHardwareBuffer_sendHandleToUnixSocket(buffer, pair[0])) { close(pair[0]); close(pair[1]); return -1; }
        close(pair[0]);
        int selected = -1; unsigned candidates = 0;
        for (;;) {
            unsigned char data[1024]; iovec bytesReceived{data, sizeof(data)};
            alignas(cmsghdr) unsigned char control[CMSG_SPACE(32 * sizeof(int))]{};
            msghdr message{}; message.msg_iov = &bytesReceived; message.msg_iovlen = 1;
            message.msg_control = control; message.msg_controllen = sizeof(control);
            ssize_t result = recvmsg(pair[1], &message, MSG_CMSG_CLOEXEC);
            if (result < 0 && errno == EINTR) continue;
            if (result <= 0) break;
            for (auto *item = CMSG_FIRSTHDR(&message); item; item = CMSG_NXTHDR(&message, item)) {
                if (item->cmsg_level != SOL_SOCKET || item->cmsg_type != SCM_RIGHTS) continue;
                auto *fds = reinterpret_cast<int *>(CMSG_DATA(item));
                for (unsigned index = 0; index < (item->cmsg_len - CMSG_LEN(0)) / sizeof(int); index++) {
                    struct stat status{};
                    if (!fstat(fds[index], &status) && status.st_size >= static_cast<int64_t>(bytes)
                        && status.st_size <= static_cast<int64_t>(bytes + 65536)) {
                        candidates++; if (selected >= 0) close(selected);
                        selected = fds[index]; allocation = status.st_size;
                    } else close(fds[index]);
                }
            }
            if (message.msg_flags & (MSG_TRUNC | MSG_CTRUNC)) { candidates = 0; break; }
        }
        close(pair[1]);
        if (candidates != 1) { if (selected >= 0) close(selected); return -1; }
        return selected;
    }
    bool Allocate(uint32_t width, uint32_t height) {
        DropPool(); width_ = width; height_ = height;
        const uint64_t bytes = uint64_t(width) * height * 4;
        GLint64 limit{}; glGetInteger64v(GL_MAX_SHADER_STORAGE_BLOCK_SIZE, &limit);
        if (bytes > static_cast<uint64_t>(limit)) return false;
        AHardwareBuffer_Desc description{}; description.width = bytes; description.height = description.layers = 1;
        description.format = AHARDWAREBUFFER_FORMAT_BLOB;
        description.usage = AHARDWAREBUFFER_USAGE_GPU_DATA_BUFFER;
        glGenBuffers(buffers_.size(), buffers_.data());
        for (unsigned index = 0; index < XR_GPU_SLOTS; index++) {
            if (AHardwareBuffer_allocate(&description, &hardware_[index])) { DropPool(); return false; }
            fds_[index] = AllocationFd(hardware_[index], bytes, sizes_[index]);
            if (fds_[index] < 0) { DropPool(); return false; }
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, buffers_[index]);
            storage_(GL_SHADER_STORAGE_BUFFER, 0, bytes, client_(hardware_[index]), 0);
            if (glGetError() != GL_NO_ERROR) { DropPool(); return false; }
        }
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);
        return true;
    }
    bool Output() {
        if (raw_ && outputWidth_ == width_ && outputHeight_ == height_) return true;
        glDeleteTextures(1, &srgb_); glDeleteTextures(1, &raw_); srgb_ = raw_ = 0;
        glGenTextures(1, &raw_); glBindTexture(GL_TEXTURE_2D, raw_);
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, width_, height_);
        glGenTextures(1, &srgb_); view_(srgb_, GL_TEXTURE_2D, raw_, GL_SRGB8_ALPHA8, 0, 1, 0, 1);
        for (GLuint texture : {raw_, srgb_}) {
            glBindTexture(GL_TEXTURE_2D, texture);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        }
        outputWidth_ = width_; outputHeight_ = height_;
        return glGetError() == GL_NO_ERROR;
    }
};
