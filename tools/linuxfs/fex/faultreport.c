#if !defined(__x86_64__)
#error faultreport is built for x86_64 guests only
#endif

#define SYS_read 0
#define SYS_write 1
#define SYS_close 3
#define SYS_rt_sigaction 13
#define SYS_getpid 39
#define SYS_gettid 186
#define SYS_tgkill 234
#define SYS_openat 257
#define AT_FDCWD (-100)
#define SIGILL 4
#define SA_SIGINFO 0x4UL
#define SA_RESTORER 0x04000000UL
#define SA_NODEFER 0x40000000UL
#define SA_RESETHAND 0x80000000UL

struct kernel_sigaction {
    void (*handler)(int, void *, void *);
    unsigned long flags;
    void (*restorer)(void);
    unsigned long mask;
};

static long sys(long n, long a, long b, long c, long d)
{
    long r;
    register long r10 __asm__("r10") = d;
    __asm__ volatile("syscall" : "=a"(r) : "a"(n), "D"(a), "S"(b), "d"(c), "r"(r10) : "rcx", "r11", "memory");
    return r;
}

static char out[1024];
static unsigned long used;
static char maps[65536];

static void put(const char *s)
{
    while (*s && used < sizeof(out) - 1)
        out[used++] = *s++;
}

static void put_hex(unsigned long v, int digits)
{
    char buf[17];
    for (int i = digits - 1; i >= 0; i--) {
        buf[i] = "0123456789abcdef"[v & 15];
        v >>= 4;
    }
    buf[digits] = 0;
    put(buf);
}

static unsigned long parse_hex(const char **p)
{
    unsigned long v = 0;
    for (;;) {
        char c = **p;
        if (c >= '0' && c <= '9') v = v * 16 + (c - '0');
        else if (c >= 'a' && c <= 'f') v = v * 16 + (c - 'a' + 10);
        else return v;
        (*p)++;
    }
}

static int find_mapping(unsigned long addr, const char **line, unsigned long *length, unsigned long *start, int *readable)
{
    long fd = sys(SYS_openat, AT_FDCWD, (long)"/proc/self/maps", 0, 0);
    if (fd < 0) return 0;
    unsigned long have = 0;
    long n;
    while (have < sizeof(maps) - 1 && (n = sys(SYS_read, fd, (long)(maps + have), sizeof(maps) - 1 - have, 0)) > 0)
        have += n;
    sys(SYS_close, fd, 0, 0, 0);
    maps[have] = 0;
    const char *p = maps;
    while (*p) {
        const char *begin = p;
        unsigned long lo = parse_hex(&p);
        if (*p == '-') p++;
        unsigned long hi = parse_hex(&p);
        while (*p == ' ') p++;
        int r = *p == 'r';
        const char *end = begin;
        while (*end && *end != '\n') end++;
        if (addr >= lo && addr < hi) {
            *line = begin;
            *length = end - begin;
            *start = lo;
            *readable = r && addr + 16 <= hi;
            return 1;
        }
        p = *end ? end + 1 : end;
    }
    return 0;
}

static void report(int sig, void *info, void *context)
{
    (void)context;
    unsigned long addr = *(unsigned long *)((char *)info + 16);
    int code = *(int *)((char *)info + 8);
    used = 0;
    put("droiddeck-fex: SIGILL at 0x");
    put_hex(addr, 12);
    put(" (si_code ");
    put_hex((unsigned long)code, 2);
    put(")");
    const char *line;
    unsigned long length, start;
    int readable;
    if (find_mapping(addr, &line, &length, &start, &readable)) {
        put(", offset 0x");
        put_hex(addr - start, 8);
        if (readable) {
            put(", bytes");
            for (int i = 0; i < 16; i++) {
                put(" ");
                put_hex(((unsigned char *)addr)[i], 2);
            }
        }
        put("\n  in ");
        for (unsigned long i = 0; i < length && used < sizeof(out) - 2; i++)
            out[used++] = line[i];
    } else {
        put(", outside any mapping");
    }
    put("\n");
    sys(SYS_write, 2, (long)out, used, 0);
    sys(SYS_tgkill, sys(SYS_getpid, 0, 0, 0, 0), sys(SYS_gettid, 0, 0, 0, 0), sig, 0);
}

void faultreport_restore(void);
__asm__(".text\n.type faultreport_restore,@function\nfaultreport_restore:\nmov $15, %eax\nsyscall\nhlt\n");

__attribute__((constructor)) static void install(void)
{
    struct kernel_sigaction action = {report, SA_SIGINFO | SA_NODEFER | SA_RESETHAND | SA_RESTORER, faultreport_restore, 0};
    sys(SYS_rt_sigaction, SIGILL, (long)&action, 0, sizeof(action.mask));
}
