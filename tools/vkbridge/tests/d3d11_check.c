/* SPDX-License-Identifier: GPL-3.0-or-later
 *
 * d3d11_check.exe - checks the D3D11 features the Mali bridge emulates, through DXVK, by reading
 * back pixels: BC1/BC3/BC4/BC5/BC7 sampling, SV_ClipDistance, instance data step rates
 * (vertex attribute divisor), plus a plain clear and draw. Prints PASS/FAIL lines; exit 0 = all.
 *
 * Built with mingw-w64:  x86_64-w64-mingw32-gcc -O2 d3d11_check.c -o d3d11_check.exe -ld3d11 -ld3dcompiler_47 -ldxgi
 */
#define COBJMACROS
#define INITGUID
#include <windows.h>
#include <d3d11.h>
#include <d3dcompiler.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

static ID3D11Device *dev;
static ID3D11DeviceContext *ctx;
static ID3D11Texture2D *rt, *staging;
static ID3D11RenderTargetView *rtv;
static int failures, checks;
#define TW 64
#define TH 64

static void check(int ok, const char *what, const char *detail)
{
    checks++;
    if (!ok) failures++;
    printf("%s %s%s%s\n", ok ? "PASS" : "FAIL", what, detail && *detail ? ": " : "", detail ? detail : "");
    fflush(stdout);
}

static ID3DBlob *compile(const char *src, const char *entry, const char *target)
{
    ID3DBlob *code = NULL, *err = NULL;
    HRESULT hr = D3DCompile(src, strlen(src), NULL, NULL, NULL, entry, target, 0, 0, &code, &err);
    if (FAILED(hr)) {
        printf("shader %s failed: %s\n", entry, err ? (const char *)ID3D10Blob_GetBufferPointer(err) : "?");
        return NULL;
    }
    return code;
}

/* Reads the render target back; returns a pointer valid until the next call. */
static const uint32_t *readback(void)
{
    static uint32_t px[TW * TH];
    ID3D11DeviceContext_CopyResource(ctx, (ID3D11Resource *)staging, (ID3D11Resource *)rt);
    D3D11_MAPPED_SUBRESOURCE m;
    if (FAILED(ID3D11DeviceContext_Map(ctx, (ID3D11Resource *)staging, 0, D3D11_MAP_READ, 0, &m))) return NULL;
    for (int y = 0; y < TH; y++) memcpy(px + y * TW, (const uint8_t *)m.pData + y * m.RowPitch, TW * 4);
    ID3D11DeviceContext_Unmap(ctx, (ID3D11Resource *)staging, 0);
    return px;
}

static int close_to(uint32_t a, uint32_t b, int tol)
{
    for (int i = 0; i < 32; i += 8) {
        int d = (int)((a >> i) & 0xff) - (int)((b >> i) & 0xff);
        if (d < -tol || d > tol) return 0;
    }
    return 1;
}

static void clear(float r, float g, float b)
{
    float c[4] = {r, g, b, 1};
    ID3D11DeviceContext_ClearRenderTargetView(ctx, rtv, c);
}

static void bind_target(void)
{
    ID3D11DeviceContext_OMSetRenderTargets(ctx, 1, &rtv, NULL);
    D3D11_VIEWPORT vp = {0, 0, TW, TH, 0, 1};
    ID3D11DeviceContext_RSSetViewports(ctx, 1, &vp);
}

/* ------------------------------------------------------------------ textures */

static const char *tex_vs =
    "struct O { float4 p : SV_Position; float2 uv : TEXCOORD; };"
    "O main(uint id : SV_VertexID) { O o; float2 t = float2((id << 1) & 2, id & 2);"
    "o.p = float4(t * float2(2, -2) + float2(-1, 1), 0, 1); o.uv = t; return o; }";
