// Picture effects for the stream: the video is drawn through WebGL2 shaders onto a canvas over it.
// Base pass: sharpening (CAS / unsharp mask, from Better xCloud by redphx, MIT) plus DroidDeck's colour
// grading (brightness, contrast, saturation, gamma) and deband dither. Extra passes are DroidDeck's own
// screen effects (FXAA, Toon, CRT, NTSC, from Bannerlator). Off (no canvas) when nothing is set.

export interface Effects {
    brightness: number   // -50..50 (DroidDeck's slider units)
    contrast: number     // -100..100
    gamma: number        // 0.5..3
    saturation: number   // 0..200 %
    sharpen: number      // 0..10
    sharpenMode: string  // 'cas' | 'usm'
    deband: number       // 0..8 (dither amplitude in 8-bit steps)
    fxaa: boolean
    toon: boolean
    crt: boolean
    ntsc: boolean
    maxFps: number       // 0 = every frame
}

export function needsRenderer(e: Effects): boolean {
    return e.brightness !== 0 || e.contrast !== 0 || Math.abs(e.gamma - 1) > 0.001 || e.saturation !== 100 ||
        e.sharpen > 0 || e.deband > 0 || e.fxaa || e.toon || e.crt || e.ntsc || e.maxFps > 0
}

const VERTEX_300 = `#version 300 es
in vec2 position;
out vec2 vUV;
void main() { vUV = position * 0.5 + 0.5; gl_Position = vec4(position, 0.0, 1.0); }`

const VERTEX_100 = `attribute vec2 position;
varying vec2 vUV;
void main() { vUV = position * 0.5 + 0.5; gl_Position = vec4(position, 0.0, 1.0); }`

const BASE = `#version 300 es
precision mediump float;
uniform sampler2D screenTexture;
uniform vec2 resolution;
uniform int filterId;
uniform float sharpenFactor;
uniform float brightness;
uniform float contrast;
uniform float gamma;
uniform float saturation;
uniform float deband;
out vec4 fragColor;
const float CAS_CONTRAST_PEAK = 0.8 * -3.0 + 8.0;

vec3 sharpen(vec2 coord, vec3 e) {
    vec2 px = 1.0 / resolution;
    vec3 b = texture(screenTexture, coord + px * vec2(0, 1)).rgb;
    vec3 d = texture(screenTexture, coord + px * vec2(-1, 0)).rgb;
    vec3 f = texture(screenTexture, coord + px * vec2(1, 0)).rgb;
    vec3 h = texture(screenTexture, coord + px * vec2(0, -1)).rgb;
    vec3 a = texture(screenTexture, coord + px * vec2(-1, 1)).rgb;
    vec3 c = texture(screenTexture, coord + px * vec2(1, 1)).rgb;
    vec3 g = texture(screenTexture, coord + px * vec2(-1, -1)).rgb;
    vec3 i = texture(screenTexture, coord + px * vec2(1, -1)).rgb;
    if (filterId == 1) {
        vec3 blur = ((a + c + g + i) + (b + d + f + h) * 2.0 + e * 4.0) / 16.0;
        return e + (e - blur) * sharpenFactor / 3.0;
    }
    vec3 minRgb = min(min(min(d, e), min(f, b)), h) + min(min(a, c), min(g, i));
    vec3 maxRgb = max(max(max(d, e), max(f, b)), h) + max(max(a, c), max(g, i));
    vec3 amplify = inversesqrt(clamp(min(minRgb, 2.0 - maxRgb) / maxRgb, 0.0, 1.0));
    vec3 weight = -(1.0 / (amplify * CAS_CONTRAST_PEAK));
    vec3 outColor = clamp(((b + d + f + h) * weight + e) / (4.0 * weight + 1.0), 0.0, 1.0);
    return mix(e, outColor, sharpenFactor / 2.0);
}

float ign(vec2 p) { return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715)))); }

void main() {
    vec2 uv = gl_FragCoord.xy / resolution;
    vec3 color = texture(screenTexture, uv).rgb;
    if (sharpenFactor > 0.0) color = sharpen(uv, color);
    color = clamp(color + brightness, 0.0, 1.0);
    color = (color - 0.5) * clamp(contrast + 1.0, 0.5, 2.0) + 0.5;
    color = mix(vec3(dot(color, vec3(0.2126, 0.7152, 0.0722))), color, saturation);
    color = pow(max(color, 0.0), vec3(1.0 / gamma));
    if (deband > 0.0) color += vec3(((ign(gl_FragCoord.xy) + ign(gl_FragCoord.xy + vec2(11.0, 17.0))) - 1.0) * (deband / 255.0));
    fragColor = vec4(color, 1.0);
}`

