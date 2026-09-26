#!/usr/bin/env python3
"""DroidDeck boot animation: procedural sound design.

Every sound is synthesized (no samples) and placed on the exact physics events the animation
engine reports (events.json, exported by events.py). Key: D major.

  legs (arch)   lands on a muted D
  ball          lands on A, the second bounce a quieter echo
  bowl          lands on F#
  balancing     silence
  jump          silence
  lock          a soft landing in the same voice, then all those notes together as the D chord

Usage: python3 sound.py [events.json] [out.wav]
"""
import json, sys
import numpy as np
from scipy import signal

SR = 48000
FPS = 60
ev = json.load(open(sys.argv[1] if len(sys.argv) > 1 else 'events.json'))
OUT = sys.argv[2] if len(sys.argv) > 2 else 'droiddeck-boot-sound.wav'
FRAMES = round(ev['DUR'] * FPS) + 1
N = int(np.ceil(FRAMES / FPS * SR))                 # exactly the video length
rng = np.random.default_rng(20260925)

# note frequencies
def hz(n): return 440.0 * 2 ** ((n - 69) / 12)
D2 = 440.0 * 2 ** ((38 - 69) / 12)
D3, A3, D4, E4, Fs4, A4, B4, Cs5, D5, E5, Fs5, A5, D6 = [hz(n) for n in (50, 57, 62, 64, 66, 69, 71, 73, 74, 76, 78, 81, 86)]

# ------------------------------------------------------------------ building blocks
def tt(dur): return np.arange(int(dur * SR)) / SR

def edge(x, a_ms=1.0, r_ms=4.0):
    """Click-free start and end."""
    na, nr = max(1, int(a_ms * SR / 1000)), max(1, int(r_ms * SR / 1000))
    x[:na] *= np.sin(np.linspace(0, np.pi / 2, na)) ** 2
    x[-nr:] *= np.cos(np.linspace(0, np.pi / 2, nr)) ** 2
    return x

def bp(x, lo, hi, order=2):
    return signal.sosfilt(signal.butter(order, [lo, min(hi, SR * .45)], 'bandpass', fs=SR, output='sos'), x)

def lp(x, f, order=2): return signal.sosfilt(signal.butter(order, f, 'lowpass', fs=SR, output='sos'), x)
def hp(x, f, order=2): return signal.sosfilt(signal.butter(order, f, 'highpass', fs=SR, output='sos'), x)

def modal(freqs, decays, amps, dur):
    t = tt(dur); y = np.zeros_like(t)
    for f, d, a in zip(freqs, decays, amps):
        if f < SR * .45: y += a * np.sin(2 * np.pi * f * t) * np.exp(-t / d)
    return y

def glide(f0, f1, tau, dur):
    """Sine whose pitch moves exponentially from f0 to f1."""
    t = tt(dur); f = f1 + (f0 - f1) * np.exp(-t / tau)
    return np.sin(2 * np.pi * np.cumsum(f) / SR)

def noise_burst(dur, lo, hi, tau):
    t = tt(dur); return bp(rng.standard_normal(len(t)), lo, hi) * np.exp(-t / tau)

def pan(x, p):
    p = np.clip(p, -1, 1); a = (p + 1) * np.pi / 4
    return np.stack([x * np.cos(a), x * np.sin(a)])

MIX = np.zeros((2, N))
def put(t0, st, gain=1.0):
    i = int(round(t0 * SR))
    if i >= N: return
    j = min(N, i + st.shape[1]); MIX[:, i:j] += gain * st[:, :j - i]

# ------------------------------------------------------------------ instruments
def wood(amp, f0=520, size=1.0, thump=0.0, dur=.35):
    """Hard wood / toy-block clack. thump adds body for heavy landings."""
    r = [1, 1.93, 2.81, 4.13, 5.62]
    y = modal([f0 * k for k in r], [s * size for s in (.055, .034, .022, .014, .009)], [1, .55, .42, .26, .16], dur)
    y += .7 * noise_burst(dur, 1400, 7500, .0035)
    if thump: y += thump * glide(118, 52, .035, dur) * np.exp(-tt(dur) / .075)
    return edge(y * amp)

