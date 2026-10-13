#version 450
// Cardboard pass (cardboard.c): the finished frame -> two eye viewports, side by side, each
// barrel-distorted for a phone-in-a-viewer lens. Runs on upscale.vert's full quad, so uv covers
// the whole panel with (0,0) at the top left.
//
// Source modes:
//   0 = virtual screen: the frame is a flat screen hanging in front of the viewer. Each eye casts
//       a ray through its lens, rotated by the head pose, from its own position (half the IPD to
//       either side), so the screen has real stereo depth and stays put while the head turns.
//   1 = side by side: the frame already holds a left and a right eye image (SteamVR, an SBS 3D
//       game or video); each eye samples its own half. The head pose belongs to whoever rendered
//       it, so none is applied here.
//
// Regenerate cardboard_frag.h after editing (glslangValidator, see cardboard.c).
layout(binding = 0) uniform sampler2D Source;
layout(location = 0) in vec2 uv;
layout(location = 0) out vec4 outColor;

layout(push_constant) uniform PC {
    vec4 ndc;   // upscale.vert's quad
    vec4 rot0;  // xyz: head -> world rotation, row 0; w: half the IPD (m)
    vec4 rot1;  // xyz: row 1; w: distance to the virtual screen (m)
    vec4 rot2;  // xyz: row 2; w: half the virtual screen's width (m)
    vec4 lens;  // x: k1, y: k2, z: tan of half the eye's vertical field of view, w: chromatic spread
    vec4 view;  // x: lens centre inset (fraction of an eye viewport), y: source aspect w/h,
                // z: source mode, w: eye viewport aspect w/h
} pc;

const vec3 BACKDROP = vec3(0.015, 0.015, 0.02);

// Lens: a point on the panel at tangent-space offset t (from the lens centre) shows the ray that
// leaves the eye at t * (1 + k1 r^2 + k2 r^4). Sampling outwards like this draws the picture
// barrel-shaped; the viewer's lens pincushions it back.
vec2 undistort(vec2 t, float scale) {
    float r2 = dot(t, t);
    return t * scale * (1.0 + pc.lens.x * r2 + pc.lens.y * r2 * r2);
}

// What one eye sees along tangent direction tan (x right, y down): colour, or the backdrop.
vec3 eyeSample(vec2 tan, float eye) {
    if (pc.view.z > 0.5) {
        // Side by side: this eye's half of the source covers the eye's field of view.
        float tanH = pc.lens.z;
        vec2 s = vec2(tan.x / (tanH * pc.view.w), tan.y / tanH) * 0.5 + 0.5;
        if (any(lessThan(s, vec2(0.0))) || any(greaterThan(s, vec2(1.0)))) return vec3(0.0);
        return texture(Source, vec2((s.x + eye) * 0.5, s.y)).rgb;
    }
    // mat3() takes columns; the push constants carry rows.
    mat3 R = transpose(mat3(pc.rot0.xyz, pc.rot1.xyz, pc.rot2.xyz));
    vec3 d = R * normalize(vec3(tan.x, -tan.y, -1.0));
    vec3 o = R * vec3((eye < 0.5 ? -1.0 : 1.0) * pc.rot0.w, 0.0, 0.0);
    if (d.z > -1e-4) return BACKDROP; // looking away from the screen
    float t = (-pc.rot1.w - o.z) / d.z;
    vec3 hit = o + d * t;
    float halfW = pc.rot2.w, halfH = halfW / pc.view.y;
    vec2 s = vec2(hit.x / halfW, -hit.y / halfH) * 0.5 + 0.5;
    if (any(lessThan(s, vec2(0.0))) || any(greaterThan(s, vec2(1.0)))) return BACKDROP;
    return texture(Source, s).rgb;
}

void main() {
    float eye = uv.x < 0.5 ? 0.0 : 1.0;
    vec2 local = vec2(uv.x * 2.0 - eye, uv.y);
    // Lens centres sit inset towards the nose by view.x of a viewport.
    float cx = 0.5 + (eye < 0.5 ? pc.view.x : -pc.view.x);
    vec2 p = local - vec2(cx, 0.5);
    float tanH = pc.lens.z;
    vec2 t = vec2(p.x * pc.view.w, p.y) * 2.0 * tanH;

    vec3 c;
    if (pc.lens.w > 0.0) {
        // Chromatic aberration: the lens bends red least and blue most; pre-spread to match.
        c.r = eyeSample(undistort(t, 1.0 - pc.lens.w), eye).r;
        c.g = eyeSample(undistort(t, 1.0), eye).g;
        c.b = eyeSample(undistort(t, 1.0 + pc.lens.w), eye).b;
    } else {
        c = eyeSample(undistort(t, 1.0), eye);
    }
    // Fade out towards each viewport's edge so the lens rim never shows a hard cut.
    vec2 e = min(local, 1.0 - local);
    float fade = smoothstep(0.0, 0.03, min(e.x * pc.view.w, e.y));
    outColor = vec4(c * fade, 1.0);
}
