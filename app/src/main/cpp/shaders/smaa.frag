#version 450

layout(location = 0) in vec2 fragTexCoord;
layout(location = 0) out vec4 outColor;
layout(set = 0, binding = 0) uniform sampler2D InputTexture;
layout(push_constant) uniform Constants {
    vec4 ndc;
    vec2 resolution;
    float extra;
    float pad;
} pc;

float luma(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

float edgeVertical(vec2 uv, vec2 px) {
    float a = luma(texture(InputTexture, uv - vec2(px.x, 0.0)).rgb);
    float b = luma(texture(InputTexture, uv + vec2(px.x, 0.0)).rgb);
    return abs(a - b);
}

float edgeHorizontal(vec2 uv, vec2 px) {
    float a = luma(texture(InputTexture, uv - vec2(0.0, px.y)).rgb);
    float b = luma(texture(InputTexture, uv + vec2(0.0, px.y)).rgb);
    return abs(a - b);
}

void main() {
    vec2 px = 1.0 / max(pc.resolution, vec2(1.0));
    vec2 uv = clamp(fragTexCoord, px * 0.5, vec2(1.0) - px * 0.5);
    vec4 center = texture(InputTexture, uv);

    float lc = luma(center.rgb);
    float ll = luma(texture(InputTexture, uv - vec2(px.x, 0.0)).rgb);
    float lr = luma(texture(InputTexture, uv + vec2(px.x, 0.0)).rgb);
    float lu = luma(texture(InputTexture, uv - vec2(0.0, px.y)).rgb);
    float ld = luma(texture(InputTexture, uv + vec2(0.0, px.y)).rgb);

    float dL = abs(lc - ll);
    float dR = abs(lc - lr);
    float dU = abs(lc - lu);
    float dD = abs(lc - ld);
    float localMin = min(lc, min(min(ll, lr), min(lu, ld)));
    float localMax = max(lc, max(max(ll, lr), max(lu, ld)));
    float localContrast = localMax - localMin;
    float threshold = max(0.045, localContrast * 0.08);

    float vertical = max(dL, dR);
    float horizontal = max(dU, dD);
    float strongest = max(vertical, horizontal);
    if (strongest <= threshold) {
        outColor = center;
        return;
    }

    bool verticalEdge = vertical >= horizontal;
    vec2 tangent = verticalEdge ? vec2(0.0, px.y) : vec2(px.x, 0.0);
    vec2 normal = verticalEdge
        ? vec2(dL >= dR ? -1.0 : 1.0, 0.0)
        : vec2(0.0, dU >= dD ? -1.0 : 1.0);

    float negativeRun = 0.0;
    float positiveRun = 0.0;
    for (int i = 1; i <= 6; ++i) {
        vec2 offset = tangent * float(i);
        float edgeN = verticalEdge ? edgeVertical(uv - offset, px) : edgeHorizontal(uv - offset, px);
        float edgeP = verticalEdge ? edgeVertical(uv + offset, px) : edgeHorizontal(uv + offset, px);
        if (negativeRun == float(i - 1) && edgeN > threshold * 0.65) negativeRun = float(i);
        if (positiveRun == float(i - 1) && edgeP > threshold * 0.65) positiveRun = float(i);
    }

    float span = negativeRun + positiveRun;
    float edgeConfidence = smoothstep(threshold, threshold * 2.5, strongest);

    float nw = luma(texture(InputTexture, uv + vec2(-px.x, -px.y)).rgb);
    float ne = luma(texture(InputTexture, uv + vec2(px.x, -px.y)).rgb);
    float sw = luma(texture(InputTexture, uv + vec2(-px.x, px.y)).rgb);
    float se = luma(texture(InputTexture, uv + vec2(px.x, px.y)).rgb);
    float endpointPattern = clamp(abs(negativeRun - positiveRun) / max(span, 1.0), 0.0, 1.0);
    float endpointArea = endpointPattern / (1.0 + span * 0.055);
    float crossingA = verticalEdge ? abs(nw - ne) : abs(nw - sw);
    float crossingB = verticalEdge ? abs(sw - se) : abs(ne - se);
    float crossingContrast = abs(crossingA - crossingB);
    float crossingPattern = smoothstep(threshold, threshold * 2.5, crossingContrast);
    float areaWeight = 0.38 * crossingPattern * clamp(span / 4.0, 0.0, 1.0);
    float diagonalContrast = max(abs(nw - se), abs(ne - sw));
    float cornerFactor = 1.0 - 0.18 * smoothstep(threshold, threshold * 3.0, diagonalContrast);
    float baseWeight = areaWeight * endpointArea * edgeConfidence * cornerFactor;

    float diagonalStrength = abs(abs(nw - se) - abs(ne - sw));
    float diagonalPattern = smoothstep(threshold, threshold * 2.5, diagonalStrength);
    float diagonalFallback = 0.16 * diagonalPattern * edgeConfidence * cornerFactor;

    float weight = clamp(max(baseWeight, diagonalFallback), 0.0, 0.42);
    vec3 across = texture(InputTexture, uv + normal * px).rgb;
    outColor = vec4(mix(center.rgb, across, weight), center.a);
}
