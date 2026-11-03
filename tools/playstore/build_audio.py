import argparse
import hashlib
import io
import json
import os
import re
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile


ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from tools.playstore.payloads import fetch, sha256
from tools.release.check_android_libraries import page_alignment


LIBRARIES = ['libltdl.so', 'libpulseaudio.so', 'libpulse.so', 'libpulsecommon-13.0.so',
             'libpulsecore-13.0.so', 'libsndfile.so']
SOURCES = Path(__file__).with_name('audio-sources.json')


def identity(ndk):
    paths = [Path(__file__), SOURCES, *sorted((ROOT / 'tools/aaudio-sink').glob('*')),
             *sorted((ROOT / 'tools/directaudio/sink').glob('*')),
             *[ROOT / 'app/src/main/jniLibs/arm64-v8a' / name for name in LIBRARIES]]
    entries = {str(path.relative_to(ROOT)): sha256(path) for path in paths if path.is_file()}
    entries['ndk'] = sha256(ndk / 'source.properties')
    return hashlib.sha256(json.dumps(entries, sort_keys=True).encode()).hexdigest()


def verify(output, ndk):
    manifest = json.loads((output / 'complete.json').read_text())
    if manifest.get('identity') != identity(ndk):
        raise ValueError('Play audio sources or toolchain changed')
    for name, digest in manifest['outputs'].items():
        path = output / name
        if path.is_symlink() or not path.is_file() or sha256(path) != digest:
            raise ValueError('Play audio output failed verification: ' + name)
    if set(LIBRARIES) != {Path(name).name for name in manifest['outputs'] if name.startswith('jni/arm64-v8a/')}:
        raise ValueError('Play audio native inventory is incomplete')
    if 'assets/pulseaudio.tzst' not in manifest['outputs']:
        raise ValueError('Play audio modules are missing')
    return manifest


def extract(source, destination):
    destination.mkdir(parents=True)
    with tarfile.open(source, 'r:*') as archive:
        archive.extractall(destination, filter='data')
    roots = list(destination.iterdir())
    if len(roots) != 1 or not roots[0].is_dir():
        raise ValueError('Unexpected audio source archive layout')
    return roots[0]