def bonk(amp, f):
    """Hollow rubber ball on a board: round pitched body, soft click, tiny board knock."""
    d = .42; t = tt(d)
    y = glide(f * 1.28, f, .018, d) * np.exp(-t / (.09 + .05 * amp))
    y += .22 * glide(f * 2.45, f * 2.3, .02, d) * np.exp(-t / .035)
    y += .55 * glide(95, 60, .03, d) * np.exp(-t / .045)
    y += .22 * noise_burst(d, 1800, 5000, .002)
    y += .25 * wood(1, f0=640, size=.6, dur=d)
    return edge(y * amp, a_ms=.6)

def bowl_ring(amp, f=A4, dur=2.4, dscale=1.0):
    """Struck metal bowl: inharmonic partials, each split in two for the slow beating shimmer."""
    t = tt(dur); y = np.zeros_like(t)
    for k, dec, a, beat in zip((1, 2.71, 5.12, 8.21), tuple(d * dscale for d in (1.25, .62, .3, .15)), (1, .45, .24, .12), (.9, 1.7, 2.6, 3.1)):
        for s in (-1, 1):
            y += .5 * a * np.sin(2 * np.pi * (f * k + s * beat / 2) * t + (s > 0) * .8) * np.exp(-t / dec)
    return edge(y * amp, a_ms=.8, r_ms=30)

def clonk(amp, ring):
    d = .5; t = tt(d)
    y = .8 * glide(160, 92, .03, d) * np.exp(-t / .06) + .45 * wood(1, f0=880, size=.7, dur=d)
    return edge(y * amp), bowl_ring(ring)

def marimba(f, amp, damp=1.0):
    d = 1.2; t = tt(d)
    tau = .55 * (440 / f) ** .5 * damp
    y = modal([f, f * 3.93, f * 9.2], [tau, tau * .22, .03], [1, .32, .07], d)
    y += .05 * lp(rng.standard_normal(len(t)), 2500) * np.exp(-t / .002)
    return edge(y * amp, a_ms=1.5, r_ms=40)

def svf_whoosh(env, fc, q=.9):
    """Noise through a band-pass whose centre follows fc(t): air moving past a spinning piece."""
    x = rng.standard_normal(len(env)); y = np.zeros_like(x); ic1 = ic2 = 0.0
    for i in range(len(x)):
        g = np.tan(np.pi * min(fc[i], SR * .45) / SR); k = 1 / q
        a1 = 1 / (1 + g * (g + k)); a2 = g * a1; a3 = g * a2
        v3 = x[i] - ic2; v1 = a1 * ic1 + a2 * v3; v2 = ic2 + a2 * ic1 + a3 * v3
        ic1 = 2 * v1 - ic1; ic2 = 2 * v2 - ic2; y[i] = v1
    return y * env

def epiano(f, amp, dur=2.8, decay=1.5):
    """FM electric piano: bright tine at the strike that mellows as the index falls."""
    t = tt(dur)
    idx = 1.7 * np.exp(-t / .22) + .22
    y = np.sin(2 * np.pi * f * t + idx * np.sin(2 * np.pi * f * t))
    y += .18 * np.sin(2 * np.pi * f * t + .9 * np.exp(-t / .012) * np.sin(2 * np.pi * 14 * f * t)) * np.exp(-t / .05)
    return edge(y * np.exp(-t / decay) * amp, a_ms=3, r_ms=60)

def tock(f, amp, decay=.42, sub=0.0):
    """A landing as a muted note in the chord's own FM e-piano voice: felt contact, no pitch bend."""
    d = 1.5; t = tt(d)
    idx = 1.3 * np.exp(-t / .07) + .18
    y = np.sin(2 * np.pi * f * t + idx * np.sin(2 * np.pi * f * t)) * np.exp(-t / decay)
    y = lp(y, 2600)
    y += .30 * lp(rng.standard_normal(len(t)), 1600) * np.exp(-t / .0016)
    if sub: y += sub * np.sin(2 * np.pi * D2 * t) * np.exp(-t / .075)
    return edge(y * amp, a_ms=1.5, r_ms=80)

def bell(f, amp, dur=1.6):
    t = tt(dur)
    y = np.sin(2 * np.pi * f * t + 1.1 * np.exp(-t / .3) * np.sin(2 * np.pi * 3.5 * f * t)) * np.exp(-t / .7)
    return edge(y * amp, a_ms=2, r_ms=40)

# ------------------------------------------------------------------ tracks from the engine
tr = {k: np.array(v) for k, v in ev['tr'].items()}
TT = tr['t']; dT = TT[1] - TT[0]
def at(name, t): return np.interp(t, TT, tr[name])
def speed(xk, yk):
    return np.hypot(np.gradient(tr[xk], dT), np.gradient(tr[yk], dT))
