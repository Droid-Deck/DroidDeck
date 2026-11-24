#version 450


layout(binding = 0) uniform sampler2D Mixed;

layout(push_constant) uniform PC {
    vec4 ndc;
    vec4 params;
    uvec2 rects[6];
    vec4 conv[3];
} pc;

layout(location = 0) in vec2 fragTexCoord;
layout(location = 0) out vec4 outColor;

const float PQ_M1 = 0.1593017578125;
const float PQ_M2 = 78.84375;
const float PQ_C1 = 0.8359375;
const float PQ_C2 = 18.8515625;
const float PQ_C3 = 18.6875;

const mat3 BT709_TO_BT2020 = mat3(0.6274, 0.0691, 0.0164,
                                  0.3293, 0.9195, 0.0880,
                                  0.0433, 0.0114, 0.8956);
const mat3 BT2020_TO_BT709 = mat3( 1.6605, -0.1246, -0.0182,
                                  -0.5876,  1.1329, -0.1006,
                                  -0.0728, -0.0083,  1.1187);

vec3 srgbToLinear(vec3 c) {
    vec3 lo = c / 12.92;
    vec3 hi = pow((c + 0.055) / 1.055, vec3(2.4));
    return mix(lo, hi, step(vec3(0.04045), c));
}

vec3 linearToSrgb(vec3 c) {
    vec3 lo = c * 12.92;
    vec3 hi = 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055;
    return mix(lo, hi, step(vec3(0.0031308), c));
}

vec3 pqToLinear(vec3 e) {
    vec3 p = pow(clamp(e, 0.0, 1.0), vec3(1.0 / PQ_M2));
    return pow(max(p - PQ_C1, 0.0) / max(PQ_C2 - PQ_C3 * p, 1e-6), vec3(1.0 / PQ_M1));
}

vec3 linearToPq(vec3 y) {
    vec3 p = pow(clamp(y, 0.0, 1.0), vec3(PQ_M1));
    return pow((PQ_C1 + PQ_C2 * p) / (1.0 + PQ_C3 * p), vec3(PQ_M2));
}

float toneCurve(float x, float peak) {
    const float knee = 0.8;
    if (x <= knee) return x;
    float span = max(peak - knee, 0.05);
    float rate = max(span / 3.912, 1.0 - knee);
    return knee + (1.0 - knee) * (1.0 - exp(-(x - knee) / rate));
}

int rectAt(vec2 p) {
    float count = mod(pc.params.w, 16.0);
    for (int i = 0; i < 6; i++) {
        if (float(i) >= count - 0.5) break;
        uvec2 r = pc.rects[i];
        vec2 lo = vec2(float(r.x & 0xFFFFu), float(r.x >> 16));
        vec2 hi = vec2(float(r.y & 0xFFFFu), float(r.y >> 16));
        if (p.x >= lo.x && p.y >= lo.y && p.x < hi.x && p.y < hi.y) return i;
    }
    return -1;
}

bool bitOf(float mask, int i) {
    return i >= 0 && mod(floor(mask / exp2(float(i))), 2.0) > 0.5;
}

vec3 convertedNits(vec3 c) {
    vec3 lin = pc.conv[1].w > 0.5 ? c : pqToLinear(c);
    return vec3(dot(pc.conv[0].xyz, lin), dot(pc.conv[1].xyz, lin), dot(pc.conv[2].xyz, lin)) * pc.conv[2].w;
}

void main() {
    vec3 c = texelFetch(Mixed, ivec2(gl_FragCoord.xy), 0).rgb;
    int at = rectAt(gl_FragCoord.xy);
    bool hdr = bitOf(floor(pc.params.w / 16.0), at);
    bool conv = hdr && bitOf(pc.conv[0].w, at);
    float sdrWhite = max(pc.params.x, 1.0);
    vec3 outc;
    if (pc.params.z < 0.5) {
        if (conv) {
            outc = linearToPq(max(convertedNits(c), 0.0) / 10000.0);
        } else if (hdr) {
            outc = c;
        } else {
            vec3 nits = (BT709_TO_BT2020 * srgbToLinear(c)) * sdrWhite;
            outc = linearToPq(nits / 10000.0);
        }
    } else {
        if (!hdr) {
            outc = c;
        } else {
            vec3 nits = conv ? convertedNits(c) : pqToLinear(c) * 10000.0;
            vec3 rel = max(BT2020_TO_BT709 * nits, 0.0) / sdrWhite;
            float m = max(max(rel.r, rel.g), rel.b);
            if (m > 1e-6) rel *= toneCurve(m, max(pc.params.y, sdrWhite) / sdrWhite) / m;
            outc = linearToSrgb(clamp(rel, 0.0, 1.0));
        }
    }
    outColor = vec4(outc, 1.0);
}
