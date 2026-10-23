#pragma once
#include "xr_gpu_channel.h"
#include <atomic>
#include <condition_variable>
#include <mutex>
#include <thread>

class XrGpuCompletion {
public:
    ~XrGpuCompletion() { Stop(); }
    bool Submit(int peer, int fence, uint64_t frame) {
        int socket = fcntl(peer, F_DUPFD_CLOEXEC, 0);
        std::lock_guard<std::mutex> lock(mutex_);
        if (socket < 0 || stopping_ || pending_.socket >= 0) {
            if (socket >= 0) close(socket);
            close(fence); return false;
        }
        if (!worker_.joinable()) {
            try { worker_ = std::thread([this] { Run(); }); }
            catch (...) { close(socket); close(fence); return false; }
        }
        pending_ = {socket, fence, frame};
        ready_.notify_one(); return true;
    }
    void Stop() {
        { std::lock_guard<std::mutex> lock(mutex_); stopping_ = true; ready_.notify_all(); }
        if (worker_.joinable()) worker_.join();
    }
    uint64_t Failures() const { return failures_.load(); }
private:
    struct Job { int socket = -1, fence = -1; uint64_t frame{}; };
    std::mutex mutex_;
    std::condition_variable ready_;
    std::thread worker_;
    std::atomic<bool> stopping_{false};
    std::atomic<uint64_t> failures_{0};
    Job pending_;
    void Run() {
        for (;;) {
            Job job;
            {
                std::unique_lock<std::mutex> lock(mutex_);
                ready_.wait(lock, [this] { return stopping_ || pending_.socket >= 0; });
                job = pending_; pending_ = {};
                if (job.socket < 0) return;
            }
            bool complete = false;
            const int64_t deadline = xr_frame_now() + 5000000000LL;
            while (!stopping_ && xr_frame_now() < deadline) {
                pollfd wait{job.fence, POLLIN, 0};
                int result = poll(&wait, 1, 10);
                if (result < 0 && errno == EINTR) continue;
                if (result < 0 || (wait.revents & (POLLERR | POLLNVAL | POLLHUP))) break;
                if (result > 0 && (wait.revents & POLLIN)) { complete = true; break; }
            }
            // Retaining this socket binds the acknowledgement to its original connection.
            if (!complete || stopping_ || !xr_gpu_send(job.socket, &job.frame, sizeof(job.frame))) failures_++;
            close(job.fence); close(job.socket);
        }
    }
};
