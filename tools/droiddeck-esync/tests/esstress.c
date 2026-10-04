#include <windows.h>
#include <winternl.h>
#include <stdio.h>
#include <stdlib.h>

static int failures;
#define CHECK(c, ...) do { if (!(c)) { failures++; printf("FAIL %s:%d: ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); fflush(stdout); } } while (0)

static volatile LONG counter;
static HANDLE mutex, sem, ev_auto, ev_manual, start_ev;
static volatile LONG stop;

static DWORD WINAPI mutex_worker(void *arg)
{
    int i;
    WaitForSingleObject(start_ev, INFINITE);
    for (i = 0; i < 20000; i++)
    {
        DWORD r = WaitForSingleObject(mutex, INFINITE);
        LONG v;
        if (r != WAIT_OBJECT_0) { CHECK(0, "mutex wait %lu", r); break; }
        v = counter;
        if (i % 7 == 0) WaitForSingleObject(mutex, 0), ReleaseMutex(mutex);
        counter = v + 1;
        ReleaseMutex(mutex);
    }
    return 0;
}

static void test_mutex_contention(void)
{
    HANDLE th[8];
    int i;
    counter = 0;
    mutex = CreateMutexA(NULL, FALSE, NULL);
    start_ev = CreateEventA(NULL, TRUE, FALSE, NULL);
    for (i = 0; i < 8; i++) th[i] = CreateThread(NULL, 0, mutex_worker, NULL, 0, NULL);
    SetEvent(start_ev);
    WaitForMultipleObjects(8, th, TRUE, INFINITE);
    CHECK(counter == 8 * 20000, "mutex counter %ld", counter);
    for (i = 0; i < 8; i++) CloseHandle(th[i]);
    CloseHandle(mutex);
    CloseHandle(start_ev);
    printf("mutex_contention ok counter=%ld\n", counter);
}

static volatile LONG produced, consumed;

static DWORD WINAPI sem_producer(void *arg)
{
    int i;
    for (i = 0; i < 50000; i++)
    {
        LONG prev;
        while (!ReleaseSemaphore(sem, 1, &prev))
        {
            CHECK(GetLastError() == ERROR_TOO_MANY_POSTS, "release err %lu", GetLastError());
            Sleep(0);
        }
        InterlockedIncrement(&produced);
    }
    return 0;
}

static DWORD WINAPI sem_consumer(void *arg)
{
    for (;;)
    {
        DWORD r = WaitForSingleObject(sem, 2000);
        if (r == WAIT_TIMEOUT) break;
        CHECK(r == WAIT_OBJECT_0, "sem wait %lu", r);
        InterlockedIncrement(&consumed);
    }
    return 0;
}

static void test_semaphore(void)
{
    HANDLE th[8];
    LONG prev;
    int i;
    produced = consumed = 0;
    sem = CreateSemaphoreA(NULL, 0, 16, NULL);
    for (i = 0; i < 4; i++) th[i] = CreateThread(NULL, 0, sem_producer, NULL, 0, NULL);
    for (i = 4; i < 8; i++) th[i] = CreateThread(NULL, 0, sem_consumer, NULL, 0, NULL);
    WaitForMultipleObjects(8, th, TRUE, INFINITE);
    CHECK(produced == 200000 && consumed == 200000, "sem produced %ld consumed %ld", produced, consumed);
    CHECK(!ReleaseSemaphore(sem, 17, &prev) && GetLastError() == ERROR_TOO_MANY_POSTS, "limit");
    CHECK(ReleaseSemaphore(sem, 16, &prev) && prev == 0, "prev %ld", prev);
    CHECK(!ReleaseSemaphore(sem, 1, &prev), "over max");
    for (i = 0; i < 8; i++) CloseHandle(th[i]);
    CloseHandle(sem);
    printf("semaphore ok %ld/%ld\n", produced, consumed);
}

static volatile LONG auto_hits;

static DWORD WINAPI auto_waiter(void *arg)
{
    while (!stop)
    {
        DWORD r = WaitForSingleObject(ev_auto, 50);
        if (r == WAIT_OBJECT_0) InterlockedIncrement(&auto_hits);
    }
    return 0;
}

