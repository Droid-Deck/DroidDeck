#define _GNU_SOURCE
#include <errno.h>
#include <pthread.h>
#include <stdio.h>
#include <string.h>
#include <sys/ipc.h>
#include <sys/msg.h>
#include <sys/wait.h>
#include <unistd.h>

struct note { long mtype; char text[32]; };

static int count_lines(const char *path) {
    FILE *f = fopen(path, "r");
    if (!f) return -1;
    int n = 0, c;
    while ((c = fgetc(f)) != EOF) n += c == '\n';
    fclose(f);
    return n;
}

static void *wait_for_note(void *arg) {
    struct note n;
    long rc = msgrcv(*(int *)arg, &n, sizeof(n.text), 0, 0);
    return (void *)(rc < 0 && errno == EIDRM ? 1L : 0L);
}

int main(void) {
    int q = msgget(0x5eed0002, IPC_CREAT | IPC_EXCL | 0600);
    if (q < 0) { perror("msgget"); return 1; }
    struct note n;
    if (msgrcv(q, &n, sizeof(n.text), 0, IPC_NOWAIT) != -1 || errno != ENOMSG) { printf("FAIL: empty queue\n"); return 1; }
    pid_t child = fork();
    if (child == 0) {
        struct note a = {2, "second"}, b = {1, "first"};
        return msgsnd(q, &a, sizeof(a.text), 0) || msgsnd(q, &b, sizeof(b.text), 0);
    }
    int status;
    waitpid(child, &status, 0);
    if (!WIFEXITED(status) || WEXITSTATUS(status)) { printf("FAIL: child send\n"); return 1; }
    if (msgrcv(q, &n, sizeof(n.text), 1, 0) < 0 || n.mtype != 1 || strcmp(n.text, "first")) { printf("FAIL: typed receive\n"); return 1; }
    if (msgrcv(q, &n, sizeof(n.text), 0, 0) < 0 || n.mtype != 2 || strcmp(n.text, "second")) { printf("FAIL: any receive\n"); return 1; }
    int maps = count_lines("/proc/self/maps");
    for (int i = 0; i < 2000; i++) {
        struct note p = {3, "poll"};
        if (msgsnd(q, &p, sizeof(p.text), 0) || msgrcv(q, &n, sizeof(n.text), 0, 0) < 0 ||
            msgrcv(q, &n, sizeof(n.text), 0, IPC_NOWAIT) != -1) { printf("FAIL: round %d\n", i); return 1; }
    }
    if (count_lines("/proc/self/maps") != maps) { printf("FAIL: mappings grew\n"); return 1; }
    pthread_t waiter;
    pthread_create(&waiter, NULL, wait_for_note, &q);
    usleep(100000);
    if (msgctl(q, IPC_RMID, NULL) != 0) { perror("msgctl"); return 1; }
    void *woken;
    pthread_join(waiter, &woken);
    if (!woken) { printf("FAIL: waiter not told the queue was removed\n"); return 1; }
    if (msgsnd(q, &n, sizeof(n.text), IPC_NOWAIT) != -1) { printf("FAIL: send to a removed queue\n"); return 1; }
    int again = msgget(0x5eed0002, IPC_CREAT | 0600);
    struct note r = {4, "again"};
    if (again < 0 || msgsnd(again, &r, sizeof(r.text), 0) || msgrcv(again, &n, sizeof(n.text), 0, 0) < 0 || strcmp(n.text, "again")) {
        printf("FAIL: queue made again after removal\n");
        return 1;
    }
    msgctl(again, IPC_RMID, NULL);
    int watched = msgget(0x5eed0003, IPC_CREAT | 0600);
    pid_t blocked = fork();
    if (blocked == 0) return msgrcv(watched, &n, sizeof(n.text), 0, 0) < 0 && errno == EIDRM ? 0 : 1;
    int filler[64];
    for (int i = 0; i < 64; i++) {
        struct note f = {5, "fill"};
        filler[i] = msgget(0x5eed0100 + i, IPC_CREAT | 0600);
        if (filler[i] < 0 || msgsnd(filler[i], &f, sizeof(f.text), 0)) { printf("FAIL: filler %d\n", i); return 1; }
    }
    usleep(100000);
    if (msgctl(watched, IPC_RMID, NULL) != 0) { perror("msgctl with a full cache"); return 1; }
    waitpid(blocked, &status, 0);
    if (!WIFEXITED(status) || WEXITSTATUS(status)) { printf("FAIL: waiter not woken with a full cache\n"); return 1; }
    int extra = msgget(0x5eed0200, IPC_CREAT | 0600);
    struct note x = {6, "extra"};
    if (extra < 0 || msgsnd(extra, &x, sizeof(x.text), 0) || msgrcv(extra, &n, sizeof(n.text), 6, 0) < 0 || strcmp(n.text, "extra")) {
        printf("FAIL: queue past a full cache\n");
        return 1;
    }
    msgctl(extra, IPC_RMID, NULL);
    for (int i = 0; i < 64; i++) msgctl(filler[i], IPC_RMID, NULL);
    printf("PASS: sysv msg\n");
    return 0;
}
