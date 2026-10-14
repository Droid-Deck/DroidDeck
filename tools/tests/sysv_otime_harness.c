/* Host test for sysv.c: IPC_STAT's sem_otime turns non-zero with the first semop, which the usual
 * race-free init (and SteamVR's web helper) waits for. Linked with sysv.c, whose semget/semop/semctl
 * replace libc's. */
#define _GNU_SOURCE
#include <stdio.h>
#include <sys/ipc.h>
#include <sys/sem.h>

int main(void) {
    int creator = semget(0x5eed0001, 1, IPC_CREAT | IPC_EXCL | 0600);
    if (creator < 0) { perror("semget"); return 1; }
    struct semid_ds ds;
    if (semctl(creator, 0, IPC_STAT, &ds) != 0 || ds.sem_otime != 0 || ds.sem_ctime == 0) {
        printf("FAIL: a new set has otime %ld ctime %ld\n", (long)ds.sem_otime, (long)ds.sem_ctime);
        return 1;
    }
    struct sembuf up = {0, 1, 0};
    if (semop(creator, &up, 1) != 0) { perror("semop"); return 1; }
    int other = semget(0x5eed0001, 1, 0600);
    if (other < 0 || semctl(other, 0, IPC_STAT, &ds) != 0 || ds.sem_otime == 0) {
        printf("FAIL: otime still 0 after semop\n");
        return 1;
    }
    if (semctl(other, 0, GETVAL) != 1) { printf("FAIL: value\n"); return 1; }
    semctl(creator, 0, IPC_RMID);
    printf("PASS: sysv otime\n");
    return 0;
}
