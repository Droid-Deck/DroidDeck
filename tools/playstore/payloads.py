import argparse
import hashlib
import json
import os
import re
import ssl
import sys
import urllib.parse
import urllib.request
from pathlib import Path
import zipfile


ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))


CHUNK_BYTES = 64 * 1024 * 1024
MODULE_BYTES = 448 * 1024 * 1024
TOTAL_BYTES = 4_000_000_000
RESERVED_BYTES = 158_000_000


def sha256(path):
    result = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            result.update(block)
    return result.hexdigest()


def https_url(value):
    if not isinstance(value, str):
        raise ValueError('URL must be a string')
    parsed = urllib.parse.urlsplit(value)
    if parsed.scheme != 'https' or not parsed.hostname or parsed.username or parsed.password or parsed.fragment:
        raise ValueError('Expected a public HTTPS URL: ' + value)
    return value


def catalog_bytes(content):
    if not isinstance(content, (dict, list)):
        raise ValueError('Frozen catalog must be a JSON object or array')
    data = (json.dumps(content, sort_keys=True, separators=(',', ':'), ensure_ascii=False) + '\n').encode('utf-8')
    if len(data) > 16 * 1024 * 1024:
        raise ValueError('Frozen catalog exceeds its size limit')
    return data


def load_manifest(path):
    manifest = json.loads(path.read_text())
    if manifest.get('schema') != 1:
        raise ValueError('Unsupported payload manifest schema')
    entries = manifest.get('entries')
    if not isinstance(entries, list) or not entries:
        raise ValueError('Payload manifest is empty')
    urls = set()
    identities = {}
    data_identities = {}
    ids = set()
    for entry in entries:
        identity = entry.get('id', '')
        if not re.fullmatch(r'[a-z0-9][a-z0-9._-]{0,127}', identity) or identity in ids:
            raise ValueError('Invalid or duplicate payload id: ' + identity)
        ids.add(identity)
        digest = entry.get('sha256', '')
        if not re.fullmatch(r'[a-f0-9]{64}', digest):
            raise ValueError('Payload requires a SHA-256: ' + identity)
        size = entry.get('size')
        if type(size) is not int or size < 1:
            raise ValueError('Payload requires a positive byte size: ' + identity)
        if digest in identities and identities[digest] != size:
            raise ValueError('Inconsistent size for identical payload: ' + identity)
        delivery = entry.get('delivery', 'bundled')
        if delivery not in {'bundled', 'windows-guest'}:
            raise ValueError('Unsupported component delivery: ' + identity)
        if delivery == 'windows-guest':
            if not identity.startswith('windows-') or manifest.get('windows_guest_downloads') is not True or not entry.get('provides') or any(
                    not feature.startswith('windows:') for feature in entry['provides']):
                raise ValueError('External delivery is restricted to approved Windows prerequisites')
            https_url(entry.get('download_url', ''))
            if 'content' in entry or entry.get('prepare'):
                raise ValueError('External Windows prerequisites cannot replace bundled catalogs')
        else:
            layout = entry.get('layout')
            if layout is None:
                identities[digest] = size
            else:
                parts = layout.get('segments', [])
                if len(parts) != 2 or [part.get('kind') for part in parts] != ['code', 'data'] or sum(part.get('size', 0) for part in parts) != size:
                    raise ValueError('Invalid split component layout: ' + identity)
                if not re.fullmatch(r'[a-f0-9]{64}', layout.get('inventory_sha256', '')) or layout.get('zstd_version') != '1.5.5':
                    raise ValueError('Split component inventory is unpinned')
                source = layout.get('source', {})
                if not re.fullmatch(r'[a-f0-9]{64}', source.get('sha256', '')) or type(source.get('size')) is not int or source['size'] <= 0 or 'layout' in source:
                    raise ValueError('Split component source is unpinned')
                for url in source.get('fetch_urls', source.get('urls', [])):
                    https_url(url)
                for part in parts:
                    if not re.fullmatch(r'[a-f0-9]{64}', part.get('sha256', '')) or type(part.get('size')) is not int or part['size'] <= 0:
                        raise ValueError('Split component segment is unpinned')
                    (identities if part['kind'] == 'code' else data_identities)[part['sha256']] = part['size']
        if 'storage_chunk_bytes' in entry and (type(entry['storage_chunk_bytes']) is not int or
                not 65536 <= entry['storage_chunk_bytes'] <= CHUNK_BYTES):
            raise ValueError('Invalid component storage chunk size: ' + identity)
        aliases = entry.get('urls')
        if not isinstance(aliases, list) or not aliases:
            raise ValueError('Payload requires URL aliases: ' + identity)
        if 'fetch_urls' in entry:
            if not isinstance(entry['fetch_urls'], list) or not entry['fetch_urls']:
                raise ValueError('Payload fetch sources must be a nonempty list: ' + identity)
            for url in entry['fetch_urls']:
                https_url(url)
        for url in aliases:
            if url.startswith('droiddeck-bundle://'):
                if not re.fullmatch(r'droiddeck-bundle://[a-z0-9][a-z0-9._-]*/[A-Za-z0-9._+-]+', url) or not (entry.get('prepare') or entry.get('layout')):
                    raise ValueError('Invalid prepared component alias: ' + identity)
            else:
                https_url(url)
            if url in urls:
                raise ValueError('Duplicate URL alias: ' + url)
            urls.add(url)
        preparation = entry.get('prepare')
        if 'content' in entry:
            data = catalog_bytes(entry['content'])
            if preparation is not None or 'upstream' in entry or len(data) != size or hashlib.sha256(data).hexdigest() != digest:
                raise ValueError('Frozen catalog identity mismatch: ' + identity)
        if preparation is None and 'upstream' in entry:
            raise ValueError('Upstream archive requires explicit component preparation: ' + identity)
        if preparation is not None:
            if preparation.get('method') not in {'gzip-to-zstd-19', 'zstd-to-zstd-19'} or not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+', preparation.get('zstd_version', '')):
                raise ValueError('Invalid component preparation method: ' + identity)
            if not re.fullmatch(r'[a-f0-9]{64}', preparation.get('tar_sha256', '')) or type(preparation.get('tar_size')) is not int or not 0 < preparation['tar_size'] <= 16 * 1024 * 1024 * 1024:
                raise ValueError('Prepared component requires a pinned tar identity: ' + identity)
            upstream = entry.get('upstream', {})
            if not re.fullmatch(r'[a-f0-9]{64}', upstream.get('sha256', '')) or type(upstream.get('size')) is not int or upstream['size'] < 1:
                raise ValueError('Prepared component requires a pinned upstream archive: ' + identity)
            if not isinstance(upstream.get('urls'), list) or not upstream['urls']:
                raise ValueError('Prepared component requires upstream URLs: ' + identity)
            for url in upstream['urls']:
                https_url(url)
        https_url(entry.get('source', ''))
        if not re.fullmatch(r'[a-f0-9]{40}', entry.get('source_commit', '')):
            raise ValueError('Payload requires a pinned source commit: ' + identity)
        license_info = entry.get('license', {})
        if not license_info.get('expression') or not license_info.get('redistribution_review'):
            raise ValueError('Payload requires license and redistribution review: ' + identity)
        https_url(license_info.get('url', ''))
    required = manifest.get('required')
    if not isinstance(required, list) or not required or len(set(required)) != len(required):
        raise ValueError('Manifest requires a unique component coverage list')
    provided = {feature for entry in entries for feature in entry.get('provides', [])}
    missing = set(required) - provided
    if missing:
        raise ValueError('Missing component coverage: ' + ', '.join(sorted(missing)))
    if sum(data_identities.values()) > 30_000_000_000:
        raise ValueError('Play data asset budget exceeded')
    total = sum(identities.values())
    if total + RESERVED_BYTES > TOTAL_BYTES:
        raise ValueError(f'Payload size budget exceeded: {total} + {RESERVED_BYTES} reserved > {TOTAL_BYTES}')
    return manifest


