"""Build the pinned lite-palm/lite-landmark MediaPipe bundle (mediapipe==0.10.21)."""
import argparse
import hashlib
import io
from pathlib import Path
import zipfile

FULL_SHA = 'fbc2a30080c3c557093b5ddfc334698132eb341044ccee322ccf8bcf3607cde1'
LITE_SHA = 'd7fde8ac11f8ce03f8663775bfc323f4fc9f2a38062b4f4efa142874ef5b2a48'
PALM_SHA = 'e9a4aaddf90dda56a87235303cf00e4c2d3fb28725f68fd88772997dac905c18'

def write_entry(archive, name, data):
    entry = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
    entry.create_system = 3
    entry.external_attr = 0o100644 << 16
    archive.writestr(entry, data)

def canonicalize_associated_files(data):
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        entries = archive.infolist()
        if not entries:
            return data
        prefix = data[:min(entry.header_offset for entry in entries)]
        files = {entry.filename: archive.read(entry) for entry in entries}
    output = io.BytesIO(prefix)
    with zipfile.ZipFile(output, 'a', compression=zipfile.ZIP_STORED) as archive:
        for name in sorted(files):
            write_entry(archive, name, files[name])
    return output.getvalue()

def build(full, lite, palm, output):
    import mediapipe
    from mediapipe.tasks.python.metadata import metadata
    from mediapipe.tasks.metadata import schema_py_generated as schema
    if mediapipe.__version__ != '0.10.21':
        raise ValueError('Use mediapipe==0.10.21 for reproducible metadata')
    for path, expected in [(full, FULL_SHA), (lite, LITE_SHA), (palm, PALM_SHA)]:
        if hashlib.sha256(path.read_bytes()).hexdigest() != expected:
            raise ValueError(f'Model source checksum mismatch: {path}')
    with zipfile.ZipFile(full) as task:
        references = {name: task.read(name) for name in ['hand_detector.tflite', 'hand_landmarks_detector.tflite']}
    def contract(data):
        graph = schema.Model.GetRootAsModel(data, 0).Subgraphs(0)
        return [[(graph.Tensors(int(i)).Name(), graph.Tensors(int(i)).ShapeAsNumpy().tolist(), graph.Tensors(int(i)).Type())
                 for i in indices] for indices in (graph.InputsAsNumpy(), graph.OutputsAsNumpy())]
    models = {}
    for name, path in [('hand_detector.tflite', palm), ('hand_landmarks_detector.tflite', lite)]:
        data = path.read_bytes()
        reference = references[name]
        if contract(data) != contract(reference):
            raise ValueError(f'Lite tensor contract differs: {name}')
        populator = metadata.MetadataPopulator.with_model_buffer(data)
        populator.load_metadata_and_associated_files(reference)
        populator.populate()
        models[name] = canonicalize_associated_files(populator.get_model_buffer())
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, 'w', compression=zipfile.ZIP_STORED) as task:
        for name, data in models.items():
            write_entry(task, name, data)
    print(hashlib.sha256(output.read_bytes()).hexdigest())

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--full', type=Path, default=Path('app/src/main/assets/models/hand_landmarker.task'))
    parser.add_argument('--lite', type=Path, required=True, help='Pinned hand_landmark_lite.tflite from storage.googleapis.com/mediapipe-assets/')
    parser.add_argument('--palm', type=Path, required=True, help='Pinned palm_detection_lite.tflite from storage.googleapis.com/mediapipe-assets/')
    parser.add_argument('--output', type=Path, default=Path('app/src/main/assets/models/hand_landmarker_lite.task'))
    args = parser.parse_args()
    build(args.full, args.lite, args.palm, args.output)
