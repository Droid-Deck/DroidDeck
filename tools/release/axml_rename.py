#!/usr/bin/env python3
import struct, sys, zipfile

def dec_len8(b, o):
    n = b[o]; o += 1
    if n & 0x80: n = ((n & 0x7f) << 8) | b[o]; o += 1
    return n, o

def dec_len16(b, o):
    n = struct.unpack_from('<H', b, o)[0]; o += 2
    if n & 0x8000: n = ((n & 0x7fff) << 16) | struct.unpack_from('<H', b, o)[0]; o += 2
    return n, o

def enc_len8(n):
    return bytes([n]) if n < 0x80 else bytes([0x80 | (n >> 8), n & 0xff])

def enc_len16(n):
    return struct.pack('<H', n) if n < 0x8000 else struct.pack('<HH', 0x8000 | (n >> 16), n & 0xffff)

def rewrite(axml, repl):
    ftype, fhs = struct.unpack_from('<HH', axml, 0)
    assert ftype == 0x0003, 'not binary XML'
    sp = fhs
    ctype, chs, csize, count, scount, flags, sstart, stystart = struct.unpack_from('<HHIIIIII', axml, sp)
    assert ctype == 0x0001
    utf8 = bool(flags & 0x100)
    offs = struct.unpack_from('<%dI' % count, axml, sp + chs)
    sty_offs = axml[sp + chs + 4 * count: sp + chs + 4 * count + 4 * scount]
    base = sp + sstart
    strings = []
    for off in offs:
        o = base + off
        if utf8:
            _, o = dec_len8(axml, o); n, o = dec_len8(axml, o)
            strings.append(axml[o:o + n].decode('utf-8'))
        else:
            n, o = dec_len16(axml, o)
            strings.append(axml[o:o + 2 * n].decode('utf-16-le'))
    hits = 0
    for i, s in enumerate(strings):
        if s in repl: strings[i] = repl[s]; hits += 1
    data = bytearray(); new_offs = []
    for s in strings:
        new_offs.append(len(data))
        if utf8:
            e = s.encode('utf-8'); data += enc_len8(len(s.encode('utf-16-le')) // 2) + enc_len8(len(e)) + e + b'\0'
        else:
            e = s.encode('utf-16-le'); data += enc_len16(len(e) // 2) + e + b'\0\0'
    while len(data) % 4: data += b'\0'
    styles = axml[sp + stystart: sp + csize] if scount else b''
    new_sstart = chs + 4 * count + 4 * scount
    new_stystart = new_sstart + len(data) if scount else 0
    body = struct.pack('<%dI' % count, *new_offs) + sty_offs + bytes(data) + styles
    new_csize = chs + len(body)
    header = struct.pack('<HHIIIIII', ctype, chs, new_csize, count, scount, flags, new_sstart, new_stystart)
    header += axml[sp + 28: sp + chs]
    rest = axml[sp + csize:]
    out = axml[:sp] + header + body + rest
    out = out[:4] + struct.pack('<I', len(out)) + out[8:]
    return out, hits

src, dst = sys.argv[1], sys.argv[2]
repl = dict(a.split('=', 1) for a in sys.argv[3:])
with zipfile.ZipFile(src) as zi, zipfile.ZipFile(dst, 'w') as zo:
    for info in zi.infolist():
        if info.filename.startswith('META-INF/') and info.filename.split('/')[-1].split('.')[-1] in ('SF', 'RSA', 'DSA', 'EC', 'MF'):
            continue
        b = zi.read(info)
        if info.filename == 'AndroidManifest.xml':
            b, hits = rewrite(b, repl)
            print(f'manifest: {hits} strings replaced')
        zo.writestr(info, b, compress_type=info.compress_type)
