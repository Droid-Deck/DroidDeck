// gstag: tags top-level X windows whose WM_CLASS contains <match> (any case) with
// STEAM_GAME=<appid>, so gamescope treats a game started outside Steam's process tree
// as the game (stand-in until a bionic winex11 sets STEAM_GAME itself).
// Usage: gstag <display> <appid> <match> <seconds> [stop when this pid exits]
// gamescope publishes focus (GAMESCOPE_FOCUSED_APP) and reads GAMESCOPECTRL_BASELAYER_APPID on its first X server
// (:0, Steam's); with several (DroidDeck: games on :1) those two are read and written there.
// If gamescope still focuses another app 8 s after the tag, it sets GAMESCOPECTRL_BASELAYER_APPID once to
// <appid>,769 (what Steam writes when it shows a game); Steam can change it again later (e.g. the Steam button).
#define _GNU_SOURCE
#include <errno.h>
#include <signal.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/select.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

static int fd;
static uint8_t rbuf[1 << 16];
static uint32_t pending[256]; static int npending;
static uint32_t tagged[256]; static int ntagged;

static int wr(const void *p, size_t n) {
    const uint8_t *b = p;
    while (n) { ssize_t k = write(fd, b, n); if (k <= 0) return -1; b += k; n -= (size_t)k; }
    return 0;
}
static int rd(void *p, size_t n) {
    uint8_t *b = p;
    while (n) { ssize_t k = read(fd, b, n); if (k <= 0) return -1; b += k; n -= (size_t)k; }
    return 0;
}
static size_t pad4(size_t n) { return (n + 3) & ~(size_t)3; }
static int known(uint32_t *list, int n, uint32_t w) { for (int i = 0; i < n; i++) if (list[i] == w) return 1; return 0; }

static void note_event(const uint8_t *e) {
    uint8_t type = e[0] & 0x7f;
    uint32_t win = 0;
    if (type == 16) win = *(uint32_t *)(e + 8);       // CreateNotify
    else if (type == 19) win = *(uint32_t *)(e + 8);  // MapNotify
    if (win && !known(tagged, ntagged, win) && !known(pending, npending, win) && npending < 256) pending[npending++] = win;
}

// Reads one packet. Returns 1 reply, 0 error, -1 io; events are noted and skipped.
static int reply(void) {
    for (;;) {
        if (rd(rbuf, 32)) return -1;
        if (rbuf[0] == 0) return 0;
        if (rbuf[0] == 1) {
            uint32_t extra = *(uint32_t *)(rbuf + 4) * 4;
            if (extra > sizeof rbuf - 32 || (extra && rd(rbuf + 32, extra))) return -1;
            return 1;
        }
        note_event(rbuf);
    }
}

static uint32_t intern(const char *name) {
    size_t n = strlen(name);
    uint8_t req[256] = {0};
    req[0] = 16;
    *(uint16_t *)(req + 2) = (uint16_t)(2 + pad4(n) / 4);
    *(uint16_t *)(req + 4) = (uint16_t)n;
    memcpy(req + 8, name, n);
    if (wr(req, 8 + pad4(n))) return 0;
    return reply() == 1 ? *(uint32_t *)(rbuf + 8) : 0;
}

// WM_CLASS of a window into out (both strings joined by '|'); 0 if none.
static int wm_class(uint32_t win, char *out, size_t cap) {
    uint8_t req[24] = {0};
    req[0] = 20;
    *(uint16_t *)(req + 2) = 6;
    *(uint32_t *)(req + 4) = win;
    *(uint32_t *)(req + 8) = 67;  // WM_CLASS
    *(uint32_t *)(req + 12) = 31; // STRING
    *(uint32_t *)(req + 20) = 64;
    if (wr(req, sizeof req) || reply() != 1) return 0;
    uint32_t n = *(uint32_t *)(rbuf + 16);
    if (!n || n >= cap) return 0;
    for (uint32_t i = 0; i < n; i++) out[i] = rbuf[32 + i] ? (char)rbuf[32 + i] : '|';
    out[n] = 0;
    return 1;
}

static void set_cards(uint32_t win, uint32_t prop, const uint32_t *values, uint32_t n) {
    uint8_t req[24 + 4 * 8] = {0};
    if (n > 8) n = 8;
    req[0] = 18;
    *(uint16_t *)(req + 2) = (uint16_t)(6 + n);
    *(uint32_t *)(req + 4) = win;
    *(uint32_t *)(req + 8) = prop;
    *(uint32_t *)(req + 12) = 6; // CARDINAL
    req[16] = 32;
    *(uint32_t *)(req + 20) = n;
    memcpy(req + 24, values, 4 * n);
    wr(req, 24 + 4 * n);
}

