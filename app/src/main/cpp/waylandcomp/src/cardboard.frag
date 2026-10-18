#version 450
layout(binding = 0) uniform sampler2D Source;
layout(location = 0) in vec2 uv;
layout(location = 0) out vec4 outColor;

layout(push_constant) uniform PC {
    vec4 ndc;
    vec4 rot0;
    vec4 rot1;
    vec4 rot2;
    vec4 lens;
    vec4 view;
    vec4 extra;
} pc;

const vec3 BACKDROP = vec3(0.015, 0.015, 0.02);

vec2 undistort(vec2 t, float scale) {
    float r2 = dot(t, t);
    return t * scale * (1.0 + pc.lens.x * r2 + pc.lens.y * r2 * r2);
}

vec3 eyeSample(vec2 tan, float eye) {
    if (pc.view.z > 0.5) {
        float tanH = pc.lens.z;
        vec2 s = vec2(tan.x / (tanH * pc.view.w), tan.y / tanH) * 0.5 + 0.5;
        if (pc.view.z > 1.5) {
            vec2 fit = max(vec2(pc.view.w / pc.view.y, pc.view.y / pc.view.w), vec2(1.0));
            s = (s - 0.5) * fit + 0.5;
            if (any(lessThan(s, vec2(0.0))) || any(greaterThan(s, vec2(1.0)))) return vec3(0.0);
            return texture(Source, s).rgb;
        }
        if (any(lessThan(s, vec2(0.0))) || any(greaterThan(s, vec2(1.0)))) return vec3(0.0);
        return texture(Source, vec2((s.x + eye) * 0.5, s.y)).rgb;
    }
    mat3 R = transpose(mat3(pc.rot0.xyz, pc.rot1.xyz, pc.rot2.xyz));
    vec3 d = R * normalize(vec3(tan.x, -tan.y, -1.0));
    vec3 o = pc.extra.yzw + R * vec3((eye < 0.5 ? -1.0 : 1.0) * pc.rot0.w, 0.0, 0.0);
    if (d.z > -1e-4) return BACKDROP;
    float t = (-pc.rot1.w - o.z) / d.z;
    if (t <= 0.0) return BACKDROP;
    vec3 hit = o + d * t;
    float halfW = pc.rot2.w, halfH = halfW / pc.view.y;
    vec2 s = vec2(hit.x / halfW, -hit.y / halfH) * 0.5 + 0.5;
    if (any(lessThan(s, vec2(0.0))) || any(greaterThan(s, vec2(1.0)))) return BACKDROP;
    return texture(Source, s).rgb;
}

void main() {
    bool mono = pc.extra.x > 0.5;
    float eye = mono || uv.x < 0.5 ? 0.0 : 1.0;
    vec2 local = mono ? uv : vec2(uv.x * 2.0 - eye, uv.y);
    float cx = mono ? 0.5 : 0.5 + (eye < 0.5 ? pc.view.x : -pc.view.x);
    vec2 p = local - vec2(cx, 0.5);
    float tanH = pc.lens.z;
    vec2 t = vec2(p.x * pc.view.w, p.y) * 2.0 * tanH;

    vec3 c;
    if (pc.lens.w > 0.0) {
        c.r = eyeSample(undistort(t, 1.0 - pc.lens.w), eye).r;
        c.g = eyeSample(undistort(t, 1.0), eye).g;
        c.b = eyeSample(undistort(t, 1.0 + pc.lens.w), eye).b;
    } else {
        c = eyeSample(undistort(t, 1.0), eye);
    }
    vec2 e = min(local, 1.0 - local);
    float fade = smoothstep(0.0, 0.03, min(e.x * pc.view.w, e.y));
    outColor = vec4(c * fade, 1.0);
}