def validate_play_engines(manifest):
    if manifest.get('release_ready') is False:
        raise ValueError('Review-only component inventory cannot be published')
    pending = [entry['id'] for entry in manifest['entries'] if entry.get('license', {}).get('approved') is not True]
    if pending:
        raise ValueError('Component redistribution review is incomplete: ' + ', '.join(pending))
    if 'proton:droiddeck' not in manifest['required']:
        raise ValueError('Play distribution requires DroidDeck Proton')
    for entry in manifest['entries']:
        if any(feature.startswith('emulator:') for feature in entry.get('provides', [])):
            raise ValueError('Extra desktop emulators are excluded from the Play distribution')
        engines = {name for name in entry.get('provides', []) if name.startswith('proton:')}
        if engines - {'proton:droiddeck'}:
            raise ValueError('Play distribution permits only DroidDeck Proton')
        original = entry.get('layout', {}).get('source', entry)
        source_urls = original.get('upstream', {}).get('urls', original['urls'])
        for url in [*entry['urls'], *source_urls]:
            lower = url.lower()
            parsed = urllib.parse.urlsplit(url)
            if '/steamdeck-desktop-' in parsed.path and parsed.path.rsplit('/', 1)[-1] != 'desktop.tar.zst':
                raise ValueError('Extra desktop emulator payload is forbidden in the Play distribution: ' + entry['id'])
            if any(name in lower for name in ['/proton-ge-custom/', '/proton-cachyos/', '/steam-seed.json']):
                raise ValueError('Alternate Proton input is forbidden in the Play distribution: ' + entry['id'])
        if engines and not any('/Droid-Deck/DroidDeck-Proton/' in url for url in source_urls):
            raise ValueError('DroidDeck Proton must come from its pinned release')


class HttpsRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, file, code, message, headers, url):
        https_url(url)
        return super().redirect_request(request, file, code, message, headers, url)


def fetch(entry, cache):
    cache.mkdir(parents=True, exist_ok=True)
    target = cache / entry['sha256']
    if target.exists():
        if target.is_symlink() or target.stat().st_size != entry['size'] or sha256(target) != entry['sha256']:
            raise ValueError('Existing cache payload failed verification: ' + entry['id'])
        return target
    temporary = cache / (entry['sha256'] + '.part')
    if temporary.exists():
        raise ValueError('Incomplete payload exists; preserve it and select a fresh cache: ' + str(temporary))
    if 'content' in entry:
        data = catalog_bytes(entry['content'])
        if len(data) != entry['size'] or hashlib.sha256(data).hexdigest() != entry['sha256']:
            raise ValueError('Frozen catalog identity mismatch: ' + entry['id'])
        with temporary.open('xb') as output:
            output.write(data)
        temporary.rename(target)
        return target
    if entry.get('layout'):
        from tools.playstore.split_archive import prepare, combine, inventory
        layout = entry['layout']
        original = fetch(layout['source'], cache)
        code, data = [cache / (entry['sha256'] + '.' + name + '.part') for name in ['code', 'data']]
        print('Rebuilding split archive: ' + entry['id'], file=sys.stderr, flush=True)
        prepared = prepare(original, code, data, layout['zstd_version'])
        if any(prepared[key] != layout[key] for key in ['segments', 'inventory_sha256', 'zstd_version']):
            raise ValueError('Split archive identity changed')
        combine([code, data], temporary)
        if temporary.stat().st_size != entry['size'] or sha256(temporary) != entry['sha256'] or inventory(temporary, os.environ.get('DROIDDECK_ZSTD', 'zstd'))[0] != layout['inventory_sha256']:
            raise ValueError('Split archive failed reconstruction verification')
        temporary.rename(target)
        for source, segment in zip([code, data], layout['segments']):
            destination = cache / segment['sha256']
            if not destination.exists():
                source.rename(destination)
        return target
    if entry.get('prepare'):
        from tools.playstore.prepare_archive import prepare
        upstream = fetch(dict(entry['upstream'], id='upstream-' + entry['id']), cache)
        prepare(upstream, temporary, entry['prepare'])
        if temporary.stat().st_size != entry['size'] or sha256(temporary) != entry['sha256']:
            raise ValueError('Prepared payload digest or size mismatch: ' + entry['id'])
        temporary.rename(target)
        return target
    opener = urllib.request.build_opener(HttpsRedirects(), urllib.request.HTTPSHandler(context=ssl.create_default_context()))
    request = urllib.request.Request(entry.get('fetch_urls', entry['urls'])[0], headers={'Accept-Encoding': 'identity'})
    digest = hashlib.sha256()
    size = 0
    with opener.open(request, timeout=60) as response, temporary.open('xb') as output:
        https_url(response.url)
        for block in iter(lambda: response.read(1024 * 1024), b''):
            size += len(block)
            if size > entry['size']:
                raise ValueError('Payload exceeds pinned size: ' + entry['id'])
            digest.update(block)
            output.write(block)
    if size != entry['size'] or digest.hexdigest() != entry['sha256']:
        raise ValueError('Payload digest or size mismatch: ' + entry['id'])
    temporary.rename(target)
    return target


