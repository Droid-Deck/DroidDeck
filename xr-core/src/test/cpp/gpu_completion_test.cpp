#include "GpuCompletion.h"
#include <cassert>
#include <cstdio>
#include <dirent.h>
#include <chrono>

static unsigned descriptors() {
    DIR *dir=opendir("/proc/self/fd"); if(!dir)dir=opendir("/dev/fd");assert(dir);
    unsigned count=0;while(readdir(dir))count++;closedir(dir);return count;
}
static void pair(int sockets[2]) {assert(!socketpair(AF_UNIX,SOCK_SEQPACKET|SOCK_CLOEXEC|SOCK_NONBLOCK,0,sockets));}
static void signal(int fd) {const char byte=1;assert(write(fd,&byte,1)==1);}
static void expect(int socket,uint64_t frame) {
    uint64_t got{};assert(xr_gpu_wait(socket,1000));assert(xr_gpu_receive(socket,&got,sizeof(got)));assert(got==frame);
}
int main() {
    unsigned before=descriptors();
    {
        XrGpuCompletion completion;int sockets[2],fence[2];pair(sockets);assert(!pipe(fence));
        assert(completion.Submit(sockets[0],fence[0],41));
        assert(!xr_gpu_wait(sockets[1],50));signal(fence[1]);expect(sockets[1],41);close(fence[1]);
        int delayed[2];assert(!pipe(delayed));assert(completion.Submit(sockets[0],delayed[0],42));
        close(sockets[0]);int replacement[2];pair(replacement);
        signal(delayed[1]);expect(sockets[1],42);close(delayed[1]);assert(!xr_gpu_wait(replacement[1],20));
        int ready[2];assert(!pipe(ready));assert(completion.Submit(replacement[0],ready[0],43));signal(ready[1]);expect(replacement[1],43);close(ready[1]);
        int never[2];assert(!pipe(never));assert(completion.Submit(replacement[0],never[0],44));
        auto begin=std::chrono::steady_clock::now();completion.Stop();
        assert(std::chrono::steady_clock::now()-begin<std::chrono::seconds(1));
        assert(!xr_gpu_wait(replacement[1],20));close(never[1]);
        close(sockets[1]);close(replacement[0]);close(replacement[1]);
    }
    assert(descriptors()==before);
    std::puts("GPU completion passed: no early ACK, connection-bound ACK, cancellation and no descriptor leaks");
}
