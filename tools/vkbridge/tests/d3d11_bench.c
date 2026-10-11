/* SPDX-License-Identifier: GPL-3.0-or-later
 *
 * d3d11_bench.exe [draws] [frames]: a game-like D3D11 frame - many small draws, each with a
 * Map(WRITE_DISCARD) constant buffer update and its own texture/sampler binding - rendered
 * offscreen and flushed every frame (with a readback every 30th, so the GPU cannot fall behind).
 * Prints milliseconds per frame: the bridge's overhead shows as the difference to native.
 */
#define COBJMACROS
#include <windows.h>
#include <d3d11.h>
#include <d3dcompiler.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

int main(int argc, char **argv)
{
    int draws = argc > 1 ? atoi(argv[1]) : 1000;
    int frames = argc > 2 ? atoi(argv[2]) : 300;
    ID3D11Device *dev;
    ID3D11DeviceContext *ctx;
    D3D_FEATURE_LEVEL fl = D3D_FEATURE_LEVEL_11_0;
    if (FAILED(D3D11CreateDevice(NULL, D3D_DRIVER_TYPE_HARDWARE, NULL, 0, &fl, 1, D3D11_SDK_VERSION, &dev, NULL, &ctx))) {
        printf("no device\n");
        return 1;
    }
    const char *vs = "cbuffer C : register(b0) { float4 pos; float4 col; };"
                     "struct O { float4 p : SV_Position; float4 c : COLOR; float2 uv : TEXCOORD; };"
                     "O main(uint id : SV_VertexID) { O o; float2 t = float2((id << 1) & 2, id & 2) * 0.05;"
                     "o.p = float4(pos.xy + t, 0, 1); o.c = col; o.uv = t * 20; return o; }";
    const char *ps = "Texture2D t : register(t0); SamplerState s : register(s0);"
                     "float4 main(float4 p : SV_Position, float4 c : COLOR, float2 uv : TEXCOORD) : SV_Target { return c * t.Sample(s, uv); }";
    ID3DBlob *vb, *pb;
    D3DCompile(vs, strlen(vs), NULL, NULL, NULL, "main", "vs_5_0", 0, 0, &vb, NULL);
    D3DCompile(ps, strlen(ps), NULL, NULL, NULL, "main", "ps_5_0", 0, 0, &pb, NULL);
    ID3D11VertexShader *vsh;
    ID3D11PixelShader *psh;
    ID3D11Device_CreateVertexShader(dev, ID3D10Blob_GetBufferPointer(vb), ID3D10Blob_GetBufferSize(vb), NULL, &vsh);
    ID3D11Device_CreatePixelShader(dev, ID3D10Blob_GetBufferPointer(pb), ID3D10Blob_GetBufferSize(pb), NULL, &psh);
    D3D11_BUFFER_DESC bd = {32, D3D11_USAGE_DYNAMIC, D3D11_BIND_CONSTANT_BUFFER, D3D11_CPU_ACCESS_WRITE, 0, 0};
    ID3D11Buffer *cb;
    ID3D11Device_CreateBuffer(dev, &bd, NULL, &cb);
    /* 8 small BC1 textures (block-compressed like a game's) */
    ID3D11ShaderResourceView *srv[8];
    uint8_t blocks[16 * 8];
    for (int i = 0; i < (int)sizeof(blocks); i++) blocks[i] = (uint8_t)(i * 37 + 11);
    for (int i = 0; i < 8; i++) {
        D3D11_TEXTURE2D_DESC td = {16, 16, 1, 1, DXGI_FORMAT_BC1_UNORM, {1, 0}, D3D11_USAGE_DEFAULT, D3D11_BIND_SHADER_RESOURCE, 0, 0};
        D3D11_SUBRESOURCE_DATA init = {blocks, 32, 0};
        ID3D11Texture2D *t;
        ID3D11Device_CreateTexture2D(dev, &td, &init, &t);
        ID3D11Device_CreateShaderResourceView(dev, (ID3D11Resource *)t, NULL, &srv[i]);
    }
    D3D11_SAMPLER_DESC sd = {D3D11_FILTER_MIN_MAG_MIP_LINEAR, D3D11_TEXTURE_ADDRESS_WRAP, D3D11_TEXTURE_ADDRESS_WRAP, D3D11_TEXTURE_ADDRESS_WRAP,
                             0, 1, D3D11_COMPARISON_NEVER, {0}, 0, D3D11_FLOAT32_MAX};
    ID3D11SamplerState *smp;
    ID3D11Device_CreateSamplerState(dev, &sd, &smp);
    D3D11_TEXTURE2D_DESC rd = {1280, 720, 1, 1, DXGI_FORMAT_R8G8B8A8_UNORM, {1, 0}, D3D11_USAGE_DEFAULT, D3D11_BIND_RENDER_TARGET, 0, 0};
    ID3D11Texture2D *rt, *st;
    ID3D11Device_CreateTexture2D(dev, &rd, NULL, &rt);
    rd.Usage = D3D11_USAGE_STAGING;
    rd.BindFlags = 0;
    rd.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
    rd.Width = rd.Height = 16;
    ID3D11Device_CreateTexture2D(dev, &rd, NULL, &st);
    ID3D11RenderTargetView *rtv;
    ID3D11Device_CreateRenderTargetView(dev, (ID3D11Resource *)rt, NULL, &rtv);
    D3D11_VIEWPORT vp = {0, 0, 1280, 720, 0, 1};
    LARGE_INTEGER f, t0, t1;
    QueryPerformanceFrequency(&f);
    double total = 0, worst = 0;
    for (int fr = 0; fr < frames + 10; fr++) {
        QueryPerformanceCounter(&t0);
        float clear[4] = {0.1f, 0.1f, 0.1f, 1};
        ID3D11DeviceContext_OMSetRenderTargets(ctx, 1, &rtv, NULL);
        ID3D11DeviceContext_RSSetViewports(ctx, 1, &vp);
        ID3D11DeviceContext_ClearRenderTargetView(ctx, rtv, clear);
        ID3D11DeviceContext_IASetPrimitiveTopology(ctx, D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);
        ID3D11DeviceContext_VSSetShader(ctx, vsh, NULL, 0);
        ID3D11DeviceContext_PSSetShader(ctx, psh, NULL, 0);
        ID3D11DeviceContext_VSSetConstantBuffers(ctx, 0, 1, &cb);
        ID3D11DeviceContext_PSSetSamplers(ctx, 0, 1, &smp);
        for (int d = 0; d < draws; d++) {
            D3D11_MAPPED_SUBRESOURCE m;
            ID3D11DeviceContext_Map(ctx, (ID3D11Resource *)cb, 0, D3D11_MAP_WRITE_DISCARD, 0, &m);
            float *p = m.pData;
            p[0] = -1 + 2.0f * (d % 40) / 40;
            p[1] = -1 + 2.0f * ((d / 40) % 40) / 40;
            p[4] = (d & 1) ? 1.0f : 0.5f;
            p[5] = (d & 2) ? 1.0f : 0.5f;
            p[6] = (d & 4) ? 1.0f : 0.5f;
            p[7] = 1;
            ID3D11DeviceContext_Unmap(ctx, (ID3D11Resource *)cb, 0);
            ID3D11DeviceContext_PSSetShaderResources(ctx, 0, 1, &srv[d & 7]);
            ID3D11DeviceContext_Draw(ctx, 3, 0);
        }
        if (fr % 30 == 29) {
            D3D11_BOX box = {0, 0, 0, 16, 16, 1};
            ID3D11DeviceContext_CopySubresourceRegion(ctx, (ID3D11Resource *)st, 0, 0, 0, 0, (ID3D11Resource *)rt, 0, &box);
            D3D11_MAPPED_SUBRESOURCE m;
            ID3D11DeviceContext_Map(ctx, (ID3D11Resource *)st, 0, D3D11_MAP_READ, 0, &m);
            ID3D11DeviceContext_Unmap(ctx, (ID3D11Resource *)st, 0);
        } else {
            ID3D11DeviceContext_Flush(ctx);
        }
        QueryPerformanceCounter(&t1);
        double ms = (double)(t1.QuadPart - t0.QuadPart) * 1000.0 / (double)f.QuadPart;
        if (fr >= 10) {
            total += ms;
            if (ms > worst) worst = ms;
        }
    }
    printf("%d draws/frame: %.2f ms/frame average (%.0f fps), worst %.2f ms\n", draws, total / frames, 1000.0 * frames / total, worst);
    return 0;
}
