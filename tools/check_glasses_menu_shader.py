#!/usr/bin/env python3
"""Compile and render the tracked-menu GLES shaders with headless Mesa (opt-in)."""

import argparse
import ctypes as c
import hashlib
import json
import math
import os
import pathlib
import re
import struct
import zlib


def main():
    os.environ.setdefault('EGL_PLATFORM', 'surfaceless')
    os.environ.setdefault('LIBGL_ALWAYS_SOFTWARE', '1')
    os.environ.setdefault('MESA_GLES_VERSION_OVERRIDE', '2.0')
    root = pathlib.Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output-dir', type=pathlib.Path, default=root / 'build/glasses-menu-shader')
    args = parser.parse_args()
    out = args.output_dir.resolve()
    out.mkdir(parents=True, exist_ok=True)
    source_path = root / 'app/src/main/java/com/droiddeck/launcher/glasses/GlassesMenuSurface.kt'
    source = source_path.read_text()
    shaders = {name: re.search(r'private const val '+name+r' = """(.*?)"""', source, re.S).group(1).encode()
               for name in ('VERTEX', 'FRAGMENT')}
    try:
        egl = c.CDLL('libEGL.so.1')
        gl = c.CDLL('libGLESv2.so.2')
    except OSError as error:
        raise SystemExit('This opt-in check needs Linux EGL/GLES and Mesa software rendering. '
                         'On Debian/Ubuntu install libegl1 libegl-mesa0 libgles2 libgl1-mesa-dri. '
                         f'Library load failed: {error}') from error

    def bind(lib, name, result, *args):
        fn = getattr(lib, name)
        fn.restype, fn.argtypes = result, args
        return fn

    P, I, U, F = c.c_void_p, c.c_int, c.c_uint, c.c_float
    eglGetDisplay = bind(egl, 'eglGetDisplay', P, P)
    eglInitialize = bind(egl, 'eglInitialize', U, P, c.POINTER(I), c.POINTER(I))
    eglChooseConfig = bind(egl, 'eglChooseConfig', U, P, c.POINTER(I), c.POINTER(P), I, c.POINTER(I))
    eglCreatePbufferSurface = bind(egl, 'eglCreatePbufferSurface', P, P, P, c.POINTER(I))
    eglCreateContext = bind(egl, 'eglCreateContext', P, P, P, P, c.POINTER(I))
    eglMakeCurrent = bind(egl, 'eglMakeCurrent', U, P, P, P, P)
    eglQueryString = bind(egl, 'eglQueryString', c.c_char_p, P, I)
    eglGetProcAddress = bind(egl, 'eglGetProcAddress', P, c.c_char_p)
    display = eglGetDisplay(None)
    major, minor = I(), I()
    assert eglInitialize(display, c.byref(major), c.byref(minor))
    attrs = (I * 15)(0x3033, 1, 0x3040, 4, 0x3024, 8, 0x3023, 8, 0x3022, 8, 0x3021, 8, 0x3025, 0, 0x3038)
    config, count = P(), I()
    assert eglChooseConfig(display, attrs, c.byref(config), 1, c.byref(count)) and count.value
    surface = eglCreatePbufferSurface(display, config, (I * 5)(0x3057, 512, 0x3056, 256, 0x3038))
    context = eglCreateContext(display, config, None, (I * 3)(0x3098, 2, 0x3038))
    assert surface and context and eglMakeCurrent(display, surface, surface, context)
    getString = bind(gl, 'glGetString', c.c_char_p, U)
    getError = bind(gl, 'glGetError', U)
    metadata = {'source_sha256': hashlib.sha256(source.encode()).hexdigest(),
                'shader_sha256': {key: hashlib.sha256(value).hexdigest() for key, value in shaders.items()},
                'egl': eglQueryString(display, 0x3054).decode(),
                'gles': getString(0x1F02).decode(), 'renderer': getString(0x1F01).decode(),
                'assumption': 'External texture contains top-row-first Canvas buffer pixels; consumer transform flips Y, as Android GLConsumer does. Device SurfaceTexture/Canvas integration still needs validation.'}
    assert b'GL_OES_EGL_image_external' in getString(0x1F03)
    createShader = bind(gl, 'glCreateShader', U, U)
    shaderSource = bind(gl, 'glShaderSource', None, U, I, c.POINTER(c.c_char_p), c.POINTER(I))
    compileShader = bind(gl, 'glCompileShader', None, U)
    getShaderiv = bind(gl, 'glGetShaderiv', None, U, U, c.POINTER(I))
    getShaderLog = bind(gl, 'glGetShaderInfoLog', None, U, I, c.POINTER(I), c.c_char_p)
    compiled = []
    for name, kind in [('VERTEX', 0x8B31), ('FRAGMENT', 0x8B30)]:
        shader = createShader(kind)
        text = c.c_char_p(shaders[name])
        shaderSource(shader, 1, c.byref(text), None)
        compileShader(shader)
        status, log = I(), c.create_string_buffer(8192)
        getShaderiv(shader, 0x8B81, c.byref(status))
        getShaderLog(shader, len(log), None, log)
        assert status.value, name + ': ' + log.value.decode()
        compiled.append(shader)
    createProgram = bind(gl, 'glCreateProgram', U)
    attachShader = bind(gl, 'glAttachShader', None, U, U)
    linkProgram = bind(gl, 'glLinkProgram', None, U)
    getProgramiv = bind(gl, 'glGetProgramiv', None, U, U, c.POINTER(I))
    getProgramLog = bind(gl, 'glGetProgramInfoLog', None, U, I, c.POINTER(I), c.c_char_p)
    program = createProgram()
    for shader in compiled:
        attachShader(program, shader)
    linkProgram(program)
    status, log = I(), c.create_string_buffer(8192)
    getProgramiv(program, 0x8B82, c.byref(status))
    getProgramLog(program, len(log), None, log)
    assert status.value, log.value.decode()
    useProgram = bind(gl, 'glUseProgram', None, U)
    useProgram(program)
    getUniform = bind(gl, 'glGetUniformLocation', I, U, c.c_char_p)
    uniform1i = bind(gl, 'glUniform1i', None, I, I)
    uniform3f = bind(gl, 'glUniform3f', None, I, F, F, F)
    uniform4f = bind(gl, 'glUniform4f', None, I, F, F, F, F)
    uniformMatrix4 = bind(gl, 'glUniformMatrix4fv', None, I, I, U, c.POINTER(F))
    loc = lambda name: getUniform(program, name.encode())
    uniform1i(loc('uTexture'), 0)
    flip = (F * 16)(1,0,0,0, 0,-1,0,0, 0,0,1,0, 0,1,0,1)
    uniformMatrix4(loc('uTextureMatrix'), 1, 0, flip)

    genTextures = bind(gl, 'glGenTextures', None, I, c.POINTER(U))
    bindTexture = bind(gl, 'glBindTexture', None, U, U)
    texParameteri = bind(gl, 'glTexParameteri', None, U, U, I)
    texImage2D = bind(gl, 'glTexImage2D', None, U, I, I, I, I, I, U, U, P)
    names = (U * 2)()
    genTextures(2, names)
    bindTexture(0x0DE1, names[0])
    pixels = bytes(value for y in range(256) for x in range(256) for value in (x, y, 37, 255))
    pixel_data = (c.c_ubyte * len(pixels)).from_buffer_copy(pixels)
    texImage2D(0x0DE1, 0, 0x1908, 256, 256, 0, 0x1908, 0x1401, pixel_data)
    for param, value in [(0x2801,0x2601),(0x2800,0x2601),(0x2802,0x812F),(0x2803,0x812F)]:
        texParameteri(0x0DE1, param, value)
    createImage = c.CFUNCTYPE(P, P, P, U, P, c.POINTER(I))(eglGetProcAddress(b'eglCreateImageKHR'))
    imageTarget = c.CFUNCTYPE(None, U, P)(eglGetProcAddress(b'glEGLImageTargetTexture2DOES'))
    image = createImage(display, context, 0x30B1, P(names[0]), (I * 3)(0x30BC, 0, 0x3038))
    assert image
    bindTexture(0x8D65, names[1])
    imageTarget(0x8D65, image)
    for param, value in [(0x2801,0x2601),(0x2800,0x2601),(0x2802,0x812F),(0x2803,0x812F)]:
        texParameteri(0x8D65, param, value)
    assert getError() == 0
    getAttrib = bind(gl, 'glGetAttribLocation', I, U, c.c_char_p)
    enableAttrib = bind(gl, 'glEnableVertexAttribArray', None, U)
    attribPointer = bind(gl, 'glVertexAttribPointer', None, U, I, U, U, I, P)
    vertices = (F * 8)(-1,-1, 1,-1, -1,1, 1,1)
    position = getAttrib(program, b'aPosition')
    enableAttrib(position)
    attribPointer(position, 2, 0x1406, 0, 0, vertices)
    viewport = bind(gl, 'glViewport', None, I, I, I, I)
    draw = bind(gl, 'glDrawArrays', None, U, I, I)
    read = bind(gl, 'glReadPixels', None, I, I, I, I, U, U, P)
    finish = bind(gl, 'glFinish', None)
    cases = []

    def render(name, mono=True, position=(0,0,0), yaw=0, width=512, height=256, coords=((64,32),(448,32),(64,224),(448,224)), backdrop=False):
        viewport(0, 0, width, height)
        cs, sn = math.cos(yaw), math.sin(yaw)
        for row, values in enumerate(((cs,0,sn),(0,1,0),(-sn,0,cs))):
            uniform3f(loc('uRotation'+str(row)), *values)
        uniform3f(loc('uHead'), *position)
        uniform3f(loc('uScreen'), 1, 1, 2)
        uniform4f(loc('uOptics'), .5, width/height/(1 if mono else 2), 0 if mono else .032, 1 if mono else 0)
        draw(0x0005, 0, 4)
        finish()
        assert getError() == 0
        actual = (c.c_ubyte * (width*height*4))()
        read(0,0,width,height,0x1908,0x1401,actual)
        samples = []
        for x, y in coords:
            # Readback is GL bottom-up; coordinates below are image top-left.
            offset = ((height-1-y)*width+x)*4
            rgb = list(actual[offset:offset+3])
            eye = 0 if mono or x < width/2 else 1
            ux = (x+.5)/width if mono else (x+.5)/(width/2)-eye
            vy = (y+.5)/height
            rayx = (ux-.5)*(width/height/(1 if mono else 2))
            rayy = .5-vy
            dx, dy, dz = cs*rayx-sn, rayy, -sn*rayx-cs
            eo = 0 if mono else (-.032 if eye == 0 else .032)
            ox, oy, oz = position[0]+cs*eo, position[1], position[2]-sn*eo
            t = (-1-oz)/dz if dz else -1
            uv = ((ox+dx*t)*.5+.5, -(oy+dy*t)+.5)
            valid = dz < -.0001 and t > 0 and all(0 <= v <= 1 for v in uv) and not backdrop
            expected = [round(max(0,min(255,v*256-.5))) for v in uv]+[37] if valid else [4,4,5]
            assert all(abs(a-b) <= 2 for a,b in zip(rgb,expected)), (name,x,y,rgb,expected,uv)
            samples.append({'at':[x,y],'rgb':rgb,'expected':expected})
        cases.append({'name':name,'samples':samples})
        if name == 'mono_upright':
            rows = bytearray()
            for y in range(height-1,-1,-1):
                for x in range(width):
                    start=(y*width+x)*4
                    rows.extend(actual[start:start+3])
            (out/'menu-shader-mono.ppm').write_bytes(f'P6\n{width} {height}\n255\n'.encode()+rows)
            def chunk(name, data):
                return struct.pack('>I',len(data))+name+data+struct.pack('>I',zlib.crc32(name+data)&0xffffffff)
            scanlines=b''.join(b'\x00'+rows[y*width*3:(y+1)*width*3] for y in range(height))
            (out/'menu-shader-mono.png').write_bytes(b'\x89PNG\r\n\x1a\n'+
                chunk(b'IHDR',struct.pack('>IIBBBBB',width,height,8,2,0,0,0))+
                chunk(b'IDAT',zlib.compress(scanlines))+chunk(b'IEND',b''))

    render('mono_upright')
    render('sbs_both_eyes', mono=False, width=512, height=128,
           coords=((32,16),(224,16),(32,112),(224,112),(288,16),(480,16),(288,112),(480,112),(128,64),(384,64)))
    render('six_dof_translation', position=(.2,.1,.1), coords=((256,128),(200,96),(300,160)))
    render('row_major_yaw', yaw=math.radians(10), coords=((256,128),(200,96),(300,160)))
    render('behind_plane', position=(0,0,-2), coords=((256,128),), backdrop=True)
    render('back_facing', yaw=math.pi, coords=((256,128),), backdrop=True)
    metadata['cases'] = cases
    metadata['result'] = 'PASS'
    (out/'menu-shader-validation.json').write_text(json.dumps(metadata, indent=2)+'\n')
    print(json.dumps(metadata, indent=2), flush=True)


if __name__ == '__main__':
    main()