static const char *tex_ps =
    "Texture2D t : register(t0); SamplerState s : register(s0);"
    "float4 main(float4 p : SV_Position, float2 uv : TEXCOORD) : SV_Target { return t.Sample(s, uv); }";

/* One 8x8 texture of 2x2 blocks, each block a solid colour. Returns RGBA8 (A in top byte). */
static void test_bc(DXGI_FORMAT fmt, const char *name, const void *blocks, size_t block_bytes, const uint32_t want[4], int tol)
{
    D3D11_TEXTURE2D_DESC td = {8, 8, 1, 1, fmt, {1, 0}, D3D11_USAGE_DEFAULT, D3D11_BIND_SHADER_RESOURCE, 0, 0};
    D3D11_SUBRESOURCE_DATA init = {blocks, (UINT)(block_bytes * 2), 0};
    ID3D11Texture2D *tex = NULL;
    HRESULT hr = ID3D11Device_CreateTexture2D(dev, &td, &init, &tex);
    if (FAILED(hr)) {
        char d[64];
        snprintf(d, sizeof d, "CreateTexture2D 0x%08lx", (unsigned long)hr);
        check(0, name, d);
        return;
    }
    ID3D11ShaderResourceView *srv;
    ID3D11Device_CreateShaderResourceView(dev, (ID3D11Resource *)tex, NULL, &srv);
    D3D11_SAMPLER_DESC sd = {D3D11_FILTER_MIN_MAG_MIP_POINT, D3D11_TEXTURE_ADDRESS_CLAMP, D3D11_TEXTURE_ADDRESS_CLAMP,
                             D3D11_TEXTURE_ADDRESS_CLAMP, 0, 1, D3D11_COMPARISON_NEVER, {0}, 0, D3D11_FLOAT32_MAX};
    ID3D11SamplerState *smp;
    ID3D11Device_CreateSamplerState(dev, &sd, &smp);
    static ID3D11VertexShader *vs;
    static ID3D11PixelShader *ps;
    if (!vs) {
        ID3DBlob *v = compile(tex_vs, "main", "vs_5_0"), *p = compile(tex_ps, "main", "ps_5_0");
        ID3D11Device_CreateVertexShader(dev, ID3D10Blob_GetBufferPointer(v), ID3D10Blob_GetBufferSize(v), NULL, &vs);
        ID3D11Device_CreatePixelShader(dev, ID3D10Blob_GetBufferPointer(p), ID3D10Blob_GetBufferSize(p), NULL, &ps);
    }
    bind_target();
    clear(0, 0, 0);
    ID3D11DeviceContext_IASetInputLayout(ctx, NULL);
    ID3D11DeviceContext_IASetPrimitiveTopology(ctx, D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);
    ID3D11DeviceContext_VSSetShader(ctx, vs, NULL, 0);
    ID3D11DeviceContext_PSSetShader(ctx, ps, NULL, 0);
    ID3D11DeviceContext_PSSetShaderResources(ctx, 0, 1, &srv);
    ID3D11DeviceContext_PSSetSamplers(ctx, 0, 1, &smp);
    ID3D11DeviceContext_Draw(ctx, 3, 0);
    const uint32_t *px = readback();
    /* quadrant centres: TL, TR, BL, BR */
    int pts[4][2] = {{TW / 4, TH / 4}, {3 * TW / 4, TH / 4}, {TW / 4, 3 * TH / 4}, {3 * TW / 4, 3 * TH / 4}};
    char d[160] = "";
    int ok = px != NULL;
    for (int i = 0; ok && i < 4; i++) {
        uint32_t v = px[pts[i][1] * TW + pts[i][0]];
        if (!close_to(v, want[i], tol)) {
            snprintf(d, sizeof d, "block %d = 0x%08x, want 0x%08x", i, v, want[i]);
            ok = 0;
        }
    }
    check(ok, name, ok ? "4 blocks decode to the right colours" : d);
}

