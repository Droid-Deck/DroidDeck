#version 450

// Full-screen / sub-rect quad for the spatial-upscaler post passes (SGSR, FSR
// EASU/RCAS). Identical quad generation to window.vert, but the push-constant
// block is declared as a single vec4 `ndc` so it matches the leading member of
// the upscaler fragment shaders' push-constant blocks (cross-stage layout must
// agree). gl_VertexIndex 0..3 over a TRIANGLE_STRIP -> the rect; fragTexCoord
// spans [0,1] across that rect (= normalised output position).

layout(push_constant) uniform PC {
    vec4 ndc; // (x0, y0, x1, y1) destination rect in NDC
} pc;

layout(location = 0) out vec2 fragTexCoord;

void main() {
    int xi = (gl_VertexIndex >> 1) & 1;
    int yi = gl_VertexIndex & 1;
    float x = xi == 1 ? pc.ndc.z : pc.ndc.x;
    float y = yi == 1 ? pc.ndc.w : pc.ndc.y;
    gl_Position = vec4(x, y, 0.0, 1.0);
    fragTexCoord = vec2(float(xi), float(yi));
}