static void test_auto_event(void)
{
    HANDLE th[6];
    int i;
    LONG expected = 0;
    stop = 0; auto_hits = 0;
    ev_auto = CreateEventA(NULL, FALSE, FALSE, NULL);
    for (i = 0; i < 6; i++) th[i] = CreateThread(NULL, 0, auto_waiter, NULL, 0, NULL);
    for (i = 0; i < 30000; i++)
    {
        LONG before = auto_hits;
        SetEvent(ev_auto);
        expected++;
        while (auto_hits == before) SwitchToThread();
    }
    stop = 1;
    WaitForMultipleObjects(6, th, TRUE, INFINITE);
    CHECK(auto_hits == expected, "auto hits %ld expected %ld", auto_hits, expected);
    CHECK(WaitForSingleObject(ev_auto, 0) == WAIT_TIMEOUT, "auto event left signaled");
    SetEvent(ev_auto); SetEvent(ev_auto);
    CHECK(WaitForSingleObject(ev_auto, 0) == WAIT_OBJECT_0, "auto set");
    CHECK(WaitForSingleObject(ev_auto, 0) == WAIT_TIMEOUT, "auto coalesce");
    for (i = 0; i < 6; i++) CloseHandle(th[i]);
    CloseHandle(ev_auto);
    printf("auto_event ok hits=%ld\n", auto_hits);
}

static volatile LONG manual_seen;

static DWORD WINAPI manual_waiter(void *arg)
{
    DWORD r = WaitForSingleObject(ev_manual, 10000);
    if (r == WAIT_OBJECT_0) InterlockedIncrement(&manual_seen);
    return 0;
}

static void test_manual_event(void)
{
    HANDLE th[16];
    int i, round;
    ev_manual = CreateEventA(NULL, TRUE, FALSE, NULL);
    for (round = 0; round < 50; round++)
    {
        manual_seen = 0;
        ResetEvent(ev_manual);
        for (i = 0; i < 16; i++) th[i] = CreateThread(NULL, 0, manual_waiter, NULL, 0, NULL);
        Sleep(2);
        SetEvent(ev_manual);
        WaitForMultipleObjects(16, th, TRUE, INFINITE);
        CHECK(manual_seen == 16, "manual round %d seen %ld", round, manual_seen);
        for (i = 0; i < 16; i++) CloseHandle(th[i]);
    }
    for (i = 0; i < 20000; i++)
    {
        SetEvent(ev_manual);
        if (i & 1) ResetEvent(ev_manual);
    }
    CHECK(WaitForSingleObject(ev_manual, 0) == WAIT_TIMEOUT, "manual final reset");
    SetEvent(ev_manual);
    CHECK(WaitForSingleObject(ev_manual, 0) == WAIT_OBJECT_0, "manual stays");
    CHECK(WaitForSingleObject(ev_manual, 0) == WAIT_OBJECT_0, "manual stays 2");
    CloseHandle(ev_manual);
    printf("manual_event ok\n");
}

static HANDLE wa_objs[3];
static volatile LONG wa_count;

static DWORD WINAPI wait_all_worker(void *arg)
{
    int i;
    for (i = 0; i < 3000; i++)
    {
        DWORD r = WaitForMultipleObjects(3, wa_objs, TRUE, 5000);
        if (r != WAIT_OBJECT_0) { CHECK(0, "wait all %lu", r); break; }
        InterlockedIncrement(&wa_count);
        ReleaseMutex(wa_objs[0]);
        ReleaseSemaphore(wa_objs[1], 1, NULL);
        SetEvent(wa_objs[2]);
    }
    return 0;
}

static void test_wait_all(void)
{
    HANDLE th[6], dup[2];
    int i;
    DWORD r;
    wa_count = 0;
    wa_objs[0] = CreateMutexA(NULL, FALSE, NULL);
    wa_objs[1] = CreateSemaphoreA(NULL, 1, 1, NULL);
    wa_objs[2] = CreateEventA(NULL, FALSE, TRUE, NULL);
    for (i = 0; i < 6; i++) th[i] = CreateThread(NULL, 0, wait_all_worker, NULL, 0, NULL);
    WaitForMultipleObjects(6, th, TRUE, INFINITE);
    CHECK(wa_count == 18000, "wait all count %ld", wa_count);
    dup[0] = wa_objs[2];
    DuplicateHandle(GetCurrentProcess(), wa_objs[2], GetCurrentProcess(), &dup[1], 0, FALSE, DUPLICATE_SAME_ACCESS);
    r = WaitForMultipleObjects(2, dup, TRUE, 0);
    CHECK(r == WAIT_FAILED && GetLastError() == ERROR_INVALID_PARAMETER, "dup wait all %lu %lu", r, GetLastError());
    CloseHandle(dup[1]);
    for (i = 0; i < 6; i++) CloseHandle(th[i]);
    for (i = 0; i < 3; i++) CloseHandle(wa_objs[i]);
    printf("wait_all ok %ld\n", wa_count);
}