static void tests_bc(void)
{
    /* BC1: colour0 = colour1 = solid; indices 0. RGB565 values -> 8-bit. */
    uint16_t c[4] = {0xF800, 0x07E0, 0x001F, 0xFFFF}; /* red, green, blue, white */
    uint8_t bc1[4][8];
    for (int i = 0; i < 4; i++) {
        memcpy(bc1[i], &c[i], 2);
        memcpy(bc1[i] + 2, &c[i], 2);
        memset(bc1[i] + 4, 0, 4);
    }
    /* layout: block row 0 = blocks 0,1; row 1 = 2,3 */
    uint32_t want1[4] = {0xFF0000FF, 0xFF00FF00, 0xFFFF0000, 0xFFFFFFFF};
    test_bc(DXGI_FORMAT_BC1_UNORM, "BC1 texture", bc1, 8, want1, 8);

    /* BC3: alpha 255 (alpha endpoints 255,255) + BC1 colour block. */
    uint8_t bc3[4][16];
    for (int i = 0; i < 4; i++) {
        memset(bc3[i], 0, 16);
        bc3[i][0] = bc3[i][1] = 0xFF;
        memcpy(bc3[i] + 8, bc1[i], 8);
    }
    test_bc(DXGI_FORMAT_BC3_UNORM, "BC3 texture", bc3, 16, want1, 8);

    /* BC4: single channel; endpoints = value. Sampled as R, G=B=0, A=1. */
    uint8_t vals[4] = {255, 128, 64, 0};
    uint8_t bc4[4][8];
    uint32_t want4[4];
    for (int i = 0; i < 4; i++) {
        memset(bc4[i], 0, 8);
        bc4[i][0] = bc4[i][1] = vals[i];
        want4[i] = 0xFF000000u | vals[i];
    }
    test_bc(DXGI_FORMAT_BC4_UNORM, "BC4 texture", bc4, 8, want4, 2);

    /* BC5: two channels (R from first half, G from second). */
    uint8_t bc5[4][16];
    uint32_t want5[4];
    for (int i = 0; i < 4; i++) {
        memset(bc5[i], 0, 16);
        bc5[i][0] = bc5[i][1] = vals[i];
        bc5[i][8] = bc5[i][9] = vals[3 - i];
        want5[i] = 0xFF000000u | vals[i] | ((uint32_t)vals[3 - i] << 8);
    }
    test_bc(DXGI_FORMAT_BC5_UNORM, "BC5 texture", bc5, 16, want5, 2);

    /* BC7 mode 6: endpoints RGBA 7+1 bits; solid colour when both endpoints are equal. Encoded
     * below by bit packing: mode 6 = bit 6 set (bits 0-6 = 1000000b). */
    uint8_t bc7[4][16];
    uint32_t want7[4];
    uint8_t cols[4][3] = {{255, 0, 0}, {0, 255, 0}, {0, 0, 255}, {255, 255, 255}};
    for (int i = 0; i < 4; i++) {
        uint8_t blk[16] = {0};
        int bit = 0;
#define PUT(v, n) do { for (int k_ = 0; k_ < (n); k_++, bit++) if (((v) >> k_) & 1) blk[bit >> 3] |= (uint8_t)(1u << (bit & 7)); } while (0)
        PUT(1u << 6, 7);                              /* mode 6 */
        for (int ch = 0; ch < 3; ch++) { PUT(cols[i][ch] >> 1, 7); PUT(cols[i][ch] >> 1, 7); }
        PUT(127, 7); PUT(127, 7);                     /* alpha endpoints */
        PUT(cols[i][0] & 1, 1); PUT(cols[i][0] & 1, 1); /* p-bits (same for all channels) */
#undef PUT
        memcpy(bc7[i], blk, 16);
        uint8_t r = cols[i][0], g = cols[i][1], b = cols[i][2];
        /* 7-bit value + shared p-bit */
        uint8_t p = cols[i][0] & 1;
        r = (uint8_t)(((r >> 1) << 1) | p);
        g = (uint8_t)(((g >> 1) << 1) | p);
        b = (uint8_t)(((b >> 1) << 1) | p);
        want7[i] = 0xFF000000u | r | ((uint32_t)g << 8) | ((uint32_t)b << 16);
    }
    test_bc(DXGI_FORMAT_BC7_UNORM, "BC7 texture", bc7, 16, want7, 3);
}