def stage(manifest, cache, output, chunk_bytes=CHUNK_BYTES, module_bytes=MODULE_BYTES):
    if chunk_bytes < 1 or module_bytes < chunk_bytes or module_bytes > MODULE_BYTES:
        raise ValueError('Invalid chunk or module size')
    if output.exists():
        raise ValueError('Output must be a new directory: ' + str(output))
    payloads = {}
    data_payloads = set()
    layouts = {}
    for entry in manifest['entries']:
        if entry.get('delivery') == 'windows-guest':
            continue
        if entry['sha256'] in layouts:
            continue
        print('Preparing Play component: ' + entry['id'], file=sys.stderr, flush=True)
        original = fetch(entry, cache)
        if entry.get('layout'):
            parts = entry['layout']['segments']
            with original.open('rb') as source:
                for part in parts:
                    destination = cache / part['sha256']
                    if not destination.exists():
                        with destination.open('xb') as target:
                            remaining = part['size']
                            while remaining:
                                block = source.read(min(1024 * 1024, remaining))
                                if not block:
                                    raise ValueError('Truncated split archive')
                                target.write(block)
                                remaining -= len(block)
                    else:
                        source.seek(part['size'], 1)
                    if destination.stat().st_size != part['size'] or sha256(destination) != part['sha256']:
                        raise ValueError('Split archive segment failed verification')
                    payloads[part['sha256']] = destination
                    if part['kind'] == 'data':
                        from tools.playstore.split_archive import validate_data
                        validate_data(destination)
                        data_payloads.add(part['sha256'])
            layouts[entry['sha256']] = [part['sha256'] for part in parts]
        else:
            payloads[entry['sha256']] = original
            layouts[entry['sha256']] = [entry['sha256']]
    output.mkdir(parents=True)
    index = {'schema': 1, 'manifest_sha256': hashlib.sha256(
        json.dumps(manifest, sort_keys=True, separators=(',', ':')).encode()).hexdigest(), 'entries': {}}
    module = 1
    used = 0
    chunks = {}
    shared = {}
    data_module = 1
    data_used = 0
    for digest, path in sorted(payloads.items()):
        pieces = []
        block_bytes = min([chunk_bytes] + [entry.get('storage_chunk_bytes', chunk_bytes) for entry in manifest['entries']
                                         if entry['sha256'] == digest and entry.get('delivery') != 'windows-guest'])
        with path.open('rb') as stream:
            number = 0
            for block in iter(lambda: stream.read(block_bytes), b''):
                chunk_digest = hashlib.sha256(block).hexdigest()
                shared_key = (digest in data_payloads, chunk_digest)
                if shared_key in shared:
                    pieces.append(shared[shared_key])
                    number += 1
                    continue
                if digest in data_payloads:
                    if data_used + len(block) > 1_400_000_000:
                        data_module += 1
                        data_used = 0
                    name = f'playdata{data_module:02d}'
                    data_used += len(block)
                else:
                    if used + len(block) > module_bytes:
                        module += 1
                        used = 0
                    name = f'playpayload{module:02d}'
                    used += len(block)
                asset = f'play-components/{digest}/{number:04d}'
                target = output / name / 'src/main/assets' / asset
                target.parent.mkdir(parents=True, exist_ok=True)
                with target.open('xb') as destination:
                    destination.write(block)
                piece = {'asset': asset, 'module': name, 'size': len(block), 'sha256': chunk_digest}
                pieces.append(piece)
                shared[shared_key] = piece
                number += 1
        chunks[digest] = pieces
    base = output / 'base/src/main/assets/play-components'
    recipes = base / 'recipes'
    recipes.mkdir(parents=True)
    for digest, sources in layouts.items():
        pieces = [piece for source in sources for piece in chunks[source]]
        (recipes / (digest + '.json')).write_text(json.dumps({'chunks': pieces}, separators=(',', ':')) + '\n')
    for entry in manifest['entries']:
        for url in entry['urls']:
            metadata = {'id': entry['id'], 'sha256': entry['sha256'], 'size': entry['size']}
            if entry.get('delivery') == 'windows-guest':
                metadata.update(delivery='windows-guest', download_url=entry['download_url'])
            else:
                metadata['recipe'] = 'play-components/recipes/' + entry['sha256'] + '.json'
            index['entries'][url] = metadata
    (output / 'index.json').write_text(json.dumps(index, indent=2, sort_keys=True) + '\n')
    (output / 'provenance.json').write_text(json.dumps(manifest, indent=2, sort_keys=True) + '\n')
    (base / 'index.json').write_text(json.dumps(index, sort_keys=True, separators=(',', ':')) + '\n')
    (base / 'provenance.json').write_text(json.dumps(manifest, sort_keys=True, separators=(',', ':')) + '\n')
    modules = [f'playpayload{number:02d}' for number in range(1, module + 1)]
    for name in modules:
        (output / name / 'build.gradle').write_text(
            "plugins { id 'com.android.dynamic-feature' }\n"
            "android {\n"
            f"    namespace 'com.droiddeck.launcher.{name}'\n"
            "    compileSdk 37\n"
            "    defaultConfig { minSdk 26 }\n"
            "}\n"
            "dependencies { implementation project(':app') }\n")
        (output / name / 'src/main/AndroidManifest.xml').write_text(
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
            'xmlns:dist="http://schemas.android.com/apk/distribution">\n'
            '    <dist:module dist:instant="false" dist:title="@string/app_name">\n'
            '        <dist:delivery><dist:on-demand /></dist:delivery>\n'
            '        <dist:fusing dist:include="true" />\n'
            '    </dist:module>\n'
            '    <application android:hasCode="false" />\n'
            '</manifest>\n')
    packs = [f'playdata{number:02d}' for number in range(1, data_module + 1)] if data_payloads else []
    for name in packs:
        (output / name / 'build.gradle').write_text(
            "plugins { id 'com.android.asset-pack' }\n"
            f"assetPack {{ packName = '{name}'; dynamicDelivery {{ deliveryType = 'on-demand' }} }}\n")
    (base / 'delivery.json').write_text(json.dumps({'modules': modules, 'packs': packs}, separators=(',', ':')) + '\n')
    (output / 'assetpacks.json').write_text(json.dumps(packs) + '\n')
    (output / 'modules.json').write_text(json.dumps(modules) + '\n')
    return index


