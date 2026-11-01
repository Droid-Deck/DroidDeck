/* ws2_32.dll stand-in for DiRT 3 and DiRT Showdown (32-bit).
 *
 * Both games close their motion telemetry UDP socket at the end of a race (and when leaving Joyride) and keep
 * calling sendto() on it; the WSAENOTSOCK (10038) failure stops the game. Every export here jumps to the
 * same-ordinal export of Wine's own ws2_32.dll, except that a sendto() failing with 10038 reports 0 bytes sent.
 * Same behaviour as DiRTTelemetryErrorFix by RoccoC (MIT), based on the fix by Carsten Gottschling. */

typedef unsigned short WCHAR;
typedef unsigned long DWORD;
typedef unsigned int UINT;
typedef unsigned int UINT_PTR;
typedef int BOOL;
typedef void *HMODULE;
#define WINAPI __attribute__((stdcall))
#define WSAENOTSOCK 10038
#define ORDINAL(n) ((const char *)(UINT_PTR)(n))

__attribute__((dllimport)) HMODULE WINAPI LoadLibraryW(const WCHAR *name);
__attribute__((dllimport)) void *WINAPI GetProcAddress(HMODULE module, const char *name);
__attribute__((dllimport)) UINT WINAPI GetSystemDirectoryW(WCHAR *buffer, UINT size);
__attribute__((dllimport)) DWORD WINAPI GetEnvironmentVariableW(const WCHAR *name, WCHAR *buffer, DWORD size);
__attribute__((dllimport)) DWORD WINAPI GetFileAttributesW(const WCHAR *name);

/* droiddeck-game-env recognises a swapped-in copy by this text. */
__attribute__((used)) const char dirtfix_marker[] = "droiddeck-dirt-ws2_32";

extern const unsigned short dirtfix_ordinals[];
extern const unsigned int dirtfix_count;
extern void *dirtfix_real[];

static int (WINAPI *real_sendto)(UINT_PTR, const char *, int, int, const void *, int);
static int (WINAPI *real_WSAGetLastError)(void);
static void (WINAPI *real_WSASetLastError)(int);

int WINAPI dirtfix_sendto(UINT_PTR s, const char *buf, int len, int flags, const void *to, int tolen)
{
    int ret = real_sendto(s, buf, len, flags, to, tolen);
    if (ret == -1 && real_WSAGetLastError() == WSAENOTSOCK)
    {
        real_WSASetLastError(0);
        return 0;
    }
    return ret;
}

static void append(WCHAR *dst, const char *src)
{
    while (*dst) dst++;
    while ((*dst++ = (WCHAR)(unsigned char)*src++));
}

static void append_number(WCHAR *dst, UINT n)
{
    char digits[12];
    int i = 11;

    digits[i] = 0;
    do digits[--i] = (char)('0' + n % 10); while (n /= 10);
    append(dst, digits + i);
}

/* When this file replaces the prefix's syswow64\ws2_32.dll, Wine's own copy is the i386-windows\ws2_32.dll in one
 * of the builtin DLL folders Wine lists in WINEDLLDIR0, WINEDLLDIR1, ... as NT paths (\??\Z:\...). */
static HMODULE load_wine_ws2_32(HMODULE self)
{
    WCHAR name[24], path[600];
    HMODULE real;
    UINT n, d;

    for (d = 0; d < 16; d++)
    {
        name[0] = 0;
        append(name, "WINEDLLDIR");
        append_number(name, d);
        n = GetEnvironmentVariableW(name, path, 500);
        if (!n || n >= 500) continue;
        if (path[0] == '\\' && path[1] == '?' && path[2] == '?' && path[3] == '\\') path[1] = '\\';
        append(path, "\\i386-windows\\ws2_32.dll");
        if (GetFileAttributesW(path) == (DWORD)-1) continue;
        if ((real = LoadLibraryW(path)) && real != self) return real;
    }
    n = GetSystemDirectoryW(path, 280);
    if (!n || n >= 280) return 0;
    path[n] = 0;
    append(path, "\\ws2_32.dll");
    real = LoadLibraryW(path);
    return real != self ? real : 0;
}

BOOL WINAPI DllMain(HMODULE self, DWORD reason, void *reserved)
{
    HMODULE real;
    UINT i;

    (void)reserved;
    if (reason != 1) return 1;
    if (!(real = load_wine_ws2_32(self))) return 0;
    for (i = 0; i < dirtfix_count; i++) dirtfix_real[i] = GetProcAddress(real, ORDINAL(dirtfix_ordinals[i]));
    real_sendto = GetProcAddress(real, ORDINAL(20));
    real_WSAGetLastError = GetProcAddress(real, ORDINAL(111));
    real_WSASetLastError = GetProcAddress(real, ORDINAL(112));
    return real_sendto && real_WSAGetLastError && real_WSASetLastError;
}
