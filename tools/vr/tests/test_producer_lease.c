#define _GNU_SOURCE
#define DROIDDECK_PRODUCER_SERVER
#include "../src/producer_lease.h"
#include <assert.h>
#include <stdatomic.h>

static pthread_mutex_t queue_lock = PTHREAD_MUTEX_INITIALIZER;
static atomic_int completed;
static atomic_int released;
static atomic_int quarantined;
static atomic_int available;
static atomic_int slow;

static int acquire(int64_t deadline) {
    assert(deadline > droiddeck_producer_now());
    if (!atomic_load(&available)) return 0;
    assert(pthread_mutex_lock(&queue_lock) == 0);
    atomic_fetch_add(&completed, 1);
    if (atomic_load(&slow)) usleep(170000);
    return 1;
}

static void release(void) {
    atomic_fetch_add(&released, 1);
    assert(pthread_mutex_unlock(&queue_lock) == 0);
}

static void quarantine(void) {
    atomic_store(&quarantined, 1);
}

static void wait_count(atomic_int *counter, int expected) {
    int64_t deadline = droiddeck_producer_now() + 1000000000LL;
    while (atomic_load(counter) != expected && droiddeck_producer_now() < deadline) usleep(1000);
    assert(atomic_load(counter) == expected);
}

int main(void) {
    char directory[] = "/tmp/droiddeck-producer-XXXXXX";
    assert(mkdtemp(directory));
    setenv("XDG_RUNTIME_DIR", directory, 1);
    char parent[128];
    assert(snprintf(parent, sizeof(parent), "%s/droiddeck-steamvr", directory) > 0);
    assert(mkdir(parent, 0755) == 0);
    struct droiddeck_producer_server server = {.acquire = acquire, .release = release, .quarantine = quarantine};
    assert(!droiddeck_producer_start(&server));
    assert(chmod(parent, 0700) == 0);
    char path[128];
    assert(droiddeck_producer_path(path, sizeof(path), getpid()));
    int regular = open(path, O_CREAT | O_RDWR, 0600);
    assert(regular >= 0); close(regular);
    assert(!droiddeck_producer_start(&server));
    assert(unlink(path) == 0);
    int stale = socket(AF_UNIX, SOCK_SEQPACKET, 0);
    struct sockaddr_un address = {.sun_family = AF_UNIX};
    strcpy(address.sun_path, path);
    assert(bind(stale, (struct sockaddr *)&address, sizeof(address)) == 0);
    close(stale);
    assert(droiddeck_producer_start(&server));
    struct stat metadata;
    assert(stat(server.path, &metadata) == 0 && (metadata.st_mode & 0777) == 0600);
    struct droiddeck_producer_server duplicate = server;
    assert(!droiddeck_producer_start(&duplicate));
    struct droiddeck_producer_lease lease;
    assert(!droiddeck_producer_acquire(getpid(), &lease));
    assert(!atomic_load(&completed));
    atomic_store(&available, 1);
    assert(droiddeck_producer_acquire(getpid(), &lease));
    assert(fcntl(lease.descriptor, F_GETFD) & FD_CLOEXEC);
    assert(pthread_mutex_trylock(&queue_lock) == EBUSY);
    uint64_t first_ticket = lease.ticket;
    usleep(300000);
    assert(pthread_mutex_trylock(&queue_lock) == EBUSY && !atomic_load(&released));
    assert(droiddeck_producer_release(&lease));
    assert(lease.descriptor == -1 && atomic_load(&released) == 1);
    assert(pthread_mutex_trylock(&queue_lock) == 0);
    assert(pthread_mutex_unlock(&queue_lock) == 0);
    assert(droiddeck_producer_acquire(getpid(), &lease));
    assert(lease.ticket > first_ticket);
    assert(droiddeck_producer_release(&lease));
    atomic_store(&slow, 1);
    assert(droiddeck_producer_acquire(getpid(), &lease));
    assert(droiddeck_producer_release(&lease));
    atomic_store(&slow, 0);
    int abandoned = socket(AF_UNIX, SOCK_SEQPACKET, 0);
    assert(connect(abandoned, (struct sockaddr *)&address, sizeof(address)) == 0);
    struct droiddeck_producer_message request = {DROIDDECK_PRODUCER_MAGIC, DROIDDECK_PRODUCER_ACQUIRE, 0};
    atomic_store(&slow, 1);
    int before = atomic_load(&completed);
    assert(send(abandoned, &request, sizeof(request), MSG_NOSIGNAL) == sizeof(request));
    wait_count(&completed, before + 1);
    close(abandoned);
    wait_count(&released, 4);
    assert(!atomic_load(&quarantined));
    atomic_store(&slow, 0);
    char wrong_path[128];
    assert(droiddeck_producer_path(wrong_path, sizeof(wrong_path), (uint32_t)getpid() + 1));
    assert(link(server.path, wrong_path) == 0);
    assert(!droiddeck_producer_acquire((uint32_t)getpid() + 1, &lease));
    assert(unlink(wrong_path) == 0);
    assert(chmod(parent, 0755) == 0);
    assert(!droiddeck_producer_acquire(getpid(), &lease));
    assert(chmod(parent, 0700) == 0);
    assert(droiddeck_producer_acquire(getpid(), &lease));
    close(lease.descriptor);
    wait_count(&quarantined, 1);
    assert(atomic_load(&released) == 4);
    assert(pthread_mutex_trylock(&queue_lock) == EBUSY);
    assert(pthread_join(server.thread, NULL) == 0);
    close(server.listener);
    assert(unlink(server.path) == 0);
    assert(rmdir(parent) == 0 && rmdir(directory) == 0);
    puts("producer lease: peer PID, private socket, completion gate, retained ownership and disconnect quarantine passed");
    return 0;
}
