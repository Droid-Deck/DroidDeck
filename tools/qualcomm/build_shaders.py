"""Build the Gamescope shader workaround and its ARM64 glibc Vulkan layer."""
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import subprocess
import tarfile
import tempfile


def build(output, archive, lock, ndk):
    host = 'windows-x86_64' if os.name == 'nt' else 'darwin-x86_64' if os.uname().sysname == 'Darwin' else 'linux-x86_64'
    suffix = '.exe' if os.name == 'nt' else ''
    toolchain = ndk / 'toolchains/llvm/prebuilt' / host
    compiler = toolchain / ('bin/clang' + suffix)
    shader_compiler = ndk / 'shader-tools' / host / ('glslc' + suffix)
    here = Path(__file__).resolve().parent
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='qualcomm-shaders-') as temporary:
        source = Path(temporary)
        with tarfile.open(archive, 'r:gz') as package:
            for member in package:
                path = PurePosixPath(member.name)
                relative = PurePosixPath(*path.parts[1:])
                if not member.isfile() or '..' in relative.parts:
                    continue
                if not str(relative).startswith('src/shaders/') and str(relative) != 'LICENSE':
                    continue
                target = source / str(relative)
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(package.extractfile(member).read())
        patch = here / 'constant-yuv-samplers.patch'
        subprocess.run(['git', 'apply', '--check', str(patch)], cwd=source, check=True)
        subprocess.run(['git', 'apply', str(patch)], cwd=source, check=True)
        for fingerprint, name in lock['replacements'].items():
            subprocess.run([str(shader_compiler), str(source / 'src/shaders' / (name + '.comp')),
                            '-o', str(output / (fingerprint + '.spv'))], check=True)
        licenses = output / 'licenses'
        licenses.mkdir(exist_ok=True)
        shutil.copyfile(source / 'LICENSE', licenses / 'Gamescope-LICENSE')
        shutil.copyfile(archive, licenses / 'gamescope-source.tar.gz')
        shutil.copyfile(patch, licenses / patch.name)
    # Only Vulkan types and opaque libc pointers cross this glibc ABI boundary.
    subprocess.run([str(compiler), '--target=aarch64-linux-gnu', '--sysroot=' + str(toolchain / 'sysroot'),
                    '-I' + str(toolchain / 'sysroot/usr/include/aarch64-linux-android'),
                    '-fuse-ld=lld', '-nostdlib', '-shared', '-fPIC', '-O2', '-fno-stack-protector',
                    '-Wl,-z,max-page-size=16384', '-Wall', '-Wextra', '-Werror',
                    str(here / 'shader_layer.c'), '-o', str(output / 'qualcomm-shaders.so')], check=True)
    manifest = dict(file_format_version='1.2.0', layer=dict(
        name='VK_LAYER_DROIDDECK_qualcomm_shaders', type='GLOBAL', library_path='./qualcomm-shaders.so',
        api_version='1.4.0', implementation_version='1', description='Gamescope constant YUV sampler indices for Qualcomm'))
    (output / 'VkLayer_DROIDDECK_qualcomm_shaders.json').write_text(json.dumps(manifest) + '\n', encoding='utf-8')
