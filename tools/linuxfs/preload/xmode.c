/*
 * The Steam client's own screen, in a Follow screen session.
 *
 * With two Xwayland servers the client sizes them through GAMESCOPE_XWAYLAND_MODE_CONTROL
 * ({server, width, height, allow_super_res} on the root window): the games' server (1) to a game's
 * Game Resolution, and its own (0) to what it recommends for the display - 1920x1080 on a Fold's
 * 2448x1848 inner panel. From then on it reads that 16:9 size back as the display's "native" one,
 * so its interface and every game it starts are 16:9 with bars on a near-square panel, and stay so
 * when the phone folds onto the cover screen.
 *
 * gamescope already keeps server 0 at the output's size and resizes it when the output changes (a
 * Steam Deck docking; here, the app resizing the output as the phone folds and unfolds). So in a
 * Follow screen session (BL_FOLLOW_OUTPUT=1) a request for server 0 is dropped and the client's
 * screen stays the panel's; requests for the games' servers pass through untouched. Any other
 * session, and any other property, is passed straight on.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* Xlib's types, without needing its headers in the build image. */
typedef struct _XDisplay Display;
typedef unsigned long Atom;
typedef unsigned long Window;
typedef int Bool;

typedef int (*change_property_fn)(Display *, Window, Atom, Atom, int, int, const unsigned char *, int);
typedef Atom (*intern_atom_fn)(Display *, const char *, Bool);

static int following(void) {
  static int cached = -1;
  if (cached < 0) {
    const char *v = getenv("BL_FOLLOW_OUTPUT");
    cached = v && v[0] == '1';
  }
  return cached;
}

int XChangeProperty(Display *dpy, Window w, Atom property, Atom type, int format, int mode,
                    const unsigned char *data, int nelements) {
  static change_property_fn real;
  if (!real) real = (change_property_fn)dlsym(RTLD_NEXT, "XChangeProperty");
  if (!real) return 0;
  if (following() && format == 32 && nelements == 4 && data) {
    static intern_atom_fn intern;
    static Display *atom_dpy;
    static Atom mode_control;
    if (!intern) intern = (intern_atom_fn)dlsym(RTLD_NEXT, "XInternAtom");
    /* Atoms belong to a connection; the client's is the one that writes this. */
    if (intern && atom_dpy != dpy) {
      mode_control = intern(dpy, "GAMESCOPE_XWAYLAND_MODE_CONTROL", 1);
      atom_dpy = dpy;
    }
    /* Format-32 property data is an array of long in Xlib. */
    const long *v = (const long *)data;
    if (mode_control && property == mode_control && v[0] == 0) {
      static int said;
      if (said++ < 3)
        fprintf(stderr, "== steam: kept its screen at the panel's size (it asked for %ldx%ld)\n", v[1], v[2]);
      return 1;
    }
  }
  return real(dpy, w, property, type, format, mode, data, nelements);
}