// Screen-effect shaders from Bannerlator (GLSL ES 1.00), unchanged.
const FXAA = `precision highp float;
#define FXAA_MIN_REDUCE (1.0 / 128.0)
#define FXAA_MUL_REDUCE (1.0 / 8.0)
#define MAX_SPAN 8.0
uniform sampler2D screenTexture;
uniform vec2 resolution;
const vec3 luma = vec3(0.299, 0.587, 0.114);
void main() {
    vec2 invResolution = 1.0 / resolution;
    vec3 rgbNW = texture2D(screenTexture, (gl_FragCoord.xy + vec2(-1.0, -1.0)) * invResolution).rgb;
    vec3 rgbNE = texture2D(screenTexture, (gl_FragCoord.xy + vec2( 1.0, -1.0)) * invResolution).rgb;
    vec3 rgbSW = texture2D(screenTexture, (gl_FragCoord.xy + vec2(-1.0,  1.0)) * invResolution).rgb;
    vec3 rgbSE = texture2D(screenTexture, (gl_FragCoord.xy + vec2( 1.0,  1.0)) * invResolution).rgb;
    vec3 rgbM  = texture2D(screenTexture,  gl_FragCoord.xy * invResolution).rgb;
    float lumaNW = dot(rgbNW, luma);
    float lumaNE = dot(rgbNE, luma);
    float lumaSW = dot(rgbSW, luma);
    float lumaSE = dot(rgbSE, luma);
    float lumaM  = dot(rgbM,  luma);
    float lumaMin = min(lumaM, min(min(lumaNW, lumaNE), min(lumaSW, lumaSE)));
    float lumaMax = max(lumaM, max(max(lumaNW, lumaNE), max(lumaSW, lumaSE)));
    vec2 dir;
    dir.x = -((lumaNW + lumaNE) - (lumaSW + lumaSE));
    dir.y =  ((lumaNW + lumaSW) - (lumaNE + lumaSE));
    float dirReduce = max((lumaNW + lumaNE + lumaSW + lumaSE) * 0.25 * FXAA_MUL_REDUCE, FXAA_MIN_REDUCE);
    float minDirFactor = 1.0 / (min(abs(dir.x), abs(dir.y)) + dirReduce);
    dir = clamp(dir * minDirFactor, vec2(-MAX_SPAN), vec2(MAX_SPAN)) * invResolution;
    vec4 rgbA = 0.5 * (
        texture2D(screenTexture, gl_FragCoord.xy * invResolution + dir * (1.0 / 3.0 - 0.5)) +
        texture2D(screenTexture, gl_FragCoord.xy * invResolution + dir * (2.0 / 3.0 - 0.5)));
    vec4 rgbB = rgbA * 0.5 + 0.25 * (
        texture2D(screenTexture, gl_FragCoord.xy * invResolution + dir * -0.5) +
        texture2D(screenTexture, gl_FragCoord.xy * invResolution + dir *  0.5));
    float lumaB = dot(rgbB, vec4(luma, 0.0));
    gl_FragColor = lumaB < lumaMin || lumaB > lumaMax ? rgbA : rgbB;
}`