def verify_bundle(manifest, bundle):
    expected = {url: entry for entry in manifest['entries'] for url in entry['urls']}
    with zipfile.ZipFile(bundle) as package:
        if len(package.namelist()) != len(set(package.namelist())):
            raise ValueError('Bundle contains duplicate ZIP entries')
        index = json.loads(package.read('base/assets/play-components/index.json'))
        provenance = json.loads(package.read('base/assets/play-components/provenance.json'))
        if provenance != manifest or index.get('schema') != 1 or set(index.get('entries', {})) != set(expected):
            raise ValueError('Bundle component inventory differs from the pinned manifest')
        canonical = hashlib.sha256(json.dumps(manifest, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
        if index.get('manifest_sha256') != canonical:
            raise ValueError('Bundle manifest identity mismatch')
        referenced = set()
        referenced_recipes = set()
        verified = set()
        for url, pinned in expected.items():
            entry = index['entries'][url]
            if any(entry.get(field) != pinned[field] for field in ['id', 'sha256', 'size']):
                raise ValueError('Bundle component metadata mismatch: ' + pinned['id'])
            if pinned.get('delivery') == 'windows-guest':
                if entry != {field: pinned[field] for field in ['id', 'sha256', 'size', 'delivery', 'download_url']}:
                    raise ValueError('Bundle Windows prerequisite metadata mismatch')
                continue
            if 'delivery' in entry or 'download_url' in entry:
                raise ValueError('Bundled native components cannot use external delivery')
            recipe = 'play-components/recipes/' + pinned['sha256'] + '.json'
            if entry.get('recipe') != recipe:
                raise ValueError('Bundle component recipe path mismatch')
            recipe_path = 'base/assets/' + recipe
            referenced_recipes.add(recipe_path)
            if package.getinfo(recipe_path).file_size > 16 * 1024 * 1024:
                raise ValueError('Bundle component recipe exceeds its size limit')
            pieces = json.loads(package.read(recipe_path)).get('chunks')
            if not isinstance(pieces, list) or not pieces:
                raise ValueError('Bundle component contains no chunks: ' + pinned['id'])
            digest = hashlib.sha256()
            size = 0
            identity = json.dumps(entry, sort_keys=True)
            logical_size = 0
            for chunk in pieces:
                boundary = pinned.get('layout', {}).get('segments', [{'size': pinned['size']}])[0]['size']
                expected_module = 'playpayload' if logical_size < boundary else 'playdata'
                if not chunk['module'].startswith(expected_module) or logical_size < boundary < logical_size + chunk['size']:
                    raise ValueError('Executable and data component delivery are mixed')
                logical_size += chunk['size']
                if not re.fullmatch(r'(?:playpayload|playdata)[0-9]{2}', chunk['module']) or not re.fullmatch(
                        r'play-components/[0-9a-f]{64}/[0-9]{4,}', chunk['asset']):
                    raise ValueError('Invalid bundle chunk path')
                path = chunk['module'] + '/assets/' + chunk['asset']
                referenced.add(path)
                if identity in verified:
                    continue
                info = package.getinfo(path)
                if info.file_size != chunk['size'] or not 0 < chunk['size'] <= CHUNK_BYTES:
                    raise ValueError('Bundle chunk size mismatch: ' + path)
                chunk_digest = hashlib.sha256()
                with package.open(info) as source:
                    for block in iter(lambda: source.read(1024 * 1024), b''):
                        digest.update(block)
                        chunk_digest.update(block)
                        size += len(block)
                if chunk_digest.hexdigest() != chunk['sha256']:
                    raise ValueError('Bundle chunk digest mismatch: ' + path)
            if identity not in verified and (digest.hexdigest() != pinned['sha256'] or size != pinned['size']):
                raise ValueError('Bundle payload failed verification: ' + pinned['id'])
            verified.add(identity)
        delivered = {name for name in package.namelist() if '/assets/play-components/' in name and
                     not name.startswith('base/assets/play-components/')}
        if delivered != referenced:
            raise ValueError('Bundle contains unreferenced component chunks')
        base_assets = {name for name in package.namelist() if name.startswith('base/assets/play-components/')}
        if base_assets != referenced_recipes | {'base/assets/play-components/index.json', 'base/assets/play-components/provenance.json', 'base/assets/play-components/delivery.json'}:
            raise ValueError('Bundle contains unreferenced component recipes')
        sizes = {}
        for info in package.infolist():
            module = info.filename.split('/')[0]
            if module == 'base' or module.startswith('playpayload'):
                sizes[module] = sizes.get(module, 0) + info.compress_size
        asset_sizes = {}
        for info in package.infolist():
            name = info.filename.split('/')[0]
            if re.fullmatch(r'playdata[0-9]{2}', name):
                asset_sizes[name] = asset_sizes.get(name, 0) + info.compress_size
        if any(size > 1_500_000_000 for size in asset_sizes.values()) or sum(asset_sizes.values()) > 30_000_000_000:
            raise ValueError('Bundle exceeds the Play asset pack budget')
        if any(size > 500_000_000 for size in sizes.values()) or sum(sizes.values()) > TOTAL_BYTES:
            raise ValueError('Bundle exceeds the conservative module download-size budget')
    return sizes


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('command', choices=['validate', 'stage', 'verify-bundle'])
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--cache', type=Path)
    parser.add_argument('--output', type=Path)
    parser.add_argument('--bundle', type=Path)
    parser.add_argument('--play-only', action='store_true')
    arguments = parser.parse_args()
    manifest = load_manifest(arguments.manifest)
    if arguments.play_only:
        validate_play_engines(manifest)
    if arguments.command == 'stage':
        if arguments.cache is None or arguments.output is None:
            parser.error('stage requires --cache and --output')
        stage(manifest, arguments.cache, arguments.output)
    if arguments.command == 'verify-bundle':
        if arguments.bundle is None:
            parser.error('verify-bundle requires --bundle')
        print(json.dumps({'compressed_module_bytes': verify_bundle(manifest, arguments.bundle)}, sort_keys=True))
    print(json.dumps({'entries': len(manifest['entries']), 'required': len(manifest['required'])}))


if __name__ == '__main__':
    main()
