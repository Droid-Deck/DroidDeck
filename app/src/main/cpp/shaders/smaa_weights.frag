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

vec4 edgeAt(vec2 uv, vec2 px) {
    vec2 edgeUV = clamp(uv, px * 0.5, vec2(1.0) - px * 0.5);
    vec4 e = textureLod(EdgeTexture, edgeUV, 0.0);
    e.a = edgeUV.y >= 1.0 - px.y ? 0.0 : e.a;
    return e;
}

float verticalEdge(vec2 uv, vec2 px) {
    vec4 e = edgeAt(uv, px);
    return max(e.r, e.g);
}

float horizontalEdge(vec2 uv, vec2 px) {
    vec4 e = edgeAt(uv, px);
    return max(e.b, e.a);
}

float measureRun(vec2 origin, vec2 texel, vec2 tangent,
                  bool verticalLine, float limit, bool positive) {
    float run = 0.0;
    for (int i = 1; i <= 6; ++i) {
        vec2 probe = origin + tangent * float(i) * (positive ? 1.0 : -1.0);
        ivec2 index = ivec2(floor(probe * pc.resolution));
        ivec2 size = ivec2(pc.resolution);
        if (any(lessThan(index, ivec2(0))) ||
            any(greaterThanEqual(index, size))) {
            break;
        }
        float edge = verticalLine
            ? verticalEdge(probe, texel)
            : horizontalEdge(probe, texel);
        if (edge <= limit * 0.65) {
            break;
        }
        run = float(i);
    }
    return run;
}

void main() {
    vec2 px = 1.0 / max(pc.resolution, vec2(1.0));
    vec2 uv = clamp(fragTexCoord, px * 0.5, vec2(1.0) - px * 0.5);
    vec4 e = edgeAt(uv, px);

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

    bool verticalLine;
    float negativeRun = 0.0;
    float positiveRun = 0.0;
    bool runsMeasured = false;

    if (vertical > horizontal) {
        verticalLine = true;
    } else if (horizontal > vertical) {
        verticalLine = false;
    } else {
        float vNeg = measureRun(uv, px, vec2(0.0, px.y), true, threshold, false);
        float vPos = measureRun(uv, px, vec2(0.0, px.y), true, threshold, true);
        float hNeg = measureRun(uv, px, vec2(px.x, 0.0), false, threshold, false);
        float hPos = measureRun(uv, px, vec2(px.x, 0.0), false, threshold, true);
        verticalLine = (vNeg + vPos) >= (hNeg + hPos);
        negativeRun = verticalLine ? vNeg : hNeg;
        positiveRun = verticalLine ? vPos : hPos;
        runsMeasured = true;
    }

    vec2 tangent = verticalLine ? vec2(0.0, px.y) : vec2(px.x, 0.0);
    vec2 normal = verticalLine
        ? vec2(dL >= dR ? -1.0 : 1.0, 0.0)
        : vec2(0.0, dU >= dD ? -1.0 : 1.0);

    if (!runsMeasured) {
        negativeRun = measureRun(uv, px, tangent, verticalLine, threshold, false);
        positiveRun = measureRun(uv, px, tangent, verticalLine, threshold, true);
    }

    float span = negativeRun + positiveRun;
    float edgeConfidence = smoothstep(threshold, threshold * 2.5, strongest);

    vec4 nw = edgeAt(uv + vec2(-px.x, -px.y), px);
    vec4 ne = edgeAt(uv + vec2(px.x, -px.y), px);
    vec4 sw = edgeAt(uv + vec2(-px.x, px.y), px);
    vec4 se = edgeAt(uv + vec2(px.x, px.y), px);

    float endpointPattern = clamp(abs(negativeRun - positiveRun) / max(span, 1.0), 0.0, 1.0);
    float endpointArea = endpointPattern / (1.0 + span * 0.055);
    float crossingA = verticalLine
        ? abs(nw.a - ne.a)
        : abs(nw.g - sw.g);
    float crossingB = verticalLine
        ? abs(sw.b - se.b)
        : abs(ne.r - se.r);
    float crossingContrast = abs(crossingA - crossingB);
    float crossingPattern = smoothstep(threshold, threshold * 2.5, crossingContrast);
    vec4 edgeMin = min(min(nw, ne), min(sw, se));
    vec4 edgeMax = max(max(nw, ne), max(sw, se));
    float edgeVariation = max(
        max(edgeMax.r - edgeMin.r, edgeMax.g - edgeMin.g),
        max(edgeMax.b - edgeMin.b, edgeMax.a - edgeMin.a));
    float balancedCrossing = edgeVariation > 0.01
        ? smoothstep(threshold, threshold * 2.5, min(crossingA, crossingB))
        : 0.0;
    float areaWeight = 0.38 * crossingPattern * clamp(span / 4.0, 0.0, 1.0);
    float diagonalContrast = max(
        max(max(abs(nw.r - se.r), abs(nw.g - se.g)),
            max(abs(nw.b - se.b), abs(nw.a - se.a))),
        max(max(abs(ne.r - sw.r), abs(ne.g - sw.g)),
            max(abs(ne.b - sw.b), abs(ne.a - sw.a))));
    float cornerFactor = 1.0 - 0.35 * smoothstep(threshold, threshold * 3.0, diagonalContrast);
    float baseWeight = areaWeight * endpointArea * edgeConfidence * cornerFactor;

    float diagonalStrength = min(vertical, horizontal);
    float diagonalPattern = smoothstep(threshold, threshold * 2.5, diagonalStrength);
    float diagonalContinuity = smoothstep(1.0, 3.0, span);
    float shortDiagonalFallback =
        (span > 0.0 && span <= 1.0 && crossingContrast <= threshold && diagonalStrength > threshold)
            ? 1.0 : 0.0;
    float diagonalFallback = 0.16 * diagonalPattern * edgeConfidence
        * cornerFactor * max(
            diagonalContinuity * max(crossingPattern, balancedCrossing),
            shortDiagonalFallback);

    float weight = clamp(max(baseWeight, diagonalFallback), 0.0, 0.42);
    outColor = vec4(weight, normal.x * 0.5 + 0.5, normal.y * 0.5 + 0.5, 1.0);
}