const TOON = `precision highp float;
uniform sampler2D screenTexture;
uniform vec2 resolution;
void main() {
    vec2 uv = gl_FragCoord.xy / resolution;
    float edgeThreshold = 0.2;
    vec2 offset = vec2(1.0) / resolution;
    vec3 colorCenter = texture2D(screenTexture, uv).rgb;
    vec3 colorLeft = texture2D(screenTexture, uv - vec2(offset.x, 0.0)).rgb;
    vec3 colorRight = texture2D(screenTexture, uv + vec2(offset.x, 0.0)).rgb;
    vec3 colorUp = texture2D(screenTexture, uv - vec2(0.0, offset.y)).rgb;
    vec3 colorDown = texture2D(screenTexture, uv + vec2(0.0, offset.y)).rgb;
    float diffHorizontal = length(colorRight - colorLeft);
    float diffVertical = length(colorUp - colorDown);
    float edgeFactor = step(edgeThreshold, diffHorizontal + diffVertical);
    vec3 outlineColor = mix(colorCenter, vec3(0.0), edgeFactor);
    gl_FragColor = vec4(outlineColor, 1.0);
}`

const CRT = `precision highp float;
#define CA_AMOUNT 1.0025
#define SCANLINE_INTENSITY_X 0.125
#define SCANLINE_INTENSITY_Y 0.375
#define SCANLINE_SIZE 1024.0
uniform sampler2D screenTexture;
varying vec2 vUV;
void main() {
    vec4 finalColor = texture2D(screenTexture, vUV);
    finalColor.rgb = vec3(
        texture2D(screenTexture, (vUV - 0.5) * CA_AMOUNT + 0.5).r,
        finalColor.g,
        texture2D(screenTexture, (vUV - 0.5) / CA_AMOUNT + 0.5).b
    );
    float scanlineX = abs(sin(vUV.x * SCANLINE_SIZE) * 0.5 * SCANLINE_INTENSITY_X);
    float scanlineY = abs(sin(vUV.y * SCANLINE_SIZE) * 0.5 * SCANLINE_INTENSITY_Y);
    gl_FragColor = vec4(mix(finalColor.rgb, vec3(0.0), scanlineX + scanlineY), finalColor.a);
}`

const NTSC = `precision highp float;
#define PI 3.14159265
#define SCANLINE_INTENSITY 0.35
#define CHROMA_OFFSET 0.005
#define BLUR_RADIUS 0.002
#define WARP_AMOUNT 0.01
#define SCANLINE_DARKEN 0.5
uniform sampler2D screenTexture;
uniform int FrameCount;
uniform vec2 TextureSize;
uniform vec2 resolution;
varying vec2 vUV;
const mat3 yiq_mat = mat3(
   0.299, 0.587, 0.114,
   0.596, -0.275, -0.321,
   0.212, -0.523, 0.311
);
const mat3 yiq2rgb_mat = mat3(
   1.0, 0.956, 0.621,
   1.0, -0.272, -0.647,
   1.0, -1.106, 1.705
);
vec3 applyNTSC(vec2 uv) {
   vec3 col = texture2D(screenTexture, uv).rgb;
   vec3 yiq = col * yiq_mat;
   float chromaPhase = PI * (mod(uv.y * TextureSize.y, 2.0) + float(FrameCount));
   yiq.y *= cos(chromaPhase * 0.5);
   yiq.z *= sin(chromaPhase * 0.5);
   vec3 rgb = yiq * yiq2rgb_mat;
   vec3 finalColor;
   finalColor.r = texture2D(screenTexture, uv + vec2(CHROMA_OFFSET, 0.0)).r;
   finalColor.g = texture2D(screenTexture, uv + vec2(0.0, BLUR_RADIUS)).g;
   finalColor.b = texture2D(screenTexture, uv - vec2(CHROMA_OFFSET, 0.0)).b;
   return finalColor;
}
vec3 applyScanlines(vec2 uv) {
   vec3 col = texture2D(screenTexture, uv).rgb;
   float scanline = abs(sin(uv.y * resolution.y * 2.0)) * SCANLINE_INTENSITY;
   col *= 1.0 - (scanline * SCANLINE_DARKEN);
   return col;
}
vec2 applyWarp(vec2 uv) {
   uv = uv * 2.0 - 1.0;
   float r = sqrt(uv.x * uv.x + uv.y * uv.y);
   uv += uv * (r * r) * WARP_AMOUNT;
   return uv * 0.5 + 0.5;
}
void main() {
   vec2 warpedUV = applyWarp(vUV);
   vec3 ntscColor = applyNTSC(warpedUV);
   vec3 scanlineColor = applyScanlines(warpedUV);
   vec3 finalColor = mix(ntscColor, scanlineColor, 0.7);
   gl_FragColor = vec4(finalColor, 1.0);
}`

