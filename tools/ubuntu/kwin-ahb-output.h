#pragma once

#include "core/graphicsbuffer.h"
#include "core/graphicsbufferallocator.h"
#include "droiddeck_ahb_protocol.h"

#include <algorithm>
#include <cstddef>
#include <cstdio>
#include <cstring>
#include <drm_fourcc.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <unistd.h>

namespace KWin::Wayland
{

class AndroidOutputBuffer final : public GraphicsBuffer
{
public:
    AndroidOutputBuffer(FileDescriptor socket, FileDescriptor plane,
                        const GraphicsBufferOptions &options, const droiddeck_ahb_reply &reply)
        : m_socket(std::move(socket))
    {
        m_attributes.planeCount = 1;
        m_attributes.width = options.size.width();
        m_attributes.height = options.size.height();
        m_attributes.format = options.format;
        m_attributes.modifier = reply.modifier;
        m_attributes.fd[0] = std::move(plane);
        m_attributes.pitch[0] = reply.stride;
        m_attributes.offset[0] = reply.offset;
    }

    QSize size() const override { return {m_attributes.width, m_attributes.height}; }
    bool hasAlphaChannel() const override { return alphaChannelFromDrmFormat(m_attributes.format); }
    const DmaBufAttributes *dmabufAttributes() const override { return &m_attributes; }

private:
    FileDescriptor m_socket;
    DmaBufAttributes m_attributes;
};

class AndroidOutputAllocator final : public GraphicsBufferAllocator
{
public:
    GraphicsBuffer *allocate(const GraphicsBufferOptions &options) override
    {
        if (options.software || options.size.isEmpty()
            || options.size.width() > 8192 || options.size.height() > 8192
            || (!options.modifiers.isEmpty() && !options.modifiers.contains(DRM_FORMAT_MOD_LINEAR))) {
            return nullptr;
        }
        FileDescriptor connection(socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0));
        if (!connection) {
            return nullptr;
        }
        const timeval timeout{2, 0};
        setsockopt(connection.get(), SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
        setsockopt(connection.get(), SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout));
        sockaddr_un address{};
        address.sun_family = AF_UNIX;
        const int length = snprintf(address.sun_path + 1, sizeof(address.sun_path) - 1,
                                    "%s-%u", DROIDDECK_AHB_SOCKET, unsigned(getuid()));
        if (length < 0 || size_t(length) >= sizeof(address.sun_path) - 1
            || connect(connection.get(), reinterpret_cast<sockaddr *>(&address),
                       offsetof(sockaddr_un, sun_path) + 1 + length) != 0) {
            return nullptr;
        }
        ucred credential{};
        socklen_t credentialSize = sizeof(credential);
        if (getsockopt(connection.get(), SOL_SOCKET, SO_PEERCRED, &credential, &credentialSize) != 0
            || credential.uid != getuid()) {
            return nullptr;
        }
        const droiddeck_ahb_request request{DROIDDECK_AHB_OUTPUT_MAGIC,
            uint32_t(options.size.width()), uint32_t(options.size.height()), options.format};
        if (send(connection.get(), &request, sizeof(request), MSG_NOSIGNAL) != sizeof(request)) {
            return nullptr;
        }
        droiddeck_ahb_reply reply{};
        iovec vector{&reply, sizeof(reply)};
        alignas(cmsghdr) char control[CMSG_SPACE(sizeof(int) * 4)]{};
        msghdr message{};
        message.msg_iov = &vector;
        message.msg_iovlen = 1;
        message.msg_control = control;
        message.msg_controllen = sizeof(control);
        const ssize_t received = recvmsg(connection.get(), &message, MSG_CMSG_CLOEXEC);
        FileDescriptor plane;
        unsigned count = 0;
        for (auto header = CMSG_FIRSTHDR(&message); header; header = CMSG_NXTHDR(&message, header)) {
            if (header->cmsg_level != SOL_SOCKET || header->cmsg_type != SCM_RIGHTS
                || header->cmsg_len < CMSG_LEN(0)) {
                continue;
            }
            const auto descriptors = reinterpret_cast<const int *>(CMSG_DATA(header));
            const size_t entries = (header->cmsg_len - CMSG_LEN(0)) / sizeof(int);
            for (size_t i = 0; i < entries; ++i) {
                FileDescriptor descriptor(descriptors[i]);
                if (++count == 1) {
                    plane = std::move(descriptor);
                }
            }
        }
        struct stat identity{};
        const uint64_t minimumSize = uint64_t(reply.offset)
            + uint64_t(reply.stride) * uint32_t(options.size.height());
        if (received != sizeof(reply) || (message.msg_flags & (MSG_TRUNC | MSG_CTRUNC))
            || reply.status || reply.reserved || reply.offset % 4 || count != 1 || !plane ||
            reply.modifier != DRM_FORMAT_MOD_LINEAR
            || reply.stride < uint64_t(options.size.width()) * 4 || reply.stride % 4
            || reply.size < minimumSize || fstat(plane.get(), &identity) != 0
            || identity.st_size <= 0 || uint64_t(identity.st_size) < minimumSize
            || uint64_t(identity.st_size) != reply.size) {
            return nullptr;
        }
        return new AndroidOutputBuffer(std::move(connection), std::move(plane), options, reply);
    }
};

inline AndroidOutputAllocator *androidOutputAllocator()
{
    static AndroidOutputAllocator allocator;
    return &allocator;
}

inline bool androidOutputEnabled()
{
    static const bool enabled = qEnvironmentVariable("KWIN_DROIDDECK_OUTPUT_AHB") == QStringLiteral("1");
    return enabled;
}

}