X2PAN = 1 / 330                                     # world units to stereo pan
drops = ev['drops']; T_L = ev['T_L']; LOCK = ev['LOCK']; HOP = ev['HOP']
events_log = []
def log(t, what): events_log.append((round(t, 3), what))

# ------------------------------------------------------------------ cue sheet
# Kept deliberately sparse: one sound per story beat, with silence between them.

tsamp = np.arange(N) / SR

# 1. the stack: each piece lands on a muted note of the final chord (D, A, A-echo, F#);
#    the lock then plays them all together
ai = drops['archDrop']['impacts']
put(ai[0]['t'], pan(tock(D3, .40, decay=.5, sub=.8), .1)); log(ai[0]['t'], 'legs land: D')

bi = drops['ballDrop']['impacts']
for k, imp in enumerate(bi[:2]):
    a = .32 * (imp['v'] / bi[0]['v']) ** .9
    put(imp['t'], pan(tock(A3, a, decay=.36, sub=.3 * a / .32), 0)); log(imp['t'], f'ball: A {a:.2f}')

di = drops['domeDrop']['impacts']
put(di[0]['t'], pan(tock(Fs4, .28, decay=.45, sub=.45), -.05)); log(di[0]['t'], 'bowl lands: F#')

# 2-3. the balancing act and the jump play in silence: the stack and the lock carry it

# 4. lock: one snap, then a four-note chord that rings out
put(LOCK, pan(tock(D3, .24, decay=.28, sub=.8), 0)); log(LOCK, 'lock: soft landing in the stack voice')
for f, a, p, off in ((D3, .24, -.1, 0), (A3, .28, .2, .02), (D4, .30, -.2, .04), (Fs4, .26, .15, .06)):
    put(LOCK + .018 + off, pan(epiano(f, a, decay=1.3 if f < 300 else 1.1), p))
log(LOCK + .018, 'D chord')

# ------------------------------------------------------------------ room, master
def room_ir(dur=1.1, rt60=.75):
    t = tt(dur); tau = rt60 / 6.9; irs = []
    for ch in range(2):
        n = rng.standard_normal(len(t))
        ir = lp(n, 7000) * np.exp(-t / (tau * .6)) * .5 + lp(n, 2200) * np.exp(-t / tau)
        ir[:int(.011 * SR)] = 0                              # pre-delay
        for d_ms, g in ((13, .5), (19, .35), (27, .3), (38, .2)):
            ir[int((d_ms + ch * 1.3) * SR / 1000)] += g
        irs.append(ir / np.sqrt(np.sum(ir ** 2)))
    return irs
irs = room_ir()
wet = np.stack([signal.fftconvolve(MIX[ch], irs[ch])[:N] for ch in range(2)])
out = MIX + .14 * wet
out = hp(out, 28)
fade = np.ones(N); nf = int(.45 * SR); fade[-nf:] = np.cos(np.linspace(0, np.pi / 2, nf)) ** 2
out *= fade
# gentle peak control: soft-knee compress only the loudest transients, then normalise
pk = np.max(np.abs(out)); x = out / pk
knee = .5; ax = np.abs(x); over = ax > knee
x[over] = np.sign(x[over]) * (knee + (1 - knee) * np.tanh((ax[over] - knee) / (1 - knee)))
L0, L1 = int((LOCK + .06) * SR), int((LOCK + .76) * SR)
body_rms = np.sqrt(np.mean(x[:, L0:L1].mean(0) ** 2))
out = x * (10 ** (-14.9 / 20) / body_rms)          # chord body held at the level the team liked (-14.9 dB RMS)
assert np.max(np.abs(out)) < 10 ** (-1 / 20), 'would clip'

import wave
pcm = (np.clip(out.T, -1, 1) * 32767).astype('<i2')
with wave.open(OUT, 'wb') as w:
    w.setnchannels(2); w.setsampwidth(2); w.setframerate(SR); w.writeframes(pcm.tobytes())
json.dump(sorted(events_log), open('sound_events.json', 'w'), indent=0)
print(f'wrote {OUT}: {N / SR:.3f}s, {len(events_log)} cues')
for t, w_ in sorted(events_log): print(f'  {t:6.3f}  {w_}')