// First CARDINAL of a root property, or 0 when unset.
static uint32_t first_card(uint32_t root, uint32_t prop) {
    uint8_t gp[24] = {20, 0, 6, 0};
    *(uint32_t *)(gp + 4) = root;
    *(uint32_t *)(gp + 8) = prop;
    *(uint32_t *)(gp + 20) = 64;
    if (wr(gp, sizeof gp) || reply() != 1) return 0;
    return *(uint32_t *)(rbuf + 16) ? *(uint32_t *)(rbuf + 32) : 0;
}

static void set_card(uint32_t win, uint32_t prop, uint32_t value) {
    uint8_t req[28] = {0};
    req[0] = 18;
    *(uint16_t *)(req + 2) = 7;
    *(uint32_t *)(req + 4) = win;
    *(uint32_t *)(req + 8) = prop;
    *(uint32_t *)(req + 12) = 6; // CARDINAL
    req[16] = 32;
    *(uint32_t *)(req + 20) = 1;
    *(uint32_t *)(req + 24) = value;
    wr(req, sizeof req);
}

// Connects to display :<disp> (abstract socket) and returns the socket, its root window in *root; -1 on failure.
static int x_connect(int disp, uint32_t *root) {
    struct sockaddr_un sa = {.sun_family = AF_UNIX};
    int n = snprintf(sa.sun_path + 1, sizeof sa.sun_path - 1, "/tmp/.X11-unix/X%d", disp);
    fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (connect(fd, (struct sockaddr *)&sa, (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + n))) {
        printf("gstag: connect to :%d failed: %s\n", disp, strerror(errno)); close(fd); return -1;
    }
    uint8_t setup[12] = {'l', 0, 11, 0};
    if (wr(setup, sizeof setup) || rd(rbuf, 8)) { close(fd); return -1; }
    uint32_t extra = *(uint16_t *)(rbuf + 6) * 4u;
    if (extra > sizeof rbuf - 8 || rd(rbuf + 8, extra) || rbuf[0] != 1) { printf("gstag: :%d refused\n", disp); close(fd); return -1; }
    uint8_t *d = rbuf + 8;
    uint16_t vlen = *(uint16_t *)(d + 16);
    uint8_t nformats = d[21];
    *root = *(uint32_t *)(d + 32 + pad4(vlen) + 8u * nformats);
    return fd;
}