/* ------------------------------------------------------------------ clip distance */

static void test_clip(void)
{
    const char *vs =
        "struct O { float4 p : SV_Position; float c : SV_ClipDistance0; };"
        "O main(uint id : SV_VertexID) { O o; float2 t = float2((id << 1) & 2, id & 2);"
        "o.p = float4(t * float2(2, -2) + float2(-1, 1), 0, 1); o.c = o.p.x; return o; }";
    const char *ps = "float4 main() : SV_Target { return float4(0, 1, 0, 1); }";
    ID3DBlob *v = compile(vs, "main", "vs_5_0"), *p = compile(ps, "main", "ps_5_0");
    if (!v || !p) {
        check(0, "clip distance", "shader compile");
        return;
    }
    ID3D11VertexShader *vsh;
    ID3D11PixelShader *psh;
    ID3D11Device_CreateVertexShader(dev, ID3D10Blob_GetBufferPointer(v), ID3D10Blob_GetBufferSize(v), NULL, &vsh);
    ID3D11Device_CreatePixelShader(dev, ID3D10Blob_GetBufferPointer(p), ID3D10Blob_GetBufferSize(p), NULL, &psh);
    bind_target();
    clear(1, 0, 0);
    ID3D11DeviceContext_IASetInputLayout(ctx, NULL);
    ID3D11DeviceContext_IASetPrimitiveTopology(ctx, D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);
    ID3D11DeviceContext_VSSetShader(ctx, vsh, NULL, 0);
    ID3D11DeviceContext_PSSetShader(ctx, psh, NULL, 0);
    ID3D11DeviceContext_Draw(ctx, 3, 0);
    const uint32_t *px = readback();
    int ok = px && close_to(px[TH / 2 * TW + TW / 4], 0xFF0000FF, 2) && close_to(px[TH / 2 * TW + 3 * TW / 4], 0xFF00FF00, 2);
    char d[96];
    snprintf(d, sizeof d, "left 0x%08x (want red), right 0x%08x (want green)", px ? px[TH / 2 * TW + TW / 4] : 0,
             px ? px[TH / 2 * TW + 3 * TW / 4] : 0);
    check(ok, "SV_ClipDistance", d);
}

/* ------------------------------------------------------------------ instance step rate */

