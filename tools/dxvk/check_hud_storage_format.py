#!/usr/bin/env python3
"""Compile the actual HUD shader and verify its binding-3 R8_UINT contract.

The frame-time HUD binds an R8_UINT texel-buffer view. An Unknown SPIR-V image
format requires STORAGE_WRITE_WITHOUT_FORMAT, which PowerVR does not report
for that buffer format. Static spirv-val alone accepts both declarations;
this check validates the shader against the known host-side binding contract.
"""
import argparse
from pathlib import Path
import shutil
import struct
import subprocess
import tempfile


def check(shader):
    compiler = shutil.which('glslangValidator')
    validator = shutil.which('spirv-val')
    if not compiler or not validator:
        raise RuntimeError('glslangValidator and spirv-val required')
    with tempfile.TemporaryDirectory(prefix='dxvk-hud-contract-') as directory:
        output = Path(directory) / 'hud.spv'
        for command in ([compiler, '-V', '--target-env', 'vulkan1.3', str(shader), '-o', str(output)],
                        [validator, '--target-env', 'vulkan1.3', str(output)]):
            process = subprocess.run(command, capture_output=True, text=True, timeout=30)
            if process.returncode:
                raise RuntimeError('HUD shader compilation/SPIR-V validation failed')
        raw = output.read_bytes()
    if len(raw) < 20 or len(raw) % 4:
        raise RuntimeError('Malformed SPIR-V')
    words = struct.unpack('<' + str(len(raw) // 4) + 'I', raw)
    if words[0] != 0x07230203:
        raise RuntimeError('Unexpected SPIR-V magic')
    bindings, sets, variables, pointers, images = {}, {}, {}, {}, {}
    offset = 5
    while offset < len(words):
        size, opcode = words[offset] >> 16, words[offset] & 0xffff
        if not size or size > len(words) - offset:
            raise RuntimeError('Malformed SPIR-V instruction')
        instruction = words[offset:offset + size]
        if opcode == 71 and size == 4:  # OpDecorate
            if instruction[2] == 33:
                bindings[instruction[1]] = instruction[3]
            elif instruction[2] == 34:
                sets[instruction[1]] = instruction[3]
        elif opcode == 59 and size >= 4:  # OpVariable
            variables[instruction[2]] = instruction[1]
        elif opcode == 32 and size == 4:  # OpTypePointer
            pointers[instruction[1]] = instruction[3]
        elif opcode == 25 and size >= 9:  # OpTypeImage
            images[instruction[1]] = (instruction[3], instruction[7], instruction[8])
        offset += size
    targets = [images.get(pointers.get(variables.get(identifier)))
               for identifier, binding in bindings.items()
               if binding == 3 and sets.get(identifier) == 0]
    if targets != [(5, 2, 39)]:  # DimBuffer, storage image, ImageFormatR8ui
        raise RuntimeError('HUD binding 3 must compile to a storage texel buffer with ImageFormatR8ui')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('shader', type=Path)
    args = parser.parse_args()
    try:
        check(args.shader)
    except RuntimeError as error:
        parser.exit(1, str(error) + '\n')
    print('HUD binding 3: SPIR-V valid, explicit R8ui storage texel-buffer format')


if __name__ == '__main__':
    main()