int main(int argc, char **argv) {
    if (argc < 5) { fprintf(stderr, "usage: %s <display> <appid> <match> <seconds> [pid]\n", argv[0]); return 2; }
    setvbuf(stdout, NULL, _IONBF, 0);
    int disp = atoi(argv[1]);
    uint32_t appid = (uint32_t)strtoul(argv[2], NULL, 10);
    const char *match = argv[3];
    int seconds = atoi(argv[4]);
    pid_t watch = argc > 5 ? (pid_t)atoi(argv[5]) : 0;

    uint32_t root, croot;
    int tfd = x_connect(disp, &root), cfd = tfd;
    if (tfd < 0) return 3;
    croot = root;
    if (disp != 0) {
        int c = x_connect(0, &croot);
        if (c >= 0) cfd = c;
        else croot = root;
    }
    fd = tfd;
    uint32_t a_game = intern("STEAM_GAME");

    uint8_t cwa[16] = {2, 0, 4, 0};  // ChangeWindowAttributes(root, CWEventMask=SubstructureNotify)
    *(uint32_t *)(cwa + 4) = root;
    *(uint32_t *)(cwa + 8) = 0x800;
    *(uint32_t *)(cwa + 12) = 0x80000;
    wr(cwa, sizeof cwa);

    uint8_t qt[8] = {15, 0, 2, 0};   // QueryTree(root): windows that exist already
    *(uint32_t *)(qt + 4) = root;
    if (wr(qt, sizeof qt) == 0 && reply() == 1) {
        uint16_t nk = *(uint16_t *)(rbuf + 16);
        uint32_t kids[1024];
        if (nk > 1024) nk = 1024;
        memcpy(kids, rbuf + 32, nk * 4u);
        for (int i = 0; i < nk && npending < 256; i++) pending[npending++] = kids[i];
    }
    printf("gstag: watching display %d for WM_CLASS ~ \"%s\" -> STEAM_GAME=%u\n", disp, match, appid);

    fd = cfd;  // atoms belong to the server they are interned on
    uint32_t a_apps = intern("GAMESCOPE_FOCUSABLE_APPS");
    uint32_t a_focus = intern("GAMESCOPE_FOCUSED_APP");
    uint32_t a_ctrl = intern("GAMESCOPECTRL_BASELAYER_APPID");
    fd = tfd;
    time_t tagged_at = 0; int focus_checked = 0;
    time_t end = time(NULL) + seconds, next_status = time(NULL) + 3;
    while (time(NULL) < end) {
        if (time(NULL) >= next_status) {
            next_status = time(NULL) + 5;
            printf("status:");
            uint32_t props[3] = {a_apps, a_focus, a_ctrl};
            const char *names[3] = {"focusable", "focused", "baselayer"};
            fd = cfd;
            for (int k = 0; k < 3; k++) {
                uint8_t gp[24] = {20, 0, 6, 0};
                *(uint32_t *)(gp + 4) = croot;
                *(uint32_t *)(gp + 8) = props[k];
                *(uint32_t *)(gp + 20) = 64;
                if (wr(gp, sizeof gp) == 0 && reply() == 1) {
                    uint32_t cnt = *(uint32_t *)(rbuf + 16);
                    printf(" %s=[", names[k]);
                    for (uint32_t i = 0; i < cnt && i < 16; i++) printf("%s%u", i ? "," : "", ((uint32_t *)(rbuf + 32))[i]);
                    printf("]");
                }
            }
            fd = tfd;
            for (int i = 0; i < ntagged; i++) {
                uint8_t ga[8] = {3, 0, 2, 0};  // GetWindowAttributes
                *(uint32_t *)(ga + 4) = tagged[i];
                int map = -1, ovr = -1, w = -1, h = -1;
                if (wr(ga, sizeof ga) == 0 && reply() == 1) { map = rbuf[26]; ovr = rbuf[27]; }
                uint8_t gg[8] = {14, 0, 2, 0}; // GetGeometry
                *(uint32_t *)(gg + 4) = tagged[i];
                if (wr(gg, sizeof gg) == 0 && reply() == 1) { w = *(uint16_t *)(rbuf + 16); h = *(uint16_t *)(rbuf + 18); }
                printf(" win0x%x=%dx%d map%d ovr%d", tagged[i], w, h, map, ovr);
            }
            printf("\n");
        }
        if (tagged_at && !focus_checked && time(NULL) - tagged_at >= 8) {
            focus_checked = 1;
            fd = cfd;
            uint32_t f = first_card(croot, a_focus);
            if (f != appid) {
                uint32_t ids[2] = {appid, 769};
                set_cards(croot, a_ctrl, ids, 2);
                printf("gstag: focus was %u, not %u, 8 s after the tag; set GAMESCOPECTRL_BASELAYER_APPID=%u,769\n", f, appid, appid);
            } else printf("gstag: %u has focus\n", appid);
            fd = tfd;
        }
        if (watch && kill(watch, 0) != 0 && errno == ESRCH) { printf("gstag: pid %d gone, stopping\n", watch); break; }
        for (int i = 0; i < npending; ) {
            char cls[512];
            uint32_t w = pending[i];
            if (wm_class(w, cls, sizeof cls) && strcasestr(cls, match)) {
                set_card(w, a_game, appid);
                if (ntagged < 256) tagged[ntagged++] = w;
                if (!tagged_at) tagged_at = time(NULL);
                printf("gstag: tagged window 0x%x (%s)\n", w, cls);
                pending[i] = pending[--npending];
            } else i++;
        }
        fd_set rs; FD_ZERO(&rs); FD_SET(fd, &rs);
        struct timeval tv = {0, 500000};
        if (select(fd + 1, &rs, NULL, NULL, &tv) > 0) {
            if (rd(rbuf, 32)) { printf("gstag: connection closed\n"); break; }
            if (rbuf[0] == 1) { uint32_t x = *(uint32_t *)(rbuf + 4) * 4; if (x && rd(rbuf + 32, x)) break; }
            else if (rbuf[0] != 0) note_event(rbuf);
        }
        if (npending > 200) npending = 0;
    }
    printf("gstag: done, %d window(s) tagged\n", ntagged);
    return 0;
}
