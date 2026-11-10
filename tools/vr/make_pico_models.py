"""Convert the pinned WebXR PICO models and their input-driven node transforms."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import struct

import numpy as np
from PIL import Image

HERE = Path(__file__).resolve().parent
SOURCE = HERE / 'third_party/webxr-input-profiles'
OUTPUT = HERE / 'driver_droiddeck/resources/rendermodels'
REVISION = 'f4992299601614adbfefd398dc8e281556bb7444'
MODELS = {'pico-neo3': 'neo3', 'pico-4': '4', 'pico-4u': '4u'}
ANIMATION = HERE / 'src/xr_profile_animation_data.h'


def cpp_array(values):
    return '{' + ','.join(f'{float(v):.10e}f' for v in np.asarray(values).reshape(-1)) + '}'


def stick_directions(doc, parts, vertices, name):
    if not name.startswith(('droiddeck_pico_4_', 'droiddeck_pico_neo3_')):
        return (1, 1)
    nodes = doc['nodes']
    names = {node.get('name'): index for index, node in enumerate(nodes)}
    parents = {child: parent for parent, node in enumerate(nodes) for child in node.get('children', [])}
    mesh = names['joystick']
    points = np.concatenate([points for (node, _), points in zip(parts, vertices) if node == mesh])
    top = np.quantile(points[:, 1], .8)
    cap = np.r_[points[points[:, 1] >= top].mean(axis=0), 1]

    def world(index, replacements):
        parent = world(parents[index], replacements) if index in parents else np.eye(4)
        return parent @ node_matrix(replacements.get(index, nodes[index]))

    inverse_rest = np.linalg.inv(world(mesh, {}))
    signs = []
    for axis, direction in (('x', np.array([1, 0, 0])), ('y', np.array([0, 0, -1]))):
        stem = 'xr_standard_thumbstick_' + axis + 'axis_pressed'
        joint = names[stem + '_value']
        endpoints = [
            (world(mesh, {joint: nodes[names[stem + '_' + endpoint]]}) @ inverse_rest @ cap)[:3]
            for endpoint in ('min', 'max')
        ]
        displacement = endpoints[1] - endpoints[0]
        projection = float(displacement @ direction)
        assert np.isfinite(displacement).all() and abs(projection) > 1e-6, (name, axis, displacement)
        assert abs(projection) > np.linalg.norm(displacement - projection * direction), (name, axis, displacement)
        signs.append(1 if projection > 0 else -1)
    return tuple(signs)


def animation_data(doc, parts, vertices, name):
    channels = {
        'xr_standard_trigger_pressed': 'Trigger',
        'xr_standard_squeeze_pressed': 'Squeeze',
        'xr_standard_thumbstick_pressed': 'StickClick',
        'xr_standard_thumbstick_xaxis_pressed': 'StickX',
        'xr_standard_thumbstick_yaxis_pressed': 'StickY',
        'a_button_pressed': 'Primary', 'x_button_pressed': 'Primary',
        'b_button_pressed': 'Secondary', 'y_button_pressed': 'Secondary',
        'menu_button_pressed': 'Menu', 'LMenu_pressed': 'Menu',
    }
    nodes = doc['nodes']
    by_name = {node.get('name'): node for node in nodes}
    parents = {child: parent for parent, node in enumerate(nodes) for child in node.get('children', [])}
    lines = [f'inline constexpr ProfileNode {name}_nodes[] = {{']
    for index, node in enumerate(nodes):
        stem = node.get('name', '').removesuffix('_value')
        channel = channels.get(stem, 'None') if node.get('name', '').endswith('_value') else 'None'
        minimum = by_name[stem + '_min'] if channel != 'None' else {}
        maximum = by_name[stem + '_max'] if channel != 'None' else {}
        lines.append('{' + f'{parents.get(index, -1)},' + cpp_array(node_matrix(node)) +
                     f',ProfileChannel::{channel},' +
                     cpp_array(minimum.get('translation', [0, 0, 0])) + ',' +
                     cpp_array(minimum.get('rotation', [0, 0, 0, 1])) + ',' +
                     cpp_array(maximum.get('translation', [0, 0, 0])) + ',' +
                     cpp_array(maximum.get('rotation', [0, 0, 0, 1])) + '},')
    lines += ['};', f'inline constexpr ProfilePart {name}_parts[] = {{']
    for node, rest in parts:
        lines.append('{' + str(node) + ',' + cpp_array(np.linalg.inv(rest)) + '},')
    directions = stick_directions(doc, parts, vertices, name)
    lines += ['};', f'inline constexpr ProfileAsset {name} = {{',
              f'{name}_nodes, {len(nodes)}, {name}_parts, {len(parts)}, '
              + 'true, '
              + '{' + ', '.join(f'{value}.f' for value in directions) + '}', '};']
    return '\n'.join(lines)


def node_matrix(node):
    if 'matrix' in node:
        return np.array(node['matrix']).reshape(4, 4, order='F')
    x, y, z, w = node.get('rotation', [0, 0, 0, 1])
    matrix = np.eye(4)
    matrix[:3, :3] = [
        [1 - 2 * (y*y + z*z), 2 * (x*y - z*w), 2 * (x*z + y*w)],
        [2 * (x*y + z*w), 1 - 2 * (x*x + z*z), 2 * (y*z - x*w)],
        [2 * (x*z - y*w), 2 * (y*z + x*w), 1 - 2 * (x*x + y*y)],
    ]
    matrix[:3, :3] *= np.array(node.get('scale', [1, 1, 1]))
    matrix[:3, 3] = node.get('translation', [0, 0, 0])
    return matrix


def convert(path, name):
    raw = path.read_bytes()
    magic, version, size = struct.unpack_from('<4sII', raw)
    assert magic == b'glTF' and version == 2 and size == len(raw)
    json_size, json_type = struct.unpack_from('<II', raw, 12)
    assert json_type == 0x4e4f534a
    doc = json.loads(raw[20:20 + json_size])
    offset = 20 + json_size
    binary_size, binary_type = struct.unpack_from('<II', raw, offset)
    assert binary_type == 0x004e4942
    binary = raw[offset + 8:offset + 8 + binary_size]

    def view(index):
        definition = doc['bufferViews'][index]
        assert definition['buffer'] == 0
        start = definition.get('byteOffset', 0)
        return binary[start:start + definition['byteLength']]

    def accessor(index):
        definition = doc['accessors'][index]
        assert 'sparse' not in definition and not definition.get('normalized')
        dtype = {5121: 'u1', 5123: '<u2', 5125: '<u4', 5126: '<f4'}[definition['componentType']]
        columns = {'SCALAR': 1, 'VEC2': 2, 'VEC3': 3, 'VEC4': 4}[definition['type']]
        stride = doc['bufferViews'][definition['bufferView']].get('byteStride', np.dtype(dtype).itemsize * columns)
        return np.ndarray((definition['count'], columns), dtype=dtype,
                          buffer=view(definition['bufferView']), offset=definition.get('byteOffset', 0),
                          strides=(stride, np.dtype(dtype).itemsize)).copy()

    files, components, bounds, parts = {}, {}, [], []
    for index, material in enumerate(doc.get('materials', [])):
        pbr = material.get('pbrMetallicRoughness', {})
        factor = np.array(pbr.get('baseColorFactor', [1, 1, 1, 1]))
        texture = pbr.get('baseColorTexture')
        if texture:
            assert texture.get('texCoord', 0) == 0 and not texture.get('extensions')
            image = doc['images'][doc['textures'][texture['index']]['source']]
            pixels = np.array(Image.open(io.BytesIO(view(image['bufferView']))).convert('RGBA'))
        else:
            pixels = np.full((4, 4, 4), 255, dtype=np.uint8)
        if not np.all(factor == 1):
            srgb = pixels[..., :3] / 255.
            linear = np.where(srgb <= .04045, srgb / 12.92, ((srgb + .055) / 1.055) ** 2.4)
            linear *= factor[:3]
            srgb = np.where(linear <= .0031308, linear * 12.92, 1.055 * linear ** (1/2.4) - .055)
            pixels[..., :3] = np.rint(np.clip(srgb, 0, 1) * 255).astype(np.uint8)
            pixels[..., 3] = np.rint(pixels[..., 3] * factor[3]).astype(np.uint8)
        stream = io.BytesIO()
        Image.fromarray(pixels).save(stream, format='PNG', compress_level=9)
        files[f'material_{index}.png'] = stream.getvalue()
        files[f'material_{index}.mtl'] = (
            f'newmtl material_{index}\nKa 1 1 1\nKd 1 1 1\nKs 0 0 0\nd 1\n'
            f'map_Kd material_{index}.png\n').encode()

    def visit(index, parent):
        node = doc['nodes'][index]
        matrix = parent @ node_matrix(node)
        if 'mesh' in node:
            for primitive in doc['meshes'][node['mesh']]['primitives']:
                assert primitive.get('mode', 4) == 4
                attributes = primitive['attributes']
                positions = accessor(attributes['POSITION'])
                normals = accessor(attributes['NORMAL'])
                texcoords = (accessor(attributes['TEXCOORD_0']) if 'TEXCOORD_0' in attributes
                             else np.zeros((len(positions), 2)))
                positions = positions @ matrix[:3, :3].T + matrix[:3, 3]
                normals = normals @ np.linalg.inv(matrix[:3, :3])
                normals /= np.linalg.norm(normals, axis=1)[:, None]
                indices = (accessor(primitive['indices']).reshape(-1, 3) if 'indices' in primitive
                           else np.arange(len(positions)).reshape(-1, 3))
                if np.linalg.det(matrix[:3, :3]) < 0:
                    indices = indices[:, [0, 2, 1]]
                assert np.isfinite(positions).all() and np.isfinite(normals).all()
                assert indices.max() < len(positions)
                bounds.append(positions)
                part = f'part_{len(components):02d}'
                material = primitive.get('material', 0)
                lines = [f'mtllib material_{material}.mtl', f'usemtl material_{material}']
                lines.extend('v ' + ' '.join(f'{v:.8f}' for v in p) for p in positions)
                lines.extend(f'vt {uv[0]:.8f} {1-uv[1]:.8f}' for uv in texcoords)
                lines.extend('vn ' + ' '.join(f'{v:.8f}' for v in n) for n in normals)
                lines.extend('f ' + ' '.join(f'{i+1}/{i+1}/{i+1}' for i in triangle) for triangle in indices)
                files[part + '.obj'] = ('\n'.join(lines) + '\n').encode()
                components[part] = {'filename': part + '.obj',
                                    'motion': {'type': 'pose_component',
                                               'pose_path': f'/pose/profile_{len(parts)}'}}
                parts.append((index, matrix))
        for child in node.get('children', []):
            visit(child, matrix)

    for root in doc['scenes'][doc.get('scene', 0)]['nodes']:
        visit(root, np.eye(4))
    for pose in ('grip', 'handgrip', 'tip', 'openxr_grip', 'openxr_aim'):
        components[pose] = {'motion': {'type': 'pose_component', 'pose_path': '/pose/' + pose},
                            'component_local': {'origin': [0, 0, 0], 'rotate_xyz': [0, 0, 0]}}
    files[name + '.json'] = (json.dumps({'components': components}, indent=2) + '\n').encode()
    files['LICENSE.txt'] = (SOURCE / 'LICENSE.md').read_bytes().replace(b'\r\n', b'\n')
    vertices = np.concatenate(bounds)
    extent = vertices.max(axis=0) - vertices.min(axis=0)
    assert .05 < extent.max() < .4, (path, extent)
    metadata = {'source': f'packages/assets/profiles/{path.parent.name}/{path.name}',
                'sha256': hashlib.sha256(raw).hexdigest(), 'components': len(bounds),
                'bounds_m': [vertices.min(axis=0).tolist(), vertices.max(axis=0).tolist()]}
    return files, metadata, animation_data(doc, parts, bounds, name)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='Verify checked-in resources without writing')
    args = parser.parse_args()
    provenance = {'repository': 'https://github.com/immersive-web/webxr-input-profiles',
                  'revision': REVISION, 'license': 'MIT, Copyright (c) 2019 Amazon', 'models': {}}
    animation = ['#pragma once', 'namespace droiddeck {']
    for profile, family in MODELS.items():
        for side in ('left', 'right'):
            name = f'droiddeck_pico_{family}_{side}'
            files, metadata, data = convert(SOURCE / profile / (side + '.glb'), name)
            animation.append(data)
            provenance['models'][name] = metadata
            directory = OUTPUT / name
            if args.check:
                assert {p.name for p in directory.iterdir()} == set(files), name
                for filename, data in files.items():
                    assert (directory / filename).read_bytes() == data, f'{name}/{filename}'
            else:
                directory.mkdir(parents=True, exist_ok=True)
                for filename, data in files.items():
                    (directory / filename).write_bytes(data)
            print(f'{name}: {metadata["components"]} components, bounds {metadata["bounds_m"]}')
    animation += ['inline const ProfileAsset *XrProfileAsset(uint32_t profile, bool right) {', 'switch (profile) {']
    for enum, family in (('PICO_NEO3', 'neo3'), ('PICO4', '4'), ('PICO4S', '4u')):
        animation.append(f'case DROIDDECK_XR_CONTROLLER_{enum}: return right ? '
                         f'&droiddeck_pico_{family}_right : &droiddeck_pico_{family}_left;')
    animation += ['default: return nullptr;', '}', '}', '}']
    data = ('\n'.join(animation) + '\n').encode()
    if args.check:
        assert ANIMATION.read_bytes() == data
    else:
        ANIMATION.write_bytes(data)
    data = (json.dumps(provenance, indent=2, sort_keys=True) + '\n').encode()
    path = SOURCE / 'provenance.json'
    if args.check:
        assert path.read_bytes() == data
    else:
        path.write_bytes(data)


if __name__ == '__main__':
    main()