static DWORD WINAPI hold_and_die(void *arg)
{
    WaitForSingleObject((HANDLE)arg, INFINITE);
    return 0;
}

static void test_abandoned(void)
{
    HANDLE m = CreateMutexA(NULL, FALSE, NULL);
    HANDLE th = CreateThread(NULL, 0, hold_and_die, m, 0, NULL);
    HANDLE objs[2];
    DWORD r;
    WaitForSingleObject(th, INFINITE);
    r = WaitForSingleObject(m, 1000);
    CHECK(r == WAIT_ABANDONED, "abandoned %lu", r);
    CHECK(ReleaseMutex(m), "release after abandon");
    th = CreateThread(NULL, 0, hold_and_die, m, 0, NULL);
    WaitForSingleObject(th, INFINITE);
    objs[0] = CreateEventA(NULL, TRUE, TRUE, NULL);
    objs[1] = m;
    r = WaitForMultipleObjects(2, objs, TRUE, 1000);
    CHECK(r == WAIT_ABANDONED_0, "abandoned all %lu", r);
    ReleaseMutex(m);
    r = WaitForMultipleObjects(2, objs, FALSE, 1000);
    CHECK(r == WAIT_OBJECT_0, "any after %lu", r);
    CloseHandle(th); CloseHandle(objs[0]); CloseHandle(m);
    printf("abandoned ok\n");
}

static volatile LONG apc_ran;
static void CALLBACK apc_proc(ULONG_PTR p) { InterlockedIncrement(&apc_ran); }

static HANDLE apc_ready;

static DWORD WINAPI alert_waiter(void *arg)
{
    SetEvent(apc_ready);
    return WaitForSingleObjectEx((HANDLE)arg, 5000, TRUE);
}

static void test_apc(void)
{
    HANDLE ev = CreateEventA(NULL, TRUE, FALSE, NULL);
    HANDLE th;
    DWORD r, code;
    int i;
    apc_ready = CreateEventA(NULL, FALSE, FALSE, NULL);
    for (i = 0; i < 200; i++)
    {
        apc_ran = 0;
        th = CreateThread(NULL, 0, alert_waiter, ev, 0, NULL);
        WaitForSingleObject(apc_ready, INFINITE);
        if (i & 1) Sleep(1);
        QueueUserAPC(apc_proc, th, 0);
        WaitForSingleObject(th, INFINITE);
        GetExitCodeThread(th, &code);
        CHECK(code == WAIT_IO_COMPLETION && apc_ran == 1, "apc round %d code %lu ran %ld", i, code, apc_ran);
        CloseHandle(th);
    }
    apc_ran = 0;
    QueueUserAPC(apc_proc, GetCurrentThread(), 0);
    r = SleepEx(1000, TRUE);
    CHECK(r == WAIT_IO_COMPLETION && apc_ran == 1, "sleepex %lu %ld", r, apc_ran);
    r = SleepEx(10, TRUE);
    CHECK(r == 0, "sleepex timeout %lu", r);
    QueueUserAPC(apc_proc, GetCurrentThread(), 0);
    r = WaitForSingleObjectEx(ev, 0, FALSE);
    CHECK(r == WAIT_TIMEOUT, "non alertable %lu", r);
    r = WaitForSingleObjectEx(ev, 0, TRUE);
    CHECK(r == WAIT_IO_COMPLETION, "alertable pending %lu", r);
    CloseHandle(ev);
    printf("apc ok\n");
}

static DWORD WINAPI close_waiter(void *arg)
{
    return WaitForSingleObject((HANDLE)arg, 300);
}

