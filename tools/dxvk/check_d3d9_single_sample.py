#!/usr/bin/env python3
"""Compile pinned DXVK's actual descriptor paths against a controlled backend.

This checks CPU control flow, not GPU rendering. No Wine/game/device is started.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile

CASES = ('forced_msaa_depth', 'default_msaa_depth', 'forced_nonmaskable',
         'default_nonmaskable', 'forced_already_single', 'forced_no_depth',
         'forced_unsupported', 'forced_invalid_type', 'forced_lockable_msaa',
         'forced_lockable_single', 'forced_allocation_failure',
         'default_allocation_failure', 'forced_recreate', 'ordinary_surface',
         'ordinary_invalid_lockable')


def function(text, signature):
    start = text.index(signature)
    opening = text.index('{', start)
    # Ignore braces in comments and strings while preserving source positions.
    stripped = re.sub(r'//[^\n]*|/\*.*?\*/|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'',
                      lambda m: ' ' * len(m.group()), text, flags=re.S)
    depth = 0
    for index in range(opening, len(stripped)):
        depth += (stripped[index] == '{') - (stripped[index] == '}')
        if depth == 0:
            return text[start:index + 1]
    raise ValueError('Unterminated source function: ' + signature)


def production(source):
    base = source / 'src/d3d9'
    common = (base / 'd3d9_common_texture.cpp').read_text()
    header = (base / 'd3d9_common_texture.h').read_text()
    swap = (base / 'd3d9_swapchain.cpp').read_text()
    device = (base / 'd3d9_device.cpp').read_text()
    util = (base / 'd3d9_util.cpp').read_text()
    pieces = {
        'decode': function(util, 'HRESULT DecodeMultiSampleType('),
        'normalize': function(common, 'HRESULT D3D9CommonTexture::NormalizeTextureProperties('),
        'backbuffers': function(swap, 'HRESULT D3D9SwapChainEx::CreateBackBuffers('),
        'uses_image': function(header, 'static bool TextureUsesImage('),
    }
    reset = function(device, 'HRESULT D3D9DeviceEx::ResetSwapChain(')
    begin = reset.index('D3D9_COMMON_TEXTURE_DESC desc;', reset.index('if (pPresentationParameters->EnableAutoDepthStencil)'))
    end = reset.index('m_autoDepthStencil =', begin)
    pieces['auto_depth'] = reset[begin:end].rstrip()
    framebuffer = function(device, 'void D3D9DeviceEx::BindFramebuffer(')
    begin = framebuffer.index('const bool mismatch =')
    end = framebuffer.index(';', framebuffer.index('const bool bindDS =', begin)) + 1
    pieces['depth_binding'] = framebuffer[begin:end]
    return pieces


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=Path, help='Pinned DXVK tree with downstream patches applied')
    parser.add_argument('--cxx', default=os.environ.get('CXX', 'c++'))
    parser.add_argument('--sanitize', action='store_true', help='Enable ASan/UBSan on the CPU harness')
    args = parser.parse_args()
    pieces = production(args.source)
    harness = (Path(__file__).parent / 'tests/d3d9_single_sample.cpp').read_text()
    harness = harness.replace('/* PRODUCTION_USES_IMAGE */', pieces['uses_image'])
    with tempfile.TemporaryDirectory(prefix='dxvk-d3d9-') as directory:
        out = Path(directory)
        (out / 'harness.cpp').write_text(harness)
        include = '\n\n'.join(pieces[key] for key in ('decode', 'normalize', 'backbuffers'))
        include += '\nHRESULT auto_depth(D3D9DeviceEx* device, D3DPRESENT_PARAMETERS* pPresentationParameters, D3D9_COMMON_TEXTURE_DESC* output) {\n'
        include += 'auto* self = device;\n' + pieces['auto_depth'].replace('(this,', '(self,')
        include += '\n*output = desc; return D3D_OK;\n}\n'
        include += '\nbool depth_binds(const ImageInfo& color, const ImageInfo& dsInfo) {\n'
        include += 'auto sampleCount = color.sampleCount; auto renderArea = color.extent;\n'
        include += 'const bool noRT0Bound = false, ignoreRT0 = false;\n'
        include += pieces['depth_binding'] + '\nreturn bindDS;\n}\n'
        (out / 'd3d9_production.inc').write_text(include)
        command = [args.cxx, '-std=c++17', '-Wall', '-Wextra', '-Werror',
                   '-Wno-unused-parameter', str(out / 'harness.cpp'), '-o', str(out / 'check')]
        # The original CreateBackBuffers does not use Flags; that known baseline
        # warning must not turn a negative control into a compiler-fixture error.
        if args.sanitize:
            command[1:1] = ['-fsanitize=address,undefined', '-fno-omit-frame-pointer']
        compilation = subprocess.run(command, capture_output=True, text=True)
        if compilation.returncode:
            raise SystemExit(compilation.stderr)
        results = []
        for case in CASES:
            process = subprocess.run([str(out / 'check'), case], capture_output=True,
                                     text=True, timeout=10, cwd=out,
                                     env={'ASAN_OPTIONS': 'detect_leaks=' +
                                          ('0' if sys.platform == 'darwin' else '1')})
            results.append({'case': case, 'passed': process.returncode == 0,
                            'exit': process.returncode, 'detail': process.stderr.strip()[-800:]})
    result = {'cases': results, 'passed': sum(row['passed'] for row in results),
              'failed': sum(not row['passed'] for row in results),
              'production_sha256': {key: hashlib.sha256(value.encode()).hexdigest()
                                    for key, value in pieces.items()},
              'backend_and_adapter_mocked': True, 'GPU_test': False,
              'sanitizers': args.sanitize,
              'leak_detection': args.sanitize and sys.platform != 'darwin'}
    print(json.dumps(result, indent=2))
    return bool(result['failed'])


if __name__ == '__main__':
    raise SystemExit(main())