static void test_step_rate(void)
{
    /* Four instances of a quad column; per-instance colour with step rate 2: instances 0-1 get
     * colour 0 (blue), 2-3 colour 1 (yellow). Each instance covers a quarter of the width. */
    const char *vs =
        "struct I { uint id : SV_VertexID; uint inst : SV_InstanceID; float4 col : COLOR; };"
        "struct O { float4 p : SV_Position; float4 col : COLOR; };"
        "O main(I i) { O o; float2 t = float2((i.id << 1) & 2, i.id & 2) * 0.5; /* 0..1 tri covering a quad's lower-left half */"
        "float x0 = -1 + 0.5 * i.inst; float2 q = float2(i.id == 1 ? 1 : 0, i.id == 2 ? 1 : 0);"
        "float2 pos[6] = { float2(0,0), float2(1,0), float2(0,1), float2(1,0), float2(1,1), float2(0,1) };"
        "float2 v = pos[i.id]; o.p = float4(x0 + v.x * 0.5, -1 + v.y * 2, 0, 1); o.col = i.col; return o; }";
    const char *ps = "float4 main(float4 p : SV_Position, float4 c : COLOR) : SV_Target { return c; }";
    ID3DBlob *v = compile(vs, "main", "vs_5_0"), *p = compile(ps, "main", "ps_5_0");
    if (!v || !p) {
        check(0, "instance step rate", "shader compile");
        return;
    }
    ID3D11VertexShader *vsh;
    ID3D11PixelShader *psh;
    ID3D11Device_CreateVertexShader(dev, ID3D10Blob_GetBufferPointer(v), ID3D10Blob_GetBufferSize(v), NULL, &vsh);
    ID3D11Device_CreatePixelShader(dev, ID3D10Blob_GetBufferPointer(p), ID3D10Blob_GetBufferSize(p), NULL, &psh);
    D3D11_INPUT_ELEMENT_DESC el = {"COLOR", 0, DXGI_FORMAT_R32G32B32A32_FLOAT, 0, 0, D3D11_INPUT_PER_INSTANCE_DATA, 2};
    ID3D11InputLayout *il;
    HRESULT hr = ID3D11Device_CreateInputLayout(dev, &el, 1, ID3D10Blob_GetBufferPointer(v), ID3D10Blob_GetBufferSize(v), &il);
    if (FAILED(hr)) {
        check(0, "instance step rate", "CreateInputLayout");
        return;
    }
    float cols[2][4] = {{0, 0, 1, 1}, {1, 1, 0, 1}};
    D3D11_BUFFER_DESC bd = {sizeof cols, D3D11_USAGE_DEFAULT, D3D11_BIND_VERTEX_BUFFER, 0, 0, 0};
    D3D11_SUBRESOURCE_DATA init = {cols, 0, 0};
    ID3D11Buffer *vb;
    ID3D11Device_CreateBuffer(dev, &bd, &init, &vb);
    UINT stride = 16, offset = 0;
    D3D11_RASTERIZER_DESC rd = {D3D11_FILL_SOLID, D3D11_CULL_NONE, FALSE, 0, 0, 0, TRUE, FALSE, FALSE, FALSE};
    ID3D11RasterizerState *rs;
    ID3D11Device_CreateRasterizerState(dev, &rd, &rs);
    ID3D11DeviceContext_RSSetState(ctx, rs);
    bind_target();
    clear(0, 0, 0);
    ID3D11DeviceContext_IASetInputLayout(ctx, il);
    ID3D11DeviceContext_IASetVertexBuffers(ctx, 0, 1, &vb, &stride, &offset);
    ID3D11DeviceContext_IASetPrimitiveTopology(ctx, D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);
    ID3D11DeviceContext_VSSetShader(ctx, vsh, NULL, 0);
    ID3D11DeviceContext_PSSetShader(ctx, psh, NULL, 0);
    ID3D11DeviceContext_DrawInstanced(ctx, 6, 4, 0, 0);
    const uint32_t *px = readback();
    uint32_t want[4] = {0xFFFF0000, 0xFFFF0000, 0xFF00FFFF, 0xFF00FFFF};
    int ok = px != NULL;
    char d[128] = "instances 0-1 blue, 2-3 yellow";
    for (int i = 0; ok && i < 4; i++) {
        uint32_t got = px[TH / 2 * TW + i * TW / 4 + TW / 8];
        if (!close_to(got, want[i], 2)) {
            snprintf(d, sizeof d, "instance %d = 0x%08x, want 0x%08x", i, got, want[i]);
            ok = 0;
        }
    }
    check(ok, "instance step rate 2", d);
}

/* ------------------------------------------------------------------ depth clip */