static void test_close_while_waiting(void)
{
    int i;
    for (i = 0; i < 300; i++)
    {
        HANDLE ev = CreateEventA(NULL, TRUE, FALSE, NULL), ev2, th;
        DWORD code;
        th = CreateThread(NULL, 0, close_waiter, ev, 0, NULL);
        Sleep(0);
        CloseHandle(ev);
        ev2 = CreateEventA(NULL, TRUE, TRUE, NULL);
        WaitForSingleObject(th, INFINITE);
        GetExitCodeThread(th, &code);
        CHECK(code == WAIT_TIMEOUT || code == WAIT_FAILED || code == WAIT_OBJECT_0, "close round %d code %lu", i, code);
        CHECK(WaitForSingleObject(ev2, 0) == WAIT_OBJECT_0, "reused handle state");
        CloseHandle(ev2);
        CloseHandle(th);
    }
    printf("close_while_waiting ok\n");
}

static void test_pulse(void)
{
    HANDLE ev = CreateEventA(NULL, TRUE, FALSE, NULL), th[4];
    DWORD code;
    int i, woke = 0;
    for (i = 0; i < 4; i++) th[i] = CreateThread(NULL, 0, close_waiter, ev, 0, NULL);
    Sleep(50);
    PulseEvent(ev);
    WaitForMultipleObjects(4, th, TRUE, INFINITE);
    for (i = 0; i < 4; i++) { GetExitCodeThread(th[i], &code); if (code == WAIT_OBJECT_0) woke++; CloseHandle(th[i]); }
    CHECK(WaitForSingleObject(ev, 0) == WAIT_TIMEOUT, "pulse left signaled");
    CHECK(woke == 4, "pulse woke %d of 4", woke);
    CloseHandle(ev);
    ev = CreateEventA(NULL, FALSE, FALSE, NULL);
    PulseEvent(ev);
    CHECK(WaitForSingleObject(ev, 0) == WAIT_TIMEOUT, "auto pulse without waiter left signaled");
    th[0] = CreateThread(NULL, 0, close_waiter, ev, 0, NULL);
    th[1] = CreateThread(NULL, 0, close_waiter, ev, 0, NULL);
    Sleep(50);
    PulseEvent(ev);
    WaitForMultipleObjects(2, th, TRUE, INFINITE);
    woke = 0;
    for (i = 0; i < 2; i++) { GetExitCodeThread(th[i], &code); if (code == WAIT_OBJECT_0) woke++; CloseHandle(th[i]); }
    CHECK(woke == 1, "auto pulse woke %d of 2", woke);
    CHECK(WaitForSingleObject(ev, 0) == WAIT_TIMEOUT, "auto pulse left signaled");
    CloseHandle(ev);
    printf("pulse ok\n");
}

static void test_semantics(void)
{
    HANDLE ev = CreateEventA(NULL, TRUE, TRUE, NULL), sem = CreateSemaphoreA(NULL, 2, 3, NULL), h[2];
    NTSTATUS (WINAPI *pNtWaitForSingleObject)(HANDLE, BOOLEAN, const LARGE_INTEGER *);
    LARGE_INTEGER when;
    LONG prev = -1;
    DWORD r, start;
    int i;
    pNtWaitForSingleObject = (void *)GetProcAddress(GetModuleHandleA("ntdll.dll"), "NtWaitForSingleObject");
    apc_ran = 0;
    QueueUserAPC(apc_proc, GetCurrentThread(), 0);
    r = WaitForSingleObjectEx(ev, INFINITE, TRUE);
    CHECK(r == WAIT_OBJECT_0 && apc_ran == 0, "object before apc %lu ran %ld", r, apc_ran);
    r = SleepEx(0, TRUE);
    CHECK(r == WAIT_IO_COMPLETION && apc_ran == 1, "apc after object %lu ran %ld", r, apc_ran);
    r = SleepEx(0, TRUE);
    CHECK(r == 0, "sleepex zero %lu", r);
    ResetEvent(ev);
    GetSystemTimeAsFileTime((FILETIME *)&when);
    when.QuadPart += 2000000;
    start = GetTickCount();
    r = pNtWaitForSingleObject(ev, FALSE, &when);
    CHECK(r == STATUS_TIMEOUT, "absolute timeout status %lx", r);
    CHECK(GetTickCount() - start >= 150 && GetTickCount() - start < 1500, "absolute timeout took %lu", GetTickCount() - start);
    CHECK(!ReleaseSemaphore(sem, 2, &prev) && GetLastError() == ERROR_TOO_MANY_POSTS, "semaphore limit");
    CHECK(ReleaseSemaphore(sem, 1, &prev) && prev == 2, "semaphore prev %ld", prev);
    for (i = 0; i < 3; i++) CHECK(WaitForSingleObject(sem, 0) == WAIT_OBJECT_0, "semaphore take %d", i);
    CHECK(WaitForSingleObject(sem, 0) == WAIT_TIMEOUT, "semaphore empty");
    h[0] = sem; h[1] = ev;
    ReleaseSemaphore(sem, 1, NULL);
    CHECK(WaitForMultipleObjects(2, h, TRUE, 50) == WAIT_TIMEOUT, "wait all partial");
    CHECK(ReleaseSemaphore(sem, 0, &prev) && prev == 1, "wait all rollback lost token prev %ld", prev);
    SetEvent(ev);
    CHECK(WaitForMultipleObjects(2, h, TRUE, 50) == WAIT_OBJECT_0, "wait all ready");
    CHECK(ReleaseSemaphore(sem, 0, &prev) && prev == 0, "wait all consumed prev %ld", prev);
    CHECK(WaitForSingleObject(ev, 0) == WAIT_OBJECT_0, "manual stays set");
    CloseHandle(sem);
    CloseHandle(ev);
    printf("semantics ok\n");
}