def build(output, cache, ndk):
    if output.exists():
        return verify(output, ndk)
    output.mkdir(parents=True)
    work = output / 'work'
    work.mkdir()
    definitions = json.loads(SOURCES.read_text())
    sources = {}
    for name, definition in definitions.items():
        archive = fetch(dict(definition, id=name, urls=[definition['url']]), cache)
        sources[name] = extract(archive, work / name)
        notices = output / 'assets/licenses/play-audio/sources'
        notices.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(archive, notices / (name + ('.tar.xz' if name == 'libtool' else '.tar.gz')))
    prefix = work / 'prefix'
    prefix.mkdir()
    toolchain = ndk / 'toolchains/llvm/prebuilt/linux-x86_64/bin'
    environment = dict(os.environ)
    environment.update({name: str(toolchain / value) for name, value in {
        'CC': 'aarch64-linux-android26-clang', 'CXX': 'aarch64-linux-android26-clang++',
        'AR': 'llvm-ar', 'NM': 'llvm-nm', 'RANLIB': 'llvm-ranlib', 'STRIP': 'llvm-strip',
    }.items()})
    environment.update(CFLAGS=f'-O2 -fPIC -Wno-error=int-conversion -I{prefix}/include',
                       LDFLAGS=f'-Wl,-z,max-page-size=16384 -L{prefix}/lib',
                       PKG_CONFIG_PATH=str(prefix / 'lib/pkgconfig'), PKG_CONFIG_LIBDIR=str(prefix / 'lib/pkgconfig'),
                       ac_cv_func_mkfifo='yes', ac_cv_func_getuid='no', ac_cv_func_seteuid='no',
                       ax_cv_PTHREAD_PRIO_INHERIT='no', ac_cv_header_glob_h='no', ac_cv_header_execinfo_h='no',
                       ac_cv_func_malloc_0_nonnull='yes', ac_cv_func_realloc_0_nonnull='yes',
                       ac_cv_lib_ltdl_lt_dladvise_init='yes')
    environment['CXXFLAGS'] = environment['CFLAGS']

    def run(arguments, directory=work):
        subprocess.run([str(value) for value in arguments], cwd=directory, env=environment, check=True)

    libtool = work / 'libtool-build'
    libtool.mkdir()
    run([sources['libtool'] / 'configure', '--host=aarch64-linux-android', '--prefix=' + str(prefix),
         'HELP2MAN=/bin/true', 'MAKEINFO=/bin/true'], libtool)
    run(['make', '-j8'], libtool)
    run(['make', 'install'], libtool)
    sndfile = work / 'sndfile-build'
    run(['cmake', '-S', sources['libsndfile'], '-B', sndfile, '-G', 'Ninja',
         '-DCMAKE_TOOLCHAIN_FILE=' + str(ndk / 'build/cmake/android.toolchain.cmake'),
         '-DANDROID_ABI=arm64-v8a', '-DANDROID_PLATFORM=android-26', '-DCMAKE_BUILD_TYPE=Release',
         '-DBUILD_SHARED_LIBS=ON', '-DBUILD_PROGRAMS=OFF', '-DBUILD_EXAMPLES=OFF', '-DBUILD_TESTING=OFF',
         '-DENABLE_EXTERNAL_LIBS=OFF', '-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384',
         '-DCMAKE_INSTALL_PREFIX=' + str(prefix)])
    run(['cmake', '--build', sndfile, '-j8'])
    run(['cmake', '--install', sndfile])
    pulse = sources['pulseaudio']
    (pulse / '.tarball-version').write_text('13.0\n')
    run(['autoreconf', '--install', '--force'], pulse)
    pulse_build = work / 'pulse-build'
    pulse_build.mkdir()
    disabled = ['rpath', 'nls', 'x11', 'oss-wrapper', 'alsa', 'esound', 'waveout', 'glib2', 'gtk3', 'gconf',
                'avahi', 'jack', 'asyncns', 'tcpwrap', 'lirc', 'dbus', 'bluez5', 'udev', 'openssl', 'manpages',
                'samplerate', 'orc', 'systemd-daemon', 'systemd-login', 'systemd-journal', 'webrtc-aec',
                'tests', 'neon-opt', 'gsettings']
    run([pulse / 'configure', '--host=aarch64-linux-android', '--prefix=' + str(prefix), '--disable-static',
         '--enable-shared', '--with-database=simple', '--without-speex', '--without-caps', '--without-fftw',
         *['--disable-' + name for name in disabled]], pulse_build)
    run(['make', '-j8'], pulse_build)
    run(['make', 'install'], pulse_build)
    jni = output / 'jni/arm64-v8a'
    jni.mkdir(parents=True)
    for name in LIBRARIES:
        source = prefix / ('bin/pulseaudio' if name == 'libpulseaudio.so' else
                           'lib/pulseaudio/' + name if name.startswith(('libpulsecommon-', 'libpulsecore-')) else 'lib/' + name)
        shutil.copyfile(source, jni / name)
    modules = work / 'android-modules'
    environment.update(NDK=str(ndk), DROIDDECK_PULSE_LIBS=str(jni))
    run(['bash', ROOT / 'tools/aaudio-sink/build.sh', pulse, modules], ROOT)
    run([environment['CC'], '-O2', '-shared', '-fPIC', '-Wno-error=int-conversion',
         '-Wl,-z,max-page-size=16384', '-I' + str(pulse_build), '-I' + str(pulse / 'src'),
         '-I' + str(prefix / 'include'), '-L' + str(jni), '-lpulsecore-13.0', '-lpulsecommon-13.0', '-lpulse',
         '-laaudio', '-o', modules / 'module-aaudio-classic-sink.so',
         sources['android-module'] / 'pulseaudio-module/module-aaudio-sink.c'])
    replacements = {'modules/arm64/' + name: prefix / 'lib/pulse-13.0/modules' / name for name in
                    ['module-pipe-source.so', 'module-pipe-sink.so', 'libprotocol-native.so', 'module-native-protocol-unix.so']}
    replacements.update({'modules/arm64/' + name: modules / name for name in
                         ['module-aaudio-sink.so', 'module-directaudio-native-sink.so', 'module-aaudio-classic-sink.so']})
    replacements['pactl'] = prefix / 'bin/pactl'
    for path in [*jni.glob('*.so'), *replacements.values()]:
        errors = page_alignment(path, str(toolchain / 'llvm-readelf'), 16384)
        if errors:
            raise ValueError('\n'.join(errors))
    def exports(path):
        symbols = subprocess.check_output([str(toolchain / 'llvm-nm'), '-D', '--defined-only', str(path)], text=True)
        return {line.split()[-1].split('@')[0] for line in symbols.splitlines() if line.split()}

    for name in LIBRARIES:
        old = ROOT / 'app/src/main/jniLibs/arm64-v8a' / name
        if exports(old) - exports(jni / name):
            raise ValueError('Play audio library dropped existing exports: ' + name)
        original_dynamic = subprocess.check_output([str(toolchain / 'llvm-readelf'), '-d', str(old)], text=True)
        rebuilt_dynamic = subprocess.check_output([str(toolchain / 'llvm-readelf'), '-d', str(jni / name)], text=True)
        if re.findall(r'SONAME.*\[(.*?)\]', original_dynamic) != re.findall(r'SONAME.*\[(.*?)\]', rebuilt_dynamic):
            raise ValueError('Play audio library changed its load identity: ' + name)
    original = ROOT / 'app/src/main/assets/pulseaudio.tzst'
    process = subprocess.Popen(['zstd', '-dc', str(original)], stdout=subprocess.PIPE)
    seen = set()
    plain = work / 'pulseaudio.tar'
    with tarfile.open(fileobj=process.stdout, mode='r|') as source, tarfile.open(plain, 'w') as target:
        for member in source:
            name = member.name.removeprefix('./')
            stream = source.extractfile(member) if member.isfile() else None
            if name in replacements:
                data = replacements[name].read_bytes()
                member.size = len(data)
                stream = io.BytesIO(data)
                seen.add(name)
            target.addfile(member, stream)
        for name in sorted(set(replacements) - seen):
            data = replacements[name].read_bytes()
            member = tarfile.TarInfo(name)
            member.size, member.mode = len(data), 0o755
            target.addfile(member, io.BytesIO(data))
    process.stdout.close()
    if process.wait():
        raise ValueError('Original audio archive failed to decode')
    with (output / 'assets/pulseaudio.tzst').open('xb') as destination:
        subprocess.run(['zstd', '-19', '-c', str(plain)], stdout=destination, check=True)
    files = [path for tree in ['jni', 'assets'] for path in (output / tree).rglob('*') if path.is_file()]
    manifest = {'identity': identity(ndk), 'sources': definitions,
                'outputs': {path.relative_to(output).as_posix(): sha256(path) for path in files}}
    (output / 'complete.json').write_text(json.dumps(manifest, indent=2, sort_keys=True) + '\n')
    return verify(output, ndk)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('command', choices=['build', 'verify'])
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--ndk', type=Path, required=True)
    parser.add_argument('--cache', type=Path)
    arguments = parser.parse_args()
    if arguments.command == 'build':
        if arguments.cache is None:
            parser.error('build requires --cache')
        manifest = build(arguments.output.resolve(), arguments.cache.resolve(), arguments.ndk.resolve())
    else:
        manifest = verify(arguments.output.resolve(), arguments.ndk.resolve())
    print(json.dumps({'verified_outputs': len(manifest['outputs']), 'identity': manifest['identity']}))


if __name__ == '__main__':
    main()
