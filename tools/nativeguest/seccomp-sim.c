// Runs a program under a seccomp filter that answers the system calls Android's app policy blocks
// with SIGSYS, the way a stock app process does, for testing on builds that have no filter:
//   seccomp-sim [-a NR,NR...] PROGRAM [ARGS...]
// -a leaves the listed system calls allowed (to find which one kills a program).
#define _GNU_SOURCE
#include <errno.h>
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <unistd.h>

static const int blocked[] = {99, 100, 97, 217, 218, 219, 268, 277, 280, 293, 143, 144, 145, 146, 147, 149, 151, 152, 159};

#define MAX_ALLOWED 64
#define NBLOCKED (sizeof(blocked) / sizeof(blocked[0]))

int main(int argc, char **argv) {
    int skip[MAX_ALLOWED], nskip = 0, arg = 1;
    if (argc > 3 && strcmp(argv[1], "-a") == 0) {
        for (char *p = argv[2];; p++) {
            char *end = NULL;
            long nr = strtol(p, &end, 10);
            if (end == p || (*end != '\0' && *end != ',') || *p < '0' || *p > '9' ||
                nr < 0 || nr > 1023 || nskip == MAX_ALLOWED) {
                fprintf(stderr, "seccomp-sim: -a takes up to %d comma-separated system call numbers, not \"%s\"\n", MAX_ALLOWED, argv[2]);
                return 2;
            }
            skip[nskip++] = (int)nr;
            if (*end == '\0') break;
            p = end;
        }
        arg = 3;
    }
    if (arg >= argc) return 2;
    struct sock_filter filter[4 + 2 * MAX_ALLOWED + 2 + 2 * NBLOCKED + 1];
    int n = 0;
    filter[n++] = (struct sock_filter)BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, arch));
    filter[n++] = (struct sock_filter)BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, AUDIT_ARCH_AARCH64, 1, 0);
    filter[n++] = (struct sock_filter)BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW);
    filter[n++] = (struct sock_filter)BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr));
    for (int i = 0; i < nskip; i++) {
        filter[n++] = (struct sock_filter)BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, skip[i], 0, 1);
        filter[n++] = (struct sock_filter)BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW);
    }
    filter[n++] = (struct sock_filter)BPF_JUMP(BPF_JMP | BPF_JGE | BPF_K, 424, 0, 1);
    filter[n++] = (struct sock_filter)BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP);
    for (size_t i = 0; i < NBLOCKED; i++) {
        filter[n++] = (struct sock_filter)BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, blocked[i], 0, 1);
        filter[n++] = (struct sock_filter)BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP);
    }
    filter[n++] = (struct sock_filter)BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW);
    struct sock_fprog prog = { .len = (unsigned short)n, .filter = filter };
    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) || prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &prog)) {
        perror("seccomp-sim");
        return 1;
    }
    execv(argv[arg], argv + arg);
    perror("seccomp-sim: exec");
    return 127;
}