static HANDLE token_sem;
static volatile LONG token_count;

static DWORD WINAPI token_worker(void *arg)
{
    HANDLE h[2] = { token_sem, (HANDLE)arg };
    int i;
    for (i = 0; i < 20000; i++)
    {
        DWORD r = (i & 1) ? WaitForSingleObject(token_sem, INFINITE) : WaitForMultipleObjects(2, h, TRUE, INFINITE);
        if (r != WAIT_OBJECT_0) { CHECK(0, "token wait %lu", r); break; }
        if (InterlockedIncrement(&token_count) > 3) CHECK(0, "token overcommit");
        InterlockedDecrement(&token_count);
        if (!(i & 1)) SetEvent((HANDLE)arg);
        ReleaseSemaphore(token_sem, 1, NULL);
    }
    return 0;
}

static void test_semaphore_tokens(void)
{
    HANDLE th[6], ev = CreateEventA(NULL, FALSE, TRUE, NULL);
    LONG prev = -1;
    int i;
    token_sem = CreateSemaphoreA(NULL, 3, 3, NULL);
    for (i = 0; i < 6; i++) th[i] = CreateThread(NULL, 0, token_worker, ev, 0, NULL);
    WaitForMultipleObjects(6, th, TRUE, INFINITE);
    for (i = 0; i < 6; i++) CloseHandle(th[i]);
    CHECK(ReleaseSemaphore(token_sem, 0, &prev) && prev == 3, "token final count %ld", prev);
    for (i = 0; i < 3; i++) CHECK(WaitForSingleObject(token_sem, 0) == WAIT_OBJECT_0, "token drain %d", i);
    CHECK(WaitForSingleObject(token_sem, 0) == WAIT_TIMEOUT, "token extra");
    CHECK(WaitForSingleObject(ev, 0) == WAIT_OBJECT_0, "token event lost");
    CloseHandle(token_sem);
    CloseHandle(ev);
    printf("semaphore_tokens ok\n");
}

static volatile LONG churn_ready;

static DWORD WINAPI churn_mutex(void *arg)
{
    HANDLE h[2] = { (HANDLE)arg, ev_manual };
    unsigned int i = 0;
    if (WaitForMultipleObjects(2, h, TRUE, INFINITE) <= WAIT_ABANDONED_0 + 1) ReleaseMutex(h[0]);
    InterlockedIncrement(&churn_ready);
    for (;;)
    {
        DWORD r = (i++ & 1) ? WaitForSingleObject(h[0], INFINITE) : WaitForMultipleObjects(2, h, TRUE, INFINITE);
        if (r == WAIT_OBJECT_0 || r == WAIT_ABANDONED || r == WAIT_ABANDONED_0) ReleaseMutex(h[0]);
    }
    return 0;
}

