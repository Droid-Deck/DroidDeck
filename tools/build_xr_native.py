#!/usr/bin/env python3
"""Build the small diagnostics JNI libraries and optionally compile/link the production XR backend."""
import argparse
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]

def run(command):
    print(' '.join(map(str, command)), flush=True)
    subprocess.run(list(map(str, command)), check=True)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', type=Path, default=os.environ.get('ANDROID_HOME', os.environ.get('ANDROID_SDK_ROOT')))
    parser.add_argument('--abis', default='arm64-v8a,x86_64')
    parser.add_argument('--release', action='store_true')
    parser.add_argument('--openxr-include', type=Path, help='Directory containing OpenXR 1.1.49 openxr/openxr.h')
    parser.add_argument('--openxr-loader', type=Path, help='Real ARM64 libopenxr_loader.so, required for production linking')
    args = parser.parse_args()
    if not args.sdk:
        parser.error('set ANDROID_HOME or pass --sdk')
    sdk = args.sdk.resolve()
    ndk = sdk / 'ndk/27.3.13750724'
    suffix = '.exe' if os.name == 'nt' else ''
    cmake = sdk / 'cmake/3.22.1/bin' / ('cmake' + suffix)
    ninja = cmake.with_name('ninja' + suffix)
    if not cmake.exists() or not ndk.is_dir():
        parser.error('install SDK CMake 3.22.1 and NDK 27.3.13750724')
    for abi in args.abis.split(','):
        if abi not in ('arm64-v8a', 'x86_64'):
            parser.error('supported JNI ABIs: arm64-v8a,x86_64')
        build = ROOT / 'build' / ('androidxr-jni-' + abi + ('-release' if args.release else ''))
        run([cmake, '-S', ROOT / 'tools/androidxr-diagnostics/app/src/main/cpp', '-B', build,
             '-G', 'Ninja', f'-DCMAKE_MAKE_PROGRAM={ninja}', f'-DCMAKE_TOOLCHAIN_FILE={ndk}/build/cmake/android.toolchain.cmake',
             f'-DANDROID_ABI={abi}', '-DANDROID_PLATFORM=android-34', '-DANDROID_STL=c++_static',
             '-DCMAKE_BUILD_TYPE=' + ('Release' if args.release else 'Debug'),
             '-DDROIDDECK_XR_FIXTURES=' + ('OFF' if args.release else 'ON')])
        run([cmake, '--build', build])
    if args.openxr_include:
        include = args.openxr_include.resolve()
        if not (include / 'openxr/openxr.h').exists():
            parser.error('--openxr-include must contain openxr/openxr.h')
        host = next((ndk / 'toolchains/llvm/prebuilt').iterdir())
        compiler = host / 'bin' / ('clang++' + suffix)
        ccompiler = host / 'bin' / ('clang' + suffix)
        build = ROOT / 'build/androidxr-production'
        build.mkdir(parents=True, exist_ok=True)
        glue = ndk / 'sources/android/native_app_glue'
        flags = ['--target=aarch64-linux-android26', '-std=c++17', '-fPIC', '-Wall', '-Wextra',
                 '-Wno-missing-field-initializers', '-Werror=return-type', '-Werror=format',
                 '-DXR_USE_PLATFORM_ANDROID', '-DXR_USE_GRAPHICS_API_OPENGL_ES', '-DXR_USE_TIMESPEC',
                 '-I' + str(include), '-I' + str(ROOT / 'shared/xr'), '-I' + str(ROOT / 'tools/vr/src'), '-I' + str(glue)]
        if args.release:
            flags += ['-O2', '-DNDEBUG']
        else:
            flags += ['-DDROIDDECK_XR_NATIVE_PROBE']
        run([compiler, *flags, '-c', ROOT / 'app/src/main/cpp/questxr/quest_xr.cpp', '-o', build / 'quest_xr.o'])
        if args.openxr_loader:
            run([ccompiler, '--target=aarch64-linux-android26', '-fPIC', '-c', glue / 'android_native_app_glue.c', '-o', build / 'glue.o'])
            run([compiler, '--target=aarch64-linux-android26', '-shared', '-Wl,-z,defs', '-Wl,-z,max-page-size=16384',
                 '-uANativeActivity_onCreate', build / 'quest_xr.o', build / 'glue.o', args.openxr_loader.resolve(),
                 '-lEGL', '-lGLESv3', '-landroid', '-llog', '-o', build / 'libdroiddeckxr.so'])
        else:
            print('Production source compiled. Linking NOT attempted: supply --openxr-loader (ARM64 real loader).')

if __name__ == '__main__':
    try:
        main()
    except (OSError, subprocess.SubprocessError) as error:
        print(error, file=sys.stderr)
        sys.exit(1)