interface Pass { program: WebGLProgram, uniforms: Map<string, WebGLUniformLocation | null> }

export class EffectRenderer {
    private canvas: HTMLCanvasElement
    private gl: WebGL2RenderingContext
    private passes = new Map<string, Pass>()
    private videoTexture: WebGLTexture
    private targets: { framebuffer: WebGLFramebuffer, texture: WebGLTexture }[] = []
    private width = 0
    private height = 0
    private frame = 0
    private lastDraw = 0
    private stopped = false
    private idle = false
    private pending = false

    constructor(private video: HTMLVideoElement, private effects: Effects) {
        this.canvas = document.createElement('canvas')
        Object.assign(this.canvas.style, { position: 'absolute', inset: '0', width: '100%', height: '100%', objectFit: 'contain', zIndex: '1' })
        video.parentElement?.appendChild(this.canvas)
        const gl = this.canvas.getContext('webgl2', { alpha: false, antialias: false, depth: false, desynchronized: true, powerPreference: 'high-performance' })
        if (gl === null) throw new Error('WebGL2 unavailable')
        this.gl = gl
        const buffer = gl.createBuffer()
        gl.bindBuffer(gl.ARRAY_BUFFER, buffer)
        gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 3, -1, -1, 3]), gl.STATIC_DRAW)
        this.passes.set('base', this.compile(VERTEX_300, BASE, ['screenTexture', 'resolution', 'filterId', 'sharpenFactor', 'brightness', 'contrast', 'gamma', 'saturation', 'deband']))
        this.videoTexture = this.texture()
        this.schedule()
    }

    update(effects: Effects) { this.effects = effects }

    // Turning effects off hides the canvas but keeps the GL context: losing and recreating it can stall the stream.
    pause() { if (this.idle || this.stopped) return; this.idle = true; this.canvas.style.display = 'none' }

    resume(effects: Effects) {
        this.effects = effects
        if (!this.idle || this.stopped) return
        this.idle = false
        this.canvas.style.display = ''
        this.schedule()
    }

    private schedule() {
        if (this.pending) return
        this.pending = true
        this.video.requestVideoFrameCallback(this.onFrame)
    }

    private compile(vertex: string, fragment: string, names: string[]): Pass {
        const gl = this.gl
        const shader = (type: number, source: string) => {
            const s = gl.createShader(type)!
            gl.shaderSource(s, source)
            gl.compileShader(s)
            if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error(`shader: ${gl.getShaderInfoLog(s)}`)
            return s
        }
        const program = gl.createProgram()!
        gl.attachShader(program, shader(gl.VERTEX_SHADER, vertex))
        gl.attachShader(program, shader(gl.FRAGMENT_SHADER, fragment))
        gl.bindAttribLocation(program, 0, 'position')
        gl.linkProgram(program)
        if (!gl.getProgramParameter(program, gl.LINK_STATUS)) throw new Error(`program: ${gl.getProgramInfoLog(program)}`)
        return { program, uniforms: new Map(names.map(name => [name, gl.getUniformLocation(program, name)])) }
    }

    private extra(name: string): Pass {
        let pass = this.passes.get(name)
        if (pass === undefined) {
            const source = { fxaa: FXAA, toon: TOON, crt: CRT, ntsc: NTSC }[name]!
            pass = this.compile(VERTEX_100, source, ['screenTexture', 'resolution', 'TextureSize', 'FrameCount'])
            this.passes.set(name, pass)
        }
        return pass
    }

    private texture(): WebGLTexture {
        const gl = this.gl
        const texture = gl.createTexture()!
        gl.bindTexture(gl.TEXTURE_2D, texture)
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR)
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR)
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE)
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE)
        return texture
    }

    private resize(width: number, height: number) {
        const gl = this.gl
        this.width = width; this.height = height
        this.canvas.width = width; this.canvas.height = height
        for (const target of this.targets) { gl.deleteFramebuffer(target.framebuffer); gl.deleteTexture(target.texture) }
        this.targets = [0, 1].map(() => {
            const texture = this.texture()
            gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, width, height, 0, gl.RGBA, gl.UNSIGNED_BYTE, null)
            const framebuffer = gl.createFramebuffer()!
            gl.bindFramebuffer(gl.FRAMEBUFFER, framebuffer)
            gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, texture, 0)
            return { framebuffer, texture }
        })
        gl.bindFramebuffer(gl.FRAMEBUFFER, null)
    }

    private onFrame = (now: number) => {
        this.pending = false
        if (this.stopped || this.idle) return
        this.schedule()
        const max = this.effects.maxFps
        if (max > 0 && now - this.lastDraw < 1000 / max - 2) return
        this.lastDraw = now
        try { this.draw() } catch (error) { (window as any).DDCloud?.log(`effects: ${String(error).slice(0, 200)}`); this.destroy() }
    }

    private draw() {
        const gl = this.gl
        const e = this.effects
        const width = this.video.videoWidth, height = this.video.videoHeight
        if (width === 0 || height === 0) return
        if (width !== this.width || height !== this.height) this.resize(width, height)
        this.frame++

        gl.activeTexture(gl.TEXTURE0)
        gl.bindTexture(gl.TEXTURE_2D, this.videoTexture)
        gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, true)
        gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, this.video)

        const extras = ['fxaa', 'toon', 'crt', 'ntsc'].filter(name => (e as any)[name])
        gl.viewport(0, 0, width, height)
        gl.enableVertexAttribArray(0)
        gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0)

        const base = this.passes.get('base')!
        gl.bindFramebuffer(gl.FRAMEBUFFER, extras.length > 0 ? this.targets[0].framebuffer : null)
        gl.useProgram(base.program)
        const u = base.uniforms
        gl.uniform1i(u.get('screenTexture')!, 0)
        gl.uniform2f(u.get('resolution')!, width, height)
        gl.uniform1i(u.get('filterId')!, e.sharpenMode === 'usm' ? 1 : 2)
        gl.uniform1f(u.get('sharpenFactor')!, e.sharpen)
        gl.uniform1f(u.get('brightness')!, e.brightness / 100)
        gl.uniform1f(u.get('contrast')!, e.contrast / 100)
        gl.uniform1f(u.get('gamma')!, Math.min(5, Math.max(0.1, e.gamma)))
        gl.uniform1f(u.get('saturation')!, e.saturation / 100)
        gl.uniform1f(u.get('deband')!, e.deband)
        gl.drawArrays(gl.TRIANGLES, 0, 3)

        extras.forEach((name, index) => {
            const pass = this.extra(name)
            const source = this.targets[index % 2]
            const last = index === extras.length - 1
            gl.bindFramebuffer(gl.FRAMEBUFFER, last ? null : this.targets[(index + 1) % 2].framebuffer)
            gl.useProgram(pass.program)
            gl.bindTexture(gl.TEXTURE_2D, source.texture)
            const pu = pass.uniforms
            gl.uniform1i(pu.get('screenTexture') ?? null, 0)
            gl.uniform2f(pu.get('resolution') ?? null, width, height)
            gl.uniform2f(pu.get('TextureSize') ?? null, width, height)
            gl.uniform1i(pu.get('FrameCount') ?? null, this.frame)
            gl.drawArrays(gl.TRIANGLES, 0, 3)
        })
    }

    destroy() {
        this.stopped = true
        this.canvas.remove()
        this.gl.getExtension('WEBGL_lose_context')?.loseContext()
    }
}