static DWORD WINAPI blocked_waiter(void *arg)
{
    WaitForSingleObject((HANDLE)arg, 0);
    InterlockedIncrement(&churn_ready);
    return WaitForSingleObject((HANDLE)arg, INFINITE);
}

static void test_terminate(void)
{
    HANDLE th[4], m, ev;
    DWORD r, start;
    int round, i, stuck = 0;
    ev_manual = CreateEventA(NULL, TRUE, TRUE, NULL);
    for (round = 0; round < 150; round++)
    {
        m = CreateMutexA(NULL, FALSE, NULL);
        churn_ready = 0;
        for (i = 0; i < 4; i++) th[i] = CreateThread(NULL, 0, churn_mutex, m, 0, NULL);
        while (churn_ready < 4) Sleep(0);
        Sleep(round % 4);
        for (i = 0; i < 4; i++) TerminateThread(th[i], 7);
        WaitForMultipleObjects(4, th, TRUE, INFINITE);
        r = WaitForSingleObject(m, 3000);
        if (r == WAIT_TIMEOUT) stuck++;
        else ReleaseMutex(m);
        for (i = 0; i < 4; i++) CloseHandle(th[i]);
        CloseHandle(m);
    }
    CHECK(!stuck, "mutex stuck after terminate in %d rounds", stuck);
    ev = CreateEventA(NULL, TRUE, FALSE, NULL);
    churn_ready = 0;
    for (i = 0; i < 4; i++) th[i] = CreateThread(NULL, 0, blocked_waiter, ev, 0, NULL);
    while (churn_ready < 4) Sleep(0);
    Sleep(50);
    for (i = 0; i < 4; i++) TerminateThread(th[i], 7);
    WaitForMultipleObjects(4, th, TRUE, INFINITE);
    for (i = 0; i < 4; i++) CloseHandle(th[i]);
    start = GetTickCount();
    for (i = 0; i < 100; i++) PulseEvent(ev);
    CHECK(GetTickCount() - start < 400, "pulse after killed waiters took %lu ms", GetTickCount() - start);
    CHECK(WaitForSingleObject(ev, 0) == WAIT_TIMEOUT, "pulse left event set");
    CloseHandle(ev);
    CloseHandle(ev_manual);
    printf("terminate ok\n");
}

static void test_cross_process(const char *self)
{
    HANDLE ev = CreateEventA(NULL, FALSE, FALSE, "Local\\esstress_ev");
    HANDLE done = CreateEventA(NULL, TRUE, FALSE, "Local\\esstress_done");
    HANDLE sm = CreateSemaphoreA(NULL, 0, 100000, "Local\\esstress_sem");
    PROCESS_INFORMATION pi;
    STARTUPINFOA si = { sizeof(si) };
    char cmd[MAX_PATH + 16];
    DWORD r, code;
    int i;
    sprintf(cmd, "\"%s\" child", self);
    CHECK(CreateProcessA(NULL, cmd, NULL, NULL, FALSE, 0, NULL, NULL, &si, &pi), "createprocess %lu", GetLastError());
    for (i = 0; i < 5000; i++)
    {
        SetEvent(ev);
        r = WaitForSingleObject(sm, 5000);
        if (r != WAIT_OBJECT_0) { CHECK(0, "xproc wait %lu at %d", r, i); break; }
    }
    SetEvent(done);
    r = WaitForSingleObject(pi.hProcess, 10000);
    CHECK(r == WAIT_OBJECT_0, "child exit wait %lu", r);
    GetExitCodeProcess(pi.hProcess, &code);
    CHECK(code == 0, "child code %lu", code);
    CloseHandle(pi.hProcess); CloseHandle(pi.hThread);
    CloseHandle(ev); CloseHandle(done); CloseHandle(sm);
    printf("cross_process ok\n");
}

