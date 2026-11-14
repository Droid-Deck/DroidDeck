"""Replicate com.droiddeck.launcher.ui.ThemeColors.palette() for the default theme.

Upstream defaults: DEFAULT_PRIMARY = 0xFF8B5CF6, DEFAULT_SECONDARY = 0xFF22D3EE.
Emits the resulting ArkUI palette as color.json entries.
"""

def srgb_lum(c):
    def ch(v):
        v = v / 255.0
        return v / 12.92 if v <= 0.03928 else ((v + 0.055) / 1.055) ** 2.4
    return 0.2126 * ch(c[0]) + 0.7152 * ch(c[1]) + 0.0722 * ch(c[2])

def to_hsv(argb):
    r = ((argb >> 16) & 0xFF) / 255.0
    g = ((argb >> 8) & 0xFF) / 255.0
    b = (argb & 0xFF) / 255.0
    top = max(r, g, b)
    delta = top - min(r, g, b)
    if delta == 0:
        h = 0.0
    elif top == r:
        h = 60.0 * (((g - b) / delta) % 6)
    elif top == g:
        h = 60.0 * ((b - r) / delta + 2)
    else:
        h = 60.0 * ((r - g) / delta + 4)
    return (h + 360.0 if h < 0 else h, 0.0 if top == 0 else delta / top, top)

def from_hsv(h, s, v):
    h = h % 360.0
    s = max(0.0, min(1.0, s))
    v = max(0.0, min(1.0, v))
    i = int(h // 60)
    f = h / 60 - i
    p = v * (1 - s)
    q = v * (1 - s * f)
    t = v * (1 - s * (1 - f))
    table = [(v, t, p), (q, v, p), (p, v, t), (p, q, v), (t, p, v), (v, p, q)]
    r, g, b = table[i % 6]
    return (int(round(r * 255)), int(round(g * 255)), int(round(b * 255)))

def contrast(a, b):
    x, y = srgb_lum(a), srgb_lum(b)
    return (max(x, y) + 0.05) / (min(x, y) + 0.05)

def rgb(argb):
    return ((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF)

def pack(c):
    return (c[0] << 16) | (c[1] << 8) | c[2]

def readable(argb):
    if srgb_lum(rgb(argb)) >= 0.10:
        return argb
    h, s, v = to_hsv(argb)
    out = argb
    while v < 1 and srgb_lum(rgb(out)) < 0.10:
        v = min(1.0, v + 0.04)
        out = pack(from_hsv(h, s, v))
    while s > 0 and srgb_lum(rgb(out)) < 0.10:
        s = max(0.0, s - 0.04)
        out = pack(from_hsv(h, s, v))
    return out

def tint(argb, sat, bri):
    h, s, v = to_hsv(argb)
    strength = min(1.0, s / 0.35)
    return pack(from_hsv(h, sat * strength, bri))

DARK_INK = (0x03, 0x11, 0x1F)
WHITE = (255, 255, 255)

def ink_for(a, b):
    worst = lambda ink: min(contrast(ink, a), contrast(ink, b))
    return DARK_INK if worst(DARK_INK) >= worst(WHITE) else WHITE

def fit_to(ink, argb):
    short = lambda col: contrast(ink, col) < 4.5
    if not short(rgb(argb)):
        return argb
    h, s, v = to_hsv(argb)
    out = argb
    if ink == DARK_INK:
        while v < 1 and short(rgb(out)):
            v = min(1.0, v + 0.04)
            out = pack(from_hsv(h, s, v))
        while s > 0 and short(rgb(out)):
            s = max(0.0, s - 0.04)
            out = pack(from_hsv(h, s, v))
    else:
        while v > 0 and short(rgb(out)):
            v = max(0.0, v - 0.04)
            out = pack(from_hsv(h, s, v))
    return out

def gradient_color(a, b, f):
    def ch(i):
        av = a[i]
        bv = b[i]
        return int(round(max(0, min(255, av + (bv - av) * f))))
    return (ch(0), ch(1), ch(2))

def gradient_fits(ink, a, b):
    return all(contrast(ink, gradient_color(a, b, st / 32.0)) >= 4.52 for st in range(33))

def move_toward(ink, argb):
    h, s, v = to_hsv(argb)
    if ink == DARK_INK:
        if v < 1.0:
            return pack(from_hsv(h, s, min(1.0, v + 0.04)))
        if s > 0.0:
            return pack(from_hsv(h, max(0.0, s - 0.04), v))
        return argb
    return pack(from_hsv(h, s, max(0.0, v - 0.04)))

def build(primary, secondary):
    seen = readable(primary)
    seen2 = readable(secondary)
    ink = ink_for(rgb(seen), rgb(seen2))
    first = fit_to(ink, primary)
    second = fit_to(ink, secondary)
    for _ in range(80):
        if gradient_fits(ink, rgb(first), rgb(second)):
            break
        nf = move_toward(ink, first)
        ns = move_toward(ink, second)
        if rgb(nf) == rgb(first) and rgb(ns) == rgb(second):
            break
        first, second = nf, ns
    return {
        'primary': rgb(first),
        'primary2': rgb(second),
        'onPrimary': ink,
        'background': rgb(tint(first, 0.50, 0.045)),
        'surface': rgb(tint(first, 0.45, 0.075)),
        'surfaceVariant': rgb(tint(first, 0.42, 0.115)),
        'line': rgb(tint(first, 0.40, 0.16)),
        'line2': rgb(tint(first, 0.36, 0.22)),
        'onBackground': rgb(tint(first, 0.05, 0.97)),
        'onSurfaceVariant': rgb(tint(first, 0.16, 0.68)),
        'good': (0x4C, 0xD3, 0x7F),
    }

def hexc(c):
    return '#%02X%02X%02X' % c

if __name__ == '__main__':
    pal = build(0xFF8B5CF6, 0xFF22D3EE)
    for k, v in pal.items():
        print('    { "name": "%s", "value": "%s" },' % (k, hexc(v)))