static void test_depth_clip(void)
{
    /* A full-screen triangle at z = 1.5 (beyond the far plane): clipped away with depth clip on,
     * visible (clamped to the far plane) with it off. */
    const char *vs = "float4 main(uint id : SV_VertexID) : SV_Position { float2 t = float2((id << 1) & 2, id & 2);"
                     "return float4(t * float2(2, -2) + float2(-1, 1), 1.5, 1); }";
    const char *ps = "float4 main() : SV_Target { return float4(1, 1, 1, 1); }";
    ID3DBlob *v = compile(vs, "main", "vs_5_0"), *p = compile(ps, "main", "ps_5_0");
    ID3D11VertexShader *vsh;
    ID3D11PixelShader *psh;
    ID3D11Device_CreateVertexShader(dev, ID3D10Blob_GetBufferPointer(v), ID3D10Blob_GetBufferSize(v), NULL, &vsh);
    ID3D11Device_CreatePixelShader(dev, ID3D10Blob_GetBufferPointer(p), ID3D10Blob_GetBufferSize(p), NULL, &psh);
    uint32_t got[2];
    for (int on = 1; on >= 0; on--) {
        D3D11_RASTERIZER_DESC rd = {D3D11_FILL_SOLID, D3D11_CULL_NONE, FALSE, 0, 0, 0, on ? TRUE : FALSE, FALSE, FALSE, FALSE};
        ID3D11RasterizerState *rs;
        ID3D11Device_CreateRasterizerState(dev, &rd, &rs);
        ID3D11DeviceContext_RSSetState(ctx, rs);
        bind_target();
        clear(0, 0, 0);
        ID3D11DeviceContext_IASetInputLayout(ctx, NULL);
        ID3D11DeviceContext_IASetPrimitiveTopology(ctx, D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);
        ID3D11DeviceContext_VSSetShader(ctx, vsh, NULL, 0);
        ID3D11DeviceContext_PSSetShader(ctx, psh, NULL, 0);
        ID3D11DeviceContext_Draw(ctx, 3, 0);
        const uint32_t *px = readback();
        got[on] = px ? px[TH / 2 * TW + TW / 2] : 0;
    }
    ID3D11DeviceContext_RSSetState(ctx, NULL);
    char d[96];
    snprintf(d, sizeof d, "clip on 0x%08x (want black), off 0x%08x (want white)", got[1], got[0]);
    check(close_to(got[1], 0xFF000000, 2) && close_to(got[0], 0xFFFFFFFF, 2), "DepthClipEnable", d);
}

int main(void)
{
    D3D_FEATURE_LEVEL fl = D3D_FEATURE_LEVEL_11_0, got;
    HRESULT hr = D3D11CreateDevice(NULL, D3D_DRIVER_TYPE_HARDWARE, NULL, 0, &fl, 1, D3D11_SDK_VERSION, &dev, &got, &ctx);
    char d[64];
    snprintf(d, sizeof d, "hr 0x%08lx", (unsigned long)hr);
    check(SUCCEEDED(hr), "D3D11 device (feature level 11_0)", SUCCEEDED(hr) ? "" : d);
    if (FAILED(hr)) return 1;
    D3D11_TEXTURE2D_DESC td = {TW, TH, 1, 1, DXGI_FORMAT_R8G8B8A8_UNORM, {1, 0}, D3D11_USAGE_DEFAULT, D3D11_BIND_RENDER_TARGET, 0, 0};
    ID3D11Device_CreateTexture2D(dev, &td, NULL, &rt);
    ID3D11Device_CreateRenderTargetView(dev, (ID3D11Resource *)rt, NULL, &rtv);
    td.Usage = D3D11_USAGE_STAGING;
    td.BindFlags = 0;
    td.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
    ID3D11Device_CreateTexture2D(dev, &td, NULL, &staging);
    bind_target();
    clear(0.25f, 0.5f, 0.75f);
    const uint32_t *px = readback();
    check(px && close_to(px[0], 0xFFBF8040, 2), "clear + readback", "");
    tests_bc();
    test_clip();
    test_step_rate();
    test_depth_clip();
    printf("%s: %d of %d checks passed\n", failures ? "FAILED" : "OK", checks - failures, checks);
    return failures ? 1 : 0;
}