static int child(void)
{
    HANDLE ev = OpenEventA(SYNCHRONIZE | EVENT_MODIFY_STATE, FALSE, "Local\\esstress_ev");
    HANDLE done = OpenEventA(SYNCHRONIZE, FALSE, "Local\\esstress_done");
    HANDLE sm = OpenSemaphoreA(SEMAPHORE_MODIFY_STATE | SYNCHRONIZE, FALSE, "Local\\esstress_sem");
    HANDLE objs[2];
    objs[0] = done; objs[1] = ev;
    if (!ev || !done || !sm) return 1;
    for (;;)
    {
        DWORD r = WaitForMultipleObjects(2, objs, FALSE, 10000);
        if (r == WAIT_OBJECT_0) return 0;
        if (r != WAIT_OBJECT_0 + 1) return 2;
        ReleaseSemaphore(sm, 1, NULL);
    }
}

static void test_kill_holder(const char *self)
{
    HANDLE m = CreateMutexA(NULL, FALSE, "Local\\esstress_mutex");
    PROCESS_INFORMATION pi;
    STARTUPINFOA si = { sizeof(si) };
    char cmd[MAX_PATH + 16];
    DWORD r;
    sprintf(cmd, "\"%s\" holder", self);
    CreateProcessA(NULL, cmd, NULL, NULL, FALSE, 0, NULL, NULL, &si, &pi);
    Sleep(500);
    TerminateProcess(pi.hProcess, 7);
    WaitForSingleObject(pi.hProcess, INFINITE);
    r = WaitForSingleObject(m, 3000);
    CHECK(r == WAIT_ABANDONED, "killed holder %lu", r);
    ReleaseMutex(m);
    CloseHandle(pi.hProcess); CloseHandle(pi.hThread); CloseHandle(m);
    printf("kill_holder ok\n");
}

static int holder(void)
{
    HANDLE m = OpenMutexA(SYNCHRONIZE, FALSE, "Local\\esstress_mutex");
    WaitForSingleObject(m, INFINITE);
    Sleep(INFINITE);
    return 0;
}

static void test_many_objects(void)
{
    static HANDLE h[20000];
    int i, n = 0;
    for (i = 0; i < 20000; i++)
    {
        if (!(h[i] = CreateEventA(NULL, i & 1, i & 2, NULL))) break;
        n++;
    }
    CHECK(n == 20000, "created %d", n);
    for (i = 0; i < n; i++) CHECK(WaitForSingleObject(h[i], 0) == ((i & 2) ? WAIT_OBJECT_0 : WAIT_TIMEOUT), "obj %d", i);
    for (i = 0; i < n; i++) CloseHandle(h[i]);
    for (i = 0; i < 100000; i++)
    {
        HANDLE e = CreateEventA(NULL, FALSE, TRUE, NULL);
        if (WaitForSingleObject(e, 0) != WAIT_OBJECT_0) { CHECK(0, "churn %d", i); CloseHandle(e); break; }
        CloseHandle(e);
    }
    printf("many_objects ok\n");
}

static void test_timers_and_threads(void)
{
    HANDLE t = CreateWaitableTimerA(NULL, FALSE, NULL);
    LARGE_INTEGER due;
    DWORD start = GetTickCount(), r;
    int i;
    due.QuadPart = -10 * 1000 * 20;
    SetWaitableTimer(t, &due, 10, NULL, NULL, FALSE);
    for (i = 0; i < 20; i++)
    {
        r = WaitForSingleObject(t, 1000);
        CHECK(r == WAIT_OBJECT_0, "timer %d %lu", i, r);
    }
    CHECK(GetTickCount() - start < 2000, "timer slow %lu", GetTickCount() - start);
    CancelWaitableTimer(t);
    CloseHandle(t);
    r = WaitForSingleObject(GetCurrentProcess(), 10);
    CHECK(r == WAIT_TIMEOUT, "process self %lu", r);
    printf("timers ok\n");
}

int main(int argc, char **argv)
{
    if (argc > 1 && !strcmp(argv[1], "child")) return child();
    if (argc > 1 && !strcmp(argv[1], "holder")) return holder();
    test_mutex_contention();
    test_semaphore();
    test_auto_event();
    test_manual_event();
    test_wait_all();
    test_abandoned();
    test_apc();
    test_close_while_waiting();
    test_pulse();
    test_semantics();
    test_semaphore_tokens();
    if (argc > 1 && !strcmp(argv[1], "terminate")) test_terminate();
    test_cross_process(argv[0]);
    test_kill_holder(argv[0]);
    test_many_objects();
    test_timers_and_threads();
    printf("esstress: %d failures\n", failures);
    return failures ? 1 : 0;
}
