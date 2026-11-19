#version 450

layout(location = 0) in vec2 fragTexCoord;
layout(location = 0) out vec4 outColor;
layout(set = 0, binding = 0) uniform sampler2D EdgeTexture;
layout(push_constant) uniform Constants {
    vec4 ndc;
    vec2 resolution;
    float extra;
    float pad;
} pc;

float verticalEdge(vec2 uv) {
    vec4 e = texture(EdgeTexture, uv);
    return max(e.r, e.g);
}

float horizontalEdge(vec2 uv) {
    vec4 e = texture(EdgeTexture, uv);
    return max(e.b, e.a);
}

void main() {
    vec2 px = 1.0 / max(pc.resolution, vec2(1.0));
    vec2 uv = clamp(fragTexCoord, px * 0.5, vec2(1.0) - px * 0.5);
    vec4 e = texture(EdgeTexture, uv);

    float dL = e.r;
    float dR = e.g;
    float dU = e.b;
    float dD = e.a;
    float vertical = max(dL, dR);
    float horizontal = max(dU, dD);
    float strongest = max(vertical, horizontal);
    float threshold = max(0.045, strongest * 0.12);

    if (strongest <= threshold) {
        outColor = vec4(0.0, 0.5, 0.5, 0.0);
        return;
    }

    bool verticalLine = vertical >= horizontal;
    vec2 tangent = verticalLine ? vec2(0.0, px.y) : vec2(px.x, 0.0);
    vec2 normal = verticalLine
        ? vec2(dL >= dR ? -1.0 : 1.0, 0.0)
        : vec2(0.0, dU >= dD ? -1.0 : 1.0);

    float negativeRun = 0.0;
    float positiveRun = 0.0;
    for (int i = 1; i <= 6; ++i) {
        vec2 offset = tangent * float(i);
        float edgeN = verticalLine ? verticalEdge(uv - offset) : horizontalEdge(uv - offset);
        float edgeP = verticalLine ? verticalEdge(uv + offset) : horizontalEdge(uv + offset);
        if (negativeRun == float(i - 1) && edgeN > threshold * 0.65) negativeRun = float(i);
        if (positiveRun == float(i - 1) && edgeP > threshold * 0.65) positiveRun = float(i);
    }

    float span = negativeRun + positiveRun;
    float edgeConfidence = smoothstep(threshold, threshold * 2.5, strongest);

    vec4 nw = texture(EdgeTexture, uv + vec2(-px.x, -px.y));
    vec4 ne = texture(EdgeTexture, uv + vec2(px.x, -px.y));
    vec4 sw = texture(EdgeTexture, uv + vec2(-px.x, px.y));
    vec4 se = texture(EdgeTexture, uv + vec2(px.x, px.y));

    float endpointPattern = clamp(abs(negativeRun - positiveRun) / max(span, 1.0), 0.0, 1.0);
    float endpointArea = endpointPattern / (1.0 + span * 0.055);
    float crossingA = verticalLine
        ? abs(max(nw.b, nw.a) - max(ne.b, ne.a))
        : abs(max(nw.r, nw.g) - max(sw.r, sw.g));
    float crossingB = verticalLine
        ? abs(max(sw.b, sw.a) - max(se.b, se.a))
        : abs(max(ne.r, ne.g) - max(se.r, se.g));
    float crossingContrast = abs(crossingA - crossingB);
    float crossingPattern = smoothstep(threshold, threshold * 2.5, crossingContrast);
    float areaWeight = 0.38 * crossingPattern * clamp(span / 4.0, 0.0, 1.0);
    float diagonalContrast = max(abs(nw.r - se.g), abs(ne.b - sw.a));
    float cornerFactor = 1.0 - 0.35 * smoothstep(threshold, threshold * 3.0, diagonalContrast);
    float baseWeight = areaWeight * endpointArea * edgeConfidence * cornerFactor;

    float diagonalStrength = min(vertical, horizontal);
    float diagonalPattern = smoothstep(threshold, threshold * 2.5, diagonalStrength);
    float diagonalContinuity = smoothstep(1.0, 3.0, span);
    float diagonalFallback = 0.16 * diagonalPattern * edgeConfidence
        * cornerFactor * diagonalContinuity * crossingPattern;

    float weight = clamp(max(baseWeight, diagonalFallback), 0.0, 0.42);
    outColor = vec4(weight, normal.x * 0.5 + 0.5, normal.y * 0.5 + 0.5, 1.0);
}
